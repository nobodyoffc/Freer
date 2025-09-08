package com.fc.freer.utils;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.freer.manager.AvatarManager;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.ui.MenuItem;
import com.fc.freer.ui.MenuItemType;

import java.util.ArrayList;
import java.util.List;

public class KeyCardManager {
    private static final String TAG = "KeyCardManager";
    private final Context context;
    private final ViewGroup keyListContainer;
    private final List<KeyInfo> keyInfoList;
    private final List<CompoundButton> checkBoxes;
    private final Boolean isSingleChoice;
    private final Boolean clickToReturn;
    private OnKeyListChangedListener onKeyListChangedListener;
    private OnKeyClickedListener onKeyClickedListener;
    private List<MenuItem> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private boolean showDefaultMenuItems = true;
    

    public interface OnKeyListChangedListener {
        void onKeyListChanged(List<KeyInfo> updatedKeyInfoList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItemId, KeyInfo keyInfo);
    }

    public interface OnKeyClickedListener {
        void onKeyClicked(KeyInfo keyInfo);
    }

    public KeyCardManager(Context context, LinearLayout keyListContainer) {
        this(context, keyListContainer, null, false, (List<MenuItem>) null);
    }

    public KeyCardManager(Context context, LinearLayout keyListContainer, Boolean isSingleChoice) {
        this(context, keyListContainer, isSingleChoice, false, (List<MenuItem>) null);
    }

    public KeyCardManager(Context context, LinearLayout keyListContainer, Boolean isSingleChoice, List<MenuItem> menuItems) {
        this(context, keyListContainer, isSingleChoice, false, menuItems);
    }

    public KeyCardManager(Context context, LinearLayout keyListContainer, Boolean isSingleChoice, Boolean clickToReturn, List<MenuItem> menuItems) {
        this.context = context;
        this.keyListContainer = keyListContainer;
        this.keyInfoList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.isSingleChoice = clickToReturn ? null : isSingleChoice;
        this.clickToReturn = clickToReturn;
        this.menuItems = menuItems;
    }

    public void setOnKeyListChangedListener(OnKeyListChangedListener listener) {
        this.onKeyListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnKeyClickedListener(OnKeyClickedListener listener) {
        this.onKeyClickedListener = listener;
    }

    public void setMenuItems(List<MenuItem> menuItems) {
        this.menuItems = menuItems;
    }
    
    /**
     * Set whether to show default system menu items (Add to FID list, Clear FID list)
     */
    public void setShowDefaultMenuItems(boolean showDefaultMenuItems) {
        this.showDefaultMenuItems = showDefaultMenuItems;
    }

    /**
     * Delegate methods to MenuItem static factory methods for backward compatibility
     */
    public static MenuItem createDeleteMenuItem(Context context) {
        return MenuItem.createDeleteMenuItem(context);
    }
    
    public static MenuItem createDetailMenuItem(Context context) {
        return MenuItem.createDetailMenuItem(context);
    }
    
    public static MenuItem createEditMenuItem(Context context) {
        return MenuItem.createEditMenuItem(context);
    }
    
    public static MenuItem createCopyMenuItem(Context context) {
        return MenuItem.createCopyMenuItem(context);
    }

    public static MenuItem createSignMenuItem(Context context) {
        return MenuItem.createSignMenuItem(context);
    }
    
    public static MenuItem createMenuItem(Context context, String name) {
        return MenuItem.createMenuItem(context, name);
    }
    
    public static MenuItem createMenuItemWithIcon(Context context, String id, String displayText, int iconResId) {
        return MenuItem.createMenuItemWithIcon(context, id, displayText, iconResId);
    }
    
    /**
     * Add multiple menu items at once
     */
    public void addMenuItems(MenuItem... items) {
        if (menuItems == null) {
            menuItems = new ArrayList<>();
        }
        for (MenuItem item : items) {
            menuItems.add(item);
        }
    }
    
    /**
     * Remove menu item by ID
     */
    public boolean removeMenuItem(String id) {
        if (menuItems == null) return false;
        return menuItems.removeIf(item -> item.getId().equals(id));
    }
    
    /**
     * Find menu item by ID
     */
    public MenuItem findMenuItem(String id) {
        if (menuItems == null) return null;
        return menuItems.stream()
                .filter(item -> item.getId().equals(id))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Update menu item enabled state
     */
    public void setMenuItemEnabled(String id, boolean enabled) {
        MenuItem item = findMenuItem(id);
        if (item != null) {
            // Create updated item since MenuItem is immutable
            MenuItem updatedItem = new MenuItem.Builder(item.getId(), item.getDisplayText())
                    .type(item.getType())
                    .icon(item.getIconResId())
                    .enabled(enabled)
                    .visible(item.isVisible())
                    .tag(item.getTag())
                    .order(item.getOrder())
                    .build();
            
            // Replace the item
            int index = menuItems.indexOf(item);
            if (index >= 0) {
                menuItems.set(index, updatedItem);
            }
        }
    }
    
    /**
     * Helper method to create menu items from string array (for backward compatibility)
     */
    public static List<MenuItem> createMenuItemsFromStrings(String... items) {
        List<MenuItem> menuItems = new ArrayList<>();
        for (String item : items) {
            if ("Delete".equals(item)) {
                // Handle the common case of hardcoded "Delete" string
                menuItems.add(new MenuItem("delete", item, MenuItemType.DELETE));
            } else {
                menuItems.add(new MenuItem(item.toLowerCase().replace(" ", "_"), item));
            }
        }
        return menuItems;
    }
    
    /**
     * Static factory method for backward compatibility with String-based menu items
     */
    public static KeyCardManager createWithStringMenuItems(Context context, LinearLayout keyListContainer, Boolean isSingleChoice, Boolean clickToReturn, List<String> menuItemStrings) {
        List<MenuItem> menuItems = menuItemStrings != null ? createMenuItemsFromStrings(menuItemStrings.toArray(new String[0])) : null;
        return new KeyCardManager(context, keyListContainer, isSingleChoice, clickToReturn, menuItems);
    }
    
    /**
     * Static factory method for creating KeyCardManager with string-based menu items (simplified)
     */
    public static KeyCardManager createWithStringMenuItems(Context context, LinearLayout keyListContainer, Boolean isSingleChoice, String... menuItemStrings) {
        List<MenuItem> menuItems = menuItemStrings != null ? createMenuItemsFromStrings(menuItemStrings) : null;
        return new KeyCardManager(context, keyListContainer, isSingleChoice, false, menuItems);
    }

    private void notifyKeyListChanged() {
        if (onKeyListChangedListener != null) {
            onKeyListChangedListener.onKeyListChanged(new ArrayList<>(keyInfoList));
        }
    }

    public void addKeyCard(KeyInfo keyInfo) {
        int layoutResId;
        if (isSingleChoice == null) {
            layoutResId = R.layout.item_key_card;
        } else if (isSingleChoice) {
            layoutResId = R.layout.item_key_card_radio;
        } else {
            layoutResId = R.layout.item_key_card_checkbox;
        }
        
        View cardView = LayoutInflater.from(context).inflate(layoutResId, keyListContainer, false);
        
        // Set the key card background with outline and press effect
        cardView.setBackgroundResource(R.drawable.key_card_background);
        
        // Add 4dp margin between cards
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (8 * context.getResources().getDisplayMetrics().density);
        layoutParams.setMargins(0, margin / 2, 0, margin / 2);
        cardView.setLayoutParams(layoutParams);
        
        CompoundButton checkBox;
        if (isSingleChoice != null) {
            checkBox = cardView.findViewById(R.id.key_checkbox);
            checkBoxes.add(checkBox);
            if (isSingleChoice) {
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        // Uncheck all other checkboxes
                        for (CompoundButton cb : checkBoxes) {
                            if (cb != checkBox) {
                                cb.setChecked(false);
                            }
                        }
                    }
                });
            } else {
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    notifyKeyListChanged();
                });
            }
        } else {
            checkBox = null;
        }

        ImageView avatar = cardView.findViewById(R.id.key_avatar);
        TextView keyLabel = cardView.findViewById(R.id.key_label);
        TextView keyCid = cardView.findViewById(R.id.key_cid);
        TextView keyId = cardView.findViewById(R.id.key_id);
        LinearLayout cidLabelLayout = cardView.findViewById(R.id.cid_label_layout);

        // Set up click listeners for the card view
        cardView.setOnClickListener(v -> {
            if (clickToReturn) {
                if (onKeyClickedListener != null) {
                    onKeyClickedListener.onKeyClicked(keyInfo);
                }
            } else if (isSingleChoice != null && v.getId() != R.id.key_checkbox) {
                checkBox.setChecked(!checkBox.isChecked());
            } else {
                showKeyDetail(keyInfo);
            }
        });

        try {
            AvatarManager avatarManager = AvatarManager.getInstance(context);
            android.graphics.Bitmap avatarBitmap = avatarManager.getAvatarBitmap(keyInfo.getId());
            if (avatarBitmap != null) {
                avatar.setImageBitmap(avatarBitmap);
            } else {
                TimberLogger.w(TAG, "Failed to get avatar for key ID: %s", keyInfo.getId());
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get avatar for key ID %s: %s", keyInfo.getId(), e.getMessage());
            Toast.makeText(context, R.string.failed_to_create_avatar, Toast.LENGTH_SHORT).show();
        }
        // Handle CID visibility - only show if CID exists and is not empty
        String cid = keyInfo.getCid();
        if (keyCid != null) {
            if (cid != null && !cid.isEmpty()) {
                keyCid.setVisibility(View.VISIBLE);
                keyCid.setText(cid);
            } else {
                keyCid.setVisibility(View.GONE);
            }
        }

        // Handle label visibility
        String label = keyInfo.getLabel();
        if (label != null && !label.isEmpty()) {
            keyLabel.setVisibility(View.VISIBLE);
            keyLabel.setText(label);
        } else {
            keyLabel.setVisibility(View.GONE);
        }

        // Hide entire CID/label layout if both are empty
        if (cidLabelLayout != null) {
            boolean hasCid = (cid != null && !cid.isEmpty());
            boolean hasLabel = (label != null && !label.isEmpty());
            if (!hasCid && !hasLabel) {
                cidLabelLayout.setVisibility(View.GONE);
            } else {
                cidLabelLayout.setVisibility(View.VISIBLE);
            }
        }

        keyId.setText(keyInfo.getId());

        // Add long press listeners for text views
        View.OnLongClickListener longPressListener = v -> {
            if (menuItems != null && !menuItems.isEmpty() || showDefaultMenuItems) {
                PopupMenu popup = new PopupMenu(context, v);
                
                // Collect and sort all menu items
                List<MenuItem> allMenuItems = new ArrayList<>();
                
                // Add custom menu items first (only visible ones)
                if (menuItems != null) {
                    for (MenuItem menuItem : menuItems) {
                        if (menuItem.isVisible()) {
                            allMenuItems.add(menuItem);
                        }
                    }
                }
                
                // Add default system menu items if enabled
                if (showDefaultMenuItems) {
                    allMenuItems.add(MenuItem.createAddToFidListMenuItem(context));
                    allMenuItems.add(MenuItem.createClearFidListMenuItem(context));
                }
                
                // Sort by order, then by display text
                allMenuItems.sort((a, b) -> {
                    int orderCompare = Integer.compare(a.getOrder(), b.getOrder());
                    return orderCompare != 0 ? orderCompare : a.getDisplayText().compareTo(b.getDisplayText());
                });
                
                // Add menu items to popup
                for (MenuItem menuItem : allMenuItems) {
                    android.view.MenuItem popupMenuItem = popup.getMenu().add(menuItem.getDisplayText());
                    popupMenuItem.setEnabled(menuItem.isEnabled());
                    if (menuItem.hasIcon()) {
                        popupMenuItem.setIcon(menuItem.getIconResId());
                    }
                }
                
                popup.setOnMenuItemClickListener(item -> {
                    String title = item.getTitle().toString();
                    
                    // Find the corresponding MenuItem
                    MenuItem selectedMenuItem = null;
                    for (MenuItem menuItem : allMenuItems) {
                        if (menuItem.getDisplayText().equals(title)) {
                            selectedMenuItem = menuItem;
                            break;
                        }
                    }
                    
                    if (selectedMenuItem == null) {
                        return false;
                    }
                    
                    // Handle built-in menu items first
                    if (selectedMenuItem.getType().isBuiltin()) {
                        return handleBuiltinMenuItem(selectedMenuItem, keyInfo, cardView);
                    }
                    // Handle system menu items
                    else if (selectedMenuItem.getType() == MenuItemType.SYSTEM) {
                        return handleSystemMenuItem(selectedMenuItem, keyInfo);
                    }
                    // Handle custom menu items
                    else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(selectedMenuItem.getId(), keyInfo);
                        return true;
                    }
                    
                    return false;
                });
                
                popup.show();
                return true;
            }
            return false;
        };

        keyLabel.setOnLongClickListener(longPressListener);
        keyId.setOnLongClickListener(longPressListener);
        cardView.setOnLongClickListener(longPressListener);

        if (!clickToReturn) {
            avatar.setOnClickListener(v -> AvatarManager.showAvatarDialog(context, keyInfo.getId()));
            keyId.setOnClickListener(v -> copyKeyId(keyInfo.getId()));
        } else {
            // When clickToReturn is true, make keyId also trigger the return action
            keyId.setOnClickListener(v -> {
                if (onKeyClickedListener != null) {
                    onKeyClickedListener.onKeyClicked(keyInfo);
                }
            });
        }

        keyListContainer.addView(cardView);
        keyInfoList.add(keyInfo);
    }
    
    /**
     * Handle built-in menu items (like DELETE)
     */
    private boolean handleBuiltinMenuItem(MenuItem menuItem, KeyInfo keyInfo, View cardView) {
        switch (menuItem.getType()) {
            case DELETE:
                // First notify the listener to handle persistent storage deletion
                if (onMenuItemClickListener != null) {
                    onMenuItemClickListener.onMenuItemClick(menuItem.getId(), keyInfo);
                }
                // Then remove the card from the container
                keyListContainer.removeView(cardView);
                // Remove the key info from the list
                int index = keyInfoList.indexOf(keyInfo);
                if (index != -1) {
                    keyInfoList.remove(index);
                    if (index < checkBoxes.size()) {
                        checkBoxes.remove(index);
                    }
                }
                notifyKeyListChanged();
                return true;
            default:
                return false;
        }
    }
    
    /**
     * Handle system menu items
     */
    private boolean handleSystemMenuItem(MenuItem menuItem, KeyInfo keyInfo) {
        switch (menuItem.getId()) {
            case "add_to_fid_list":
                FreerApplication.addFid(keyInfo.getId());
                Toast.makeText(context, context.getString(R.string.added_to_fid_list), Toast.LENGTH_SHORT).show();
                return true;
            case "clear_fid_list":
                FreerApplication.clearFidList();
                Toast.makeText(context, R.string.fid_list_cleared, Toast.LENGTH_SHORT).show();
                return true;
            default:
                return false;
        }
    }

    private void copyKeyId(String keyId) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Key ID", keyId);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void showKeyDetail(KeyInfo keyInfo) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_key_detail, null);
        
        android.widget.FrameLayout fragmentContainer = dialogView.findViewById(R.id.fragment_container);
        com.fc.freer.ui.DetailFragment detailFragment = com.fc.freer.ui.DetailFragment.newInstance(keyInfo, KeyInfo.class);
        ((androidx.appcompat.app.AppCompatActivity) context).getSupportFragmentManager().beginTransaction()
                .replace(fragmentContainer.getId(), detailFragment)
                .commit();
        
        builder.setView(dialogView)
               .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
               .show();
    }

    public List<KeyInfo> getSelectedKeys() {
        List<KeyInfo> selectedKeys = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedKeys.add(keyInfoList.get(i));
            }
        }
        return selectedKeys;
    }

    public void clearAll() {
        keyInfoList.clear();
        keyListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<KeyInfo> getKeyInfoList() {
        return keyInfoList;
    }
    
    public void selectAll() {
        if (isSingleChoice != null && !isSingleChoice) {
            for (CompoundButton checkBox : checkBoxes) {
                checkBox.setChecked(true);
            }
        }
    }
    
    public void unselectAll() {
        if (isSingleChoice != null) {
            for (CompoundButton checkBox : checkBoxes) {
                checkBox.setChecked(false);
            }
        }
    }
} 