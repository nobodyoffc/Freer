package com.fc.freer.call;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.fc.freer.R;

/**
 * Keeps a call alive with the screen off or the app in the background
 * (VOICE_SPEC §11.1): a foreground service of type microphone and phoneCall,
 * with an ongoing notification that opens the call screen or hangs up.
 * Started when a call is placed or answered, stopped when it ends.
 */
public class CallService extends Service {

    static final String CHANNEL_ONGOING = "calls_ongoing";
    static final int NOTIFY_ONGOING = 7102;
    static final String ACTION_HANGUP = "com.fc.freer.call.HANGUP";

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, CallService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CallService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HANGUP.equals(intent.getAction())) {
            CallManager.getInstance(this).hangup();
            return START_NOT_STICKY;
        }
        Notification n = notification();
        holdLocks();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
            }
            startForeground(NOTIFY_ONGOING, n, type);
        } else {
            startForeground(NOTIFY_ONGOING, n);
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ONGOING,
                getString(R.string.call_channel_ongoing), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent hangup = PendingIntent.getService(this, 1,
                new Intent(this, CallService.class).setAction(ACTION_HANGUP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String peer = CallManager.getInstance(this).peerFid();
        return new NotificationCompat.Builder(this, CHANNEL_ONGOING)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(getString(R.string.call_ongoing_title))
                .setContentText(peer == null ? "" : peer)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(R.drawable.ic_call_end, getString(R.string.call_hang_up), hangup)
                .build();
    }

    private android.os.PowerManager.WakeLock cpu;
    private android.net.wifi.WifiManager.WifiLock wifi;

    /**
     * For the length of the call: the CPU stays up with the screen off, and
     * Wi-Fi leaves power save, which otherwise holds incoming packets for up
     * to a few hundred ms and makes audio choppy, as calling apps do.
     */
    @SuppressWarnings("deprecation")
    private void holdLocks() {
        if (cpu == null) {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            cpu = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "freer:call");
            cpu.setReferenceCounted(false);
            cpu.acquire(4 * 60 * 60 * 1000L); // a bound, in case a call is never ended
        }
        if (wifi == null) {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifi = wm.createWifiLock(mode, "freer:call");
                wifi.setReferenceCounted(false);
                wifi.acquire();
            }
        }
    }

    @Override
    public void onDestroy() {
        if (cpu != null && cpu.isHeld()) cpu.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        cpu = null;
        wifi = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
