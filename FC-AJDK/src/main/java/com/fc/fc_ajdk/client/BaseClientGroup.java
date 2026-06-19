package com.fc.fc_ajdk.client;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Base class for client group management.
 * A client group manages multiple API clients for a specific service type.
 * 
 * App-specific implementations should extend this class for platform-specific functionality.
 */
public abstract class BaseClientGroup implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final String TAG = "BaseClientGroup";

    protected Service.ServiceType groupType;
    protected List<String> accountIds;
    protected GroupStrategy strategy;
    protected transient int roundRobinIndex = 0;
    protected transient Map<String, Object> clientMap;

    public void addAccountIds(String id) {
        if(accountIds==null)
            accountIds = new ArrayList<>();
        if (id != null && !accountIds.contains(id)) {
            accountIds.add(id);
        }
    }

    /**
     * Client selection strategies
     */
    public enum GroupStrategy {
        USE_FIRST,           // Use the first available client
        USE_ANY_VALID,       // Use any valid client
        USE_ALL,             // Use all clients
        USE_ONE_RANDOM,      // Use a random client
        USE_ONE_ROUND_ROBIN; // Use clients in round-robin fashion

        @Override
        public String toString() {
            return name();
        }
    }

    /**
     * Default constructor
     */
    protected BaseClientGroup() {
        this.accountIds = new ArrayList<>();
        this.clientMap = new HashMap<>();
        this.strategy = GroupStrategy.USE_FIRST;
    }

    /**
     * Constructor with service type
     */
    protected BaseClientGroup(Service.ServiceType groupType) {
        this();
        this.groupType = groupType;
    }

    // ==================== Abstract Methods ====================

    /**
     * Check if a client is valid/connected
     */
    protected abstract boolean isClientValid(Object client);

    // ==================== Client Management ====================

    /**
     * Add a client to the group
     */
    public void addClient(String accountId, Object client) {
        if (accountId == null || client == null) return;
        
        if (!accountIds.contains(accountId)) {
            accountIds.add(accountId);
        }
        if (clientMap == null) clientMap = new HashMap<>();
        clientMap.put(accountId, client);
        TimberLogger.d(TAG, "Added client for account: %s", accountId);
    }

    /**
     * Add a client to the first position
     */
    public void addToFirstClient(String accountId, Object client) {
        if (accountId == null || client == null) return;
        
        if (!accountIds.isEmpty()) {
            accountIds.remove(accountId); // Remove if exists
        }
        accountIds.add(0, accountId);
        
        if (clientMap == null) clientMap = new HashMap<>();
        clientMap.put(accountId, client);
        TimberLogger.d(TAG, "Added client to first position for account: %s", accountId);
    }

    /**
     * Remove a client from the group
     */
    public void removeClient(String accountId) {
        if (accountId == null) return;
        
        accountIds.remove(accountId);
        if (clientMap != null) {
            clientMap.remove(accountId);
        }
        TimberLogger.d(TAG, "Removed client for account: %s", accountId);
    }

    /**
     * Clear all clients
     */
    public void clearClients() {
        accountIds.clear();
        if (clientMap != null) {
            clientMap.clear();
        }
        roundRobinIndex = 0;
        TimberLogger.d(TAG, "Cleared all clients");
    }

    // ==================== Client Retrieval ====================

    /**
     * Get account ID based on strategy
     */
    public String getAccountId() {
        if (accountIds == null || accountIds.isEmpty()) {
            return null;
        }

        switch (strategy) {
            case USE_FIRST, USE_ALL -> {
                return accountIds.get(0);
            }
            case USE_ANY_VALID -> {
                for (String accountId : accountIds) {
                    Object client = clientMap != null ? clientMap.get(accountId) : null;
                    if (isClientValid(client)) {
                        return accountId;
                    }
                }
                return null;
            }
            case USE_ONE_RANDOM -> {
                return accountIds.get(new Random().nextInt(accountIds.size()));
            }
            case USE_ONE_ROUND_ROBIN -> {
                return accountIds.get(roundRobinIndex++ % accountIds.size());
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Get client based on strategy
     */
    public Object getClient() {
        if (accountIds == null || accountIds.isEmpty()) {
            return null;
        }

        switch (strategy) {
            case USE_FIRST -> {
                return clientMap == null ? null : clientMap.get(accountIds.get(0));
            }
            case USE_ANY_VALID -> {
                for (String accountId : accountIds) {
                    Object client = clientMap != null ? clientMap.get(accountId) : null;
                    if (isClientValid(client)) {
                        return client;
                    }
                }
                return null;
            }
            case USE_ALL -> {
                return clientMap;
            }
            case USE_ONE_RANDOM -> {
                String randomId = accountIds.get(new Random().nextInt(accountIds.size()));
                return clientMap != null ? clientMap.get(randomId) : null;
            }
            case USE_ONE_ROUND_ROBIN -> {
                String nextId = accountIds.get(roundRobinIndex++ % accountIds.size());
                return clientMap != null ? clientMap.get(nextId) : null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * Get client by account ID
     */
    public Object getClient(String accountId) {
        if (clientMap == null || accountId == null) {
            return null;
        }
        return clientMap.get(accountId);
    }

    /**
     * Get all valid clients
     */
    public List<Object> getValidClients() {
        List<Object> validClients = new ArrayList<>();
        if (clientMap == null) {
            return validClients;
        }
        
        for (Object client : clientMap.values()) {
            if (isClientValid(client)) {
                validClients.add(client);
            }
        }
        return validClients;
    }

    /**
     * Get all valid account IDs
     */
    public List<String> getValidAccountIds() {
        List<String> validAccountIds = new ArrayList<>();
        if (clientMap == null) {
            return validAccountIds;
        }
        
        for (Map.Entry<String, Object> entry : clientMap.entrySet()) {
            if (isClientValid(entry.getValue())) {
                validAccountIds.add(entry.getKey());
            }
        }
        return validAccountIds;
    }

    // ==================== Status Methods ====================

    /**
     * Get client count
     */
    public int getClientCount() {
        return accountIds != null ? accountIds.size() : 0;
    }

    /**
     * Get valid client count
     */
    public int getValidClientCount() {
        return getValidClients().size();
    }

    /**
     * Check if there are valid clients
     */
    public boolean hasValidClients() {
        return getValidClientCount() > 0;
    }

    /**
     * Get group status info
     */
    public String getStatus() {
        int total = getClientCount();
        int valid = getValidClientCount();
        return String.format("Group[%s]: %d/%d clients available, strategy: %s", 
                           groupType, valid, total, strategy);
    }

    // ==================== Serialization ====================

    protected void initTransientFields() {
        if (clientMap == null) {
            clientMap = new HashMap<>();
        }
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        initTransientFields();
    }

    // ==================== Getters and Setters ====================

    public Service.ServiceType getGroupType() {
        return groupType;
    }

    public void setGroupType(Service.ServiceType groupType) {
        this.groupType = groupType;
    }

    public List<String> getAccountIds() {
        return accountIds;
    }

    public void setAccountIds(List<String> accountIds) {
        this.accountIds = accountIds != null ? accountIds : new ArrayList<>();
    }

    public void addAccountId(String accountId) {
        if (accountIds == null) accountIds = new ArrayList<>();
        if (accountId != null && !accountIds.contains(accountId)) {
            accountIds.add(accountId);
        }
    }

    public Map<String, Object> getClientMap() {
        return clientMap;
    }

    public void setClientMap(Map<String, Object> clientMap) {
        this.clientMap = clientMap;
    }

    public GroupStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(GroupStrategy strategy) {
        this.strategy = strategy;
    }

    public int getRoundRobinIndex() {
        return roundRobinIndex;
    }

    public void setRoundRobinIndex(int roundRobinIndex) {
        this.roundRobinIndex = roundRobinIndex;
    }
}

