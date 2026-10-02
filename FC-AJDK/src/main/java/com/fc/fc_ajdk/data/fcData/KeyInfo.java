package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import static com.fc.fc_ajdk.constants.FieldNames.SAVE_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.IS_NOBODY;
import static com.fc.fc_ajdk.constants.FieldNames.LABEL;
import static com.fc.fc_ajdk.constants.FieldNames.PRI_KEY;
import static com.fc.fc_ajdk.constants.FieldNames.WATCH_ONLY;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;

import androidx.annotation.NonNull;

public class KeyInfo extends Freer {
    public final static String TAG = "KeyInfo";
    public static final String KEY_INFO_FILE_PATH = "keyInfo.json";

    private String prikeyCipher;
    private String label;
    private Boolean watchOnly;
    private String saveTime;

    public static LinkedHashMap<String,Integer> getFieldWidthMap(){
        LinkedHashMap<String,Integer> map = new LinkedHashMap<>();
        map.put(ID,ID_DEFAULT_SHOW_SIZE);
        map.put(LABEL,ID_DEFAULT_SHOW_SIZE);
        map.put(SAVE_TIME,TIME_DEFAULT_SHOW_SIZE);
        map.put(IS_NOBODY,10);
        map.put(WATCH_ONLY,12);
        return map;
    }
    public static List<String> getTimestampFieldList(){
        return new ArrayList<>();
    }

    public static List<String> getSatoshiFieldList(){
        return new ArrayList<>();
    }
    public static Map<String, String> getHeightToTimeFieldMap() {
        return new HashMap<>();
    }

    public static KeyInfo createNew(byte[] symkey){
        FcSubject fcSubject = FcSubject.createNew();
        byte[] prikeyBytes = fcSubject.getPrikeyBytes();

        // Create FreerInfo
        return KeyInfo.newKeyInfo(null, prikeyBytes, symkey);
    }

    public static Map<String, String> getShowFieldNameAsMap() {
        Map<String,String> map = new HashMap<>();
        map.put(ID,"FID");
        map.put(LABEL,"Label");
        map.put(SAVE_TIME,"Save Time");
        map.put(IS_NOBODY,"Nobody");
        map.put(WATCH_ONLY,"Watch Only");
        return map;
    }
    public static Map<String, String> getFieldOrderMap() {
        Map<String, String> map = new HashMap<>();
        map.put(SAVE_TIME,DESC);
        map.put(LABEL,ASC);
        return map;
    }

    public static List<String> getReplaceWithMeFieldList() {
        return new ArrayList<>();
    }

    /**
     * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
     * @return LinkedHashMap<fieldName, Map<language, displayName>>
     */
    public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
        LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();
        
        // Add KeyInfo fields first (lines 32-35)
        addFieldToMap(fieldMap, "prikeyCipher", "Prikey Cipher", "私钥密文");
        addFieldToMap(fieldMap, "label", "Label", "标签");
        addFieldToMap(fieldMap, "watchOnly", "Watch Only", "观察");
        addFieldToMap(fieldMap, "saveTime", "Save Time", "保存时间");
        
        // Add Freer fields by calling Freer.getFieldNameMap()
        fieldMap.putAll(Freer.getFieldNameMap());
        
        return fieldMap;
    }
    
//    private static void addFieldToMap(LinkedHashMap<String, Map<String, String>> fieldMap, String fieldName, String enName, String zhName) {
//        Map<String, String> languageMap = new HashMap<>();
//        languageMap.put("en", enName);
//        languageMap.put("zh", zhName);
//        fieldMap.put(fieldName, languageMap);
//    }

    //For create with user input
    public static LinkedHashMap<String, Object> getInputFieldDefaultValueMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(PRI_KEY,"");
        map.put(LABEL,"");
        map.put(IS_NOBODY,false);
        return map;
    }


    public KeyInfo(){}
    public KeyInfo(byte[] prikey, byte[] symkey) {
        super();
//        this.prikeyBytes=prikey;
        this.prikeyCipher = Encryptor.encryptBySymkeyToJson(prikey, symkey);
        this.pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey));
        this.id = KeyTools.prikeyToFid(prikey);
        makeAddresses();
    }

    public KeyInfo(String label, byte[] prikey, byte[] symkey) {
        super();
        this.prikeyBytes =prikey;
        if(symkey!=null)
            this.prikeyCipher = Encryptor.encryptBySymkeyToJson(prikey, symkey);
        if(label!=null&& !label.isEmpty())this.label = label;
        this.pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey));
        this.id = KeyTools.prikeyToFid(prikey);
        makeAddresses();
    }

    public KeyInfo(String fid) {
        super();
        this.id = fid;
    }

    public KeyInfo(String label, String pubkey) {
        super();
        this.label = label;
        this.pubkey = pubkey;
        this.id = KeyTools.pubkeyToFchAddr(pubkey);
        this.watchOnly = true;
        makeAddresses();
    }

    public static KeyInfo newFromFid(String fid,String label){
        KeyInfo keyInfo = new KeyInfo();
        if(!KeyTools.isGoodFid(fid))return null;
        keyInfo.setLabel(label);
        keyInfo.setId(fid);
        keyInfo.setWatchOnly(true);
        keyInfo.makeAddrsFromId(fid);
        return keyInfo;
    }

    public static KeyInfo fromContact(Contact contact) {
        KeyInfo keyInfo = new KeyInfo();
        if(contact.getLabel()!=null){
            keyInfo.setLabel(contact.getLabel());
        }else{
            keyInfo.setLabel(StringUtils.listToString(contact.getTitles()));
        }
        keyInfo.setPubkey(contact.getPubkey());
        keyInfo.setId(contact.getFid());
        keyInfo.setCid(contact.getCid());
        keyInfo.setLastHeight(contact.getLastHeight());
        return keyInfo;
    }

    public void makeAddresses() {
        btcAddr = KeyTools.pubkeyToBtcAddr(pubkey);
        ethAddr = KeyTools.pubkeyToEthAddr(pubkey);
        bchAddr = KeyTools.pubkeyToBchBesh32Addr(pubkey);
        ltcAddr = KeyTools.pubkeyToLtcAddr(pubkey);
        dogeAddr = KeyTools.pubkeyToDogeAddr(pubkey);
        trxAddr = KeyTools.pubkeyToTrxAddr(pubkey);
    }

    public void makeAddrsFromId(String fid) {
        byte[] hash160 = KeyTools.addrToHash160(fid);

        btcAddr = KeyTools.hash160ToBtcAddr(hash160);
        bchAddr = KeyTools.hash160ToBchBech32Addr(hash160);
        ltcAddr = KeyTools.hash160ToLtcAddr(hash160);
        dogeAddr = KeyTools.hash160ToDogeAddr(hash160);
    }

    public static KeyInfo newKeyInfo(String label, byte[] prikey, byte[] symkey) {
        return new KeyInfo(label, prikey, symkey);
    }

    public static KeyInfo newKeyInfo(String label, String pubkey) {
        return new KeyInfo(label, pubkey);
    }

    /**
     * Converts a Freer object to a FreerInfo object
     * @param freer The Freer object to convert
     * @return A new FreerInfo object with all properties from the Freer object
     */
    public static KeyInfo fromCid(Freer freer) {
        if (freer == null) return null;
        
        KeyInfo keyInfo = new KeyInfo();
        keyInfo.setId(freer.getId());
        keyInfo.setCid(freer.getCid());
        keyInfo.setUsedCids(freer.getUsedCids());
        keyInfo.setHome(freer.getHome());
        keyInfo.setNobody(freer.getNobody());
        keyInfo.setPrikey(freer.getPrikey());
        keyInfo.setNoticeFee(freer.getNoticeFee());
        keyInfo.setPubkey(freer.getPubkey());
        keyInfo.setMaster(freer.getMaster());
        keyInfo.setBalance(freer.getBalance());
        keyInfo.setCash(freer.getCash());
        keyInfo.setIncome(freer.getIncome());
        keyInfo.setExpend(freer.getExpend());
        keyInfo.setCd(freer.getCd());
        keyInfo.setCdd(freer.getCdd());
        keyInfo.setReputation(freer.getReputation());
        keyInfo.setHot(freer.getHot());
        keyInfo.setWeight(freer.getWeight());
        keyInfo.setGuide(freer.getGuide());
        keyInfo.setNoticeFee(freer.getNoticeFee());
        keyInfo.setHome(freer.getHome());
        keyInfo.setBtcAddr(freer.getBtcAddr());
        keyInfo.setEthAddr(freer.getEthAddr());
        keyInfo.setLtcAddr(freer.getLtcAddr());
        keyInfo.setDogeAddr(freer.getDogeAddr());
        keyInfo.setTrxAddr(freer.getTrxAddr());
        keyInfo.setBchAddr(freer.getBchAddr());
        keyInfo.setBirthHeight(freer.getBirthHeight());
        keyInfo.setNameTime(freer.getNameTime());
        keyInfo.setLastHeight(freer.getLastHeight());
        keyInfo.setMultisign(freer.getMultisign());
        
        return keyInfo;
    }
    
    /**
     * Converts a Freer object to a FreerInfo object and sets the prikeyCipher
     * @param freer The Freer object to convert
     * @param prikeyCipher The encrypted private key
     * @return A new FreerInfo object with all properties from the Freer object and the provided prikeyCipher
     */
    public static KeyInfo fromCid(Freer freer, String prikeyCipher) {
        KeyInfo keyInfo = fromCid(freer);
        if (keyInfo != null) {
            keyInfo.setPrikeyCipher(prikeyCipher);
        }
        return keyInfo;
    }
    public String getPrikeyCipher() {
        return prikeyCipher;
    }

    public void setPrikeyCipher(String prikeyCipher) {
        this.prikeyCipher = prikeyCipher;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Boolean getWatchOnly() {
        return watchOnly;
    }

    public void setWatchOnly(Boolean watchOnly) {
        this.watchOnly = watchOnly;
    }

    public byte[] decryptPrikey(byte[] symkey) {
        return Decryptor.decryptPrikey(prikeyCipher,symkey);
    }

    public String encryptPrikey(byte[] symkey) {
        if(prikeyBytes ==null || prikeyBytes.length==0)return null;
        this.prikeyCipher = Encryptor.encryptBySymkeyToJson(prikeyBytes, symkey);
        return this.prikeyCipher;
    }

    /**
     * Moves a plain {@code prikey} (hex or WIF, as a key list exported elsewhere carries it)
     * into {@code prikeyCipher} under the symkey and clears it, so the key is never stored in
     * the clear and the identity can sign. Fills the pubkey and addresses when the list left
     * them out. Does nothing when there is no plain prikey or a cipher is already there.
     * @return false if the plain prikey is not a valid key or belongs to another FID
     */
    public boolean sealPlainPrikey(byte[] symkey) {
        String plain = getPrikey();
        if (plain == null || plain.isEmpty() || prikeyCipher != null || symkey == null) return true;
        byte[] prikey32 = null;
        try {
            prikey32 = KeyTools.getPrikey32(plain.trim());
            if (prikey32 == null || prikey32.length != 32) return false;
            String fid = KeyTools.prikeyToFid(prikey32);
            if (id != null && !id.equals(fid)) return false;
            this.prikeyCipher = Encryptor.encryptBySymkeyToJson(prikey32, symkey);
            if (this.prikeyCipher == null) return false;
            this.id = fid;
            if (pubkey == null) {
                this.pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey32));
                makeAddresses();
            }
            this.watchOnly = null;
            setPrikey(null);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (prikey32 != null) java.util.Arrays.fill(prikey32, (byte) 0);
        }
    }
    @NonNull
    public static KeyInfo updateFromCid(Freer freerInfo, KeyInfo currentKeyInfo) {
        KeyInfo updatedKeyInfo = KeyInfo.fromCid(freerInfo);
        updatedKeyInfo.setLabel(currentKeyInfo.getLabel());
        updatedKeyInfo.setPrikeyCipher(currentKeyInfo.getPrikeyCipher());
        updatedKeyInfo.setWatchOnly(currentKeyInfo.getWatchOnly());
        updatedKeyInfo.setSaveTime(currentKeyInfo.getSaveTime());

        // Only preserve current multisig if the new one from API is null
        if (freerInfo.getMultisign() == null) {
            updatedKeyInfo.setMultisign(currentKeyInfo.getMultisign());
        }
        // Otherwise keep the fresh multisig data from API response (already set by fromCid)
        return updatedKeyInfo;
    }
    /**
     * Gets the isNobody flag
     * @return The isNobody flag value
     */
    public Boolean getIsNobody() {
        return isNobody;
    }

    public String getSaveTime() {
        return saveTime;
    }

    public void setSaveTime(String saveTime) {
        this.saveTime = saveTime;
    }
}
