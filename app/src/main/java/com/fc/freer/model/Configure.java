package com.fc.freer.model;

import android.content.Context;

import com.fc.fc_ajdk.config.BaseConfigure;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.ApiAccount;
import com.fc.fc_ajdk.fapi.client.ApiProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Freer-specific configuration class that extends BaseConfigure.
 * Persistence is handled by ConfigureManager using SharedPreferences ("fc_config_prefs").
 */
public class Configure extends BaseConfigure {
    public static final String TAG = "Configure";

    // Freer-specific fields
    private String esAccountId;
    private Map<String, ApiProvider> apiProviderMap;
    private Map<String, ApiAccount> apiAccountMap;

    // Android context for storage operations
    private static Context context;

    public Configure() {
        super();
        this.apiProviderMap = new HashMap<>();
        this.apiAccountMap = new HashMap<>();
    }

    // ==================== BaseConfigure Abstract Method Implementations ====================

    @Override
    public void save() {
        // Persistence is handled by ConfigureManager.storeConfigure()
    }

    @Override
    public void load() {
        // Loading is handled by ConfigureManager.getConfigure(context, passwordName)
    }

    // ==================== Android Context ====================

    public static void setContext(Context androidContext) {
        context = androidContext;
    }

    public static Context getContext() {
        return context;
    }

    // ==================== Freer-Specific Methods ====================

    public Map<String, ApiProvider> getApiProviderMap() {
        return apiProviderMap;
    }

    public void setApiProviderMap(Map<String, ApiProvider> apiProviderMap) {
        this.apiProviderMap = apiProviderMap != null ? apiProviderMap : new HashMap<>();
    }

    public Map<String, ApiAccount> getApiAccountMap() {
        return apiAccountMap;
    }

    public void setApiAccountMap(Map<String, ApiAccount> apiAccountMap) {
        this.apiAccountMap = apiAccountMap != null ? apiAccountMap : new HashMap<>();
    }

    public String getEsAccountId() {
        return esAccountId;
    }

    public void setEsAccountId(String esAccountId) {
        this.esAccountId = esAccountId;
    }

    /**
     * Get all account IDs for a service type
     */
    public List<String> getAccountIdsForServiceType(Service.ServiceType type) {
        List<String> accountIds = new ArrayList<>();
        if (apiAccountMap != null && apiProviderMap != null) {
            for (Map.Entry<String, ApiAccount> entry : apiAccountMap.entrySet()) {
                ApiProvider provider = apiProviderMap.get(entry.getValue().getProviderId());
                if (provider != null && provider.fetchServiceType() == type) {
                    accountIds.add(entry.getKey());
                }
            }
        }
        return accountIds;
    }
}
