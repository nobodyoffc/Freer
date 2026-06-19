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

import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class CodeCardContainer {
    private static final String TAG = "CodeCardContainer";
    private final Context context;
    private final ViewGroup codeListContainer;
    private final List<Code> codeList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnCodeListChangedListener onCodeListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnCodeClickListener onCodeClickListener;
    private OnCodeRemoveListener onCodeRemoveListener;
    private OnEditIconClickListener onEditIconClickListener;
    private OnRateIconClickListener onRateIconClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private OnClearIconClickListener onClearIconClickListener;
    private ActivityResultLauncher<Intent> updateCodeLauncher;
    private boolean hideEditButton = false;
    private boolean showClearButton = false;

    public interface OnCodeListChangedListener {
        void onCodeListChanged(List<Code> updatedCodeList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Code code);
    }

    public interface OnCodeClickListener {
        void onCodeClick(Code code);
    }

    public interface OnCodeRemoveListener {
        void onCodeRemove(Code code);
    }

    public interface OnEditIconClickListener {
        void onEditIconClick(Code code);
    }

    public interface OnRateIconClickListener {
        void onRateIconClick(Code code);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(Code code);
    }

    public interface OnClearIconClickListener {
        void onClearIconClick(Code code);
    }

    public CodeCardContainer(Context context, LinearLayout codeListContainer, ChooseMode chooseMode) {
        this(context, codeListContainer, chooseMode, null);
    }

    public CodeCardContainer(Context context, LinearLayout codeListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.codeListContainer = codeListContainer;
        this.codeList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnCodeListChangedListener(OnCodeListChangedListener listener) {
        this.onCodeListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnCodeClickListener(OnCodeClickListener listener) {
        this.onCodeClickListener = listener;
    }

    public void setOnCodeRemoveListener(OnCodeRemoveListener listener) {
        this.onCodeRemoveListener = listener;
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

    public void setUpdateCodeLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateCodeLauncher = launcher;
    }

    private void notifyCodeListChanged() {
        if (onCodeListChangedListener != null) {
            onCodeListChangedListener.onCodeListChanged(new ArrayList<>(codeList));
        }
    }

    @Nullable
    public Map<String, String> getCidMap(List<Code> codeList, Context context) {
        List<String> fidList = new ArrayList<>();
        for (Code code : codeList) {
            fidList.add(code.getOwner());
        }
        Map<String, String> cidMap = null;
        if (!fidList.isEmpty()) {
            CidFidManager cidFidManager = CidFidManager.getInstance(context);
            cidMap = cidFidManager.getCidsByFids(fidList);
        }
        return cidMap;
    }

    public void addCodeCard(Code code, Map<String, String> cidMap) {
        addCodeCardAtPosition(code, codeList.size(), cidMap);
    }

    private void showCodeActivity(Code code) {
        Intent intent = new Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, new com.google.gson.Gson().toJson(code));
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Code.class.getName());
        context.startActivity(intent);
    }

    public List<Code> getSelectedCodes() {
        List<Code> selectedCodes = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedCodes.add(codeList.get(i));
            }
        }
        return selectedCodes;
    }

    public void clearAll() {
        codeList.clear();
        codeListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Code> getCodeList() {
        return codeList;
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

    public void removeSelectedCodes() {
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
            if (index < codeListContainer.getChildCount()) {
                codeListContainer.removeViewAt(index);
            }
            if (index < codeList.size()) {
                codeList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifyCodeListChanged();
    }

    public void removeFromBeginning(int count) {
        if (count <= 0 || count > codeList.size()) {
            return;
        }

        for (int i = 0; i < count; i++) {
            codeListContainer.removeViewAt(0);
        }

        for (int i = 0; i < count; i++) {
            codeList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }

        notifyCodeListChanged();
    }

    public void removeFromEnd(int count) {
        if (count <= 0 || count > codeList.size()) {
            return;
        }

        int size = codeList.size();
        for (int i = 0; i < count; i++) {
            codeListContainer.removeViewAt(codeListContainer.getChildCount() - 1);
        }

        for (int i = 0; i < count; i++) {
            codeList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }

        notifyCodeListChanged();
    }

    public void addCodeCardsToBeginning(List<Code> codesToAdd) {
        if (codesToAdd == null || codesToAdd.isEmpty()) {
            return;
        }
        Map<String, String> cidMap = getCidMap(codesToAdd, context);
        for (int i = codesToAdd.size() - 1; i >= 0; i--) {
            Code code = codesToAdd.get(i);
            addCodeCardAtPosition(code, 0, cidMap);
        }
    }

    public void addCodeCardsToBeginningWithBottomRemoval(List<Code> codesToAdd, int maxSize) {
        if (codesToAdd == null || codesToAdd.isEmpty()) {
            return;
        }

        addCodeCardsToBeginning(codesToAdd);

        int currentSize = codeList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromEnd(removeCount);
        } else {
            notifyCodeListChanged();
        }
    }

    public void addCodeCardsToEndWithTopRemoval(List<Code> codesToAdd, int maxSize) {
        if (codesToAdd == null || codesToAdd.isEmpty()) {
            return;
        }

        Map<String, String> cidMap = getCidMap(codesToAdd, context);
        for (Code code : codesToAdd) {
            addCodeCardAtPosition(code, codeList.size(), cidMap);
        }

        int currentSize = codeList.size();
        int removeCount = Math.max(0, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromBeginning(removeCount);
        } else {
            notifyCodeListChanged();
        }
    }

    public void sortByName(boolean ascending, boolean enableSort) {
        sortCodes(enableSort, (pair1, pair2) -> {
            String name1 = pair1.code.getName();
            String name2 = pair2.code.getName();
            return compareNullable(name1, name2, ascending);
        });
    }

    public void sortByOwner(boolean ascending, boolean enableSort) {
        sortCodes(enableSort, (pair1, pair2) -> {
            String owner1 = pair1.code.getOwner();
            String owner2 = pair2.code.getOwner();
            return compareNullable(owner1, owner2, ascending);
        });
    }

    public void sortByLastTime(boolean ascending, boolean enableSort) {
        sortCodes(enableSort, (pair1, pair2) -> {
            Long lastTime1 = pair1.code.getLastTime();
            Long lastTime2 = pair2.code.getLastTime();
            return compareNullable(lastTime1, lastTime2, ascending);
        });
    }

    public void sortByTCdd(boolean ascending, boolean enableSort) {
        sortCodes(enableSort, (pair1, pair2) -> {
            Long tCdd1 = pair1.code.gettCdd();
            Long tCdd2 = pair2.code.gettCdd();
            return compareNullable(tCdd1, tCdd2, ascending);
        });
    }

    public void sortByTRate(boolean ascending, boolean enableSort) {
        sortCodes(enableSort, (pair1, pair2) -> {
            Float tRate1 = pair1.code.gettRate();
            Float tRate2 = pair2.code.gettRate();
            return compareNullable(tRate1, tRate2, ascending);
        });
    }

    private void addCodeCardAtPosition(Code code, int position, Map<String, String> cidMap) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, code, cidMap);

        codeListContainer.addView(cardView, position);
        codeList.add(position, code);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        return LayoutInflater.from(context).inflate(R.layout.item_code_card, codeListContainer, false);
    }

    private CompoundButton setupCardViewInteractions(View cardView, Code code, Map<String, String> cidMap) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, code, cidMap);
        setupClickListeners(cardView, code);
        setupButtons(cardView, code);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.code_checkbox);
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
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyCodeListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Code code, Map<String, String> cidMap) {
        ImageView ownerAvatarView = cardView.findViewById(R.id.code_owner_avatar);
        TextView nameValue = cardView.findViewById(R.id.code_name_value);
        TextView ownerValue = cardView.findViewById(R.id.code_owner_value);
        TextView langsValue = cardView.findViewById(R.id.code_langs_value);
        TextView tCddValue = cardView.findViewById(R.id.code_tcdd_value);
        TextView tRateValue = cardView.findViewById(R.id.code_trate_value);

        setupAvatar(ownerAvatarView, code.getOwner());
        setTextValue(nameValue, code.getName());

        if (cidMap != null && cidMap.get(code.getOwner()) != null) {
            setTextValue(ownerValue, cidMap.get(code.getOwner()));
        } else {
            setTextValue(ownerValue, code.getOwner());
        }

        // Format langs array
        if (code.getLangs() != null && !code.getLangs().isEmpty()) {
            setTextValue(langsValue, String.join(", ", code.getLangs()));
        } else {
            setTextValue(langsValue, "");
        }

        // Format tCdd
        if (code.gettCdd() != null) {
            setTextValue(tCddValue, formatNumber(code.gettCdd()));
        } else {
            setTextValue(tCddValue, "0");
        }

        // Format tRate
        if (code.gettRate() != null) {
            setTextValue(tRateValue, String.format("%.1f", code.gettRate()));
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

    private void setupClickListeners(View cardView, Code code) {
        TextView nameValue = cardView.findViewById(R.id.code_name_value);
        TextView ownerValue = cardView.findViewById(R.id.code_owner_value);

        View.OnClickListener clickListener = v -> {
            if (onCodeClickListener != null) {
                onCodeClickListener.onCodeClick(code);
            } else {
                showCodeActivity(code);
            }
        };
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, code);

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

    private View.OnLongClickListener createLongPressListener(View cardView, Code code) {
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
                        removeCodeCard(cardView, code);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, code);
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

    private void removeCodeCard(View cardView, Code code) {
        codeListContainer.removeView(cardView);
        int index = codeList.indexOf(code);
        if (index != -1) {
            codeList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyCodeListChanged();
    }

    public boolean removeCodeById(String codeId) {
        if (codeId == null) return false;

        for (int i = 0; i < codeList.size(); i++) {
            Code code = codeList.get(i);
            if (codeId.equals(code.getId())) {
                if (i < codeListContainer.getChildCount()) {
                    codeListContainer.removeViewAt(i);
                }
                codeList.remove(i);
                if (i < checkBoxes.size()) {
                    checkBoxes.remove(i);
                }
                notifyCodeListChanged();
                return true;
            }
        }
        return false;
    }

    public boolean updateCodeCard(Code updatedCode) {
        if (updatedCode == null || updatedCode.getId() == null) return false;

        for (int i = 0; i < codeList.size(); i++) {
            Code code = codeList.get(i);
            if (updatedCode.getId().equals(code.getId())) {
                codeList.set(i, updatedCode);

                View cardView = codeListContainer.getChildAt(i);
                if (cardView != null) {
                    Map<String, String> cidMap = getCidMap(java.util.Collections.singletonList(updatedCode), context);
                    setupCardData(cardView, updatedCode, cidMap);
                    setupButtons(cardView, updatedCode);
                }

                notifyCodeListChanged();
                return true;
            }
        }
        return false;
    }

    private void setupButtons(View cardView, Code code) {
        ImageButton chainStatusButton = cardView.findViewById(R.id.code_chain_status_button);
        ImageButton editButton = cardView.findViewById(R.id.code_edit_button);
        ImageButton rateButton = cardView.findViewById(R.id.code_rate_button);

        // Setup chain status button
        if (chainStatusButton != null) {
            Boolean onChain = code.getOnChain();
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            
            if (liveFid != null && liveFid.equals(code.getOwner())) {
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
                            onOffChainIconClickListener.onOffChainIconClick(code);
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
                        onClearIconClickListener.onClearIconClick(code);
                    } else {
                        removeCodeCard(cardView, code);
                    }
                });
            } else {
                FidManager fidManager = FidManager.getInstance();
                String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
                if (liveFid != null && liveFid.equals(code.getOwner())) {
                    editButton.setVisibility(VISIBLE);
                    editButton.setImageResource(R.drawable.ic_edit);
                    editButton.setContentDescription(context.getString(R.string.edit));
                    editButton.setOnClickListener(v -> {
                        if (onEditIconClickListener != null) {
                            onEditIconClickListener.onEditIconClick(code);
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
            if (liveFid != null && liveFid.equals(code.getOwner())) {
                rateButton.setVisibility(GONE);
            } else {
                rateButton.setVisibility(VISIBLE);
                rateButton.setOnClickListener(v -> {
                    if (onRateIconClickListener != null) {
                        onRateIconClickListener.onRateIconClick(code);
                    }
                });
            }
        }
    }

    private void sortCodes(boolean enableSort, Comparator<CodeViewPair> comparator) {
        if (codeList.isEmpty() || !enableSort) {
            return;
        }

        List<CodeViewPair> pairs = createCodeViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<CodeViewPair> createCodeViewPairs() {
        List<CodeViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < codeList.size(); i++) {
            Code code = codeList.get(i);
            View cardView = codeListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new CodeViewPair(code, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<CodeViewPair> pairs) {
        codeList.clear();
        checkBoxes.clear();
        codeListContainer.removeAllViews();

        for (CodeViewPair pair : pairs) {
            codeList.add(pair.code);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            codeListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private record CodeViewPair(Code code, View cardView, CompoundButton checkBox) {
    }
}
