package com.fc.freer.call;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the meeting screen shows of a running {@link MeetingSession}, taken
 * at one moment: the session runs in the {@code :voice} process (§11.3), and
 * the screen, in the main one, reads this instead. Plain data, sent as JSON.
 */
public final class MeetingView {

    private static final Gson GSON = new Gson();

    String meetingId;
    List<MeetingSession.Participant> participants = new ArrayList<>();
    List<Integer> speaking = new ArrayList<>();
    List<Integer> paused = new ArrayList<>();
    List<String> unverifiedFids = new ArrayList<>();
    String hostFid;
    boolean host;
    boolean selfMuted;
    /** "host", "locked" or null. */
    String mutedByHost;
    boolean handRaised;
    long joinedAtMs = -1;
    int mySsrc;
    long rttMs = -1;
    String relayPubkey;
    String relaySid;

    /** The current state of {@code s}. */
    static MeetingView of(MeetingSession s) {
        MeetingView v = new MeetingView();
        v.meetingId = s.meetingId();
        v.participants = new ArrayList<>(s.participants());
        v.speaking = new ArrayList<>(s.speaking());
        v.paused = new ArrayList<>(s.pausedSsrcs());
        v.unverifiedFids = s.unverifiedFids();
        v.hostFid = s.hostFid();
        v.host = s.isHost();
        v.selfMuted = s.isSelfMuted();
        v.mutedByHost = s.mutedByHost();
        v.handRaised = s.handRaised();
        v.joinedAtMs = s.joinedAtMs();
        v.mySsrc = s.mySsrc();
        v.rttMs = s.rttMs();
        v.relayPubkey = s.relayPubkey();
        v.relaySid = s.relaySid();
        return v;
    }

    String toJson() {
        return GSON.toJson(this);
    }

    static MeetingView fromJson(String json) {
        try {
            return json == null ? null : GSON.fromJson(json, MeetingView.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public String meetingId() {
        return meetingId;
    }

    public List<MeetingSession.Participant> participants() {
        return participants;
    }

    /** ssrcs heard just now, this device's own included. */
    public Set<Integer> speaking() {
        return new HashSet<>(speaking);
    }

    /** Streams whose audio is on hold for late attestations (Decision 15). */
    public Set<Integer> pausedSsrcs() {
        return new HashSet<>(paused);
    }

    /** FIDs whose audio could not be verified and is silenced for this join (§5.1). */
    public List<String> unverifiedFids() {
        return unverifiedFids;
    }

    public String hostFid() {
        return hostFid;
    }

    public boolean isHost() {
        return host;
    }

    public boolean isMuted() {
        return selfMuted || mutedByHost != null;
    }

    public boolean isSelfMuted() {
        return selfMuted;
    }

    public String mutedByHost() {
        return mutedByHost;
    }

    public boolean handRaised() {
        return handRaised;
    }

    public long joinedAtMs() {
        return joinedAtMs;
    }

    public int mySsrc() {
        return mySsrc;
    }

    public long rttMs() {
        return rttMs;
    }

    public String relayPubkey() {
        return relayPubkey;
    }

    public String relaySid() {
        return relaySid;
    }
}
