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
    private static final String PREF_ALWAYS_RELAY = "always_relay";
    private static final String PREF_AVAILABLE = "available_for_calls";

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
    private boolean failed;
    private String unverifiedFid;
    /** Well into the call and not a frame from the peer: likely its network cannot reach the relay. */
    private boolean peerSilent;
    /** The callee's CALL service for the call I placed, for its price and a top-up. */
    private com.fc.freer.im.handler.P2pHandler.CallRelay placedVia;
    /** Set when my call failed for my balance at the callee's relay: the relay to top up. */
    private String topUpRelay;
    private volatile boolean speaker; // read on the session thread when call audio starts
    private Ringtone ringtone;
    private CallRingWatch ringWatch;

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
            // An identity has just loaded, so the app is in the foreground: the only time Android allows this.
            if (availableForCalls()) CallAvailabilityService.start(context);
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

    /**
     * What a relayed minute of this call costs me, in FCH, from the CALL
     * service's price: the caller pays both sides' traffic (§7.5), about
     * 0.4 MB in and out per side. Null if I am not paying or the price is unknown.
     */
    public Double callerCostPerMinute() {
        com.fc.fc_ajdk.data.feipData.Service s = placedVia == null ? null : placedVia.service();
        if (s == null || call == null || !call.outgoing) return null;
        double in = price(s.getPricePerKBIn(), s.getPricePerKB()), out = price(s.getPricePerKBOut(), s.getPricePerKB());
        return in < 0 || out < 0 ? null : 2 * 400 * (in + out);
    }

    private static double price(String specific, String general) {
        String v = specific != null && !specific.isEmpty() ? specific : general;
        try {
            return v == null || v.isEmpty() ? -1 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The relay to top up after a call failed for my balance there, or null. */
    public String topUpRelay() {
        return topUpRelay;
    }

    /**
     * Pay the callee's CALL service from my FID, as FAPI's auto-recharge does
     * for any service, so the next call can open (§7.5). The user confirms
     * first. Runs off the main thread; {@code done} gets null on success, or
     * why not.
     */
    public void topUp(String relayUrl, java.util.function.Consumer<String> done) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String error;
            try {
                var r = com.fc.freer.utils.ApiCenter.getInstance().topUpService(context, relayUrl);
                error = r == null ? "the relay could not be reached" : r.isSuccess() ? null : r.getMessage();
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            String result = error;
            main.post(() -> done.accept(result));
        });
    }

    public long connectedAtMs() {
        return connectedAtMs;
    }

    public String endReason() {
        return endReason;
    }

    /** The call failed rather than ended: the screen stays up so the reason can be read. */
    public boolean endedWithFailure() {
        return failed;
    }

    public boolean peerSilent() {
        return peerSilent;
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

    /**
     * Debug builds only: a relay to call through instead of the callee's
     * home.CALL, and to answer on besides my own, for testing before a CALL
     * service is on chain. A release build always uses the callee's.
     */
    public String relayOverride() {
        return com.fc.freer.BuildConfig.DEBUG ? prefs().getString(PREF_RELAY, "") : "";
    }

    public void setRelayOverride(String url) {
        prefs().edit().putString(PREF_RELAY, url == null ? "" : url.trim()).apply();
    }

    /** Never try a direct path, which would show each side the other's IP (Decision 8). Off by default. */
    public boolean alwaysRelay() {
        return prefs().getBoolean(PREF_ALWAYS_RELAY, false);
    }

    public void setAlwaysRelay(boolean on) {
        prefs().edit().putBoolean(PREF_ALWAYS_RELAY, on).apply();
    }

    /** Ring in the background, through {@link CallAvailabilityService} (§6.3). Off by default. */
    public boolean availableForCalls() {
        return prefs().getBoolean(PREF_AVAILABLE, false);
    }

    public void setAvailableForCalls(boolean on) {
        prefs().edit().putBoolean(PREF_AVAILABLE, on).apply();
        if (on) CallAvailabilityService.start(context);
        else CallAvailabilityService.stop(context);
    }

    /** Audio is on a direct path rather than the relay. */
    public boolean isDirect() {
        return session != null && session.isDirect();
    }

    // ===== Actions =====

    /** Ring {@code peerFid}. Resolving the relay touches the network, so it runs off the main thread. */
    public void placeCall(String peerFid) {
        if (signaller == null || phase != Phase.IDLE && phase != Phase.ENDED) return;
        reset();
        phase = Phase.CALLING;
        notifyUi();
        Executors.newSingleThreadExecutor().execute(() -> {
            // The callee's CALL service, and no other (§6.2): no home.CALL means not callable.
            com.fc.freer.im.handler.P2pHandler.CallRelay via = null;
            String override = relayOverride();
            if (override.isEmpty()) {
                ImManager im = FidManager.getInstance().getImManager();
                via = im == null ? null : im.resolveCallRelay(peerFid);
            }
            String relayUrl = !override.isEmpty() ? override : via == null ? null : via.url();
            com.fc.freer.im.handler.P2pHandler.CallRelay resolved = via;
            main.post(() -> {
                if (phase != Phase.CALLING || call != null) return; // hung up while we looked
                if (relayUrl == null || relayUrl.isEmpty()) {
                    finish(context.getString(R.string.call_end_no_relay));
                    return;
                }
                placedVia = resolved;
                try {
                    // Nothing is sent yet: the session reaches the relay, then rings.
                    call = signaller.prepare(peerFid, relayUrl);
                } catch (IllegalStateException e) {
                    finish(context.getString(R.string.call_end_busy_here));
                    return;
                }
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
        TimberLogger.i(TAG, "audio: %s", audio.describe());
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
            watchRing(incoming);
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
        TimberLogger.i(TAG, "call %s ended by signalling: %s", ended.callId, reason);
        main.post(() -> {
            if (call != ended || phase == Phase.ENDED) return; // already ended, and said why
            finish(describe(reason));
        });
    }

    // ===== Internals =====

    /** Direct paths only with contacts, and never with Always relay on (Decision 8). */
    private boolean allowDirect(String peerFid) {
        if (alwaysRelay()) return false;
        try {
            com.fc.freer.manager.ContactManager cm = com.fc.freer.manager.ContactManager.getInstance();
            return cm != null && cm.checkIfFidExisted(peerFid);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private CallSession newSession(CallSignaller.Call c) {
        return new CallSession(context, signaller, c, myFid, AudioIo.Backend.AAUDIO, allowDirect(c.peerFid),
                new CallSession.Listener() {
            @Override
            public void onPathChanged() {
                main.post(() -> notifyUi());
            }

            @Override
            public void onPeerSilent(boolean silent) {
                main.post(() -> {
                    if (call != c) return;
                    peerSilent = silent;
                    notifyUi();
                });
            }

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
                            if (session != null && session.paymentRequired()) topUpRelay = c.relayUrl;
                            // The media path failed: end the call for the peer too.
                            hangup();
                            failed = true; // before finish(), whose redraw decides whether the screen stays
                            finish(session != null && session.networkBlocked()
                                    ? context.getString(R.string.call_end_network_blocked, relayHost())
                                    : context.getString(R.string.call_end_failed, detail));
                        }
                        default -> { }
                    }
                    notifyUi();
                });
            }

            /**
             * Call mode only once audio is about to flow: Android puts an app
             * that sits in MODE_IN_COMMUNICATION without voice audio back to
             * normal, and changing mode under open streams disconnects them.
             */
            @Override
            public void beforeAudio() {
                audio.enter(speaker);
                TimberLogger.i(TAG, "audio: %s", audio.describe());
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
        failed = false;
        unverifiedFid = null;
        peerSilent = false;
        placedVia = null;
        topUpRelay = null;
    }

    private String describe(CallSignaller.End reason) {
        return switch (reason) {
            case DECLINED -> context.getString(R.string.call_record_declined);
            case BUSY -> context.getString(R.string.call_record_busy);
            case UNSUPPORTED -> context.getString(R.string.call_end_unsupported);
            case WRONG_RELAY -> context.getString(R.string.call_end_wrong_relay);
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

    /** While ringing, watch the relay: another device may answer, or the caller leave (§6.3). */
    private void watchRing(CallSignaller.Call c) {
        if (ringWatch != null) ringWatch.stop();
        ringWatch = null;
        if (c.relayUrl == null || c.relayPubkey == null || c.relaySid == null) return;
        CallSignaller s = signaller;
        ringWatch = new CallRingWatch(new java.io.File(context.getCacheDir(), "call-ring/" + c.callId), c.callId,
                c.relayUrl, c.relayPubkey, c.relaySid, c.expiresMs, new CallRingWatch.Listener() {
                    @Override
                    public void answeredElsewhere() {
                        TimberLogger.i(TAG, "call %s answered on another device", c.callId);
                        s.settledElsewhere(c.callId, true);
                    }

                    @Override
                    public void callerGone() {
                        TimberLogger.i(TAG, "call %s: the caller left the relay", c.callId);
                        s.settledElsewhere(c.callId, false);
                    }
                });
        ringWatch.start();
    }

    private void stopRinging() {
        if (ringWatch != null) ringWatch.stop(); // before this device joins: its join is not another's
        ringWatch = null;
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
