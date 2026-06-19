package com.fc.fc_ajdk.fapi.client;

import static com.fc.fc_ajdk.constants.FieldNames.DOC;
import static com.fc.fc_ajdk.constants.Strings.API;
import static com.fc.fc_ajdk.constants.Strings.ORG;

import com.fc.fc_ajdk.data.feipData.Service;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;


public class ApiProvider extends Service {
    private static final String TAG = "ApiProvider";

    public ApiProvider() {}

    public ApiProvider(String apiUrl) {
        setApiUrl(apiUrl);
    }

    public void fromService(Service service) {
        if(service==null)return;
        this.setId(service.getId());
        this.setStdName(service.getStdName());
        this.setHome(service.getHome());
        this.setOwner(service.getOwner());
        this.makeServiceType(service.fetchServiceType());
        this.setComponents(service.getComponents());

        if(service.getProtocols()!=null && !service.getProtocols().isEmpty()) {
            this.setProtocols(service.getProtocols());
        }
        this.setDealerPubkey(service.getDealerPubkey());
        
        // Copy other Service fields
        this.setLocalNames(service.getLocalNames());
        this.setDesc(service.getDesc());
        this.setVer(service.getVer());
        this.setDealer(service.getDealer());
        this.setWaiters(service.getWaiters());
        this.setCodes(service.getCodes());
        this.setServices(service.getServices());
        this.setPricePerKB(service.getPricePerKB());
        this.setMinPayment(service.getMinPayment());
        this.setPricePerRequest(service.getPricePerRequest());
        this.setSessionDays(service.getSessionDays());
        this.setConsumeViaShare(service.getConsumeViaShare());
        this.setOrderViaShare(service.getOrderViaShare());
        this.setCurrency(service.getCurrency());
        this.setParams(service.getParams());
        this.setBirthTime(service.getBirthTime());
        this.setBirthHeight(service.getBirthHeight());
        this.setLastTxId(service.getLastTxId());
        this.setLastTime(service.getLastTime());
        this.setLastHeight(service.getLastHeight());
        this.settCdd(service.gettCdd());
        this.settRate(service.gettRate());
        this.setActive(service.getActive());
        this.setClosed(service.isClosed());
        this.setCloseStatement(service.getCloseStatement());
        this.setPricePerKBIn(service.getPricePerKBIn());
        this.setPricePerKBOut(service.getPricePerKBOut());
        this.setPricePerKBDay(service.getPricePerKBDay());
    }

    public void updateWithService(Service service) {
        if(service==null)return;
        fromService(service);
    }

    @Nullable
    public static ApiProvider fromService(Service service, Service.ServiceType type) {
        if(service==null)return null;
        ApiProvider apiProvider = new ApiProvider();
        apiProvider.fromService(service);
        
        // Ensure the type is set if provided
        if(type != null && apiProvider.fetchServiceType() == null) {
            apiProvider.makeServiceType(type);
        }

        return apiProvider;
    }

    @NotNull
    private String makeSimpleId(Service.ServiceType type) {
        return type.name() + "@" + getApiUrl();
    }

    // URL getters - retrieve from urls map
    public String getOrgUrl() {
        Map<String, String> urlMap = getHome();
        return urlMap != null ? urlMap.get(ORG) : null;
    }

    public void setOrgUrl(String orgUrl) {
        ensureHomeMap();
        getHome().put(ORG, orgUrl);
    }

    public String getDocUrl() {
        Map<String, String> urlMap = getHome();
        return urlMap != null ? urlMap.get(DOC) : null;
    }

    public void setDocUrl(String docUrl) {
        ensureHomeMap();
        getHome().put(DOC, docUrl);
    }

    public String getApiUrl() {
        Map<String, String> urlMap = getHome();
        return urlMap != null ? urlMap.get(API) : null;
    }

    public void setApiUrl(String apiUrl) {
        ensureHomeMap();
        getHome().put(API, apiUrl);
    }

    private void ensureHomeMap() {
        if (getHome() == null) {
            setHome(new HashMap<>());
        }
    }


    /**
     * Returns a map of field names to their English display names
     * @return Map of field names to English display names
     */
    public static Map<String, String> getFieldNamesEn() {
        Map<String, String> fieldNames = new LinkedHashMap<>();
        fieldNames.put("id", "Provider ID");
        fieldNames.put("stdName", "Name");
        fieldNames.put("type", "Service Type");
        fieldNames.put("components", "Components");
        fieldNames.put("orgUrl", "Organization URL");
        fieldNames.put("docUrl", "Documentation URL");
        fieldNames.put("apiUrl", "API URL");
        fieldNames.put("owner", "Owner");
        fieldNames.put("protocols", "Protocols");
        fieldNames.put("ticks", "Tickers");
        fieldNames.put("dealerPubkey", "Dealer Public Key");
        return fieldNames;
    }


    /**
     * Returns a map of field names to their Chinese display names
     * @return Map of field names to Chinese display names
     */
    public static Map<String, String> getFieldNamesZh() {
        Map<String, String> fieldNames = new LinkedHashMap<>();
        fieldNames.put("id", "提供商ID");
        fieldNames.put("stdName", "名称");
        fieldNames.put("type", "服务类型");
        fieldNames.put("components", "组件");
        fieldNames.put("orgUrl", "组织网址");
        fieldNames.put("docUrl", "文档网址");
        fieldNames.put("apiUrl", "API网址");
        fieldNames.put("owner", "所有者");
        fieldNames.put("protocols", "协议");
        fieldNames.put("ticks", "代币");
        fieldNames.put("dealerPubkey", "掌柜公钥");
        return fieldNames;
    }

    /**
     * Helper method to get name (alias for stdName for backward compatibility)
     * @return The standard name
     */
    public String getName() {
        return getStdName();
    }

    /**
     * Helper method to set name (alias for stdName for backward compatibility)
     * @param name The name to set
     */
    public void setName(String name) {
        setStdName(name);
    }
}
