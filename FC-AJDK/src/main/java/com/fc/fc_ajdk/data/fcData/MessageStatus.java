package com.fc.fc_ajdk.data.fcData;

/**
 * Status of a message in its lifecycle.
 */
public enum MessageStatus {
    /**
     * Message created but not yet sent.
     */
    PENDING,

    /**
     * Message stored locally but held for user review (stranger quarantine).
     */
    QUARANTINED,
    
    /**
     * Message sent to the network.
     */
    SENT,
    
    /**
     * Message delivered to the recipient.
     */
    DELIVERED,
    
    /**
     * Message read by the recipient.
     */
    READ,
    
    /**
     * Message delivery failed.
     */
    FAILED,
    
    /**
     * Message imported from shared history. May be unverified.
     */
    IMPORTED;
    
    public static MessageStatus fromString(String value) {
        if (value == null) return null;
        try {
            return MessageStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    public String toLowerCase() {
        return name().toLowerCase();
    }
    
    /**
     * Check if this status indicates the message was successfully sent.
     */
    public boolean isSent() {
        return this == SENT || this == DELIVERED || this == READ;
    }
    
    /**
     * Check if this status indicates the message reached the recipient.
     */
    public boolean isDelivered() {
        return this == DELIVERED || this == READ;
    }
}
