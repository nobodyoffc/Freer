package com.fc.freer.im;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Queue for reliable message delivery with retry logic.
 * Handles offline messages and failed deliveries.
 */
public class MessageQueue {
    private static final String TAG = "MessageQueue";
    private static final String DB_NAME = "message_queue";
    
    // Retry configuration
    private static final int MAX_RETRIES = 5;
    private static final long[] RETRY_DELAYS_MS = {5000, 15000, 60000, 300000, 900000}; // 5s, 15s, 1m, 5m, 15m
    
    private LocalDB<QueuedMessage> db;
    private final String liveFid;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final Handler mainHandler;
    
    private MessageSender messageSender;
    private boolean isRunning = false;
    
    /**
     * Delivery result classification.
     * Allows the queue to distinguish transient failures (worth retrying)
     * from permanent ones (fail immediately).
     */
    public enum SendResult {
        /** Message delivered successfully. Remove from queue. */
        SUCCESS,
        /** Transient failure (network glitch, peer offline). Retry later. */
        RETRY_TRANSIENT,
        /** Permanent failure (invalid target, no client). Fail immediately. */
        FAIL_PERMANENT
    }

    public interface MessageSender {
        /**
         * Attempt to send a message.
         * @return SendResult indicating success, transient failure, or permanent failure
         */
        SendResult sendMessage(ImMessage message);
    }
    
    public interface QueueListener {
        void onMessageQueued(ImMessage message);
        void onMessageSent(ImMessage message);
        void onMessageFailed(ImMessage message, String reason);
    }
    
    private QueueListener listener;
    
    public MessageQueue(Context context, String liveFid) {
        this.liveFid = liveFid;
        this.executor = Executors.newSingleThreadExecutor();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        DatabaseManager dbManager = DatabaseManager.getInstance();
        this.db = dbManager.getEntityDatabase(liveFid, QueuedMessage.class, QueuedMessage.class);
    }
    
    /**
     * Set the sender implementation.
     */
    public void setMessageSender(MessageSender sender) {
        this.messageSender = sender;
    }
    
    /**
     * Set the listener for queue events.
     */
    public void setListener(QueueListener listener) {
        this.listener = listener;
    }
    
    /**
     * Start the queue processor.
     */
    public void start() {
        if (isRunning) return;
        isRunning = true;
        
        // Process pending messages periodically
        scheduler.scheduleWithFixedDelay(this::processPending, 0, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Stop the queue processor.
     */
    public void stop() {
        isRunning = false;
        scheduler.shutdown();
        executor.shutdown();
    }
    
    /**
     * Enqueue a message for delivery.
     */
    public void enqueue(ImMessage message) {
        executor.execute(() -> {
            try {
                // ID is assigned by ImManager.send() via FUDP layer
                
                QueuedMessage qm = new QueuedMessage();
                qm.setId(message.getId());
                qm.setMessage(message);
                qm.setRetryCount(0);
                qm.setNextRetryAt(System.currentTimeMillis());
                qm.setCreatedAt(System.currentTimeMillis());
                
                db.put(qm.getId(),qm);
                
                if (listener != null) {
                    mainHandler.post(() -> listener.onMessageQueued(message));
                }
                
                // Try immediate delivery
                processMessage(qm);
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to enqueue message: %s", e.getMessage());
            }
        });
    }
    
    /**
     * Retry a specific message immediately.
     */
    public void retryNow(String messageId) {
        executor.execute(() -> {
            QueuedMessage qm = db.get(messageId);
            if (qm != null) {
                qm.setNextRetryAt(System.currentTimeMillis());
                processMessage(qm);
            }
        });
    }
    
    /**
     * Cancel a queued message.
     */
    public void cancel(String messageId) {
        executor.execute(() -> db.remove(messageId));
    }
    
    /**
     * Get all pending messages.
     */
    public List<ImMessage> getPendingMessages() {
        Map<String,QueuedMessage> queued = db.getAll();
        List<ImMessage> messages = new ArrayList<>();
        for (String id : queued.keySet()) {
            QueuedMessage qm = queued.get(id);
            if (qm!=null && qm.getMessage() != null) {
                messages.add(qm.getMessage());
            }
        }
        return messages;
    }
    
    /**
     * Get pending count.
     */
    public int getPendingCount() {
        return db.getSize();
    }
    
    /**
     * Process all pending messages.
     */
    private void processPending() {
        if (!isRunning || messageSender == null) return;
        
        try {
            Map<String,QueuedMessage> pending = db.getAll();
            long now = System.currentTimeMillis();
            
            for (String id : pending.keySet()) {
                QueuedMessage qm = pending.get(id);

                if (qm!=null && qm.getNextRetryAt() <= now) {
                    processMessage(qm);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing pending messages: %s", e.getMessage());
        }
    }
    
    /**
     * Process a single queued message.
     */
    private void processMessage(QueuedMessage qm) {
        if (messageSender == null) return;
        
        try {
            ImMessage message = qm.getMessage();
            if (message == null) {
                db.remove(qm.getId());
                return;
            }
            
            SendResult result = messageSender.sendMessage(message);
            
            switch (result) {
                case SUCCESS -> {
                    db.remove(qm.getId());
                    if (listener != null) {
                        mainHandler.post(() -> listener.onMessageSent(message));
                    }
                }
                case FAIL_PERMANENT -> {
                    db.remove(qm.getId());
                    message.setStatus(MessageStatus.FAILED);
                    TimberLogger.w(TAG, "Message %s permanently failed", message.getId());
                    if (listener != null) {
                        mainHandler.post(() -> listener.onMessageFailed(message, "Delivery permanently failed"));
                    }
                }
                case RETRY_TRANSIENT -> {
                    int retryCount = qm.getRetryCount() + 1;
                    if (retryCount >= MAX_RETRIES) {
                        db.remove(qm.getId());
                        message.setStatus(MessageStatus.FAILED);
                        if (listener != null) {
                            mainHandler.post(() -> listener.onMessageFailed(message, "Max retries exceeded"));
                        }
                    } else {
                        long delay = RETRY_DELAYS_MS[Math.min(retryCount, RETRY_DELAYS_MS.length - 1)];
                        qm.setRetryCount(retryCount);
                        qm.setNextRetryAt(System.currentTimeMillis() + delay);
                        db.put(qm.getId(), qm);
                        TimberLogger.d(TAG, "Scheduled retry %d for message %s in %d ms",
                                retryCount, message.getId(), delay);
                    }
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing message %s: %s", qm.getId(), e.getMessage());
        }
    }
    
    /**
     * Clear all pending messages.
     */
    public void clearAll() {
        executor.execute(() -> {
            Map<String,QueuedMessage> all = db.getAll();
            for (String id : all.keySet()) {
                QueuedMessage qm =all.get(id);
                if(qm!=null)
                    db.remove(qm.getId());
            }
        });
    }
    
    /**
     * Wrapper class for queued messages.
     */
    public static class QueuedMessage extends com.fc.fc_ajdk.data.fcData.FcEntity {
        private ImMessage message;
        private Integer retryCount;
        private Long nextRetryAt;
        private Long createdAt;
        
        public ImMessage getMessage() { return message; }
        public void setMessage(ImMessage message) { this.message = message; }
        
        public Integer getRetryCount() { return retryCount != null ? retryCount : 0; }
        public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
        
        public Long getNextRetryAt() { return nextRetryAt != null ? nextRetryAt : 0L; }
        public void setNextRetryAt(Long nextRetryAt) { this.nextRetryAt = nextRetryAt; }
        
        public Long getCreatedAt() { return createdAt; }
        public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    }
}
