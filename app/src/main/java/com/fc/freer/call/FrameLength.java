package com.fc.freer.call;

/**
 * Which frame length to send (VOICE_SPEC §9.1): 40 ms by default, 20 ms on a
 * direct path whose RTT is below 50 ms. Short frames cost about a third
 * more bandwidth (§7.5) and only pay off where the path adds little delay of
 * its own. Through a relay the phone sees only its own half of the path, so
 * relayed audio stays at 40 ms.
 * <p>
 * A switch waits until its reason has held for {@link #HOLD_MS}, and the way
 * back needs 70 ms: each switch restarts the receiver's jitter buffer, so it
 * should not flap on a noisy RTT.
 */
final class FrameLength {

    static final int SHORT_MS = 20, LONG_MS = 40;
    static final long SHORT_BELOW_RTT_MS = 50, LONG_FROM_RTT_MS = 70;
    static final long HOLD_MS = 3_000;

    private int current = LONG_MS;
    private long wantSinceMs = -1;

    int current() {
        return current;
    }

    /**
     * @param direct audio goes over the direct path
     * @param rttMs  that path's RTT, or -1 if unknown
     * @return the frame length to send from now on
     */
    int update(long nowMs, boolean direct, long rttMs) {
        boolean shortOk = direct && rttMs >= 0 && rttMs < SHORT_BELOW_RTT_MS;
        boolean longNeeded = !direct || rttMs < 0 || rttMs >= LONG_FROM_RTT_MS;
        boolean want = current == LONG_MS ? shortOk : longNeeded;
        if (!want) {
            wantSinceMs = -1;
        } else if (wantSinceMs < 0) {
            wantSinceMs = nowMs;
        } else if (nowMs - wantSinceMs >= HOLD_MS) {
            current = current == LONG_MS ? SHORT_MS : LONG_MS;
            wantSinceMs = -1;
        }
        return current;
    }
}
