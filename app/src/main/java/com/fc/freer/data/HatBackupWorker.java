package com.fc.freer.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.fc.fc_ajdk.data.fcData.DiskItem;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.ApiCenter;


import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * WorkManager worker for periodic HAT backup to FAPI.DISK.
 * 
 * This worker:
 * 1. Gets HATs modified since last backup
 * 2. Exports them to JSONL format
 * 3. Uploads the export to FAPI.DISK
 * 4. Updates the last backup timestamp
 * 
 * Note: On-chain Box update is not implemented here for simplicity.
 * That would require transaction signing which needs user interaction.
 */
public class HatBackupWorker extends Worker {
    private static final String TAG = "HatBackupWorker";
    
    public static final String WORK_NAME = "hat_backup_worker";
    private static final long DEFAULT_INTERVAL_HOURS = 24;
    
    public HatBackupWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }
    
    @NonNull
    @Override
    public Result doWork() {
        TimberLogger.i(TAG, "Starting HAT backup work");
        
        try {
            // Get FidManager
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null || fidManager.getLiveFid() == null) {
                TimberLogger.w(TAG, "FidManager not available, skipping backup");
                return Result.retry();
            }
            
            String liveFid = fidManager.getLiveFid();
            
            // Get HatManager
            HatManager hatManager = HatManager.getInstance(getApplicationContext(), liveFid);
            if (hatManager == null) {
                TimberLogger.w(TAG, "HatManager not available, skipping backup");
                return Result.retry();
            }
            
            // Get last backup time
            long lastBackupTime = hatManager.getLastBackupTime();
            TimberLogger.d(TAG, "Last backup time: %d", lastBackupTime);
            
            // Get modified HATs
            List<Hat> modifiedHats = hatManager.getHatsModifiedSince(lastBackupTime);
            if (modifiedHats.isEmpty()) {
                TimberLogger.i(TAG, "No HATs modified since last backup, skipping");
                return Result.success();
            }
            
            TimberLogger.i(TAG, "Found %d HATs modified since last backup", modifiedHats.size());
            
            // Export to temp file
            java.io.File tempFile = java.io.File.createTempFile("hat_backup_", ".jsonl", getApplicationContext().getCacheDir());
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                hatManager.exportToStream(fos, "Freer", modifiedHats);
                fos.close();
                
                if (tempFile.length() == 0) {
                    TimberLogger.w(TAG, "Export data is empty, skipping backup");
                    return Result.success();
                }
                
                // Get FapiClient
                FapiClient fapiClient = getFapiClient();
                if (fapiClient == null) {
                    TimberLogger.w(TAG, "FapiClient not available, will retry");
                    return Result.retry();
                }
                
                // Upload to DISK
                DiskItem diskItem = fapiClient.diskPut(tempFile);
                if (diskItem == null) {
                    TimberLogger.e(TAG, "Failed to upload backup to DISK: %s", fapiClient.getLastError());
                    return Result.retry();
                }
            
                TimberLogger.i(TAG, "Successfully uploaded backup to DISK: %s", diskItem.getId());
                
                // Update last backup time
                hatManager.setLastBackupTime(System.currentTimeMillis());
                hatManager.commit();
                
                // TODO: Update on-chain Box with the new backup DID
                // This requires transaction signing which needs user interaction
                // For now, we just log the backup DID
                TimberLogger.i(TAG, "Backup complete. DID: %s, Size: %d bytes, HATs: %d", 
                        diskItem.getId(), tempFile.length(), modifiedHats.size());
                
                return Result.success();
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
            
        } catch (IOException e) {
            TimberLogger.e(TAG, "IO error during backup: %s", e.getMessage());
            return Result.retry();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error during backup: %s", e.getMessage());
            return Result.failure();
        }
    }
    
    /**
     * Gets the FapiClient from ApiCenter.
     */
    private FapiClient getFapiClient() {
        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            Object client = apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (client instanceof FapiClient) {
                return (FapiClient) client;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting FapiClient: %s", e.getMessage());
        }
        return null;
    }
    
    /**
     * Schedules the periodic HAT backup work.
     * 
     * @param context The application context
     */
    public static void schedulePeriodicBackup(Context context) {
        schedulePeriodicBackup(context, DEFAULT_INTERVAL_HOURS);
    }
    
    /**
     * Schedules the periodic HAT backup work with custom interval.
     * 
     * @param context       The application context
     * @param intervalHours The interval in hours between backups
     */
    public static void schedulePeriodicBackup(Context context, long intervalHours) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        
        PeriodicWorkRequest backupRequest = new PeriodicWorkRequest.Builder(
                HatBackupWorker.class,
                intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                backupRequest);
        
        TimberLogger.i(TAG, "Scheduled periodic HAT backup every %d hours", intervalHours);
    }
    
    /**
     * Cancels the periodic HAT backup work.
     * 
     * @param context The application context
     */
    public static void cancelPeriodicBackup(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        TimberLogger.i(TAG, "Cancelled periodic HAT backup");
    }
    
    /**
     * Triggers an immediate backup (one-time).
     * 
     * @param context The application context
     */
    public static void triggerImmediateBackup(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        
        OneTimeWorkRequest backupRequest = new OneTimeWorkRequest.Builder(HatBackupWorker.class)
                .setConstraints(constraints)
                .build();
        
        WorkManager.getInstance(context).enqueue(backupRequest);
        TimberLogger.i(TAG, "Triggered immediate HAT backup");
    }
}
