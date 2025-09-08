package com.fc.freer.ui;

import android.content.Context;
import com.fc.freer.R;

/**
 * Represents a menu item with display text, identifier, and additional properties
 * This is a general-purpose menu item class that can be used across the application
 */
public class MenuItem {
    private final String id;
    private final String displayText;
    private final MenuItemType type;
    private final int iconResId;
    private final boolean enabled;
    private final boolean visible;
    private final Object tag;
    private final int order;
    
    // Builder pattern for flexible construction
    public static class Builder {
        private final String id;
        private final String displayText;
        private MenuItemType type = MenuItemType.CUSTOM;
        private int iconResId = 0;
        private boolean enabled = true;
        private boolean visible = true;
        private Object tag = null;
        private int order = 0;
        
        public Builder(String id, String displayText) {
            this.id = id;
            this.displayText = displayText;
        }
        
        public Builder type(MenuItemType type) {
            this.type = type;
            return this;
        }
        
        public Builder icon(int iconResId) {
            this.iconResId = iconResId;
            return this;
        }
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder visible(boolean visible) {
            this.visible = visible;
            return this;
        }
        
        public Builder tag(Object tag) {
            this.tag = tag;
            return this;
        }
        
        public Builder order(int order) {
            this.order = order;
            return this;
        }
        
        public MenuItem build() {
            return new MenuItem(id, displayText, type, iconResId, enabled, visible, tag, order);
        }
    }
    
    // Constructors for backward compatibility
    public MenuItem(String id, String displayText) {
        this(id, displayText, MenuItemType.CUSTOM, 0, true, true, null, 0);
    }
    
    public MenuItem(String id, String displayText, MenuItemType type) {
        this(id, displayText, type, 0, true, true, null, 0);
    }
    
    private MenuItem(String id, String displayText, MenuItemType type, int iconResId, 
                    boolean enabled, boolean visible, Object tag, int order) {
        this.id = id;
        this.displayText = displayText;
        this.type = type;
        this.iconResId = iconResId;
        this.enabled = enabled;
        this.visible = visible;
        this.tag = tag;
        this.order = order;
    }
    
    // Getters
    public String getId() { return id; }
    public String getDisplayText() { return displayText; }
    public MenuItemType getType() { return type; }
    public int getIconResId() { return iconResId; }
    public boolean isEnabled() { return enabled; }
    public boolean isVisible() { return visible; }
    public Object getTag() { return tag; }
    public int getOrder() { return order; }
    
    // Utility methods
    public boolean hasIcon() { return iconResId != 0; }
    
    @Override
    public String toString() {
        return "MenuItem{id='" + id + "', displayText='" + displayText + "', type=" + type + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MenuItem menuItem = (MenuItem) o;
        return id.equals(menuItem.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
    
    // Static factory methods for common menu items
    public static MenuItem createDeleteMenuItem(Context context) {
        return new MenuItem.Builder("delete", context.getString(R.string.delete))
                .type(MenuItemType.DELETE)
                .build();
    }
    
    public static MenuItem createDetailMenuItem(Context context) {
        return new MenuItem.Builder("detail", context.getString(R.string.detail))
                .type(MenuItemType.DETAIL)
                .build();
    }
    
    public static MenuItem createEditMenuItem(Context context) {
        return new MenuItem.Builder("edit", context.getString(R.string.edit))
                .type(MenuItemType.EDIT)
                .build();
    }
    
    public static MenuItem createCopyMenuItem(Context context) {
        return new MenuItem.Builder("copy", context.getString(R.string.copy))
                .type(MenuItemType.COPY)
                .build();
    }
    
    public static MenuItem createSignMenuItem(Context context) {
        return new MenuItem.Builder("sign", context.getString(R.string.sign))
                .type(MenuItemType.SIGN)
                .build();
    }
    
    /**
     * Create menu item from string with automatic type detection and localization
     */
    public static MenuItem createMenuItem(Context context, String name) {
        String id = name.toLowerCase().replace(" ", "_");
        MenuItemType type = MenuItemType.fromId(id);
        
        // Try to get localized string, fallback to original name
        String localizedText = type.getLocalizedText(context, name);
        
        return new MenuItem.Builder(id, localizedText)
                .type(type)
                .build();
    }
    
    /**
     * Create menu item with icon
     */
    public static MenuItem createMenuItemWithIcon(Context context, String id, String displayText, int iconResId) {
        return new MenuItem.Builder(id, displayText)
                .type(MenuItemType.fromId(id))
                .icon(iconResId)
                .build();
    }
    
    /**
     * Create system menu items (Add to FID list, Clear FID list)
     */
    public static MenuItem createAddToFidListMenuItem(Context context) {
        return new MenuItem.Builder("add_to_fid_list", context.getString(R.string.add_to_fid_list))
                .type(MenuItemType.SYSTEM)
                .order(1000)
                .build();
    }
    
    public static MenuItem createClearFidListMenuItem(Context context) {
        return new MenuItem.Builder("clear_fid_list", context.getString(R.string.clear_fid_list))
                .type(MenuItemType.SYSTEM)
                .order(1001)
                .build();
    }
}