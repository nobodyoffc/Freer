package com.fc.freer.model;

import com.fc.fc_ajdk.data.fcData.FcSession;

public abstract class FcHttpRequest {

    protected ApiEvent apiEvent;
    protected byte[] symkey;
    protected FcSession serverSession;

    public FcHttpRequest() {}
    public FcHttpRequest(byte[] symkey) {
        this.symkey = symkey;
    }
    public FcHttpRequest(ApiEvent apiEvent,byte[] symkey, FcSession serverSession) {
        this.apiEvent = apiEvent;
        this.symkey = symkey;
        this.serverSession = serverSession;
    }

    public ApiEvent getApiEvent() {
        return apiEvent;
    }

    public void setApiEvent(ApiEvent apiEvent) {
        this.apiEvent = apiEvent;
    }

    public byte[] getSymkey() {
        return symkey;
    }

    public void setSymkey(byte[] symkey) {
        this.symkey = symkey;
    }

    public FcSession getServerSession() {
        return serverSession;
    }

    public void setServerSession(FcSession serverSession) {
        this.serverSession = serverSession;
    }
}
