package com.fc.freer;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import com.fc.fc_ajdk.android.FcProviders;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.android.FreerLogProvider;
import com.fc.freer.android.FreerMessageCallback;
import com.fc.freer.android.FreerStorageProvider;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.DatabaseManager;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.FcManager;
import com.fc.freer.model.Configure;
import com.orhanobut.hawk.Hawk;
import com.tencent.mmkv.MMKV;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.BackgroundTimeoutManager;
import com.fc.freer.config.ApiComponentConfig;
import com.fc.freer.im.ImManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FreerApplication extends Application {
    public static final String VER = "v3.1.1";

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_CONTAINER_SIZE =40;
    public static final int DEFAULT_REQUEST_SIZE = 10;
    public static final int DEFAULT_REQUEST_PAGE_COUNT = 1;
    public static final String FREER_APP_DEALER = "FKHyN5PCw5LEEL1tS8grpTCGZVGyYoreer";

    public static final FcManager.ManagerType[] managers = new FcManager.ManagerType[]{
            FcManager.ManagerType.APP,
            FcManager.ManagerType.CASH,
            FcManager.ManagerType.SECRET,
            FcManager.ManagerType.CONTACT,
            FcManager.ManagerType.MAIL,
            FcManager.ManagerType.FC_OBJECT,
            FcManager.ManagerType.PROOF};
    public static final Map<Service.ServiceType,Integer> serviceNumberMap = new HashMap<>();

    // API 组件配置映射
    public static final Map<String, ApiComponentConfig> API_COMPONENT_CONFIG = new HashMap<>();
    
    static {
        // BASE 组件：关键组件，需要 1 个客户端（当前需求）
        API_COMPONENT_CONFIG.put("BASE", 
            new ApiComponentConfig("BASE", 1, 10, true));
        
        // 其他组件配置（为未来扩展准备，当前未使用）
        // API_COMPONENT_CONFIG.put("MAP", new ApiComponentConfig("MAP", 1, 8, false));
        // API_COMPONENT_CONFIG.put("DISK", new ApiComponentConfig("DISK", 1, 7, false));
        // API_COMPONENT_CONFIG.put("TALK", new ApiComponentConfig("TALK", 1, 6, false));
    }

    private static final List<String> fidList = new ArrayList<>();
    private static Activity currentActivity;
    private static Context appContext;

    public static Context getAppContext() {
        return appContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = this;

        // Replace Android's stripped-down "BC" provider with the full bundled Bouncy
        // Castle before any crypto runs. Without this, Cipher.getInstance(..., "BC")
        // binds to Android's crippled BC and AES/GCM file decryption fails on-device
        // (while working on the desktop JVM). See FcProviders.ensureFullBouncyCastle().
        FcProviders.ensureFullBouncyCastle();

        // Initialize TimberLogger at the application level
        TimberLogger.init("FreerApp");

        // Initialize Configure context
        Configure.setContext(this);
        
        // Initialize FC-AJDK providers for Android
        FcProviders.init(
            new FreerLogProvider(),
            new FreerStorageProvider(this),
            new FreerMessageCallback(this)
        );

        // Use FAPI as the primary API service
        serviceNumberMap.put(Service.ServiceType.FAPI_No1_NrC7, 1);

        // Initialize MMKV at the application level (must be called before any MMKVDB usage)
        String rootDir = MMKV.initialize(this);
        TimberLogger.d("FreerApp", "MMKV initialized with root dir: " + rootDir);

        // Initialize Hawk at the application level (kept for migration purposes)
        Hawk.init(this).build();
        
        // Register activity lifecycle callbacks
        registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(android.app.Activity activity) {
                BackgroundTimeoutManager.onActivityStarted();
            }

            @Override
            public void onActivityResumed(android.app.Activity activity) {
                currentActivity = activity;
                BackgroundTimeoutManager.onAppForeground(activity);
                notifyImForeground();
            }

            @Override
            public void onActivityPaused(android.app.Activity activity) {
                if (currentActivity == activity) {
                    currentActivity = null;
                }
            }

            @Override
            public void onActivityStopped(android.app.Activity activity) {
                BackgroundTimeoutManager.onActivityStopped();
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(android.app.Activity activity) {}
        });
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        // Close the database when the application is terminating
        DatabaseManager.shutdown();
    }

    /**
     * Notify the active ImManager that the app returned to the foreground so it can
     * reconnect any DOCK servers whose FUDP connection died during a long sleep.
     * Cheap no-op unless a dock is actually marked failed.
     */
    private static void notifyImForeground() {
        try {
            SettingManager sm = SettingManager.getInstance();
            if (sm == null) return;
            Setting setting = sm.getCurrentSetting();
            if (setting == null) return;
            ImManager imManager = setting.getImManager();
            if (imManager != null) {
                imManager.onAppForeground();
                healImFapiClientIfNeeded(imManager);
            }
        } catch (Exception e) {
            TimberLogger.w("FreerApp", "notifyImForeground failed: " + e.getMessage());
        }
    }

    /**
     * If the ImManager was built before the FAPI client finished (re)connecting
     * (e.g. after waking from a long sleep), its handlers hold no client and every
     * send fails with "FAPI client not available" until the app is relaunched.
     * Re-acquire the client off the UI thread and heal the manager in place.
     */
    private static void healImFapiClientIfNeeded(ImManager imManager) {
        if (imManager.hasFapiClient()) return;
        new Thread(() -> {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) return;
                com.fc.fc_ajdk.fapi.client.FapiClient client =
                        (com.fc.fc_ajdk.fapi.client.FapiClient)
                                apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (client != null) {
                    imManager.ensureFapiClient(client);
                }
            } catch (Exception e) {
                TimberLogger.w("FreerApp", "healImFapiClientIfNeeded failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Get the current foreground activity
     * @return Current activity or null if no activity is in foreground
     */
    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    /**
     * Get the global fidList
     * @return List of fids
     */
    public static synchronized List<String> getFidList() {
        return new ArrayList<>(fidList);
    }

    /**
     * Add a fid to the global list
     * @param fid The fid to add
     */
    public static synchronized void addFid(String fid) {
        if(fid.contains("_")){
            CidFidManager cidFidManager = CidFidManager.getInstance();
            if(cidFidManager!=null){
                fid = cidFidManager.getFidByCid(fid);
            }
        }
        if (!fidList.contains(fid)) {
            fidList.add(fid);
        }
    }

    /**
     * Remove a fid from the global list
     * @param fid The fid to remove
     */
    public static synchronized void removeFid(String fid) {
        fidList.remove(fid);
    }
    public static synchronized Map<Service.ServiceType,Integer> getServiceNumberMap() {
        return serviceNumberMap;
    }

    /**
     * Clear all fids from the global list
     */
    public static void clearFidList() {
        fidList.clear();
    }

    /**
     * Get a copy of the API component configuration map
     * @return Map of component name to ApiComponentConfig
     */
    public static Map<String, ApiComponentConfig> getApiComponentConfig() {
        return new HashMap<>(API_COMPONENT_CONFIG);
    }

    /**
     * Get the list of critical (required) component names
     * @return List of critical component names
     */
    public static List<String> getRequiredComponents() {
        return API_COMPONENT_CONFIG.values().stream()
            .filter(ApiComponentConfig::isCritical)
            .map(ApiComponentConfig::getComponentName)
            .collect(Collectors.toList());
    }
} 