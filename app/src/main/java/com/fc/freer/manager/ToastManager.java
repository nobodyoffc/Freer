package com.fc.freer.manager;

import android.content.Context;
import com.orhanobut.hawk.Hawk;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class ToastManager {
    private static final String TAG = "ToastManager";
    private static final String TOAST_MESSAGES_KEY_PREFIX = "toast_messages_";
    private static final int MAX_TOAST_MESSAGES = 1000;
    
    private static volatile ToastManager instance;
    private final LinkedList<ToastMessage> toastMessages;
    private String liveFid;
    
    public static class ToastMessage {
        private final long timestamp;
        private final String message;
        private final String level;
        
        public ToastMessage(long timestamp, String message, String level) {
            this.timestamp = timestamp;
            this.message = message;
            this.level = level;
        }
        
        public long getTimestamp() { return timestamp; }
        public String getMessage() { return message; }
        public String getLevel() { return level; }
    }
    
    private ToastManager() {
        toastMessages = new LinkedList<>();
    }
    
    public static synchronized ToastManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new ToastManager();
            instance.liveFid = fid;
            instance.initialize(context.getApplicationContext());
        }
        return instance;
    }
    
    public static synchronized ToastManager getInstance() {
        return instance;
    }
    
    public void initialize(Context context) {
        loadMessages();
    }
    
    public void saveToastMessage(String message, String level) {
        try {
            long timestamp = System.currentTimeMillis();
            ToastMessage toastMessage = new ToastMessage(timestamp, message, level);
            
            synchronized (toastMessages) {
                toastMessages.addFirst(toastMessage);
                
                // Keep only the most recent 1000 messages
                while (toastMessages.size() > MAX_TOAST_MESSAGES) {
                    toastMessages.removeLast();
                }
                
                saveMessages();
            }
            
            TimberLogger.d(TAG, "Toast message saved: %s", message);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving toast message: " + e.getMessage(), e);
        }
    }
    
    public void saveToastMessage(String message) {
        saveToastMessage(message, "INFO");
    }
    
    public List<ToastMessage> getToastMessages() {
        synchronized (toastMessages) {
            return new ArrayList<>(toastMessages);
        }
    }
    
    public void clearToastMessages() {
        synchronized (toastMessages) {
            toastMessages.clear();
            saveMessages();
        }
        TimberLogger.d(TAG, "All toast messages cleared");
    }
    
    private void loadMessages() {
        try {
            String key = getToastMessagesKey();
            List<ToastMessageData> savedMessages = Hawk.get(key, new ArrayList<>());
            synchronized (toastMessages) {
                toastMessages.clear();
                for (ToastMessageData data : savedMessages) {
                    toastMessages.add(new ToastMessage(data.timestamp, data.message, data.level));
                }
            }
            TimberLogger.d(TAG, "Loaded %d toast messages from HawkDB for fid: %s", savedMessages.size(), liveFid);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading toast messages: " + e.getMessage(), e);
        }
    }
    
    private void saveMessages() {
        try {
            List<ToastMessageData> dataList = new ArrayList<>();
            synchronized (toastMessages) {
                for (ToastMessage msg : toastMessages) {
                    dataList.add(new ToastMessageData(msg.timestamp, msg.message, msg.level));
                }
            }
            String key = getToastMessagesKey();
            Hawk.put(key, dataList);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving toast messages: " + e.getMessage(), e);
        }
    }
    
    private String getToastMessagesKey() {
        return TOAST_MESSAGES_KEY_PREFIX + (liveFid != null ? liveFid : "default");
    }
    
    public String getLiveFid() {
        return liveFid;
    }
    
    public void setLiveFid(String liveFid) {
        this.liveFid = liveFid;
    }
    
    // Data class for serialization
    private static class ToastMessageData {
        public long timestamp;
        public String message;
        public String level;
        
        public ToastMessageData(long timestamp, String message, String level) {
            this.timestamp = timestamp;
            this.message = message;
            this.level = level;
        }
        
        // No-arg constructor for serialization
        public ToastMessageData() {}
    }
}