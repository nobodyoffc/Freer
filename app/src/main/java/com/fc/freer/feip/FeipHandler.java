package com.fc.freer.feip;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fchData.OpReturn;
import com.fc.fc_ajdk.data.feipData.CidOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.MasterOpData;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.utils.SecurePrikeyManager;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;


/**
 * Handler for FEIP (Freecash Improvement Protocol) operations
 * Creates FEIP JSON data for various operations
 */
public class FeipHandler {
    private static final String TAG = "FeipHandler";

    public FeipHandler() {
    }

    public Feip parseFeip(OpReturn opReturn) {

        if(opReturn.getOpReturn()==null)return null;

        Feip feip = null;
        try {
            String json = JsonUtils.strToJson(opReturn.getOpReturn());
            feip = new Gson().fromJson(json, Feip.class);
        }catch(JsonSyntaxException e) {
            TimberLogger.d("Bad json on {}. ", opReturn.getId());
        }
        return  feip;
    }


    public String masterSet(String master, String priKeyCipher) {
        Feip feip = Feip.fromProtocolName(Feip.ProtocolName.MASTER);
        MasterOpData masterOpData = MasterOpData.makeMaster(master, priKeyCipher,null);
        feip.setData(masterOpData);
        return JsonUtils.toJson(feip);
    }

    public String cidRegister(String name) {
        Feip feip = Feip.fromProtocolName(Feip.ProtocolName.CID);
        feip.setData(CidOpData.makeRegister(name));
        return feip.toNiceJson();
    }
    public String unregisterCid() {
        Feip feip = Feip.fromProtocolName(Feip.ProtocolName.CID);
        feip.setData(CidOpData.makeUnregister());
        return feip.toNiceJson();
    }
    
    /**
     * Creates FEIP JSON for setting master
     *
     * @param masterPubkey The master pubkey to be set
     * @return FEIP JSON string or null on error
     */
    public String masterSet(String masterPubkey, byte[] myPrikey) {
        try {
            if (myPrikey == null) {
                TimberLogger.e(TAG, "User denied private key access or failed to get private key");
                return null;
            }

            String masterFid = KeyTools.pubkeyToFchAddr(masterPubkey);

            // Encrypt main FID's private key with master's public key
            CryptoDataByte cipher;
            try {
                Encryptor encryptor = new Encryptor(AlgorithmId.FC_EccK1AesCbc256_No1_NrC7);
                cipher = encryptor.encryptByAsyOneWay(myPrikey, Hex.fromHex(masterPubkey));
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error encrypting private key: %s", e.getMessage());
                return null;
            }

            if (cipher == null || cipher.getCode() != 0) {
                TimberLogger.e(TAG, "Failed to encrypt private key for master");
                return null;
            }

            String priKeyCipher = cipher.toJson();

            // Create FEIP JSON for setting master
            String feipJson = masterSet(masterFid, priKeyCipher);

            if (feipJson == null) {
                TimberLogger.e(TAG, "Failed to create FEIP JSON");
                return null;
            }

            return feipJson;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in setMaster FEIP operation: %s", e.getMessage());
            return null;
        }
    }
}