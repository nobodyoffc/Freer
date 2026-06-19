package com.fc.freer.home;

import static com.fc.fc_ajdk.data.fcData.AlgorithmId.FC_AesGcm256_No1_NrC7;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.fc.fc_ajdk.data.feipData.FreerHist;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Configure;
import com.fc.freer.ui.StringInputDialog;
import com.fc.freer.utils.SecurePrikeyManager;

import org.bitcoinj.core.ECKey;

public class AddPrikeyPopupMenuHelper {
    private static final String TAG = "AddPrikeyPopupMenu";

    private final HomeActivity homeActivity;
    private PopupWindow popupWindow;

    public AddPrikeyPopupMenuHelper(HomeActivity homeActivity) {
        this.homeActivity = homeActivity;
    }

    public void showAddPrikeyMenu(View anchorView) {
        View popupView = LayoutInflater.from(homeActivity).inflate(R.layout.popup_menu_add_prikey, null);
        popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        setupAddPrikeyMenuItems(popupView);
        showPopup(anchorView);
    }

    private void setupAddPrikeyMenuItems(View popupView) {
        TextView fromPriKey = popupView.findViewById(R.id.from_pri_key);
        TextView fromPriKeyCipher = popupView.findViewById(R.id.from_pri_key_cipher);
        TextView fromPhrase = popupView.findViewById(R.id.input_phrase);
        TextView fromMaster = popupView.findViewById(R.id.from_master);

        fromPriKey.setOnClickListener(v -> {
            popupWindow.dismiss();
            showPrikeyInputDialog();
        });

        fromPriKeyCipher.setOnClickListener(v -> {
            popupWindow.dismiss();
            showPrikeyCipherInputDialog();
        });

        fromPhrase.setOnClickListener(v -> {
            popupWindow.dismiss();
            showPhraseInputDialog();
        });

        fromMaster.setOnClickListener(v -> {
            popupWindow.dismiss();
            handleFromMaster();
        });
    }

    private void showPrikeyInputDialog() {
        StringInputDialog dialog = new StringInputDialog(
            homeActivity,
            homeActivity.getString(R.string.Input_prikey),
            homeActivity.getString(R.string.input_prikey_hint),
            "",
            new StringInputDialog.OnInputListener() {
                @Override
                public void onConfirm(String input) {
                    if (input != null && !input.trim().isEmpty()) {
                        processPrikeyInput(input.trim());
                    }
                }

                @Override
                public void onCancel() {
                    // User cancelled, do nothing
                }
            }
        );
        dialog.show();
    }

    private void showPrikeyCipherInputDialog() {
        StringInputDialog dialog = new StringInputDialog(
            homeActivity,
            homeActivity.getString(R.string.Input_prikey_cipher),
            homeActivity.getString(R.string.input_prikey_cipher_hint),
            "",
            new StringInputDialog.OnInputListener() {
                @Override
                public void onConfirm(String input) {
                    if (input != null && !input.trim().isEmpty()) {
                        processPrikeyCipherInput(input.trim());
                    }
                }

                @Override
                public void onCancel() {
                    // User cancelled, do nothing
                }
            }
        );
        dialog.show();
    }

    private void showPhraseInputDialog() {
        StringInputDialog dialog = new StringInputDialog(
            homeActivity,
            homeActivity.getString(R.string.input_phrase),
            homeActivity.getString(R.string.input_phrase_hint),
            "",
            new StringInputDialog.OnInputListener() {
                @Override
                public void onConfirm(String input) {
                    if (input != null && !input.trim().isEmpty()) {
                        processPhraseInput(input.trim());
                    }
                }

                @Override
                public void onCancel() {
                    // User cancelled, do nothing
                }
            }
        );
        dialog.show();
    }

    private void processPrikeyInput(String prikeyInput) {
        try {
            // Extract 32-byte private key from various formats
            byte[] prikey32 = KeyTools.getPrikey32(prikeyInput);
            if (prikey32 == null) {
                ToastUtils.makeText(homeActivity, "Invalid private key format");
                return;
            }

            // Validate private key by checking if it generates the correct FID
            if (validatePrikeyForLiveFid(prikey32)) {
                encryptAndSavePrikey(prikey32);
            } else {
                ToastUtils.makeText(homeActivity, "Private key does not match current FID");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing private key input: " + e.getMessage(), e);
            ToastUtils.makeText(homeActivity, "Error processing private key: " + e.getMessage());
        }
    }

    private void processPrikeyCipherInput(String cipherInput) {
        // Show password input dialog to decrypt the cipher
        StringInputDialog passwordDialog = new StringInputDialog(
            homeActivity,
            "Enter Password",
            "Enter password to decrypt private key",
            "",
            new StringInputDialog.OnInputListener() {
                @Override
                public void onConfirm(String password) {
                    if (password != null && !password.trim().isEmpty()) {
                        decryptAndProcessPrikeyCipher(cipherInput, password.trim());
                    }
                }

                @Override
                public void onCancel() {
                    // User cancelled, do nothing
                }
            }
        );
        passwordDialog.show();
    }

    private void decryptAndProcessPrikeyCipher(String cipherInput, String password) {
        try {
            Decryptor decryptor = new Decryptor();
            CryptoDataByte cryptoDataByte = decryptor.decryptJsonByPassword(cipherInput, password.toCharArray());

            if (cryptoDataByte.getCode() != null && cryptoDataByte.getCode() != 0) {
                ToastUtils.makeText(homeActivity, "Failed to decrypt: " + cryptoDataByte.getMessage());
                return;
            }

            byte[] prikey32 = KeyTools.getPrikey32(cryptoDataByte.getData());
            if (prikey32 == null) {
                ToastUtils.makeText(homeActivity, "Invalid decrypted private key");
                return;
            }

            // Validate private key by checking if it generates the correct FID
            if (validatePrikeyForLiveFid(prikey32)) {
                encryptAndSavePrikey(prikey32);
            } else {
                ToastUtils.makeText(homeActivity, "Decrypted private key does not match current FID");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting private key cipher: " + e.getMessage(), e);
            ToastUtils.makeText(homeActivity, "Error decrypting private key: " + e.getMessage());
        }
    }

    private void processPhraseInput(String phrase) {
        try {
            // Convert phrase to private key
            ECKey ecKey = KeyTools.secretWordsToPrikey(phrase);
            byte[] prikey32 = ecKey.getPrivKeyBytes();

            // Validate private key by checking if it generates the correct FID
            if (validatePrikeyForLiveFid(prikey32)) {
                encryptAndSavePrikey(prikey32);
            } else {
                ToastUtils.makeText(homeActivity, "Phrase does not generate the current FID");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing phrase input: " + e.getMessage(), e);
            ToastUtils.makeText(homeActivity, "Error processing phrase: " + e.getMessage());
        }
    }

    private boolean validatePrikeyForLiveFid(byte[] prikey32) {
        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null) {
                return false;
            }

            String liveFid = fidManager.getLiveFid();
            if (liveFid == null) {
                return false;
            }

            // Generate FID from private key and compare with current live FID
            String generatedFid = KeyTools.prikeyToFid(prikey32);
            return liveFid.equals(generatedFid);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error validating private key for live FID: " + e.getMessage(), e);
            return false;
        }
    }

    private void encryptAndSavePrikey(byte[] prikey32) {
        try {
            Configure configure = ConfigureManager.getInstance().getConfigure();
            if (configure == null) {
                ToastUtils.makeText(homeActivity, "Configuration not available");
                return;
            }

            byte[] symkey = configure.getSymkey();
            if (symkey == null) {
                ToastUtils.makeText(homeActivity, "Encryption key not available");
                return;
            }

            // Encrypt private key with configure.symkey
            Encryptor encryptor = new Encryptor(FC_AesGcm256_No1_NrC7);
            CryptoDataByte cryptoDataByte = encryptor.encryptBySymkey(prikey32, symkey);
            
            if (cryptoDataByte.getCode() != null && cryptoDataByte.getCode() != 0) {
                ToastUtils.makeText(homeActivity, "Failed to encrypt private key: " + cryptoDataByte.getMessage());
                return;
            }

            String prikeyCipher = cryptoDataByte.toJson();

            // Get current live KeyInfo and update with prikeyCipher
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null) {
                ToastUtils.makeText(homeActivity, "FidManager not available");
                return;
            }

            KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();
            String liveFid = fidManager.getLiveFid();
            
            if (currentLiveKeyInfo == null || liveFid == null) {
                ToastUtils.makeText(homeActivity, "Live KeyInfo not available");
                return;
            }

            // Set the encrypted private key
            currentLiveKeyInfo.setPrikeyCipher(prikeyCipher);

            // Save the updated KeyInfo using FidManager
            if (fidManager.updateKeyInfo(homeActivity, liveFid, currentLiveKeyInfo)) {
                TimberLogger.d(TAG, "Successfully saved private key for FID: %s", liveFid);
                ToastUtils.makeText(homeActivity, "Private key saved successfully");
                
                // Refresh UI to hide the no_prikey icon
                homeActivity.runOnUiThread(homeActivity::refreshLiveFidCard);
            } else {
                ToastUtils.makeText(homeActivity, "Failed to save private key");
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error encrypting and saving private key: " + e.getMessage(), e);
            ToastUtils.makeText(homeActivity, "Error saving private key: " + e.getMessage());
        }
    }

    private void handleFromMaster() {
        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null) {
                ToastUtils.makeText(homeActivity, "FidManager not available");
                return;
            }

            KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
            if (liveKeyInfo == null) {
                ToastUtils.makeText(homeActivity, "Live KeyInfo not available");
                return;
            }

            // 1. Check if liveKeyInfo.master is null
            String master = liveKeyInfo.getMaster();
            if (master == null || master.trim().isEmpty()) {
                ToastUtils.makeText(homeActivity, "No master yet");
                return;
            }

            // 2. Load FreerHist with fapiClient.masterAnnouncement
            TimberLogger.d(TAG, "Loading FreerHist for master: " + master);
            
            // Get ApiCenter instance
            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter == null) {
                ToastUtils.makeText(homeActivity, "API service not available");
                return;
            }

            // Get FapiClient
            FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (fapiClient == null) {
                ToastUtils.makeText(homeActivity, "FAPI client not available");
                return;
            }

            // Call masterAnnouncement in background thread
            new Thread(() -> {
                try {
                    FreerHist freerHist = fapiClient.masterAnnouncement(liveKeyInfo.getId());
                    
                    homeActivity.runOnUiThread(() -> {
                        if (freerHist == null) {
                            ToastUtils.makeText(homeActivity, "No master announcement found");
                            return;
                        }

                        String cipherPriKey = freerHist.getCipherPrikey();
                        if (cipherPriKey == null || cipherPriKey.trim().isEmpty()) {
                            ToastUtils.makeText(homeActivity, "No cipher private key found");
                            return;
                        }

                        // Try to decrypt with mainFid prikey
                        processFromMasterCipher(cipherPriKey, liveKeyInfo);
                    });

                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error loading master announcement: " + e.getMessage(), e);
                    homeActivity.runOnUiThread(() -> 
                        ToastUtils.showError(homeActivity, "Error loading master announcement: " + e.getMessage())
                    );
                }
            }).start();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in handleFromMaster: " + e.getMessage(), e);
            ToastUtils.makeText(homeActivity, "Error processing master: " + e.getMessage());
        }
    }

    private void processFromMasterCipher(String cipherPriKey, KeyInfo liveKeyInfo) {
        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null) {
                ToastUtils.makeText(homeActivity, "FidManager not available");
                return;
            }

            // Get main FID's private key
            String mainFid = fidManager.getMainFid();
            if(!liveKeyInfo.getMaster().equals(mainFid)){
                QRCodeGenerator.generateAndShowQRCode(homeActivity, cipherPriKey, homeActivity.getString(R.string.prikey_cipher));
                return;
            }

            KeyInfo mainKeyInfo = fidManager.getMainKeyInfo();
            
            if (mainKeyInfo == null || mainKeyInfo.getPrikeyCipher() == null || mainKeyInfo.getPrikeyCipher().trim().isEmpty()) {
                // If main FID doesn't have private key, show QR code directly
                QRCodeGenerator.generateAndShowQRCode(homeActivity, cipherPriKey, homeActivity.getString(R.string.prikey_cipher));
                return;
            }

            byte[] mainPrikey32 = SecurePrikeyManager.requestPrikeySync(homeActivity, homeActivity.getString(R.string.decrypt_servant_prikey),mainKeyInfo.getPrikeyCipher());
            if (mainPrikey32 == null) {
                QRCodeGenerator.generateAndShowQRCode(homeActivity, cipherPriKey, homeActivity.getString(R.string.prikey_cipher));
                return;
            }

            // Try to decrypt master cipher with main FID's private key
            try {
                Decryptor decryptor = new Decryptor();
                CryptoDataByte masterResult = decryptor.decryptJsonByAsyOneWay(cipherPriKey, mainPrikey32);
                
                if (masterResult.getCode() != null && masterResult.getCode() != 0) {
                    // Decryption failed, show QR code
                    QRCodeGenerator.generateAndShowQRCode(homeActivity, cipherPriKey, homeActivity.getString(R.string.prikey_cipher));
                    return;
                }

                // Decryption succeeded, encrypt with symkey and save to liveKeyInfo
                byte[] decryptedPrikey = masterResult.getData();
                if (decryptedPrikey == null) {
                    QRCodeGenerator.generateAndShowQRCode(homeActivity, cipherPriKey, homeActivity.getString(R.string.prikey_cipher));
                    return;
                }

                // Validate that this private key matches the live FID
                if (!validatePrikeyForLiveFid(decryptedPrikey)) {
                    ToastUtils.makeText(homeActivity, "Decrypted private key does not match current FID");
                    return;
                }

                // Encrypt with symkey and save
                encryptAndSavePrikey(decryptedPrikey);

            } catch (Exception e) {
                // Decryption failed, show QR code
                TimberLogger.d(TAG, "Failed to decrypt master cipher with main prikey, showing QR: " + e.getMessage());
                QRCodeGenerator.generateAndShowQRCode(homeActivity, cipherPriKey, homeActivity.getString(R.string.prikey_cipher));
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing master cipher: " + e.getMessage(), e);
            QRCodeGenerator.generateAndShowQRCode(homeActivity, cipherPriKey, homeActivity.getString(R.string.prikey_cipher));
        }
    }

    private void showPopup(View anchorView) {
        int[] location = new int[2];
        anchorView.getLocationOnScreen(location);
        popupWindow.showAtLocation(anchorView, android.view.Gravity.NO_GRAVITY, 
            location[0], location[1] - popupWindow.getHeight());
    }
    
    /**
     * Cleanup method to be called when the activity is being destroyed
     */
    public void cleanup() {
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }
}