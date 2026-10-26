package com.fc.freer.home;

import static com.fc.fc_ajdk.data.fcData.AlgorithmId.FC_AesGcm256_No1_NrC7;

import android.app.Activity;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.FreerHist;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Configure;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

/**
 * Recovers a servant's prikey from the cipherPrikey in its on-chain master declaration.
 * <p>
 * The declaration encrypts the servant prikey to the master's pubkey. When the master's prikey
 * is held here, the cipher is decrypted, checked against the servant FID, re-encrypted with the
 * local symkey and handed back on the servant KeyInfo; the caller decides where to save it.
 * Otherwise the cipher is shown as a QR code (with copy) for decrypting wherever the master
 * prikey lives.
 */
public final class ServantPrikeyRecovery {
    private static final String TAG = "ServantPrikeyRecovery";

    public interface OnRecovered {
        /** Called on the UI thread with the servant KeyInfo, its prikeyCipher now set. */
        void onRecovered(KeyInfo servantKeyInfo);
    }

    private ServantPrikeyRecovery() {}

    public static void recover(Activity activity, KeyInfo servantKeyInfo, OnRecovered onRecovered) {
        String master = servantKeyInfo.getMaster();
        if (master == null || master.trim().isEmpty()) {
            ToastUtils.makeText(activity, activity.getString(R.string.toast_no_master_yet));
            return;
        }

        ApiCenter apiCenter = ApiCenter.getInstance();
        FapiClient fapiClient = apiCenter == null ? null
                : (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) {
            ToastUtils.makeText(activity, activity.getString(R.string.toast_fapi_client_unavailable));
            return;
        }

        new Thread(() -> {
            try {
                FreerHist freerHist = fapiClient.masterAnnouncement(servantKeyInfo.getId(), master);
                if (freerHist == null) {
                    toast(activity, activity.getString(R.string.toast_no_master_announcement));
                    return;
                }
                String cipherPrikey = freerHist.getCipherPrikey();
                if (cipherPrikey == null || cipherPrikey.trim().isEmpty()) {
                    toast(activity, activity.getString(R.string.toast_no_cipher_prikey));
                    return;
                }

                FidManager fidManager = FidManager.getInstance();
                KeyInfo masterKeyInfo = fidManager == null ? null : fidManager.getKeyInfoByFid(master);
                if (masterKeyInfo == null || masterKeyInfo.getPrikeyCipher() == null
                        || masterKeyInfo.getPrikeyCipher().trim().isEmpty()) {
                    showCipher(activity, cipherPrikey);
                    return;
                }

                byte[] masterPrikey = SecurePrikeyManager.requestPrikeySync(activity,
                        activity.getString(R.string.decrypt_servant_prikey), masterKeyInfo.getPrikeyCipher());
                if (masterPrikey == null) {
                    showCipher(activity, cipherPrikey);
                    return;
                }

                byte[] servantPrikey;
                try {
                    CryptoDataByte result = new Decryptor().decryptJsonByAsyOneWay(cipherPrikey, masterPrikey);
                    servantPrikey = (result.getCode() == null || result.getCode() == 0) ? result.getData() : null;
                } finally {
                    SecurePrikeyManager.erasePrikey(masterPrikey);
                }
                if (servantPrikey == null) {
                    showCipher(activity, cipherPrikey);
                    return;
                }

                try {
                    if (!servantKeyInfo.getId().equals(KeyTools.prikeyToFid(servantPrikey))) {
                        toast(activity, activity.getString(R.string.toast_recovered_prikey_mismatch));
                        return;
                    }
                    String prikeyCipher = encryptWithSymkey(servantPrikey);
                    if (prikeyCipher == null) {
                        toast(activity, activity.getString(R.string.toast_encryption_key_unavailable));
                        return;
                    }
                    servantKeyInfo.setPrikeyCipher(prikeyCipher);
                    servantKeyInfo.setWatchOnly(false);
                } finally {
                    SecurePrikeyManager.erasePrikey(servantPrikey);
                }

                activity.runOnUiThread(() -> onRecovered.onRecovered(servantKeyInfo));
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error recovering servant prikey: %s", e.getMessage());
                toast(activity, activity.getString(R.string.toast_error_loading_master_announcement, e.getMessage()));
            }
        }).start();
    }

    private static String encryptWithSymkey(byte[] prikey32) {
        Configure configure = ConfigureManager.getInstance().getConfigure();
        byte[] symkey = configure == null ? null : configure.getSymkey();
        if (symkey == null) return null;
        CryptoDataByte cryptoDataByte = new Encryptor(FC_AesGcm256_No1_NrC7).encryptBySymkey(prikey32, symkey);
        if (cryptoDataByte.getCode() != null && cryptoDataByte.getCode() != 0) return null;
        return cryptoDataByte.toJson();
    }

    private static void showCipher(Activity activity, String cipherPrikey) {
        activity.runOnUiThread(() -> QRCodeGenerator.generateAndShowQRCode(
                activity, cipherPrikey, activity.getString(R.string.prikey_cipher)));
    }

    private static void toast(Activity activity, String message) {
        activity.runOnUiThread(() -> ToastUtils.makeText(activity, message));
    }
}
