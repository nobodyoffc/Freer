package com.fc.freer.im;

import com.fc.fc_ajdk.data.fcData.FcEntity;
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
        TEAM_INVITE,
        TEAM_TRANSFER,
        FILE_OFFER,
        ROOM_INVITE
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
     * Payload for TEAM_INVITE and TEAM_TRANSFER issues.
     */
    public static class TeamNotificationData {
        public String teamId;
        public String teamName;
        public String senderFid;
        public String txId;

        public String toJson() { return JsonUtils.toJson(this); }
        public static TeamNotificationData fromJson(String json) {
            return JsonUtils.fromJson(json, TeamNotificationData.class);
        }
    }

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

    public static PendingIssue createTeamInvite(String senderFid, String teamId, String teamName, String txId) {
        PendingIssue issue = new PendingIssue();
        issue.setId("TEAM_INVITE_" + teamId + "_" + System.currentTimeMillis());
        issue.setIssueType(IssueType.TEAM_INVITE);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(senderFid);
        issue.setCreatedAt(System.currentTimeMillis());

        TeamNotificationData data = new TeamNotificationData();
        data.teamId = teamId;
        data.teamName = teamName;
        data.senderFid = senderFid;
        data.txId = txId;
        issue.setDataFrom(data);

        if (teamName != null && !teamName.isEmpty()) {
            issue.setNote(teamName);
        }
        return issue;
    }

    public static PendingIssue createTeamTransfer(String senderFid, String teamId, String teamName, String txId) {
        PendingIssue issue = new PendingIssue();
        issue.setId("TEAM_TRANSFER_" + teamId + "_" + System.currentTimeMillis());
        issue.setIssueType(IssueType.TEAM_TRANSFER);
        issue.setStatus(IssueStatus.PENDING);
        issue.setPeerFid(senderFid);
        issue.setCreatedAt(System.currentTimeMillis());

        TeamNotificationData data = new TeamNotificationData();
        data.teamId = teamId;
        data.teamName = teamName;
        data.senderFid = senderFid;
        data.txId = txId;
        issue.setDataFrom(data);

        if (teamName != null && !teamName.isEmpty()) {
            issue.setNote(teamName);
        }
        return issue;
    }
}
