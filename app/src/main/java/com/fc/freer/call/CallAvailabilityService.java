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

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;

/**
 * <i>Available for calls</i> (VOICE_SPEC §6.3): a small foreground service
 * that keeps the app's process, and so its FUDP node and the MAP keepalive
 * that lets ROAD reach this device, running in the background. Without it,
 * Android stops the keepalive and an incoming call shows only as a missed
 * call on the next DOCK fetch. There is no push service to fall back on, by
 * design.
 * <p>
 * Started from the foreground only (Android forbids otherwise): when the
 * user turns the setting on, and when an identity loads with it on.
 */
public class CallAvailabilityService extends Service {

    private static final String TAG = "CallAvailability";
    static final String CHANNEL = "calls_available";
    static final int NOTIFY_ID = 7103;

    public static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, CallAvailabilityService.class));
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException from the background: next time the app opens
            TimberLogger.w(TAG, "not started: %s", e.getMessage());
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CallAvailabilityService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, n);
        }
        return START_NOT_STICKY; // after a process death no identity is loaded: nothing to ring
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.call_channel_available), NotificationManager.IMPORTANCE_MIN));
        PendingIntent settings = PendingIntent.getActivity(this, 0,
                new Intent(this, CallSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(getString(R.string.call_available_title))
                .setContentText(getString(R.string.call_available_text))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(settings)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
