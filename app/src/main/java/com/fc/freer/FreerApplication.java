package com.fc.freer;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.widget.Toast;

import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.manager.DatabaseManager;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.FcManager;
import com.fc.freer.model.Configure;
import com.orhanobut.hawk.Hawk;
import com.fc.freer.utils.BackgroundTimeoutManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FreerApplication extends Application {
    public static final int TOAST_LASTING = Toast.LENGTH_SHORT;
    public static final String FREER_APP_DEALER = "FJgzvgUiYPNeRinB8v3CCwThDVrJKkreer";
    public static final FcManager.ManagerType[] managers = new FcManager.ManagerType[]{FcManager.ManagerType.CASH};
    public static final Map<Service.ServiceType,Integer> serviceNumberMap = new HashMap<>();
    public static final int DEFAULT_PAGE_SIZE = 20;

    private static final List<String> fidList = new ArrayList<>();
    private static String activeFid = null;
    private static Activity currentActivity;
    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize TimberLogger at the application level
        TimberLogger.init("FreerApp");

        // Initialize Configure context
        Configure.setContext(this);

        serviceNumberMap.put(Service.ServiceType.APIP,1);
        
        // Initialize Hawk at the application level
        Hawk.init(this).build();
        
        // Register activity lifecycle callbacks
        registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity activity, Bundle savedInstanceState) {}

            @Override
            public void onActivityStarted(android.app.Activity activity) {}

            @Override
            public void onActivityResumed(android.app.Activity activity) {
                currentActivity = activity;
                BackgroundTimeoutManager.onAppForeground(activity);
            }

            @Override
            public void onActivityPaused(android.app.Activity activity) {
                if (currentActivity == activity) {
                    currentActivity = null;
                }
                BackgroundTimeoutManager.onAppBackground();
            }

            @Override
            public void onActivityStopped(android.app.Activity activity) {}

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
} 