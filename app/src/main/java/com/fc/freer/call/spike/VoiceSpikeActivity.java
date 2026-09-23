package com.fc.freer.call.spike;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.call.engine.CaptureEngine;
import com.fc.freer.call.engine.JitterBuffer;
import com.fc.freer.call.engine.PlayoutEngine;
import com.fc.freer.utils.ToolbarUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 2 audio spike (VOICE_SPEC §14): two phones on direct FUDP datagrams,
 * or any number through the throwaway relay, with live stats for the gate
 * tests. Debug builds only; not a product screen.
 * <p>
 * Scriptable for test runs: the settings can come as extras, and the stats
 * go to logcat (tag VoiceSpikeActivity) every 5 s. For example:
 * <pre>
 * adb shell am start -n com.fc.freer/.call.spike.VoiceSpikeActivity \
 *     --es mode relay --es host 203.0.113.7 --ei frameMs 40 --ez dtx false \
 *     --ei simLoss 5 --ez autostart true
 * </pre>
 * {@code mode} is host, call or relay; also {@code room}, {@code kbps},
 * {@code fecLoss} and {@code speaker}.
 */
public class VoiceSpikeActivity extends AppCompatActivity {

    private static final String TAG = "VoiceSpikeActivity";

    private RadioGroup modeGroup, frameGroup;
    private EditText roomCode, remoteHost, bitrate, expectedLoss, simulatedLoss;
    private CheckBox dtx, mute, speaker;
    private Button startStop;
    private TextView status, stats, localAddresses;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshStats;

    private SpikeTransport transport;
    private CaptureEngine capture;
    private PlayoutEngine playout;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private int previousAudioMode;
    private long startedAt, lastBytes, lastBytesAt;
    private int refreshes;

    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) start();
                else Toast.makeText(this, R.string.voice_spike_need_mic, Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_spike);
        ToolbarUtils.setupToolbar(this, getString(R.string.voice_spike_title));
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        modeGroup = findViewById(R.id.modeGroup);
        frameGroup = findViewById(R.id.frameGroup);
        roomCode = findViewById(R.id.roomCode);
        remoteHost = findViewById(R.id.remoteHost);
        bitrate = findViewById(R.id.bitrate);
        expectedLoss = findViewById(R.id.expectedLoss);
        simulatedLoss = findViewById(R.id.simulatedLoss);
        dtx = findViewById(R.id.dtx);
        mute = findViewById(R.id.mute);
        speaker = findViewById(R.id.speaker);
        startStop = findViewById(R.id.startStop);
        status = findViewById(R.id.status);
        stats = findViewById(R.id.stats);
        localAddresses = findViewById(R.id.localAddresses);

        localAddresses.setText(getString(R.string.voice_spike_local_addresses,
                String.join(", ", localIpv4()), SpikeTransport.PHONE_PORT));
        mute.setOnCheckedChangeListener((b, on) -> {
            if (capture != null) capture.setMuted(on);
        });
        speaker.setOnCheckedChangeListener((b, on) -> setSpeaker(on));
        applyExtras();
        startStop.setOnClickListener(v -> {
            if (transport != null) stop();
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) start();
            else micPermission.launch(Manifest.permission.RECORD_AUDIO);
        });
    }

    private void applyExtras() {
        android.content.Intent in = getIntent();
        String mode = in.getStringExtra("mode");
        if ("call".equals(mode)) modeGroup.check(R.id.modeCall);
        else if ("relay".equals(mode)) modeGroup.check(R.id.modeRelay);
        else if ("host".equals(mode)) modeGroup.check(R.id.modeHost);
        if (in.hasExtra("host")) remoteHost.setText(in.getStringExtra("host"));
        if (in.hasExtra("room")) roomCode.setText(in.getStringExtra("room"));
        if (in.hasExtra("frameMs")) frameGroup.check(in.getIntExtra("frameMs", 40) == 20 ? R.id.frame20 : R.id.frame40);
        if (in.hasExtra("kbps")) bitrate.setText(String.valueOf(in.getIntExtra("kbps", 24)));
        if (in.hasExtra("fecLoss")) expectedLoss.setText(String.valueOf(in.getIntExtra("fecLoss", 10)));
        if (in.hasExtra("simLoss")) simulatedLoss.setText(String.valueOf(in.getIntExtra("simLoss", 0)));
        if (in.hasExtra("dtx")) dtx.setChecked(in.getBooleanExtra("dtx", true));
        if (in.hasExtra("speaker")) speaker.setChecked(in.getBooleanExtra("speaker", false));
        if (in.getBooleanExtra("autostart", false)) ui.post(startStop::performClick);
    }

    private void start() {
        int checked = modeGroup.getCheckedRadioButtonId();
        SpikeTransport.Mode mode = checked == R.id.modeCall ? SpikeTransport.Mode.CALL
                : checked == R.id.modeRelay ? SpikeTransport.Mode.RELAY
                : SpikeTransport.Mode.HOST;
        String host = remoteHost.getText().toString().trim();
        if (mode != SpikeTransport.Mode.HOST && host.isEmpty()) {
            Toast.makeText(this, R.string.voice_spike_need_host, Toast.LENGTH_LONG).show();
            return;
        }
        int frameMs = frameGroup.getCheckedRadioButtonId() == R.id.frame20 ? 20 : 40;
        CaptureEngine.Settings settings = new CaptureEngine.Settings(frameMs,
                intOf(bitrate, 24) * 1000, dtx.isChecked(), intOf(expectedLoss, 10));

        playout = new PlayoutEngine(frameMs);
        transport = new SpikeTransport(this, mode, roomCode.getText().toString().trim(), host,
                new SpikeTransport.Listener() {
                    @Override
                    public void onFrame(com.fc.freer.call.engine.SpikeFrame frame, long arrivalMs) {
                        PlayoutEngine p = playout;
                        if (p != null) p.onFrame(frame, arrivalMs);
                    }

                    @Override
                    public void onStatus(String s) {
                        ui.post(() -> status.setText(s));
                    }
                });
        transport.setSimulatedLoss(intOf(simulatedLoss, 0) / 100.0);
        capture = new CaptureEngine(settings, transport::send);
        capture.setMuted(mute.isChecked());
        setInputsEnabled(false);
        startStop.setText(R.string.voice_spike_stop);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SpikeTransport t = transport;
        io.execute(() -> {
            try {
                t.start();
                ui.post(() -> {
                    if (transport != t) return;
                    try {
                        enterCallAudio();
                        playout.start();
                        capture.start();
                    } catch (RuntimeException e) {
                        // AudioRecord or AudioTrack refused, e.g. the mic is held by another app
                        TimberLogger.e(TAG, "audio start failed: %s", android.util.Log.getStackTraceString(e));
                        Toast.makeText(this, getString(R.string.voice_spike_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                        stop();
                        return;
                    }
                    startedAt = lastBytesAt = System.currentTimeMillis();
                    lastBytes = 0;
                    ui.post(refresh);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "start failed: %s", e.getMessage());
                ui.post(() -> {
                    Toast.makeText(this, getString(R.string.voice_spike_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    stop();
                });
            }
        });
    }

    private void stop() {
        ui.removeCallbacks(refresh);
        if (capture != null) capture.stop();
        if (playout != null) playout.stop();
        SpikeTransport t = transport;
        if (t != null) io.execute(t::close);
        capture = null;
        playout = null;
        transport = null;
        leaveCallAudio();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setInputsEnabled(true);
        startStop.setText(R.string.voice_spike_start);
    }

    @Override
    protected void onDestroy() {
        if (transport != null) stop();
        io.shutdown();
        super.onDestroy();
    }

    // ===== Audio routing (§9.2) =====

    private void enterCallAudio() {
        previousAudioMode = audio.getMode();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .build();
        audio.requestAudioFocus(focus);
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        setSpeaker(speaker.isChecked());
    }

    private void leaveCallAudio() {
        if (focus == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice();
        else audio.setSpeakerphoneOn(false);
        audio.setMode(previousAudioMode);
        audio.abandonAudioFocusRequest(focus);
        focus = null;
    }

    @SuppressWarnings("deprecation")
    private void setSpeaker(boolean on) {
        if (focus == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!on) {
                audio.clearCommunicationDevice();
                return;
            }
            for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audio.setCommunicationDevice(d);
                    return;
                }
            }
        } else {
            audio.setSpeakerphoneOn(on);
        }
    }

    // ===== Stats =====

    private void refreshStats() {
        if (transport == null || capture == null || playout == null) return;
        long now = System.currentTimeMillis();
        long bytes = capture.bytesSent();
        double kbps = (bytes - lastBytes) * 8.0 / Math.max(1, now - lastBytesAt);
        lastBytes = bytes;
        lastBytesAt = now;
        long rtt = transport.rttMs();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%s  %ds  RTT %s ms%n", transport.mode(), (now - startedAt) / 1000,
                rtt < 0 ? "-" : String.valueOf(rtt)));
        sb.append(String.format(Locale.US, "send  ssrc %08x  level -%d dBov  %.1f kbps%n",
                capture.ssrc(), capture.level(), kbps));
        sb.append(String.format(Locale.US, "      frames %d  dtx-skipped %d  dropped %d%n",
                capture.framesSent(), capture.dtxSkipped(), transport.sendDrops()));
        sb.append(String.format(Locale.US, "      effects: %s%n", capture.effects()));
        sb.append(String.format(Locale.US, "device  mic->app %s ms   app->speaker %s ms%n",
                msOrDash(capture.inputLatencyMs()), msOrDash(playout.outputLatencyMs())));
        sb.append(String.format(Locale.US, "recv  datagrams %d  sim-dropped %d  track buffer %d ms (underruns %d)%n",
                transport.received(), transport.simulatedDrops(), playout.trackBufferMs(), playout.underruns()));
        if (playout.wrongFrameSize() > 0) {
            sb.append(String.format(Locale.US, "      %d frames of another size: use the same frame size on"
                    + " every phone%n", playout.wrongFrameSize()));
        }
        for (PlayoutEngine.Stream s : playout.streams().values()) {
            JitterBuffer.Stats st = s.buffer.stats();
            long quiet = (android.os.SystemClock.elapsedRealtime() - s.buffer.lastArrivalMs()) / 1000;
            sb.append(String.format(Locale.US, "%nfrom  ssrc %08x  level -%d dBov%s%n", s.ssrc, s.level(),
                    quiet >= 2 ? "  (silent " + quiet + "s)" : ""));
            sb.append(String.format(Locale.US, "      loss %.1f%%  fec %d  concealed %d  late %d%n",
                    st.lossPercent(), st.fecRecovered(), st.concealed(), st.late()));
            sb.append(String.format(Locale.US, "      buffer %d ms / target %d  stretched %d  skipped %d  dtx %d%n",
                    st.depthMs(), st.targetMs(), st.stretched(), st.skipped(), st.dtxGap()));
            // This phone's share of mouth-to-ear, from network arrival to sound. Add
            // the other phone's "mic->app" and one frame for its whole path.
            if (rtt >= 0) {
                int out = playout.outputLatencyMs() >= 0 ? playout.outputLatencyMs() : playout.trackBufferMs();
                sb.append(String.format(Locale.US, "      network ~%d + jitter buffer %d + speaker path %d = %d ms"
                                + " (+ sender's mic->app + %d ms frame)%n",
                        rtt / 2, st.depthMs(), out, rtt / 2 + st.depthMs() + out, frameMsOf()));
            }
        }
        stats.setText(sb.toString());
        if (++refreshes % 10 == 0) TimberLogger.i(TAG, "stats%n%s", sb);
        ui.postDelayed(refresh, 500);
    }

    private static String msOrDash(int ms) {
        return ms < 0 ? "-" : String.valueOf(ms);
    }

    private int frameMsOf() {
        return frameGroup.getCheckedRadioButtonId() == R.id.frame20 ? 20 : 40;
    }

    private void setInputsEnabled(boolean on) {
        for (int i = 0; i < modeGroup.getChildCount(); i++) modeGroup.getChildAt(i).setEnabled(on);
        for (int i = 0; i < frameGroup.getChildCount(); i++) frameGroup.getChildAt(i).setEnabled(on);
        roomCode.setEnabled(on);
        remoteHost.setEnabled(on);
        bitrate.setEnabled(on);
        expectedLoss.setEnabled(on);
        simulatedLoss.setEnabled(on);
        dtx.setEnabled(on);
    }

    private static int intOf(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> localIpv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // shown as empty
        }
        return out;
    }
}
