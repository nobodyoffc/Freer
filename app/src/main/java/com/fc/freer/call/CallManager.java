package com.fc.freer.call;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.call.engine.AudioIo;
import com.fc.freer.im.ImManager;
import com.fc.freer.manager.FidManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * The device's one call at a time (VOICE_SPEC §10, §11.1): places and answers
 * calls, rings for incoming ones, runs the {@link CallSession}, the foreground
 * {@link CallService} and the audio routing, and gives {@link CallActivity}
 * one state to show. Everything it tells the UI happens on the main thread.
 */
public final class CallManager implements CallSignaller.Listener {

    private static final String TAG = "CallManager";
    static final String CHANNEL_INCOMING = "calls_incoming";
    static final int NOTIFY_INCOMING = 7101;
    private static final String PREFS = "calls";
    private static final String PREF_RELAY = "relay_override";

    public enum Phase { IDLE, CALLING, RINGING_IN, CONNECTING, CONNECTED, ENDED }

    public interface Listener {
        void onCallChanged();
    }

    private static CallManager instance;

    public static synchronized CallManager getInstance(Context context) {
        if (instance == null) instance = new CallManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CallAudio audio;
    private CallSignaller signaller;
    private String myFid;

    // State for the UI, touched on the main thread only.
    private Phase phase = Phase.IDLE;
    private CallSignaller.Call call;
    private CallSession session;
    private long connectedAtMs = -1;
    private String endReason;
    private String unverifiedFid;
    private boolean speaker;
    private Ringtone ringtone;

    private CallManager(Context context) {
        this.context = context;
        this.audio = new CallAudio(context);
    }

    /** Called by ImManager when an identity's signaller starts. */
    public void attach(String myFid, CallSignaller signaller) {
        main.post(() -> {
            this.myFid = myFid;
            this.signaller = signaller;
            signaller.setListener(this);
        });
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ===== State for the UI =====

    public Phase phase() {
        return phase;
    }

    public String peerFid() {
        return call == null ? null : call.peerFid;
    }

    public String relayHost() {
        if (call == null || call.relayUrl == null) return null;
        String u = call.relayUrl.replaceFirst("^[a-z]+://", "");
        int slash = u.indexOf('/');
        return slash > 0 ? u.substring(0, slash) : u;
    }

    public long connectedAtMs() {
        return connectedAtMs;
    }

    public String endReason() {
        return endReason;
    }

    public String unverifiedFid() {
        return unverifiedFid;
    }

    public boolean isMuted() {
        return session != null && session.isMuted();
    }

    public boolean isSpeaker() {
        return speaker;
    }

    public long rttMs() {
        return session == null ? -1 : session.rttMs();
    }

    /** A relay address to use for every call, ahead of the homes on chain: for testing. */
    public String relayOverride() {
        return prefs().getString(PREF_RELAY, "");
    }

    public void setRelayOverride(String url) {
        prefs().edit().putString(PREF_RELAY, url == null ? "" : url.trim()).apply();
    }

    // ===== Actions =====

    /** Ring {@code peerFid}. Resolving the relay touches the network, so it runs off the main thread. */
    public void placeCall(String peerFid) {
        if (signaller == null || phase != Phase.IDLE && phase != Phase.ENDED) return;
        reset();
        phase = Phase.CALLING;
        notifyUi();
        Executors.newSingleThreadExecutor().execute(() -> {
            String relay = relayOverride();
            if (relay.isEmpty()) {
                ImManager im = FidManager.getInstance().getImManager();
                relay = im == null ? null : im.resolveCallRelay(peerFid);
            }
            String relayUrl = relay;
            main.post(() -> {
                if (phase != Phase.CALLING || call != null) return; // hung up while we looked
                if (relayUrl == null || relayUrl.isEmpty()) {
                    finish(context.getString(R.string.call_end_no_relay));
                    return;
                }
                try {
                    call = signaller.invite(peerFid, relayUrl, null);
                } catch (IllegalStateException e) {
                    finish(context.getString(R.string.call_end_busy_here));
                    return;
                }
                audio.enter(speaker);
                session = newSession(call);
                session.startOutgoing();
                CallService.start(context);
            });
        });
    }

    public void answer() {
        if (phase != Phase.RINGING_IN || call == null) return;
        stopRinging();
        if (signaller.accept(call.callId, null) == null) return;
        phase = Phase.CONNECTING;
        audio.enter(speaker);
        session = newSession(call);
        session.startIncoming();
        CallService.start(context);
        notifyUi();
    }

    public void decline() {
        if (phase == Phase.RINGING_IN && call != null) signaller.reject(call.callId);
    }

    /** Cancel an unanswered call, or hang up an answered one. */
    public void hangup() {
        if (phase == Phase.CALLING && call == null) { // still looking for a relay
            finish(context.getString(R.string.call_end_ended));
            return;
        }
        if (call == null || signaller == null) return;
        switch (phase) {
            case CALLING -> signaller.cancel(call.callId);
            case RINGING_IN -> signaller.reject(call.callId);
            case CONNECTING, CONNECTED -> signaller.hangup(call.callId);
            default -> { }
        }
    }

    public void setMuted(boolean muted) {
        if (session != null) session.setMuted(muted);
        notifyUi();
    }

    public void setSpeaker(boolean on) {
        speaker = on;
        audio.setSpeaker(on);
        notifyUi();
    }

    // ===== Signalling events (from the signaller's thread) =====

    @Override
    public void onIncoming(CallSignaller.Call incoming) {
        main.post(() -> {
            if (phase != Phase.IDLE && phase != Phase.ENDED) return; // the signaller answers busy
            reset();
            call = incoming;
            phase = Phase.RINGING_IN;
            ring();
            notifyUi();
        });
    }

    @Override
    public void onAnswered(CallSignaller.Call answered) {
        main.post(() -> {
            if (session != null && call == answered) {
                phase = Phase.CONNECTING;
                session.onAnswered();
                notifyUi();
            }
        });
    }

    @Override
    public void onEnded(CallSignaller.Call ended, CallSignaller.End reason) {
        main.post(() -> {
            if (call != ended || phase == Phase.ENDED) return; // already ended, and said why
            finish(describe(reason));
        });
    }

    // ===== Internals =====

    private CallSession newSession(CallSignaller.Call c) {
        return new CallSession(context, signaller, c, myFid, AudioIo.Backend.AAUDIO, new CallSession.Listener() {
            @Override
            public void onState(CallSession.State state, String detail) {
                main.post(() -> {
                    if (call != c) return;
                    switch (state) {
                        case CONNECTED -> {
                            phase = Phase.CONNECTED;
                            connectedAtMs = System.currentTimeMillis();
                        }
                        case FAILED -> {
                            // The media path failed: end the call for the peer too.
                            hangup();
                            finish(context.getString(R.string.call_end_failed, detail));
                        }
                        default -> { }
                    }
                    notifyUi();
                });
            }

            @Override
            public void onUnverified(String fid) {
                main.post(() -> {
                    unverifiedFid = fid;
                    notifyUi();
                });
            }
        });
    }

    private void finish(String reason) {
        stopRinging();
        if (session != null) session.end();
        session = null;
        audio.leave();
        CallService.stop(context);
        phase = Phase.ENDED;
        endReason = reason;
        notifyUi();
    }

    private void reset() {
        call = null;
        session = null;
        connectedAtMs = -1;
        endReason = null;
        unverifiedFid = null;
    }

    private String describe(CallSignaller.End reason) {
        return switch (reason) {
            case DECLINED -> context.getString(R.string.call_record_declined);
            case BUSY -> context.getString(R.string.call_record_busy);
            case UNSUPPORTED -> context.getString(R.string.call_end_unsupported);
            case NO_ANSWER -> context.getString(R.string.call_record_no_answer);
            case MISSED -> context.getString(R.string.call_record_missed);
            case ANSWERED_ELSEWHERE -> context.getString(R.string.call_record_answered_elsewhere);
            case CANCELLED, HUNG_UP, LOCAL_HANGUP -> context.getString(R.string.call_end_ended);
        };
    }

    /** The incoming-call notification opens the call screen, over the lock screen if need be (§11.1). */
    private void ring() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_INCOMING,
                context.getString(R.string.call_channel_incoming), NotificationManager.IMPORTANCE_HIGH));
        Intent open = new Intent(context, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFY_INCOMING, new NotificationCompat.Builder(context, CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.call_incoming_title))
                .setContentText(call.peerFid)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build());
        try {
            ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) ringtone.play();
        } catch (RuntimeException e) {
            TimberLogger.w(TAG, "no ringtone: %s", e.getMessage());
        }
        // If the app is in front, go straight to the call screen.
        try {
            context.startActivity(open);
        } catch (RuntimeException ignored) {
            // from the background the notification does it
        }
    }

    private void stopRinging() {
        if (ringtone != null) ringtone.stop();
        ringtone = null;
        context.getSystemService(NotificationManager.class).cancel(NOTIFY_INCOMING);
    }

    private void notifyUi() {
        for (Listener l : listeners) l.onCallChanged();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
