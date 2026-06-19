package com.fc.fc_ajdk.android;

import android.graphics.Bitmap;

import java.util.List;

/**
 * Callback interface for displaying QR codes.
 * Implementations can show QR codes using dialogs, fragments, or other UI mechanisms.
 */
public interface QRDisplayCallback {
    
    /**
     * Display QR code bitmaps to the user
     * 
     * @param qrBitmaps List of QR code bitmaps to display
     * @param content Original content (for copying to clipboard)
     * @param title Title to display
     */
    void showQRCodes(List<Bitmap> qrBitmaps, String content, String title);
    
    /**
     * Called when QR code generation fails
     * 
     * @param errorMessage Error message to display
     */
    void onQRGenerationError(String errorMessage);
}

