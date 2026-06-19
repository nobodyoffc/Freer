package com.fc.freer.utils;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.core.content.ContextCompat;

import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.MailManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MailCardContainer {
    private static final String TAG = "MailCardContainer";
    private final Context context;
    private final ViewGroup mailListContainer;
    private final List<Mail> mailList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private final String liveFid;
    private OnMailListChangedListener onMailListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private OnMailClickListener onMailClickListener;
    private OnReplyClickListener onReplyClickListener;
    private OnMailRemoveListener onMailRemoveListener;
    private ActivityResultLauncher<Intent> updateMailLauncher;
    private ScrollView scrollView;

    public interface OnMailListChangedListener {
        void onMailListChanged(List<Mail> updatedMailList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Mail mail);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(Mail mail);
    }

    public interface OnMailClickListener {
        void onMailClick(Mail mail);
    }

    public interface OnReplyClickListener {
        void onReplyClick(Mail mail);
    }

    public interface OnMailRemoveListener {
        void onMailRemove(Mail mail);
    }

    public MailCardContainer(Context context, LinearLayout mailListContainer, ChooseMode chooseMode, String liveFid) {
        this(context, mailListContainer, chooseMode, liveFid, null);
    }

    public MailCardContainer(Context context, LinearLayout mailListContainer, ChooseMode chooseMode, String liveFid, List<String> menuItems) {
        this.context = context;
        this.mailListContainer = mailListContainer;
        this.mailList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.liveFid = liveFid;
        this.menuItems = menuItems;
    }

    public void setOnMailListChangedListener(OnMailListChangedListener listener) {
        this.onMailListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnOffChainIconClickListener(OnOffChainIconClickListener listener) {
        this.onOffChainIconClickListener = listener;
    }

    public void setOnMailClickListener(OnMailClickListener listener) {
        this.onMailClickListener = listener;
    }

    public void setOnReplyClickListener(OnReplyClickListener listener) {
        this.onReplyClickListener = listener;
    }

    public void setOnMailRemoveListener(OnMailRemoveListener listener) {
        this.onMailRemoveListener = listener;
    }

    public void setUpdateMailLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateMailLauncher = launcher;
    }

    public void setScrollView(ScrollView scrollView) {
        this.scrollView = scrollView;
    }

    private void notifyMailListChanged() {
        if (onMailListChangedListener != null) {
            onMailListChangedListener.onMailListChanged(new ArrayList<>(mailList));
        }
    }

    public void addMailCard(Mail mail) {
        addMailCardAtPosition(mail, mailList.size());
    }

    private void showMailDetailActivity(Mail mail) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, mail.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Mail.class.getName());
        context.startActivity(intent);
    }

    public List<Mail> getSelectedMails() {
        List<Mail> selectedMails = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedMails.add(mailList.get(i));
            }
        }
        return selectedMails;
    }

    public void clearAll() {
        mailList.clear();
        mailListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Mail> getMailList() {
        return mailList;
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

    public void removeSelectedMails() {
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
            if (index < mailListContainer.getChildCount()) {
                mailListContainer.removeViewAt(index);
            }
            if (index < mailList.size()) {
                mailList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifyMailListChanged();
    }

    public void removeFromBeginning(int count) {
        if (count <= 0 || count > mailList.size()) {
            return;
        }

        removeViewsFromBeginning(count);
        removeDataFromBeginning(count);

        notifyMailListChanged();
    }

    public void removeFromEnd(int count) {
        if (count <= 0 || count > mailList.size()) {
            return;
        }

        removeViewsFromEnd(count);
        removeDataFromEnd(count);

        notifyMailListChanged();
    }

    public void addMailCardsToBeginning(List<Mail> mailsToAdd) {
        if (mailsToAdd == null || mailsToAdd.isEmpty()) {
            return;
        }

        MailManager.makeNames(mailsToAdd, context);

        for (int i = mailsToAdd.size() - 1; i >= 0; i--) {
            Mail mail = mailsToAdd.get(i);
            addMailCardAtPosition(mail, 0);
        }
    }

    public void addMailCardsToBeginningWithBottomRemoval(List<Mail> mailsToAdd,
                                                         int itemsRemovedFromEnd,
                                                         int maxContainerSize) {
        if (mailsToAdd == null || mailsToAdd.isEmpty()) {
            return;
        }

        MailManager.makeNames(mailsToAdd, context);

        int currentSize = mailList.size();
        int newItemsCount = mailsToAdd.size();
        int requiredRemoval = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int removalCount = Math.max(requiredRemoval, Math.max(0, itemsRemovedFromEnd));

        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;

        clearAllBoundaryIndicators();

        if (removalCount > 0) {
            removeViewsFromEnd(removalCount);
            removeDataFromEnd(removalCount);
        }

        for (int i = mailsToAdd.size() - 1; i >= 0; i--) {
            addMailCardAtPosition(mailsToAdd.get(i), 0);
        }

        int boundaryIndex = Math.min(newItemsCount - 1, mailListContainer.getChildCount() - 1);
        setBoundaryIndicator(boundaryIndex, true);

        if (scrollView != null) {
            scrollView.post(() -> {
                int addedHeight = 0;
                int limit = Math.min(newItemsCount, mailListContainer.getChildCount());
                for (int i = 0; i < limit; i++) {
                    View cardView = mailListContainer.getChildAt(i);
                    if (cardView != null) {
                        addedHeight += cardView.getHeight();
                    }
                }
                scrollView.scrollTo(0, savedScrollY + addedHeight);
            });
        }

        notifyMailListChanged();
    }

    public void addMailCardsToEndWithTopRemoval(List<Mail> mailsToAdd,
                                                int itemsRemovedFromBeginning,
                                                int maxContainerSize) {
        if (mailsToAdd == null || mailsToAdd.isEmpty()) {
            return;
        }

        MailManager.makeNames(mailsToAdd, context);

        int currentSize = mailList.size();
        int newItemsCount = mailsToAdd.size();
        int requiredRemoval = Math.max(0, currentSize + newItemsCount - maxContainerSize);
        int removalCount = Math.max(requiredRemoval, Math.max(0, itemsRemovedFromBeginning));

        final int savedScrollY = scrollView != null ? scrollView.getScrollY() : 0;
        int removedHeight = 0;

        clearAllBoundaryIndicators();

        if (removalCount > 0) {
            int limit = Math.min(removalCount, mailListContainer.getChildCount());
            for (int i = 0; i < limit; i++) {
                View cardView = mailListContainer.getChildAt(i);
                if (cardView != null) {
                    removedHeight += cardView.getHeight();
                }
            }

            removeViewsFromBeginning(removalCount);
            removeDataFromBeginning(removalCount);
        }

        int boundaryIndex = mailListContainer.getChildCount();
        for (Mail mail : mailsToAdd) {
            addMailCard(mail);
        }

        setBoundaryIndicator(boundaryIndex, true);

        if (scrollView != null && removedHeight > 0) {
            final int finalRemovedHeight = removedHeight;
            scrollView.post(() -> {
                int adjusted = Math.max(0, savedScrollY - finalRemovedHeight);
                scrollView.scrollTo(0, adjusted);
            });
        }

        notifyMailListChanged();
    }

    public void clearAllBoundaryIndicators() {
        for (int i = 0; i < mailListContainer.getChildCount(); i++) {
            setBoundaryIndicator(i, false);
        }
    }

    public void setBoundaryIndicator(int index, boolean show) {
        if (index < 0 || index >= mailListContainer.getChildCount()) {
            return;
        }

        View cardView = mailListContainer.getChildAt(index);
        Mail mail = index < mailList.size() ? mailList.get(index) : null;

        if (cardView == null || mail == null) {
            return;
        }

        View indicator = cardView.findViewById(R.id.mail_unread_indicator);
        if (indicator == null) {
            return;
        }

        if (show) {
            indicator.setVisibility(View.VISIBLE);
            indicator.setBackgroundColor(ContextCompat.getColor(context, R.color.warning));
            indicator.setTag(R.id.mail_unread_indicator, Boolean.TRUE);
            indicator.bringToFront();
            View parent = (View) indicator.getParent();
            if (parent != null) {
                parent.invalidate();
            } else {
                indicator.invalidate();
            }
        } else {
            boolean isSender = liveFid != null && liveFid.equals(mail.getFrom());
            updateUnreadIndicator(indicator, mail, isSender);
        }
    }

    public void sortBySender(boolean ascending, boolean enableSort) {
        sortMails(enableSort, (pair1, pair2) -> {
            String sender1 = pair1.mail.getFrom();
            String sender2 = pair2.mail.getFrom();
            return compareNullable(sender1, sender2, ascending);
        });
    }

    public void sortByRecipient(boolean ascending, boolean enableSort) {
        sortMails(enableSort, (pair1, pair2) -> {
            String recipient1 = pair1.mail.getTo();
            String recipient2 = pair2.mail.getTo();
            return compareNullable(recipient1, recipient2, ascending);
        });
    }

    public void sortByBirthTime(boolean ascending, boolean enableSort) {
        sortMails(enableSort, (pair1, pair2) -> {
            Long birthTime1 = pair1.mail.getBirthTime();
            Long birthTime2 = pair2.mail.getBirthTime();
            return compareNullable(birthTime1, birthTime2, ascending);
        });
    }

    private void addMailCardAtPosition(Mail mail, int position) {
        mail.makeName();
        View cardView = createCardView(mail);
        CompoundButton checkBox = setupCardViewInteractions(cardView, mail);

        mailListContainer.addView(cardView, position);
        mailList.add(position, mail);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView(Mail mail) {
        int layoutResId = getLayoutResId(mail);
        return LayoutInflater.from(context).inflate(layoutResId, mailListContainer, false);
    }

    private int getLayoutResId(Mail mail) {
        if (liveFid != null && liveFid.equals(mail.getTo())) {
            return R.layout.item_mail_card_left_avatar;
        } else {
            return R.layout.item_mail_card_right_avatar;
        }
//        switch (chooseMode) {
//            case CHOOSE_ONE_RETURN:
//                return R.layout.item_mail_card;
//            case CHOOSE_ONE:
//                return R.layout.item_mail_card_radio;
//            case CHOOSE_MULTI:
//                // If recipient equals live FID, use standard checkbox layout
//                // Otherwise use right avatar layout
//                if (liveFid != null && liveFid.equals(mail.getTo())) {
//                    return R.layout.item_mail_card_checkbox;
//                } else {
//                    return R.layout.item_mail_card_checkbox_right_avatar;
//                }
//            case WITHOUT_CHOOSE:
//                return R.layout.item_mail_card;
//            default:
//                return R.layout.item_mail_card;
//        }
    }

    private CompoundButton setupCardViewInteractions(View cardView, Mail mail) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, mail);
        setupClickListeners(cardView, mail);
        setupButtons(cardView, mail);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        CompoundButton checkBox = cardView.findViewById(R.id.mail_checkbox);
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE || chooseMode == ChooseMode.WITHOUT_CHOOSE_WITH_DELETE) {
            checkBox.setVisibility(View.GONE);
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
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyMailListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Mail mail) {
        ImageView avatarView = cardView.findViewById(R.id.mail_avatar);
        TextView fidValue = cardView.findViewById(R.id.mail_fid_value);
        TextView contentValue = cardView.findViewById(R.id.mail_content_value);
        TextView timeValue = cardView.findViewById(R.id.mail_time_value);
        ImageView onChainIcon = cardView.findViewById(R.id.mail_on_chain_icon);
        View unreadIndicator = cardView.findViewById(R.id.mail_unread_indicator);

        boolean isSender = liveFid != null && liveFid.equals(mail.getFrom());

        if (isSender) {
            // Show recipient info
            String recipient = mail.getToName();
            if (recipient != null) {
                recipient = StringUtils.omitMiddle(recipient, 15);
            }
            setTextValue(fidValue, recipient);
            setupAvatar(avatarView, mail.getTo());
        } else {
            // Show sender info
            String sender = mail.getFromName();
            if (sender != null) {
                sender = StringUtils.omitMiddle(sender, 15);
            }
            setTextValue(fidValue, sender);
            setupAvatar(avatarView, mail.getFrom());
        }

        updateUnreadIndicator(unreadIndicator, mail, isSender);
        setTextValue(contentValue, mail.getContent());
        setTimeValue(timeValue, mail.getBirthTime());
        setupOnChainIcon(onChainIcon, mail, isSender);
    }

    private void updateUnreadIndicator(View indicator, Mail mail, boolean isSender) {
        if (indicator == null) {
            return;
        }

        boolean isUnread = !isSender && Boolean.TRUE.equals(mail.getUnread());
        int unreadColor = ContextCompat.getColor(context, android.R.color.holo_red_light);

        indicator.setTag(R.id.mail_unread_indicator, null);
        indicator.setBackgroundColor(unreadColor);
        indicator.setVisibility(isUnread ? View.VISIBLE : View.GONE);
        if (isUnread) {
            indicator.bringToFront();
            View parent = (View) indicator.getParent();
            if (parent != null) {
                parent.invalidate();
            } else {
                indicator.invalidate();
            }
        }
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

    private void setTimeValue(TextView timeValue, Long birthTime) {
        if (birthTime != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault());
            timeValue.setText(sdf.format(new Date(birthTime * 1000)));
        } else {
            timeValue.setText("");
        }
    }

    private void setupOnChainIcon(ImageView onChainIcon, Mail mail, boolean isSender) {
        Boolean onChain = mail.getOnChain();
        if (onChain != null && onChain) {
            onChainIcon.setImageResource(R.drawable.ic_on_chain);
        } else if (onChain != null) {
            onChainIcon.setImageResource(R.drawable.ic_off_chain);
            if (isSender) {
                onChainIcon.setOnClickListener(v -> {
                    if (onOffChainIconClickListener != null) {
                        onOffChainIconClickListener.onOffChainIconClick(mail);
                    }
                });
            }
        } else {
            onChainIcon.setImageResource(R.drawable.ic_on_chain_unknown);
        }
    }

    private void setupClickListeners(View cardView, Mail mail) {
        TextView fidValue = cardView.findViewById(R.id.mail_fid_value);
        TextView contentValue = cardView.findViewById(R.id.mail_content_value);

        View.OnClickListener clickListener = v -> {
            if (onMailClickListener != null) {
                onMailClickListener.onMailClick(mail);
            } else {
                showMailDetailActivity(mail);
            }
        };
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, mail);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        if (fidValue != null) {
            fidValue.setOnClickListener(clickListener);
            fidValue.setOnLongClickListener(longPressListener);
        }

        if (contentValue != null) {
            contentValue.setOnClickListener(clickListener);
            contentValue.setOnLongClickListener(longPressListener);
        }
    }

    private View.OnLongClickListener createLongPressListener(View cardView, Mail mail) {
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
                        removeMailCard(cardView, mail);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, mail);
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

    private void removeMailCard(View cardView, Mail mail) {
        mailListContainer.removeView(cardView);
        int index = mailList.indexOf(mail);
        if (index != -1) {
            mailList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyMailListChanged();
    }

    private void setupButtons(View cardView, Mail mail) {
        ImageButton editButton = cardView.findViewById(R.id.mail_edit_button);
        ImageButton deleteButton = cardView.findViewById(R.id.mail_delete_button);

        if (chooseMode == ChooseMode.WITHOUT_CHOOSE_WITH_DELETE) {
            // Show delete button for WITHOUT_CHOOSE_WITH_DELETE mode
            if (deleteButton != null) {
                deleteButton.setVisibility(View.VISIBLE);
                editButton.setVisibility(View.GONE);
                deleteButton.setOnClickListener(v -> {
                    // Remove the mail from the card list
                    removeMailCard(cardView, mail);
                    // Notify via callback if set
                    if (onMailRemoveListener != null) {
                        onMailRemoveListener.onMailRemove(mail);
                    }
                });
            }
            // Hide edit button in this mode
            if (editButton != null) {
                editButton.setVisibility(View.GONE);
            }
        } else if (chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            // Legacy behavior: use edit button as remove button
            if (editButton != null) {
                editButton.setVisibility(View.VISIBLE);
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setContentDescription("Remove");
                editButton.setOnClickListener(v -> {
                    // Remove the mail from the card list
                    removeMailCard(cardView, mail);
                    // Notify via callback if set
                    if (onMailRemoveListener != null) {
                        onMailRemoveListener.onMailRemove(mail);
                    }
                });
            }
            // Hide delete button in this mode
            if (deleteButton != null) {
                deleteButton.setVisibility(View.GONE);
            }
        } else {
            // Hide delete button for other modes
            if (deleteButton != null) {
                deleteButton.setVisibility(View.GONE);
            }

            // Handle edit button for other modes
            if (editButton != null) {
                boolean isSender = liveFid != null && liveFid.equals(mail.getFrom());
                Boolean onChain = mail.getOnChain();

                if (isSender && onChain != null && !onChain) {
                    // Show edit button for sender's off-chain mails
                    editButton.setVisibility(View.VISIBLE);
                    editButton.setImageResource(R.drawable.ic_edit);
                    editButton.setContentDescription("Edit");
                    editButton.setOnClickListener(v -> {
                        // Launch UpdateMailActivity or handle edit action
                        if (onMenuItemClickListener != null) {
                            onMenuItemClickListener.onMenuItemClick("Edit", mail);
                        }
                    });
                } else if (onChain != null && onChain) {
                    // Show reply button for on-chain mails (both sent and received)
                    editButton.setVisibility(View.VISIBLE);
                    editButton.setImageResource(R.drawable.ic_reply);
                    editButton.setContentDescription("Reply");
                    editButton.setOnClickListener(v -> {
                        if (onReplyClickListener != null) {
                            onReplyClickListener.onReplyClick(mail);
                        }
                    });
                } else {
                    editButton.setVisibility(View.GONE);
                }
            }
        }
    }

    private void sortMails(boolean enableSort, Comparator<MailViewPair> comparator) {
        if (mailList.isEmpty() || !enableSort) {
            return;
        }

        List<MailViewPair> pairs = createMailViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<MailViewPair> createMailViewPairs() {
        List<MailViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < mailList.size(); i++) {
            Mail mail = mailList.get(i);
            View cardView = mailListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new MailViewPair(mail, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<MailViewPair> pairs) {
        mailList.clear();
        checkBoxes.clear();
        mailListContainer.removeAllViews();

        for (MailViewPair pair : pairs) {
            mailList.add(pair.mail);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            mailListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private void removeViewsFromBeginning(int count) {
        for (int i = 0; i < count && mailListContainer.getChildCount() > 0; i++) {
            mailListContainer.removeViewAt(0);
        }
    }

    private void removeDataFromBeginning(int count) {
        for (int i = 0; i < count && !mailList.isEmpty(); i++) {
            mailList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
    }

    private void removeViewsFromEnd(int count) {
        for (int i = 0; i < count && mailListContainer.getChildCount() > 0; i++) {
            mailListContainer.removeViewAt(mailListContainer.getChildCount() - 1);
        }
    }

    private void removeDataFromEnd(int count) {
        for (int i = 0; i < count && !mailList.isEmpty(); i++) {
            int index = mailList.size() - 1;
            mailList.remove(index);
            if (checkBoxes.size() > index) {
                checkBoxes.remove(index);
            }
        }
    }

    private record MailViewPair(Mail mail, View cardView, CompoundButton checkBox) {
    }
}