package com.fc.freer.im;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.DatabaseManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages whitelist and blacklist for message filtering.
 * Controls who can send messages to the user.
 */
public class ContactPolicy {
    private static final String TAG = "ContactPolicy";
    private static final String DB_NAME = "contact_policy";
    
    /**
     * Policy strategies for handling unknown senders.
     */
    public enum Strategy {
        /** Accept all messages except from blacklisted senders */
        ACCEPT_ALL,
        /** Only accept messages from whitelisted senders */
        WHITELIST_ONLY,
        /** Accept from contacts only */
        CONTACTS_ONLY,
        /** Accept none (do not disturb) */
        ACCEPT_NONE
    }
    
    private LocalDB<PolicyEntry> db;
    private final String liveFid;
    private Strategy strategy = Strategy.ACCEPT_ALL;
    
    // In-memory cache for fast lookup
    private final Set<String> whitelistCache = new HashSet<>();
    private final Set<String> blacklistCache = new HashSet<>();
    private final Set<String> contactsCache = new HashSet<>();
    
    public ContactPolicy(Context context, String liveFid) {
        this.liveFid = liveFid;
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, PolicyEntry.class, PolicyEntry.class);
        loadCache();
        if (liveFid != null && !whitelistCache.contains(liveFid)) {
            addToWhitelist(liveFid);
            TimberLogger.d(TAG, "Auto-whitelisted liveFid: %s", liveFid);
        }
    }
    
    /**
     * Load entries into memory cache.
     */
    private void loadCache() {
        try {
            Map<String,PolicyEntry> entries = db.getAll();
            for (String id : entries.keySet()) {
                PolicyEntry entry = entries.get(id);
                if(entry==null)continue;
                if (entry.getType() == EntryType.WHITELIST) {
                    whitelistCache.add(entry.getFid());
                } else if (entry.getType() == EntryType.BLACKLIST) {
                    blacklistCache.add(entry.getFid());
                } else if (entry.getType() == EntryType.CONTACT) {
                    contactsCache.add(entry.getFid());
                } else if (entry.getType() == EntryType.STRATEGY) {
                    try {
                        strategy = Strategy.valueOf(entry.getFid());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to load cache: %s", e.getMessage());
        }
    }
    
    /**
     * Set the filtering strategy.
     */
    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
        
        // Persist strategy
        PolicyEntry entry = new PolicyEntry();
        entry.setId("strategy");
        entry.setType(EntryType.STRATEGY);
        entry.setFid(strategy.name());
        entry.setUpdatedAt(System.currentTimeMillis());
        db.put(entry.getId(),entry);
    }
    
    /**
     * Get current strategy.
     */
    public Strategy getStrategy() {
        return strategy;
    }
    
    /**
     * Check if a sender is allowed to message this user.
     */
    public boolean isAllowed(String senderFid) {
        if (senderFid == null) return false;
        
        // Always block blacklisted
        if (blacklistCache.contains(senderFid)) {
            return false;
        }
        
        // Always allow whitelisted
        if (whitelistCache.contains(senderFid)) {
            return true;
        }
        
        return switch (strategy) {
            case ACCEPT_ALL -> true;
            case WHITELIST_ONLY -> false; // Already checked above
            case CONTACTS_ONLY -> contactsCache.contains(senderFid);
            case ACCEPT_NONE -> false;
        };
    }
    
    /**
     * Add to whitelist.
     */
    public void addToWhitelist(String fid) {
        addEntry(fid, EntryType.WHITELIST);
        whitelistCache.add(fid);
        blacklistCache.remove(fid); // Remove from blacklist if present
    }
    
    /**
     * Remove from whitelist.
     */
    public void removeFromWhitelist(String fid) {
        removeEntry(fid, EntryType.WHITELIST);
        whitelistCache.remove(fid);
    }
    
    /**
     * Add to blacklist.
     */
    public void addToBlacklist(String fid) {
        addEntry(fid, EntryType.BLACKLIST);
        blacklistCache.add(fid);
        whitelistCache.remove(fid); // Remove from whitelist if present
    }
    
    /**
     * Remove from blacklist.
     */
    public void removeFromBlacklist(String fid) {
        removeEntry(fid, EntryType.BLACKLIST);
        blacklistCache.remove(fid);
    }
    
    /**
     * Add as contact (automatically whitelisted).
     */
    public void addContact(String fid) {
        addEntry(fid, EntryType.CONTACT);
        contactsCache.add(fid);
        whitelistCache.add(fid);
    }
    
    /**
     * Remove contact.
     */
    public void removeContact(String fid) {
        removeEntry(fid, EntryType.CONTACT);
        contactsCache.remove(fid);
    }
    
    /**
     * Sync contacts from Contact manager.
     */
    public void syncContacts(List<String> contactFids) {
        // Remove old contacts
        for (String fid : new ArrayList<>(contactsCache)) {
            if (!contactFids.contains(fid)) {
                removeContact(fid);
            }
        }
        
        // Add new contacts
        for (String fid : contactFids) {
            if (!contactsCache.contains(fid)) {
                addContact(fid);
            }
        }
    }
    
    /**
     * Check if FID is whitelisted.
     */
    public boolean isWhitelisted(String fid) {
        return whitelistCache.contains(fid);
    }
    
    /**
     * Check if FID is blacklisted.
     */
    public boolean isBlacklisted(String fid) {
        return blacklistCache.contains(fid);
    }
    
    /**
     * Check if FID is a contact.
     */
    public boolean isContact(String fid) {
        return contactsCache.contains(fid);
    }
    
    /**
     * Get all whitelisted FIDs.
     */
    public List<String> getWhitelist() {
        return new ArrayList<>(whitelistCache);
    }
    
    /**
     * Get all blacklisted FIDs.
     */
    public List<String> getBlacklist() {
        return new ArrayList<>(blacklistCache);
    }
    
    /**
     * Get all contacts.
     */
    public List<String> getContacts() {
        return new ArrayList<>(contactsCache);
    }
    
    private void addEntry(String fid, EntryType type) {
        PolicyEntry entry = new PolicyEntry();
        entry.setId(type.name() + "_" + fid);
        entry.setType(type);
        entry.setFid(fid);
        entry.setCreatedAt(System.currentTimeMillis());
        entry.setUpdatedAt(System.currentTimeMillis());
        db.put(entry.getId(),entry);
    }
    
    private void removeEntry(String fid, EntryType type) {
        db.remove(type.name() + "_" + fid);
    }
    
    /**
     * Entry types.
     */
    public enum EntryType {
        WHITELIST,
        BLACKLIST,
        CONTACT,
        STRATEGY
    }
    
    /**
     * Policy entry for database storage.
     */
    public static class PolicyEntry extends FcEntity {
        private EntryType type;
        private String fid;
        private Long createdAt;
        private Long updatedAt;
        private String note;
        
        public EntryType getType() { return type; }
        public void setType(EntryType type) { this.type = type; }
        
        public String getFid() { return fid; }
        public void setFid(String fid) { this.fid = fid; }
        
        public Long getCreatedAt() { return createdAt; }
        public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
        
        public Long getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
        
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        
        public String toJson() { return JsonUtils.toJson(this); }
        public static PolicyEntry fromJson(String json) { return JsonUtils.fromJson(json, PolicyEntry.class); }
    }
}
