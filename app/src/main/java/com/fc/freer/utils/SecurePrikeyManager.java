package com.fc.freer.utils;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.ui.WaitingDialog;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Secure private key manager that requires user confirmation before decrypting private keys
 * and ensures automatic key erasure after use.
 */
public class SecurePrikeyManager {
    private static final String TAG = "SecurePrikeyManager";
    private static final int AUTO_ERASE_DELAY_MS = 300000; // 30 seconds

    public interface PrikeyCallback {
        void onPrikeyDecrypted(byte[] prikey);
        void onError(String errorMessage);
        void onUserDenied();
    }

    public interface PrikeyTask {
        void execute(byte[] prikey) throws Exception;
    }

    /**
     * Request private key with user confirmation dialog
     * The private key will be automatically erased after the callback completes
     * 
     * @param context Application context
     * @param purpose Description of why the private key is needed (shown to user)
     * @param prikeyCipher Encrypted private key cipher to decrypt
     * @param callback Callback to receive the decrypted private key
     */
    public static void requestPrikey(Context context, String purpose, String prikeyCipher, PrikeyCallback callback) {
        if (context == null || callback == null) {
            if (callback != null) {
                callback.onError("Context or callback is null");
            }
            return;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        
        // Show user confirmation dialog
        mainHandler.post(() -> {
            // Check if context is a valid Activity and not finishing
            if (!(context instanceof Activity activity)) {
                // Try to get the current activity from FreerApplication
                Activity currentActivity = getCurrentActivity();
                if (currentActivity == null) {
                    TimberLogger.e(TAG, "Context is not an Activity and no current activity available, cannot show dialog");
                    callback.onError("Invalid context for showing dialog");
                    return;
                }
                // Use the current activity instead
                showConfirmationDialog(currentActivity, purpose, prikeyCipher, callback);
                return;
            }

            showConfirmationDialog(activity, purpose, prikeyCipher, callback);
        });
    }

    private static void showConfirmationDialog(Activity activity, String purpose, String prikeyCipher, PrikeyCallback callback) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            TimberLogger.e(TAG, "Activity is finishing or destroyed, cannot show dialog");
            callback.onError("Activity is no longer available");
            return;
        }

        UserConfirmDialog dialog = new UserConfirmDialog(activity,"Request Prikey", purpose, choice -> {
            switch (choice) {
                case YES:
                    // Show waiting dialog immediately after user confirms
                    WaitingDialog waitingDialog = new WaitingDialog(activity, "Decrypting private key and processing request...");
                    waitingDialog.show();
                    
                    // User confirmed, proceed to decrypt private key
                    decryptPrikeyInBackground(prikeyCipher, new PrikeyCallback() {
                        @Override
                        public void onPrikeyDecrypted(byte[] prikey) {
                            // Dismiss waiting dialog on main thread
                            activity.runOnUiThread(() -> {
                                if (waitingDialog.isShowing()) {
                                    waitingDialog.dismiss();
                                }
                            });
                            callback.onPrikeyDecrypted(prikey);
                        }

                        @Override
                        public void onError(String errorMessage) {
                            // Dismiss waiting dialog on main thread
                            activity.runOnUiThread(() -> {
                                if (waitingDialog.isShowing()) {
                                    waitingDialog.dismiss();
                                }
                            });
                            callback.onError(errorMessage);
                        }

                        @Override
                        public void onUserDenied() {
                            // Dismiss waiting dialog on main thread
                            activity.runOnUiThread(() -> {
                                if (waitingDialog.isShowing()) {
                                    waitingDialog.dismiss();
                                }
                            });
                            callback.onUserDenied();
                        }
                    });
                    break;
                case NO:
                case STOP:
                    TimberLogger.i(TAG, "User denied private key access for: %s", purpose);
                    callback.onUserDenied();
                    break;
            }
        }, false); // Show as warning dialog
        
        dialog.show();
    }

    private static Activity getCurrentActivity() {
        try {
            return com.fc.freer.FreerApplication.getCurrentActivity();
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to get current activity: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Execute a task with private key using user confirmation
     * The private key is automatically erased after task completion
     * 
     * @param context Application context
     * @param purpose Description of why the private key is needed
     * @param prikeyCipher Encrypted private key cipher to decrypt
     * @param task Task to execute with the private key
     * @param callback Callback for completion notification
     */
    public static void executeWithPrikey(Context context, String purpose, String prikeyCipher, PrikeyTask task, ExecutionCallback callback) {
        requestPrikey(context, purpose, prikeyCipher, new PrikeyCallback() {
            @Override
            public void onPrikeyDecrypted(byte[] prikey) {
                try {
                    // Execute the task
                    task.execute(prikey);
                    
                    // Erase the private key immediately after use
                    BytesUtils.clearByteArray(prikey);
                    
                    if (callback != null) {
                        callback.onSuccess();
                    }
                    
                    TimberLogger.d(TAG, "Task completed and private key erased for purpose: %s", purpose);
                    
                } catch (Exception e) {
                    // Ensure private key is erased even on error
                    BytesUtils.clearByteArray(prikey);
                    
                    TimberLogger.e(TAG, "Error executing task with private key: %s", e.getMessage(), e);
                    
                    if (callback != null) {
                        callback.onError("Task execution failed: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onError(String errorMessage) {
                if (callback != null) {
                    callback.onError(errorMessage);
                }
            }

            @Override
            public void onUserDenied() {
                if (callback != null) {
                    callback.onUserDenied();
                }
            }
        });
    }

    /**
     * Synchronous method to request private key with user confirmation
     * WARNING: This method blocks the current thread until user responds
     * Should only be used in background threads
     * 
     * @param context Application context
     * @param purpose Description of why the private key is needed
     * @param prikeyCipher Encrypted private key cipher to decrypt
     * @return Decrypted private key or null if failed/denied
     */
    public static byte[] requestPrikeySync(Context context, String purpose, String prikeyCipher) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            TimberLogger.e(TAG, "requestPrikeySync cannot be called on main thread");
            return null;
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<byte[]> prikeyRef = new AtomicReference<>();
        
        requestPrikey(context, purpose, prikeyCipher, new PrikeyCallback() {
            @Override
            public void onPrikeyDecrypted(byte[] prikey) {
                prikeyRef.set(prikey);
                latch.countDown();
            }

            @Override
            public void onError(String errorMessage) {
                TimberLogger.e(TAG, "Error getting private key: %s", errorMessage);
                latch.countDown();
            }

            @Override
            public void onUserDenied() {
                TimberLogger.i(TAG, "User denied private key access");
                latch.countDown();
            }
        });

        try {
            latch.await();
            return prikeyRef.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TimberLogger.e(TAG, "Interrupted while waiting for user confirmation");
            return null;
        }
    }

    /**
     * Decrypt private key in background thread
     */
    private static void decryptPrikeyInBackground(String prikeyCipher, PrikeyCallback callback) {
        new Thread(() -> {
            try {
                if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                    callback.onError("Private key cipher is null or empty");
                    return;
                }

                byte[] symkey = ConfigureManager.getInstance().getSymkey();
                if (symkey == null) {
                    callback.onError("Symmetric key is null");
                    return;
                }

                // Decrypt the private key
                Decryptor decryptor = new Decryptor();
                CryptoDataByte cryptoDataByte = decryptor.decryptJsonBySymkey(prikeyCipher, symkey);
                
                if (cryptoDataByte.getCode() != 0) {
                    callback.onError("Failed to decrypt private key: " + cryptoDataByte.getMessage());
                    return;
                }

                byte[] prikey = cryptoDataByte.getData();
                if (prikey == null || prikey.length == 0) {
                    callback.onError("Decrypted private key is null or empty");
                    return;
                }

                TimberLogger.d(TAG, "Private key successfully decrypted");
                
                // Schedule automatic erasure as a safety measure
                scheduleAutoErase(prikey);
                
                callback.onPrikeyDecrypted(prikey);

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error decrypting private key: %s", e.getMessage(), e);
                callback.onError("Exception while decrypting private key: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Schedule automatic erasure of private key as a safety measure
     */
    private static void scheduleAutoErase(byte[] prikey) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (prikey != null) {
                BytesUtils.clearByteArray(prikey);
                TimberLogger.w(TAG, "Private key automatically erased after timeout");
            }
        }, AUTO_ERASE_DELAY_MS);
    }

    /**
     * Execution callback interface
     */
    public interface ExecutionCallback {
        void onSuccess();
        void onError(String errorMessage);
        void onUserDenied();
    }

    /**
     * Fetch private key silently without user confirmation dialog
     * This method should only be used when user has already given permission
     * (e.g., in retry scenarios after initial confirmation)
     * 
     * @param prikeyCipher Encrypted private key cipher to decrypt
     * @return Decrypted private key or null if failed
     */
    public static byte[] fetchPrikeySilent(String prikeyCipher) {
        try {
            if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                TimberLogger.e(TAG, "Private key cipher is null or empty");
                return null;
            }

            byte[] symkey = ConfigureManager.getInstance().getSymkey();
            if (symkey == null) {
                TimberLogger.e(TAG, "Symmetric key is null");
                return null;
            }

            // Decrypt the private key
            Decryptor decryptor = new Decryptor();
            CryptoDataByte cryptoDataByte = decryptor.decryptJsonBySymkey(prikeyCipher, symkey);
            
            if (cryptoDataByte.getCode() != 0) {
                TimberLogger.e(TAG, "Failed to decrypt private key: %s", cryptoDataByte.getMessage());
                return null;
            }

            byte[] prikey = cryptoDataByte.getData();
            if (prikey == null || prikey.length == 0) {
                TimberLogger.e(TAG, "Decrypted private key is null or empty");
                return null;
            }

            TimberLogger.d(TAG, "Private key successfully decrypted silently");
            
            // Schedule automatic erasure as a safety measure
            scheduleAutoErase(prikey);
            
            return prikey;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error decrypting private key silently: %s", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Utility method to safely erase private key
     */
    public static void erasePrikey(byte[] prikey) {
        if (prikey != null) {
            BytesUtils.clearByteArray(prikey);
            TimberLogger.d(TAG, "Private key manually erased");
        }
    }
}