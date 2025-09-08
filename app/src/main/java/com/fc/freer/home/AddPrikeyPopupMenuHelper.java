package com.fc.freer.home;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.FreerApplication;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Configure;
import com.fc.freer.ui.StringInputDialog;

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
    }

    private void showPrikeyInputDialog() {
        StringInputDialog dialog = new StringInputDialog(
            homeActivity,
            homeActivity.getString(R.string.Input_prikey),
            homeActivity.getString(R.string.input_private_key_hint),
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
                Toast.makeText(homeActivity, "Invalid private key format", Toast.LENGTH_SHORT).show();
                return;
            }

            // Validate private key by checking if it generates the correct FID
            if (validatePrikeyForLiveFid(prikey32)) {
                encryptAndSavePrikey(prikey32);
            } else {
                Toast.makeText(homeActivity, "Private key does not match current FID", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing private key input: " + e.getMessage(), e);
            Toast.makeText(homeActivity, "Error processing private key: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(homeActivity, "Failed to decrypt: " + cryptoDataByte.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }

            byte[] prikey32 = KeyTools.getPrikey32(cryptoDataByte.getData());
            if (prikey32 == null) {
                Toast.makeText(homeActivity, "Invalid decrypted private key", Toast.LENGTH_SHORT).show();
                return;
            }

            // Validate private key by checking if it generates the correct FID
            if (validatePrikeyForLiveFid(prikey32)) {
                encryptAndSavePrikey(prikey32);
            } else {
                Toast.makeText(homeActivity, "Decrypted private key does not match current FID", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting private key cipher: " + e.getMessage(), e);
            Toast.makeText(homeActivity, "Error decrypting private key: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(homeActivity, "Phrase does not generate the current FID", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing phrase input: " + e.getMessage(), e);
            Toast.makeText(homeActivity, "Error processing phrase: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(homeActivity, "Configuration not available", Toast.LENGTH_SHORT).show();
                return;
            }

            byte[] symkey = configure.getSymkey();
            if (symkey == null) {
                Toast.makeText(homeActivity, "Encryption key not available", Toast.LENGTH_SHORT).show();
                return;
            }

            // Encrypt private key with configure.symkey
            Encryptor encryptor = new Encryptor();
            CryptoDataByte cryptoDataByte = encryptor.encryptBySymkey(prikey32, symkey);
            
            if (cryptoDataByte.getCode() != null && cryptoDataByte.getCode() != 0) {
                Toast.makeText(homeActivity, "Failed to encrypt private key: " + cryptoDataByte.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }

            String prikeyCipher = cryptoDataByte.toJson();

            // Get current live KeyInfo and update with prikeyCipher
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null) {
                Toast.makeText(homeActivity, "FidManager not available", Toast.LENGTH_SHORT).show();
                return;
            }

            KeyInfo currentLiveKeyInfo = fidManager.getLiveKeyInfo();
            String liveFid = fidManager.getLiveFid();
            
            if (currentLiveKeyInfo == null || liveFid == null) {
                Toast.makeText(homeActivity, "Live KeyInfo not available", Toast.LENGTH_SHORT).show();
                return;
            }

            // Set the encrypted private key
            currentLiveKeyInfo.setPrikeyCipher(prikeyCipher);

            // Save the updated KeyInfo using FidManager
            if (fidManager.updateKeyInfo(homeActivity, liveFid, currentLiveKeyInfo)) {
                TimberLogger.d(TAG, "Successfully saved private key for FID: %s", liveFid);
                Toast.makeText(homeActivity, "Private key saved successfully", Toast.LENGTH_SHORT).show();
                
                // Refresh UI to hide the no_prikey icon
                homeActivity.runOnUiThread(homeActivity::refreshLiveFidCard);
            } else {
                Toast.makeText(homeActivity, "Failed to save private key", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error encrypting and saving private key: " + e.getMessage(), e);
            Toast.makeText(homeActivity, "Error saving private key: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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