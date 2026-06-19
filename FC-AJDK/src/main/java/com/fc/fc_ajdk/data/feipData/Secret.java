package com.fc.fc_ajdk.data.feipData;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static com.fc.fc_ajdk.constants.FieldNames.*;

public class Secret extends FcObject {
    private String owner;
    private String alg;
    private String cipher;
    private Long birthTime;
    private Long birthHeight;
    private Boolean active;

    private String type;
    private String title;
    private String content;
    private String memo;
    private Long lastHeight;
    private String saveTime;
    private String contentCipher;
    private Boolean onChain;
    private Boolean decrypted;

    public Secret(){}

    public Secret(String title, String content, String memo, Type secretType) {
        super();
        this.title=title;
        this.content=content;
        this.memo=memo;
        this.type = secretType.name();
    }


    public enum Type{
        PRIKEY("prikey"),
        SYMKEY("symkey"),
        PASSWORD("password"),
        TOTP("TOTP"),
        SECRET("secret"),
        TEXT("text");
        public final String displayName;
        Type(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName(Type type){
            return type.displayName;
        }
        
        public static Type fromString(String typeString) {
            for (Type type : Type.values()) {
                if (type.toString().equalsIgnoreCase(typeString)) {
                    return type;
                }
            }
            return null;
        }
    }
    public static List<String> getSatoshiFieldList(){
        return new ArrayList<>();
    }
    public static Map<String, String> getHeightToTimeFieldMap() {
        return new HashMap<>();
    }

    public String checkIdWithCreate(){
        if(this.id==null || this.id.isEmpty())
            id = Hex.toHex(Hash.sha256x2(this.toBytes()));
        return id;
    }

    public static LinkedHashMap<String,Integer> getFieldWidthMap(){
        LinkedHashMap<String,Integer> fieldWidthMap = new LinkedHashMap<>();
        fieldWidthMap.put(TITLE, TEXT_DEFAULT_SHOW_SIZE);
        fieldWidthMap.put(TYPE, TEXT_MEDIUM_DEFAULT_SHOW_SIZE);
        fieldWidthMap.put(COSIGNERS_INVITED, BOOLEAN_DEFAULT_SHOW_SIZE);
        fieldWidthMap.put(SAVE_TIME, TIME_DEFAULT_SHOW_SIZE);
        fieldWidthMap.put(MEMO, TEXT_DEFAULT_SHOW_SIZE);
        fieldWidthMap.put(ID, ID_DEFAULT_SHOW_SIZE);
        return fieldWidthMap;
    }

    public static List<String> getTimestampFieldList(){
        List<String> timestampFieldList = new ArrayList<>();
        timestampFieldList.add(SAVE_TIME);
        return timestampFieldList;
    }

    public static Map<String,String> getShowFieldNameAs(){
        return null;
    }
    
    /**
     * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
     * @return LinkedHashMap<fieldName, Map<language, displayName>>
     */
    public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
        LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();
        
        // Add Secret fields (lines 32-38)
        addFieldToMap(fieldMap, "type", "Type", "类型");
        addFieldToMap(fieldMap, "title", "Title", "标题");
        addFieldToMap(fieldMap, "content", "Content", "内容");
        addFieldToMap(fieldMap, "memo", "Memo", "备注");
        addFieldToMap(fieldMap, "lastHeight", "Last Height", "最新高度");
        addFieldToMap(fieldMap, "saveTime", "Save Time", "保存时间");
        addFieldToMap(fieldMap, "contentCipher", "Content Cipher", "内容密文");
        addFieldToMap(fieldMap, "onChain", "On Chain", "链上");
        addFieldToMap(fieldMap, "owner", "Owner", "所有者");
        addFieldToMap(fieldMap, "cipher", "Cipher On Chain", "链上密文");
        
        return fieldMap;
    }


    public byte[] toBytes() {
        return JsonUtils.toJson(this).getBytes();
    }

    public static Secret fromBytes(byte[] bytes) {
        return JsonUtils.fromJson(new String(bytes), Secret.class);
    }

    public boolean parseDetail(byte[] priKey, byte[] pubkey) {
        if(priKey==null)return false;

        if (getCipher() != null && !getCipher().isEmpty()) {
            // Use Decryptor.decryptTry for unified decryption handling
            CryptoDataByte cryptoDataByte = Decryptor.decryptTry(getCipher(), priKey, null, null);

            if (cryptoDataByte != null && cryptoDataByte.getCode() == 0) {
                // Successfully decrypted
                try {
                    String decrypted = new String(cryptoDataByte.getData());
                    Secret decryptedDetail = JsonUtils.fromJson(decrypted, Secret.class);
                    makeDecryptedSecret(decryptedDetail, pubkey);
                    return true;
                } catch (Exception e) {
                    // JSON parsing failed, but decryption was successful
                    return false;
                }
            }

            return false; // Decryption failed
        }
        return false;
    }


    private void makeDecryptedSecret(Secret decryptedDetail, byte[] pubkey) {
        if (decryptedDetail != null) {
            setTitle(decryptedDetail.getTitle());
            setType(decryptedDetail.getType());
            setMemo(decryptedDetail.getMemo());
            setContentCipher(new Encryptor(AlgorithmId.FC_EccK1AesCbc256_No1_NrC7).encryptByAsyOneWay(decryptedDetail.content.getBytes(), pubkey).toJson());
            setCipher(null);
            setContent(null);
            setDecrypted(true);
        }
    }

    /**
     * Decrypts the content cipher using the provided private key
     * @param priKey Private key for decryption
     * @return Decrypted content or null if decryption fails
     */
    public String decryptContent(byte[] priKey) {
        if (contentCipher == null || contentCipher.isEmpty() || priKey == null) {
            return null;
        }

        try {
            // Use Decryptor.decryptTry for unified decryption handling
            CryptoDataByte cryptoDataByte = Decryptor.decryptTry(contentCipher, priKey, null, null);

            if (cryptoDataByte != null && cryptoDataByte.getCode() == 0) {
                // Successfully decrypted
                this.content = new String(cryptoDataByte.getData());
                return content;
            }
        } catch (Exception e) {
            // Decryption failed
        }

        return null;
    }


    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public Long getLastHeight() {
        return lastHeight;
    }

    public void setLastHeight(Long lastHeight) {
        this.lastHeight = lastHeight;
    }

    public String getContentCipher() {
        return contentCipher;
    }

    public void setContentCipher(String contentCipher) {
        this.contentCipher = contentCipher;
    }

    public String getSaveTime() {
        return saveTime;
    }

    public void setSaveTime(String saveTime) {
        this.saveTime = saveTime;
    }

    public Boolean getOnChain() {
        return onChain;
    }

    public void setOnChain(Boolean onChain) {
        this.onChain = onChain;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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

    public String getAlg() {
        return alg;
    }

    public void setAlg(String alg) {
        this.alg = alg;
    }

    public Long getBirthTime() {
        return birthTime;
    }

    public void setBirthTime(Long birthTime) {
        this.birthTime = birthTime;
    }

    public Long getBirthHeight() {
        return birthHeight;
    }

    public void setBirthHeight(Long birthHeight) {
        this.birthHeight = birthHeight;
    }

    public Boolean getDecrypted() {
        return decrypted;
    }

    public void setDecrypted(Boolean decrypted) {
        this.decrypted = decrypted;
    }
}
