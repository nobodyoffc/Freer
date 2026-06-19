package com.fc.fc_ajdk.data.fcData;

import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.IS_NOBODY;
import static com.fc.fc_ajdk.constants.FieldNames.LABEL;
import static com.fc.fc_ajdk.constants.FieldNames.PRI_KEY;
import static com.fc.fc_ajdk.constants.FieldNames.SAVE_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.WATCH_ONLY;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;

import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.utils.Hex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FreerInfo extends Freer {
    public final static String TAG = "FreerInfo";

    private String prikeyCipher;
    private String label;
    private Boolean watchOnly;


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

    //For create with user input
    public static LinkedHashMap<String, Object> getInputFieldDefaultValueMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(PRI_KEY,"");
        map.put(LABEL,"");
        map.put(IS_NOBODY,false);
        return map;
    }


    public FreerInfo(){}
    public FreerInfo(byte[] prikey, byte[] symkey) {
        super();
//        this.prikeyBytes=prikey;
        this.prikeyCipher = Encryptor.encryptBySymkeyToJson(prikey, symkey);
        this.pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey));
        this.id = KeyTools.prikeyToFid(prikey);
        makeAddresses();
    }

    public FreerInfo(String label, byte[] prikey, byte[] symkey) {
        super();
        this.prikeyBytes =prikey;
        this.prikeyCipher = Encryptor.encryptBySymkeyToJson(prikey, symkey);
        this.label = label;
        this.pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey));
        this.id = KeyTools.prikeyToFid(prikey);
        makeAddresses();
    }

    public FreerInfo(String label, String pubkey) {
        super();
        this.label = label;
        this.pubkey = pubkey;
        this.id = KeyTools.pubkeyToFchAddr(pubkey);
        this.watchOnly = true;
        makeAddresses();
    }

    public void makeAddresses() {
        btcAddr = KeyTools.pubkeyToBtcAddr(pubkey);
        ethAddr = KeyTools.pubkeyToEthAddr(pubkey);
        bchAddr = KeyTools.pubkeyToBchBesh32Addr(pubkey);
        ltcAddr = KeyTools.pubkeyToLtcAddr(pubkey);
        dogeAddr = KeyTools.pubkeyToDogeAddr(pubkey);
        trxAddr = KeyTools.pubkeyToTrxAddr(pubkey);
    }

    public static FreerInfo newKeyInfo(String label, byte[] prikey, byte[] symkey) {
        return new FreerInfo(label, prikey, symkey);
    }

    public static FreerInfo newKeyInfo(String label, String pubkey) {
        return new FreerInfo(label, pubkey);
    }

    /**
     * Converts a Freer object to a FreerInfo object
     * @param freer The Freer object to convert
     * @return A new FreerInfo object with all properties from the Freer object
     */
    public static FreerInfo fromCid(Freer freer) {
        if (freer == null) return null;
        
        FreerInfo keyInfo = new FreerInfo();
        keyInfo.setId(freer.getId());
        keyInfo.setCid(freer.getCid());
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
        
        return keyInfo;
    }
    
    /**
     * Converts a Freer object to a FreerInfo object and sets the prikeyCipher
     * @param freer The Freer object to convert
     * @param prikeyCipher The encrypted private key
     * @return A new FreerInfo object with all properties from the Freer object and the provided prikeyCipher
     */
    public static FreerInfo fromCid(Freer freer, String prikeyCipher) {
        FreerInfo keyInfo = fromCid(freer);
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
     * Gets the isNobody flag
     * @return The isNobody flag value
     */
    public Boolean getIsNobody() {
        return isNobody;
    }

}
