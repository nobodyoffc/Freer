package com.fc.freer.utils;

import static android.view.View.VISIBLE;
import static com.fc.fc_ajdk.utils.StringUtils.listToString;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.PopupMenu;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;

import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ContactCardContainer {
    private static final String TAG = "ContactCardContainer";
    private final Context context;
    private final ViewGroup contactListContainer;
    private final List<Contact> contactList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnContactListChangedListener onContactListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private OnContactClickListener onContactClickListener;
    private OnContactRemoveListener onContactRemoveListener;
    private ActivityResultLauncher<Intent> updateContactLauncher;
    private ScrollView scrollView;

    public interface OnContactListChangedListener {
        void onContactListChanged(List<Contact> updatedContactList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Contact contact);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(Contact contact);
    }

    public interface OnContactClickListener {
        void onContactClick(Contact contact);
    }

    public interface OnContactRemoveListener {
        void onContactRemove(Contact contact);
    }

    public ContactCardContainer(Context context, LinearLayout contactListContainer, ChooseMode chooseMode) {
        this(context, contactListContainer, chooseMode, null);
    }

    public ContactCardContainer(Context context, LinearLayout contactListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.contactListContainer = contactListContainer;
        this.contactList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnContactListChangedListener(OnContactListChangedListener listener) {
        this.onContactListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnOffChainIconClickListener(OnOffChainIconClickListener listener) {
        this.onOffChainIconClickListener = listener;
    }

    public void setOnContactClickListener(OnContactClickListener listener) {
        this.onContactClickListener = listener;
    }

    public void setOnContactRemoveListener(OnContactRemoveListener listener) {
        this.onContactRemoveListener = listener;
    }

    public void setUpdateContactLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateContactLauncher = launcher;
    }

    public void setScrollView(ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    private void notifyContactListChanged() {
        if (onContactListChangedListener != null) {
            onContactListChangedListener.onContactListChanged(new ArrayList<>(contactList));
        }
    }

    public void addContactCard(Contact contact) {
        addContactCardAtPosition(contact, contactList.size());
    }

    private void showContactActivity(Contact contact) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, contact.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Contact.class.getName());
        context.startActivity(intent);
    }

    public List<Contact> getSelectedContacts() {
        List<Contact> selectedContacts = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedContacts.add(contactList.get(i));
            }
        }
        return selectedContacts;
    }

    public void clearAll() {
        contactList.clear();
        contactListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Contact> getContactList() {
        return contactList;
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
     * Removes all currently selected contacts from the container
     */
    public void removeSelectedContacts() {
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
            if (index < contactListContainer.getChildCount()) {
                contactListContainer.removeViewAt(index);
            }
            if (index < contactList.size()) {
                contactList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifyContactListChanged();
    }

    /**
     * Removes cards from the beginning of the list (newest/latest cards)
     * @param count Number of cards to remove from the beginning
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > contactList.size()) {
            return;
        }

        removeViewsFromBeginning(count);
        removeDataFromBeginning(count);

        notifyContactListChanged();
    }

    /**
     * Removes cards from the end of the list (oldest/earliest cards)
     * @param count Number of cards to remove from the end
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > contactList.size()) {
            return;
        }

        removeViewsFromEnd(count);
        removeDataFromEnd(count);

        notifyContactListChanged();
    }

    /**
     * Adds contact cards to the beginning of the list (newest/latest position)
     * @param contactsToAdd List of contact objects to add at the beginning
     */
    public void addContactCardsToBeginning(List<Contact> contactsToAdd) {
        if (contactsToAdd == null || contactsToAdd.isEmpty()) {
            return;
        }

        // Add in reverse order so they appear in correct order at the beginning
        for (int i = contactsToAdd.size() - 1; i >= 0; i--) {
            Contact contact = contactsToAdd.get(i);
            addContactCardAtPosition(contact, 0);
        }
    }

    public void sortByName(boolean ascending, boolean enableSort) {
        sortContacts(enableSort, (pair1, pair2) -> {
            String name1 = pair1.contact.getName();
            String name2 = pair2.contact.getName();
            return compareNullable(name1, name2, ascending);
        });
    }

    public void sortByFid(boolean ascending, boolean enableSort) {
        sortContacts(enableSort, (pair1, pair2) -> {
            String fid1 = pair1.contact.getFid();
            String fid2 = pair2.contact.getFid();
            return compareNullable(fid1, fid2, ascending);
        });
    }

    public void sortByUpdateHeight(boolean ascending, boolean enableSort) {
        sortContacts(enableSort, (pair1, pair2) -> {
            Long updateHeight1 = pair1.contact.getLastHeight();
            Long updateHeight2 = pair2.contact.getLastHeight();
            return compareNullable(updateHeight1, updateHeight2, ascending);
        });
    }

    private void addContactCardAtPosition(Contact contact, int position) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, contact);
        View boundaryIndicator = cardView.findViewById(R.id.contact_boundary_indicator);
        if (boundaryIndicator != null) {
            boundaryIndicator.setVisibility(View.GONE);
        }

        contactListContainer.addView(cardView, position);
        contactList.add(position, contact);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        int layoutResId = getLayoutResId();
        return LayoutInflater.from(context).inflate(layoutResId, contactListContainer, false);
    }

    private int getLayoutResId() {
        return R.layout.item_contact_card;
//        switch (chooseMode) {
//            case CHOOSE_ONE_RETURN:
//                return R.layout.item_contact_card;
//            case CHOOSE_ONE:
//                return R.layout.item_contact_card_radio;
//            case CHOOSE_MULTI:
//                return R.layout.item_contact_card_checkbox;
//            case WITHOUT_CHOOSE:
//                return R.layout.item_contact_card;
//            default:
//                return R.layout.item_contact_card;
//        }
    }

    private CompoundButton setupCardViewInteractions(View cardView, Contact contact) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, contact);
        setupClickListeners(cardView, contact);
        setupButtons(cardView, contact);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.contact_checkbox);
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
                // Notify so the confirm (tick) button enables/disables with the selection.
                notifyContactListChanged();
            });
        } else {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyContactListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Contact contact) {
        ImageView avatarView = cardView.findViewById(R.id.contact_avatar);
        TextView nameValue = cardView.findViewById(R.id.contact_name_value);
        TextView titlesValue = cardView.findViewById(R.id.contact_titles_value);
        ImageView onChainIcon = cardView.findViewById(R.id.contact_on_chain_icon);

        setupAvatar(avatarView, contact.getFid());
        String name = contact.getName();
        setTextValue(nameValue, name);
        setTitlesValue(titlesValue, contact.getTitles());
        setupOnChainIcon(onChainIcon, contact);
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

    private void setTitlesValue(TextView titlesValue, List<String> titles) {
        String finalStr = listToString(titles);
        titlesValue.setText(finalStr);
    }

    private void setupOnChainIcon(ImageView onChainIcon, Contact contact) {
        Boolean onChain = contact.getOnChain();
        if (onChain != null && onChain) {
            onChainIcon.setImageResource(R.drawable.ic_on_chain);
        } else if (onChain != null) {
            onChainIcon.setImageResource(R.drawable.ic_off_chain);
            onChainIcon.setOnClickListener(v -> {
                if (onOffChainIconClickListener != null) {
                    onOffChainIconClickListener.onOffChainIconClick(contact);
                }
            });
        } else {
            onChainIcon.setImageResource(R.drawable.ic_on_chain_unknown);
        }
    }

    private void setupClickListeners(View cardView, Contact contact) {
        TextView nameValue = cardView.findViewById(R.id.contact_name_value);
        TextView titlesValue = cardView.findViewById(R.id.contact_titles_value);

        // Use onContactClickListener if set (for direct selection mode), otherwise show detail activity
        View.OnClickListener clickListener = v -> {
            if (onContactClickListener != null) {
                onContactClickListener.onContactClick(contact);
            } else {
                showContactActivity(contact);
            }
        };
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, contact);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        if (nameValue != null) {
            nameValue.setOnClickListener(clickListener);
            nameValue.setOnLongClickListener(longPressListener);
        }

        if (titlesValue != null) {
            titlesValue.setOnClickListener(clickListener);
            titlesValue.setOnLongClickListener(longPressListener);
        }
    }

    private View.OnLongClickListener createLongPressListener(View cardView, Contact contact) {
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
                        removeContactCard(cardView, contact);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, contact);
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

    private void removeContactCard(View cardView, Contact contact) {
        contactListContainer.removeView(cardView);
        int index = contactList.indexOf(contact);
        if (index != -1) {
            contactList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyContactListChanged();
    }

    private void setupButtons(View cardView, Contact contact) {
        ImageButton editButton = cardView.findViewById(R.id.contact_edit_button);

        if (editButton != null) {
            if (chooseMode == ChooseMode.WITHOUT_CHOOSE) {
                // Change icon to clear/remove icon
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setOnClickListener(v -> {
                    // Remove the contact from the card list
                    removeContactCard(cardView, contact);
                    // Notify via callback if set
                    if (onContactRemoveListener != null) {
                        onContactRemoveListener.onContactRemove(contact);
                    }
                });
            } else {
                editButton.setOnClickListener(v -> {
                    // Launch UpdateContactActivity
                    android.content.Intent intent = new android.content.Intent(context, com.fc.freer.contact.UpdateContactActivity.class);
                    intent.putExtra("contactDetail", contact.toJson());
                    if (updateContactLauncher != null) {
                        updateContactLauncher.launch(intent);
                    } else {
                        // Fallback to direct startActivity if launcher not set
                        context.startActivity(intent);
                    }
                });
            }
        }
    }

    private void sortContacts(boolean enableSort, Comparator<ContactViewPair> comparator) {
        if (contactList.isEmpty() || !enableSort) {
            return;
        }

        List<ContactViewPair> pairs = createContactViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<ContactViewPair> createContactViewPairs() {
        List<ContactViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < contactList.size(); i++) {
            Contact contact = contactList.get(i);
            View cardView = contactListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new ContactViewPair(contact, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<ContactViewPair> pairs) {
        contactList.clear();
        checkBoxes.clear();
        contactListContainer.removeAllViews();

        for (ContactViewPair pair : pairs) {
            contactList.add(pair.contact);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            contactListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private record ContactViewPair(Contact contact, View cardView, CompoundButton checkBox) {
    }

    private void removeViewsFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            contactListContainer.removeViewAt(0);
        }
    }

    private void removeDataFromBeginning(int count) {
        for (int i = 0; i < count; i++) {
            contactList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
    }

    private void removeViewsFromEnd(int count) {
        for (int i = 0; i < count; i++) {
            contactListContainer.removeViewAt(contactListContainer.getChildCount() - 1);
        }
    }

    private void removeDataFromEnd(int count) {
        int size = contactList.size();
        for (int i = 0; i < count; i++) {
            int index = size - 1 - i;
            contactList.remove(index);
            if (checkBoxes.size() > index) {
                checkBoxes.remove(index);
            }
        }
    }

    public void addContactCardsToBeginningWithBottomRemoval(List<Contact> contactsToAdd,
                                                            int itemsRemovedFromEnd,
                                                            int maxContainerSize) {
        if (contactsToAdd == null || contactsToAdd.isEmpty()) {
            return;
        }

        int currentSize = contactList.size();
        int newItemsCount = contactsToAdd.size();
        int requiredRemoval = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int removalCount = Math.max(requiredRemoval, Math.max(0, itemsRemovedFromEnd));

        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

        clearAllBoundaryIndicators();

        if (removalCount > 0) {
            removeViewsFromEnd(removalCount);
            removeDataFromEnd(removalCount);
        }

        for (int i = contactsToAdd.size() - 1; i >= 0; i--) {
            addContactCardAtPosition(contactsToAdd.get(i), 0);
        }

        int boundaryIndex = Math.min(newItemsCount - 1, contactListContainer.getChildCount() - 1);
        setBoundaryIndicator(boundaryIndex, true);

        if (scrollView != null) {
            scrollView.post(() -> {
                int addedHeight = 0;
                int limit = Math.min(newItemsCount, contactListContainer.getChildCount());
                for (int i = 0; i < limit; i++) {
                    View cardView = contactListContainer.getChildAt(i);
                    if (cardView != null) {
                        addedHeight += cardView.getHeight();
                    }
                }
                scrollView.scrollTo(0, savedScrollY + addedHeight);
            });
        }

        notifyContactListChanged();
    }

    public void addContactCardsToEndWithTopRemoval(List<Contact> contactsToAdd,
                                                   int itemsRemovedFromBeginning,
                                                   int maxContainerSize) {
        if (contactsToAdd == null || contactsToAdd.isEmpty()) {
            return;
        }

        int currentSize = contactList.size();
        int newItemsCount = contactsToAdd.size();
        int requiredRemoval = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int removalCount = Math.max(requiredRemoval, Math.max(0, itemsRemovedFromBeginning));

        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;
        int removedHeight = 0;

        clearAllBoundaryIndicators();

        if (removalCount > 0) {
            int limit = Math.min(removalCount, contactListContainer.getChildCount());
            for (int i = 0; i < limit; i++) {
                View cardView = contactListContainer.getChildAt(i);
                if (cardView != null) {
                    removedHeight += cardView.getHeight();
                }
            }

            removeViewsFromBeginning(removalCount);
            removeDataFromBeginning(removalCount);
        }

        int boundaryIndex = contactListContainer.getChildCount();
        for (Contact contact : contactsToAdd) {
            addContactCard(contact);
        }

        setBoundaryIndicator(boundaryIndex, true);

        if (scrollView != null && removedHeight > 0) {
            final int finalRemovedHeight = removedHeight;
            scrollView.post(() -> {
                int adjusted = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, adjusted);
            });
        }

        notifyContactListChanged();
    }

    public void setBoundaryIndicator(int index, boolean show) {
        if (index < 0 || index >= contactListContainer.getChildCount()) {
            return;
        }

        View cardView = contactListContainer.getChildAt(index);
        if (cardView == null) {
            return;
        }

        View indicator = cardView.findViewById(R.id.contact_boundary_indicator);
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
        for (int i = 0; i < contactListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
        }
    }
}