package com.fc.freer.data;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.data.DataActivity;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.SecurePrikeyManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service for background download of HAT data from DISK.
 */
public class DownloadService extends Service {
    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "download_service_channel";
    private static final int NOTIFICATION_ID = 4002;

    public static final String EXTRA_HAT_IDS = "extra_hat_ids";
    public static final String ACTION_PROGRESS = "com.fc.freer.DOWNLOAD_PROGRESS";
    public static final String ACTION_COMPLETE = "com.fc.freer.DOWNLOAD_COMPLETE";
    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_DOWNLOADED = "extra_downloaded";
    public static final String EXTRA_FAILED = "extra_failed";
    public static final String EXTRA_FEE = "extra_fee";
    public static final String EXTRA_HAT_ID = "extra_hat_id";
    public static final String EXTRA_PROGRESS = "extra_progress";
    public static final String ACTION_CANCEL = "com.fc.freer.DOWNLOAD_CANCEL";

    private volatile boolean stopped;

    private final android.content.BroadcastReceiver cancelReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            TimberLogger.i(TAG, "Cancel requested");
            stopped = true;
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        ArrayList<String> hatIds = intent.getStringArrayListExtra(EXTRA_HAT_IDS);
        if (hatIds == null || hatIds.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_CANCEL);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cancelReceiver, filter);
        }
        startForeground(NOTIFICATION_ID, createNotification(0, hatIds.size(), "Starting..."));
        stopped = false;
        new Thread(() -> runDownload(hatIds)).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(cancelReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runDownload(List<String> hatIds) {
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        String mainFid = fidManager != null ? fidManager.getMainFid() : null;
        if (liveFid == null || !liveFid.equals(mainFid)) {
            TimberLogger.w(TAG, "Download aborted: liveFid != mainFid");
            sendComplete(hatIds.size(), 0, 0, 0);
            stopSelf();
            return;
        }
        com.fc.fc_ajdk.data.fcData.KeyInfo keyInfo = fidManager.getLiveKeyInfo();
        if (keyInfo == null || keyInfo.getPrikeyCipher() == null) {
            sendComplete(hatIds.size(), 0, 0, 0);
            stopSelf();
            return;
        }
        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(keyInfo.getPrikeyCipher());
        if (prikey == null) {
            sendComplete(hatIds.size(), 0, 0, 0);
            stopSelf();
            return;
        }
        HatManager hatManager = HatManager.getInstance(this, liveFid);
        DataSyncManager syncManager = new DataSyncManager(this, hatManager);
        List<Hat> hatList = new ArrayList<>();
        for (String id : hatIds) {
            Hat h = hatManager.getHatById(id);
            if (h != null) hatList.add(h);
        }
        int total = hatList.size();
        int downloaded = 0;
        int failed = 0;
        long totalFee = 0;
        File dataDir = new File(getFilesDir(), "data");
        dataDir.mkdirs();

        int done = 0;
        for (Hat hat : hatList) {
            if (stopped) break;
            final int fileNum = done + 1;
            try {
                File outputFile = new File(dataDir, hat.getId());
                final String hatId = hat.getId();
                Long size = hat.getSize();
                boolean success = syncManager.downloadData(hat, prikey, outputFile, cb -> {
                    if (size != null && size > 0) {
                        int pct = (int) Math.min(100, (cb * 100) / size);
                        sendProgress(hatId, pct);
                    }
                    updateNotification(fileNum, total, "Downloading...");
                });
                if (!success) {
                    TimberLogger.w(TAG, "downloadData failed for hatId=%s: %s",
                            hat.getId(), syncManager.getLastError());
                }
                if (success) {
                    totalFee += syncManager.getLastCharged();
                    List<String> locas = hat.getLocas();
                    if (locas == null) locas = new ArrayList<>();
                    boolean hasLocal = false;
                    for (String loca : locas) {
                        if (loca != null && loca.startsWith("local://")) {
                            hasLocal = true;
                            break;
                        }
                    }
                    if (!hasLocal) {
                        locas.add("local://" + outputFile.getAbsolutePath());
                        hat.setLocas(locas);
                    }
                    hat.setLast(System.currentTimeMillis());
                    hatManager.updateHat(hat);
                    downloaded++;
                } else failed++;
            } catch (Exception e) {
                TimberLogger.e(TAG, "Download error: %s", e.getMessage());
                failed++;
            }
            done++;
        }
        hatManager.commit();
        sendComplete(total, downloaded, failed, totalFee);
        stopForeground(true);
        stopSelf();
    }

    private void sendProgress(String hatId, int progress) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_HAT_ID, hatId);
        i.putExtra(EXTRA_PROGRESS, progress);
        sendBroadcast(i);
    }

    private void sendComplete(int total, int downloaded, int failed, long fee) {
        Intent i = new Intent(ACTION_COMPLETE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_TOTAL, total);
        i.putExtra(EXTRA_DOWNLOADED, downloaded);
        i.putExtra(EXTRA_FAILED, failed);
        i.putExtra(EXTRA_FEE, fee);
        sendBroadcast(i);
        showCompletionNotification(total, downloaded, failed, fee);
    }

    private void showCompletionNotification(int total, int downloaded, int failed, long fee) {
        createChannel();
        Intent intent = new Intent(this, DataActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = getString(R.string.transfer_complete_download, downloaded, failed);
        if (fee > 0) text += " " + getString(R.string.transfer_complete_fee, fee);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_data))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_download)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID + 100, n);
    }

    private Notification createNotification(int current, int total, String status) {
        createChannel();
        Intent intent = new Intent(this, DataActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.download_data))
                .setContentText(status + " " + current + "/" + total)
                .setSmallIcon(R.drawable.ic_download)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(int current, int total, String status) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, createNotification(current, total, status));
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.download_data),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
