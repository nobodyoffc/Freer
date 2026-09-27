package com.fc.freer.call;

import com.fc.fc_ajdk.call.Attestation;
import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.call.MediaFrame;
import com.fc.fc_ajdk.call.ReplayWindow;
import com.fc.freer.call.engine.EncodedFrame;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The end-to-end layer of one call (VOICE_SPEC §5, §5.1): seals this
 * device's frames under its sender key, opens the others' under theirs, and
 * checks every played frame against its sender's signed attestations. The
 * same bytes go direct or through the relay, which reads only the header.
 * <p>
 * In a meeting a sender's audio pauses when its attestations are late, and
 * resumes once one vouches for what was held (Decision 15); only a digest
 * that does not match silences it for good. A rekey (§4.5) moves everyone to
 * a new key epoch; the previous epoch's keys open frames for 5 s more.
 * <p>
 * No Android and no network in here. Thread-safety: every method is
 * synchronized; {@link #seal} runs on the capture thread, {@link #open} on the
 * network thread.
 */
public final class CallMedia {

    public static final int KEY_EPOCH = 0;
    /** Attest at least this often (§5.1). */
    static final long ATTEST_EVERY_MS = 1_000;
    /** A played frame no attestation has covered by now makes its stream unverified (§5.1). */
    static final long ATTEST_DEADLINE_MS = 3_000;
    /** Played frames' digests are kept this long (§5.1). */
    static final long KEEP_PLAYED_MS = 5_000;
    /** A paused stream's held digests: at most this long, of frames never played (Decision 15). */
    static final long KEEP_HELD_MS = 60_000;
    /** After a rekey, the previous epoch's keys still open frames this long (§4.5). */
    static final long PREVIOUS_EPOCH_MS = 5_000;

    public interface Listener {
        /** Audio claimed to be from {@code fid} could not be verified; that stream is now silent. */
        void onUnverified(String fid, int ssrc);

        /** A meeting stream paused for late attestations, or resumed once vouched for (Decision 15). */
        default void onPaused(String fid, int ssrc, boolean paused) {}
    }

    /** A frame's digest, and whether it was played or only held while its stream was paused. */
    private record Played(byte[] digest, long atMs, boolean heard) {}

    private static final class Peer {
        final String fid;
        final int ssrc;
        final byte[] tPub;
        /** Sender keys by key epoch: the current one, and the previous one for a few seconds. */
        final Map<Integer, byte[]> keys = new HashMap<>();
        final ReplayWindow window = new ReplayWindow();
        final TreeMap<Long, Played> played = new TreeMap<>();
        boolean unverified;
        boolean paused;

        Peer(String fid, int ssrc, byte[] tPub) {
            this.fid = fid;
            this.ssrc = ssrc;
            this.tPub = tPub;
        }
    }

    private final String callId;
    /** Call secrets by key epoch, as {@link Peer#keys}. */
    private final Map<Integer, byte[]> secrets = new HashMap<>();
    private final String myFid;
    private final int mySsrc;
    private int myEpoch = KEY_EPOCH;
    private byte[] myKey;
    private int previousEpoch = -1;
    private long previousUntilMs;
    private final byte[] tPriv;
    private final Map<Integer, Peer> peers = new HashMap<>();
    private Listener listener;
    private int routeId;

    // Outgoing attestation window: one entry per seq from pendingFirst, null where not sent.
    private final List<byte[]> pending = new ArrayList<>();
    private long pendingFirst = -1;
    private long pendingSinceMs;
    private final ArrayDeque<byte[]> ready = new ArrayDeque<>();

    /**
     * @param tPriv this call's transport key, which signs the attestations;
     *              the caller erases it when the call ends
     */
    private boolean oneToOne;

    public CallMedia(String callId, byte[] callSecret, String myFid, int mySsrc, byte[] tPriv) {
        this.callId = callId;
        this.secrets.put(KEY_EPOCH, callSecret.clone());
        this.myFid = myFid;
        this.mySsrc = mySsrc;
        this.myKey = CallKeys.senderKey(callSecret, myFid, mySsrc, KEY_EPOCH);
        this.tPriv = tPriv;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * A 1:1 call: only the two ends hold the key, so no one else can make a
     * frame that opens, and an attestation that is late or lost means a slow
     * path, never a forgery. It then silences no one; a digest that does not
     * match still does (§5.1). Meetings keep the 3 s rule.
     */
    public synchronized void setOneToOne(boolean oneToOne) {
        this.oneToOne = oneToOne;
    }

    /**
     * The relay's handle for our frames, from {@code call.join}; 0 on a direct
     * path. An attestation carries one routeId, so a change of path closes the
     * current one first.
     */
    public synchronized void setRouteId(int routeId) {
        if (routeId != this.routeId && !pending.isEmpty()) flush(System.currentTimeMillis());
        this.routeId = routeId;
    }

    /**
     * Someone we will hear: from the roster, with a {@code tPub} whose
     * delegation from {@code fid} the caller has checked (§5.1 step 1). The
     * key comes from {@code (fid, ssrc)} and nothing else (§4.3).
     */
    public synchronized void addPeer(String fid, int ssrc, byte[] tPub) {
        if (ssrc == mySsrc || peers.containsKey(ssrc)) return;
        Peer p = new Peer(fid, ssrc, tPub.clone());
        for (Map.Entry<Integer, byte[]> e : secrets.entrySet()) {
            p.keys.put(e.getKey(), CallKeys.senderKey(e.getValue(), fid, ssrc, e.getKey()));
        }
        peers.put(ssrc, p);
    }

    /**
     * Move to a new key epoch (§4.5): our frames go out under it at once, and
     * everyone's frames open under it. The previous epoch's keys open frames
     * already in flight for {@link #PREVIOUS_EPOCH_MS} more.
     */
    public synchronized void rekey(byte[] newSecret, int keyEpoch, long nowMs) {
        int epoch = keyEpoch & 0xFF; // the header carries it as a u8
        if (epoch == myEpoch) return;
        dropPreviousEpoch();
        previousEpoch = myEpoch;
        previousUntilMs = nowMs + PREVIOUS_EPOCH_MS;
        secrets.put(epoch, newSecret.clone());
        for (Peer p : peers.values()) p.keys.put(epoch, CallKeys.senderKey(newSecret, p.fid, p.ssrc, epoch));
        myEpoch = epoch;
        myKey = CallKeys.senderKey(newSecret, myFid, mySsrc, epoch);
    }

    public synchronized int keyEpoch() {
        return myEpoch;
    }

    private void dropPreviousEpoch() {
        if (previousEpoch < 0) return;
        byte[] s = secrets.remove(previousEpoch);
        if (s != null) Arrays.fill(s, (byte) 0);
        for (Peer p : peers.values()) p.keys.remove(previousEpoch);
        previousEpoch = -1;
    }

    public synchronized void removePeer(int ssrc) {
        peers.remove(ssrc);
    }

    // ===== Sending =====

    /** Seal one of our frames for the wire, and note it for the next attestation. */
    public synchronized byte[] seal(EncodedFrame f, long nowMs) {
        int flags = (f.voiceActive() ? MediaFrame.FLAG_VAD : 0) | (f.afterDtx() ? MediaFrame.FLAG_DTX : 0);
        byte[] frame = MediaFrame.seal(myKey, new MediaFrame.Header(flags, routeId, mySsrc, f.seq(), f.timestamp(),
                f.level(), myEpoch), f.opus());
        if (pendingFirst >= 0) {
            long gap = f.seq() - (pendingFirst + pending.size());
            // Seqs skipped as DTX get an all-zero digest. If they would overflow the
            // 64 an attestation holds (or seq went backwards), close this window and
            // start a new one here: unsent seqs need no attestation.
            if (gap < 0 || pending.size() + gap >= Attestation.MAX_COUNT) {
                flush(nowMs);
            } else {
                for (long i = 0; i < gap; i++) pending.add(null);
            }
        }
        if (pendingFirst < 0) {
            pendingFirst = f.seq();
            pendingSinceMs = nowMs;
        }
        pending.add(frame);
        return frame;
    }

    /**
     * Attestations to send now, by NOTIFY (dataType 0), never as a datagram:
     * one each second covering the frames since the last (§5.1).
     */
    public synchronized List<byte[]> takeAttestations(long nowMs) {
        if (!pending.isEmpty() && nowMs - pendingSinceMs >= ATTEST_EVERY_MS) flush(nowMs);
        List<byte[]> out = new ArrayList<>(ready);
        ready.clear();
        return out;
    }

    /** The final attestation, before leaving or hanging up (§5.1). */
    public synchronized List<byte[]> finish(long nowMs) {
        flush(nowMs);
        return takeAttestations(nowMs);
    }

    private void flush(long nowMs) {
        // Trailing unsent seqs need no attestation: nothing claims them yet.
        while (!pending.isEmpty() && pending.get(pending.size() - 1) == null) pending.remove(pending.size() - 1);
        if (!pending.isEmpty()) {
            ready.add(Attestation.sign(tPriv, callId, routeId, mySsrc, pendingFirst, new ArrayList<>(pending)).toBytes());
        }
        pending.clear();
        pendingFirst = -1;
    }

    // ===== Receiving =====

    /**
     * @return the frame to play, or null: not a v1 media frame, an ssrc not in
     *         the roster, one already found unverified or paused, a key epoch
     *         we hold no key for, failed authentication, or a replay
     */
    public synchronized EncodedFrame open(byte[] datagram, long nowMs) {
        return open(datagram, nowMs, false);
    }

    /**
     * @param fromPeerConnection the frame came on the direct FUDP connection,
     *        accepted only under the key the peer's verified delegation names
     *        (§6.2 step 7). The connection itself attributes it, so it waits
     *        for no attestation: one lost with a dying direct path must not
     *        silence the peer (§5.1).
     */
    public synchronized EncodedFrame open(byte[] datagram, long nowMs, boolean fromPeerConnection) {
        MediaFrame.Header h = MediaFrame.Header.parse(datagram);
        if (h == null) return null;
        Peer p = peers.get(h.ssrc());
        if (p == null || p.unverified) return null;
        byte[] key = p.keys.get(h.keyEpoch());
        if (key == null) return null;
        byte[] payload = MediaFrame.open(key, datagram);
        if (payload == null || !p.window.accept(h.seq())) return null; // window only after authentication
        // Paused: keep the digest for when an attestation comes, but play nothing (Decision 15).
        if (!fromPeerConnection) p.played.put(h.seq(), new Played(Attestation.digest(datagram), nowMs, !p.paused));
        if (p.paused) return null;
        return new EncodedFrame(h.ssrc(), h.seq(), h.timestamp(), h.level(),
                (h.flags() & MediaFrame.FLAG_VAD) != 0, (h.flags() & MediaFrame.FLAG_DTX) != 0, payload);
    }

    /**
     * A sender's attestation. Accepted only if signed by the transport key the
     * roster names for that ssrc (§5.1 step 1); then every frame we played in
     * its range must match its digest.
     */
    public synchronized void onAttestation(byte[] bytes) {
        onAttestation(bytes, System.currentTimeMillis());
    }

    public synchronized void onAttestation(byte[] bytes, long nowMs) {
        Attestation a = Attestation.parse(bytes);
        if (a == null) return;
        Peer p = peers.get(a.ssrc());
        if (p == null || p.unverified || !a.verify(p.tPub, callId)) return;
        for (Iterator<Map.Entry<Long, Played>> it = p.played.subMap(a.firstSeq(), true, a.lastSeq(), true)
                .entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Played> e = it.next();
            byte[] claimed = a.digests()[(int) (e.getKey() - a.firstSeq())];
            if (!Arrays.equals(claimed, e.getValue().digest())) {
                unverified(p);
                return;
            }
            it.remove(); // vouched for
        }
        // Everything overdue is now vouched for: the delay was the network's (Decision 15).
        if (p.paused && !overdue(p, nowMs)) {
            p.paused = false;
            if (listener != null) listener.onPaused(p.fid, p.ssrc, false);
        }
    }

    /**
     * Call about once a second. In a meeting, a stream with a frame played 3 s
     * ago and still unattested pauses until an attestation vouches for it.
     */
    public synchronized void tick(long nowMs) {
        if (previousEpoch >= 0 && nowMs >= previousUntilMs) dropPreviousEpoch();
        for (Peer p : new ArrayList<>(peers.values())) {
            if (p.unverified) continue;
            if (!oneToOne && !p.paused && overdue(p, nowMs)) {
                p.paused = true;
                if (listener != null) listener.onPaused(p.fid, p.ssrc, true);
            }
            if (p.paused) {
                // Keep what was played until vouched for; what was only held, for a minute.
                p.played.values().removeIf(pl -> !pl.heard() && nowMs - pl.atMs() > KEEP_HELD_MS);
            } else {
                p.played.values().removeIf(pl -> nowMs - pl.atMs() > KEEP_PLAYED_MS);
            }
        }
    }

    /** A digest older than the deadline that no attestation has vouched for yet. */
    private static boolean overdue(Peer p, long nowMs) {
        for (Played pl : p.played.values()) {
            if (nowMs - pl.atMs() >= ATTEST_DEADLINE_MS) return true;
        }
        return false;
    }

    public synchronized boolean isUnverified(int ssrc) {
        Peer p = peers.get(ssrc);
        return p != null && p.unverified;
    }

    public synchronized boolean isPaused(int ssrc) {
        Peer p = peers.get(ssrc);
        return p != null && p.paused;
    }

    private void unverified(Peer p) {
        p.unverified = true;
        p.paused = false;
        p.played.clear();
        if (listener != null) listener.onUnverified(p.fid, p.ssrc);
    }

    public int ssrc() {
        return mySsrc;
    }
}
