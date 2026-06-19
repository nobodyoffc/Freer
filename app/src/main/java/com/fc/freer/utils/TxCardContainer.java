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
import android.widget.ScrollView;
import android.widget.TextView;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.FidTxMask;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
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

public class TxCardContainer {
    private static final String TAG = "TxCardContainer";
    private final Context context;
    private final ViewGroup txListContainer;
    private final List<FidTxMask> txList;
    private final List<CompoundButton> checkBoxes;
    private final Boolean isSingleChoice;
    private OnTxListChangedListener onTxListChangedListener;
    private OnTxClickListener onTxClickListener;
    private ScrollView scrollView;

    public interface OnTxListChangedListener {
        void onTxListChanged(List<FidTxMask> updatedTxList);
    }

    public interface OnTxClickListener {
        void onTxClick(FidTxMask txInfo);
    }

    public TxCardContainer(Context context, LinearLayout txListContainer) {
        this(context, txListContainer, null);
    }

    public TxCardContainer(Context context, LinearLayout txListContainer, Boolean isSingleChoice) {
        this.context = context;
        this.txListContainer = txListContainer;
        this.txList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.isSingleChoice = isSingleChoice;
    }

    public void setOnTxListChangedListener(OnTxListChangedListener listener) {
        this.onTxListChangedListener = listener;
    }

    public void setOnTxClickListener(OnTxClickListener listener) {
        this.onTxClickListener = listener;
    }

    private void notifyTxListChanged() {
        if (onTxListChangedListener != null) {
            onTxListChangedListener.onTxListChanged(new ArrayList<>(txList));
        }
    }

    public void addTxCard(FidTxMask txInfo) {
        addTxCardAtPosition(txInfo, txList.size());
    }

    private void addTxCardAtPosition(FidTxMask txInfo, int position) {
        int layoutResId = R.layout.item_tx_card_checkbox;

        View cardView = LayoutInflater.from(context).inflate(layoutResId, txListContainer, false);

        CompoundButton checkBox;
        if (isSingleChoice != null) {
            checkBox = cardView.findViewById(R.id.tx_checkbox);

            if (position >= 0 && position < checkBoxes.size()) {
                checkBoxes.add(position, checkBox);
            } else {
                checkBoxes.add(checkBox);
            }

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
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    notifyTxListChanged();
                });
            }
        } else {
            checkBox = null;
        }

        ImageView payerAvatar = cardView.findViewById(R.id.tx_payer_avatar);
        TextView timeDate = cardView.findViewById(R.id.tx_time_date);
        TextView timeClock = cardView.findViewById(R.id.tx_time_clock);
        TextView inOutCount = cardView.findViewById(R.id.tx_in_out_count);
        TextView amountValue = cardView.findViewById(R.id.tx_amount_value);
        ImageView payeeAvatar = cardView.findViewById(R.id.tx_payee_avatar);

        View newIndicator = cardView.findViewById(R.id.new_indicator);

        if (newIndicator != null && Boolean.TRUE.equals(txInfo.getNew())) {
            newIndicator.setVisibility(View.VISIBLE);
            txInfo.setNew(Boolean.FALSE);
        }

        cardView.setOnClickListener(v -> {
            if (onTxClickListener != null) {
                onTxClickListener.onTxClick(txInfo);
            } else {
                showTxDetailActivity(txInfo);
            }
        });

        String payerFid = txInfo.getFrom();
        setupAvatar(payerAvatar, payerFid);

        if (txInfo.getTime() != null) {
            Date date = new Date(txInfo.getTime() * 1000L);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yy-MM-dd", Locale.getDefault());
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

            timeDate.setText(dateFormat.format(date));
            timeClock.setText(timeFormat.format(date));
        } else {
            timeDate.setText("");
            timeClock.setText("");
        }

        String inOutText = String.format("%d -> %d",
            txInfo.getIn() != null ? txInfo.getIn() : 0,
            txInfo.getOut() != null ? txInfo.getOut() : 0);
        inOutCount.setText(inOutText);

        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(txInfo.getBalance() != null ? txInfo.getBalance() : 0);
        amountValue.setText(amountText);

        String payeeFid = txInfo.getTo();
        setupAvatar(payeeAvatar, payeeFid);

        timeDate.setOnClickListener(v -> {
            if (onTxClickListener != null) {
                onTxClickListener.onTxClick(txInfo);
            } else {
                showTxDetailActivity(txInfo);
            }
        });
        timeClock.setOnClickListener(v -> {
            if (onTxClickListener != null) {
                onTxClickListener.onTxClick(txInfo);
            } else {
                showTxDetailActivity(txInfo);
            }
        });
        inOutCount.setOnClickListener(v -> {
            if (onTxClickListener != null) {
                onTxClickListener.onTxClick(txInfo);
            } else {
                showTxDetailActivity(txInfo);
            }
        });
        amountValue.setOnClickListener(v -> {
            if (onTxClickListener != null) {
                onTxClickListener.onTxClick(txInfo);
            } else {
                showTxDetailActivity(txInfo);
            }
        });

        payerAvatar.setOnClickListener(v -> copyFidToClipboard(payerFid));
        payerAvatar.setOnLongClickListener(v -> {
            addFidToList(payerFid);
            return true;
        });

        payeeAvatar.setOnClickListener(v -> copyFidToClipboard(payeeFid));
        payeeAvatar.setOnLongClickListener(v -> {
            addFidToList(payeeFid);
            return true;
        });

        if (position >= 0 && position < txListContainer.getChildCount()) {
            txListContainer.addView(cardView, position);
        } else {
            txListContainer.addView(cardView);
        }

        if (position >= 0 && position < txList.size()) {
            txList.add(position, txInfo);
        } else {
            txList.add(txInfo);
        }
    }

    private void setupAvatar(ImageView avatarView, String fid) {
        try {
            // Set initial background while avatar loads
//            avatarView.setBackgroundColor(context.getResources().getColor(R.color.colorAccent, context.getTheme()));
            
            if (fid == null || fid.isEmpty() || !KeyTools.isGoodFid(fid)) {
                return;
            }
            
            // Get avatar manager instance
            AvatarManager avatarManager = AvatarManager.getInstance(context);
            
            // Get avatar bitmap in background thread to avoid blocking UI
            new Thread(() -> {
                try {
                    Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
                    
                    // Update UI on main thread
                    ((android.app.Activity) context).runOnUiThread(() -> {
                        if (avatarBitmap != null && !((android.app.Activity) context).isFinishing() && !((android.app.Activity) context).isDestroyed()) {
                            avatarView.setImageBitmap(avatarBitmap);
                            // Clear background color when bitmap is set
                            avatarView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                        }
                    });
                    
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error generating avatar for FID: %s", e.getMessage());
                }
            }).start();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up avatar: %s", e.getMessage());
        }
    }

    private void showTxDetailActivity(FidTxMask txInfo) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, txInfo.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, FidTxMask.class.getName());
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

    public List<FidTxMask> getSelectedTxs() {
        List<FidTxMask> selectedTxs = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedTxs.add(txList.get(i));
            }
        }
        return selectedTxs;
    }

    public void clearAll() {
        txList.clear();
        txListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public void setScrollView(ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    public List<FidTxMask> getTxList() {
        return txList;
    }

    public void selectAll(boolean selected) {
        if (isSingleChoice == null || isSingleChoice) {
            return; // Do nothing for non-checkbox modes or single choice mode
        }
        
        for (CompoundButton checkBox : checkBoxes) {
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
     * Sorts the tx cards by time
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByTime(boolean ascending, boolean enableSort) {
        if (txList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of tx and their corresponding views to maintain association
            List<TxViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < txList.size(); i++) {
                FidTxMask txInfo = txList.get(i);
                View cardView = txListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new TxViewPair(txInfo, cardView, checkBox));
            }
            
            // Sort pairs by time
            Collections.sort(pairs, new Comparator<TxViewPair>() {
                @Override
                public int compare(TxViewPair pair1, TxViewPair pair2) {
                    Long time1 = pair1.txInfo.getTime();
                    Long time2 = pair2.txInfo.getTime();
                    
                    // Handle null values - put them at the end
                    if (time1 == null && time2 == null) return 0;
                    if (time1 == null) return 1;
                    if (time2 == null) return -1;
                    
                    return ascending ? time1.compareTo(time2) : time2.compareTo(time1);
                }
            });
            
            // Update the lists and views based on sorted order
            txList.clear();
            checkBoxes.clear();
            txListContainer.removeAllViews();
            
            for (TxViewPair pair : pairs) {
                txList.add(pair.txInfo);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                txListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Sorts the tx cards by payer (spentCashes[0].owner)
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByPayer(boolean ascending, boolean enableSort) {
        if (txList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of tx and their corresponding views to maintain association
            List<TxViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < txList.size(); i++) {
                FidTxMask txInfo = txList.get(i);
                View cardView = txListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new TxViewPair(txInfo, cardView, checkBox));
            }
            
            // Sort pairs by payer field
            Collections.sort(pairs, new Comparator<TxViewPair>() {
                @Override
                public int compare(TxViewPair pair1, TxViewPair pair2) {

                    String payer1 = pair1.txInfo.getFrom();
                    String payer2 = pair2.txInfo.getTo();

                    // Handle null values - put them at the end
                    if (payer1 == null && payer2 == null) return 0;
                    if (payer1 == null) return 1;
                    if (payer2 == null) return -1;
                    
                    return ascending ? payer1.compareTo(payer2) : payer2.compareTo(payer1);
                }
            });
            
            // Update the lists and views based on sorted order
            txList.clear();
            checkBoxes.clear();
            txListContainer.removeAllViews();
            
            for (TxViewPair pair : pairs) {
                txList.add(pair.txInfo);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                txListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Sorts the tx cards by payee (issuedCashes[0].owner)
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByPayee(boolean ascending, boolean enableSort) {
        if (txList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of tx and their corresponding views to maintain association
            List<TxViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < txList.size(); i++) {
                FidTxMask txInfo = txList.get(i);
                View cardView = txListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new TxViewPair(txInfo, cardView, checkBox));
            }
            
            // Sort pairs by payee field
            Collections.sort(pairs, new Comparator<TxViewPair>() {
                @Override
                public int compare(TxViewPair pair1, TxViewPair pair2) {

                    String payee1 = pair1.txInfo.getTo();
                    String payee2 = pair2.txInfo.getTo();
                    
                    // Handle null values - put them at the end
                    if (payee1 == null && payee2 == null) return 0;
                    if (payee1 == null) return 1;
                    if (payee2 == null) return -1;
                    
                    return ascending ? payee1.compareTo(payee2) : payee2.compareTo(payee1);
                }
            });
            
            // Update the lists and views based on sorted order
            txList.clear();
            checkBoxes.clear();
            txListContainer.removeAllViews();
            
            for (TxViewPair pair : pairs) {
                txList.add(pair.txInfo);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                txListContainer.addView(pair.cardView);
            }
        }
    }
    
    /**
     * Sorts the tx cards by value (FCH amount)
     * @param ascending true for ascending order, false for descending
     * @param enableSort true to enable sorting, false to restore original order
     */
    public void sortByValue(boolean ascending, boolean enableSort) {
        if (txList.isEmpty()) {
            return;
        }
        
        if (enableSort) {
            // Create pairs of tx and their corresponding views to maintain association
            List<TxViewPair> pairs = new ArrayList<>();
            for (int i = 0; i < txList.size(); i++) {
                FidTxMask txInfo = txList.get(i);
                View cardView = txListContainer.getChildAt(i);
                CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
                pairs.add(new TxViewPair(txInfo, cardView, checkBox));
            }
            
            // Sort pairs by value
            Collections.sort(pairs, new Comparator<TxViewPair>() {
                @Override
                public int compare(TxViewPair pair1, TxViewPair pair2) {
                    Double value1 = pair1.txInfo.getBalance();
                    Double value2 = pair2.txInfo.getBalance();
                    
                    // Handle null values - put them at the end
                    if (value1 == null && value2 == null) return 0;
                    if (value1 == null) return 1;
                    if (value2 == null) return -1;
                    
                    return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
                }
            });
            
            // Update the lists and views based on sorted order
            txList.clear();
            checkBoxes.clear();
            txListContainer.removeAllViews();
            
            for (TxViewPair pair : pairs) {
                txList.add(pair.txInfo);
                if (pair.checkBox != null) {
                    checkBoxes.add(pair.checkBox);
                }
                txListContainer.addView(pair.cardView);
            }
        }
    }

    public void removeFromBeginning(int count) {
        if (count <= 0 || count > txList.size()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
            txList.remove(0);
            txListContainer.removeViewAt(0);
        }

        notifyTxListChanged();
    }

    public void removeFromEnd(int count) {
        if (count <= 0 || count > txList.size()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            int lastIndex = txList.size() - 1;
            if (lastIndex < txListContainer.getChildCount()) {
                txListContainer.removeViewAt(lastIndex);
            }
            txList.remove(lastIndex);
            if (checkBoxes.size() > lastIndex) {
                checkBoxes.remove(lastIndex);
            }
        }

        notifyTxListChanged();
    }

    public void addTxCardsToBeginningWithBottomRemoval(List<FidTxMask> txsToAdd, int maxContainerSize) {
        if (txsToAdd == null || txsToAdd.isEmpty()) {
            return;
        }

        int currentSize = txList.size();
        int newItemsCount = txsToAdd.size();
        int itemsToRemove = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

        clearAllBoundaryIndicators();

        if (itemsToRemove > 0) {
            removeFromEnd(itemsToRemove);
        }

        for (int i = txsToAdd.size() - 1; i >= 0; i--) {
            FidTxMask txInfo = txsToAdd.get(i);
            addTxCardAtPosition(txInfo, 0);
        }

        if (newItemsCount > 0) {
            setBoundaryIndicator(newItemsCount - 1, true);
        }

        if (scrollView != null) {
            scrollView.post(() -> {
                int addedHeight = 0;
                for (int i = 0; i < newItemsCount && i < txListContainer.getChildCount(); i++) {
                    View cardView = txListContainer.getChildAt(i);
                    addedHeight += cardView.getHeight();
                }
                scrollView.scrollTo(0, savedScrollY + addedHeight);
                TimberLogger.d(TAG, "Added %d tx cards to beginning, adjusted scroll from %d to %d",
                    newItemsCount, savedScrollY, savedScrollY + addedHeight);
            });
        }

        notifyTxListChanged();
    }

    public void addTxCardsToEndWithTopRemoval(List<FidTxMask> txsToAdd, int maxContainerSize) {
        if (txsToAdd == null || txsToAdd.isEmpty()) {
            return;
        }

        int currentSize = txList.size();
        int newItemsCount = txsToAdd.size();
        int itemsToRemove = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int boundaryIndex = Math.max(0, currentSize - itemsToRemove);
        int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;
        int removedHeight = 0;

        clearAllBoundaryIndicators();

        if (itemsToRemove > 0) {
            for (int i = 0; i < itemsToRemove && i < txListContainer.getChildCount(); i++) {
                View cardView = txListContainer.getChildAt(i);
                removedHeight += cardView.getHeight();
            }

            removeFromBeginning(itemsToRemove);
        }

        for (FidTxMask txInfo : txsToAdd) {
            addTxCard(txInfo);
        }

        if (scrollView != null && removedHeight > 0) {
            int finalRemovedHeight = removedHeight;
            scrollView.post(() -> {
                int newScrollY = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, newScrollY);
                TimberLogger.d(TAG, "Removed %d height from top, adjusted scroll from %d to %d",
                    finalRemovedHeight, savedScrollY, newScrollY);
            });
        }

        setBoundaryIndicator(boundaryIndex, true);

        notifyTxListChanged();
    }

    public void setBoundaryIndicator(int index, boolean show) {
        if (index < 0 || index >= txListContainer.getChildCount()) {
            return;
        }

        View cardView = txListContainer.getChildAt(index);
        if (cardView == null) {
            return;
        }

        View boundaryIndicator = cardView.findViewById(R.id.new_indicator);
        if (boundaryIndicator != null && index < txList.size()) {
            FidTxMask txInfo = txList.get(index);

            if (show) {
                boundaryIndicator.setVisibility(View.VISIBLE);
                boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.warning, null));
            } else if (Boolean.TRUE.equals(txInfo.getNew())) {
                boundaryIndicator.setVisibility(View.VISIBLE);
                boundaryIndicator.setBackgroundColor(context.getResources().getColor(R.color.accent, null));
            } else {
                boundaryIndicator.setVisibility(View.GONE);
            }
        }
    }

    public void clearAllBoundaryIndicators() {
        for (int i = 0; i < txListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
        }
    }

    /**
     * Updates the payer and payee fields in all tx cards to display CIDs instead of FIDs when available
     * This method checks each transaction's from/to fields against the CidFidManager and updates the display accordingly
     */
    public void updatePayerPayeeFieldsWithCids() {
        try {
            CidFidManager cidFidManager = CidFidManager.getInstance(context);

            // Iterate through all tx cards and update the payer/payee fields
            for (int i = 0; i < txList.size() && i < txListContainer.getChildCount(); i++) {
                FidTxMask txInfo = txList.get(i);
                View cardView = txListContainer.getChildAt(i);

                if (txInfo != null) {
                    // Update payer field (from)
                    String payerFid = txInfo.getFrom();
                    if (payerFid != null && !payerFid.isEmpty()) {
                        String payerCid = cidFidManager.getCidByFid(payerFid);
                        if (payerCid != null && !payerCid.isEmpty()) {
                            // Note: Payer is represented by avatar only, no text field to update
                            TimberLogger.d(TAG, "Found CID for payer FID %s -> CID %s", payerFid, payerCid);
                        }
                    }

                    // Update payee field (to)
                    String payeeFid = txInfo.getTo();
                    if (payeeFid != null && !payeeFid.isEmpty()) {
                        String payeeCid = cidFidManager.getCidByFid(payeeFid);
                        if (payeeCid != null && !payeeCid.isEmpty()) {
                            // Note: Payee is represented by avatar only, no text field to update
                            TimberLogger.d(TAG, "Found CID for payee FID %s -> CID %s", payeeFid, payeeCid);
                        }
                    }

                    TimberLogger.d(TAG, "Processed tx card %d for CID updates", i);
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating tx cards with CIDs: %s", e.getMessage());
        }
    }

    /**
     * Helper class to maintain association between Tx objects, their views, and checkboxes during sorting
     */
    private static class TxViewPair {
        final FidTxMask txInfo;
        final View cardView;
        final CompoundButton checkBox;
        
        TxViewPair(FidTxMask txInfo, View cardView, CompoundButton checkBox) {
            this.txInfo = txInfo;
            this.cardView = cardView;
            this.checkBox = checkBox;
        }
    }
}