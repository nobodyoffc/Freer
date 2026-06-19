package com.fc.freer.android;

import android.content.Context;

import com.fc.fc_ajdk.android.StorageProvider;

import java.lang.ref.WeakReference;

/**
 * Freer implementation of StorageProvider using Android Context.
 */
public class FreerStorageProvider implements StorageProvider {
    
    private final WeakReference<Context> contextRef;
    
    public FreerStorageProvider(Context context) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
    }
    
    private Context getContext() {
        return contextRef.get();
    }
    
    @Override
    public String getDataDir() {
        Context context = getContext();
        if (context == null) return null;
        return context.getFilesDir().getAbsolutePath();
    }
    
    @Override
    public String getCacheDir() {
        Context context = getContext();
        if (context == null) return null;
        return context.getCacheDir().getAbsolutePath();
    }
}

