package com.fc.freer.utils;

import static android.view.View.VISIBLE;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.PopupMenu;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.ui.DetailActivity;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class CashCardContainer {
    private static final String TAG = "CashCardContainer";
    private final Context context;
    private final ViewGroup cashListContainer;
    private final List<Cash> cashList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private final boolean showDeleteButton;
    private OnCashListChangedListener onCashListChangedListener;
    private List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnCashViewedListener onCashViewedListener;
    private android.widget.ScrollView scrollView;

    public interface OnCashListChangedListener {
        void onCashListChanged(List<Cash> updatedCashList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Cash cash);
    }

    public interface OnCashViewedListener {
        void onCashViewed(Cash cash);
    }

    public CashCardContainer(Context context, LinearLayout cashListContainer) {
        this(context, cashListContainer, ChooseMode.CHOOSE_ONE_RETURN, true);
    }

    public CashCardContainer(Context context, LinearLayout cashListContainer, ChooseMode chooseMode) {
        this(context, cashListContainer, chooseMode, true);
    }

    public CashCardContainer(Context context, LinearLayout cashListContainer, ChooseMode chooseMode, boolean showDeleteButton) {
        this(context, cashListContainer, chooseMode, showDeleteButton, null);
    }

    public CashCardContainer(Context context, LinearLayout cashListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this(context, cashListContainer, chooseMode, true, menuItems);
    }

    public CashCardContainer(Context context, LinearLayout cashListContainer, ChooseMode chooseMode, boolean showDeleteButton, List<String> menuItems) {
        this.context = context;
        this.cashListContainer = cashListContainer;
        this.cashList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.showDeleteButton = showDeleteButton;
        this.menuItems = menuItems;
    }

    public void setOnCashListChangedListener(OnCashListChangedListener listener) {
        this.onCashListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnCashViewedListener(OnCashViewedListener listener) {
        this.onCashViewedListener = listener;
    }

    public void setMenuItems(List<String> menuItems) {
        this.menuItems = menuItems;
    }

    public void setScrollView(android.widget.ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    private void notifyCashListChanged() {
        if (onCashListChangedListener != null) {
            onCashListChangedListener.onCashListChanged(new ArrayList<>(cashList));
        }
    }

    public void addCashCard(Cash cash) {
        int layoutResId = R.layout.item_cash_card;

        View cardView = LayoutInflater.from(context).inflate(layoutResId, cashListContainer, false);

        // Check if cash is conflicted
        boolean isConflicted = Boolean.TRUE.equals(cash.getConflicted());

        switch (chooseMode) {
            case CHOOSE_ONE_RETURN:
                break;
            case CHOOSE_ONE:
                RadioButton radio = cardView.findViewById(R.id.cash_radio);
                radio.setVisibility(VISIBLE);
                // Disable radio button if cash is conflicted
                radio.setEnabled(!isConflicted);
                radio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        // Uncheck all other checkboxes
                        for (CompoundButton cb : checkBoxes) {
                            if (cb != radio) {
                                cb.setChecked(false);
                            }
                        }
                    }
                });
                break;
            case CHOOSE_MULTI:
                CompoundButton checkBox;
                checkBox = cardView.findViewById(R.id.cash_checkbox);
                checkBox.setVisibility(VISIBLE);
                // Disable checkbox if cash is conflicted
                checkBox.setEnabled(!isConflicted);
                checkBoxes.add(checkBox);
                break;
            default:
                break;
        }

        TextView amountValue = cardView.findViewById(R.id.cash_amount_value);
        TextView cdValue = cardView.findViewById(R.id.cash_cd_value);
        TextView idBrief = cardView.findViewById(R.id.cash_id_brief);
        TextView lastTime = cardView.findViewById(R.id.cash_last_time);
        View newIndicator = cardView.findViewById(R.id.new_indicator);
        ImageView offChainIndicator = cardView.findViewById(R.id.unconfirmed_indicator);
        ImageView conflictedIndicator = cardView.findViewById(R.id.conflicted_indicator);
        LinearLayout lockTimeContainer = cardView.findViewById(R.id.lockTimeContainer);
        ImageView lockTimeIcon = cardView.findViewById(R.id.lockTimeIcon);
        TextView lockTimeDaysText = cardView.findViewById(R.id.lockTimeDaysText);

        // Show new indicator if cash is new, then mark as viewed
        if (newIndicator != null && Boolean.TRUE.equals(cash.getNew())) {
            newIndicator.setVisibility(VISIBLE);
            cash.setNew(false);
            if (onCashViewedListener != null) {
                onCashViewedListener.onCashViewed(cash);
            }
        }

        // Show off-chain indicator if birthTime is null
        if (offChainIndicator != null && cash.getBirthTime() == null) {
            offChainIndicator.setVisibility(VISIBLE);
        }

        // Show conflicted indicator if cash is conflicted
        if (conflictedIndicator != null && isConflicted) {
            conflictedIndicator.setVisibility(VISIBLE);
            // Dim the card view to indicate it's not usable
            cardView.setAlpha(0.5f);
            // Add tooltip to show conflicted message
            conflictedIndicator.setTooltipText(context.getString(R.string.conflicted));
            // Trigger tooltip on click
            conflictedIndicator.setOnClickListener(v -> v.performLongClick());
        }

        // Set up click listeners for the card view
        cardView.setOnClickListener(v -> showCashDetailActivity(cash));

        // Set amount value (no label)
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(cash.getValue())) + " F";
        amountValue.setText(amountText);

        // Set CD value (no label)
        Long cd = cash.getCd();
        if (cd != null) {
            cdValue.setText(String.format(Locale.US, "%d cd", cd));
        } else {
            cdValue.setText("0 cd");
        }

        // Set ID brief (first 4 characters of cash.id)
        String cashId = cash.getId();
        if (cashId != null && cashId.length() >= 4) {
            idBrief.setText(cashId);
        } else if (cashId != null) {
            idBrief.setText(cashId);
        } else {
            idBrief.setText("");
        }

        // Set last time
        Long lastTimeValue = cash.getLastTime();
        if (lastTimeValue != null) {
            String formattedTime = DateUtils.longToTime(lastTimeValue * 1000, DateUtils.TO_MINUTE);
            lastTime.setText(formattedTime);
        } else {
            lastTime.setText("");
        }

        // Set lockTime information if present
        setLockTimeInfo(cash, lockTimeContainer, lockTimeIcon, lockTimeDaysText);

        // Add long press listeners for text views
        View.OnLongClickListener longPressListener = v -> {
            if (menuItems != null && !menuItems.isEmpty()) {
                PopupMenu popup = new PopupMenu(context, v);
                for (String menuItem : menuItems) {
                    popup.getMenu().add(menuItem);
                }
                
                popup.setOnMenuItemClickListener(item -> {
                    String title = item.getTitle().toString();
                    if ("Delete".equals(title)) {
                        // Remove the card from the container
                        cashListContainer.removeView(cardView);
                        // Remove the cash from the list
                        int index = cashList.indexOf(cash);
                        if (index != -1) {
                            cashList.remove(index);
                            if (index < checkBoxes.size()) {
                                checkBoxes.remove(index);
                            }
                        }
                        notifyCashListChanged();
                        return true;
                    } else if (context.getString(R.string.add_to_fid_list).equals(title)) {
                        FreerApplication.addFid(cash.getOwner());
                        ToastUtils.makeText(context, context.getString(R.string.added_to_fid_list));
                        return true;
                    } else if (context.getString(R.string.clear_fid_list).equals(title)) {
                        FreerApplication.clearFidList();
                        ToastUtils.makeText(context, R.string.fid_list_cleared);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, cash);
                        return true;
                    }
                    return false;
                });
                
                // Add the new menu items
                popup.getMenu().add(context.getString(R.string.add_to_fid_list));
                popup.getMenu().add(context.getString(R.string.clear_fid_list));
                
                popup.show();
                return true;
            }
            return false;
        };

        // Only set long press listeners for visible elements
        amountValue.setOnLongClickListener(longPressListener);
        cdValue.setOnLongClickListener(longPressListener);
        idBrief.setOnLongClickListener(longPressListener);
        lastTime.setOnLongClickListener(longPressListener);
        cardView.setOnLongClickListener(longPressListener);

        // Set text field click listeners to launch DetailActivity
        amountValue.setOnClickListener(v -> showCashDetailActivity(cash));
        cdValue.setOnClickListener(v -> showCashDetailActivity(cash));
        idBrief.setOnClickListener(v -> showCashDetailActivity(cash));
        lastTime.setOnClickListener(v -> showCashDetailActivity(cash));

        cashListContainer.addView(cardView);
        cashList.add(cash);
    }

    private void copyAmount(Long value) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(value)) + " F";
        ClipData clip = ClipData.newPlainText("Amount", amountText);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(context, R.string.copied);
    }

    private void copyCd(Long cd) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String cdText = cd != null ? String.format(Locale.US, "%d cd", cd) : "0 cd";
        ClipData clip = ClipData.newPlainText("CD", cdText);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(context, R.string.copied);
    }

    private void showCashDetailActivity(Cash cash) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, cash.toJson());
        intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Cash.class.getName());
        context.startActivity(intent);
    }

    public List<Cash> getSelectedCashes() {
        List<Cash> selectedCashes = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedCashes.add(cashList.get(i));
            }
        }
        return selectedCashes;
    }

    public void clearAll() {
        cashList.clear();
        cashListContainer.removeAllViews();
        checkBoxes.clear();
    }

    /**
     * Removes cards from the beginning of the list (newest/latest cards)
     * @param count Number of cards to remove from the beginning
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > cashList.size()) {
            return;
        }

        // Remove views from the beginning
        for (int i = 0; i < count; i++) {
            cashListContainer.removeViewAt(0);
        }

        // Remove from lists
        for (int i = 0; i < count; i++) {
            cashList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }

        notifyCashListChanged();
    }

    /**
     * Removes cards from the end of the list (oldest/earliest cards)
     * @param count Number of cards to remove from the end
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > cashList.size()) {
            return;
        }

        int size = cashList.size();
        // Remove views from the end
        for (int i = 0; i < count; i++) {
            cashListContainer.removeViewAt(cashListContainer.getChildCount() - 1);
        }

        // Remove from lists
        for (int i = 0; i < count; i++) {
            cashList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }

        notifyCashListChanged();
    }

    /**
     * Adds cash cards to the beginning of the list (newest/latest position)
     * @param cashesToAdd List of cash objects to add at the beginning
     */
    public void addCashCardsToBeginning(List<Cash> cashesToAdd) {
        if (cashesToAdd == null || cashesToAdd.isEmpty()) {
            return;
        }

        // Add in reverse order so they appear in correct order at the beginning
        for (int i = cashesToAdd.size() - 1; i >= 0; i--) {
            Cash cash = cashesToAdd.get(i);
            addCashCardAtPosition(cash, 0);
        }
    }

    /**
     * Adds cash cards to the beginning of the list (newest/latest position) while removing
     * the same number from the end to maintain container size. Adjusts scroll position so
     * user stays viewing the same content and can scroll up to see new cards one by one.
     *
     * @param cashesToAdd List of cash objects to add at the beginning
     * @param maxContainerSize Maximum allowed container size
     */
    public void addCashCardsToBeginningWithBottomRemoval(List<Cash> cashesToAdd, int maxContainerSize) {
        if (cashesToAdd == null || cashesToAdd.isEmpty()) {
            return;
        }

        int currentSize = cashList.size();
        int newItemsCount = cashesToAdd.size();

        // Calculate how many items to remove from the bottom
        int itemsToRemove = Math.max(0, currentSize + newItemsCount - maxContainerSize);

        // Clear all old boundary indicators before setting a new one
        clearAllBoundaryIndicators();

        if (itemsToRemove > 0) {
            // Save the current scroll position
            int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            // Remove items from the end (bottom)
            removeFromEnd(itemsToRemove);

            // Add new items to the beginning in reverse order to maintain correct order
            for (int i = cashesToAdd.size() - 1; i >= 0; i--) {
                Cash cash = cashesToAdd.get(i);
                addCashCardAtPosition(cash, 0);
            }

            // Mark the boundary card (last new card added, which is at index newItemsCount - 1)
            setBoundaryIndicator(newItemsCount - 1, true);

            // Calculate the height of newly added cards to adjust scroll position
            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < cashListContainer.getChildCount(); i++) {
                        View cardView = cashListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    // Adjust scroll position to account for the new cards added at the top
                    // This keeps the user viewing the same content they were looking at
                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Added %d cards to beginning, adjusted scroll from %d to %d (added height: %d)",
                        newItemsCount, savedScrollY, newScrollY, addedHeight);
                });
            }

            com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Added %d cards to beginning, removed %d from bottom, container size: %d",
                newItemsCount, itemsToRemove, cashList.size());
        } else {
            // Container not full, save scroll position before adding
            int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            // Add new items to the beginning
            for (int i = cashesToAdd.size() - 1; i >= 0; i--) {
                Cash cash = cashesToAdd.get(i);
                addCashCardAtPosition(cash, 0);
            }

            // Mark the boundary card (last new card added, which is at index newItemsCount - 1)
            setBoundaryIndicator(newItemsCount - 1, true);

            // Adjust scroll position for the newly added cards
            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < cashListContainer.getChildCount(); i++) {
                        View cardView = cashListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Container not full - adjusted scroll from %d to %d", savedScrollY, newScrollY);
                });
            }

            com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Added %d cards to beginning, container size: %d/%d",
                newItemsCount, cashList.size(), maxContainerSize);
        }

        // Notify that the list has changed
        notifyCashListChanged();
    }

    /**
     * Adds cash cards to the end of the list (oldest position) while removing the same number
     * from the beginning to maintain container size. Preserves scroll position so user stays
     * at the same visual location and can scroll down to see new cards one by one.
     *
     * @param cashesToAdd List of cash objects to add at the end
     * @param maxContainerSize Maximum allowed container size
     */
    public void addCashCardsToEndWithTopRemoval(List<Cash> cashesToAdd, int maxContainerSize) {
        if (cashesToAdd == null || cashesToAdd.isEmpty()) {
            return;
        }

        int currentSize = cashList.size();
        int newItemsCount = cashesToAdd.size();

        // Calculate how many items to remove from the top
        int itemsToRemove = Math.max(0, currentSize + newItemsCount - maxContainerSize);

        // The boundary card will be the first new card added at the current end
        int boundaryIndex = currentSize - itemsToRemove;

        // Clear all old boundary indicators before setting a new one
        clearAllBoundaryIndicators();

        if (itemsToRemove > 0) {
            // Save current scroll position
            final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            // Calculate the height of items that will be removed
            int removedHeight = 0;
            for (int i = 0; i < itemsToRemove && i < cashListContainer.getChildCount(); i++) {
                View cardView = cashListContainer.getChildAt(i);
                removedHeight += cardView.getHeight();
            }
            final int finalRemovedHeight = removedHeight;

            // Remove items from the beginning (top)
            removeFromBeginning(itemsToRemove);

            // Adjust scroll position immediately after removing items to prevent jumping
            if (scrollView != null && finalRemovedHeight > 0) {
                int newScrollY = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, newScrollY);
                com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Adjusted scroll after removal: %d -> %d (removed height: %d)",
                    savedScrollY, newScrollY, finalRemovedHeight);
            }

            // Add new items to the end
            for (Cash cash : cashesToAdd) {
                addCashCardAtPosition(cash, cashList.size());
            }

            // Mark the boundary card (first new card, which is now at boundaryIndex)
            setBoundaryIndicator(boundaryIndex, true);

            com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Added %d cards to end, removed %d from top, container size: %d",
                newItemsCount, itemsToRemove, cashList.size());
        } else {
            // Container not full, just add to the end
            for (Cash cash : cashesToAdd) {
                addCashCardAtPosition(cash, cashList.size());
            }

            // Mark the boundary card (first new card at the current end)
            setBoundaryIndicator(boundaryIndex, true);

            com.fc.fc_ajdk.utils.TimberLogger.d(TAG, "Added %d cards to end, container size: %d/%d",
                newItemsCount, cashList.size(), maxContainerSize);
        }

        // Notify that the list has changed
        notifyCashListChanged();
    }

    /**
     * Adds a cash card at a specific position
     * @param cash The cash object to add
     * @param position The position to insert at (0 = beginning)
     */
    private void addCashCardAtPosition(Cash cash, int position) {
        int layoutResId = R.layout.item_cash_card;

        View cardView = LayoutInflater.from(context).inflate(layoutResId, cashListContainer, false);
        CompoundButton checkBox = null;

        switch (chooseMode) {
            case CHOOSE_ONE_RETURN:
                break;
            case CHOOSE_ONE:
                RadioButton radio = cardView.findViewById(R.id.cash_radio);
                radio.setVisibility(VISIBLE);
                // Disable radio button if cash is conflicted
                radio.setEnabled(Boolean.FALSE.equals(cash.getConflicted()));
                radio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        // Uncheck all other checkboxes
                        for (CompoundButton cb : checkBoxes) {
                            if (cb != radio) {
                                cb.setChecked(false);
                            }
                        }
                    }
                });
                break;
            case CHOOSE_MULTI:
                checkBox = cardView.findViewById(R.id.cash_checkbox);
                checkBox.setVisibility(VISIBLE);
                // Disable checkbox if cash is conflicted
                checkBox.setEnabled(Boolean.FALSE.equals(cash.getConflicted()));
                // Note: checkBox is added to checkBoxes at the correct position below
                // (checkBoxes.add(position, checkBox)) to stay aligned with cashList.
                break;
            default:
                break;
        }

        TextView amountValue = cardView.findViewById(R.id.cash_amount_value);
        TextView cdValue = cardView.findViewById(R.id.cash_cd_value);
        TextView idBrief = cardView.findViewById(R.id.cash_id_brief);
        TextView lastTime = cardView.findViewById(R.id.cash_last_time);
        View newIndicator = cardView.findViewById(R.id.new_indicator);
        ImageView offChainIndicator = cardView.findViewById(R.id.unconfirmed_indicator);
        ImageView conflictedIndicator = cardView.findViewById(R.id.conflicted_indicator);
        LinearLayout lockTimeContainer = cardView.findViewById(R.id.lockTimeContainer);
        ImageView lockTimeIcon = cardView.findViewById(R.id.lockTimeIcon);
        TextView lockTimeDaysText = cardView.findViewById(R.id.lockTimeDaysText);

        // Check if cash is conflicted
        boolean isConflicted = Boolean.TRUE.equals(cash.getConflicted());

        // Show new indicator if cash is new, then mark as viewed
        if (newIndicator != null && Boolean.TRUE.equals(cash.getNew())) {
            newIndicator.setVisibility(VISIBLE);
            cash.setNew(false);
            if (onCashViewedListener != null) {
                onCashViewedListener.onCashViewed(cash);
            }
        }

        // Show off-chain indicator if birthTime is null
        if (offChainIndicator != null && cash.getBirthTime() == null) {
            offChainIndicator.setVisibility(VISIBLE);
        }

        // Show conflicted indicator if cash is conflicted
        if (conflictedIndicator != null && isConflicted) {
            conflictedIndicator.setVisibility(VISIBLE);
            // Dim the card view to indicate it's not usable
            cardView.setAlpha(0.5f);
            // Add tooltip to show conflicted message
            conflictedIndicator.setTooltipText(context.getString(R.string.conflicted));
            // Trigger tooltip on click
            conflictedIndicator.setOnClickListener(v -> v.performLongClick());
        }

        // Set up click listeners for the card view
        cardView.setOnClickListener(v -> showCashDetailActivity(cash));

        // Set amount value
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(cash.getValue())) + " F";
        amountValue.setText(amountText);

        // Set CD value
        Long cd = cash.getCd();
        if (cd != null) {
            cdValue.setText(String.format(Locale.US, "%d cd", cd));
        } else {
            cdValue.setText("0 cd");
        }

        // Set ID brief (first 4 characters of cash.id)
        String cashId = cash.getId();
        if (cashId != null && cashId.length() >= 4) {
            idBrief.setText(cashId);
        } else if (cashId != null) {
            idBrief.setText(cashId);
        } else {
            idBrief.setText("");
        }

        // Set last time
        Long lastTimeValue = cash.getLastTime();
        if (lastTimeValue != null) {
            String formattedTime = DateUtils.longToTime(lastTimeValue * 1000, DateUtils.TO_MINUTE);
            lastTime.setText(formattedTime);
        } else {
            lastTime.setText("");
        }

        // Set lockTime information if present
        setLockTimeInfo(cash, lockTimeContainer, lockTimeIcon, lockTimeDaysText);

        // Add long press and click listeners (same as in addCashCard method)
        View.OnLongClickListener longPressListener = v -> {
            if (menuItems != null && !menuItems.isEmpty()) {
                PopupMenu popup = new PopupMenu(context, v);
                for (String menuItem : menuItems) {
                    popup.getMenu().add(menuItem);
                }

                popup.setOnMenuItemClickListener(item -> {
                    String title = item.getTitle().toString();
                    if ("Delete".equals(title)) {
                        // Remove the card from the container
                        cashListContainer.removeView(cardView);
                        // Remove the cash from the list
                        int index = cashList.indexOf(cash);
                        if (index != -1) {
                            cashList.remove(index);
                            if (index < checkBoxes.size()) {
                                checkBoxes.remove(index);
                            }
                        }
                        notifyCashListChanged();
                        return true;
                    } else if (context.getString(R.string.add_to_fid_list).equals(title)) {
                        FreerApplication.addFid(cash.getOwner());
                        ToastUtils.makeText(context, context.getString(R.string.added_to_fid_list));
                        return true;
                    } else if (context.getString(R.string.clear_fid_list).equals(title)) {
                        FreerApplication.clearFidList();
                        ToastUtils.makeText(context, R.string.fid_list_cleared);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, cash);
                        return true;
                    }
                    return false;
                });

                popup.getMenu().add(context.getString(R.string.add_to_fid_list));
                popup.getMenu().add(context.getString(R.string.clear_fid_list));

                popup.show();
                return true;
            }
            return false;
        };

        amountValue.setOnLongClickListener(longPressListener);
        cdValue.setOnLongClickListener(longPressListener);
        idBrief.setOnLongClickListener(longPressListener);
        lastTime.setOnLongClickListener(longPressListener);
        cardView.setOnLongClickListener(longPressListener);

        amountValue.setOnClickListener(v -> showCashDetailActivity(cash));
        cdValue.setOnClickListener(v -> showCashDetailActivity(cash));
        idBrief.setOnClickListener(v -> showCashDetailActivity(cash));
        lastTime.setOnClickListener(v -> showCashDetailActivity(cash));

        // Insert at the specified position
        cashListContainer.addView(cardView, position);
        cashList.add(position, cash);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    public List<Cash> getCashList() {
        return cashList;
    }

    public void selectAll(boolean selected) {
        if (chooseMode != ChooseMode.CHOOSE_MULTI) {
            return; // Do nothing for non-checkbox modes or single choice mode
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
     * Sorts the cash cards by lastTime
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByLastTime(boolean ascending, boolean enableSort) {
        if (cashList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of cash and their corresponding views to maintain association
            List<CashViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < cashList.size(); i++) {
                Cash cash = cashList.get(i);
                View cardView = cashListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new CashViewPair(cash, cardView, checkBox));
            }
            
            // Sort pairs by lastTime
            Collections.sort(pairs, new Comparator<CashViewPair>() {
                @Override
                public int compare(CashViewPair pair1, CashViewPair pair2) {
                    Long time1 = pair1.cash.getLastTime();
                    Long time2 = pair2.cash.getLastTime();
                    
                    // Handle null values - put them at the end
                    if (time1 == null && time2 == null) return 0;
                    if (time1 == null) return 1;
                    if (time2 == null) return -1;
                    
                    return ascending ? time1.compareTo(time2) : time2.compareTo(time1);
                }
            });
            
            // Update the lists and views based on sorted order
            cashList.clear();
            checkBoxes.clear();
            cashListContainer.removeAllViews();
            
            for (CashViewPair pair : pairs) {
                cashList.add(pair.cash);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                cashListContainer.addView(pair.cardView);
            }
        }
        // If enableSort is false, we don't need to do anything special as the original order
        // will be restored when the list is refreshed from the database
    }
    
    /**
     * Sorts the cash cards by CD (Coin Days)
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByCD(boolean ascending, boolean enableSort) {
        if (cashList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of cash and their corresponding views to maintain association
            List<CashViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < cashList.size(); i++) {
                Cash cash = cashList.get(i);
                View cardView = cashListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new CashViewPair(cash, cardView, checkBox));
            }
            
            // Sort pairs by CD
            Collections.sort(pairs, new Comparator<CashViewPair>() {
                @Override
                public int compare(CashViewPair pair1, CashViewPair pair2) {
                    Long cd1 = pair1.cash.getCd();
                    Long cd2 = pair2.cash.getCd();
                    
                    // Handle null values - put them at the end
                    if (cd1 == null && cd2 == null) return 0;
                    if (cd1 == null) return 1;
                    if (cd2 == null) return -1;
                    
                    return ascending ? cd1.compareTo(cd2) : cd2.compareTo(cd1);
                }
            });
            
            // Update the lists and views based on sorted order
            cashList.clear();
            checkBoxes.clear();
            cashListContainer.removeAllViews();
            
            for (CashViewPair pair : pairs) {
                cashList.add(pair.cash);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                cashListContainer.addView(pair.cardView);
            }
        }
        // If enableSort is false, we don't need to do anything special as the original order
        // will be restored when the list is refreshed from the database
    }
    
    /**
     * Sorts the cash cards by value (FCH amount)
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByValue(boolean ascending, boolean enableSort) {
        if (cashList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of cash and their corresponding views to maintain association
            List<CashViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < cashList.size(); i++) {
                Cash cash = cashList.get(i);
                View cardView = cashListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new CashViewPair(cash, cardView, checkBox));
            }
            
            // Sort pairs by value
            Collections.sort(pairs, new Comparator<CashViewPair>() {
                @Override
                public int compare(CashViewPair pair1, CashViewPair pair2) {
                    Long value1 = pair1.cash.getValue();
                    Long value2 = pair2.cash.getValue();
                    
                    // Handle null values - put them at the end
                    if (value1 == null && value2 == null) return 0;
                    if (value1 == null) return 1;
                    if (value2 == null) return -1;
                    
                    return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
                }
            });
            
            // Update the lists and views based on sorted order
            cashList.clear();
            checkBoxes.clear();
            cashListContainer.removeAllViews();
            
            for (CashViewPair pair : pairs) {
                cashList.add(pair.cash);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                cashListContainer.addView(pair.cardView);
            }
        }
        // If enableSort is false, we don't need to do anything special as the original order
        // will be restored when the list is refreshed from the database
    }
    
    /**
     * Sets lockTime information for CLTV outputs
     * @param cash The cash object containing lockTime information
     * @param lockTimeContainer The container layout for lockTime UI elements
     * @param lockTimeIcon The lock icon
     * @param lockTimeDaysText The text view for displaying days/blocks info
     */
    private void setLockTimeInfo(Cash cash, LinearLayout lockTimeContainer, ImageView lockTimeIcon, TextView lockTimeDaysText) {
        Long lockTime = cash.getLockTime();

        if (lockTime == null) {
            lockTimeContainer.setVisibility(View.GONE);
            return;
        }

        lockTimeContainer.setVisibility(VISIBLE);
        lockTimeIcon.setVisibility(VISIBLE);

        // Get best height from FapiClient
        long bestHeight = 0;
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient != null && fapiClient.getBestHeight() != null) {
            bestHeight = fapiClient.getBestHeight();
        }

        // Calculate blocks and days until unlock
        if(bestHeight==0){
            lockTimeDaysText.setText("...");
        }else {
            long blocksToLock = lockTime - bestHeight;
            boolean isUnlocked = blocksToLock <= 0;
            if (blocksToLock < 0)
                blocksToLock = 0;
            long daysToLock = (long) (blocksToLock / (24.0 * 60)); // 1 block ≈ 1 minute

            // Set the days text (primary info)
            String daysInfo = String.format(Locale.US, "%d %s (%d %s) @%d",
                    daysToLock,
                    context.getString(R.string.days),
                    blocksToLock,
                    context.getString(R.string.blocks),
                    lockTime);
            lockTimeDaysText.setText(daysInfo);

            // Set green color and unlock icon for unlocked cash
            if (isUnlocked) {
                lockTimeIcon.setImageResource(R.drawable.ic_unlock);
                lockTimeIcon.setColorFilter(context.getColor(android.R.color.holo_green_dark));
                lockTimeDaysText.setTextColor(context.getColor(android.R.color.holo_green_dark));
            } else {
                lockTimeIcon.setImageResource(R.drawable.ic_lock);
                lockTimeIcon.clearColorFilter();
                lockTimeDaysText.setTextColor(context.getColor(R.color.warning));
            }

            // Make the lockTime container clickable to copy lockTime value
            lockTimeContainer.setClickable(true);
            lockTimeContainer.setFocusable(true);
            lockTimeContainer.setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("lockTime", String.valueOf(lockTime));
                clipboard.setPrimaryClip(clip);
            });
        }
    }

    /**
     * Sets or clears the boundary indicator for a specific card at the given index.
     * The boundary indicator is shown in warning color to mark the boundary between
     * old and new content when loading more cards.
     *
     * @param index The index of the card in the container
     * @param show Whether to show or hide the boundary indicator
     */
    public void setBoundaryIndicator(int index, boolean show) {
        if (index < 0 || index >= cashListContainer.getChildCount()) {
            return;
        }

        View cardView = cashListContainer.getChildAt(index);
        if (cardView == null) {
            return;
        }

        View boundaryIndicator = cardView.findViewById(R.id.new_indicator);
        if (boundaryIndicator != null && index < cashList.size()) {
            Cash cash = cashList.get(index);

            if (show) {
                // Show boundary indicator in warning color
                boundaryIndicator.setVisibility(View.VISIBLE);
                boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.warning, null));
            } else {
                // Clear boundary indicator - restore based on cash.getNew()
                if (Boolean.TRUE.equals(cash.getNew())) {
                    boundaryIndicator.setVisibility(View.VISIBLE);
                    boundaryIndicator.setBackgroundColor(context.getResources().getColor(android.R.color.holo_red_light, null));
                } else {
                    boundaryIndicator.setVisibility(View.GONE);
                }
            }
        }
    }

    /**
     * Clears all boundary indicators from all cards
     */
    public void clearAllBoundaryIndicators() {
        for (int i = 0; i < cashListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
        }
    }

    /**
     * Helper class to maintain association between Cash objects, their views, and checkboxes during sorting
     */
    private static class CashViewPair {
        final Cash cash;
        final View cardView;
        final CompoundButton checkBox;

        CashViewPair(Cash cash, View cardView, CompoundButton checkBox) {
            this.cash = cash;
            this.cardView = cardView;
            this.checkBox = checkBox;
        }
    }
} 