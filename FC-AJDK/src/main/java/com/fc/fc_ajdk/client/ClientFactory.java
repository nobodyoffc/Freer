package com.fc.fc_ajdk.client;

import com.fc.fc_ajdk.data.feipData.Service;

/**
 * Factory interface for creating API clients.
 * Implementations provide platform-specific client creation logic.
 */
public interface ClientFactory {
    
    /**
     * Create a client for the given service type and URL
     * 
     * @param serviceType The type of service
     * @param url The service URL
     * @return The created client, or null if creation failed
     */
    Object createClient(Service.ServiceType serviceType, String url);
    
    /**
     * Create a client from account and provider information
     * 
     * @param serviceType The type of service
     * @param accountId The account ID
     * @param providerId The provider ID
     * @param url The service URL
     * @return The created client, or null if creation failed
     */
    Object createClientFromAccount(Service.ServiceType serviceType, String accountId, 
                                   String providerId, String url);
    
    /**
     * Get default API URLs for a service type
     */
    String[] getDefaultApis(Service.ServiceType serviceType);
    
    /**
     * Check if a client is valid
     */
    boolean isClientValid(Object client);
}

