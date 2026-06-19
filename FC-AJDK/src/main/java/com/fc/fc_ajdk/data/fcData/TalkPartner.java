package com.fc.fc_ajdk.data.fcData;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.fc.fc_ajdk.constants.FieldNames.CID;
import static com.fc.fc_ajdk.constants.FieldNames.ID;

/**
 * A local talk partner for IM.
 * Different from on-chain Contacts -- TalkPartners are lightweight, local-only entries
 * representing people the user has intentionally added to chat with.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TalkPartner extends FcEntity {
    private String fid;
    private String pubkey;
    private String cid;
    private Map<String, String> home;
    private String lastTalkVia;
    private Long addedAt;
    private Long lastTalkedAt;
    private String source;

    public static final String SOURCE_FUDP_DISCOVER = "FUDP_DISCOVER";
    public static final String SOURCE_CONTACT = "CONTACT";
    public static final String SOURCE_SEARCH = "SEARCH";

    public TalkPartner() {}

    public TalkPartner(String fid, String pubkey) {
        this.fid = fid;
        this.id = fid;
        this.pubkey = pubkey;
        this.addedAt = System.currentTimeMillis();
    }

    public static LinkedHashMap<String, Integer> getFieldWidthMap() {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
        map.put(ID, ID_DEFAULT_SHOW_SIZE);
        map.put(CID, ID_DEFAULT_SHOW_SIZE);
        return map;
    }

    public static List<String> getTimestampFieldList() {
        return List.of("addedAt", "lastTalkedAt");
    }

    public static List<String> getSatoshiFieldList() {
        return new ArrayList<>();
    }

    public static Map<String, String> getHeightToTimeFieldMap() {
        return new HashMap<>();
    }

    public static Map<String, String> getShowFieldNameAsMap() {
        return new HashMap<>();
    }

    public static LinkedHashMap<String, Object> getInputFieldDefaultValueMap() {
        return new LinkedHashMap<>();
    }

    public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
        LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();
        addFieldToMap(fieldMap, "fid", "FID", "FID");
        addFieldToMap(fieldMap, "pubkey", "Public Key", "公钥");
        addFieldToMap(fieldMap, "cid", "CID", "CID");
        addFieldToMap(fieldMap, "home", "Home", "主页");
        addFieldToMap(fieldMap, "lastTalkVia", "Last Talk Via", "最近联系方式");
        addFieldToMap(fieldMap, "addedAt", "Added At", "添加时间");
        addFieldToMap(fieldMap, "lastTalkedAt", "Last Talked At", "最近聊天时间");
        addFieldToMap(fieldMap, "source", "Source", "来源");
        return fieldMap;
    }

    // Getters and Setters

    public String getFid() { return fid; }
    public void setFid(String fid) {
        this.fid = fid;
        this.id = fid;
    }

    public String getPubkey() { return pubkey; }
    public void setPubkey(String pubkey) { this.pubkey = pubkey; }

    public String getCid() { return cid; }
    public void setCid(String cid) { this.cid = cid; }

    public Map<String, String> getHome() { return home; }
    public void setHome(Map<String, String> home) { this.home = home; }

    public String getLastTalkVia() { return lastTalkVia; }
    public void setLastTalkVia(String lastTalkVia) { this.lastTalkVia = lastTalkVia; }

    public Long getAddedAt() { return addedAt; }
    public void setAddedAt(Long addedAt) { this.addedAt = addedAt; }

    public Long getLastTalkedAt() { return lastTalkedAt; }
    public void setLastTalkedAt(Long lastTalkedAt) { this.lastTalkedAt = lastTalkedAt; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
