package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.utils.JsonUtils;

/**
 * Represents a conversation (chat thread) for display in conversation list.
 * Aggregates information about a chat with a person, group, team, or room.
 */
public class Conversation extends FcEntity {
    
    private String targetId;           // FID, groupId, teamId, or roomId
    private ImType type;               // P2P, GROUP, TEAM, ROOM
    private String displayName;        // Name to show (CID, group name, etc.)
    private String avatarDid;          // Avatar data ID
    
    // Last message preview
    private String lastMessageId;
    private String lastMessageContent;
    private String lastMessageSenderId;
    private String lastMessageSenderName;
    private ContentType lastMessageType;
    private Long lastMessageTime;
    
    // Unread status
    private Integer unreadCount;
    
    // User preferences
    private Boolean pinned;
    private Boolean muted;
    private Boolean archived;
    
    // Timestamps
    private Long lastActiveAt;         // Last activity timestamp
    private Long createdAt;            // First message timestamp
    
    // For team/room: encryption info
    private Long symkeyVersion;
    private Boolean hasSymkey;
    
    // For p2p: online status
    private Boolean isOnline;
    private Long lastSeenAt;
    
    // For group: membership status
    private Boolean leftGroup;
    
    // For group/team: metrics
    private Long memberNum;
    private Long tCdd;
    private Float tRate;
    
    /**
     * Create a conversation from an ImMessage.
     */
    public static Conversation fromMessage(ImMessage message, String myFid) {
        Conversation conv = new Conversation();
        conv.setType(message.getType());
        
        // Set target ID based on message type
        if (message.getType() == ImType.P2P) {
            conv.setTargetId(message.getConversationPartnerId(myFid));
        } else {
            conv.setTargetId(message.getTargetId());
        }
        
        conv.setId(conv.getType().name() + "_" + conv.getTargetId());
        conv.updateWithMessage(message);
        conv.setUnreadCount(message.isOutgoing(myFid) ? 0 : 1);
        conv.setCreatedAt(message.getTimestamp());
        
        return conv;
    }
    
    /**
     * Update conversation with a new message.
     */
    public void updateWithMessage(ImMessage message) {
        lastMessageId = message.getId();
        lastMessageContent = getPreviewContent(message);
        lastMessageSenderId = message.getSenderId();
        lastMessageSenderName = message.getSenderName();
        lastMessageType = message.getContentType();
        lastMessageTime = message.getTimestamp();
        lastActiveAt = message.getTimestamp();
    }
    
    /**
     * Get preview content for display.
     */
    private String getPreviewContent(ImMessage message) {
        if (message.getContentType() == null) {
            return message.getContent();
        }
        return switch (message.getContentType()) {
            case TEXT -> message.getContent();
            case HAT -> "[File]";
            case STREAM -> "[Stream]";
            case SYMKEY -> "[Symmetric Key]";
            case MEMBERS -> "[Members]";
            case HISTORY -> "[History]";
            case REQUEST -> "[Request]";
            case RESPONSE -> "[Response]";
            case TYPING -> "typing...";
            case RECEIPT -> "";
            case PRESENCE -> "";
            case REACTION -> "[Reaction]";
            case EDIT -> message.getContent();
            case DELETE -> "[Deleted]";
            case FORWARD -> "[Forwarded] " + message.getContent();
            case ROOM_INFO -> "";
            case ROOM_LEAVE -> "";
            case VOICE -> "[Voice]";
            case ROOM_ACCEPT -> "";
            case ROOM_DISBAND -> "";
            case ROOM_REMOVED -> "";
        };
    }
    
    /**
     * Increment unread count.
     */
    public void incrementUnread() {
        unreadCount = (unreadCount != null ? unreadCount : 0) + 1;
    }
    
    /**
     * Mark as read (reset unread count).
     */
    public void markAsRead() {
        unreadCount = 0;
    }
    
    public String toJson() {
        return JsonUtils.toJson(this);
    }
    
    public String toNiceJson() {
        return JsonUtils.toNiceJson(this);
    }
    
    public static Conversation fromJson(String json) {
        return JsonUtils.fromJson(json, Conversation.class);
    }
    
    // Getters and setters
    
    public String getTargetId() {
        return targetId;
    }
    
    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }
    
    public ImType getType() {
        return type;
    }
    
    public void setType(ImType type) {
        this.type = type;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public String getAvatarDid() {
        return avatarDid;
    }
    
    public void setAvatarDid(String avatarDid) {
        this.avatarDid = avatarDid;
    }
    
    public String getLastMessageId() {
        return lastMessageId;
    }
    
    public void setLastMessageId(String lastMessageId) {
        this.lastMessageId = lastMessageId;
    }
    
    public String getLastMessageContent() {
        return lastMessageContent;
    }
    
    public void setLastMessageContent(String lastMessageContent) {
        this.lastMessageContent = lastMessageContent;
    }
    
    public String getLastMessageSenderId() {
        return lastMessageSenderId;
    }
    
    public void setLastMessageSenderId(String lastMessageSenderId) {
        this.lastMessageSenderId = lastMessageSenderId;
    }
    
    public String getLastMessageSenderName() {
        return lastMessageSenderName;
    }
    
    public void setLastMessageSenderName(String lastMessageSenderName) {
        this.lastMessageSenderName = lastMessageSenderName;
    }
    
    public ContentType getLastMessageType() {
        return lastMessageType;
    }
    
    public void setLastMessageType(ContentType lastMessageType) {
        this.lastMessageType = lastMessageType;
    }
    
    public Long getLastMessageTime() {
        return lastMessageTime;
    }
    
    public void setLastMessageTime(Long lastMessageTime) {
        this.lastMessageTime = lastMessageTime;
    }
    
    public Integer getUnreadCount() {
        return unreadCount;
    }
    
    public void setUnreadCount(Integer unreadCount) {
        this.unreadCount = unreadCount;
    }
    
    public Boolean getPinned() {
        return pinned;
    }
    
    public void setPinned(Boolean pinned) {
        this.pinned = pinned;
    }
    
    public Boolean getMuted() {
        return muted;
    }
    
    public void setMuted(Boolean muted) {
        this.muted = muted;
    }
    
    public Boolean getArchived() {
        return archived;
    }
    
    public void setArchived(Boolean archived) {
        this.archived = archived;
    }
    
    public Long getLastActiveAt() {
        return lastActiveAt;
    }
    
    public void setLastActiveAt(Long lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
    
    public Long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
    
    public Long getSymkeyVersion() {
        return symkeyVersion;
    }
    
    public void setSymkeyVersion(Long symkeyVersion) {
        this.symkeyVersion = symkeyVersion;
    }
    
    public Boolean getHasSymkey() {
        return hasSymkey;
    }
    
    public void setHasSymkey(Boolean hasSymkey) {
        this.hasSymkey = hasSymkey;
    }
    
    public Boolean getIsOnline() {
        return isOnline;
    }
    
    public void setIsOnline(Boolean isOnline) {
        this.isOnline = isOnline;
    }
    
    public Long getLastSeenAt() {
        return lastSeenAt;
    }
    
    public void setLastSeenAt(Long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
    
    public Boolean getLeftGroup() {
        return leftGroup;
    }
    
    public void setLeftGroup(Boolean leftGroup) {
        this.leftGroup = leftGroup;
    }
    
    public Long getMemberNum() {
        return memberNum;
    }
    
    public void setMemberNum(Long memberNum) {
        this.memberNum = memberNum;
    }
    
    public Long gettCdd() {
        return tCdd;
    }
    
    public void settCdd(Long tCdd) {
        this.tCdd = tCdd;
    }
    
    public Float gettRate() {
        return tRate;
    }
    
    public void settRate(Float tRate) {
        this.tRate = tRate;
    }
}
