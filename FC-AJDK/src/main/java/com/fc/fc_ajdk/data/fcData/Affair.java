package com.fc.fc_ajdk.data.fcData;

public class Affair extends FcObject {
    public static String NAME = "affair";

    //Subject
    private String fid;
    private String pubkey;

    //Operation
    private Op op; // For operating affairs.
    private String opType;

    //Object
    private String oid;

    private Object data;
    private String dataStr;

    private String fidB;
    private String pubkeyB;

    private String oidB;
    private Object dataB;

    public static Affair makeNotifyAffair(String fromFid, String recipientFid, String message) {
        // Create payment notice affair
        Affair affair = new Affair();
        affair.setOp(Op.NOTIFY);
        affair.setFid(fromFid);
        affair.setFidB(recipientFid);
        affair.setData(message);
        return affair;
    }


    public String getDataStr() {
        return dataStr;
    }

    public void setDataStr(String dataStr) {
        this.dataStr = dataStr;
    }

    public String getFidB() {
        return fidB;
    }

    public void setFidB(String fidB) {
        this.fidB = fidB;
    }

    public String getOidB() {
        return oidB;
    }

    public void setOidB(String oidB) {
        this.oidB = oidB;
    }

    public Op getOp() {
        return op;
    }

    public void setOp(Op op) {
        this.op = op;
    }

    public String getFid() {
        return fid;
    }

    public void setFid(String fid) {
        this.fid = fid;
    }

    public String getOid() {
        return oid;
    }

    public void setOid(String oid) {
        this.oid = oid;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public String getOpType() {
        return opType;
    }

    public void setOpType(String opType) {
        this.opType = opType;
    }

    public String getPubkey() {
        return pubkey;
    }

    public void setPubkey(String pubkey) {
        this.pubkey = pubkey;
    }

    public String getPubkeyB() {
        return pubkeyB;
    }

    public void setPubkeyB(String pubkeyB) {
        this.pubkeyB = pubkeyB;
    }

    public Object getDataB() {
        return dataB;
    }

    public void setDataB(Object dataB) {
        this.dataB = dataB;
    }
}
