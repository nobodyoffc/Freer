package com.fc.freer.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import com.fc.fc_ajdk.utils.TimberLogger;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.freer.R;
import com.fc.freer.data.UpdateHatActivity;
import com.fc.freer.im.FileShareHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Container for displaying Hat cards in a list.
 * Follows the pattern from SecretCardContainer.
 */
public class HatCardContainer {
    private static final String TAG = "HatCardContainer";

    private final Context context;
    private final LinearLayout container;
    private final List<Hat> hatList;
    private final List<View> cardViews;
    private final List<Hat> selectedHats;
    private final SimpleDateFormat dateFormat;

    private OnHatClickListener clickListener;
    private OnHatLongClickListener longClickListener;
    private OnHatRemoveListener removeListener;
    private OnHatEditListener editListener;
    private ChooseMode chooseMode = ChooseMode.WITHOUT_CHOOSE;
    private boolean showCheckboxes = false;
    private boolean alwaysShowCheckboxes = false;
    private boolean hideEditButton = false;
    private int clearButtonIconRes = 0;
    private String clearButtonContentDescription = null;

    public interface OnHatClickListener {
        void onHatClick(Hat hat, int position);
    }

    public interface OnHatLongClickListener {
        boolean onHatLongClick(Hat hat, int position);
    }

    public interface OnHatRemoveListener {
        void onHatRemove(Hat hat, int position);
    }

    public interface OnHatEditListener {
        void onHatEdit(Hat hat, int position);
    }

    public HatCardContainer(Context context, LinearLayout container) {
        this.context = context;
        this.container = container;
        this.hatList = new ArrayList<>();
        this.cardViews = new ArrayList<>();
        this.selectedHats = new ArrayList<>();
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
    }

    /**
     * Sets the click listener for hat cards.
     */
    public void setOnHatClickListener(OnHatClickListener listener) {
        this.clickListener = listener;
    }

    /**
     * Sets the long click listener for hat cards.
     */
    public void setOnHatLongClickListener(OnHatLongClickListener listener) {
        this.longClickListener = listener;
    }

    /**
     * Sets the remove listener for hat cards.
     */
    public void setOnHatRemoveListener(OnHatRemoveListener listener) {
        this.removeListener = listener;
    }

    /**
     * Sets the edit listener for hat cards.
     */
    public void setOnHatEditListener(OnHatEditListener listener) {
        this.editListener = listener;
    }

    /**
     * Sets whether checkboxes are always visible regardless of choose mode.
     */
    public void setAlwaysShowCheckboxes(boolean alwaysShow) {
        this.alwaysShowCheckboxes = alwaysShow;
        this.showCheckboxes = alwaysShow || (chooseMode != ChooseMode.WITHOUT_CHOOSE);
        refreshCheckboxVisibility();
    }

    /**
     * Sets a custom icon and content description for the clear/action button on cards.
     */
    public void setClearButtonIcon(int iconRes, String contentDescription) {
        this.clearButtonIconRes = iconRes;
        this.clearButtonContentDescription = contentDescription;
        refreshClearButtonIcon();
    }

    /**
     * Sets whether the edit button should be hidden on cards.
     */
    public void setHideEditButton(boolean hide) {
        this.hideEditButton = hide;
        refreshEditButtonVisibility();
    }

    /**
     * Sets the choose mode for selection.
     */
    public void setChooseMode(ChooseMode mode) {
        this.chooseMode = mode;
        this.showCheckboxes = alwaysShowCheckboxes || (mode != ChooseMode.WITHOUT_CHOOSE);
        refreshCheckboxVisibility();
    }

    /**
     * Gets the current choose mode.
     */
    public ChooseMode getChooseMode() {
        return chooseMode;
    }

    /**
     * Clears all hats from the container.
     */
    public void clear() {
        container.removeAllViews();
        hatList.clear();
        cardViews.clear();
        selectedHats.clear();
    }

    /**
     * Adds a list of hats to the container.
     */
    public void addHats(List<Hat> hats) {
        if (hats == null) return;
        for (Hat hat : hats) {
            addHat(hat);
        }
    }

    /**
     * Adds a single hat to the container.
     */
    public void addHat(Hat hat) {
        if (hat == null) return;

        hatList.add(hat);
        View cardView = createHatCard(hat, hatList.size() - 1);
        cardViews.add(cardView);
        container.addView(cardView);
    }

    /**
     * Removes a hat from the container.
     */
    public void removeHat(Hat hat) {
        int index = hatList.indexOf(hat);
        if (index >= 0) {
            hatList.remove(index);
            View cardView = cardViews.remove(index);
            container.removeView(cardView);
            selectedHats.remove(hat);
        }
    }

    /**
     * Updates a hat in the container.
     */
    public void updateHat(Hat hat) {
        for (int i = 0; i < hatList.size(); i++) {
            if (hatList.get(i).getId().equals(hat.getId())) {
                hatList.set(i, hat);
                View oldView = cardViews.get(i);
                int viewIndex = container.indexOfChild(oldView);
                container.removeView(oldView);

                View newView = createHatCard(hat, i);
                cardViews.set(i, newView);
                container.addView(newView, viewIndex);
                break;
            }
        }
    }

    /**
     * Gets all hats in the container.
     */
    public List<Hat> getHats() {
        return new ArrayList<>(hatList);
    }

    /**
     * Gets the selected hats.
     */
    public List<Hat> getSelectedHats() {
        return new ArrayList<>(selectedHats);
    }

    /**
     * Selects all hats.
     */
    public void selectAll() {
        selectedHats.clear();
        selectedHats.addAll(hatList);
        refreshCheckboxes();
    }

    /**
     * Deselects all hats.
     */
    public void deselectAll() {
        selectedHats.clear();
        refreshCheckboxes();
    }

    /**
     * Gets the count of hats.
     */
    public int getCount() {
        return hatList.size();
    }

    /**
     * Creates a card view for a hat.
     */
    private View createHatCard(Hat hat, int position) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View cardView = inflater.inflate(R.layout.item_hat_card, container, false);

        // Find views
        TextView nameTextView = cardView.findViewById(R.id.hat_name);
        TextView idTextView = cardView.findViewById(R.id.hat_id);
        TextView sizeTextView = cardView.findViewById(R.id.hat_size);
        TextView lastTextView = cardView.findViewById(R.id.hat_last);
        ImageView typeIcon = cardView.findViewById(R.id.hat_type_icon);
        ImageView storageIcon = cardView.findViewById(R.id.hat_storage_icon);
        ImageButton editButton = cardView.findViewById(R.id.hat_edit_button);
        ImageButton clearButton = cardView.findViewById(R.id.hat_clear_button);
        CheckBox checkBox = cardView.findViewById(R.id.hat_checkbox);
        ProgressBar progressBar = cardView.findViewById(R.id.hat_progress_bar);

        // Progress bar is hidden by default
        progressBar.setVisibility(View.GONE);

        // Set type icon
        typeIcon.setImageResource(FileShareHelper.getTypeIconRes(hat));

        // Set name
        String name = hat.getName();
        if (name != null && !name.isEmpty()) {
            nameTextView.setText(name);
        } else {
            nameTextView.setText(R.string.unnamed);
        }
        nameTextView.setOnClickListener(v -> {
            String val = hat.getName();
            if (val != null && !val.isEmpty()) {
                copyToClipboard(val);
            }
        });

        // Set ID (truncated)
        String id = hat.getId();
        if (id != null && id.length() > 16) {
            idTextView.setText(id.substring(0, 8) + "..." + id.substring(id.length() - 8));
        } else {
            idTextView.setText(id != null ? id : "");
        }
        idTextView.setOnClickListener(v -> {
            String val = hat.getId();
            if (val != null && !val.isEmpty()) {
                copyToClipboard(val);
            }
        });

        // Set size
        Long size = hat.getSize();
        if (size != null) {
            sizeTextView.setText(formatSize(size));
        } else {
            sizeTextView.setText("");
        }
        sizeTextView.setOnClickListener(v -> {
            CharSequence val = sizeTextView.getText();
            if (val != null && val.length() > 0) {
                copyToClipboard(val.toString());
            }
        });

        // Set last access time
        Long last = hat.getLast();
        if (last != null) {
            lastTextView.setText(dateFormat.format(new Date(last)));
        } else {
            lastTextView.setText("");
        }
        lastTextView.setOnClickListener(v -> {
            CharSequence val = lastTextView.getText();
            if (val != null && val.length() > 0) {
                copyToClipboard(val.toString());
            }
        });

        // Set storage location icon based on locas and cipherIds
        List<String> locas = hat.getLocas();
        boolean isOnDisk = false;
        boolean isLocal = false;

        if (locas != null) {
            for (String loca : locas) {
                if (loca != null) {
                    if (loca.startsWith("disk://") || loca.startsWith("fudp://") || loca.startsWith("(sid)")) {
                        isOnDisk = true;
                    } else if (loca.startsWith("local://")) {
                        isLocal = true;
                    }
                }
            }
        }

        // If the raw HAT has cipherIds, an encrypted copy exists on DISK
        List<String> cipherIds = hat.getCipherIds();
        if (cipherIds != null && !cipherIds.isEmpty()) {
            isOnDisk = true;
        }

        TimberLogger.d("HatCardIcon", "hatId=%s isOnDisk=%b isLocal=%b locas=%s cipherIds=%s",
                hat.getId(), isOnDisk, isLocal, locas, cipherIds);

        if (isOnDisk && isLocal) {
            storageIcon.setImageResource(R.drawable.ic_disk_local);
            storageIcon.setVisibility(View.VISIBLE);
            storageIcon.setContentDescription(context.getString(R.string.stored_on_disk_and_locally));
        } else if (isOnDisk) {
            storageIcon.setImageResource(R.drawable.ic_disk);
            storageIcon.setVisibility(View.VISIBLE);
            storageIcon.setContentDescription(context.getString(R.string.stored_on_disk));
        } else if (isLocal) {
            storageIcon.setImageResource(R.drawable.ic_local);
            storageIcon.setVisibility(View.VISIBLE);
            storageIcon.setContentDescription(context.getString(R.string.stored_locally));
        } else {
            storageIcon.setVisibility(View.GONE);
        }

        // Set checkbox visibility and state
        checkBox.setVisibility(showCheckboxes ? View.VISIBLE : View.GONE);
        checkBox.setChecked(selectedHats.contains(hat));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!selectedHats.contains(hat)) {
                    selectedHats.add(hat);
                }
            } else {
                selectedHats.remove(hat);
            }
        });

        // Set edit button visibility and click listener
        editButton.setVisibility(hideEditButton ? View.GONE : View.VISIBLE);
        editButton.setOnClickListener(v -> {
            if (editListener != null) {
                editListener.onHatEdit(hat, position);
            } else {
                // Default: launch UpdateHatActivity
                Intent intent = new Intent(context, UpdateHatActivity.class);
                intent.putExtra(UpdateHatActivity.EXTRA_HAT_JSON, hat.toJson());
                context.startActivity(intent);
            }
        });

        // Apply custom clear button icon if set
        if (clearButtonIconRes != 0) {
            clearButton.setImageResource(clearButtonIconRes);
            clearButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                    context.getResources().getColor(R.color.colorAccent, null)));
            if (clearButtonContentDescription != null) {
                clearButton.setContentDescription(clearButtonContentDescription);
            }
        }

        // Set clear/remove button click listener
        clearButton.setOnClickListener(v -> {
            if (removeListener != null) {
                removeListener.onHatRemove(hat, position);
            }
        });

        // Set click listeners - card click always opens detail, checkbox handles its own toggle
        cardView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onHatClick(hat, position);
            }
        });

        // Prevent checkbox click from propagating to card
        checkBox.setOnClickListener(v -> {
            // Checkbox state is already toggled by the system before this callback
            // The OnCheckedChangeListener above handles adding/removing from selectedHats
        });

        cardView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                return longClickListener.onHatLongClick(hat, position);
            }
            return false;
        });

        return cardView;
    }

    /**
     * Refreshes checkbox visibility for all cards.
     */
    private void refreshCheckboxVisibility() {
        for (View cardView : cardViews) {
            CheckBox checkBox = cardView.findViewById(R.id.hat_checkbox);
            if (checkBox != null) {
                checkBox.setVisibility(showCheckboxes ? View.VISIBLE : View.GONE);
            }
        }
    }

    /**
     * Refreshes checkbox states for all cards.
     */
    private void refreshCheckboxes() {
        for (int i = 0; i < cardViews.size(); i++) {
            View cardView = cardViews.get(i);
            Hat hat = hatList.get(i);
            CheckBox checkBox = cardView.findViewById(R.id.hat_checkbox);
            if (checkBox != null) {
                checkBox.setChecked(selectedHats.contains(hat));
            }
        }
    }

    /**
     * Refreshes the clear button icon for all existing cards.
     */
    private void refreshClearButtonIcon() {
        if (clearButtonIconRes == 0) return;
        for (View cardView : cardViews) {
            ImageButton clearButton = cardView.findViewById(R.id.hat_clear_button);
            if (clearButton != null) {
                clearButton.setImageResource(clearButtonIconRes);
                clearButton.setImageTintList(android.content.res.ColorStateList.valueOf(
                        context.getResources().getColor(R.color.colorAccent, null)));
                if (clearButtonContentDescription != null) {
                    clearButton.setContentDescription(clearButtonContentDescription);
                }
            }
        }
    }

    /**
     * Refreshes edit button visibility for all cards.
     */
    private void refreshEditButtonVisibility() {
        for (View cardView : cardViews) {
            ImageButton editButton = cardView.findViewById(R.id.hat_edit_button);
            if (editButton != null) {
                editButton.setVisibility(hideEditButton ? View.GONE : View.VISIBLE);
            }
        }
    }

    // ============================================================
    // Progress bar methods for upload/download tracking
    // ============================================================

    /**
     * Shows the progress bar for a hat identified by its ID.
     *
     * @param hatId   the ID of the hat
     * @param progress initial progress value (0-100)
     */
    public void showProgress(String hatId, int progress) {
        ProgressBar progressBar = findProgressBarByHatId(hatId);
        if (progressBar != null) {
            progressBar.setProgress(Math.max(0, Math.min(100, progress)));
            progressBar.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Updates the progress bar value for a hat identified by its ID.
     *
     * @param hatId    the ID of the hat
     * @param progress progress value (0-100)
     */
    public void updateProgress(String hatId, int progress) {
        ProgressBar progressBar = findProgressBarByHatId(hatId);
        if (progressBar != null) {
            progressBar.setProgress(Math.max(0, Math.min(100, progress)));
        }
    }

    /**
     * Hides the progress bar for a hat identified by its ID and resets it to 0.
     *
     * @param hatId the ID of the hat
     */
    public void hideProgress(String hatId) {
        ProgressBar progressBar = findProgressBarByHatId(hatId);
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
            progressBar.setProgress(0);
        }
    }

    /**
     * Shows the progress bar for a hat at the given position.
     *
     * @param position the position of the hat in the list
     * @param progress initial progress value (0-100)
     */
    public void showProgress(int position, int progress) {
        if (position < 0 || position >= cardViews.size()) return;
        ProgressBar progressBar = cardViews.get(position).findViewById(R.id.hat_progress_bar);
        if (progressBar != null) {
            progressBar.setProgress(Math.max(0, Math.min(100, progress)));
            progressBar.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Updates the progress bar value for a hat at the given position.
     *
     * @param position the position of the hat in the list
     * @param progress progress value (0-100)
     */
    public void updateProgress(int position, int progress) {
        if (position < 0 || position >= cardViews.size()) return;
        ProgressBar progressBar = cardViews.get(position).findViewById(R.id.hat_progress_bar);
        if (progressBar != null) {
            progressBar.setProgress(Math.max(0, Math.min(100, progress)));
        }
    }

    /**
     * Hides the progress bar for a hat at the given position and resets it to 0.
     *
     * @param position the position of the hat in the list
     */
    public void hideProgress(int position) {
        if (position < 0 || position >= cardViews.size()) return;
        ProgressBar progressBar = cardViews.get(position).findViewById(R.id.hat_progress_bar);
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
            progressBar.setProgress(0);
        }
    }

    /**
     * Finds the ProgressBar for a hat by its ID.
     */
    private ProgressBar findProgressBarByHatId(String hatId) {
        if (hatId == null) return null;
        for (int i = 0; i < hatList.size(); i++) {
            Hat hat = hatList.get(i);
            if (hatId.equals(hat.getId())) {
                return cardViews.get(i).findViewById(R.id.hat_progress_bar);
            }
        }
        return null;
    }

    // ============================================================
    // In-place sorting methods (matching NewsCardContainer pattern)
    // ============================================================

    /**
     * Sorts hats by name in-place, reordering existing card views without recreating them.
     */
    public void sortByName(boolean ascending, boolean enableSort) {
        sortHats(enableSort, (pair1, pair2) -> {
            String n1 = pair1.hat.getName() != null ? pair1.hat.getName() : "";
            String n2 = pair2.hat.getName() != null ? pair2.hat.getName() : "";
            return ascending ? n1.compareToIgnoreCase(n2) : n2.compareToIgnoreCase(n1);
        });
    }

    /**
     * Sorts hats by size in-place, reordering existing card views without recreating them.
     */
    public void sortBySize(boolean ascending, boolean enableSort) {
        sortHats(enableSort, (pair1, pair2) -> {
            Long s1 = pair1.hat.getSize() != null ? pair1.hat.getSize() : 0L;
            Long s2 = pair2.hat.getSize() != null ? pair2.hat.getSize() : 0L;
            return ascending ? s1.compareTo(s2) : s2.compareTo(s1);
        });
    }

    /**
     * Sorts hats by last access time in-place, reordering existing card views without recreating them.
     */
    public void sortByTime(boolean ascending, boolean enableSort) {
        sortHats(enableSort, (pair1, pair2) -> {
            Long t1 = pair1.hat.getLast() != null ? pair1.hat.getLast() : 0L;
            Long t2 = pair2.hat.getLast() != null ? pair2.hat.getLast() : 0L;
            return ascending ? t1.compareTo(t2) : t2.compareTo(t1);
        });
    }

    private void sortHats(boolean enableSort, Comparator<HatViewPair> comparator) {
        if (hatList.isEmpty() || !enableSort) {
            return;
        }

        List<HatViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < hatList.size(); i++) {
            Hat hat = hatList.get(i);
            View cardView = cardViews.get(i);
            pairs.add(new HatViewPair(hat, cardView));
        }

        pairs.sort(comparator);

        hatList.clear();
        cardViews.clear();
        container.removeAllViews();

        for (HatViewPair pair : pairs) {
            hatList.add(pair.hat);
            cardViews.add(pair.cardView);
            container.addView(pair.cardView);
        }
    }

    private record HatViewPair(Hat hat, View cardView) {
    }

    /**
     * Copies the given text to the system clipboard and shows a toast.
     */
    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("hat_field", text);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Formats a file size in bytes to a human-readable string.
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
