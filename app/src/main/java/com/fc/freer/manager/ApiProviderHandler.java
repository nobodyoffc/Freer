package com.fc.freer.manager;

import android.app.Activity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fc.fc_ajdk.clients.ApipClient;
import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.freer.model.ApiProvider;
import com.fc.freer.ui.InputObjectActivity;
import com.fc.freer.ui.UpdateObjectActivity;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ApiProvider处理器 - 管理ApiProvider实例的创建、删除、修改、读取等操作
 */
public class ApiProviderHandler {
    private static final String TAG = "ApiProviderHandler";
    
    private final Configure configure;
    private final Gson gson = new Gson();
    
    public ApiProviderHandler(@NonNull Configure configure) {
        this.configure = configure;
    }
    
    /**
     * 创建ApiProvider
     * @param activity 当前Activity
     * @param serviceType 服务类型
     * @param apipClient ApipClient实例（可为null）
     * @param requestCode 请求码
     * @return 是否成功启动创建流程
     */
    public boolean createApiProvider(@NonNull Activity activity, 
                                   @NonNull Service.ServiceType serviceType, 
                                   @Nullable ApipClient apipClient, 
                                   int requestCode) {
        TimberLogger.d(TAG, "Creating ApiProvider for type: " + serviceType);
        
        // 检查是否为支持的服务类型且apipClient不为空
        if (isSupportedServiceType(serviceType) && apipClient != null) {
            // 使用InputObjectActivity创建新的ApiProvider
            return createApiProviderViaInput(activity, serviceType, requestCode);
        } else {
            // 使用InputObjectActivity创建新的ApiProvider
            return createApiProviderViaInput(activity, serviceType, requestCode);
        }
    }
    
    /**
     * 通过InputObjectActivity创建ApiProvider
     */
    private boolean createApiProviderViaInput(@NonNull Activity activity, 
                                            @NonNull Service.ServiceType serviceType, 
                                            int requestCode) {
        // 定义字段和必填状态
        Map<String, Boolean> fieldRequiredMap = new HashMap<>();
        fieldRequiredMap.put("name", true);
        fieldRequiredMap.put("type", true);
        fieldRequiredMap.put("apiUrl", true);
        fieldRequiredMap.put("orgUrl", false);
        fieldRequiredMap.put("docUrl", false);
        fieldRequiredMap.put("owner", false);
        fieldRequiredMap.put("protocols", false);
        fieldRequiredMap.put("ticks", false);
        fieldRequiredMap.put("dealerPubkey", false);
        
        // 启动InputObjectActivity
        InputObjectActivity.startForResult(activity, ApiProvider.class, fieldRequiredMap, requestCode);
        return true;
    }
    
    /**
     * 处理创建ApiProvider的结果
     * @param data Intent数据
     * @param serviceType 服务类型
     * @return 创建的ApiProvider实例，如果失败返回null
     */
    @Nullable
    public ApiProvider handleCreateResult(@Nullable Intent data, @NonNull Service.ServiceType serviceType) {
        if (data == null) {
            TimberLogger.w(TAG, "Create result data is null");
            return null;
        }
        
        String resultJson = InputObjectActivity.getResultJson(data);
        if (resultJson == null) {
            TimberLogger.w(TAG, "No result JSON found in create result");
            return null;
        }
        
        try {
            // 解析JSON并创建ApiProvider
            JsonObject jsonObject = gson.fromJson(resultJson, JsonObject.class);
            ApiProvider apiProvider = new ApiProvider();
            
            // 设置基本字段

            if (jsonObject.has("name")) {
                apiProvider.setName(jsonObject.get("name").getAsString());
            }
            if (jsonObject.has("type")) {
                String typeStr = jsonObject.get("type").getAsString();
                try {
                    apiProvider.setType(Service.ServiceType.valueOf(typeStr));
                } catch (IllegalArgumentException e) {
                    apiProvider.setType(serviceType); // 使用传入的服务类型作为默认值
                }
            } else {
                apiProvider.setType(serviceType);
            }

            if (jsonObject.has("apiUrl")) {
                apiProvider.setApiUrl(jsonObject.get("apiUrl").getAsString());
            }

            if (jsonObject.has("id")) {
                apiProvider.setId(jsonObject.get("id").getAsString());
            }else{
                apiProvider.makeSimpleId();
            }

            if (jsonObject.has("orgUrl")) {
                apiProvider.setOrgUrl(jsonObject.get("orgUrl").getAsString());
            }
            if (jsonObject.has("docUrl")) {
                apiProvider.setDocUrl(jsonObject.get("docUrl").getAsString());
            }
            if (jsonObject.has("owner")) {
                apiProvider.setOwner(jsonObject.get("owner").getAsString());
            }
            if (jsonObject.has("protocols")) {
                String protocolsStr = jsonObject.get("protocols").getAsString();
                if (!protocolsStr.isEmpty()) {
                    apiProvider.setProtocols(protocolsStr.split(","));
                }
            }
            if (jsonObject.has("ticks")) {
                String ticksStr = jsonObject.get("ticks").getAsString();
                if (!ticksStr.isEmpty()) {
                    apiProvider.setTicks(ticksStr.split(","));
                }
            }
            if (jsonObject.has("dealerPubkey")) {
                apiProvider.setDealerPubkey(jsonObject.get("dealerPubkey").getAsString());
            }
            
            // 保存到configure中
            if (apiProvider.getId() != null) {
                configure.getApiProviderMap().put(apiProvider.getId(), apiProvider);
                Configure.saveConfig();
                TimberLogger.d(TAG, "Successfully created and saved ApiProvider: " + apiProvider.getId());
                return apiProvider;
            } else {
                TimberLogger.e(TAG, "ApiProvider ID is null, cannot save");
                return null;
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing create result JSON", e);
            return null;
        }
    }
    
    /**
     * 删除ApiProvider
     * @param id ApiProvider的ID
     * @return 是否删除成功
     */
    public boolean deleteApiProvider(@NonNull String id) {
        TimberLogger.d(TAG, "Deleting ApiProvider: " + id);
        
        Map<String, ApiProvider> apiProviderMap = configure.getApiProviderMap();
        if (apiProviderMap == null) {
            TimberLogger.w(TAG, "ApiProviderMap is null");
            return false;
        }
        
        ApiProvider removed = apiProviderMap.remove(id);
        if (removed != null) {
            Configure.saveConfig();
            TimberLogger.d(TAG, "Successfully deleted ApiProvider: " + id);
            return true;
        } else {
            TimberLogger.w(TAG, "ApiProvider not found: " + id);
            return false;
        }
    }
    
    /**
     * 修改ApiProvider
     * @param activity 当前Activity
     * @param id ApiProvider的ID
     * @param requestCode 请求码
     * @return 是否成功启动修改流程
     */
    public boolean updateApiProvider(@NonNull Activity activity, @NonNull String id, int requestCode) {
        TimberLogger.d(TAG, "Updating ApiProvider: " + id);
        
        ApiProvider apiProvider = getApiProvider(id);
        if (apiProvider == null) {
            TimberLogger.w(TAG, "ApiProvider not found: " + id);
            return false;
        }
        
        return updateApiProvider(activity, apiProvider, requestCode);
    }
    
    /**
     * 修改ApiProvider
     * @param activity 当前Activity
     * @param apiProvider ApiProvider实例
     * @param requestCode 请求码
     * @return 是否成功启动修改流程
     */
    public boolean updateApiProvider(@NonNull Activity activity, @NonNull ApiProvider apiProvider, int requestCode) {
        TimberLogger.d(TAG, "Updating ApiProvider: " + apiProvider.getId());
        
        // 定义字段和必填状态
        Map<String, Boolean> fieldRequiredMap = new HashMap<>();
        fieldRequiredMap.put("name", true);
        fieldRequiredMap.put("type", true);
        fieldRequiredMap.put("apiUrl", true);
        fieldRequiredMap.put("orgUrl", false);
        fieldRequiredMap.put("docUrl", false);
        fieldRequiredMap.put("owner", false);
        fieldRequiredMap.put("protocols", false);
        fieldRequiredMap.put("ticks", false);
        fieldRequiredMap.put("dealerPubkey", false);
        
        // 将ApiProvider转换为JSON
        String originalObjectJson = gson.toJson(apiProvider);
        
        // 启动UpdateObjectActivity
        UpdateObjectActivity.startForResult(activity, ApiProvider.class, fieldRequiredMap, originalObjectJson, requestCode);
        return true;
    }
    
    /**
     * 处理修改ApiProvider的结果
     * @param data Intent数据
     * @param originalId 原始ApiProvider的ID
     * @return 修改后的ApiProvider实例，如果失败返回null
     */
    @Nullable
    public ApiProvider handleUpdateResult(@Nullable Intent data, @NonNull String originalId) {
        if (data == null) {
            TimberLogger.w(TAG, "Update result data is null");
            return null;
        }
        
        String resultJson = UpdateObjectActivity.getResultJson(data);
        if (resultJson == null) {
            TimberLogger.w(TAG, "No result JSON found in update result");
            return null;
        }
        
        try {
            // 解析JSON并更新ApiProvider
            JsonObject jsonObject = gson.fromJson(resultJson, JsonObject.class);
            ApiProvider apiProvider = getApiProvider(originalId);
            
            if (apiProvider == null) {
                TimberLogger.e(TAG, "Original ApiProvider not found: " + originalId);
                return null;
            }
            
            // 更新字段
            if (jsonObject.has("id")) {
                apiProvider.setId(jsonObject.get("id").getAsString());
            }
            if (jsonObject.has("name")) {
                apiProvider.setName(jsonObject.get("name").getAsString());
            }
            if (jsonObject.has("type")) {
                String typeStr = jsonObject.get("type").getAsString();
                try {
                    apiProvider.setType(Service.ServiceType.valueOf(typeStr));
                } catch (IllegalArgumentException e) {
                    TimberLogger.w(TAG, "Invalid service type: " + typeStr);
                }
            }
            if (jsonObject.has("apiUrl")) {
                apiProvider.setApiUrl(jsonObject.get("apiUrl").getAsString());
            }
            if (jsonObject.has("orgUrl")) {
                apiProvider.setOrgUrl(jsonObject.get("orgUrl").getAsString());
            }
            if (jsonObject.has("docUrl")) {
                apiProvider.setDocUrl(jsonObject.get("docUrl").getAsString());
            }
            if (jsonObject.has("owner")) {
                apiProvider.setOwner(jsonObject.get("owner").getAsString());
            }
            if (jsonObject.has("protocols")) {
                String protocolsStr = jsonObject.get("protocols").getAsString();
                if (!protocolsStr.isEmpty()) {
                    apiProvider.setProtocols(protocolsStr.split(","));
                }
            }
            if (jsonObject.has("ticks")) {
                String ticksStr = jsonObject.get("ticks").getAsString();
                if (!ticksStr.isEmpty()) {
                    apiProvider.setTicks(ticksStr.split(","));
                }
            }
            if (jsonObject.has("dealerPubkey")) {
                apiProvider.setDealerPubkey(jsonObject.get("dealerPubkey").getAsString());
            }
            
            // 如果ID发生了变化，需要更新Map中的key
            String newId = apiProvider.getId();
            if (!originalId.equals(newId)) {
                configure.getApiProviderMap().remove(originalId);
                configure.getApiProviderMap().put(newId, apiProvider);
            }
            
            configure.saveConfig();
            TimberLogger.d(TAG, "Successfully updated ApiProvider: " + newId);
            return apiProvider;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing update result JSON", e);
            return null;
        }
    }
    
    /**
     * 读取ApiProvider
     * @param id ApiProvider的ID
     * @return ApiProvider实例，如果不存在返回null
     */
    @Nullable
    public ApiProvider getApiProvider(@NonNull String id) {
        Map<String, ApiProvider> apiProviderMap = configure.getApiProviderMap();
        if (apiProviderMap == null) {
            return null;
        }
        return apiProviderMap.get(id);
    }
    
    /**
     * 读取某种类型的ApiProvider列表
     * @param serviceType 服务类型
     * @return 该类型的所有ApiProvider列表
     */
    @NonNull
    public List<ApiProvider> getApiProvidersByType(@NonNull Service.ServiceType serviceType) {
        List<ApiProvider> result = new ArrayList<>();
        Map<String, ApiProvider> apiProviderMap = configure.getApiProviderMap();
        
        if (apiProviderMap == null) {
            return result;
        }
        
        for (ApiProvider apiProvider : apiProviderMap.values()) {
            if (serviceType.equals(apiProvider.getType())) {
                result.add(apiProvider);
            }
        }
        
        return result;
    }
    
    /**
     * 获取所有ApiProvider
     * @return 所有ApiProvider的列表
     */
    @NonNull
    public List<ApiProvider> getAllApiProviders() {
        List<ApiProvider> result = new ArrayList<>();
        Map<String, ApiProvider> apiProviderMap = configure.getApiProviderMap();
        
        if (apiProviderMap != null) {
            result.addAll(apiProviderMap.values());
        }
        
        return result;
    }
    
    /**
     * 检查是否为支持的服务类型
     * @param serviceType 服务类型
     * @return 是否支持
     */
    private boolean isSupportedServiceType(@NonNull Service.ServiceType serviceType) {
        return serviceType == Service.ServiceType.APIP ||
               serviceType == Service.ServiceType.DISK ||
               serviceType == Service.ServiceType.MAP ||
               serviceType == Service.ServiceType.FEIP ||
               serviceType == Service.ServiceType.SWAP_HALL;
    }
    
    /**
     * 通过Service创建ApiProvider（用于从链上服务创建）
     * @param service Service实例
     * @param serviceType 服务类型
     * @return 创建的ApiProvider实例，如果失败返回null
     */
    @Nullable
    public ApiProvider createFromService(@NonNull Service service, @NonNull Service.ServiceType serviceType) {
        TimberLogger.d(TAG, "Creating ApiProvider from Service: " + service.getId());
        
        ApiProvider apiProvider = ApiProvider.fromService(service, serviceType);
        if (apiProvider != null && apiProvider.getId() != null) {
            configure.getApiProviderMap().put(apiProvider.getId(), apiProvider);
            Configure.saveConfig();
            TimberLogger.d(TAG, "Successfully created ApiProvider from Service: " + apiProvider.getId());
            return apiProvider;
        } else {
            TimberLogger.e(TAG, "Failed to create ApiProvider from Service");
            return null;
        }
    }
    
    /**
     * 通过ApipClient获取Service并创建ApiProvider
     * @param apipClient ApipClient实例
     * @param serviceId 服务ID
     * @param serviceType 服务类型
     * @return 创建的ApiProvider实例，如果失败返回null
     */
    @Nullable
    public ApiProvider createFromServiceId(@NonNull ApipClient apipClient, 
                                         @NonNull String serviceId, 
                                         @NonNull Service.ServiceType serviceType) {
        TimberLogger.d(TAG, "Creating ApiProvider from service ID: " + serviceId);
        
        try {
            // 调用apipClient.serviceByIds获取Service
            Map<String, Service> serviceMap = apipClient.serviceByIds(
                com.fc.fc_ajdk.utils.http.RequestMethod.POST, 
                AuthType.FC_SIGN_BODY, 
                serviceId
            );
            
            if (serviceMap == null || serviceMap.get(serviceId) == null) {
                TimberLogger.e(TAG, "Failed to get Service from serviceId: " + serviceId);
                return null;
            }
            
            Service service = serviceMap.get(serviceId);
            if (service == null) {return null;}
            return createFromService(service, serviceType);
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating ApiProvider from serviceId: " + serviceId, e);
            return null;
        }
    }
} 