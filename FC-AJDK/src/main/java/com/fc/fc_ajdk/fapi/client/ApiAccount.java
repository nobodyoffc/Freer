package com.fc.fc_ajdk.fapi.client;

import static com.fc.fc_ajdk.data.fcData.AlgorithmId.FC_EccK1AesCbc256_No1_NrC7;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.FcSession;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.IdNameUtils;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.List;

public class ApiAccount {
    public final static String TAG = "ApiAccount";
    public static long orderRequestTimes = 10000;
    private String id;
    private String providerId;
    private String userId;
    private String userName;
    private String passwordCipher;
    private String userPubkey;
    private String userPrikeyCipher;
    private FcSession fcSession;
    private Double minPayment;
    private List<Payment> payments;
    private String apiUrl;
    private String via;
    private long bestHeight;
    private String bestBlockId;
    private Long balance;
    private Long remainRequestCount;
    private int timeout;
    private transient Object client;
    private transient FapiClient fapiClient;
    private transient byte[] password;

    private transient byte[] sessionKey;
    private transient Service service;

    public ApiAccount() {
    }

    public ApiAccount(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public static void updateSession(ApiAccount apipAccount, byte[] symkey, FcSession fcSession, byte[] newSessionKey) {
        String newSessionKeyCipher = new Encryptor(FC_EccK1AesCbc256_No1_NrC7).encryptToJsonBySymkey(newSessionKey, symkey);
        if(newSessionKeyCipher.contains("Error"))return;
        fcSession.setKeyCipher(newSessionKeyCipher);
        apipAccount.fcSession.setKeyCipher(newSessionKeyCipher);
        String newSessionName = IdNameUtils.makeKeyName(newSessionKey);
        apipAccount.fcSession.setId(newSessionName);
    }

    private boolean checkApiGeneralParams(ApiProvider apiProvider){
        if(!providerId.equals(apiProvider.getId())){
            TimberLogger.e("The SID of apiProvider %s is not the same as the SID %s in apiAccount.", apiProvider.getId(), providerId);
            return false;
        }

        if (apiUrl==null && apiProvider.getApiUrl() == null) {
            TimberLogger.e("The apiUrl is required.");
            return false;
        }

        if(! apiProvider.getApiUrl().equals(this.apiUrl)){
            this.apiUrl = apiProvider.getApiUrl();
        }
        return true;
    }


    public static String makeApiAccountId(String sid, String userName) {
        byte[] bundleBytes;
        if(userName!=null) {
            bundleBytes = BytesUtils.bytesMerger(sid.getBytes(), userName.getBytes());
        }else bundleBytes = sid.getBytes();
        return Hex.toHex(Hash.sha256x2(bundleBytes));
    }

    public static String makePubkey(String userPrikeyCipher, byte[] symkey) {
        Decryptor decryptor = new Decryptor();
        CryptoDataByte cryptoDataByte = decryptor.decryptJsonBySymkey(userPrikeyCipher,symkey);
        if(cryptoDataByte.getCode()!=0)return null;
        byte[] pubkey = KeyTools.prikeyToPubkey(cryptoDataByte.getData());
        return Hex.toHex(pubkey);
    }

    public static KeyInfo makeFidInfoByPrikeyCipher(String userPrikeyCipher, byte[] symkey) {
        Decryptor decryptor = new Decryptor();
        CryptoDataByte cryptoDataByte = decryptor.decryptJsonBySymkey(userPrikeyCipher,symkey);
        if(cryptoDataByte.getCode()!=0)return null;
        byte[] prikey = cryptoDataByte.getData();
        return new KeyInfo(prikey,symkey);
    }

    public static byte[] decryptSessionKey(String sessionKeyCipher, byte[] symkey) {
        Decryptor decryptor = new Decryptor();
        CryptoDataByte cryptoDataByte = decryptor.decryptJsonBySymkey(sessionKeyCipher,symkey);
        if(cryptoDataByte.getCode()==null || cryptoDataByte.getCode()!=0)return null;
        return cryptoDataByte.getData();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPasswordCipher() {
        return passwordCipher;
    }

    public void setPasswordCipher(String passwordCipher) {
        this.passwordCipher = passwordCipher;
    }

    public String getUserPrikeyCipher() {
        return userPrikeyCipher;
    }

    public void setUserPrikeyCipher(String userPrikeyCipher) {
        this.userPrikeyCipher = userPrikeyCipher;
    }

    public long getBestHeight() {
        return bestHeight;
    }

    public void setBestHeight(long bestHeight) {
        this.bestHeight = bestHeight;
    }

    public Long getBalance() {
        return balance;
    }

    public void setBalance(Long balance) {
        this.balance = balance;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getVia() {
        return via;
    }

    public void setVia(String via) {
        this.via = via;
    }

    public static long getOrderRequestTimes() {
        return orderRequestTimes;
    }

    public static void setOrderRequestTimes(long orderRequestTimes) {
        ApiAccount.orderRequestTimes = orderRequestTimes;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }


    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public byte[] getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(byte[] sessionKey) {
        this.sessionKey = sessionKey;
    }

    public byte[] getPassword() {
        return password;
    }

    public void setPassword(byte[] password) {
        this.password = password;
    }

    public String getBestBlockId() {
        return bestBlockId;
    }

    public void setBestBlockId(String bestBlockId) {
        this.bestBlockId = bestBlockId;
    }

    public Object getClient() {
        return client;
    }

    public void setClient(Object client) {
        this.client = client;
    }

    public FapiClient getFapiClient() {
        return fapiClient;
    }

    public void setFapiClient(FapiClient fapiClient) {
        this.fapiClient = fapiClient;
    }

    public FcSession getSession() {
        return fcSession;
    }

    public void setSession(FcSession fcSession) {
        this.fcSession = fcSession;
    }

    public String getUserPubkey() {
        return userPubkey;
    }

    public void setUserPubkey(String userPubkey) {
        this.userPubkey = userPubkey;
    }

    public List<Payment> getPayments() {
        return payments;
    }

    public void setPayments(List<Payment> payments) {
        this.payments = payments;
    }

    public Double getMinPayment() {
        return minPayment;
    }

    public void setMinPayment(Double minPayment) {
        this.minPayment = minPayment;
    }
    public int getTimeout() {
        return timeout;
    }
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public Long getRemainRequestCount() {
        return remainRequestCount;
    }

    public void setRemainRequestCount(Long remainRequestCount) {
        this.remainRequestCount = remainRequestCount;
    }
}
