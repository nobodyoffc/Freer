package com.fc.freer.data;

import android.app.Activity;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.HomeOpData;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.im.ImManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds and broadcasts the live FID's combined DOCK/DISK server-registration TX, and performs
 * the post-broadcast bookkeeping that keeps the app consistent while the TX confirms.
 * <p>
 * This logic is shared by both entry points — the one-tap "use current server" path in
 * {@link com.fc.freer.im.ChannelSetupDialog} and the manual {@link ServerSetupActivity} — so
 * they behave identically. In particular, on a successful broadcast it always:
 * <ul>
 *   <li>marks DISK usable locally and caches its client, plus a persisted DISK pending flag
 *       ({@link DiskHomeManager#markRegistrationPending});</li>
 *   <li>enters {@link ImManager}'s DOCK pending state ({@link ImManager#onRegistrationTxSent});</li>
 *   <li>sets the persisted, ImManager-independent {@link ServerSetupState} flag that suppresses
 *       the combined server-setup prompt until the TX confirms — so neither entry point
 *       re-prompts (or double-registers) during the confirmation window, even across a restart.</li>
 * </ul>
 * A single HOME register TX writes {@code home.DOCK} as a plaintext SID (peers must read it to
 * reach the relay) and {@code home.DISK} as the SID encrypted with the FID public key.
 */
public final class ServerSetupManager {
    private ServerSetupManager() {}

    /**
     * Register DOCK and/or DISK for the live FID. Runs entirely off the caller's thread; the
     * {@code uiCallback} fires on a background thread, so callers must marshal any UI work.
     *
     * @param activity    calling activity (for TX UI fallbacks and app context)
     * @param liveKeyInfo the live FID's KeyInfo
     * @param dockVal     the DOCK SID to register (empty/null to skip DOCK)
     * @param diskSid     the DISK SID to register (empty/null to skip DISK)
     * @param prikey      the live FID's private key (for signing and DISK encryption)
     * @param uiCallback  result callback (onSuccess fires only after bookkeeping completes)
     */
    public static void register(Activity activity, KeyInfo liveKeyInfo,
                                String dockVal, String diskSid, byte[] prikey,
                                TxSender.TxCallback uiCallback) {
        final String dock = dockVal != null ? dockVal.trim() : "";
        final String disk = diskSid != null ? diskSid.trim() : "";
        final boolean settingDock = !dock.isEmpty();
        final boolean settingDisk = !disk.isEmpty();

        new Thread(() -> {
            // A brand-new FID that has never spent has no pubkey published on-chain, so
            // KeyInfo.pubkey is null. The DISK SID is encrypted to that pubkey, so derive it
            // from the private key we hold.
            String pubkey = liveKeyInfo.getPubkey();
            if (pubkey == null || pubkey.isEmpty()) {
                pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey));
                liveKeyInfo.setPubkey(pubkey);
            }

            // Read-modify-write: preserve existing home entries; set DOCK (plaintext) and/or
            // DISK (encrypted) in a single HOME register TX.
            Map<String, String> homeMap = new HashMap<>();
            if (liveKeyInfo.getHome() != null) homeMap.putAll(liveKeyInfo.getHome());
            if (settingDock) homeMap.put(Constants.DOCK_NO1_NRC7, dock);

            final String diskEnc;
            if (settingDisk) {
                diskEnc = DiskHomeManager.encryptSid(disk, pubkey);
                if (diskEnc == null) {
                    if (uiCallback != null) uiCallback.onError("Failed to encrypt DISK sid");
                    return;
                }
                homeMap.put(DiskHomeManager.DISK_KEY, diskEnc);
            } else {
                diskEnc = null;
            }

            HomeOpData homeOpData = new HomeOpData();
            homeOpData.setOp(HomeOpData.Op.REGISTER.toLowerCase());
            homeOpData.setHome(homeMap);
            Feip feip = Feip.fromProtocolName(Feip.FeipProtocol.HOME);
            feip.setData(homeOpData);
            String feipJson = feip.toJson();

            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(activity, liveKeyInfo.getId(), feipJson, prikey,
                    CashManager.getInstance(), new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            // Bookkeeping (resolveAndCacheDiskClient does a blocking UDP request)
                            // runs off the callback thread.
                            new Thread(() -> {
                                if (settingDisk) {
                                    Map<String, String> localHome = liveKeyInfo.getHome() != null
                                            ? new HashMap<>(liveKeyInfo.getHome()) : new HashMap<>();
                                    localHome.put(DiskHomeManager.DISK_KEY, diskEnc);
                                    liveKeyInfo.setHome(localHome);
                                    DiskHomeManager.resolveAndCacheDiskClient(liveKeyInfo, prikey);
                                    DiskHomeManager.markRegistrationPending(
                                            activity.getApplicationContext(), liveKeyInfo.getId());
                                }
                                if (settingDock) {
                                    Setting setting = SettingManager.getInstance().getCurrentSetting();
                                    ImManager im = setting != null ? setting.getImManager() : null;
                                    if (im != null) im.onRegistrationTxSent(txId);
                                }
                                // Persisted, ImManager-independent suppression of the combined
                                // prompt until the TX confirms (survives an app restart within the
                                // window). Set for BOTH entry points so neither re-registers.
                                ServerSetupState.markTxBroadcast(
                                        activity.getApplicationContext(), liveKeyInfo.getId());
                                if (uiCallback != null) uiCallback.onSuccess(txId);
                            }).start();
                        }

                        @Override
                        public void onError(String errorMessage) {
                            if (uiCallback != null) uiCallback.onError(errorMessage);
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            if (uiCallback != null) uiCallback.onUnsignedTx(rawTxInfo);
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            if (uiCallback != null) uiCallback.onUnbroadcasted(signedTxHex);
                        }
                    });
        }).start();
    }
}
