package com.fc.freer.android;

import com.fc.fc_ajdk.android.LogProvider;
import com.fc.fc_ajdk.utils.TimberLogger;

/**
 * Freer implementation of LogProvider using TimberLogger.
 */
public class FreerLogProvider implements LogProvider {
    
    @Override
    public void d(String tag, String message) {
        TimberLogger.d(tag, message);
    }
    
    @Override
    public void d(String tag, String format, Object... args) {
        TimberLogger.d(tag, format, args);
    }
    
    @Override
    public void i(String tag, String message) {
        TimberLogger.i(tag, message);
    }
    
    @Override
    public void i(String tag, String format, Object... args) {
        TimberLogger.i(tag, format, args);
    }
    
    @Override
    public void w(String tag, String message) {
        TimberLogger.w(tag, message);
    }
    
    @Override
    public void w(String tag, String format, Object... args) {
        TimberLogger.w(tag, format, args);
    }
    
    @Override
    public void e(String tag, String message) {
        TimberLogger.e(tag, message);
    }
    
    @Override
    public void e(String tag, String format, Object... args) {
        TimberLogger.e(tag, format, args);
    }
    
    @Override
    public void e(String tag, String message, Throwable t) {
        TimberLogger.e(tag, message, t);
    }
    
    @Override
    public void e(String tag, Throwable t, String format, Object... args) {
        TimberLogger.e(tag, t, format, args);
    }
}

