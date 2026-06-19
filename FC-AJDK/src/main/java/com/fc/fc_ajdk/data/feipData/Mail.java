package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.FROM;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.RECIPIENT;
import static com.fc.fc_ajdk.constants.FieldNames.SENDER;
import static com.fc.fc_ajdk.constants.FieldNames.TO;
import static com.fc.fc_ajdk.constants.FieldNames.CONTENT;
import static com.fc.fc_ajdk.constants.FieldNames.UPDATE_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.UPDATE_TIME;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;


public class Mail extends FcObject {

    private String alg;
    private String cipher;

    private String from;
    private String to;


    private Long birthTime;
    private Long birthHeight;
    private Long lastHeight;
    private Boolean active;


    private String content;
    private Boolean onChain;
    private Boolean decrypted;

    private String fromName;
    private String toName;

    private Boolean unread;
    private Long noticeFee;

    public static LinkedHashMap<String,Integer> getFieldWidthMap(){
        LinkedHashMap<String,Integer> map = new LinkedHashMap<>();
        map.put(FROM, ID_DEFAULT_SHOW_SIZE);
        map.put(TO, ID_DEFAULT_SHOW_SIZE);
        map.put(CONTENT, TEXT_DEFAULT_SHOW_SIZE);
        map.put(BIRTH_TIME, TIME_DEFAULT_SHOW_SIZE);
        map.put(LAST_HEIGHT, TIME_DEFAULT_SHOW_SIZE);
        return map;
    }

    public static List<String> getTimestampFieldList(){
        List<String> list = new ArrayList<>();
        list.add(BIRTH_TIME);
        return list;
    }

    public static List<String> getSatoshiFieldList(){
        return new ArrayList<>();
    }

    public static Map<String, String> getHeightToTimeFieldMap() {
        Map<String, String> map = new HashMap<>();
        map.put(UPDATE_HEIGHT, UPDATE_TIME);
        return map;
    }

    public static List<String> parseFidList(List<Mail> mailsToAdd) {
        Set<String> fidSet = new HashSet<>();
        for(Mail mail:mailsToAdd){
            if(mail.getFrom()!=null)fidSet.add(mail.getFrom());
            if(mail.getTo()!=null)fidSet.add(mail.getTo());
        }
        return new ArrayList<>(fidSet);
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

        // Add Mail fields
        addFieldToMap(fieldMap, "from", "From", "来自");
        addFieldToMap(fieldMap, "to", "To", "送至");
        addFieldToMap(fieldMap, "content", "Content", "内容");
        addFieldToMap(fieldMap, "birthTime", "Time", "时间");
        addFieldToMap(fieldMap, "lastHeight", "Last Height", "最新高度");
        addFieldToMap(fieldMap, "onChain", "On Chain", "链上");
        addFieldToMap(fieldMap, "cipher", "Cipher", "密文");
        addFieldToMap(fieldMap,"noticeFee","Fee","付费");

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
        map.put(SENDER, "");
        map.put(RECIPIENT, "");
        map.put(CONTENT, "");
        return map;
    }

    public byte[] toBytes() {
        return JsonUtils.toJson(this).getBytes();
    }

    public boolean parseDetail(byte[] priKey) {
        // Transfer relevant data from Mail to Mail
        CryptoDataByte cryptoDataByte = Decryptor.decryptTry(cipher, priKey, null, null);

        if (cryptoDataByte == null) {
            setDecrypted(false);
            return false;
        }

        if (cryptoDataByte.getCode() == 0) {
            setContent(new String(cryptoDataByte.getData()));
            setCipher(null);
            setDecrypted(true);
        } else {
            setContent("Failed to decrypt mail "+getId());
            setDecrypted(false);
            return false;
        }
        return true;
    }


    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public byte[] getIdBytes() {
        return Hex.fromHex(this.id);
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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
    public Long getLastHeight() {
        return lastHeight;
    }
    public void setLastHeight(Long lastHeight) {
        this.lastHeight = lastHeight;
    }


    public String getCipher() {
        return cipher;
    }

    public void setCipher(String cipher) {
        this.cipher = cipher;
    }

//    public String getCipherSend() {
//        return cipherSend;
//    }
//
//    public void setCipherSend(String cipherSend) {
//        this.cipherSend = cipherSend;
//    }
//
//    public String getCipherReci() {
//        return cipherReci;
//    }
//
//    public void setCipherReci(String cipherReci) {
//        this.cipherReci = cipherReci;
//    }

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
    public Boolean getUnread() {
        return unread;
    }

    public void setUnread(Boolean unread) {
        this.unread = unread;
    }

    public boolean encryptContent(byte[] prikey, String pubkey) {
        if (prikey==null || content==null || pubkey==null)return false;
        Encryptor encryptor = new Encryptor(AlgorithmId.FC_EccK1AesCbc256_No1_NrC7);
        CryptoDataByte cryptoDataByte;
        if(this.from.equals(this.to))
            cryptoDataByte = encryptor.encryptByAsyOneWay(content.getBytes(),Hex.fromHex(pubkey));
        else cryptoDataByte = encryptor.encryptByAsyTwoWay(content.getBytes(), prikey,Hex.fromHex(pubkey));
        if(cryptoDataByte.getCode()!=0 || cryptoDataByte.getCipher()==null)return false;
        this.cipher = cryptoDataByte.toJson();
        return true;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public String getToName() {
        return toName;
    }

    public void setToName(String toName) {
        this.toName = toName;
    }

    public void makeName() {
        if(this.fromName==null){
            this.fromName=this.from;
        }
        if(this.toName==null){
            this.toName=this.to;
        }
    }

    public Long getNoticeFee() {
        return noticeFee;
    }

    public void setNoticeFee(Long noticeFee) {
        this.noticeFee = noticeFee;
    }

}
