package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.CID;
import static com.fc.fc_ajdk.constants.FieldNames.FID;
import static com.fc.fc_ajdk.constants.FieldNames.MEMO;
import static com.fc.fc_ajdk.constants.FieldNames.NOTICE_FEE;
import static com.fc.fc_ajdk.constants.FieldNames.SEE_STATEMENT;
import static com.fc.fc_ajdk.constants.FieldNames.SEE_WRITINGS;
import static com.fc.fc_ajdk.constants.FieldNames.TITLES;
import static com.fc.fc_ajdk.constants.FieldNames.UPDATE_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.UPDATE_TIME;

import com.fc.fc_ajdk.core.crypto.Hash;

import com.fc.fc_ajdk.data.fcData.FreerInfo;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.Hex;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class Contact extends FreerInfo {

    private String alg;
    private String cipher;

    private String owner;
    private Long birthTime;
    private Boolean active;

    private String name;


    // Detail fields
    private String fid;
    private List<String> titles;
    private String memo;
    private Boolean seeStatement;
    private Boolean seeWritings;

    private Boolean onChain;
    private Boolean decrypted;


	public static LinkedHashMap<String,Integer>getFieldWidthMap(){
		LinkedHashMap<String,Integer> map = new LinkedHashMap<>();
        map.put(CID,ID_DEFAULT_SHOW_SIZE);
        map.put(FID,ID_DEFAULT_SHOW_SIZE);
        map.put(TITLES,TEXT_DEFAULT_SHOW_SIZE);
        map.put(MEMO,TEXT_DEFAULT_SHOW_SIZE);
        map.put(NOTICE_FEE,AMOUNT_DEFAULT_SHOW_SIZE);
        map.put(UPDATE_HEIGHT,TIME_DEFAULT_SHOW_SIZE);
		return map;
	}
	public static List<String> getTimestampFieldList(){
		return new ArrayList<>();
	}

	public static List<String> getSatoshiFieldList(){
		return new ArrayList<>();
	}
	public static Map<String, String> getHeightToTimeFieldMap() {
		Map<String, String> map = new HashMap<>();
		map.put(UPDATE_HEIGHT, UPDATE_TIME);
		return map;
	}

    public String checkIdWithCreate(){
        if(this.id==null || this.id.isEmpty())
            id = Hex.toHex(Hash.sha256x2(this.toBytes()));
        return id;
    }

    /**
     * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
     * @return LinkedHashMap<fieldName, Map<language, displayName>>
     */
    public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
        LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

        // Add Contact fields
        addFieldToMap(fieldMap, "fid", "FID", "FID");
        addFieldToMap(fieldMap, "titles", "Titles", "标题");
        addFieldToMap(fieldMap, "memo", "Memo", "备注");
        addFieldToMap(fieldMap, "seeStatement", "See Statement", "查看声明");
        addFieldToMap(fieldMap, "seeWritings", "See Writings", "查看文章");
        addFieldToMap(fieldMap, "updateHeight", "Update Height", "更新高度");
        addFieldToMap(fieldMap, "noticeFeeSat", "Notice Fee (Sat)", "通知费用(聪)");

        addFieldToMap(fieldMap, "pubkey", "Pubkey", "公钥");
        addFieldToMap(fieldMap, "lastHeight", "Last Height", "最新高度");
        addFieldToMap(fieldMap, "birtTime", "Birth Time", "出生时间");
        addFieldToMap(fieldMap, "onChain", "On Chain", "链上");
        addFieldToMap(fieldMap, "cipher", "Cipher On Chain", "链上密文");

        return fieldMap;
    }

    public static void addFieldToMap(LinkedHashMap<String, Map<String, String>> fieldMap, String fieldName, String englishName, String chineseName) {
        Map<String, String> languageMap = new HashMap<>();
        languageMap.put("en", englishName);
        languageMap.put("zh", chineseName);
        fieldMap.put(fieldName, languageMap);
    }

    public static LinkedHashMap<String, Object> getInputFieldDefaultValueMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(FID, "");
        map.put(TITLES, new ArrayList<>().add(""));
        map.put(MEMO, "");
        map.put(SEE_STATEMENT, true);

        map.put(SEE_WRITINGS, true);
        return map;
    }

    public byte[] toBytes(){
        return JsonUtils.toJson(this).getBytes();
    }

    public static Contact fromBytes(byte[] bytes){
        return JsonUtils.fromJson(new String(bytes), Contact.class);
    }

    public boolean parseDetail(byte[] priKey) {

        if (getCipher() != null && !getCipher().isEmpty()) {
            String cipher = getCipher();

            CryptoDataByte cryptoDataByte = Decryptor.decryptTry(cipher, priKey, null, null);

            if (cryptoDataByte.getCode() == 0) {
                // Successfully decrypted
                String decryptedContent = new String(cryptoDataByte.getData());
                Contact decryptedDetail = JsonUtils.fromJson(decryptedContent, Contact.class);

                if (decryptedDetail != null) {
                    setName(null);
                    setFid(decryptedDetail.getFid());
                    setTitles(decryptedDetail.getTitles());
                    setMemo(decryptedDetail.getMemo());
                    setSeeStatement(decryptedDetail.getSeeStatement());
                    setSeeWritings(decryptedDetail.getSeeWritings());
                    setDecrypted(true);
                    setCipher(null);

                    return true;
                }
            }
        }
        return false;
    }

    public static Contact fromCid(Freer guideFreer) {
        Contact contact = new Contact();
        contact.setFid(guideFreer.getId());
        contact.setCid(guideFreer.getCid());
        contact.setPubkey(guideFreer.getPubkey());
        contact.setNoticeFee(guideFreer.getNoticeFee());
        contact.setHome(guideFreer.getHome());
        contact.setMultisign(guideFreer.getMultisign());
        contact.makeId();
        return contact;
    }

    public String getFid() {
        return fid;
    }

    public void setFid(String fid) {
        this.fid = fid;
        if(this.name ==null && this.cid==null)
            this.name=fid;
    }

    @Override
    public void setCid(String cid) {
        this.cid = cid;
        if(cid!=null)
            if(this.name ==null || this.name.equals(this.fid))
                this.name=cid;
    }

    public void setName(String name) {
        this.name=name;
    }

    public void freshName() {
        if(this.cid!=null){
            this.name = this.cid;
        }else this.name = this.fid;
    }

    public String getName() {
        return name;
    }

    public List<String> getTitles() {
        return titles;
    }

    public void setTitles(List<String> titles) {
        this.titles = titles;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public Boolean getSeeStatement() {
        return seeStatement;
    }

    public void setSeeStatement(Boolean seeStatement) {
        this.seeStatement = seeStatement;
    }

    public Boolean getSeeWritings() {
        return seeWritings;
    }

    public void setSeeWritings(Boolean seeWritings) {
        this.seeWritings = seeWritings;
    }

    public String getAlg() {
        return alg;
    }
    public void setAlg(String alg) {
        this.alg = alg;
    }
    public String getCipher() {
        return cipher;
    }
    public void setCipher(String cipher) {
        this.cipher = cipher;
    }
    public String getOwner() {
        return owner;
    }
    public void setOwner(String owner) {
        this.owner = owner;
    }
    public Long getBirthTime() {
        return birthTime;
    }
    public void setBirthTime(Long birthTime) {
        this.birthTime = birthTime;
    }
    public Boolean getActive() {
        return active;
    }
    public void setActive(Boolean active) {
        this.active = active;
    }

    public Boolean getOnChain() {
        return onChain;
    }

    public void setOnChain(Boolean onChain) {
        this.onChain = onChain;
    }

    public Boolean getDecrypted() {
        return decrypted;
    }

    public void setDecrypted(Boolean decrypted) {
        this.decrypted = decrypted;
    }
}
