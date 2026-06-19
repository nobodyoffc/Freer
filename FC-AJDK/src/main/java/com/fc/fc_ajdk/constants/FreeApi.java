package com.fc.fc_ajdk.constants;

import com.fc.fc_ajdk.data.feipData.Service;

public class FreeApi {
    private String url;
    private Boolean active;
    private String sid;
    private Service.ServiceType serviceType;

    public FreeApi() {
    }

    public FreeApi(String url, Boolean active, Service.ServiceType serviceType) {
        this.active = active;
        this.url = url;
        this.serviceType = serviceType;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }

    public Service.ServiceType getApiType() {
        return serviceType;
    }

    public void setApiType(Service.ServiceType serviceType) {
        this.serviceType = serviceType;
    }
}
