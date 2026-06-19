package com.fc.freer.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.model.Income;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class IncomeCardContainer {
    private static final String TAG = "IncomeCardContainer";
    private final Context context;
    private final ViewGroup incomeListContainer;
    private final List<Income> incomeList;
    private final List<CompoundButton> checkBoxes;
    private final Boolean isSingleChoice;
    private OnIncomeListChangedListener onIncomeListChangedListener;
    private android.widget.ScrollView scrollView;


    public interface OnIncomeListChangedListener {
        void onIncomeListChanged(List<Income> updatedIncomeList);
    }

    public interface OnIncomeViewedListener {
        void onIncomeViewed(Income income);
    }

    public IncomeCardContainer(Context context, LinearLayout incomeListContainer) {
        this(context, incomeListContainer, null);
    }

    public IncomeCardContainer(Context context, LinearLayout incomeListContainer, Boolean isSingleChoice) {
        this.context = context;
        this.incomeListContainer = incomeListContainer;
        this.incomeList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.isSingleChoice = isSingleChoice;
    }

    public void setOnIncomeListChangedListener(OnIncomeListChangedListener listener) {
        this.onIncomeListChangedListener = listener;
    }

    public void setScrollView(android.widget.ScrollView scrollView) {
        this.scrollView = scrollView;
    }


    private void notifyIncomeListChanged() {
        if (onIncomeListChangedListener != null) {
            onIncomeListChangedListener.onIncomeListChanged(new ArrayList<>(incomeList));
        }
    }

    public void addIncomeCard(Income income) {
        int layoutResId = R.layout.item_income_card;

        View cardView = LayoutInflater.from(context).inflate(layoutResId, incomeListContainer, false);
        
        CompoundButton checkBox;
        if (isSingleChoice != null) {
            checkBox = cardView.findViewById(R.id.income_checkbox);
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
                    notifyIncomeListChanged();
                });
            }
        } else {
            checkBox = null;
        }

        TextView amountValue = cardView.findViewById(R.id.income_amount_value);
        TextView fromValue = cardView.findViewById(R.id.income_from_value);
        ImageView fromAvatar = cardView.findViewById(R.id.income_from_avatar);
        TextView timeDate = cardView.findViewById(R.id.income_time_date);
        TextView timeClock = cardView.findViewById(R.id.income_time_clock);
        View newIndicator = cardView.findViewById(R.id.new_indicator);

        // Show new indicator if it is new
        if (newIndicator != null && Boolean.TRUE.equals(income.getNew())) {
            newIndicator.setVisibility(View.VISIBLE);
            income.setNew(Boolean.FALSE);
        }

        // Set up click listeners for the card view
        cardView.setOnClickListener(v -> {
            // Always show detail activity when card is clicked, regardless of selection mode
            showIncomeDetailActivity(income);
        });

        // Set amount value with F suffix
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(income.getValue())) ;
        amountValue.setText(amountText);

        // Set from value (truncate if longer than 13 chars)
        String fromText = income.getFrom();
        fromValue.setText(fromText != null ? fromText : "");

        // Set time values (date and time on separate lines)
        if (income.getTime() != null) {
            Date date = new Date(income.getTime() * 1000L); // Convert from Unix timestamp
            SimpleDateFormat dateFormat = new SimpleDateFormat("yy-MM-dd", Locale.getDefault());
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            
            timeDate.setText(dateFormat.format(date));
            timeClock.setText(timeFormat.format(date));
        } else {
            timeDate.setText("");
            timeClock.setText("");
        }

        // Set up avatar for the from address
        setupFromAvatar(fromAvatar, income.getFrom());

        // Set text field click listeners to launch DetailActivity
        amountValue.setOnClickListener(v -> showIncomeDetailActivity(income));
        fromAvatar.setOnClickListener(v -> showIncomeDetailActivity(income));
        timeDate.setOnClickListener(v -> showIncomeDetailActivity(income));
        timeClock.setOnClickListener(v -> showIncomeDetailActivity(income));
        
        // Set FID text click and long press listeners
        fromValue.setOnClickListener(v -> copyFidToClipboard(income.getFrom()));
        fromValue.setOnLongClickListener(v -> {
            addFidToList(income.getFrom());
            return true;
        });

        incomeListContainer.addView(cardView);
        incomeList.add(income);
    }

    private void setupFromAvatar(ImageView avatarView, String fromFid) {
        try {
            
            // Set initial background while avatar loads
            avatarView.setBackgroundColor(context.getResources().getColor(R.color.accent, context.getTheme()));
            
            if (fromFid == null || fromFid.isEmpty()|| !KeyTools.isGoodFid(fromFid)) {
                return;
            }
            
            // Get avatar manager instance
            AvatarManager avatarManager = AvatarManager.getInstance(context);
            
            // Get avatar bitmap in background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fromFid);
                    
                    // Update UI on main thread
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        if (avatarBitmap != null && !((android.app.Activity) context).isFinishing() && !((android.app.Activity) context).isDestroyed()) {
                            avatarView.setImageBitmap(avatarBitmap);
                            // Clear background color when bitmap is set
                            avatarView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                        } else {
                        }
                    });
                    
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error generating avatar for from FID: %s", e.getMessage());
                }
            }).start();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up from avatar: %s", e.getMessage());
        }
    }

    private void showIncomeDetailActivity(Income income) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, income.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Income.class.getName());
        context.startActivity(intent);
    }

    private void copyFidToClipboard(String fid) {
        if (fid == null || fid.isEmpty()) {
            return;
        }
        
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("FID", fid);
        clipboard.setPrimaryClip(clip);
        
        ToastUtils.makeText(context, context.getString(R.string.fid_copied_to_clipboard));
    }

    private void addFidToList(String fid) {
        if (fid == null || fid.isEmpty()) {
            return;
        }
        
        FreerApplication.addFid(fid);
        ToastUtils.makeText(context, context.getString(R.string.fid_added_to_list));
    }

    public List<Income> getSelectedIncomes() {
        List<Income> selectedIncomes = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedIncomes.add(incomeList.get(i));
            }
        }
        return selectedIncomes;
    }

    public void clearAll() {
        incomeList.clear();
        incomeListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Income> getIncomeList() {
        return incomeList;
    }

    public void selectAll(boolean selected) {
        if (isSingleChoice == null || isSingleChoice) {
            return; // Do nothing for non-checkbox modes or single choice mode
        }

        TimberLogger.d(TAG, "selectAll called with selected=%s, checkBoxes size=%d", selected, checkBoxes.size());

        for (int i = 0; i < checkBoxes.size(); i++) {
            CompoundButton checkBox = checkBoxes.get(i);
            TimberLogger.d(TAG, "Setting checkbox %d to %s", i, selected);
            checkBox.setChecked(selected);
        }
    }

    public boolean areAllSelected() {
        if (isSingleChoice == null || isSingleChoice || checkBoxes.isEmpty()) {
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
        if (isSingleChoice == null || isSingleChoice || checkBoxes.isEmpty()) {
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
     * Sorts the income cards by time
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByTime(boolean ascending, boolean enableSort) {
        if (incomeList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of income and their corresponding views to maintain association
            List<IncomeViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < incomeList.size(); i++) {
                Income income = incomeList.get(i);
                View cardView = incomeListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new IncomeViewPair(income, cardView, checkBox));
            }
            
            // Sort pairs by time
            Collections.sort(pairs, new Comparator<IncomeViewPair>() {
                @Override
                public int compare(IncomeViewPair pair1, IncomeViewPair pair2) {
                    Long time1 = pair1.income.getTime();
                    Long time2 = pair2.income.getTime();
                    
                    // Handle null values - put them at the end
                    if (time1 == null && time2 == null) return 0;
                    if (time1 == null) return 1;
                    if (time2 == null) return -1;
                    
                    return ascending ? time1.compareTo(time2) : time2.compareTo(time1);
                }
            });
            
            // Update the lists and views based on sorted order
            incomeList.clear();
            checkBoxes.clear();
            incomeListContainer.removeAllViews();
            
            for (IncomeViewPair pair : pairs) {
                incomeList.add(pair.income);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                incomeListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Sorts the income cards by from field
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByFrom(boolean ascending, boolean enableSort) {
        if (incomeList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of income and their corresponding views to maintain association
            List<IncomeViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < incomeList.size(); i++) {
                Income income = incomeList.get(i);
                View cardView = incomeListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new IncomeViewPair(income, cardView, checkBox));
            }
            
            // Sort pairs by from field
            Collections.sort(pairs, new Comparator<IncomeViewPair>() {
                @Override
                public int compare(IncomeViewPair pair1, IncomeViewPair pair2) {
                    String from1 = pair1.income.getFrom();
                    String from2 = pair2.income.getFrom();
                    
                    // Handle null values - put them at the end
                    if (from1 == null && from2 == null) return 0;
                    if (from1 == null) return 1;
                    if (from2 == null) return -1;
                    
                    return ascending ? from1.compareTo(from2) : from2.compareTo(from1);
                }
            });
            
            // Update the lists and views based on sorted order
            incomeList.clear();
            checkBoxes.clear();
            incomeListContainer.removeAllViews();
            
            for (IncomeViewPair pair : pairs) {
                incomeList.add(pair.income);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                incomeListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Sorts the income cards by value (FCH amount)
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByValue(boolean ascending, boolean enableSort) {
        if (incomeList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of income and their corresponding views to maintain association
            List<IncomeViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < incomeList.size(); i++) {
                Income income = incomeList.get(i);
                View cardView = incomeListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new IncomeViewPair(income, cardView, checkBox));
            }
            
            // Sort pairs by value
            Collections.sort(pairs, new Comparator<IncomeViewPair>() {
                @Override
                public int compare(IncomeViewPair pair1, IncomeViewPair pair2) {
                    Long value1 = pair1.income.getValue();
                    Long value2 = pair2.income.getValue();
                    
                    // Handle null values - put them at the end
                    if (value1 == null && value2 == null) return 0;
                    if (value1 == null) return 1;
                    if (value2 == null) return -1;
                    
                    return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
                }
            });
            
            // Update the lists and views based on sorted order
            incomeList.clear();
            checkBoxes.clear();
            incomeListContainer.removeAllViews();
            
            for (IncomeViewPair pair : pairs) {
                incomeList.add(pair.income);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                incomeListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Removes cards from the beginning of the list (newest/latest cards)
     * @param count Number of cards to remove from the beginning
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > incomeList.size()) {
            return;
        }

        removeViewsFromBeginning(count);
        removeDataFromBeginning(count);
        notifyIncomeListChanged();
    }

    /**
     * Removes cards from the end of the list (oldest/earliest cards)
     * @param count Number of cards to remove from the end
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > incomeList.size()) {
            return;
        }

        removeViewsFromEnd(count);
        removeDataFromEnd(count);
        notifyIncomeListChanged();
    }

    private void removeViewsFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            incomeListContainer.removeViewAt(0);
        }
    }

    private void removeDataFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            incomeList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
    }

    private void removeViewsFromEnd(int count) {
        for (int i = 0; i < count; i++) {
            incomeListContainer.removeViewAt(incomeListContainer.getChildCount() - 1);
        }
    }

    private void removeDataFromEnd(int count) {
        int size = incomeList.size();
        for (int i = 0; i < count; i++) {
            int index = size - 1 - i;
            incomeList.remove(index);
            if (checkBoxes.size() > index) {
                checkBoxes.remove(index);
            }
        }
    }

    /**
     * Adds an income card at a specific position
     */
    private void addIncomeCardAtPosition(Income income, int position) {
        int layoutResId = R.layout.item_income_card;
        View cardView = LayoutInflater.from(context).inflate(layoutResId, incomeListContainer, false);

        CompoundButton checkBox = setupCheckBoxForCard(cardView);
        setupCardData(cardView, income);
        setupCardClickListeners(cardView, income);

        incomeListContainer.addView(cardView, position);
        incomeList.add(position, income);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private CompoundButton setupCheckBoxForCard(View cardView) {
        if (isSingleChoice == null) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.income_checkbox);
        if (isSingleChoice) {
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
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyIncomeListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Income income) {
        TextView amountValue = cardView.findViewById(R.id.income_amount_value);
        TextView fromValue = cardView.findViewById(R.id.income_from_value);
        ImageView fromAvatar = cardView.findViewById(R.id.income_from_avatar);
        TextView timeDate = cardView.findViewById(R.id.income_time_date);
        TextView timeClock = cardView.findViewById(R.id.income_time_clock);
        View newIndicator = cardView.findViewById(R.id.new_indicator);

        if (newIndicator != null && Boolean.TRUE.equals(income.getNew())) {
            newIndicator.setVisibility(View.VISIBLE);
            income.setNew(Boolean.FALSE);
        }

        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(income.getValue()));
        amountValue.setText(amountText);

        String fromText = income.getFrom();
        fromValue.setText(fromText != null ? fromText : "");

        if (income.getTime() != null) {
            Date date = new Date(income.getTime() * 1000L);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yy-MM-dd", Locale.getDefault());
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

            timeDate.setText(dateFormat.format(date));
            timeClock.setText(timeFormat.format(date));
        } else {
            timeDate.setText("");
            timeClock.setText("");
        }

        setupFromAvatar(fromAvatar, income.getFrom());
    }

    private void setupCardClickListeners(View cardView, Income income) {
        cardView.setOnClickListener(v -> showIncomeDetailActivity(income));

        TextView amountValue = cardView.findViewById(R.id.income_amount_value);
        ImageView fromAvatar = cardView.findViewById(R.id.income_from_avatar);
        TextView fromValue = cardView.findViewById(R.id.income_from_value);
        TextView timeDate = cardView.findViewById(R.id.income_time_date);
        TextView timeClock = cardView.findViewById(R.id.income_time_clock);

        amountValue.setOnClickListener(v -> showIncomeDetailActivity(income));
        fromAvatar.setOnClickListener(v -> showIncomeDetailActivity(income));
        timeDate.setOnClickListener(v -> showIncomeDetailActivity(income));
        timeClock.setOnClickListener(v -> showIncomeDetailActivity(income));

        fromValue.setOnClickListener(v -> copyFidToClipboard(income.getFrom()));
        fromValue.setOnLongClickListener(v -> {
            addFidToList(income.getFrom());
            return true;
        });
    }

    /**
     * Adds income cards to the beginning of the list (newest/latest position) while removing
     * the same number from the end to maintain container size. Adjusts scroll position so
     * user stays viewing the same content and can scroll up to see new cards one by one.
     *
     * @param incomesToAdd List of income objects to add at the beginning
     * @param maxContainerSize Maximum allowed container size
     */
    public void addIncomeCardsToBeginningWithBottomRemoval(List<Income> incomesToAdd, int maxContainerSize) {
        if (incomesToAdd == null || incomesToAdd.isEmpty()) {
            return;
        }

        int currentSize = incomeList.size();
        int newItemsCount = incomesToAdd.size();

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
            for (int i = incomesToAdd.size() - 1; i >= 0; i--) {
                Income income = incomesToAdd.get(i);
                addIncomeCardAtPosition(income, 0);
            }

            // Mark the boundary card (last new card added, which is at index newItemsCount - 1)
            setBoundaryIndicator(newItemsCount - 1, true);

            // Calculate the height of newly added cards to adjust scroll position
            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < incomeListContainer.getChildCount(); i++) {
                        View cardView = incomeListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    // Adjust scroll position to account for the new cards added at the top
                    // This keeps the user viewing the same content they were looking at
                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    TimberLogger.d(TAG, "Added %d cards to beginning, adjusted scroll from %d to %d (added height: %d)",
                        newItemsCount, savedScrollY, newScrollY, addedHeight);
                });
            }

            TimberLogger.d(TAG, "Added %d cards to beginning, removed %d from bottom, container size: %d",
                newItemsCount, itemsToRemove, incomeList.size());
        } else {
            // Container not full, save scroll position before adding
            int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            // Add new items to the beginning
            for (int i = incomesToAdd.size() - 1; i >= 0; i--) {
                Income income = incomesToAdd.get(i);
                addIncomeCardAtPosition(income, 0);
            }

            // Mark the boundary card (last new card added, which is at index newItemsCount - 1)
            setBoundaryIndicator(newItemsCount - 1, true);

            // Adjust scroll position for the newly added cards
            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < incomeListContainer.getChildCount(); i++) {
                        View cardView = incomeListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    TimberLogger.d(TAG, "Container not full - adjusted scroll from %d to %d", savedScrollY, newScrollY);
                });
            }

            TimberLogger.d(TAG, "Added %d cards to beginning, container size: %d/%d",
                newItemsCount, incomeList.size(), maxContainerSize);
        }

        // Notify that the list has changed
        notifyIncomeListChanged();
    }

    /**
     * Adds income cards to the end of the list (oldest position) while removing the same number
     * from the beginning to maintain container size. Preserves scroll position so user stays
     * at the same visual location and can scroll down to see new cards one by one.
     *
     * @param incomesToAdd List of income objects to add at the end
     * @param maxContainerSize Maximum allowed container size
     */
    public void addIncomeCardsToEndWithTopRemoval(List<Income> incomesToAdd, int maxContainerSize) {
        if (incomesToAdd == null || incomesToAdd.isEmpty()) {
            return;
        }

        int currentSize = incomeList.size();
        int newItemsCount = incomesToAdd.size();

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
            for (int i = 0; i < itemsToRemove && i < incomeListContainer.getChildCount(); i++) {
                View cardView = incomeListContainer.getChildAt(i);
                removedHeight += cardView.getHeight();
            }
            final int finalRemovedHeight = removedHeight;

            // Remove items from the beginning (top)
            removeFromBeginning(itemsToRemove);

            // Adjust scroll position immediately after removing items to prevent jumping
            if (scrollView != null && finalRemovedHeight > 0) {
                int newScrollY = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, newScrollY);
                TimberLogger.d(TAG, "Adjusted scroll after removal: %d -> %d (removed height: %d)",
                    savedScrollY, newScrollY, finalRemovedHeight);
            }

            // Add new items to the end
            for (Income income : incomesToAdd) {
                addIncomeCardAtPosition(income, incomeList.size());
            }

            // Mark the boundary card (first new card, which is now at boundaryIndex)
            setBoundaryIndicator(boundaryIndex, true);

            TimberLogger.d(TAG, "Added %d cards to end, removed %d from top, container size: %d",
                newItemsCount, itemsToRemove, incomeList.size());
        } else {
            // Container not full, just add to the end
            for (Income income : incomesToAdd) {
                addIncomeCardAtPosition(income, incomeList.size());
            }

            // Mark the boundary card (first new card at the current end)
            setBoundaryIndicator(boundaryIndex, true);

            TimberLogger.d(TAG, "Added %d cards to end, container size: %d/%d",
                newItemsCount, incomeList.size(), maxContainerSize);
        }

        // Notify that the list has changed
        notifyIncomeListChanged();
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
        if (index < 0 || index >= incomeListContainer.getChildCount()) {
            return;
        }

        View cardView = incomeListContainer.getChildAt(index);
        if (cardView == null) {
            return;
        }

        View boundaryIndicator = cardView.findViewById(R.id.new_indicator);
        if (boundaryIndicator != null && index < incomeList.size()) {
            Income income = incomeList.get(index);

            if (show) {
                // Show boundary indicator in warning color
                boundaryIndicator.setVisibility(View.VISIBLE);
                boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.warning, null));
            } else {
                // Clear boundary indicator - restore based on income.getNew()
                if (Boolean.TRUE.equals(income.getNew())) {
                    boundaryIndicator.setVisibility(View.VISIBLE);
                    // Restore original new indicator color
                    boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.accent, null));
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
        for (int i = 0; i < incomeListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
        }
    }

    /**
     * Updates the from fields in all income cards to display CIDs instead of FIDs when available
     * This method checks each income's from field against the CidFidManager and updates the display accordingly
     */
    public void updateFromFieldsWithCids() {
        try {
            CidFidManager cidFidManager = CidFidManager.getInstance(context);
            
            // Iterate through all income cards and update the from fields
            for (int i = 0; i < incomeList.size() && i < incomeListContainer.getChildCount(); i++) {
                Income income = incomeList.get(i);
                View cardView = incomeListContainer.getChildAt(i);
                
                if (income != null && income.getFrom() != null && !income.getFrom().isEmpty()) {
                    String fid = income.getFrom();
                    String cid = cidFidManager.getCidByFid(fid);
                    
                    if (cid != null && !cid.isEmpty()) {
                        // Update the from value TextView with CID
                        TextView fromValue = cardView.findViewById(R.id.income_from_value);
                        if (fromValue != null) {
                            fromValue.setText(cid);
                            
                            // Update the click listeners to use CID instead of FID
                            fromValue.setOnClickListener(v -> copyFidToClipboard(cid));
                            fromValue.setOnLongClickListener(v -> {
                                addFidToList(cid);
                                return true;
                            });
                        }
                        
                        TimberLogger.d(TAG, "Updated income card %d: FID %s -> CID %s", i, fid, cid);
                    }
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating income cards with CIDs: %s", e.getMessage());
        }
    }

    /**
     * Helper class to maintain association between Income objects, their views, and checkboxes during sorting
     */
    private static class IncomeViewPair {
        final Income income;
        final View cardView;
        final CompoundButton checkBox;
        
        IncomeViewPair(Income income, View cardView, CompoundButton checkBox) {
            this.income = income;
            this.cardView = cardView;
            this.checkBox = checkBox;
        }
    }
}