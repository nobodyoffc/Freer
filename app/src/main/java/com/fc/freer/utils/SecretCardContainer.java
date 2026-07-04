package com.fc.freer.utils;

import static android.view.View.GONE;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.PopupMenu;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.FcDate;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.secret.UpdateSecretActivity;
import com.fc.freer.ui.SecretContentDialog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SecretCardContainer {
    private static final String TAG = "SecretCardContainer";
    private final Context context;
    private final ViewGroup secretListContainer;
    private final List<Secret> secretList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnSecretListChangedListener onSecretListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private ActivityResultLauncher<Intent> updateSecretLauncher;
    private ScrollView scrollView;

    public interface OnSecretListChangedListener {
        void onSecretListChanged(List<Secret> updatedSecretList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Secret secret);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(Secret secret);
    }

    public SecretCardContainer(Context context, LinearLayout secretListContainer, ChooseMode chooseMode) {
        this(context, secretListContainer, chooseMode, null);
    }

    public SecretCardContainer(Context context, LinearLayout secretListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.secretListContainer = secretListContainer;
        this.secretList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnSecretListChangedListener(OnSecretListChangedListener listener) {
        this.onSecretListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnOffChainIconClickListener(OnOffChainIconClickListener listener) {
        this.onOffChainIconClickListener = listener;
    }

    public void setUpdateSecretLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateSecretLauncher = launcher;
    }

    public void setScrollView(ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    private void notifySecretListChanged() {
        if (onSecretListChangedListener != null) {
            onSecretListChangedListener.onSecretListChanged(new ArrayList<>(secretList));
        }
    }

    public void addSecretCard(Secret secret) {
        addSecretCardAtPosition(secret, secretList.size());
    }

    private void showSecretDetailActivity(Secret secret) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, secret.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Secret.class.getName());
        context.startActivity(intent);
    }

    private void launchUpdateSecretActivity(Secret secret) {
        Intent intent = new Intent(context, UpdateSecretActivity.class);
        intent.putExtra("secret", secret.toJson());
        if (updateSecretLauncher != null) {
            updateSecretLauncher.launch(intent);
        } else if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).startActivityForResult(intent, 2002); // Using 2002 for update activity (fallback)
        } else {
            context.startActivity(intent);
        }
    }

    private void decryptAndShowContent(Secret secret) {
        try {
            FidManager fidManager = FidManager.getInstance();
            if (fidManager == null || fidManager.getLiveKeyInfo() == null) {
                ToastUtils.makeText(context, context.getString(R.string.toast_no_active_key_decrypt));
                return;
            }

            String prikeyCipher = fidManager.getLiveKeyInfo().getPrikeyCipher();

            // Perform decryption in background thread
            new Thread(() -> {
                try {
                    // Get private key without user confirmation dialog
                    byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(prikeyCipher);

                    if (prikey == null) {
                        if (context instanceof android.app.Activity) {
                            ((android.app.Activity) context).runOnUiThread(() -> ToastUtils.makeText(context, context.getString(R.string.toast_failed_get_private_key)));
                        }
                        return;
                    }

                    // Decrypt the content
                    String decryptedContent = secret.decryptContent(prikey);

                    // Erase prikey immediately after use
                    com.fc.fc_ajdk.utils.BytesUtils.clearByteArray(prikey);

                    // Show result on main thread
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(() -> {
                            if (decryptedContent != null && !decryptedContent.isEmpty()) {
                                try {
                                    // Show content dialog
                                    SecretContentDialog dialog = new SecretContentDialog(context,
                                        secret.getTitle(), decryptedContent);
                                    dialog.show();
                                } catch (Exception e) {
                                    TimberLogger.e(TAG, "Error showing dialog: %s", e.getMessage());
                                    ToastUtils.makeText(context, context.getString(R.string.toast_error_showing_content, e.getMessage()));
                                }
                            } else {
                                ToastUtils.makeText(context, context.getString(R.string.toast_failed_decrypt_content));
                            }
                        });
                    }

                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error decrypting content: %s", e.getMessage());
                    if (context instanceof android.app.Activity) {
                        ((android.app.Activity) context).runOnUiThread(() -> ToastUtils.makeText(context, context.getString(R.string.toast_error_decrypting_content, e.getMessage())));
                    }
                }
            }).start();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initiating content decryption: %s", e.getMessage());
            ToastUtils.makeText(context, context.getString(R.string.toast_error_initiating_decryption, e.getMessage()));
        }
    }

    public List<Secret> getSelectedSecrets() {
        List<Secret> selectedSecrets = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedSecrets.add(secretList.get(i));
            }
        }
        return selectedSecrets;
    }

    public void clearAll() {
        secretList.clear();
        secretListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Secret> getSecretList() {
        return secretList;
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
     * Removes all currently selected secrets from the container
     */
    public void removeSelectedSecrets() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return;
        }

        // Get indices of selected items (in reverse order to avoid index shifting)
        List<Integer> selectedIndices = new ArrayList<>();
        for (int i = checkBoxes.size() - 1; i >= 0; i--) {
            if (checkBoxes.get(i).isChecked()) {
                selectedIndices.add(i);
            }
        }

        // Remove selected items
        for (int index : selectedIndices) {
            if (index < secretListContainer.getChildCount()) {
                secretListContainer.removeViewAt(index);
            }
            if (index < secretList.size()) {
                secretList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifySecretListChanged();
    }

    /**
     * Removes cards from the beginning of the list (newest/latest cards)
     * @param count Number of cards to remove from the beginning
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > secretList.size()) {
            return;
        }

        removeViewsFromBeginning(count);
        removeDataFromBeginning(count);

        notifySecretListChanged();
    }

    /**
     * Removes cards from the end of the list (oldest/earliest cards)
     * @param count Number of cards to remove from the end
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > secretList.size()) {
            return;
        }

        removeViewsFromEnd(count);
        removeDataFromEnd(count);

        notifySecretListChanged();
    }

    /**
     * Adds secret cards to the beginning of the list (newest/latest position)
     * @param secretsToAdd List of secret objects to add at the beginning
     */
    public void addSecretCardsToBeginning(List<Secret> secretsToAdd) {
        if (secretsToAdd == null || secretsToAdd.isEmpty()) {
            return;
        }

        // Add in reverse order so they appear in correct order at the beginning
        for (int i = secretsToAdd.size() - 1; i >= 0; i--) {
            Secret secret = secretsToAdd.get(i);
            addSecretCardAtPosition(secret, 0);
        }
    }
    
    public void sortBySaveTime(boolean ascending, boolean enableSort) {
        sortSecrets(enableSort, (pair1, pair2) -> {
            String saveTime1 = pair1.secret.getSaveTime();
            String saveTime2 = pair2.secret.getSaveTime();
            return compareNullable(saveTime1, saveTime2, ascending);
        });
    }
    
    public void sortByType(boolean ascending, boolean enableSort) {
        sortSecrets(enableSort, (pair1, pair2) -> {
            String type1 = pair1.secret.getType();
            String type2 = pair2.secret.getType();
            return compareNullable(type1, type2, ascending);
        });
    }

    public void sortByTitle(boolean ascending, boolean enableSort) {
        sortSecrets(enableSort, (pair1, pair2) -> {
            String title1 = pair1.secret.getTitle();
            String title2 = pair2.secret.getTitle();
            return compareNullable(title1, title2, ascending);
        });
    }

    public void sortByUpdateHeight(boolean ascending, boolean enableSort) {
        sortSecrets(enableSort, (pair1, pair2) -> {
            Long updateHeight1 = pair1.secret.getLastHeight();
            Long updateHeight2 = pair2.secret.getLastHeight();
            return compareNullable(updateHeight1, updateHeight2, ascending);
        });
    }


    private void addSecretCardAtPosition(Secret secret, int position) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, secret);

        secretListContainer.addView(cardView, position);
        secretList.add(position, secret);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        int layoutResId = getLayoutResId();
        return LayoutInflater.from(context).inflate(layoutResId, secretListContainer, false);
    }

    private int getLayoutResId() {
//        switch (chooseMode) {
//            case CHOOSE_ONE_RETURN:
//                return R.layout.item_secret_card;
//            case CHOOSE_ONE:
//                return R.layout.item_secret_card_radio;
//            case CHOOSE_MULTI:
//                return R.layout.item_secret_card_checkbox;
//            case WITHOUT_CHOOSE:
//                return R.layout.item_secret_card;
//            default:
//                return R.layout.item_secret_card;
//        }
        return R.layout.item_secret_card_checkbox;
    }

    private CompoundButton setupCardViewInteractions(View cardView, Secret secret) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, secret);
        setupClickListeners(cardView, secret);
        setupButtons(cardView, secret);
        View boundaryIndicator = cardView.findViewById(R.id.secret_boundary_indicator);
        if (boundaryIndicator != null) {
            boundaryIndicator.setVisibility(View.GONE);
        }
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.secret_checkbox);
        if (checkBox == null) {
            return null;
        }

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
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifySecretListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Secret secret) {
        TextView titleValue = cardView.findViewById(R.id.secret_title_value);
        TextView typeValue = cardView.findViewById(R.id.secret_type_value);
        TextView saveTimeValue = cardView.findViewById(R.id.secret_save_time_value);
        TextView updateHeightValue = cardView.findViewById(R.id.secret_update_height_value);
        ImageView onChainIcon = cardView.findViewById(R.id.secret_on_chain_icon);


        if(chooseMode!=ChooseMode.CHOOSE_MULTI) {
            CheckBox checkBox = cardView.findViewById(R.id.secret_checkbox);
            checkBox.setVisibility(GONE);
        }


        setTextValue(titleValue, secret.getTitle());
        setTextValue(typeValue, secret.getType());
        setSaveTimeValue(saveTimeValue, secret.getSaveTime());
        setUpdateHeightValue(updateHeightValue, secret.getLastHeight());
        setupOnChainIcon(onChainIcon, secret);
    }

    private void setTextValue(TextView textView, String value) {
        textView.setText(value != null ? value : "");
    }

    private void setSaveTimeValue(TextView saveTimeValue, String saveTime) {
        saveTimeValue.setText(saveTime != null ? saveTime:"");
    }

    private void setUpdateHeightValue(TextView updateHeightValue, Long updateHeight) {
        if (updateHeight != null && updateHeight > 0 && updateHeight != Constants.MaX_HEIGHT) {
            updateHeightValue.setText(FcDate.fromHeight(updateHeight).toString());
        } else {
            updateHeightValue.setText("");
        }
    }

    private void setupOnChainIcon(ImageView onChainIcon, Secret secret) {
        Boolean onChain = secret.getOnChain();
        if (onChain != null && onChain) {
            onChainIcon.setImageResource(R.drawable.ic_on_chain);
        } else if (onChain != null) {
            onChainIcon.setImageResource(R.drawable.ic_off_chain);
            onChainIcon.setOnClickListener(v -> {
                if (onOffChainIconClickListener != null) {
                    onOffChainIconClickListener.onOffChainIconClick(secret);
                }
            });
        } else {
            onChainIcon.setImageResource(R.drawable.ic_on_chain_unknown);
        }
    }

    private void setupClickListeners(View cardView, Secret secret) {
        TextView titleValue = cardView.findViewById(R.id.secret_title_value);
        TextView typeValue = cardView.findViewById(R.id.secret_type_value);
        TextView saveTimeValue = cardView.findViewById(R.id.secret_save_time_value);
        TextView updateHeightValue = cardView.findViewById(R.id.secret_update_height_value);

        View.OnClickListener clickListener = v -> showSecretDetailActivity(secret);
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, secret);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        titleValue.setOnClickListener(clickListener);
        titleValue.setOnLongClickListener(longPressListener);

        typeValue.setOnClickListener(clickListener);
        typeValue.setOnLongClickListener(longPressListener);

        saveTimeValue.setOnClickListener(clickListener);
        saveTimeValue.setOnLongClickListener(longPressListener);

        updateHeightValue.setOnClickListener(clickListener);
        updateHeightValue.setOnLongClickListener(longPressListener);
    }

    private View.OnLongClickListener createLongPressListener(View cardView, Secret secret) {
        return v -> {
            if (menuItems != null && !menuItems.isEmpty()) {
                PopupMenu popup = new PopupMenu(context, v);
                for (String menuItem : menuItems) {
                    popup.getMenu().add(menuItem);
                }

                popup.setOnMenuItemClickListener(item -> {
                    if(item.getTitle()==null)return false;
                    String title = item.getTitle().toString();
                    if ("Delete".equals(title)) {
                        removeSecretCard(cardView, secret);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, secret);
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

    private void removeSecretCard(View cardView, Secret secret) {
        secretListContainer.removeView(cardView);
        int index = secretList.indexOf(secret);
        if (index != -1) {
            secretList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifySecretListChanged();
    }

    private void setupButtons(View cardView, Secret secret) {
        ImageButton decryptButton = cardView.findViewById(R.id.secret_decrypt_button);
        ImageButton editButton = cardView.findViewById(R.id.secret_edit_button);

        if (decryptButton != null) {
            if (secret.getContentCipher() != null && !secret.getContentCipher().isEmpty()) {
                decryptButton.setVisibility(View.VISIBLE);
                decryptButton.setOnClickListener(v -> decryptAndShowContent(secret));
            } else {
                decryptButton.setVisibility(GONE);
            }
        }

        if (editButton != null) {
            if (chooseMode == ChooseMode.WITHOUT_CHOOSE) {
                // In WITHOUT_CHOOSE mode, change edit button to remove button
                editButton.setVisibility(View.VISIBLE);
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setContentDescription(context.getString(R.string.remove));
                editButton.setOnClickListener(v -> removeSecretCard(cardView, secret));
            } else {
                // Normal edit button behavior
                String content = secret.getContent();
                String contentCipher = secret.getContentCipher();
                if ((content == null || content.isEmpty()) && (contentCipher == null || contentCipher.isEmpty())) {
                    editButton.setVisibility(GONE);
                } else {
                    editButton.setVisibility(View.VISIBLE);
                    editButton.setOnClickListener(v -> launchUpdateSecretActivity(secret));
                }
            }
        }
    }

    private void sortSecrets(boolean enableSort, Comparator<SecretViewPair> comparator) {
        if (secretList.isEmpty() || !enableSort) {
            return;
        }

        List<SecretViewPair> pairs = createSecretViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<SecretViewPair> createSecretViewPairs() {
        List<SecretViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < secretList.size(); i++) {
            Secret secret = secretList.get(i);
            View cardView = secretListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new SecretViewPair(secret, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<SecretViewPair> pairs) {
        secretList.clear();
        checkBoxes.clear();
        secretListContainer.removeAllViews();

        for (SecretViewPair pair : pairs) {
            secretList.add(pair.secret);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            secretListContainer.addView(pair.cardView);
        }
    }

    public void addSecretCardsToBeginningWithBottomRemoval(List<Secret> secretsToAdd,
                                                           int itemsRemovedFromEnd,
                                                           int maxContainerSize) {
        if (secretsToAdd == null || secretsToAdd.isEmpty()) {
            return;
        }

        int currentSize = secretList.size();
        int newItemsCount = secretsToAdd.size();
        int requiredRemoval = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int removalCount = Math.max(requiredRemoval, Math.max(0, itemsRemovedFromEnd));

        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

        clearAllBoundaryIndicators();

        if (removalCount > 0) {
            removeViewsFromEnd(removalCount);
            removeDataFromEnd(removalCount);
        }

        for (int i = secretsToAdd.size() - 1; i >= 0; i--) {
            addSecretCardAtPosition(secretsToAdd.get(i), 0);
        }

        int boundaryIndex = Math.min(newItemsCount - 1, secretListContainer.getChildCount() - 1);
        setBoundaryIndicator(boundaryIndex, true);

        if (scrollView != null) {
            scrollView.post(() -> {
                int addedHeight = 0;
                int limit = Math.min(newItemsCount, secretListContainer.getChildCount());
                for (int i = 0; i < limit; i++) {
                    View cardView = secretListContainer.getChildAt(i);
                    if (cardView != null) {
                        addedHeight += cardView.getHeight();
                    }
                }
                scrollView.scrollTo(0, savedScrollY + addedHeight);
            });
        }

        notifySecretListChanged();
    }

    public void addSecretCardsToEndWithTopRemoval(List<Secret> secretsToAdd,
                                                  int itemsRemovedFromBeginning,
                                                  int maxContainerSize) {
        if (secretsToAdd == null || secretsToAdd.isEmpty()) {
            return;
        }

        int currentSize = secretList.size();
        int newItemsCount = secretsToAdd.size();
        int requiredRemoval = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int removalCount = Math.max(requiredRemoval, Math.max(0, itemsRemovedFromBeginning));

        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;
        int removedHeight = 0;

        clearAllBoundaryIndicators();

        if (removalCount > 0) {
            int limit = Math.min(removalCount, secretListContainer.getChildCount());
            for (int i = 0; i < limit; i++) {
                View cardView = secretListContainer.getChildAt(i);
                if (cardView != null) {
                    removedHeight += cardView.getHeight();
                }
            }

            removeViewsFromBeginning(removalCount);
            removeDataFromBeginning(removalCount);
        }

        int boundaryIndex = secretListContainer.getChildCount();
        for (Secret secret : secretsToAdd) {
            addSecretCard(secret);
        }

        setBoundaryIndicator(boundaryIndex, true);

        if (scrollView != null && removedHeight > 0) {
            final int finalRemovedHeight = removedHeight;
            scrollView.post(() -> {
                int adjusted = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, adjusted);
            });
        }

        notifySecretListChanged();
    }

    public void setBoundaryIndicator(int index, boolean show) {
        if (index < 0 || index >= secretListContainer.getChildCount()) {
            return;
        }

        View cardView = secretListContainer.getChildAt(index);
        if (cardView == null) {
            return;
        }

        View indicator = cardView.findViewById(R.id.secret_boundary_indicator);
        if (indicator == null) {
            return;
        }

        if (show) {
            indicator.setVisibility(View.VISIBLE);
            indicator.setBackgroundColor(context.getResources().getColor(R.color.warning, null));
        } else {
            indicator.setVisibility(View.GONE);
        }
    }

    public void clearAllBoundaryIndicators() {
        for (int i = 0; i < secretListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
        }
    }

    private void removeViewsFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            secretListContainer.removeViewAt(0);
        }
    }

    private void removeDataFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            secretList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
    }

    private void removeViewsFromEnd(int count) {
        for (int i = 0; i < count; i++) {
            secretListContainer.removeViewAt(secretListContainer.getChildCount() - 1);
        }
    }

    private void removeDataFromEnd(int count) {
        int size = secretList.size();
        for (int i = 0; i < count; i++) {
            int index = size - 1 - i;
            secretList.remove(index);
            if (checkBoxes.size() > index) {
                checkBoxes.remove(index);
            }
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private record SecretViewPair(Secret secret, View cardView, CompoundButton checkBox) {
    }
}