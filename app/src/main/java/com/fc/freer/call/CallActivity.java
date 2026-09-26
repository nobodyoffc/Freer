package com.fc.freer.call;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fc.fc_ajdk.data.fcData.TalkPartner;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.ToolbarUtils;

import java.util.Locale;

/**
 * The call screen (VOICE_SPEC §10): calling, ringing, and in a call. It
 * always says the call is end-to-end encrypted and how it travels, and warns
 * when audio claimed to be from someone could not be verified (§5.1). All
 * state lives in {@link CallManager}; this only shows it.
 */
public class CallActivity extends AppCompatActivity implements CallManager.Listener {

    private static final long CLOSE_AFTER_END_MS = 1_500;

    private CallManager calls;
    private final androidx.activity.result.ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) calls.answer();
                        else android.widget.Toast.makeText(this, R.string.call_need_mic,
                                android.widget.Toast.LENGTH_LONG).show();
                    });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::render;
    private TextView peer, status, path, warning;
    private CheckBox mute, speaker;
    private Button answer, hangup;
    private View toggles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Incoming calls show over the lock screen and wake it (§11.1).
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        setContentView(R.layout.activity_call);
        ToolbarUtils.setupToolbar(this, getString(R.string.call_title));
        calls = CallManager.getInstance(this);

        peer = findViewById(R.id.callPeer);
        status = findViewById(R.id.callStatus);
        path = findViewById(R.id.callPath);
        warning = findViewById(R.id.callWarning);
        mute = findViewById(R.id.callMute);
        speaker = findViewById(R.id.callSpeaker);
        answer = findViewById(R.id.callAnswer);
        hangup = findViewById(R.id.callHangup);
        toggles = findViewById(R.id.callToggles);

        answer.setOnClickListener(v -> {
            if (calls.phase() == CallManager.Phase.ENDED && calls.topUpRelay() != null) {
                confirmTopUp(calls.topUpRelay());
                return;
            }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                calls.answer();
            } else {
                micPermission.launch(android.Manifest.permission.RECORD_AUDIO);
            }
        });
        hangup.setOnClickListener(v -> {
            CallManager.Phase phase = calls.phase();
            if (phase == CallManager.Phase.ENDED || phase == CallManager.Phase.IDLE) finish();
            else if (phase == CallManager.Phase.RINGING_IN) calls.decline();
            else calls.hangup();
        });
        mute.setOnClickListener(v -> calls.setMuted(mute.isChecked()));
        speaker.setOnClickListener(v -> calls.setSpeaker(speaker.isChecked()));
    }

    @Override
    protected void onStart() {
        super.onStart();
        calls.addListener(this);
        render();
    }

    @Override
    protected void onStop() {
        calls.removeListener(this);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    public void onCallChanged() {
        render();
    }

    private void render() {
        handler.removeCallbacks(tick);
        CallManager.Phase phase = calls.phase();
        String fid = calls.peerFid();
        peer.setText(fid == null ? "" : displayName(fid));

        switch (phase) {
            case CALLING -> status.setText(R.string.call_status_calling);
            case RINGING_IN -> status.setText(R.string.call_status_incoming);
            case CONNECTING -> status.setText(R.string.call_status_connecting);
            case CONNECTED -> {
                status.setText(duration(System.currentTimeMillis() - calls.connectedAtMs()));
                handler.postDelayed(tick, 1_000);
            }
            case ENDED -> status.setText(calls.endReason() == null ? getString(R.string.call_end_ended)
                    : calls.endReason());
            case IDLE -> status.setText("");
        }

        String relay = calls.relayHost();
        long rtt = calls.rttMs();
        if (calls.isDirect()) {
            path.setText(rtt >= 0 ? getString(R.string.call_direct_rtt, rtt) : getString(R.string.call_direct));
        } else {
            String via = relay == null ? "" : rtt >= 0
                    ? getString(R.string.call_relayed_via_rtt, relay, rtt)
                    : getString(R.string.call_relayed_via, relay);
            Double cost = calls.callerCostPerMinute(); // shown to the caller, who pays (§7.5)
            if (cost != null && !via.isEmpty()) {
                via += " · " + (cost == 0 ? getString(R.string.call_free)
                        : getString(R.string.call_cost_per_minute, String.format(Locale.US, "%.6f", cost)));
            }
            path.setText(via);
        }

        String unverified = calls.unverifiedFid();
        boolean silent = calls.peerSilent() && phase == CallManager.Phase.CONNECTED;
        warning.setVisibility(unverified == null && !silent ? View.GONE : View.VISIBLE);
        if (unverified != null) warning.setText(getString(R.string.call_unverified, displayName(unverified)));
        else if (silent) warning.setText(getString(R.string.call_peer_silent, fid == null ? "" : displayName(fid)));

        boolean ringingIn = phase == CallManager.Phase.RINGING_IN;
        boolean live = phase == CallManager.Phase.CONNECTING || phase == CallManager.Phase.CONNECTED;
        boolean topUp = phase == CallManager.Phase.ENDED && calls.topUpRelay() != null;
        answer.setText(topUp ? R.string.call_top_up : R.string.call_answer);
        answer.setVisibility(ringingIn || topUp ? View.VISIBLE : View.GONE);
        boolean over = phase == CallManager.Phase.ENDED || phase == CallManager.Phase.IDLE;
        boolean keepOpen = phase == CallManager.Phase.ENDED && calls.endedWithFailure();
        hangup.setText(keepOpen ? R.string.call_close : ringingIn ? R.string.call_decline : R.string.call_hang_up);
        hangup.setVisibility(!over || keepOpen ? View.VISIBLE : View.GONE);
        toggles.setVisibility(live || phase == CallManager.Phase.CALLING ? View.VISIBLE : View.GONE);
        mute.setChecked(calls.isMuted());
        speaker.setChecked(calls.isSpeaker());

        if (over && !keepOpen) {
            handler.postDelayed(this::finish, CLOSE_AFTER_END_MS);
        }
    }

    /** Paying moves the user's money: ask first, and say where it goes. */
    private void confirmTopUp(String relayUrl) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.call_top_up)
                .setMessage(getString(R.string.call_top_up_confirm, relayUrl))
                .setPositiveButton(R.string.call_top_up, (d, w) -> {
                    answer.setEnabled(false);
                    calls.topUp(relayUrl, error -> {
                        answer.setEnabled(true);
                        android.widget.Toast.makeText(this, error == null ? getString(R.string.call_top_up_done)
                                : getString(R.string.call_top_up_failed, error), android.widget.Toast.LENGTH_LONG).show();
                        if (error == null) finish();
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String displayName(String fid) {
        ImManager im = FidManager.getInstance().getImManager();
        TalkPartner p = im == null ? null : im.getTalkPartner(fid);
        String cid = p == null ? null : p.getCid();
        return cid != null && !cid.isEmpty() ? cid : StringUtils.omitMiddle(fid, 20);
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
