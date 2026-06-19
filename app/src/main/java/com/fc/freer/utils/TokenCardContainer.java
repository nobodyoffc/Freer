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
import android.widget.TextView;

import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TokenCardContainer {
    private static final String TAG = "TokenCardContainer";
    private final Context context;
    private final ViewGroup listContainer;
    private final List<Token> tokenList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnTokenListChangedListener onListChangedListener;
    private OnTokenClickListener onTokenClickListener;
    private OnHistoryIconClickListener onHistoryIconClickListener;
    private OnEditIconClickListener onEditIconClickListener;
    private OnClearIconClickListener onClearIconClickListener;
    private boolean hideEditButton = false;
    private boolean showClearButton = false;

    public interface OnTokenListChangedListener {
        void onListChanged(List<Token> updatedList);
    }

    public interface OnTokenClickListener {
        void onTokenClick(Token token);
    }

    public interface OnHistoryIconClickListener {
        void onHistoryIconClick(Token token);
    }

    public interface OnEditIconClickListener {
        void onEditIconClick(Token token);
    }

    public interface OnClearIconClickListener {
        void onClearIconClick(Token token);
    }

    public TokenCardContainer(Context context, LinearLayout listContainer, ChooseMode chooseMode) {
        this.context = context;
        this.listContainer = listContainer;
        this.tokenList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
    }

    public void setOnListChangedListener(OnTokenListChangedListener listener) {
        this.onListChangedListener = listener;
    }

    public void setOnTokenClickListener(OnTokenClickListener listener) {
        this.onTokenClickListener = listener;
    }

    public void setOnHistoryIconClickListener(OnHistoryIconClickListener listener) {
        this.onHistoryIconClickListener = listener;
    }

    public void setOnEditIconClickListener(OnEditIconClickListener listener) {
        this.onEditIconClickListener = listener;
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

    private void notifyListChanged() {
        if (onListChangedListener != null) {
            onListChangedListener.onListChanged(new ArrayList<>(tokenList));
        }
    }

    public Map<String, String> getCidMap(List<Token> tokens, Context context) {
        List<String> fidList = new ArrayList<>();
        for (Token token : tokens) {
            if (token.getDeployer() != null) {
                fidList.add(token.getDeployer());
            }
        }
        if (!fidList.isEmpty()) {
            CidFidManager cidFidManager = CidFidManager.getInstance(context);
            return cidFidManager.getCidsByFids(fidList);
        }
        return null;
    }

    public void addTokenCard(Token token, Map<String, String> cidMap) {
        addTokenCardAtPosition(token, tokenList.size(), cidMap);
    }

    private void addTokenCardAtPosition(Token token, int position, Map<String, String> cidMap) {
        View cardView = LayoutInflater.from(context).inflate(R.layout.item_token_card, listContainer, false);
        CompoundButton checkBox = setupCardViewInteractions(cardView, token, cidMap);

        listContainer.addView(cardView, position);
        tokenList.add(position, token);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private CompoundButton setupCardViewInteractions(View cardView, Token token, Map<String, String> cidMap) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, token, cidMap);
        setupClickListeners(cardView, token);
        setupButtons(cardView, token);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.token_checkbox);
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
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Token token, Map<String, String> cidMap) {
        ImageView avatarView = cardView.findViewById(R.id.token_deployer_avatar);
        TextView nameValue = cardView.findViewById(R.id.token_name_value);
        TextView deployerValue = cardView.findViewById(R.id.token_deployer_value);
        TextView descValue = cardView.findViewById(R.id.token_desc_value);
        TextView circulatingValue = cardView.findViewById(R.id.token_circulating_value);
        TextView decimalValue = cardView.findViewById(R.id.token_decimal_value);

        setupAvatar(avatarView, token.getDeployer());
        nameValue.setText(token.getName() != null ? token.getName() : "");
        
        String deployerDisplay = token.getDeployer();
        if (cidMap != null && cidMap.containsKey(token.getDeployer())) {
            deployerDisplay = cidMap.get(token.getDeployer());
        }
        deployerValue.setText(deployerDisplay != null ? deployerDisplay : "");
        
        descValue.setText(token.getDesc() != null ? token.getDesc() : "");
        
        if (token.getCirculating() != null) {
            circulatingValue.setText(formatNumber(token.getCirculating()));
        } else {
            circulatingValue.setText("0");
        }
        
        decimalValue.setText(token.getDecimal() != null ? token.getDecimal() : "0");
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
                avatarView.setImageResource(R.drawable.container_outline);
            }
        } else {
            avatarView.setImageResource(R.drawable.container_outline);
        }
    }

    private String formatNumber(Double number) {
        if (number == null) return "0";
        if (number >= 1_000_000_000) {
            return String.format("%.1fB", number / 1_000_000_000.0);
        } else if (number >= 1_000_000) {
            return String.format("%.1fM", number / 1_000_000.0);
        } else if (number >= 1_000) {
            return String.format("%.1fK", number / 1_000.0);
        } else if (number == number.longValue()) {
            return String.valueOf(number.longValue());
        }
        return String.format("%.2f", number);
    }

    private void setupClickListeners(View cardView, Token token) {
        View.OnClickListener clickListener = v -> {
            if (onTokenClickListener != null) {
                onTokenClickListener.onTokenClick(token);
            } else {
                showTokenDetail(token);
            }
        };

        cardView.setOnClickListener(clickListener);
        TextView nameValue = cardView.findViewById(R.id.token_name_value);
        if (nameValue != null) {
            nameValue.setOnClickListener(clickListener);
        }
    }

    private void showTokenDetail(Token token) {
        Intent intent = new Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, token.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Token.class.getName());
        context.startActivity(intent);
    }

    private void setupButtons(View cardView, Token token) {
        ImageButton historyButton = cardView.findViewById(R.id.token_history_button);
        ImageButton editButton = cardView.findViewById(R.id.token_edit_button);

        // History button - always visible
        if (historyButton != null) {
            historyButton.setVisibility(VISIBLE);
            historyButton.setOnClickListener(v -> {
                if (onHistoryIconClickListener != null) {
                    onHistoryIconClickListener.onHistoryIconClick(token);
                }
            });
        }

        // Edit button
        if (editButton != null) {
            if (hideEditButton) {
                editButton.setVisibility(GONE);
            } else if (showClearButton) {
                editButton.setVisibility(VISIBLE);
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setContentDescription(context.getString(R.string.remove));
                editButton.setOnClickListener(v -> {
                    if (onClearIconClickListener != null) {
                        onClearIconClickListener.onClearIconClick(token);
                    } else {
                        removeTokenCard(cardView, token);
                    }
                });
            } else {
                // Only show edit button for deployer
                FidManager fidManager = FidManager.getInstance();
                String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
                if (liveFid != null && liveFid.equals(token.getDeployer())) {
                    editButton.setVisibility(VISIBLE);
                    editButton.setImageResource(R.drawable.ic_edit);
                    editButton.setOnClickListener(v -> {
                        if (onEditIconClickListener != null) {
                            onEditIconClickListener.onEditIconClick(token);
                        }
                    });
                } else {
                    editButton.setVisibility(GONE);
                }
            }
        }
    }

    private void removeTokenCard(View cardView, Token token) {
        listContainer.removeView(cardView);
        int index = tokenList.indexOf(token);
        if (index != -1) {
            tokenList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyListChanged();
    }

    public List<Token> getSelectedTokens() {
        List<Token> selected = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selected.add(tokenList.get(i));
            }
        }
        return selected;
    }

    public void clearAll() {
        tokenList.clear();
        listContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Token> getTokenList() {
        return tokenList;
    }

    public void selectAll(boolean selected) {
        if (chooseMode != ChooseMode.CHOOSE_MULTI) return;
        for (CompoundButton checkBox : checkBoxes) {
            checkBox.setChecked(selected);
        }
    }

    public boolean areAllSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) return false;
        for (CompoundButton checkBox : checkBoxes) {
            if (!checkBox.isChecked()) return false;
        }
        return true;
    }

    public boolean areNoneSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) return true;
        for (CompoundButton checkBox : checkBoxes) {
            if (checkBox.isChecked()) return false;
        }
        return true;
    }

    public boolean removeTokenById(String id) {
        if (id == null) return false;
        for (int i = 0; i < tokenList.size(); i++) {
            Token token = tokenList.get(i);
            if (id.equals(token.getId())) {
                if (i < listContainer.getChildCount()) {
                    listContainer.removeViewAt(i);
                }
                tokenList.remove(i);
                if (i < checkBoxes.size()) {
                    checkBoxes.remove(i);
                }
                notifyListChanged();
                return true;
            }
        }
        return false;
    }

    public void removeFromEnd(int count) {
        if (count <= 0 || count > tokenList.size()) return;
        int size = tokenList.size();
        for (int i = 0; i < count; i++) {
            listContainer.removeViewAt(listContainer.getChildCount() - 1);
            tokenList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }
        notifyListChanged();
    }

    public void removeFromBeginning(int count) {
        if (count <= 0 || count > tokenList.size()) return;
        for (int i = 0; i < count; i++) {
            listContainer.removeViewAt(0);
            tokenList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
        notifyListChanged();
    }

    public void sortByName(boolean ascending, boolean enableSort) {
        if (!enableSort || tokenList.isEmpty()) return;
        sortTokens((t1, t2) -> {
            String n1 = t1.token.getName();
            String n2 = t2.token.getName();
            if (n1 == null && n2 == null) return 0;
            if (n1 == null) return 1;
            if (n2 == null) return -1;
            return ascending ? n1.compareTo(n2) : n2.compareTo(n1);
        });
    }

    public void sortByDeployer(boolean ascending, boolean enableSort) {
        if (!enableSort || tokenList.isEmpty()) return;
        sortTokens((t1, t2) -> {
            String d1 = t1.token.getDeployer();
            String d2 = t2.token.getDeployer();
            if (d1 == null && d2 == null) return 0;
            if (d1 == null) return 1;
            if (d2 == null) return -1;
            return ascending ? d1.compareTo(d2) : d2.compareTo(d1);
        });
    }

    public void sortByCirculating(boolean ascending, boolean enableSort) {
        if (!enableSort || tokenList.isEmpty()) return;
        sortTokens((t1, t2) -> {
            Double c1 = t1.token.getCirculating();
            Double c2 = t2.token.getCirculating();
            if (c1 == null && c2 == null) return 0;
            if (c1 == null) return 1;
            if (c2 == null) return -1;
            return ascending ? c1.compareTo(c2) : c2.compareTo(c1);
        });
    }

    private void sortTokens(Comparator<TokenViewPair> comparator) {
        List<TokenViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < tokenList.size(); i++) {
            Token token = tokenList.get(i);
            View cardView = listContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new TokenViewPair(token, cardView, checkBox));
        }
        pairs.sort(comparator);

        tokenList.clear();
        checkBoxes.clear();
        listContainer.removeAllViews();

        for (TokenViewPair pair : pairs) {
            tokenList.add(pair.token);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            listContainer.addView(pair.cardView);
        }
    }

    private record TokenViewPair(Token token, View cardView, CompoundButton checkBox) {}
}
