package com.fc.freer.utils;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;

import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class AppCardContainer {
    private static final String TAG = "AppCardContainer";
    private final Context context;
    private final ViewGroup appListContainer;
    private final List<App> appList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnAppListChangedListener onAppListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnAppClickListener onAppClickListener;
    private OnAppRemoveListener onAppRemoveListener;
    private OnEditIconClickListener onEditIconClickListener;
    private OnRateIconClickListener onRateIconClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private OnClearIconClickListener onClearIconClickListener;
    private ActivityResultLauncher<Intent> updateAppLauncher;
    private boolean hideEditButton = false;
    private boolean showClearButton = false;

    public interface OnAppListChangedListener {
        void onAppListChanged(List<App> updatedAppList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, App app);
    }

    public interface OnAppClickListener {
        void onAppClick(App app);
    }

    public interface OnAppRemoveListener {
        void onAppRemove(App app);
    }

    public interface OnEditIconClickListener {
        void onEditIconClick(App app);
    }

    public interface OnRateIconClickListener {
        void onRateIconClick(App app);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(App app);
    }

    public interface OnClearIconClickListener {
        void onClearIconClick(App app);
    }

    public AppCardContainer(Context context, LinearLayout appListContainer, ChooseMode chooseMode) {
        this(context, appListContainer, chooseMode, null);
    }

    public AppCardContainer(Context context, LinearLayout appListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.appListContainer = appListContainer;
        this.appList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnAppListChangedListener(OnAppListChangedListener listener) {
        this.onAppListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnAppClickListener(OnAppClickListener listener) {
        this.onAppClickListener = listener;
    }

    public void setOnAppRemoveListener(OnAppRemoveListener listener) {
        this.onAppRemoveListener = listener;
    }

    public void setOnEditIconClickListener(OnEditIconClickListener listener) {
        this.onEditIconClickListener = listener;
    }

    public void setOnRateIconClickListener(OnRateIconClickListener listener) {
        this.onRateIconClickListener = listener;
    }

    public void setOnOffChainIconClickListener(OnOffChainIconClickListener listener) {
        this.onOffChainIconClickListener = listener;
    }

    public void setOnClearIconClickListener(OnClearIconClickListener listener) {
        this.onClearIconClickListener = listener;
    }

    public void setHideEditButton(boolean hide) {
        this.hideEditButton = hide;
    }

    public void setShowClearButton(boolean show) {
        this.showClearButton = show;
    }

    public void setUpdateAppLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateAppLauncher = launcher;
    }

    private void notifyAppListChanged() {
        if (onAppListChangedListener != null) {
            onAppListChangedListener.onAppListChanged(new ArrayList<>(appList));
        }
    }

    @Nullable
    public Map<String, String> getCidMap(List<App> appList, Context context) {
        List<String> fidList = new ArrayList<>();
        for (App app : appList) {
            fidList.add(app.getOwner());
        }
        Map<String, String> cidMap = null;
        if (!fidList.isEmpty()) {
            CidFidManager cidFidManager = CidFidManager.getInstance(context);
            cidMap = cidFidManager.getCidsByFids(fidList);
        }
        return cidMap;
    }

    public void addAppCard(App app, Map<String, String> cidMap) {
        addAppCardAtPosition(app, appList.size(), cidMap);
    }

    private void showAppActivity(App app) {
        Intent intent = new Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, app.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, App.class.getName());
        context.startActivity(intent);
    }

    public List<App> getSelectedApps() {
        List<App> selectedApps = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedApps.add(appList.get(i));
            }
        }
        return selectedApps;
    }

    public void clearAll() {
        appList.clear();
        appListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<App> getAppList() {
        return appList;
    }

    public void selectAll(boolean selected) {
        if (chooseMode != ChooseMode.CHOOSE_MULTI) {
            return;
        }

        for (CompoundButton checkBox : checkBoxes) {
            checkBox.setChecked(selected);
        }
    }

    public boolean areAllSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return false;
        }

        for (CompoundButton checkBox : checkBoxes) {
            if (!checkBox.isChecked()) {
                return false;
            }
        }
        return true;
    }

    public boolean areNoneSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return true;
        }

        for (CompoundButton checkBox : checkBoxes) {
            if (checkBox.isChecked()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes all currently selected apps from the container
     */
    public void removeSelectedApps() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return;
        }

        List<Integer> selectedIndices = new ArrayList<>();
        for (int i = checkBoxes.size() - 1; i >= 0; i--) {
            if (checkBoxes.get(i).isChecked()) {
                selectedIndices.add(i);
            }
        }

        for (int index : selectedIndices) {
            if (index < appListContainer.getChildCount()) {
                appListContainer.removeViewAt(index);
            }
            if (index < appList.size()) {
                appList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifyAppListChanged();
    }

    /**
     * Removes cards from the beginning of the list
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > appList.size()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            appListContainer.removeViewAt(0);
        }

        for (int i = 0; i < count; i++) {
            appList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }

        notifyAppListChanged();
    }

    /**
     * Removes cards from the end of the list
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > appList.size()) {
            return;
        }

        int size = appList.size();
        for (int i = 0; i < count; i++) {
            appListContainer.removeViewAt(appListContainer.getChildCount() - 1);
        }

        for (int i = 0; i < count; i++) {
            appList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }

        notifyAppListChanged();
    }

    /**
     * Adds app cards to the beginning of the list
     */
    public void addAppCardsToBeginning(List<App> appsToAdd) {
        if (appsToAdd == null || appsToAdd.isEmpty()) {
            return;
        }
        Map<String, String> cidMap = getCidMap(appsToAdd, context);
        for (int i = appsToAdd.size() - 1; i >= 0; i--) {
            App app = appsToAdd.get(i);
            addAppCardAtPosition(app, 0, cidMap);
        }
    }

    public void addAppCardsToBeginningWithBottomRemoval(List<App> appsToAdd, int maxSize) {
        if (appsToAdd == null || appsToAdd.isEmpty()) {
            return;
        }

        addAppCardsToBeginning(appsToAdd);

        int currentSize = appList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromEnd(removeCount);
        } else {
            notifyAppListChanged();
        }
    }

    public void addAppCardsToEndWithTopRemoval(List<App> appsToAdd, int maxSize) {
        if (appsToAdd == null || appsToAdd.isEmpty()) {
            return;
        }

        Map<String, String> cidMap = getCidMap(appsToAdd, context);
        for (App app : appsToAdd) {
            addAppCardAtPosition(app, appList.size(), cidMap);
        }

        int currentSize = appList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromBeginning(removeCount);
        } else {
            notifyAppListChanged();
        }
    }

    public void sortByStdName(boolean ascending, boolean enableSort) {
        sortApps(enableSort, (pair1, pair2) -> {
            String name1 = pair1.app.getStdName();
            String name2 = pair2.app.getStdName();
            return compareNullable(name1, name2, ascending);
        });
    }

    public void sortByOwner(boolean ascending, boolean enableSort) {
        sortApps(enableSort, (pair1, pair2) -> {
            String owner1 = pair1.app.getOwner();
            String owner2 = pair2.app.getOwner();
            return compareNullable(owner1, owner2, ascending);
        });
    }

    public void sortByLastTime(boolean ascending, boolean enableSort) {
        sortApps(enableSort, (pair1, pair2) -> {
            Long lastTime1 = pair1.app.getLastTime();
            Long lastTime2 = pair2.app.getLastTime();
            return compareNullable(lastTime1, lastTime2, ascending);
        });
    }

    public void sortByTCdd(boolean ascending, boolean enableSort) {
        sortApps(enableSort, (pair1, pair2) -> {
            Long tCdd1 = pair1.app.gettCdd();
            Long tCdd2 = pair2.app.gettCdd();
            return compareNullable(tCdd1, tCdd2, ascending);
        });
    }

    public void sortByTRate(boolean ascending, boolean enableSort) {
        sortApps(enableSort, (pair1, pair2) -> {
            Float tRate1 = pair1.app.gettRate();
            Float tRate2 = pair2.app.gettRate();
            return compareNullable(tRate1, tRate2, ascending);
        });
    }

    private void addAppCardAtPosition(App app, int position, Map<String, String> cidMap) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, app, cidMap);

        appListContainer.addView(cardView, position);
        appList.add(position, app);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        return LayoutInflater.from(context).inflate(R.layout.item_app_card, appListContainer, false);
    }

    private CompoundButton setupCardViewInteractions(View cardView, App app, Map<String, String> cidMap) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, app, cidMap);
        setupClickListeners(cardView, app);
        setupButtons(cardView, app);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.app_checkbox);
        checkBox.setVisibility(VISIBLE);
        if (chooseMode == ChooseMode.CHOOSE_ONE) {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    for (CompoundButton cb : checkBoxes) {
                        if (cb != checkBox) {
                            cb.setChecked(false);
                        }
                    }
                }
            });
        } else {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyAppListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, App app, Map<String, String> cidMap) {
        ImageView ownerAvatarView = cardView.findViewById(R.id.app_owner_avatar);
        TextView stdNameValue = cardView.findViewById(R.id.app_std_name_value);
        TextView ownerValue = cardView.findViewById(R.id.app_owner_value);
        TextView typesValue = cardView.findViewById(R.id.app_types_value);
        TextView tCddValue = cardView.findViewById(R.id.app_tcdd_value);
        TextView tRateValue = cardView.findViewById(R.id.app_trate_value);

        setupAvatar(ownerAvatarView, app.getOwner());
        setTextValue(stdNameValue, app.getStdName());

        if (cidMap != null && cidMap.get(app.getOwner()) != null) {
            setTextValue(ownerValue, cidMap.get(app.getOwner()));
        } else {
            setTextValue(ownerValue, app.getOwner());
        }

        // Format types array
        if (app.getTypes() != null && !app.getTypes().isEmpty()) {
            setTextValue(typesValue, String.join(", ", app.getTypes()));
        } else {
            setTextValue(typesValue, "");
        }

        // Format tCdd
        if (app.gettCdd() != null) {
            setTextValue(tCddValue, formatNumber(app.gettCdd()));
        } else {
            setTextValue(tCddValue, "0");
        }

        // Format tRate
        if (app.gettRate() != null) {
            setTextValue(tRateValue, String.format("%.1f", app.gettRate()));
        } else {
            setTextValue(tRateValue, "0.0");
        }
    }

    private String formatNumber(Long number) {
        if (number == null) return "0";
        if (number >= 1_000_000_000) {
            return String.format("%.1fB", number / 1_000_000_000.0);
        } else if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        }
        return String.valueOf(number);
    }

    private void setupAvatar(ImageView avatarView, String fid) {
        if (fid != null && !fid.isEmpty()) {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(context);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
                if (avatarBitmap != null) {
                    avatarView.setImageBitmap(avatarBitmap);
                } else {
                    avatarView.setImageResource(R.drawable.container_outline);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to load avatar for FID %s: %s", fid, e.getMessage());
                avatarView.setImageResource(R.drawable.container_outline);
            }
        } else {
            avatarView.setImageResource(R.drawable.container_outline);
        }
    }

    private void setTextValue(TextView textView, String value) {
        textView.setText(value != null ? value : "");
    }

    private void setupClickListeners(View cardView, App app) {
        TextView stdNameValue = cardView.findViewById(R.id.app_std_name_value);
        TextView ownerValue = cardView.findViewById(R.id.app_owner_value);

        View.OnClickListener clickListener = v -> {
            if (onAppClickListener != null) {
                onAppClickListener.onAppClick(app);
            } else {
                showAppActivity(app);
            }
        };
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, app);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        if (stdNameValue != null) {
            stdNameValue.setOnClickListener(clickListener);
            stdNameValue.setOnLongClickListener(longPressListener);
        }

        if (ownerValue != null) {
            ownerValue.setOnClickListener(clickListener);
            ownerValue.setOnLongClickListener(longPressListener);
        }
    }

    private View.OnLongClickListener createLongPressListener(View cardView, App app) {
        return v -> {
            if (menuItems != null && !menuItems.isEmpty()) {
                PopupMenu popup = new PopupMenu(context, v);
                for (String menuItem : menuItems) {
                    popup.getMenu().add(menuItem);
                }

                popup.setOnMenuItemClickListener(item -> {
                    if (item.getTitle() == null) return false;
                    String title = item.getTitle().toString();
                    if ("Delete".equals(title)) {
                        removeAppCard(cardView, app);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, app);
                        return true;
                    }
                    return false;
                });

                popup.show();
                return true;
            }
            return false;
        };
    }

    private void removeAppCard(View cardView, App app) {
        appListContainer.removeView(cardView);
        int index = appList.indexOf(app);
        if (index != -1) {
            appList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyAppListChanged();
    }

    /**
     * Removes a specific app from the container by app ID
     */
    public boolean removeAppById(String appId) {
        if (appId == null) return false;

        for (int i = 0; i < appList.size(); i++) {
            App app = appList.get(i);
            if (appId.equals(app.getId())) {
                if (i < appListContainer.getChildCount()) {
                    appListContainer.removeViewAt(i);
                }
                appList.remove(i);
                if (i < checkBoxes.size()) {
                    checkBoxes.remove(i);
                }
                notifyAppListChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * Updates a specific app card in the container
     */
    public boolean updateAppCard(App updatedApp) {
        if (updatedApp == null || updatedApp.getId() == null) return false;

        for (int i = 0; i < appList.size(); i++) {
            App app = appList.get(i);
            if (updatedApp.getId().equals(app.getId())) {
                appList.set(i, updatedApp);

                View cardView = appListContainer.getChildAt(i);
                if (cardView != null) {
                    Map<String, String> cidMap = getCidMap(java.util.Collections.singletonList(updatedApp), context);
                    setupCardData(cardView, updatedApp, cidMap);
                    setupButtons(cardView, updatedApp);
                }

                notifyAppListChanged();
                return true;
            }
        }
        return false;
    }

    private void setupButtons(View cardView, App app) {
        ImageButton chainStatusButton = cardView.findViewById(R.id.app_chain_status_button);
        ImageButton editButton = cardView.findViewById(R.id.app_edit_button);
        ImageButton rateButton = cardView.findViewById(R.id.app_rate_button);

        // Setup chain status button
        if (chainStatusButton != null) {
            Boolean onChain = app.getOnChain();
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            
            // Only show chain status for owner's apps
            if (liveFid != null && liveFid.equals(app.getOwner())) {
                chainStatusButton.setVisibility(VISIBLE);
                
                if (Boolean.TRUE.equals(onChain)) {
                    // onChain = true: Confirmed on-chain - show green cloud, not clickable
                    chainStatusButton.setImageResource(R.drawable.ic_on_chain);
                    chainStatusButton.setOnClickListener(null);
                    chainStatusButton.setClickable(false);
                } else if (onChain == null) {
                    // onChain = null: Carved but pending confirmation - show pending icon, not clickable
                    chainStatusButton.setImageResource(R.drawable.ic_on_chain_unknown);
                    chainStatusButton.setOnClickListener(null);
                    chainStatusButton.setClickable(false);
                } else {
                    // onChain = false: Off-chain (not carved yet) - show off-chain icon, clickable to carve
                    chainStatusButton.setImageResource(R.drawable.ic_off_chain);
                    chainStatusButton.setClickable(true);
                    chainStatusButton.setOnClickListener(v -> {
                        if (onOffChainIconClickListener != null) {
                            onOffChainIconClickListener.onOffChainIconClick(app);
                        }
                    });
                }
            } else {
                chainStatusButton.setVisibility(GONE);
            }
        }

        if (editButton != null) {
            if (hideEditButton) {
                editButton.setVisibility(GONE);
            } else if (showClearButton) {
                // Replace edit button with clear button
                editButton.setVisibility(VISIBLE);
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setContentDescription(context.getString(R.string.remove));
                editButton.setOnClickListener(v -> {
                    if (onClearIconClickListener != null) {
                        onClearIconClickListener.onClearIconClick(app);
                    } else {
                        // Default behavior: remove the card
                        removeAppCard(cardView, app);
                    }
                });
            } else {
                // Normal edit button behavior - only show if owner is the current liveFid
                FidManager fidManager = FidManager.getInstance();
                String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
                if (liveFid != null && liveFid.equals(app.getOwner())) {
                    editButton.setVisibility(VISIBLE);
                    editButton.setImageResource(R.drawable.ic_edit);
                    editButton.setContentDescription(context.getString(R.string.edit));
                    editButton.setOnClickListener(v -> {
                        if (onEditIconClickListener != null) {
                            onEditIconClickListener.onEditIconClick(app);
                        }
                    });
                } else {
                    // Hide edit button if owner is not the current liveFid
                    editButton.setVisibility(GONE);
                }
            }
        }

        if (rateButton != null) {
            // Hide rate button if the app owner is the current liveFid
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (liveFid != null && liveFid.equals(app.getOwner())) {
                rateButton.setVisibility(GONE);
            } else {
                rateButton.setVisibility(VISIBLE);
                rateButton.setOnClickListener(v -> {
                    if (onRateIconClickListener != null) {
                        onRateIconClickListener.onRateIconClick(app);
                    }
                });
            }
        }
    }

    private void sortApps(boolean enableSort, Comparator<AppViewPair> comparator) {
        if (appList.isEmpty() || !enableSort) {
            return;
        }

        List<AppViewPair> pairs = createAppViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<AppViewPair> createAppViewPairs() {
        List<AppViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < appList.size(); i++) {
            App app = appList.get(i);
            View cardView = appListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new AppViewPair(app, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<AppViewPair> pairs) {
        appList.clear();
        checkBoxes.clear();
        appListContainer.removeAllViews();

        for (AppViewPair pair : pairs) {
            appList.add(pair.app);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            appListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private record AppViewPair(App app, View cardView, CompoundButton checkBox) {
    }
}

