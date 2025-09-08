package com.fc.freer.model;


import static com.fc.fc_ajdk.constants.Strings.URL_HEAD;
import static com.fc.fc_ajdk.constants.Tickers.FCH;
import static com.fc.fc_ajdk.ui.Inputer.askIfYes;
import static com.fc.fc_ajdk.ui.Inputer.promptAndUpdate;

import com.fc.fc_ajdk.clients.ApipClient;
import com.fc.fc_ajdk.clients.FcClient;
import com.fc.fc_ajdk.config.Configure;
import com.fc.fc_ajdk.config.Settings;
import com.fc.fc_ajdk.constants.FreeApi;
import com.fc.fc_ajdk.constants.Strings;
import com.fc.fc_ajdk.data.fcData.ReplyBody;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.serviceParams.ApipParams;
import com.fc.fc_ajdk.data.feipData.serviceParams.DiskParams;
import com.fc.fc_ajdk.data.feipData.serviceParams.Params;
import com.fc.fc_ajdk.ui.Inputer;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.HttpUtils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;


public class ApiProvider {
    private static final String TAG = "ApiProvider";
    private String id;
    private String name;
    private Service.ServiceType type;
    private String orgUrl;
    private String docUrl;
    private String apiUrl;
    private String owner;
    private String[] protocols;
    private String[] ticks;
    private transient Service service;
    private transient Params apiParams;
    private String dealerPubkey;

    public ApiProvider() {}
    public ApiProvider(String apiUrl) {
        this.apiUrl = apiUrl;
    }
    public boolean fromService(Service service, Class<?extends Params> tClass) {
        if(service==null)return false;
        this.id =service.getId();
        this.name = service.getStdName();
        Params params = Params.getParamsFromService(service, tClass);
        if(params==null) return false;
        this.apiUrl=params.getUrlHead();
        this.owner=service.getOwner();
        for(String type : service.getTypes()){
            try{
                this.type= Service.ServiceType.valueOf(type);
                break;
            }catch (Exception ignore){
                Timber.e("Failed to get the type of the service: %s", service.getStdName());
            }
        }
        if(service.getUrls().length>0)this.orgUrl=service.getUrls()[0];
        if(service.getProtocols()!=null && service.getProtocols().length>0)this.protocols=service.getProtocols();
        this.apiParams=Params.getParamsFromService(service,tClass);
        this.service=service;
        return true;
    }

    public boolean updateWithService(Service service, Service.ServiceType serviceType) {
        if(service==null)return false;
        Class<? extends Params> tClass = switch (serviceType) {
            case APIP -> ApipParams.class;
            case DISK -> DiskParams.class;
            default -> {
                TimberLogger.e(TAG, "The type of the service is not supported: %s", service.getStdName());
                yield null;
            }
        };
        if(tClass == null) return false;
        return fromService(service, tClass);
    }

    @Nullable
    public static ApiProvider fromService(Service service, Service.ServiceType type) {
        if(service==null)return null;
        ApiProvider apiProvider = new ApiProvider();
        apiProvider.setId(service.getId());
        Params params = null;
        switch (type){
            case APIP -> params = Params.getParamsFromService(service, ApipParams.class);
            case DISK -> params = Params.getParamsFromService(service, DiskParams.class);
            default -> {
                TimberLogger.e(TAG,"The type of the service is not supported: %s", service.getStdName());
                return null;
            }
        }

        if(params==null) return null;
        apiProvider.setApiUrl(params.getUrlHead());
        apiProvider.setOwner(service.getOwner());
        for(String typeStr : service.getTypes()){
            try{
                apiProvider.setType(Service.ServiceType.valueOf(typeStr));
                break;
            }catch (Exception ignore){}
        }
        if(service.getUrls()!=null&&service.getUrls().length>0)apiProvider.setOrgUrl(service.getUrls()[0]);
        if(service.getProtocols()!=null&&service.getProtocols().length>0)apiProvider.setProtocols(service.getProtocols());
        apiProvider.setApiParams((Params) service.getParams());
        apiProvider.setService(service);
        apiProvider.setType(type);

        return apiProvider;
    }

    @NotNull
    private String makeSimpleId(Service.ServiceType type) {
        return type.name() + "@" + apiUrl;
    }

    @NotNull
    private String makeNasaId() {
        return ticks[0] + "@" + apiUrl;
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Service.ServiceType getType() {
        return type;
    }

    public void setType(Service.ServiceType type) {
        this.type = type;
    }

    public String getOrgUrl() {
        return orgUrl;
    }

    public void setOrgUrl(String orgUrl) {
        this.orgUrl = orgUrl;
    }

    public String getDocUrl() {
        return docUrl;
    }

    public void setDocUrl(String docUrl) {
        this.docUrl = docUrl;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String[] getTicks() {
        return ticks;
    }

    public void setTicks(String[] ticks) {
        this.ticks = ticks;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    public Params getApiParams() {
        return apiParams;
    }

    public void setApiParams(Params apiParams) {
        this.apiParams = apiParams;
    }

    public String[] getProtocols() {
        return protocols;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDealerPubkey() {
        return dealerPubkey;
    }

    public void setDealerPubkey(String dealerPubkey) {
        this.dealerPubkey = dealerPubkey;
    }

    public String makeSimpleId() {
        return type.name() + "@" + apiUrl;
    }

    /**
     * Returns a map of field names to their English display names
     * @return Map of field names to English display names
     */
    public static Map<String, String> getFieldNamesEn() {
        Map<String, String> fieldNames = new LinkedHashMap<>();
        fieldNames.put("id", "Provider ID");
        fieldNames.put("name", "Name");
        fieldNames.put("type", "Service Type");
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
        fieldNames.put("name", "名称");
        fieldNames.put("type", "服务类型");
        fieldNames.put("orgUrl", "组织网址");
        fieldNames.put("docUrl", "文档网址");
        fieldNames.put("apiUrl", "API网址");
        fieldNames.put("owner", "所有者");
        fieldNames.put("protocols", "协议");
        fieldNames.put("ticks", "代币");
        fieldNames.put("dealerPubkey", "经销商公钥");
        return fieldNames;
    }

}
