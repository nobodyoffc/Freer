package com.fc.freer.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.fc.fc_ajdk.android.MessageCallback;
import com.fc.freer.utils.ToastUtils;

import java.lang.ref.WeakReference;

/**
 * Freer implementation of MessageCallback using Android Toast.
 */
public class FreerMessageCallback implements MessageCallback {
    
    private final WeakReference<Context> contextRef;
    private final Handler mainHandler;
    
    public FreerMessageCallback(Context context) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    private Context getContext() {
        return contextRef.get();
    }
    
    @Override
    public void showMessage(String message, Level level) {
        Context context = getContext();
        if (context == null) return;
        
        // Convert level to ToastUtils type
        String toastLevel = switch (level) {
            case WARNING -> ToastUtils.type.WARNING.name();
            case ERROR -> ToastUtils.type.ERROR.name();
            default -> ToastUtils.type.INFO.name();
        };
        
        int duration = (level == Level.WARNING || level == Level.ERROR) 
            ? Toast.LENGTH_LONG 
            : Toast.LENGTH_SHORT;
        
        // Ensure we're on the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ToastUtils.makeText(context, message, duration, toastLevel);
        } else {
            mainHandler.post(() -> ToastUtils.makeText(context, message, duration, toastLevel));
        }
    }
}

