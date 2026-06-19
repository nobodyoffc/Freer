package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Locally-defined room for private group messaging.
 * Unlike Team/Group, Room has no on-chain state - members and keys are managed locally.
 */
public class Room extends FcEntity {
    
    private String owner;              // FID of room owner
    private String name;               // Room display name
    private String desc;               // Room description
    private List<String> members;      // Member FIDs
    private List<String> pendingMembers; // Local-only (owner side): invited members not yet confirmed via ROOM_ACCEPT
    
    // Encryption
    private String symkeyCipher;       // Encrypted symmetric key
    private Long symkeyVersion;        // Key version for rotation
    private List<String> symkeyHistory; // Previous key ciphers for old message decryption
    
    // Timestamps
    private Long created;              // Creation timestamp
    private Long lastActive;           // Last message timestamp
    private Long lastUpdated;          // Last member/key change
    
    // State
    private Boolean active;            // Whether room is active
    private String avatarDid;          // Avatar data ID
    private Map<String, String> home;  // Home URLs/locations set by owner
    
    // Local settings
    private Boolean muted;
    private Boolean pinned;
    
    /**
     * Generate a unique room ID.
     */
    public static String generateRoomId(String owner) {
        try {
            SecureRandom random = new SecureRandom();
            String input = owner + System.currentTimeMillis() + random.nextLong();
            byte[] hash = Hash.sha256(input.getBytes());
            return "room_" + Hex.toHex(hash).substring(0, 24);
        } catch (Exception e) {
            return "room_" + Hex.toHex(Hash.sha256((System.nanoTime() + "").getBytes()))
                    .substring(0, 24);
        }
    }
    
    /**
     * Check and create ID if not set.
     */
    public void checkIdWithCreate() {
        if (id == null && owner != null) {
            id = generateRoomId(owner);
        }
    }
    
    /**
     * Create a new room.
     */
    public static Room create(String owner, String name, String desc) {
        Room room = new Room();
        room.setOwner(owner);
        room.setName(name);
        room.setDesc(desc);
        room.setMembers(new ArrayList<>());
        room.getMembers().add(owner); // Owner is always a member
        room.setSymkeyVersion(1L);
        room.setCreated(System.currentTimeMillis());
        room.setLastActive(room.getCreated());
        room.setLastUpdated(room.getCreated());
        room.setActive(true);
        room.checkIdWithCreate();
        return room;
    }
    
    /**
     * Add a member to the room.
     * @return true if member was added, false if already exists
     */
    public boolean addMember(String fid) {
        if (members == null) {
            members = new ArrayList<>();
        }
        if (members.contains(fid)) {
            return false;
        }
        members.add(fid);
        lastUpdated = System.currentTimeMillis();
        return true;
    }
    
    /**
     * Remove a member from the room.
     * @return true if member was removed, false if not found
     */
    public boolean removeMember(String fid) {
        if (members == null) return false;
        if (fid.equals(owner)) return false; // Can't remove owner
        boolean removed = members.remove(fid);
        if (pendingMembers != null) {
            pendingMembers.remove(fid);
        }
        if (removed) {
            lastUpdated = System.currentTimeMillis();
        }
        return removed;
    }

    /**
     * Mark a member as pending (invited but not yet confirmed via ROOM_ACCEPT).
     * Owner-side bookkeeping only; never sent on the wire.
     * @return true if the member was newly marked pending
     */
    public boolean addPendingMember(String fid) {
        if (fid == null || fid.equals(owner)) return false;
        if (pendingMembers == null) {
            pendingMembers = new ArrayList<>();
        }
        if (pendingMembers.contains(fid)) {
            return false;
        }
        pendingMembers.add(fid);
        return true;
    }

    /**
     * Confirm a pending member (on receipt of ROOM_ACCEPT).
     * @return true if the member was pending and is now confirmed
     */
    public boolean confirmMember(String fid) {
        return pendingMembers != null && pendingMembers.remove(fid);
    }

    /**
     * Check if a member is still pending confirmation.
     */
    public boolean isPendingMember(String fid) {
        return pendingMembers != null && pendingMembers.contains(fid);
    }
    
    /**
     * Check if FID is a member.
     */
    public boolean isMember(String fid) {
        return members != null && members.contains(fid);
    }
    
    /**
     * Check if FID is the owner.
     */
    public boolean isOwner(String fid) {
        return fid != null && fid.equals(owner);
    }
    
    /**
     * Rotate the symmetric key (owner only).
     */
    public void rotateSymkey(String newSymkeyCipher) {
        if (symkeyHistory == null) {
            symkeyHistory = new ArrayList<>();
        }
        if (symkeyCipher != null) {
            symkeyHistory.add(symkeyCipher);
        }
        symkeyCipher = newSymkeyCipher;
        symkeyVersion = (symkeyVersion != null ? symkeyVersion : 0) + 1;
        lastUpdated = System.currentTimeMillis();
    }
    
    /**
     * Get member count.
     */
    public int getMemberCount() {
        return members != null ? members.size() : 0;
    }
    
    public String toJson() {
        return JsonUtils.toJson(this);
    }
    
    public String toNiceJson() {
        return JsonUtils.toNiceJson(this);
    }
    
    public static Room fromJson(String json) {
        return JsonUtils.fromJson(json, Room.class);
    }
    
    // Getters and setters
    
    public String getOwner() {
        return owner;
    }
    
    public void setOwner(String owner) {
        this.owner = owner;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDesc() {
        return desc;
    }
    
    public void setDesc(String desc) {
        this.desc = desc;
    }
    
    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = members;
    }

    public List<String> getPendingMembers() {
        return pendingMembers;
    }

    public void setPendingMembers(List<String> pendingMembers) {
        this.pendingMembers = pendingMembers;
    }
    
    public String getSymkeyCipher() {
        return symkeyCipher;
    }
    
    public void setSymkeyCipher(String symkeyCipher) {
        this.symkeyCipher = symkeyCipher;
    }
    
    public Long getSymkeyVersion() {
        return symkeyVersion;
    }
    
    public void setSymkeyVersion(Long symkeyVersion) {
        this.symkeyVersion = symkeyVersion;
    }
    
    public List<String> getSymkeyHistory() {
        return symkeyHistory;
    }
    
    public void setSymkeyHistory(List<String> symkeyHistory) {
        this.symkeyHistory = symkeyHistory;
    }
    
    public Long getCreated() {
        return created;
    }
    
    public void setCreated(Long created) {
        this.created = created;
    }
    
    public Long getLastActive() {
        return lastActive;
    }
    
    public void setLastActive(Long lastActive) {
        this.lastActive = lastActive;
    }
    
    public Long getLastUpdated() {
        return lastUpdated;
    }
    
    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    
    public Boolean getActive() {
        return active;
    }
    
    public void setActive(Boolean active) {
        this.active = active;
    }
    
    public String getAvatarDid() {
        return avatarDid;
    }
    
    public void setAvatarDid(String avatarDid) {
        this.avatarDid = avatarDid;
    }
    
    public Boolean getMuted() {
        return muted;
    }
    
    public void setMuted(Boolean muted) {
        this.muted = muted;
    }
    
    public Boolean getPinned() {
        return pinned;
    }
    
    public void setPinned(Boolean pinned) {
        this.pinned = pinned;
    }
    
    public Map<String, String> getHome() {
        return home;
    }
    
    public void setHome(Map<String, String> home) {
        this.home = home;
    }
}
