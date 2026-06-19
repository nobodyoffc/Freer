package com.fc.freer.data;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for handling file types and MIME types.
 * Provides methods to determine how to open different file types.
 */
public class FileTypeHandler {
    
    // Editable text file MIME types
    private static final Set<String> EDITABLE_MIME_TYPES = new HashSet<>(Arrays.asList(
            "text/plain",
            "text/markdown",
            "text/x-markdown",
            "text/md",
            "application/json",
            "text/json",
            "text/html",
            "text/xml",
            "text/css",
            "text/javascript",
            "application/javascript",
            "text/x-java-source",
            "text/x-python",
            "text/x-kotlin"
    ));

    // Editable file extensions
    private static final Set<String> EDITABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "md", "markdown", "json", "xml", "html", "htm",
            "css", "js", "java", "kt", "py", "sh", "bat", "log",
            "ini", "cfg", "conf", "properties", "yaml", "yml"
    ));

    // Media file MIME type prefixes
    private static final String[] MEDIA_PREFIXES = {"image/", "video/", "audio/"};

    /**
     * Checks if a file is editable in a text editor.
     *
     * @param mimeType The MIME type of the file
     * @return true if the file can be edited as text
     */
    public static boolean isEditable(String mimeType) {
        if (mimeType == null) return false;
        
        String lowerMime = mimeType.toLowerCase();
        
        // Check exact matches
        if (EDITABLE_MIME_TYPES.contains(lowerMime)) {
            return true;
        }
        
        // Check if it starts with text/
        return lowerMime.startsWith("text/");
    }

    /**
     * Checks if a file is editable based on its extension.
     *
     * @param fileName The file name
     * @return true if the file can be edited as text
     */
    public static boolean isEditableByExtension(String fileName) {
        if (fileName == null) return false;
        String ext = getExtension(fileName);
        return ext != null && EDITABLE_EXTENSIONS.contains(ext.toLowerCase());
    }

    /**
     * Checks if a file is a media file (image, video, or audio).
     *
     * @param mimeType The MIME type of the file
     * @return true if the file is a media file
     */
    public static boolean isMediaFile(String mimeType) {
        if (mimeType == null) return false;
        
        String lowerMime = mimeType.toLowerCase();
        for (String prefix : MEDIA_PREFIXES) {
            if (lowerMime.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a file is an image.
     *
     * @param mimeType The MIME type of the file
     * @return true if the file is an image
     */
    public static boolean isImage(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("image/");
    }

    /**
     * Checks if a file is a video.
     *
     * @param mimeType The MIME type of the file
     * @return true if the file is a video
     */
    public static boolean isVideo(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("video/");
    }

    /**
     * Checks if a file is an audio file.
     *
     * @param mimeType The MIME type of the file
     * @return true if the file is an audio file
     */
    public static boolean isAudio(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().startsWith("audio/");
    }

    /**
     * Gets the MIME type for a file name.
     *
     * @param fileName The file name
     * @return The MIME type, or "application/octet-stream" if unknown
     */
    public static String getMimeType(String fileName) {
        if (fileName == null) return "application/octet-stream";
        
        String ext = getExtension(fileName);
        if (ext == null) return "application/octet-stream";
        
        String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
        
        // Handle some common types that MimeTypeMap might miss
        if (mimeType == null) {
            switch (ext.toLowerCase()) {
                case "md":
                case "markdown":
                    return "text/markdown";
                case "json":
                    return "application/json";
                case "kt":
                    return "text/x-kotlin";
                case "yaml":
                case "yml":
                    return "text/yaml";
                default:
                    return "application/octet-stream";
            }
        }
        
        return mimeType;
    }

    /**
     * Gets the file extension from a file name.
     *
     * @param fileName The file name
     * @return The extension without the dot, or null if no extension
     */
    public static String getExtension(String fileName) {
        if (fileName == null) return null;
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= fileName.length() - 1) {
            return null;
        }
        return fileName.substring(lastDot + 1);
    }

    /**
     * Creates an intent to view a file with the default system app.
     *
     * @param context  The context
     * @param file     The file to view
     * @param mimeType The MIME type of the file
     * @return An intent to view the file, or null if unable to create
     */
    public static Intent getViewIntent(Context context, File file, String mimeType) {
        if (context == null || file == null || !file.exists()) {
            return null;
        }
        
        try {
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file
            );
            
            return getViewIntent(context, uri, mimeType);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates an intent to view a URI with the default system app.
     *
     * @param context  The context
     * @param uri      The URI to view
     * @param mimeType The MIME type of the content
     * @return An intent to view the content
     */
    public static Intent getViewIntent(Context context, Uri uri, String mimeType) {
        if (uri == null) return null;
        
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        
        return intent;
    }

    /**
     * Creates an intent to share a file.
     *
     * @param context  The context
     * @param file     The file to share
     * @param mimeType The MIME type of the file
     * @return An intent to share the file, or null if unable to create
     */
    public static Intent getShareIntent(Context context, File file, String mimeType) {
        if (context == null || file == null || !file.exists()) {
            return null;
        }
        
        try {
            Uri uri = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    file
            );
            
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mimeType != null ? mimeType : "*/*");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            return Intent.createChooser(intent, "Share file");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets a human-readable file type description.
     *
     * @param mimeType The MIME type
     * @return A human-readable description
     */
    public static String getTypeDescription(String mimeType) {
        if (mimeType == null) return "Unknown";
        
        if (isImage(mimeType)) return "Image";
        if (isVideo(mimeType)) return "Video";
        if (isAudio(mimeType)) return "Audio";
        if (isJson(mimeType)) return "JSON";
        if (isEditable(mimeType)) return "Text";
        if (mimeType.contains("pdf")) return "PDF";
        if (mimeType.contains("zip") || mimeType.contains("archive")) return "Archive";
        
        return "File";
    }

    /**
     * Gets the icon resource ID for a file type.
     *
     * @param mimeType The MIME type
     * @return A drawable resource ID (using android.R.drawable)
     */
    public static int getTypeIconResource(String mimeType) {
        if (mimeType == null) return android.R.drawable.ic_menu_save;
        
        if (isImage(mimeType)) return android.R.drawable.ic_menu_gallery;
        if (isVideo(mimeType)) return android.R.drawable.ic_media_play;
        if (isAudio(mimeType)) return android.R.drawable.ic_lock_silent_mode_off;
        if (isJson(mimeType)) return com.fc.freer.R.drawable.ic_json;
        if (isEditable(mimeType)) return android.R.drawable.ic_menu_edit;
        
        return android.R.drawable.ic_menu_save;
    }

    public static boolean isJson(String mimeType) {
        if (mimeType == null) return false;
        String lower = mimeType.toLowerCase();
        return "application/json".equals(lower) || "text/json".equals(lower);
    }
}
