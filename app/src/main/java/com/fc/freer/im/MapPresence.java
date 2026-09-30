package com.fc.freer.im;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.fapi.MapEntry;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * This device's presence in the MAP of the live FID's own home.ROAD server
 * (FAPI14/15): senders relay through the target's home.ROAD, and a ROAD
 * delivers only to devices in its own MAP, so presence anywhere else reaches
 * no one.
 * <p>
 * No home.ROAD, no presence: {@code map.register} is billed like any message,
 * so an FID that has not chosen a ROAD pays nothing for one. With one, it
 * registers every {@link #INTERVAL_S} seconds while the app is in front, or
 * behind while <i>Available for calls</i> keeps the process alive. When it
 * stops, the server drops the entry once it goes stale.
 */
final class MapPresence {
    private static final String TAG = "MapPresence";
    /** Under the MAP's 90 s freshness, and often enough to hold a NAT mapping open. */
    static final int INTERVAL_S = 25;

    private final FudpNode fudpNode;
    private final Supplier<FapiClient> fapi;

    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "map-presence");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> task;
    /** The home.ROAD value the client below was resolved from. */
    private String roadValue;
    private FapiClient roadClient;
    /** What the last tick logged, so a steady state logs once. */
    private String lastNote;

    MapPresence(FudpNode fudpNode, Supplier<FapiClient> fapi) {
        this.fudpNode = fudpNode;
        this.fapi = fapi;
    }

    /** Keep registering, or stop. */
    synchronized void setWanted(boolean wanted) {
        if (wanted == (task != null)) return;
        if (wanted) {
            task = ticker.scheduleWithFixedDelay(this::tick, 0, INTERVAL_S, TimeUnit.SECONDS);
            TimberLogger.i(TAG, "MAP presence on");
        } else {
            task.cancel(false);
            task = null;
            lastNote = null;
            TimberLogger.i(TAG, "MAP presence off");
        }
    }

    /** home.ROAD may have changed: resolve it again, now if presence is on. */
    synchronized void refresh() {
        roadValue = null;
        roadClient = null;
        if (task != null) ticker.execute(this::tick);
    }

    synchronized void stop() {
        if (task != null) task.cancel(false);
        task = null;
        ticker.shutdownNow();
    }

    private void tick() {
        try {
            KeyInfo me = FidManager.getInstance() != null ? FidManager.getInstance().getLiveKeyInfo() : null;
            Map<String, String> home = me != null ? me.getHome() : null;
            String road = home != null ? home.get(Constants.ROAD_NO1_NRC7) : null;
            if (road == null || road.trim().isEmpty()) {
                note("no home.ROAD: no MAP presence");
                return;
            }
            FapiClient client = clientFor(road.trim(), home);
            if (client == null) return;
            MapEntry entry = client.mapRegister();
            if (entry != null) {
                note("registered at " + client.getServerUrl() + " as " + entry.getObservedIp() + ":" + entry.getObservedPort());
            } else {
                note("map.register at " + client.getServerUrl() + " failed: " + client.getLastError());
                // Reconnect on the next tick: the server may have restarted.
                synchronized (this) {
                    if (client != fapi.get()) roadClient = null;
                }
            }
        } catch (Exception e) {
            note("MAP presence error: " + e.getMessage());
        }
    }

    /** The client for the home.ROAD server: the connected FAPI when it is that server, else its own. */
    private FapiClient clientFor(String road, Map<String, String> home) {
        synchronized (this) {
            if (road.equals(roadValue) && roadClient != null) return roadClient;
        }
        FapiClient base = fapi.get();
        if (base == null) {
            note("no FAPI yet to resolve home.ROAD");
            return null;
        }
        String url = base.getHomeServiceResolver().resolveFromHome(home, Constants.ROAD_NO1_NRC7, base);
        if (url == null) {
            note("home.ROAD " + road + " did not resolve to a server");
            return null;
        }
        FapiClient client;
        if (sameServer(url, base.getServerUrl())) {
            client = base;
        } else {
            client = FapiClient.bootstrapFromUrl(fudpNode, url, null);
            if (client == null) {
                note("could not reach home.ROAD server " + url);
                return null;
            }
            // The ROAD server bills map.register: top it up as the main FAPI is.
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            String mainFid = setting != null ? setting.getMainFid() : null;
            if (mainFid != null) {
                client.setAutoRechargeInfo(mainFid, setting::decryptPrikey);
                client.setUtxoProvider(CashManager.UTXO_PROVIDER);
                client.setAutoRechargeVia(FreerApplication.FREER_APP_DEALER);
            }
        }
        synchronized (this) {
            roadValue = road;
            roadClient = client;
        }
        return client;
    }

    private static boolean sameServer(String a, String b) {
        return a != null && b != null && norm(a).equals(norm(b));
    }

    private static String norm(String url) {
        String u = url.trim().toLowerCase(Locale.ROOT);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private void note(String what) {
        if (what.equals(lastNote)) return;
        lastNote = what;
        TimberLogger.i(TAG, "%s", what);
    }
}
