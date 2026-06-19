package com.fc.fc_ajdk.data.fcData;

/**
 * Methods of message delivery.
 */
public enum DeliveryMethod {
    /**
     * Direct FUDP peer-to-peer delivery.
     */
    FUDP_DIRECT,
    
    /**
     * Relayed through one or more ROAD servers.
     */
    ROAD_RELAY,
    
    /**
     * Stored in DOCK for offline retrieval.
     */
    DOCK_STORED;
    
    public static DeliveryMethod fromString(String value) {
        if (value == null) return null;
        try {
            return DeliveryMethod.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    
    public String toLowerCase() {
        return name().toLowerCase();
    }
    
    /**
     * Check if this delivery method indicates the recipient was online.
     * @return true if delivered directly or via relay (online), false if stored (offline)
     */
    public boolean isOnline() {
        return this == FUDP_DIRECT || this == ROAD_RELAY;
    }
}
