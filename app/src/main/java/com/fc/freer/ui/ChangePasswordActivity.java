package com.fc.freer.ui;

import android.content.Intent;
import android.os.Bundle;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.VaultKey;
import com.fc.fc_ajdk.utils.IdNameUtils;
import com.fc.freer.manager.FreerVaultStore;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.ApiAccount;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ToastUtils;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.SecretManager;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.CheckPasswordActivity;
import com.fc.freer.initiate.CreatePasswordActivity;
import com.fc.freer.manager.DatabaseManager;

import java.util.Map;
import java.util.Set;

/**
 * Activity to handle the full change password flow:
 * 1. Check current password
 * 2. Create new password
 * 3. Re-encrypt all KeyInfos and SecretDetails with new symKey
 * 4. Save new configure and remove old configure
 * 5. Show result and finish
 *
 * No visible layout is needed.
 */
public class ChangePasswordActivity extends AppCompatActivity {
    private ActivityResultLauncher<Intent> checkPasswordLauncher;
    private ActivityResultLauncher<Intent> createPasswordLauncher;
    private WaitingDialog waitingDialog;
    private Configure oldConfigure;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Optionally set a blank layout, or none at all
        // setContentView(new View(this));
        registerActivityResultLaunchers();
        oldConfigure = ConfigureManager.getInstance().getConfigure();
        // Start the flow
        Intent intent = new Intent(this, CheckPasswordActivity.class);
        intent.putExtra("allow_back_navigation", true); // Allow user to cancel password change
        intent.putExtra(CheckPasswordActivity.FOR_PASSWORD_CHANGE, true);
        checkPasswordLauncher.launch(intent);
    }

    private void registerActivityResultLaunchers() {
        checkPasswordLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    Intent intent = new Intent(this, CreatePasswordActivity.class);
                    intent.putExtra(CheckPasswordActivity.FOR_PASSWORD_CHANGE, true);
                    createPasswordLauncher.launch(intent);
                } else {
                    finish();
                }
            }
        );
        createPasswordLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                String newPassword = result.getResultCode() == RESULT_OK && result.getData() != null
                        ? result.getData().getStringExtra(CreatePasswordActivity.NEW_PASSWORD) : null;
                if (newPassword != null && oldConfigure.getDekCipher() != null
                        && !VaultKey.isLegacyName(oldConfigure.getPasswordName())) {
                    rewrapDataKey(newPassword);
                } else if (newPassword != null) {
                    new Thread(() -> {
                        Thread.currentThread().setName("PasswordChangeThread");
                        try {
                            showWaitingDialog("Preparing password change...");

                            // Get new symKey
                            Configure newConfigure = makeLegacyConfigure(newPassword);

                            copyOldConfigure(oldConfigure, newConfigure);

                            ConfigureManager.getInstance().removeConfigure(this,oldConfigure.getPasswordName());

                            immigrateAllSettings(oldConfigure,newConfigure);

                            byte[] newSymkey = newConfigure.getSymkey();
                            // Save new configure
                            ConfigureManager.getInstance().storeConfigure(this, newConfigure);

                            // Update database's current password name and transfer data
                            showWaitingDialog("Transferring encrypted data...");
                            changePassword(oldConfigure, newConfigure);

                            // Reinitialize managers to use new password name
                            showWaitingDialog("Initializing managers...");
                            SecretManager.getInstance().initialize(this, FidManager.getInstance().getLiveFid());

                            // Re-encrypt all KeyInfos
                            showWaitingDialog("Re-encrypting private keys...");
                            reEncryptPriKeyOfKeyInfos(newSymkey);

                            // Remove old configure
                            showWaitingDialog("Finalizing...");
                            if (oldConfigure.getPasswordName() != null) {
                                ConfigureManager.getInstance().removeConfigure(this,oldConfigure.getPasswordName());
                            }

                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                ToastUtils.makeText(this, getString(R.string.toast_password_changed_restart));

                                // Post exit to avoid IllegalStateException during result delivery
                                new android.os.Handler().postDelayed(() -> {
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                    System.exit(0);
                                }, 1000);

                                setResult(RESULT_OK);
                                finish();
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                dismissWaitingDialog();
                                ToastUtils.makeText(this, getString(R.string.error_during_password_change) + e.getMessage());
                                setResult(RESULT_CANCELED);
                                finish();
                            });
                        }
                    }).start();
                } else {
                    finish();
                }
            }
        );
    }

    /** A vault with a data key keeps its records and storage; only the wrapping of the key changes. */
    private void rewrapDataKey(String newPassword) {
        new Thread(() -> {
            Thread.currentThread().setName("PasswordChangeThread");
            String oldDekCipher = oldConfigure.getDekCipher();
            try {
                showWaitingDialog("Changing password...");
                oldConfigure.setDekCipher(VaultKey.wrap(oldConfigure.getSymkey(), ConfigureManager.toChars(newPassword.getBytes())));
                if (!ConfigureManager.getInstance().storeConfigure(this, oldConfigure)) {
                    throw new IllegalStateException("Failed to save the configuration");
                }
                ConfigureManager.getInstance().setConfigure(oldConfigure);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.password_changed));
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                oldConfigure.setDekCipher(oldDekCipher);
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.error_during_password_change) + e.getMessage());
                    setResult(RESULT_CANCELED);
                    finish();
                });
            }
        }).start();
    }

    /**
     * The Configure a legacy vault's password change moves to, made the way CreatePasswordActivity made
     * one before vaults had data keys. The vault then moves to a data key at its next unlock.
     */
    private Configure makeLegacyConfigure(String newPassword) {
        if (oldConfigure.getPendingVaultId() != null) {
            // A migration that stopped part way left a copy under a vault id this change abandons.
            new FreerVaultStore(this, oldConfigure).discard(oldConfigure.getPendingVaultId());
        }
        byte[] passwordBytes = newPassword.getBytes();
        Configure newConfigure = new Configure();
        newConfigure.makeSymkeyFromPassword(passwordBytes);
        newConfigure.setPasswordName(IdNameUtils.makePasswordHashName(passwordBytes));
        DatabaseManager.getInstance().setCurrentPasswordName(newConfigure.getPasswordName());
        ConfigureManager.getInstance().setConfigure(newConfigure);
        ConfigureManager.getInstance().storeConfigure(this, newConfigure);
        return newConfigure;
    }

    private void immigrateAllSettings(Configure oldConfigure, Configure newConfigure) {
        String oldSettingMapKey = SettingManager.getSettingMapKey(oldConfigure);
        if(oldSettingMapKey.isEmpty())return;

        Map<String, Setting> settingsMap = SettingManager.loadSettingMap(this, oldSettingMapKey);
        if(settingsMap.isEmpty())return;

        reencryptSettingKeyInfoMapPrikeys(oldConfigure, newConfigure, settingsMap);

        String newSettingMapKey = SettingManager.getSettingMapKey(newConfigure);
        SettingManager.saveSettingMap(this, newSettingMapKey,settingsMap);

        SettingManager.eraseSettingMap(this, oldSettingMapKey);
    }

    private void reencryptSettingKeyInfoMapPrikeys(Configure oldConfigure, Configure newConfigure, Map<String, Setting> settingsMap) {
        for(String key: settingsMap.keySet()){
            Setting setting = settingsMap.get(key);
            if(setting == null ){
                continue;
            }
            Map<String, KeyInfo> keyInfoMap = setting.getKeyInfoMap();

            if(keyInfoMap==null || keyInfoMap.isEmpty())return;

            for(KeyInfo keyInfo : keyInfoMap.values()){
                String prikeyCipher = keyInfo.getPrikeyCipher();
                if(prikeyCipher ==null)continue;
                String newCipher = null;
                try {
                    newCipher = reEncryptCipher(prikeyCipher, oldConfigure.getSymkey(), newConfigure.getSymkey());
                    keyInfo.setPrikeyCipher(newCipher);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private void reEncryptPriKeyOfKeyInfos(byte[] newSymKey) {
        Map<String,KeyInfo> keyInfoMap = oldConfigure.getMainCidInfoMap();
        if (keyInfoMap != null) {
            for (KeyInfo keyInfo : keyInfoMap.values()) {
                try {
                    String priKeyCipher = keyInfo.getPrikeyCipher();
                    if (priKeyCipher != null) {
                        byte[] priKeyBytes = Decryptor.decryptPrikey(priKeyCipher, oldConfigure.getSymkey());
                        if (priKeyBytes != null) {
                            String newCipher = Encryptor.encryptBySymkeyToJson(priKeyBytes, newSymKey);
                            keyInfo.setPrikeyCipher(newCipher);
                        }
                    }
                } catch (Exception e) {
                    TimberLogger.e("ChangePasswordActivity", "Error re-encrypting key info: " + e.getMessage());
                    throw new RuntimeException("Failed to re-encrypt key info: " + e.getMessage());
                }
            }
            
            // Update the new configure with re-encrypted keyInfos
            Configure newConfigure = ConfigureManager.getInstance().getConfigure();
            newConfigure.setMainCidInfoMap(keyInfoMap);
            ConfigureManager.getInstance().storeConfigure(this, newConfigure);
        }
    }

    private void showWaitingDialog(String hint) {
        runOnUiThread(() -> {
            if (waitingDialog == null) {
                waitingDialog = new WaitingDialog(this, hint);
            } else {
                waitingDialog.setHint(hint);
            }
            waitingDialog.show();
        });
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
    }

    private String reEncryptCipher(String cipher, byte[] oldSymkey, byte[] newSymkey) throws Exception {
        if (cipher == null) {
            return null;
        }

        CryptoDataByte cryptoDataByte = new Decryptor().decryptJsonBySymkey(cipher, oldSymkey);
        if (cryptoDataByte.getCode() != 0) {
            throw new Exception("Failed to decrypt cipher");
        }

        String newCipher = new Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7).encryptToJsonBySymkey(cryptoDataByte.getData(), newSymkey);
        BytesUtils.clearByteArray(cryptoDataByte.getData());
        return newCipher;
    }

    private void copyOldConfigure(Configure oldConfigure, Configure newConfigure) throws Exception {
        byte[] oldSymkey = oldConfigure.getSymkey();
        byte[] newSymkey = newConfigure.getSymkey();

        // Copy non-password fields
        newConfigure.setOwnerList(oldConfigure.getOwnerList());
        newConfigure.setEsAccountId(oldConfigure.getEsAccountId());
        newConfigure.setMyServiceMaskMap(oldConfigure.getMyServiceMaskMap());
        newConfigure.setApiProviderMap(oldConfigure.getApiProviderMap());
        newConfigure.setFreeApiListMap(oldConfigure.getFreeApiListMap());

        // Re-encrypt mainCidInfoMap
        Map<String, KeyInfo> mainCidInfoMap = oldConfigure.getMainCidInfoMap();
        if (mainCidInfoMap != null) {
            for (Map.Entry<String, KeyInfo> entry : mainCidInfoMap.entrySet()) {
                KeyInfo keyInfo = entry.getValue();

                // Re-encrypt prikeyCipher if exists
                if (keyInfo.getPrikeyCipher() != null) {
                    String newCipher = reEncryptCipher(keyInfo.getPrikeyCipher(), oldSymkey, newSymkey);
                    keyInfo.setPrikeyCipher(newCipher);
                }
            }
            newConfigure.setMainCidInfoMap(mainCidInfoMap);
        }

        // Re-encrypt apiAccountMap
        Map<String, ApiAccount> apiAccountMap = oldConfigure.getApiAccountMap();
        if (apiAccountMap != null) {
            for (Map.Entry<String, ApiAccount> entry : apiAccountMap.entrySet()) {
                ApiAccount apiAccount = entry.getValue();
                // Re-encrypt session keyCipher if exists
                com.fc.fc_ajdk.data.fcData.FcSession fcSession = apiAccount.getSession();
                if (fcSession != null) {
                    fcSession.setKeyCipher(reEncryptCipher(fcSession.getKeyCipher(), oldSymkey, newSymkey));
                    apiAccount.setSession(fcSession);
                }
            }
            newConfigure.setApiAccountMap(apiAccountMap);
        }
    }

    private void changePassword(Configure oldConfigure, Configure newConfigure) {
        if (newConfigure.getPasswordName().equals(oldConfigure.getPasswordName())) {
            return;
        }

        try {
            TimberLogger.d("ChangePasswordActivity", "Changing password from " + oldConfigure.getPasswordName() + " to " + newConfigure.getPasswordName());

            Map<String, KeyInfo> mainCidInfoMap = oldConfigure.getMainCidInfoMap();

            for (Map.Entry<String, KeyInfo> entry : mainCidInfoMap.entrySet()) {
                String fid = entry.getKey();

                com.fc.freer.manager.FcManager.ManagerType[] managers = com.fc.freer.FreerApplication.managers;
                for(com.fc.freer.manager.FcManager.ManagerType manager : managers){
                    String dbName = manager.name().toLowerCase();
                    com.fc.fc_ajdk.db.MMKVDB<? extends com.fc.fc_ajdk.data.fcData.FcEntity> oldDB;
                    com.fc.fc_ajdk.db.MMKVDB<?extends com.fc.fc_ajdk.data.fcData.FcEntity> newDB;
                    switch (com.fc.freer.manager.FcManager.ManagerType.fromString(dbName)){
                        case MAIL -> {
                            oldDB =  new com.fc.fc_ajdk.db.MMKVDB<>(Mail.class);
                            newDB = new com.fc.fc_ajdk.db.MMKVDB<>(Mail.class);

                        }
                        case SECRET -> {
                            oldDB =  new com.fc.fc_ajdk.db.MMKVDB<>( Secret.class);
                            newDB = new com.fc.fc_ajdk.db.MMKVDB<>(Secret.class);

                        }
                        case CONTACT -> {
                            oldDB =  new com.fc.fc_ajdk.db.MMKVDB<>(Contact.class);
                            newDB = new com.fc.fc_ajdk.db.MMKVDB<>(Contact.class);

                        }
                        case CASH -> {
                            oldDB =  new com.fc.fc_ajdk.db.MMKVDB<>(Cash.class);
                            newDB = new com.fc.fc_ajdk.db.MMKVDB<>(Cash.class);

                        }
                        case PROOF -> {
                            oldDB =  new com.fc.fc_ajdk.db.MMKVDB<>(Proof.class);
                            newDB = new com.fc.fc_ajdk.db.MMKVDB<>(Proof.class);

                        }
                        default -> {
                            oldDB =  new com.fc.fc_ajdk.db.MMKVDB<>(FcEntity.class);
                            newDB = new com.fc.fc_ajdk.db.MMKVDB<>( FcEntity.class);

                        }
                    }
                    oldDB.initialize(oldConfigure.getPasswordName(), fid, null, null, dbName);

                    newDB.initialize(newConfigure.getPasswordName(), fid, null, null, dbName);

                    if(transferDBData(oldDB, newDB)){
                        oldDB.clearDB();
                        newDB.close();
                    }
                }
            }

            // Close old databases
            DatabaseManager.getInstance().setCurrentPasswordName(newConfigure.getPasswordName());
            TimberLogger.d("ChangePasswordActivity", "Password change completed");
        } catch (Exception e) {
            TimberLogger.e("ChangePasswordActivity", "Failed to change password: " + e.getMessage()+"\n");
            throw new RuntimeException("Failed to change password: " + e.getMessage());
        }
    }

    private boolean transferDBData(com.fc.fc_ajdk.db.MMKVDB<?> oldDB, com.fc.fc_ajdk.db.MMKVDB<?> newDB) {
        // Transfer main data
        Map<String, ?> allData = oldDB.getAll();
        if (allData != null) {
            @SuppressWarnings("unchecked")
            Map<String, com.fc.fc_ajdk.data.fcData.FcEntity> typedData = (Map<String, com.fc.fc_ajdk.data.fcData.FcEntity>) allData;
            ((com.fc.fc_ajdk.db.MMKVDB<com.fc.fc_ajdk.data.fcData.FcEntity>) newDB).put(typedData);
        }

        // Transfer settings
        Map<String, String> allSettings = oldDB.getAllSettings();
        if (allSettings != null) {
            for (Map.Entry<String, String> setting : allSettings.entrySet()) {
                newDB.putSetting(setting.getKey(), setting.getValue());
            }
        }

        // Transfer state
        Map<String, Object> allState = oldDB.getStateMap();
        if (allState != null) {
            for (Map.Entry<String, Object> state : allState.entrySet()) {
                newDB.putState(state.getKey(), state.getValue());
            }
        }

        // Transfer meta
        Map<String, Object> allMeta = oldDB.getMetaMap();
        if (allMeta != null) {
            for (Map.Entry<String, Object> meta : allMeta.entrySet()) {
                newDB.putMeta(meta.getKey(), meta.getValue());
            }
        }

        // Transfer maps
        for (String mapName : oldDB.getMapNames()) {
            Class<?> mapType = oldDB.getMapType(mapName);
            if (mapType != null) {
                newDB.registerMapType(mapName, mapType);
                Map<String, ?> mapData = oldDB.getAllFromMap(mapName);
                if (mapData != null && !mapData.isEmpty()) {
                    if (java.util.Objects.equals(mapType.getName(), byte[].class.getName())) {
                        for (Map.Entry<String, ?> mapEntry : mapData.entrySet()) {
                            if (mapEntry.getValue() instanceof byte[]) {
                                newDB.putInMap(mapName, mapEntry.getKey(), mapEntry.getValue());
                            }
                        }
                    } else {
                        newDB.putAllInMap(mapName, mapData);
                    }
                }
            }
        }

        // Transfer lists
        Set<String> listNames = oldDB.getListNames();
        for (String listName : listNames) {
            Class<?> listType = oldDB.getListType(listName);
            if (listType != null) {
                newDB.registerListType(listName, listType);
                java.util.List<?> list = oldDB.getAllFromList(listName);
                if (list != null && !list.isEmpty()) {
                    newDB.addAllToList(listName, list);
                }
            }
        }

        return true;
    }
} 