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

import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class ProtocolCardContainer {
    private static final String TAG = "ProtocolCardContainer";
    private final Context context;
    private final ViewGroup protocolListContainer;
    private final List<Protocol> protocolList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnProtocolListChangedListener onProtocolListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnProtocolClickListener onProtocolClickListener;
    private OnProtocolRemoveListener onProtocolRemoveListener;
    private OnEditIconClickListener onEditIconClickListener;
    private OnRateIconClickListener onRateIconClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private OnClearIconClickListener onClearIconClickListener;
    private ActivityResultLauncher<Intent> updateProtocolLauncher;
    private boolean hideEditButton = false;
    private boolean showClearButton = false;

    public interface OnProtocolListChangedListener {
        void onProtocolListChanged(List<Protocol> updatedProtocolList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Protocol protocol);
    }

    public interface OnProtocolClickListener {
        void onProtocolClick(Protocol protocol);
    }

    public interface OnProtocolRemoveListener {
        void onProtocolRemove(Protocol protocol);
    }

    public interface OnEditIconClickListener {
        void onEditIconClick(Protocol protocol);
    }

    public interface OnRateIconClickListener {
        void onRateIconClick(Protocol protocol);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(Protocol protocol);
    }

    public interface OnClearIconClickListener {
        void onClearIconClick(Protocol protocol);
    }

    public ProtocolCardContainer(Context context, LinearLayout protocolListContainer, ChooseMode chooseMode) {
        this(context, protocolListContainer, chooseMode, null);
    }

    public ProtocolCardContainer(Context context, LinearLayout protocolListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.protocolListContainer = protocolListContainer;
        this.protocolList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnProtocolListChangedListener(OnProtocolListChangedListener listener) {
        this.onProtocolListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnProtocolClickListener(OnProtocolClickListener listener) {
        this.onProtocolClickListener = listener;
    }

    public void setOnProtocolRemoveListener(OnProtocolRemoveListener listener) {
        this.onProtocolRemoveListener = listener;
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

    public void setUpdateProtocolLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateProtocolLauncher = launcher;
    }

    private void notifyProtocolListChanged() {
        if (onProtocolListChangedListener != null) {
            onProtocolListChangedListener.onProtocolListChanged(new ArrayList<>(protocolList));
        }
    }

    @Nullable
    public Map<String, String> getCidMap(List<Protocol> protocolList, Context context) {
        List<String> fidList = new ArrayList<>();
        for (Protocol protocol : protocolList) {
            fidList.add(protocol.getOwner());
        }
        Map<String, String> cidMap = null;
        if (!fidList.isEmpty()) {
            CidFidManager cidFidManager = CidFidManager.getInstance(context);
            cidMap = cidFidManager.getCidsByFids(fidList);
        }
        return cidMap;
    }

    public void addProtocolCard(Protocol protocol, Map<String, String> cidMap) {
        addProtocolCardAtPosition(protocol, protocolList.size(), cidMap);
    }

    private void showProtocolActivity(Protocol protocol) {
        Intent intent = new Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, new com.google.gson.Gson().toJson(protocol));
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Protocol.class.getName());
        context.startActivity(intent);
    }

    public List<Protocol> getSelectedProtocols() {
        List<Protocol> selectedProtocols = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedProtocols.add(protocolList.get(i));
            }
        }
        return selectedProtocols;
    }

    public void clearAll() {
        protocolList.clear();
        protocolListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Protocol> getProtocolList() {
        return protocolList;
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

    public void removeSelectedProtocols() {
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
            if (index < protocolListContainer.getChildCount()) {
                protocolListContainer.removeViewAt(index);
            }
            if (index < protocolList.size()) {
                protocolList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifyProtocolListChanged();
    }

    public void removeFromBeginning(int count) {
        if (count <= 0 || count > protocolList.size()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            protocolListContainer.removeViewAt(0);
        }

        for (int i = 0; i < count; i++) {
            protocolList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }

        notifyProtocolListChanged();
    }

    public void removeFromEnd(int count) {
        if (count <= 0 || count > protocolList.size()) {
            return;
        }

        int size = protocolList.size();
        for (int i = 0; i < count; i++) {
            protocolListContainer.removeViewAt(protocolListContainer.getChildCount() - 1);
        }

        for (int i = 0; i < count; i++) {
            protocolList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }

        notifyProtocolListChanged();
    }

    public void addProtocolCardsToBeginning(List<Protocol> protocolsToAdd) {
        if (protocolsToAdd == null || protocolsToAdd.isEmpty()) {
            return;
        }
        Map<String, String> cidMap = getCidMap(protocolsToAdd, context);
        for (int i = protocolsToAdd.size() - 1; i >= 0; i--) {
            Protocol protocol = protocolsToAdd.get(i);
            addProtocolCardAtPosition(protocol, 0, cidMap);
        }
    }

    public void addProtocolCardsToBeginningWithBottomRemoval(List<Protocol> protocolsToAdd, int maxSize) {
        if (protocolsToAdd == null || protocolsToAdd.isEmpty()) {
            return;
        }

        addProtocolCardsToBeginning(protocolsToAdd);

        int currentSize = protocolList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromEnd(removeCount);
        } else {
            notifyProtocolListChanged();
        }
    }

    public void addProtocolCardsToEndWithTopRemoval(List<Protocol> protocolsToAdd, int maxSize) {
        if (protocolsToAdd == null || protocolsToAdd.isEmpty()) {
            return;
        }

        Map<String, String> cidMap = getCidMap(protocolsToAdd, context);
        for (Protocol protocol : protocolsToAdd) {
            addProtocolCardAtPosition(protocol, protocolList.size(), cidMap);
        }

        int currentSize = protocolList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromBeginning(removeCount);
        } else {
            notifyProtocolListChanged();
        }
    }

    public void sortByName(boolean ascending, boolean enableSort) {
        sortProtocols(enableSort, (pair1, pair2) -> {
            String name1 = pair1.protocol.getName();
            String name2 = pair2.protocol.getName();
            return compareNullable(name1, name2, ascending);
        });
    }

    public void sortByOwner(boolean ascending, boolean enableSort) {
        sortProtocols(enableSort, (pair1, pair2) -> {
            String owner1 = pair1.protocol.getOwner();
            String owner2 = pair2.protocol.getOwner();
            return compareNullable(owner1, owner2, ascending);
        });
    }

    public void sortByLastTime(boolean ascending, boolean enableSort) {
        sortProtocols(enableSort, (pair1, pair2) -> {
            Long lastTime1 = pair1.protocol.getLastTime();
            Long lastTime2 = pair2.protocol.getLastTime();
            return compareNullable(lastTime1, lastTime2, ascending);
        });
    }

    public void sortByTCdd(boolean ascending, boolean enableSort) {
        sortProtocols(enableSort, (pair1, pair2) -> {
            Long tCdd1 = pair1.protocol.gettCdd();
            Long tCdd2 = pair2.protocol.gettCdd();
            return compareNullable(tCdd1, tCdd2, ascending);
        });
    }

    public void sortByTRate(boolean ascending, boolean enableSort) {
        sortProtocols(enableSort, (pair1, pair2) -> {
            Float tRate1 = pair1.protocol.gettRate();
            Float tRate2 = pair2.protocol.gettRate();
            return compareNullable(tRate1, tRate2, ascending);
        });
    }

    private void addProtocolCardAtPosition(Protocol protocol, int position, Map<String, String> cidMap) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, protocol, cidMap);

        protocolListContainer.addView(cardView, position);
        protocolList.add(position, protocol);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        return LayoutInflater.from(context).inflate(R.layout.item_protocol_card, protocolListContainer, false);
    }

    private CompoundButton setupCardViewInteractions(View cardView, Protocol protocol, Map<String, String> cidMap) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, protocol, cidMap);
        setupClickListeners(cardView, protocol);
        setupButtons(cardView, protocol);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.protocol_checkbox);
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
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyProtocolListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Protocol protocol, Map<String, String> cidMap) {
        ImageView ownerAvatarView = cardView.findViewById(R.id.protocol_owner_avatar);
        TextView nameValue = cardView.findViewById(R.id.protocol_name_value);
        TextView ownerValue = cardView.findViewById(R.id.protocol_owner_value);
        TextView typeValue = cardView.findViewById(R.id.protocol_type_value);
        TextView tCddValue = cardView.findViewById(R.id.protocol_tcdd_value);
        TextView tRateValue = cardView.findViewById(R.id.protocol_trate_value);

        setupAvatar(ownerAvatarView, protocol.getOwner());
        setTextValue(nameValue, protocol.getName());

        if (cidMap != null && cidMap.get(protocol.getOwner()) != null) {
            setTextValue(ownerValue, cidMap.get(protocol.getOwner()));
        } else {
            setTextValue(ownerValue, protocol.getOwner());
        }

        // Format type
        if (protocol.getType() != null) {
            setTextValue(typeValue, protocol.getType());
        } else {
            setTextValue(typeValue, "");
        }

        // Format tCdd
        if (protocol.gettCdd() != null) {
            setTextValue(tCddValue, formatNumber(protocol.gettCdd()));
        } else {
            setTextValue(tCddValue, "0");
        }

        // Format tRate
        if (protocol.gettRate() != null) {
            setTextValue(tRateValue, String.format("%.1f", protocol.gettRate()));
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

    private void setupClickListeners(View cardView, Protocol protocol) {
        TextView nameValue = cardView.findViewById(R.id.protocol_name_value);
        TextView ownerValue = cardView.findViewById(R.id.protocol_owner_value);

        View.OnClickListener clickListener = v -> {
            if (onProtocolClickListener != null) {
                onProtocolClickListener.onProtocolClick(protocol);
            } else {
                showProtocolActivity(protocol);
            }
        };
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, protocol);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        if (nameValue != null) {
            nameValue.setOnClickListener(clickListener);
            nameValue.setOnLongClickListener(longPressListener);
        }

        if (ownerValue != null) {
            ownerValue.setOnClickListener(clickListener);
            ownerValue.setOnLongClickListener(longPressListener);
        }
    }

    private View.OnLongClickListener createLongPressListener(View cardView, Protocol protocol) {
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
                        removeProtocolCard(cardView, protocol);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, protocol);
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

    private void removeProtocolCard(View cardView, Protocol protocol) {
        protocolListContainer.removeView(cardView);
        int index = protocolList.indexOf(protocol);
        if (index != -1) {
            protocolList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyProtocolListChanged();
    }

    public boolean removeProtocolById(String protocolId) {
        if (protocolId == null) return false;

        for (int i = 0; i < protocolList.size(); i++) {
            Protocol protocol = protocolList.get(i);
            if (protocolId.equals(protocol.getId())) {
                if (i < protocolListContainer.getChildCount()) {
                    protocolListContainer.removeViewAt(i);
                }
                protocolList.remove(i);
                if (i < checkBoxes.size()) {
                    checkBoxes.remove(i);
                }
                notifyProtocolListChanged();
                return true;
            }
        }
        return false;
    }

    public boolean updateProtocolCard(Protocol updatedProtocol) {
        if (updatedProtocol == null || updatedProtocol.getId() == null) return false;

        for (int i = 0; i < protocolList.size(); i++) {
            Protocol protocol = protocolList.get(i);
            if (updatedProtocol.getId().equals(protocol.getId())) {
                protocolList.set(i, updatedProtocol);

                View cardView = protocolListContainer.getChildAt(i);
                if (cardView != null) {
                    Map<String, String> cidMap = getCidMap(java.util.Collections.singletonList(updatedProtocol), context);
                    setupCardData(cardView, updatedProtocol, cidMap);
                    setupButtons(cardView, updatedProtocol);
                }

                notifyProtocolListChanged();
                return true;
            }
        }
        return false;
    }

    private void setupButtons(View cardView, Protocol protocol) {
        ImageButton chainStatusButton = cardView.findViewById(R.id.protocol_chain_status_button);
        ImageButton editButton = cardView.findViewById(R.id.protocol_edit_button);
        ImageButton rateButton = cardView.findViewById(R.id.protocol_rate_button);

        // Setup chain status button
        if (chainStatusButton != null) {
            Boolean onChain = protocol.getOnChain();
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            
            if (liveFid != null && liveFid.equals(protocol.getOwner())) {
                chainStatusButton.setVisibility(VISIBLE);
                
                if (Boolean.TRUE.equals(onChain)) {
                    chainStatusButton.setImageResource(R.drawable.ic_on_chain);
                    chainStatusButton.setOnClickListener(null);
                    chainStatusButton.setClickable(false);
                } else if (onChain == null) {
                    chainStatusButton.setImageResource(R.drawable.ic_on_chain_unknown);
                    chainStatusButton.setOnClickListener(null);
                    chainStatusButton.setClickable(false);
                } else {
                    chainStatusButton.setImageResource(R.drawable.ic_off_chain);
                    chainStatusButton.setClickable(true);
                    chainStatusButton.setOnClickListener(v -> {
                        if (onOffChainIconClickListener != null) {
                            onOffChainIconClickListener.onOffChainIconClick(protocol);
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
                editButton.setVisibility(VISIBLE);
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setContentDescription(context.getString(R.string.remove));
                editButton.setOnClickListener(v -> {
                    if (onClearIconClickListener != null) {
                        onClearIconClickListener.onClearIconClick(protocol);
                    } else {
                        removeProtocolCard(cardView, protocol);
                    }
                });
            } else {
                FidManager fidManager = FidManager.getInstance();
                String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
                if (liveFid != null && liveFid.equals(protocol.getOwner())) {
                    editButton.setVisibility(VISIBLE);
                    editButton.setImageResource(R.drawable.ic_edit);
                    editButton.setContentDescription(context.getString(R.string.edit));
                    editButton.setOnClickListener(v -> {
                        if (onEditIconClickListener != null) {
                            onEditIconClickListener.onEditIconClick(protocol);
                        }
                    });
                } else {
                    editButton.setVisibility(GONE);
                }
            }
        }

        if (rateButton != null) {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (liveFid != null && liveFid.equals(protocol.getOwner())) {
                rateButton.setVisibility(GONE);
            } else {
                rateButton.setVisibility(VISIBLE);
                rateButton.setOnClickListener(v -> {
                    if (onRateIconClickListener != null) {
                        onRateIconClickListener.onRateIconClick(protocol);
                    }
                });
            }
        }
    }

    private void sortProtocols(boolean enableSort, Comparator<ProtocolViewPair> comparator) {
        if (protocolList.isEmpty() || !enableSort) {
            return;
        }

        List<ProtocolViewPair> pairs = createProtocolViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<ProtocolViewPair> createProtocolViewPairs() {
        List<ProtocolViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < protocolList.size(); i++) {
            Protocol protocol = protocolList.get(i);
            View cardView = protocolListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new ProtocolViewPair(protocol, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<ProtocolViewPair> pairs) {
        protocolList.clear();
        checkBoxes.clear();
        protocolListContainer.removeAllViews();

        for (ProtocolViewPair pair : pairs) {
            protocolList.add(pair.protocol);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            protocolListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private record ProtocolViewPair(Protocol protocol, View cardView, CompoundButton checkBox) {
    }
}
