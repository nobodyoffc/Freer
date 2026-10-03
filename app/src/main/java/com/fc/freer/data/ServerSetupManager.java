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
 * Builds and broadcasts the live FID's combined DOCK/DISK (and CALL/ROAD/FUDP) server-registration TX, and performs
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
 * reach the relay), and {@code home.BASE} and {@code home.DISK} each public or sealed to the FID
 * public key, as the user chose (see {@link HomePrivacy}).
 */
public final class ServerSetupManager {
    private static final String TAG = "ServerSetupManager";

    private ServerSetupManager() {}

    /**
     * The entries beside DOCK and DISK a carve may set or remove. A blank value leaves that
     * entry as it is; removing wins over setting.
     */
    public static final class HomeEdits {
        /** The CALL service; removing it stops taking calls (VOICE_SPEC §6.2). */
        public String call;
        public boolean removeCall;
        /**
         * The ROAD service, which must also run MAP: peers relay through it, and this device
         * keeps its presence in that MAP. Setting it writes home.MAP to the same service;
         * removing it drops both.
         */
        public String road;
        public boolean removeRoad;
        /** A fixed, reachable FUDP node address: only for a node that has one. */
        public String fudp;
        public boolean removeFudp;
        /** The chain server every device of this FID follows; first of the home. Blank leaves it. */
        public String base;
        /**
         * Keep BASE / DISK sealed to the FID's own pubkey (see {@link HomePrivacy}). Private by
         * default, as DISK always was here; a choice now, because some people want theirs known.
         */
        public boolean basePrivate = true;
        public boolean diskPrivate = true;

        public static HomeEdits none() {
            return new HomeEdits();
        }

        boolean touchesCall() {
            return removeCall || !blank(call);
        }

        boolean touchesRoad() {
            return removeRoad || !blank(road);
        }

        public boolean touchesAny() {
            return touchesCall() || touchesRoad() || removeFudp || !blank(fudp) || !blank(base);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** The keys of {@code home} naming {@code kind}: the bare kind or kind@anything, any case. */
    private static java.util.Set<String> keysOf(Map<String, String> home, String kind) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (home == null) return keys;
        String k = kind.toUpperCase(java.util.Locale.ROOT);
        for (String key : home.keySet()) {
            if (key == null) continue;
            String u = key.toUpperCase(java.util.Locale.ROOT);
            if (u.equals(k) || u.startsWith(k + "@")) keys.add(key);
        }
        return keys;
    }

    /** Whether the service {@code value} names runs both ROAD and MAP; a URL cannot be checked, so passes. */
    private static boolean runsRoadAndMap(FapiClient fapiClient, String value) {
        String sid = com.fc.fc_ajdk.fapi.client.HomeServiceResolver.extractSid(value);
        if (sid == null || fapiClient == null) return true;
        Service service = fapiClient.serviceById(sid);
        java.util.List<String> components = service != null ? service.getComponents() : null;
        if (components == null) return false;
        return components.stream().anyMatch(Constants.ROAD_NO1_NRC7::equalsIgnoreCase)
                && components.stream().anyMatch(Constants.MAP_NO1_NRC7::equalsIgnoreCase);
    }

    /**
     * Register DOCK and/or DISK for the live FID, with any other {@code edits}. Runs entirely
     * off the caller's thread; the {@code uiCallback} fires on a background thread, so callers
     * must marshal any UI work.
     *
     * @param activity    calling activity (for TX UI fallbacks and app context)
     * @param liveKeyInfo the live FID's KeyInfo
     * @param dockVal     the DOCK SID to register (empty/null to skip DOCK)
     * @param diskSid     the DISK SID to register (empty/null to skip DISK)
     * @param edits       CALL, ROAD and FUDP to set or remove; null for none
     * @param prikey      the live FID's private key (for signing and DISK encryption)
     * @param uiCallback  result callback (onSuccess fires only after bookkeeping completes)
     */
    public static void register(Activity activity, KeyInfo liveKeyInfo,
                                String dockVal, String diskSid, HomeEdits edits,
                                byte[] prikey, TxSender.TxCallback uiCallback) {
        final HomeEdits ed = edits != null ? edits : HomeEdits.none();
        final boolean homeEdited = ed.touchesAny();
        final boolean roadChanged = ed.touchesRoad();
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

            // A typed SID skipped the picker's check: ROAD delivers only to its own MAP.
            if (!ed.removeRoad && !blank(ed.road)
                    && (onChain.home == null || !ed.road.trim().equals(onChain.home.get(Constants.ROAD_NO1_NRC7)))
                    && !runsRoadAndMap(fapiClient, ed.road.trim())) {
                if (uiCallback != null) uiCallback.onError(appContext.getString(R.string.server_setup_road_no_map));
                return;
            }

            Map<String, String> changes = new HashMap<>();
            if (settingDock) changes.put(Constants.DOCK_NO1_NRC7, dock);
            java.util.Set<String> removals = new java.util.HashSet<>();
            if (ed.removeCall) removals.addAll(keysOf(onChain.home, "CALL"));
            else if (!blank(ed.call)) changes.put(Constants.CALL_NO1_NRC7, ed.call.trim());
            // Setting an entry also drops any other spelling of its key, so peers find one value.
            if (ed.touchesRoad()) {
                removals.addAll(keysOf(onChain.home, "ROAD"));
                removals.addAll(keysOf(onChain.home, "MAP"));
            }
            if (!ed.removeRoad && !blank(ed.road)) {
                changes.put(Constants.ROAD_NO1_NRC7, ed.road.trim());
                changes.put(Constants.MAP_NO1_NRC7, ed.road.trim());
            }
            if (ed.removeFudp || !blank(ed.fudp)) removals.addAll(keysOf(onChain.home, "FUDP"));
            if (!ed.removeFudp && !blank(ed.fudp)) changes.put(Constants.FUDP_NO1_NRC7, ed.fudp.trim());
            // BASE and DISK are compared opened, never as bytes: a sealed value is different
            // every time, so re-sealing the same SID would be a paid carve that changes nothing.
            String diskWrite;
            String baseWrite;
            try {
                diskWrite = HomePrivacy.pending(disk, ed.diskPrivate,
                        HomePrivacy.valueOf(onChain.home, "DISK"), prikey, pubkey);
                baseWrite = HomePrivacy.pending(ed.base, ed.basePrivate,
                        HomePrivacy.valueOf(onChain.home, "BASE"), prikey, pubkey);
            } catch (IllegalArgumentException e) {
                if (uiCallback != null) uiCallback.onError(appContext.getString(R.string.server_setup_private_needs_sid));
                return;
            } catch (IllegalStateException e) {
                if (uiCallback != null) uiCallback.onError("Failed to encrypt a home entry");
                return;
            }
            boolean settingDisk = diskWrite != null;
            if (settingDisk) {
                removals.addAll(keysOf(onChain.home, "DISK"));
                changes.put(DiskHomeManager.DISK_KEY, diskWrite);
            }
            if (baseWrite != null) {
                removals.addAll(keysOf(onChain.home, "BASE"));
                changes.put(Constants.BASE_NO1_NRC7, baseWrite);
            }

            final Map<String, String> homeMap = HomeFeip.merged(onChain.home, changes, removals);
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
                                if (homeEdited && !diskChanged) {
                                    // The callee answers only on its own home.CALL, and keeps its
                                    // presence at its own home.ROAD: use the new home now.
                                    liveKeyInfo.setHome(new HashMap<>(homeMap));
                                }
                                if (diskChanged) {
                                    liveKeyInfo.setHome(new HashMap<>(homeMap));
                                    DiskHomeManager.resolveAndCacheDiskClient(liveKeyInfo, prikey);
                                }
                                if (roadChanged) {
                                    Setting s = SettingManager.getInstance().getCurrentSetting();
                                    ImManager im = s != null ? s.getImManager() : null;
                                    if (im != null) im.refreshMapPresence();
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
