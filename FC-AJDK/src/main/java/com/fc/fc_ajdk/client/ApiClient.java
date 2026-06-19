package com.fc.fc_ajdk.client;

import com.fc.fc_ajdk.fapi.message.FapiRequest;
import com.fc.fc_ajdk.fapi.message.FapiResponse;

/**
 * Unified interface for API clients in FC ecosystem.
 * Both FapiClient and ApipClient should implement this interface.
 */
public interface ApiClient {
    
    /**
     * Send a request and get response
     * 
     * @param request The FAPI request
     * @return The FAPI response
     */
    FapiResponse request(FapiRequest request);
    
    /**
     * Check if the client is connected/available
     */
    boolean isConnected();
    
    /**
     * Get the service ID this client is connected to
     */
    String getServiceId();
    
    /**
     * Close the client and release resources
     */
    void close();
    
    /**
     * Get the last error message if any
     */
    String getLastError();
    
    /**
     * Ping the service to check availability
     */
    default boolean ping() {
        try {
            FapiRequest pingRequest = FapiRequest.simple("base.health");
            FapiResponse response = request(pingRequest);
            return response != null && response.isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

}

