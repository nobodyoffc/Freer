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

    public interface Listener {
        /** Audio claimed to be from {@code fid} could not be verified; that stream is now silent. */
        void onUnverified(String fid, int ssrc);
    }

    private record Played(byte[] digest, long atMs) {}

    private static final class Peer {
        final String fid;
        final int ssrc;
        final byte[] tPub;
        final byte[] key;
        final ReplayWindow window = new ReplayWindow();
        final TreeMap<Long, Played> played = new TreeMap<>();
        boolean unverified;

        Peer(String fid, int ssrc, byte[] tPub, byte[] key) {
            this.fid = fid;
            this.ssrc = ssrc;
            this.tPub = tPub;
            this.key = key;
        }
    }

    private final String callId;
    private final byte[] callSecret;
    private final String myFid;
    private final int mySsrc;
    private final byte[] myKey;
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
    public CallMedia(String callId, byte[] callSecret, String myFid, int mySsrc, byte[] tPriv) {
        this.callId = callId;
        this.callSecret = callSecret.clone();
        this.myFid = myFid;
        this.mySsrc = mySsrc;
        this.myKey = CallKeys.senderKey(callSecret, myFid, mySsrc, KEY_EPOCH);
        this.tPriv = tPriv;
    }

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
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
        peers.put(ssrc, new Peer(fid, ssrc, tPub.clone(), CallKeys.senderKey(callSecret, fid, ssrc, KEY_EPOCH)));
    }

    public synchronized void removePeer(int ssrc) {
        peers.remove(ssrc);
    }

    // ===== Sending =====

    /** Seal one of our frames for the wire, and note it for the next attestation. */
    public synchronized byte[] seal(EncodedFrame f, long nowMs) {
        int flags = (f.voiceActive() ? MediaFrame.FLAG_VAD : 0) | (f.afterDtx() ? MediaFrame.FLAG_DTX : 0);
        byte[] frame = MediaFrame.seal(myKey, new MediaFrame.Header(flags, routeId, mySsrc, f.seq(), f.timestamp(),
                f.level(), KEY_EPOCH), f.opus());
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
     *         the roster, one already found unverified, another key epoch,
     *         failed authentication, or a replay
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
        if (h == null || h.keyEpoch() != KEY_EPOCH) return null;
        Peer p = peers.get(h.ssrc());
        if (p == null || p.unverified) return null;
        byte[] payload = MediaFrame.open(p.key, datagram);
        if (payload == null || !p.window.accept(h.seq())) return null; // window only after authentication
        if (!fromPeerConnection) p.played.put(h.seq(), new Played(Attestation.digest(datagram), nowMs));
        return new EncodedFrame(h.ssrc(), h.seq(), h.timestamp(), h.level(),
                (h.flags() & MediaFrame.FLAG_VAD) != 0, (h.flags() & MediaFrame.FLAG_DTX) != 0, payload);
    }

    /**
     * A sender's attestation. Accepted only if signed by the transport key the
     * roster names for that ssrc (§5.1 step 1); then every frame we played in
     * its range must match its digest.
     */
    public synchronized void onAttestation(byte[] bytes) {
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
    }

    /** Call about once a second: anything played 3 s ago and still unattested is unverified. */
    public synchronized void tick(long nowMs) {
        for (Peer p : new ArrayList<>(peers.values())) {
            if (p.unverified) continue;
            for (Played pl : p.played.values()) {
                if (nowMs - pl.atMs() >= ATTEST_DEADLINE_MS) {
                    unverified(p);
                    break;
                }
            }
            p.played.values().removeIf(pl -> nowMs - pl.atMs() > KEEP_PLAYED_MS);
        }
    }

    public synchronized boolean isUnverified(int ssrc) {
        Peer p = peers.get(ssrc);
        return p != null && p.unverified;
    }

    private void unverified(Peer p) {
        p.unverified = true;
        p.played.clear();
        if (listener != null) listener.onUnverified(p.fid, p.ssrc);
    }

    public int ssrc() {
        return mySsrc;
    }
}
