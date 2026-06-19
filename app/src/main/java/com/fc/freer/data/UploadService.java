package com.fc.freer.data;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.fc.fc_ajdk.data.fcData.DiskItem;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.data.DataActivity;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Foreground service for background upload of HAT data to DISK.
 */
public class UploadService extends Service {
    private static final String TAG = "UploadService";
    private static final String CHANNEL_ID = "upload_service_channel";
    private static final int NOTIFICATION_ID = 4001;

    public static final String EXTRA_HAT_IDS = "extra_hat_ids";
    public static final String ACTION_PROGRESS = "com.fc.freer.UPLOAD_PROGRESS";
    public static final String ACTION_COMPLETE = "com.fc.freer.UPLOAD_COMPLETE";
    public static final String EXTRA_TOTAL = "extra_total";
    public static final String EXTRA_UPLOADED = "extra_uploaded";
    public static final String EXTRA_SKIPPED = "extra_skipped";
    public static final String EXTRA_FAILED = "extra_failed";
    public static final String EXTRA_FEE = "extra_fee";
    public static final String EXTRA_HAT_ID = "extra_hat_id";
    public static final String EXTRA_PROGRESS = "extra_progress";
    public static final String ACTION_CANCEL = "com.fc.freer.UPLOAD_CANCEL";

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
            registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cancelReceiver, filter);
        }
        startForeground(NOTIFICATION_ID, createNotification(0, hatIds.size(), "Starting..."));
        stopped = false;
        new Thread(() -> runUpload(hatIds)).start();
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

    private void runUpload(List<String> hatIds) {
        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
        String mainFid = fidManager != null ? fidManager.getMainFid() : null;
        if (liveFid == null || !liveFid.equals(mainFid)) {
            TimberLogger.w(TAG, "Upload aborted: liveFid != mainFid");
            sendComplete(hatIds.size(), 0, 0, 0, 0);
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
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        long totalFee = 0;
        File dataDir = new File(getFilesDir(), "data");

        Map<String, DiskItem> existing = syncManager.batchCheckOnDisk(hatList);
        List<Hat> toUpload = new ArrayList<>();
        for (Hat hat : hatList) {
            if (isHatAlreadyOnDisk(hat, existing)) skipped++;
            else toUpload.add(hat);
        }

        int done = 0;
        for (Hat hat : toUpload) {
            if (stopped) break;
            final int fileNum = done + 1;
            try {
                File localFile = findLocalFile(hat, dataDir);
                if (localFile == null || !localFile.exists()) {
                    failed++;
                    done++;
                    continue;
                }
                long fileSize = localFile.length();
                DiskItem item = syncManager.uploadData(localFile, hat, true, null, cb -> {
                    if (fileSize > 0) {
                        int pct = (int) Math.min(100, (cb * 100) / fileSize);
                        sendProgress(hat.getId(), pct);
                        updateNotification(fileNum, total, "Uploading...");
                    }
                });
                if (item != null) {
                    uploaded++;
                    totalFee += syncManager.getLastCharged();
                } else failed++;
            } catch (Exception e) {
                TimberLogger.e(TAG, "Upload error: %s", e.getMessage());
                failed++;
            }
            done++;
        }
        hatManager.commit();
        sendComplete(total, uploaded, skipped, failed, totalFee);
        stopForeground(true);
        stopSelf();
    }

    private boolean isHatAlreadyOnDisk(Hat hat, Map<String, DiskItem> existing) {
        if (existing == null) return false;
        if (existing.containsKey(hat.getId())) return true;
        List<String> cipherIds = hat.getCipherIds();
        if (cipherIds != null) {
            for (String cid : cipherIds) {
                if (existing.containsKey(cid)) return true;
            }
        }
        return false;
    }

    private File findLocalFile(Hat hat, File dataDir) {
        List<String> locas = hat.getLocas();
        if (locas != null) {
            for (String loca : locas) {
                if (loca != null && loca.startsWith("local://")) {
                    File f = new File(loca.substring("local://".length()));
                    if (f.exists()) return f;
                }
            }
        }
        if (hat.getId() != null) {
            File f = new File(dataDir, hat.getId());
            if (f.exists()) return f;
        }
        return null;
    }

    private void sendProgress(String hatId, int progress) {
        Intent i = new Intent(ACTION_PROGRESS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_HAT_ID, hatId);
        i.putExtra(EXTRA_PROGRESS, progress);
        sendBroadcast(i);
    }

    private void sendComplete(int total, int uploaded, int skipped, int failed, long fee) {
        Intent i = new Intent(ACTION_COMPLETE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_TOTAL, total);
        i.putExtra(EXTRA_UPLOADED, uploaded);
        i.putExtra(EXTRA_SKIPPED, skipped);
        i.putExtra(EXTRA_FAILED, failed);
        i.putExtra(EXTRA_FEE, fee);
        sendBroadcast(i);
        showCompletionNotification(total, uploaded, skipped, failed, fee);
    }

    private void showCompletionNotification(int total, int uploaded, int skipped, int failed, long fee) {
        createChannel();
        Intent intent = new Intent(this, DataActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = getString(R.string.transfer_complete_upload, uploaded, skipped, failed);
        if (fee > 0) text += " " + getString(R.string.transfer_complete_fee, fee);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.upload_data))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_upload)
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
                .setContentTitle(getString(R.string.upload_data))
                .setContentText(status + " " + current + "/" + total)
                .setSmallIcon(R.drawable.ic_upload)
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
                    getString(R.string.upload_data),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }
}
