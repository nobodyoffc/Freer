package com.fc.freer.data;

import android.app.Activity;
import android.content.Context;

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
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.model.Setting;
import com.fc.freer.onboarding.HomeFeip;
import com.fc.freer.onboarding.LiveFidRecord;
import com.fc.freer.onboarding.PendingIdentityCarve;
import com.fc.freer.onboarding.PendingIdentityCarves;
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
 * they behave identically.
 * <p>
 * <b>FEIP9 register replaces the whole home map.</b> The map carved is the one the chain holds
 * <i>now</i>, read fresh rather than from the cached KeyInfo, with DOCK and/or DISK laid over
 * it; a cached copy missing an entry added elsewhere would erase it. When the chain already
 * says this, nothing is carved.
 * <p>
 * On a successful broadcast it always:
 * <ul>
 *   <li>records the carve in {@link PendingIdentityCarves}, so no screen offers it again until
 *       the chain shows it or a day passes;</li>
 *   <li>marks DISK usable locally and caches its client;</li>
 *   <li>enters {@link ImManager}'s DOCK pending state ({@link ImManager#onRegistrationTxSent}).</li>
 * </ul>
 * A single HOME register TX writes {@code home.DOCK} as a plaintext SID (peers must read it to
 * reach the relay) and {@code home.DISK} as the SID encrypted with the FID public key.
 */
public final class ServerSetupManager {
    private static final String TAG = "ServerSetupManager";

    private ServerSetupManager() {}

    /**
     * Register DOCK and/or DISK for the live FID. Runs entirely off the caller's thread; the
     * {@code uiCallback} fires on a background thread, so callers must marshal any UI work.
     *
     * @param activity    calling activity (for TX UI fallbacks and app context)
     * @param liveKeyInfo the live FID's KeyInfo
     * @param dockVal     the DOCK SID to register (empty/null to skip DOCK)
     * @param diskSid     the DISK SID to register (empty/null to skip DISK)
     * @param callVal     the CALL service to register (empty/null to leave CALL as it is)
     * @param removeCall  remove the home's CALL entry: stop taking calls (VOICE_SPEC §6.2)
     * @param prikey      the live FID's private key (for signing and DISK encryption)
     * @param uiCallback  result callback (onSuccess fires only after bookkeeping completes)
     */
    public static void register(Activity activity, KeyInfo liveKeyInfo,
                                String dockVal, String diskSid, String callVal, boolean removeCall,
                                byte[] prikey, TxSender.TxCallback uiCallback) {
        final String call = callVal != null ? callVal.trim() : "";
        final boolean callChanged = !call.isEmpty() || removeCall;
        final String dock = dockVal != null ? dockVal.trim() : "";
        final String disk = diskSid != null ? diskSid.trim() : "";
        final boolean settingDock = !dock.isEmpty();
        final String fid = liveKeyInfo.getId();
        final Context appContext = activity.getApplicationContext();

        new Thread(() -> {
            PendingIdentityCarves pendingCarves = PendingIdentityCarves.of(appContext);
            PendingIdentityCarve inFlight = pendingCarves.getInFlight(
                    fid, PendingIdentityCarve.Kind.HOME, System.currentTimeMillis());
            if (inFlight != null) {
                if (uiCallback != null) {
                    uiCallback.onError(appContext.getString(R.string.carve_already_pending, inFlight.txid));
                }
                return;
            }

            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            LiveFidRecord onChain;
            try {
                onChain = LiveFidRecord.fetch(fapiClient, fid).record;
            } catch (Exception e) {
                TimberLogger.w(TAG, "Could not read home for %s: %s", fid, e.getMessage());
                if (uiCallback != null) {
                    uiCallback.onError(appContext.getString(R.string.server_setup_home_unreadable, e.getMessage()));
                }
                return;
            }

            // A brand-new FID that has never spent has no pubkey published on-chain, so
            // KeyInfo.pubkey is null. The DISK SID is encrypted to that pubkey, so derive it
            // from the private key we hold.
            String pubkey = liveKeyInfo.getPubkey();
            if (pubkey == null || pubkey.isEmpty()) {
                pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey));
                liveKeyInfo.setPubkey(pubkey);
            }

            Map<String, String> changes = new HashMap<>();
            if (settingDock) changes.put(Constants.DOCK_NO1_NRC7, dock);
            if (!call.isEmpty()) changes.put(Constants.CALL_NO1_NRC7, call);
            boolean settingDisk = false;
            // The DISK value is encrypted afresh each time, so compare the SID it holds, not the
            // bytes: re-encrypting the same SID would be a paid carve that changes nothing.
            if (!disk.isEmpty() && !disk.equals(DiskHomeManager.resolveSid(onChain.home, prikey))) {
                String diskEnc = DiskHomeManager.encryptSid(disk, pubkey);
                if (diskEnc == null) {
                    if (uiCallback != null) uiCallback.onError("Failed to encrypt DISK sid");
                    return;
                }
                changes.put(DiskHomeManager.DISK_KEY, diskEnc);
                settingDisk = true;
            }

            final Map<String, String> homeMap = HomeFeip.merged(onChain.home, changes,
                    removeCall ? java.util.Set.of(Constants.CALL_NO1_NRC7) : java.util.Set.of());
            if (homeMap == null) {
                if (uiCallback != null) uiCallback.onError(appContext.getString(R.string.server_setup_home_unchanged));
                return;
            }
            final boolean diskChanged = settingDisk;

            HomeOpData homeOpData = new HomeOpData();
            homeOpData.setOp(HomeOpData.Op.REGISTER.toLowerCase());
            homeOpData.setHome(homeMap);
            Feip feip = Feip.fromProtocolName(Feip.FeipProtocol.HOME);
            feip.setData(homeOpData);
            String feipJson = feip.toJson();

            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(activity, fid, feipJson, prikey,
                    CashManager.getInstance(), new TxHandler(), fapiClient,
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            pendingCarves.record(PendingIdentityCarve.home(
                                    fid, homeMap, txId, System.currentTimeMillis()));
                            // Bookkeeping (resolveAndCacheDiskClient does a blocking UDP request)
                            // runs off the callback thread.
                            new Thread(() -> {
                                if (callChanged && !diskChanged) {
                                    // The callee answers only on its own home.CALL: use the new one now.
                                    liveKeyInfo.setHome(new HashMap<>(homeMap));
                                }
                                if (diskChanged) {
                                    liveKeyInfo.setHome(new HashMap<>(homeMap));
                                    DiskHomeManager.resolveAndCacheDiskClient(liveKeyInfo, prikey);
                                }
                                if (settingDock) {
                                    Setting setting = SettingManager.getInstance().getCurrentSetting();
                                    ImManager im = setting != null ? setting.getImManager() : null;
                                    if (im != null) im.onRegistrationTxSent(txId);
                                }
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

                        @Override
                        public void onCancelled() {
                            if (uiCallback != null) uiCallback.onCancelled();
                        }
                    });
        }).start();
    }
}
