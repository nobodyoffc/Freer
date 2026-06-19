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
import android.widget.ScrollView;

import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.model.Expense;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.utils.ToastUtils;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExpenseCardContainer {
    private static final String TAG = "ExpenseCardContainer";
    private final Context context;
    private final ViewGroup expenseListContainer;
    private final List<Expense> expenseList;
    private final List<CompoundButton> checkBoxes;
    private final Boolean isSingleChoice;
    private OnExpenseListChangedListener onExpenseListChangedListener;
    private ScrollView scrollView;

    public interface OnExpenseListChangedListener {
        void onExpenseListChanged(List<Expense> updatedExpenseList);
    }

    public ExpenseCardContainer(Context context, LinearLayout expenseListContainer) {
        this(context, expenseListContainer, null);
    }

    public ExpenseCardContainer(Context context, LinearLayout expenseListContainer, Boolean isSingleChoice) {
        this.context = context;
        this.expenseListContainer = expenseListContainer;
        this.expenseList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.isSingleChoice = isSingleChoice;
    }

    public void setOnExpenseListChangedListener(OnExpenseListChangedListener listener) {
        this.onExpenseListChangedListener = listener;
    }

    private void notifyExpenseListChanged() {
        if (onExpenseListChangedListener != null) {
            onExpenseListChangedListener.onExpenseListChanged(new ArrayList<>(expenseList));
        }
    }

    public void addExpenseCard(Expense expense) {
        addExpenseCardAtPosition(expense, expenseList.size());
    }

    public void setScrollView(ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    private void setupToAvatar(ImageView avatarView, String toFid) {
        try {
            
            // Set initial background while avatar loads
            avatarView.setBackgroundColor(context.getResources().getColor(R.color.accent, context.getTheme()));
            
            if (toFid == null || toFid.isEmpty()) {
                return;
            }
            
            // Get avatar manager instance
            AvatarManager avatarManager = AvatarManager.getInstance(context);
            
            // Get avatar bitmap in background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    Bitmap avatarBitmap = avatarManager.getAvatarBitmap(toFid);
                    
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
                    TimberLogger.e(TAG, "Error generating avatar for to FID: %s", e.getMessage());
                }
            }).start();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up to avatar: %s", e.getMessage());
        }
    }

    private void showExpenseDetailActivity(Expense expense) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, expense.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Expense.class.getName());
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

    public List<Expense> getSelectedExpenses() {
        List<Expense> selectedExpenses = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedExpenses.add(expenseList.get(i));
            }
        }
        return selectedExpenses;
    }

    public void clearAll() {
        expenseList.clear();
        expenseListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Expense> getExpenseList() {
        return expenseList;
    }

    public void selectAll(boolean selected) {
        if (isSingleChoice == null || isSingleChoice) {
            return; // Do nothing for non-checkbox modes or single choice mode
        }
        
        for (CompoundButton checkBox : checkBoxes) {
            checkBox.setChecked(selected);
        }
    }

    /**
     * Removes cards from the beginning of the list (newest/latest cards)
     * @param count Number of cards to remove from the beginning
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > expenseList.size()) {
            return;
        }

        removeViewsFromBeginning(count);
        removeDataFromBeginning(count);
        notifyExpenseListChanged();
    }

    /**
     * Removes cards from the end of the list (oldest/earliest cards)
     * @param count Number of cards to remove from the end
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > expenseList.size()) {
            return;
        }

        removeViewsFromEnd(count);
        removeDataFromEnd(count);
        notifyExpenseListChanged();
    }

    private void removeViewsFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            expenseListContainer.removeViewAt(0);
        }
    }

    private void removeDataFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            expenseList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
    }

    private void removeViewsFromEnd(int count) {
        for (int i = 0; i < count; i++) {
            expenseListContainer.removeViewAt(expenseListContainer.getChildCount() - 1);
        }
    }

    private void removeDataFromEnd(int count) {
        int size = expenseList.size();
        for (int i = 0; i < count; i++) {
            int index = size - 1 - i;
            expenseList.remove(index);
            if (checkBoxes.size() > index) {
                checkBoxes.remove(index);
            }
        }
    }

    /**
     * Adds expense cards to the beginning of the list (newest/latest position) while removing
     * the same number from the end to maintain container size. Adjusts scroll position so
     * user stays viewing the same content and can scroll up to see new cards one by one.
     *
     * @param expensesToAdd List of expense objects to add at the beginning
     * @param maxContainerSize Maximum allowed container size
     */
    public void addExpenseCardsToBeginningWithBottomRemoval(List<Expense> expensesToAdd, int maxContainerSize) {
        if (expensesToAdd == null || expensesToAdd.isEmpty()) {
            return;
        }

        int currentSize = expenseList.size();
        int newItemsCount = expensesToAdd.size();

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
            for (int i = expensesToAdd.size() - 1; i >= 0; i--) {
                Expense expense = expensesToAdd.get(i);
                addExpenseCardAtPosition(expense, 0);
            }

            // Mark the boundary card (last new card added, which is at index newItemsCount - 1)
            setBoundaryIndicator(newItemsCount - 1, true);

            // Calculate the height of newly added cards to adjust scroll position
            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < expenseListContainer.getChildCount(); i++) {
                        View cardView = expenseListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    TimberLogger.d(TAG, "Added %d cards to beginning, adjusted scroll from %d to %d (added height: %d)",
                        newItemsCount, savedScrollY, newScrollY, addedHeight);
                });
            }

            TimberLogger.d(TAG, "Added %d cards to beginning, removed %d from bottom, container size: %d",
                newItemsCount, itemsToRemove, expenseList.size());
        } else {
            int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            for (int i = expensesToAdd.size() - 1; i >= 0; i--) {
                Expense expense = expensesToAdd.get(i);
                addExpenseCardAtPosition(expense, 0);
            }

            setBoundaryIndicator(newItemsCount - 1, true);

            if (scrollView != null) {
                scrollView.post(() -> {
                    int addedHeight = 0;
                    for (int i = 0; i < newItemsCount && i < expenseListContainer.getChildCount(); i++) {
                        View cardView = expenseListContainer.getChildAt(i);
                        addedHeight += cardView.getHeight();
                    }

                    int newScrollY = savedScrollY + addedHeight;
                    scrollView.scrollTo(0, newScrollY);

                    TimberLogger.d(TAG, "Container not full - adjusted scroll from %d to %d", savedScrollY, newScrollY);
                });
            }

            TimberLogger.d(TAG, "Added %d cards to beginning, container size: %d/%d",
                newItemsCount, expenseList.size(), maxContainerSize);
        }

        notifyExpenseListChanged();
    }

    /**
     * Adds expense cards to the end of the list (oldest position) while removing the same number
     * from the beginning to maintain container size. Preserves scroll position so user stays
     * at the same visual location and can scroll down to see new cards one by one.
     *
     * @param expensesToAdd List of expense objects to add at the end
     * @param maxContainerSize Maximum allowed container size
     */
    public void addExpenseCardsToEndWithTopRemoval(List<Expense> expensesToAdd, int maxContainerSize) {
        if (expensesToAdd == null || expensesToAdd.isEmpty()) {
            return;
        }

        int currentSize = expenseList.size();
        int newItemsCount = expensesToAdd.size();

        int itemsToRemove = Math.max(0, currentSize + newItemsCount - maxContainerSize);

        int boundaryIndex = currentSize - itemsToRemove;

        clearAllBoundaryIndicators();

        if (itemsToRemove > 0) {
            final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

            int removedHeight = 0;
            for (int i = 0; i < itemsToRemove && i < expenseListContainer.getChildCount(); i++) {
                View cardView = expenseListContainer.getChildAt(i);
                removedHeight += cardView.getHeight();
            }
            final int finalRemovedHeight = removedHeight;

            removeFromBeginning(itemsToRemove);

            if (scrollView != null && finalRemovedHeight > 0) {
                int newScrollY = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, newScrollY);
                TimberLogger.d(TAG, "Adjusted scroll after removal: %d -> %d (removed height: %d)",
                    savedScrollY, newScrollY, finalRemovedHeight);
            }

            for (Expense expense : expensesToAdd) {
                addExpenseCard(expense);
            }

            setBoundaryIndicator(boundaryIndex, true);

            TimberLogger.d(TAG, "Added %d cards to end, removed %d from top, container size: %d",
                newItemsCount, itemsToRemove, expenseList.size());
        } else {
            for (Expense expense : expensesToAdd) {
                addExpenseCard(expense);
            }

            setBoundaryIndicator(boundaryIndex, true);

            TimberLogger.d(TAG, "Added %d cards to end, container size: %d/%d",
                newItemsCount, expenseList.size(), maxContainerSize);
        }

        notifyExpenseListChanged();
    }

    /**
     * Sets or clears the boundary indicator for a specific card at the given index.
     *
     * @param index The index of the card in the container
     * @param show Whether to show or hide the boundary indicator
     */
    public void setBoundaryIndicator(int index, boolean show) {
        if (index < 0 || index >= expenseListContainer.getChildCount()) {
            return;
        }

        View cardView = expenseListContainer.getChildAt(index);
        if (cardView == null) {
            return;
        }

        View boundaryIndicator = cardView.findViewById(R.id.new_indicator);
        if (boundaryIndicator != null && index < expenseList.size()) {
            Expense expense = expenseList.get(index);

            if (show) {
                boundaryIndicator.setVisibility(View.VISIBLE);
                boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.warning, null));
            } else {
                if (Boolean.TRUE.equals(expense.getNew())) {
                    boundaryIndicator.setVisibility(View.VISIBLE);
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
        for (int i = 0; i < expenseListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
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
     * Sorts the expense cards by time
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByTime(boolean ascending, boolean enableSort) {
        if (expenseList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of expense and their corresponding views to maintain association
            List<ExpenseViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < expenseList.size(); i++) {
                Expense expense = expenseList.get(i);
                View cardView = expenseListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new ExpenseViewPair(expense, cardView, checkBox));
            }
            
            // Sort pairs by time
            Collections.sort(pairs, new Comparator<ExpenseViewPair>() {
                @Override
                public int compare(ExpenseViewPair pair1, ExpenseViewPair pair2) {
                    Long time1 = pair1.expense.getTime();
                    Long time2 = pair2.expense.getTime();
                    
                    // Handle null values - put them at the end
                    if (time1 == null && time2 == null) return 0;
                    if (time1 == null) return 1;
                    if (time2 == null) return -1;
                    
                    return ascending ? time1.compareTo(time2) : time2.compareTo(time1);
                }
            });
            
            // Update the lists and views based on sorted order
            expenseList.clear();
            checkBoxes.clear();
            expenseListContainer.removeAllViews();
            
            for (ExpenseViewPair pair : pairs) {
                expenseList.add(pair.expense);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                expenseListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Sorts the expense cards by to field
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByTo(boolean ascending, boolean enableSort) {
        if (expenseList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of expense and their corresponding views to maintain association
            List<ExpenseViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < expenseList.size(); i++) {
                Expense expense = expenseList.get(i);
                View cardView = expenseListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new ExpenseViewPair(expense, cardView, checkBox));
            }
            
            // Sort pairs by to field
            Collections.sort(pairs, new Comparator<ExpenseViewPair>() {
                @Override
                public int compare(ExpenseViewPair pair1, ExpenseViewPair pair2) {
                    String to1 = pair1.expense.getTo();
                    String to2 = pair2.expense.getTo();
                    
                    // Handle null values - put them at the end
                    if (to1 == null && to2 == null) return 0;
                    if (to1 == null) return 1;
                    if (to2 == null) return -1;
                    
                    return ascending ? to1.compareTo(to2) : to2.compareTo(to1);
                }
            });
            
            // Update the lists and views based on sorted order
            expenseList.clear();
            checkBoxes.clear();
            expenseListContainer.removeAllViews();
            
            for (ExpenseViewPair pair : pairs) {
                expenseList.add(pair.expense);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                expenseListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Sorts the expense cards by value (FCH amount)
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByValue(boolean ascending, boolean enableSort) {
        if (expenseList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of expense and their corresponding views to maintain association
            List<ExpenseViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < expenseList.size(); i++) {
                Expense expense = expenseList.get(i);
                View cardView = expenseListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new ExpenseViewPair(expense, cardView, checkBox));
            }
            
            // Sort pairs by value
            Collections.sort(pairs, new Comparator<ExpenseViewPair>() {
                @Override
                public int compare(ExpenseViewPair pair1, ExpenseViewPair pair2) {
                    Long value1 = pair1.expense.getValue();
                    Long value2 = pair2.expense.getValue();
                    
                    // Handle null values - put them at the end
                    if (value1 == null && value2 == null) return 0;
                    if (value1 == null) return 1;
                    if (value2 == null) return -1;
                    
                    return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
                }
            });
            
            // Update the lists and views based on sorted order
            expenseList.clear();
            checkBoxes.clear();
            expenseListContainer.removeAllViews();
            
            for (ExpenseViewPair pair : pairs) {
                expenseList.add(pair.expense);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                expenseListContainer.addView(pair.cardView);
            }
        }
    }

    private void addExpenseCardAtPosition(Expense expense, int position) {
        int layoutResId = R.layout.item_expense_card;

        View cardView = LayoutInflater.from(context).inflate(layoutResId, expenseListContainer, false);

        CompoundButton checkBox;
        if (isSingleChoice != null) {
            checkBox = cardView.findViewById(R.id.expense_checkbox);
            if (checkBox != null) {
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
                    checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyExpenseListChanged());
                }
            }
        } else {
            checkBox = null;
        }

        TextView amountValue = cardView.findViewById(R.id.expense_amount_value);
        TextView toValue = cardView.findViewById(R.id.expense_to_value);
        ImageView toAvatar = cardView.findViewById(R.id.expense_to_avatar);
        TextView timeDate = cardView.findViewById(R.id.expense_time_date);
        TextView timeClock = cardView.findViewById(R.id.expense_time_clock);

        View newIndicator = cardView.findViewById(R.id.new_indicator);

        if (newIndicator != null && Boolean.TRUE.equals(expense.getNew())) {
            newIndicator.setVisibility(View.VISIBLE);
            newIndicator.setBackgroundColor(context.getResources().getColor(R.color.accent, null));
            expense.setNew(Boolean.FALSE);
        }

        cardView.setOnClickListener(v -> showExpenseDetailActivity(expense));

        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(expense.getValue()));
        amountValue.setText(amountText);

        String toText = expense.getTo();
        toValue.setText(toText != null ? toText : "");

        if (expense.getTime() != null) {
            Date date = new Date(expense.getTime() * 1000L);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yy-MM-dd", Locale.getDefault());
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

            timeDate.setText(dateFormat.format(date));
            timeClock.setText(timeFormat.format(date));
        } else {
            timeDate.setText("");
            timeClock.setText("");
        }

        setupToAvatar(toAvatar, expense.getTo());

        amountValue.setOnClickListener(v -> showExpenseDetailActivity(expense));
        toAvatar.setOnClickListener(v -> showExpenseDetailActivity(expense));
        timeDate.setOnClickListener(v -> showExpenseDetailActivity(expense));
        timeClock.setOnClickListener(v -> showExpenseDetailActivity(expense));

        toValue.setOnClickListener(v -> copyFidToClipboard(expense.getTo()));
        toValue.setOnLongClickListener(v -> {
            addFidToList(expense.getTo());
            return true;
        });

        expenseListContainer.addView(cardView, position);
        expenseList.add(position, expense);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }
    
    /**
     * Helper class to maintain association between Expense objects, their views, and checkboxes during sorting
     */
    private static class ExpenseViewPair {
        final Expense expense;
        final View cardView;
        final CompoundButton checkBox;
        
        ExpenseViewPair(Expense expense, View cardView, CompoundButton checkBox) {
            this.expense = expense;
            this.cardView = cardView;
            this.checkBox = checkBox;
        }
    }
}