package com.fc.fc_ajdk.data.apipData;

import static com.fc.fc_ajdk.constants.FieldNames.ALG;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.data.fcData.Op;
import com.fc.fc_ajdk.utils.BytesUtils;

import java.util.HashMap;
import java.util.Map;

public class RequestBody extends FcEntity {
    private String sid;
    private Op op;
    private String url;
    private Long time;
    private Integer nonce;
    private String via;
    private Object data;
    private Fcdsl fcdsl;

    public void renew() {
        this.nonce = Math.abs(BytesUtils.bytesToIntBE(BytesUtils.getRandomBytes(4)));
        this.time = System.currentTimeMillis();
    }

    public RequestBody() {
        this.nonce = Math.abs(BytesUtils.bytesToIntBE(BytesUtils.getRandomBytes(4)));
        this.time = System.currentTimeMillis();
    }

    public RequestBody(Op op,Object data) {
        this.nonce = Math.abs(BytesUtils.bytesToIntBE(BytesUtils.getRandomBytes(4)));
        this.time = System.currentTimeMillis();
        this.op = op;
        this.data = data;
    }

    public RequestBody(String url, String via) {
        setTime(System.currentTimeMillis());
        this.nonce = Math.abs(BytesUtils.bytesToIntBE(BytesUtils.getRandomBytes(4)));
        setVia(via);
        setUrl(url);
    }

    public RequestBody(String url, String via, SignInMode signInMode) {
        setTime(System.currentTimeMillis());
        this.nonce = Math.abs(BytesUtils.bytesToIntBE(BytesUtils.getRandomBytes(4)));
        setVia(via);
        setUrl(url);

        if (signInMode != null) {
            if(fcdsl==null)fcdsl = new Fcdsl();
            Map<String, String> other = new HashMap<>();
            other.put(FieldNames.MODE,signInMode.name());
            other.put(ALG, AlgorithmId.FC_EccK1AesCbc256_No1_NrC7.getDisplayName());
            fcdsl.setOther(other);
        }
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public Integer getNonce() {
        return nonce;
    }

    public void setNonce(Integer nonce) {
        this.nonce = nonce;
    }

    public String getVia() {
        return via;
    }

    public void setVia(String via) {
        this.via = via;
    }

    public Fcdsl getFcdsl() {
        return fcdsl;
    }

    public void setFcdsl(Fcdsl fcdsl) {
        this.fcdsl = fcdsl;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }

    public Op getOp() {
        return op;
    }

    public void setOp(Op op) {
        this.op = op;
    }
}
