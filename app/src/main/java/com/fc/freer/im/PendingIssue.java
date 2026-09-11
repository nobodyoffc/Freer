package com.fc.freer.im;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.utils.JsonUtils;

/**
 * Represents a pending issue that requires user action.
 * Generalised: the {@code dataJson} field carries issue-type-specific
 * payload serialised as JSON, accessed via {@link #getDataAs(Class)}
 * and {@link #setDataFrom(Object)}.
 */
public class PendingIssue extends FcEntity {

    public enum IssueType {
        STRANGER_PEER,
        GROUP_INVITE,
        FILE_OFFER,
        ROOM_INVITE,
        /** A manager invited this FID to a team, or an owner named it as transferee. */
        TEAM_INVITE,
        /** The owner changed a team's consensus; this member owes a signature on the new one. */
        CONSENSUS_CHANGE,
        /** Somebody asked for a conversation's messages; handing them over needs this user's yes. */
        HISTORY_REQUEST,
        /** History shared with this user could not be downloaded after the automatic attempts. */
        HISTORY_IMPORT_FAILED
    }

    public enum IssueStatus {
        PENDING,
        ACCEPTED,
        REJECTED,
        EXPIRED,
        DEFERRED
    }

    private IssueType issueType;
    private IssueStatus status;
    private String peerFid;
    private String peerAlias;
    private Long createdAt;
    private Long resolvedAt;
    private Long expiresAt;
    private String note;
    private String dataJson;
    private int messageCount;

    /**
     * Type-specific payload stored alongside STRANGER_PEER issues.
     */
    public static class StrangerPeerData {
        public String firstMessageContent;
        public Long firstMessageAt;
        public String lastMessageContent;
        public Long lastMessageAt;
        public String peerHost;
        public int peerPort;

        public String toJson() { return JsonUtils.toJson(this); }
        public static StrangerPeerData fromJson(String json) {
            return JsonUtils.fromJson(json, StrangerPeerData.class);
        }
    }

    @Override
    public String getId() {
        return this.id;
    }

    public IssueType getIssueType() { return issueType; }
    public void setIssueType(IssueType issueType) { this.issueType = issueType; }

    public IssueStatus getStatus() { return status; }
    public void setStatus(IssueStatus status) { this.status = status; }

    public String getPeerFid() { return peerFid; }
    public void setPeerFid(String peerFid) { this.peerFid = peerFid; }

    public String getPeerAlias() { return peerAlias; }
    public void setPeerAlias(String peerAlias) { this.peerAlias = peerAlias; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Long resolvedAt) { this.resolvedAt = resolvedAt; }

    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getDataJson() { return dataJson; }
    public void setDataJson(String dataJson) { this.dataJson = dataJson; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    /**
     * Deserialise {@code dataJson} into the given type.
     * Returns null when dataJson is null/empty or parsing fails.
     */
    public <T> T getDataAs(Class<T> clazz) {
        if (dataJson == null || dataJson.isEmpty()) return null;
        try {
            return JsonUtils.fromJson(dataJson, clazz);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Serialise an arbitrary object into {@code dataJson}.
     */
    public void setDataFrom(Object obj) {
        this.dataJson = (obj != null) ? JsonUtils.toJson(obj) : null;
    }

    public String toJson() { return JsonUtils.toJson(this); }
    public static PendingIssue fromJson(String json) { return JsonUtils.fromJson(json, PendingIssue.class); }

    /**
     * Payload for ROOM_INVITE issues.
     * Stores the raw roomInfo JSON so the room can be created on accept.
     */
    public static class RoomInviteData {
        public String roomId;
        public String roomName;
        public String roomInfoJson;
        public String ownerFid;

        public String toJson() { return JsonUtils.toJson(this); }
        public static RoomInviteData fromJson(String json) {
            return JsonUtils.fromJson(json, RoomInviteData.class);
        }
    }

    public static PendingIssue createRoomInvite(String senderFid, String roomId, String roomName,
                                                 String roomInfoJson) {
        PendingIssue issue = new PendingIssue();
        issue.setId("ROOM_INVITE_" + roomId);
        issue.setIssueType(IssueType.ROOM_INVITE);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(senderFid);
        issue.setCreatedAt(System.currentTimeMillis());

        RoomInviteData data = new RoomInviteData();
        data.roomId = roomId;
        data.roomName = roomName;
        data.roomInfoJson = roomInfoJson;
        data.ownerFid = senderFid;
        issue.setDataFrom(data);

        if (roomName != null && !roomName.isEmpty()) {
            issue.setNote(roomName);
        }
        return issue;
    }

    /**
     * Payload for CONSENSUS_CHANGE issues.
     * <p>
     * {@code oldConsensusId} is captured locally before the sync overwrites the cached team — the
     * chain only ever holds the current consensus — so the member can read what they previously
     * agreed to next to what they are being asked to agree to. It is null when this device never
     * saw the previous consensus. {@code diskSid} is the team's DISK at the time the change was
     * noticed, which is where both documents can be fetched from.
     */
    public static class ConsensusChangeData {
        public String teamId;
        public String teamName;
        public String ownerFid;
        public String oldConsensusId;
        public String newConsensusId;
        public String diskSid;

        public String toJson() { return JsonUtils.toJson(this); }
        public static ConsensusChangeData fromJson(String json) {
            return JsonUtils.fromJson(json, ConsensusChangeData.class);
        }
    }

    /**
     * Payload for TEAM_INVITE issues.
     * <p>
     * The notification is only a hint: the authoritative invitation lives on chain in the team's
     * {@code invitees} (or {@code transferee}) list, which {@link JoinTeamActivity} checks before
     * carving anything. So this carries just enough to tell the user who invited them and where.
     */
    public static class TeamInviteData {
        public String teamId;
        public String teamName;
        public String senderFid;
        /** True when this is a transfer of ownership rather than a plain invitation. */
        public boolean transfer;

        public String toJson() { return JsonUtils.toJson(this); }
        public static TeamInviteData fromJson(String json) {
            return JsonUtils.fromJson(json, TeamInviteData.class);
        }
    }

    /** Issue id for a team invitation: one per team, so a resend does not stack up. */
    public static String teamInviteIssueId(String teamId) {
        return "TEAM_INVITE_" + teamId;
    }

    public static PendingIssue createTeamInvite(String senderFid, String teamId, String teamName,
                                                boolean transfer) {
        PendingIssue issue = new PendingIssue();
        issue.setId(teamInviteIssueId(teamId));
        issue.setIssueType(IssueType.TEAM_INVITE);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(senderFid);
        issue.setCreatedAt(System.currentTimeMillis());

        TeamInviteData data = new TeamInviteData();
        data.teamId = teamId;
        data.teamName = teamName;
        data.senderFid = senderFid;
        data.transfer = transfer;
        issue.setDataFrom(data);

        if (teamName != null && !teamName.isEmpty()) issue.setNote(teamName);
        return issue;
    }

    /** Issue id for a consensus change: one per team per consensus version. */
    public static String consensusIssueId(String teamId, String newConsensusId) {
        return "CONSENSUS_" + teamId + "_" + newConsensusId;
    }

    public static PendingIssue createConsensusChange(String teamId, String teamName, String ownerFid,
                                                     String oldConsensusId, String newConsensusId,
                                                     String diskSid) {
        PendingIssue issue = new PendingIssue();
        issue.setId(consensusIssueId(teamId, newConsensusId));
        issue.setIssueType(IssueType.CONSENSUS_CHANGE);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(ownerFid);
        issue.setCreatedAt(System.currentTimeMillis());

        ConsensusChangeData data = new ConsensusChangeData();
        data.teamId = teamId;
        data.teamName = teamName;
        data.ownerFid = ownerFid;
        data.oldConsensusId = oldConsensusId;
        data.newConsensusId = newConsensusId;
        data.diskSid = diskSid;
        issue.setDataFrom(data);

        if (teamName != null && !teamName.isEmpty()) {
            issue.setNote(teamName);
        }
        return issue;
    }

    /**
     * Payload for HISTORY_REQUEST issues.
     * <p>
     * {@code imType}/{@code targetId} name the thread on <b>this</b> device, already turned round
     * for P2P (the requester's FID rather than ours). {@code requestedTargetId} is the thread as
     * the requester named it, echoed in the export so their client sees its own words.
     * {@code since} is inclusive and {@code before} exclusive, in milliseconds.
     */
    public static class HistoryRequestData {
        public String nonce;
        public String requesterFid;
        public ImType imType;
        public String targetId;
        public String requestedTargetId;
        public long since;
        public long before;

        public String toJson() { return JsonUtils.toJson(this); }
        public static HistoryRequestData fromJson(String json) {
            return JsonUtils.fromJson(json, HistoryRequestData.class);
        }
    }

    /**
     * Issue id for a history request: keyed by requester <i>and</i> nonce, since the nonce is the
     * requester's to choose and on its own would let one requester overwrite another's request.
     */
    public static String historyRequestIssueId(String requesterFid, String nonce) {
        return "HISTORY_REQUEST_" + requesterFid + "_" + nonce;
    }

    public static PendingIssue createHistoryRequest(String requesterFid, String nonce, ImType imType,
                                                    String targetId, String requestedTargetId,
                                                    long since, long before, String threadName) {
        PendingIssue issue = new PendingIssue();
        issue.setId(historyRequestIssueId(requesterFid, nonce));
        issue.setIssueType(IssueType.HISTORY_REQUEST);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(requesterFid);
        issue.setCreatedAt(System.currentTimeMillis());

        HistoryRequestData data = new HistoryRequestData();
        data.nonce = nonce;
        data.requesterFid = requesterFid;
        data.imType = imType;
        data.targetId = targetId;
        data.requestedTargetId = requestedTargetId;
        data.since = since;
        data.before = before;
        issue.setDataFrom(data);

        if (threadName != null && !threadName.isEmpty()) issue.setNote(threadName);
        return issue;
    }

    /**
     * Payload for HISTORY_IMPORT_FAILED issues. The share itself (with its file key) stays in
     * {@link HistoryAskStore}; this only says which one, and what went wrong last.
     */
    public static class HistoryImportFailedData {
        public String nonce;
        public String senderFid;
        public ImType imType;
        public String targetId;
        public long since;
        public long before;
        public int attempts;
        public String lastError;

        public String toJson() { return JsonUtils.toJson(this); }
        public static HistoryImportFailedData fromJson(String json) {
            return JsonUtils.fromJson(json, HistoryImportFailedData.class);
        }
    }

    /** Issue id for a failed history import: one per answered request. */
    public static String historyImportFailedIssueId(String nonce) {
        return "HISTORY_IMPORT_" + nonce;
    }

    public static PendingIssue createHistoryImportFailed(HistoryImportFailedData data, String threadName) {
        PendingIssue issue = new PendingIssue();
        issue.setId(historyImportFailedIssueId(data.nonce));
        issue.setIssueType(IssueType.HISTORY_IMPORT_FAILED);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(data.senderFid);
        issue.setCreatedAt(System.currentTimeMillis());
        issue.setDataFrom(data);
        if (threadName != null && !threadName.isEmpty()) issue.setNote(threadName);
        return issue;
    }

    public static PendingIssue createStrangerPeer(String peerFid) {
        PendingIssue issue = new PendingIssue();
        issue.setId("STRANGER_PEER_" + peerFid);
        issue.setIssueType(IssueType.STRANGER_PEER);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(peerFid);
        if (peerFid != null && peerFid.length() > 4) {
            issue.setPeerAlias(peerFid.substring(peerFid.length() - 4));
        }
        issue.setCreatedAt(System.currentTimeMillis());
        return issue;
    }

}
