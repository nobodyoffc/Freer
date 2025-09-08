package com.fc.freer.ui;

import android.content.Context;
import com.fc.freer.R;

/**
 * Types of menu items for different behaviors with localization support
 */
public enum MenuItemType {
    CUSTOM(false, 0),           // Custom menu items handled by the listener
    DELETE(true, R.string.delete),        // Built-in delete functionality
    DETAIL(false, R.string.detail),       // Show detail functionality
    EDIT(false, R.string.edit),           // Edit functionality
    COPY(false, R.string.copy),           // Copy functionality  
    SHARE(false, R.string.share),         // Share functionality
    EXPORT(false, R.string.export),       // Export functionality
    SIGN(false, R.string.sign),           // Sign functionality
    REFRESH(false, R.string.refresh),     // Refresh functionality
    SYSTEM(false, 0);                     // System menu items (add to FID list, etc.)

    private final boolean builtin;
    private final int stringResourceId;
    
    MenuItemType(boolean builtin, int stringResourceId) {
        this.builtin = builtin;
        this.stringResourceId = stringResourceId;
    }
    
    public boolean isBuiltin() { 
        return builtin; 
    }
    
    public int getStringResourceId() {
        return stringResourceId;
    }
    
    /**
     * Get localized text for this menu item type
     * @param context Android context for string resources
     * @param fallback Fallback text if no string resource is available
     * @return Localized string or fallback
     */
    public String getLocalizedText(Context context, String fallback) {
        if (stringResourceId != 0) {
            try {
                return context.getString(stringResourceId);
            } catch (Exception e) {
                // Fallback if resource not found
            }
        }
        return fallback;
    }
    
    /**
     * Get localized text for this menu item type using default English text
     */
    public String getLocalizedText(Context context) {
        return getLocalizedText(context, getDefaultEnglishText());
    }
    
    /**
     * Get default English text for this menu item type
     */
    public String getDefaultEnglishText() {
        switch (this) {
            case DELETE: return "Delete";
            case DETAIL: return "Detail";
            case EDIT: return "Edit";
            case COPY: return "Copy";
            case SHARE: return "Share";
            case EXPORT: return "Export";
            case SIGN: return "Sign";
            case REFRESH: return "Refresh";
            case CUSTOM: return "Custom";
            case SYSTEM: return "System";
            default: return "Unknown";
        }
    }
    
    /**
     * Find menu item type by name (case insensitive)
     */
    public static MenuItemType fromName(String name) {
        if (name == null) return CUSTOM;
        
        for (MenuItemType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return CUSTOM; // Default to CUSTOM instead of null
    }
    
    /**
     * Find menu item type by ID with support for multiple languages
     */
    public static MenuItemType fromId(String id) {
        if (id == null) return CUSTOM;
        
        String lowerCaseId = id.toLowerCase();
        switch (lowerCaseId) {
            // English
            case "delete": case "remove": 
            // Chinese
            case "删除": case "移除":
                return DELETE;
                
            // English
            case "detail": case "details": case "info":
            // Chinese  
            case "详情": case "详细": case "信息":
                return DETAIL;
                
            // English
            case "edit": case "modify":
            // Chinese
            case "编辑": case "修改":
                return EDIT;
                
            // English
            case "copy": case "duplicate":
            // Chinese
            case "复制": case "拷贝":
                return COPY;
                
            // English
            case "share":
            // Chinese
            case "分享": case "共享":
                return SHARE;
                
            // English
            case "export":
            // Chinese
            case "导出": case "输出":
                return EXPORT;
                
            // English
            case "sign":
            // Chinese
            case "签名": case "签署":
                return SIGN;
                
            // English
            case "refresh":
            // Chinese
            case "刷新": case "更新":
                return REFRESH;
                
            default: 
                return CUSTOM;
        }
    }
    
    /**
     * Check if the given text (in any language) matches this menu item type
     */
    public boolean matches(String text) {
        if (text == null) return false;
        
        String lowerText = text.toLowerCase();
        switch (this) {
            case DELETE:
                return lowerText.equals("delete") || lowerText.equals("remove") ||
                       lowerText.equals("删除") || lowerText.equals("移除");
                       
            case DETAIL:
                return lowerText.equals("detail") || lowerText.equals("details") || lowerText.equals("info") ||
                       lowerText.equals("详情") || lowerText.equals("详细") || lowerText.equals("信息");
                       
            case EDIT:
                return lowerText.equals("edit") || lowerText.equals("modify") ||
                       lowerText.equals("编辑") || lowerText.equals("修改");
                       
            case COPY:
                return lowerText.equals("copy") || lowerText.equals("duplicate") ||
                       lowerText.equals("复制") || lowerText.equals("拷贝");
                       
            case SHARE:
                return lowerText.equals("share") ||
                       lowerText.equals("分享") || lowerText.equals("共享");
                       
            case EXPORT:
                return lowerText.equals("export") ||
                       lowerText.equals("导出") || lowerText.equals("输出");
                       
            case SIGN:
                return lowerText.equals("sign") ||
                       lowerText.equals("签名") || lowerText.equals("签署");
                       
            case REFRESH:
                return lowerText.equals("refresh") ||
                       lowerText.equals("刷新") || lowerText.equals("更新");
                       
            default:
                return false;
        }
    }
}