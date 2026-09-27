package com.fc.fc_ajdk.data.fcData;

/**
 * Types of message content.
 * Ordinal values are used in the binary wire format — do NOT reorder existing entries.
 */
public enum ContentType {
    // User content
    /**
     * Plain text message.
     */
    TEXT,
    
    /**
     * HAT (Hash-Addressed Token) - reference to data with locations.
     */
    HAT,
    
    /**
     * Stream - HAT header followed by byte stream for immediate data sharing.
     */
    STREAM,
    
    // Proactive sharing (push, not request/response)
    /**
     * Push a symmetric key to team/room members.
     */
    SYMKEY,
    
    /**
     * Push member list to new joiners.
     */
    MEMBERS,
    
    /**
     * Push message history (HAT reference + encrypted key).
     */
    HISTORY,
    
    // Request/Response (reactive)
    /**
     * Request for data (HAT, member list, symkey, message history, etc.).
     */
    REQUEST,
    
    /**
     * Response to a request.
     */
    RESPONSE,
    
    // Signals (no displayable content)
    /**
     * Typing indicator.
     */
    TYPING,
    
    /**
     * Delivery/read receipt.
     */
    RECEIPT,
    
    /**
     * Presence status update.
     */
    PRESENCE,
    
    // Message operations
    /**
     * Reaction (emoji) to a message.
     */
    REACTION,
    
    /**
     * Edit of a previous message.
     */
    EDIT,
    
    /**
     * Deletion of a previous message.
     */
    DELETE,
    
    /**
     * Forwarded message.
     */
    FORWARD,

    /**
     * Room info bundle (name, desc, owner, members, symkey).
     * Used to share all room information together instead of members only.
     */
    ROOM_INFO,

    /**
     * Room leave notification.
     * Sent by a departing member to the room owner so the owner can
     * remove the member and broadcast updated room info.
     */
    ROOM_LEAVE,

    /**
     * Voice message -- AAC audio with duration metadata in content JSON,
     * audio bytes in dataBase64 (inline) or HAT reference (large files).
     */
    VOICE,

    /**
     * Room invitation acceptance.
     * Sent by an invitee to the room owner after accepting a room invite,
     * so the owner can mark the member as confirmed (no longer pending).
     */
    ROOM_ACCEPT,

    /**
     * Room disband notification.
     * Sent by the room owner to all members when the room is closed.
     * Receivers MUST verify the sender is the room owner before processing.
     */
    ROOM_DISBAND,

    /**
     * Room member removal notification.
     * Sent by the room owner to a member who has been removed from the room.
     * Receivers MUST verify the sender is the room owner before processing.
     */
    ROOM_REMOVED,

    /**
     * Voice call signalling (VOICE_SPEC §3): content is JSON with an
     * {@code op} (INVITE, ACCEPT, REJECT, CANCEL, HANGUP). Never displayed as
     * a message; a call is recorded in the chat as a local entry built from
     * the signalling. Also used, never sent, for those local call records.
     * The Mac client must use the same ordinal.
     */
    CALL;

    public static ContentType fromString(String value) {
        if (value == null) return null;
        try {
            return ContentType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    public String toLowerCase() {
        return name().toLowerCase();
    }
}
