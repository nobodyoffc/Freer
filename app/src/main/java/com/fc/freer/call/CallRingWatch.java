package com.fc.freer.call;

import com.fc.fc_ajdk.utils.TimberLogger;

import java.io.File;
import java.security.SecureRandom;
import java.util.Map;

/**
 * While a device rings, it watches the call on the relay the INVITE named,
 * so it stops as soon as the call is settled elsewhere instead of waiting
 * for a CANCEL over IM, which can take until the next DOCK fetch (VOICE_SPEC
 * §6.3). {@code call.info} is public: the watch uses a throwaway key and
 * sends no delegation. Two in the call: another of my devices answered. None:
 * the caller gave up.
 */
final class CallRingWatch {

    interface Listener {
        void answeredElsewhere();

        void callerGone();
    }

    static final long EVERY_MS = 2_000;
    private static final String TAG = "CallRingWatch";

    private final Thread thread;
    private volatile boolean stopped;

    CallRingWatch(File dir, String callId, String relayUrl, String relayPubkey, String relaySid, long untilMs,
                  Listener listener) {
        thread = new Thread(() -> {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            CallRelayLink link = null;
            boolean sawCaller = false;
            try {
                link = new CallRelayLink(dir, key, callId, null, new CallRelayLink.Events() {
                    @Override
                    public void onFrame(byte[] datagram, boolean direct) {}

                    @Override
                    public void onAttestation(byte[] attestation) {}

                    @Override
                    public void onNotice(Map<String, Object> notice) {}
                });
                link.connect(relayUrl, relayPubkey, relaySid);
                while (!stopped && System.currentTimeMillis() < untilMs) {
                    try {
                        Map<String, Object> info = link.info();
                        int n = info.get("participants") instanceof Number num ? num.intValue() : -1;
                        if (n >= 2) {
                            if (!stopped) listener.answeredElsewhere();
                            return;
                        }
                        if (n >= 1) sawCaller = true;
                        else if (n == 0 && sawCaller) {
                            if (!stopped) listener.callerGone();
                            return;
                        }
                    } catch (java.io.IOException e) {
                        // a lost reply: ask again next time
                    }
                    Thread.sleep(EVERY_MS);
                }
            } catch (InterruptedException e) {
                // stopped
            } catch (Exception e) {
                TimberLogger.w(TAG, "ring watch ended: %s", e.getMessage());
            } finally {
                if (link != null) link.close();
                java.util.Arrays.fill(key, (byte) 0);
            }
        }, "call-ring-watch");
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** Before this device answers, and whenever the ring ends: its own join must not look like another's. */
    void stop() {
        stopped = true;
        thread.interrupt();
    }
}
