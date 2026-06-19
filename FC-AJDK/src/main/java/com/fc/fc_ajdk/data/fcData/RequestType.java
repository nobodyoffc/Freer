package com.fc.fc_ajdk.data.fcData;

/**
 * Types of data requests in messaging.
 */
public enum RequestType {
    /**
     * Request data by HAT reference.
     */
    HAT,
    
    /**
     * Request member list (for room).
     */
    MEMBERS,
    
    /**
     * Request symmetric key (for team or room).
     */
    SYMKEY,
    
    /**
     * Request missed messages synchronization.
     */
    MESSAGE_SYNC,
    
    /**
     * Request message history.
     */
    HISTORY,
    
    /**
     * Request peer's public key.
     */
    PUBLIC_KEY,
    
    /**
     * Request symkey history (for decrypting old messages after key rotation).
     */
    SYMKEY_HISTORY,

    /**
     * Request room info (name, desc, members, symkey) from a room member.
     */
    ROOM_INFO;
    
    public static RequestType fromString(String value) {
        if (value == null) return null;
        try {
            return RequestType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    public String toLowerCase() {
        return name().toLowerCase();
    }
}
