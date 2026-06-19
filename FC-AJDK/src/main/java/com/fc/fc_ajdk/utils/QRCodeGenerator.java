package com.fc.fc_ajdk.utils;

import android.graphics.Bitmap;

import com.fc.fc_ajdk.android.QRDisplayCallback;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for generating QR codes.
 * This class can be used across multiple FC ecosystem applications.
 * 
 * For displaying QR codes, provide a QRDisplayCallback implementation.
 */
public class QRCodeGenerator {
    
    private static final String TAG = "QRCodeGenerator";
    
    /**
     * Default capacity for QR code content in bytes
     */
    public static final int DEFAULT_CAPACITY = 300;
    
    /**
     * Default QR code size in pixels
     */
    public static final int DEFAULT_SIZE = 461;
    
    /**
     * Generates QR codes from the given content and displays them via callback.
     * 
     * @param content The content to encode in the QR code
     * @param title The title to display
     * @param callback The callback to display QR codes
     */
    public static void generateAndShow(String content, String title, QRDisplayCallback callback) {
        if (callback == null) {
            TimberLogger.w(TAG, "QRDisplayCallback is null, cannot show QR codes");
            return;
        }
        
        List<Bitmap> qrBitmaps = generateQRBitmaps(content);
        
        if (!qrBitmaps.isEmpty()) {
            callback.showQRCodes(qrBitmaps, content, title);
        } else {
            callback.onQRGenerationError("Error creating QR code");
        }
    }
    
    /**
     * Generates QR codes from the given content.
     * 
     * @param content The content to encode in the QR code
     * @param callback The callback to display QR codes
     */
    public static void generateAndShow(String content, QRDisplayCallback callback) {
        generateAndShow(content, "", callback);
    }
    
    /**
     * Generates QR code bitmaps from the given content.
     * 
     * @param content The content to encode in the QR code
     * @return A list of QR code bitmaps
     */
    public static List<Bitmap> generateQRBitmaps(String content) {
        return generateQRBitmaps(content, DEFAULT_SIZE, DEFAULT_CAPACITY);
    }
    
    /**
     * Generates QR code bitmaps from the given content with custom size.
     * 
     * @param content The content to encode in the QR code
     * @param size The size of the QR code in pixels
     * @param maxBytesPerCode Maximum bytes per QR code
     * @return A list of QR code bitmaps
     */
    public static List<Bitmap> generateQRBitmaps(String content, int size, int maxBytesPerCode) {
        List<Bitmap> qrBitmaps = new ArrayList<>();
        
        if (content == null || content.isEmpty()) {
            return qrBitmaps;
        }

        try {
            // Create encoding hints for UTF-8
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);

            // Split content into chunks if it exceeds capacity
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            if (contentBytes.length > maxBytesPerCode) {
                List<String> chunks = splitContent(content, maxBytesPerCode);
                for (String chunk : chunks) {
                    BitMatrix bitMatrix = new MultiFormatWriter().encode(
                            chunk,
                            BarcodeFormat.QR_CODE,
                            size,
                            size,
                            hints
                    );
                    qrBitmaps.add(createBitmapFromBitMatrix(bitMatrix));
                }
            } else {
                BitMatrix bitMatrix = new MultiFormatWriter().encode(
                        content,
                        BarcodeFormat.QR_CODE,
                        size,
                        size,
                        hints
                );
                qrBitmaps.add(createBitmapFromBitMatrix(bitMatrix));
            }
        } catch (WriterException e) {
            TimberLogger.e(TAG, "Error generating QR code: %s", e.getMessage());
        }
        
        return qrBitmaps;
    }

    /**
     * Splits content into chunks that fit within QR code capacity.
     * 
     * @param content The content to split
     * @param maxBytes Maximum bytes per chunk
     * @return A list of content chunks
     */
    public static List<String> splitContent(String content, int maxBytes) {
        List<String> chunks = new ArrayList<>();

        int startIndex = 0;
        while (startIndex < content.length()) {
            int endIndex = startIndex;
            int currentChunkBytes = 0;
            
            // Try to add characters until we hit the byte limit
            while (endIndex < content.length()) {
                String nextChar = content.substring(endIndex, Math.min(endIndex + 1, content.length()));
                int nextCharBytes = nextChar.getBytes(StandardCharsets.UTF_8).length;
                
                // If adding next character would exceed the limit, break
                if (currentChunkBytes + nextCharBytes > maxBytes) {
                    break;
                }
                
                currentChunkBytes += nextCharBytes;
                endIndex++;
            }
            
            // If we couldn't add even one character (shouldn't happen with reasonable limit)
            if (endIndex == startIndex) {
                endIndex = startIndex + 1;  // Force include at least one character
            }
            
            // Add the chunk
            chunks.add(content.substring(startIndex, endIndex));
            startIndex = endIndex;
        }
        
        return chunks;
    }

    /**
     * Converts a BitMatrix to a Bitmap.
     * 
     * @param bitMatrix The BitMatrix to convert
     * @return A Bitmap representation of the BitMatrix
     */
    public static Bitmap createBitmapFromBitMatrix(BitMatrix bitMatrix) {
        int width = bitMatrix.getWidth();
        int height = bitMatrix.getHeight();
        int[] pixels = new int[width * height];
        
        // Convert bit matrix to pixel array
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        
        // Create the bitmap
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        
        return bitmap;
    }

    /**
     * Generates QR codes from a string containing JSON objects separated by newlines.
     * 
     * @param jsonListString The string containing JSON objects separated by newlines
     * @param title The title to display
     * @param callback The callback to display QR codes
     */
    public static void generateFromJsonList(String jsonListString, String title, QRDisplayCallback callback) {
        if (callback == null) {
            TimberLogger.w(TAG, "QRDisplayCallback is null, cannot show QR codes");
            return;
        }
        
        if (jsonListString == null || jsonListString.trim().isEmpty()) {
            callback.onQRGenerationError("No data to export");
            return;
        }
        
        // Split the string into individual JSON objects by newlines
        String[] jsonObjects = jsonListString.split("\n");
        List<Bitmap> allQrBitmaps = new ArrayList<>();
        
        try {
            // Generate QR code for each JSON object
            for (String jsonObject : jsonObjects) {
                if (jsonObject.trim().isEmpty()) continue;
                
                List<Bitmap> qrBitmaps = generateQRBitmaps(jsonObject.trim());
                allQrBitmaps.addAll(qrBitmaps);
            }
            
            if (!allQrBitmaps.isEmpty()) {
                // Show all QR codes, but pass the original string for copying
                callback.showQRCodes(allQrBitmaps, jsonListString, title);
            } else {
                callback.onQRGenerationError("Error creating QR code");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error generating QR from JSON list: %s", e.getMessage());
            callback.onQRGenerationError("Error creating QR code");
        }
    }

    /**
     * Makes QR bitmaps list from a list of JSON strings.
     * 
     * @param jsonList List of JSON strings
     * @return List of bitmap lists (one list per JSON string)
     */
    public static List<List<Bitmap>> makeQRBitmapsList(List<String> jsonList) {
        List<List<Bitmap>> qrBitmapsList = new ArrayList<>();
        if (jsonList == null || jsonList.isEmpty()) return qrBitmapsList;

        for (String json : jsonList) {
            if (json != null && !json.isEmpty()) {
                List<Bitmap> bitmaps = generateQRBitmaps(json);
                qrBitmapsList.add(bitmaps);
            }
        }

        return qrBitmapsList;
    }
    
    /**
     * Count total QR codes in a bitmaps list
     */
    public static int countTotalQRCodes(List<List<Bitmap>> bitmapsList) {
        int count = 0;
        if (bitmapsList != null) {
            for (List<Bitmap> bitmaps : bitmapsList) {
                if (bitmaps != null) {
                    count += bitmaps.size();
                }
            }
        }
        return count;
    }
}

