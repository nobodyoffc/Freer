package com.fc.fc_ajdk.data.fcData;

/**
 * Types of instant messaging communication.
 */
public enum ImType {
    /**
     * Person-to-person direct messaging.
     * Encrypted with recipient's public key.
     */
    P2P,
    
    /**
     * Group messaging with on-chain registered members.
     * No manager, open membership, unencrypted.
     */
    SQUARE,
    
    /**
     * Team messaging with on-chain registered members.
     * Owner-managed, encrypted with team symkey.
     */
    TEAM,
    
    /**
     * Room messaging with locally defined members.
     * Owner-managed locally, encrypted with room symkey.
     */
    ROOM;
    
    public static ImType fromString(String value) {
        if (value == null) return null;
        try {
            return ImType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    public String toLowerCase() {
        return name().toLowerCase();
    }
}
