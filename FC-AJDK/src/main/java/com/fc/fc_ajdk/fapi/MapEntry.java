package com.fc.fc_ajdk.fapi;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.utils.JsonUtils;

/**
 * MAP service entry representing a FID's network address.
 * Used for NAT traversal and peer discovery.
 */
public class MapEntry extends FcEntity {
    
    private String fid;            // The FID this entry belongs to
    private String pubkey;         // The FID's public key (for identity verification)
    private String observedIp;     // IP address as seen by MAP server
    private Integer observedPort;  // Port as seen by MAP server
    private Long lastSeen;         // Timestamp of last registration
    private Long expiresAt;        // When this entry expires if not renewed
    
    /**
     * Check if this entry is still fresh (not expired).
     */
    public boolean isFresh() {
        if (expiresAt == null) return false;
        return System.currentTimeMillis() < expiresAt;
    }
    
    /**
     * Get full address string.
     */
    public String getAddress() {
        if (observedIp == null || observedPort == null) return null;
        return observedIp + ":" + observedPort;
    }
    
    /**
     * Time in seconds until this entry expires.
     */
    public long secondsUntilExpiry() {
        if (expiresAt == null) return 0;
        return Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000);
    }
    
    public String toJson() {
        return JsonUtils.toJson(this);
    }
    
    public static MapEntry fromJson(String json) {
        return JsonUtils.fromJson(json, MapEntry.class);
    }
    
    // Getters and setters
    
    public String getFid() {
        return fid;
    }
    
    public void setFid(String fid) {
        this.fid = fid;
    }
    
    public String getPubkey() {
        return pubkey;
    }
    
    public void setPubkey(String pubkey) {
        this.pubkey = pubkey;
    }
    
    public String getObservedIp() {
        return observedIp;
    }
    
    public void setObservedIp(String observedIp) {
        this.observedIp = observedIp;
    }
    
    public Integer getObservedPort() {
        return observedPort;
    }
    
    public void setObservedPort(Integer observedPort) {
        this.observedPort = observedPort;
    }
    
    public Long getLastSeen() {
        return lastSeen;
    }
    
    public void setLastSeen(Long lastSeen) {
        this.lastSeen = lastSeen;
    }
    
    public Long getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }
}
