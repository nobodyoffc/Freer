package com.fc.freer.utils;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.TokenManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class TokenHolderCardContainer {
    private static final String TAG = "TokenHolderCardContainer";
    private final Context context;
    private final ViewGroup listContainer;
    private final List<TokenHolder> tokenHolderList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnTokenHolderListChangedListener onListChangedListener;
    private OnSendIconClickListener onSendIconClickListener;
    private OnTokenHolderClickListener onTokenHolderClickListener;
    private Map<String, Token> tokenInfoMap;

    public interface OnTokenHolderListChangedListener {
        void onListChanged(List<TokenHolder> updatedList);
    }

    public interface OnSendIconClickListener {
        void onSendIconClick(TokenHolder tokenHolder);
    }

    public interface OnTokenHolderClickListener {
        void onTokenHolderClick(TokenHolder tokenHolder);
    }

    public TokenHolderCardContainer(Context context, LinearLayout listContainer, ChooseMode chooseMode) {
        this.context = context;
        this.listContainer = listContainer;
        this.tokenHolderList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
    }

    public void setOnListChangedListener(OnTokenHolderListChangedListener listener) {
        this.onListChangedListener = listener;
    }

    public void setOnSendIconClickListener(OnSendIconClickListener listener) {
        this.onSendIconClickListener = listener;
    }

    public void setOnTokenHolderClickListener(OnTokenHolderClickListener listener) {
        this.onTokenHolderClickListener = listener;
    }

    public void setTokenInfoMap(Map<String, Token> tokenInfoMap) {
        this.tokenInfoMap = tokenInfoMap;
    }

    private void notifyListChanged() {
        if (onListChangedListener != null) {
            onListChangedListener.onListChanged(new ArrayList<>(tokenHolderList));
        }
    }

    public void addTokenHolderCard(TokenHolder tokenHolder) {
        addTokenHolderCardAtPosition(tokenHolder, tokenHolderList.size());
    }

    private void addTokenHolderCardAtPosition(TokenHolder tokenHolder, int position) {
        View cardView = LayoutInflater.from(context).inflate(R.layout.item_token_holder_card, listContainer, false);
        CompoundButton checkBox = setupCardViewInteractions(cardView, tokenHolder);

        listContainer.addView(cardView, position);
        tokenHolderList.add(position, tokenHolder);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private CompoundButton setupCardViewInteractions(View cardView, TokenHolder tokenHolder) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, tokenHolder);
        setupClickListeners(cardView, tokenHolder);
        setupButtons(cardView, tokenHolder);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.token_holder_checkbox);
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

    private void setupCardData(View cardView, TokenHolder tokenHolder) {
        TextView nameValue = cardView.findViewById(R.id.token_holder_name_value);
        TextView tokenIdValue = cardView.findViewById(R.id.token_holder_token_id_value);
        TextView balanceValue = cardView.findViewById(R.id.token_holder_balance_value);

        // Get token name from cache if available
        String tokenName = tokenHolder.getTokenId();
        if (tokenInfoMap != null && tokenInfoMap.containsKey(tokenHolder.getTokenId())) {
            Token token = tokenInfoMap.get(tokenHolder.getTokenId());
            if (token != null && token.getName() != null) {
                tokenName = token.getName();
            }
        }
        nameValue.setText(tokenName != null ? tokenName : "");
        tokenIdValue.setText(tokenHolder.getTokenId() != null ? tokenHolder.getTokenId() : "");
        
        if (tokenHolder.getBalance() != null) {
            balanceValue.setText(formatBalance(tokenHolder.getBalance()));
        } else {
            balanceValue.setText("0");
        }
    }

    private String formatBalance(Double balance) {
        if (balance == null) return "0";
        if (balance >= 1_000_000_000) {
            return String.format("%.2fB", balance / 1_000_000_000.0);
        } else if (balance >= 1_000_000) {
            return String.format("%.2fM", balance / 1_000_000.0);
        } else if (balance >= 1_000) {
            return String.format("%.2fK", balance / 1_000.0);
        } else if (balance == balance.longValue()) {
            return String.valueOf(balance.longValue());
        }
        return String.format("%.4f", balance);
    }

    private void setupClickListeners(View cardView, TokenHolder tokenHolder) {
        View.OnClickListener clickListener = v -> {
            if (onTokenHolderClickListener != null) {
                onTokenHolderClickListener.onTokenHolderClick(tokenHolder);
            } else {
                showTokenHolderDetail(tokenHolder);
            }
        };

        cardView.setOnClickListener(clickListener);
        TextView nameValue = cardView.findViewById(R.id.token_holder_name_value);
        if (nameValue != null) {
            nameValue.setOnClickListener(clickListener);
        }
    }

    private void showTokenHolderDetail(TokenHolder tokenHolder) {
        Intent intent = new Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, tokenHolder.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, TokenHolder.class.getName());
        context.startActivity(intent);
    }

    private void setupButtons(View cardView, TokenHolder tokenHolder) {
        ImageButton sendButton = cardView.findViewById(R.id.token_holder_send_button);
        if (sendButton != null) {
            sendButton.setOnClickListener(v -> {
                if (onSendIconClickListener != null) {
                    onSendIconClickListener.onSendIconClick(tokenHolder);
                }
            });
        }
    }

    public List<TokenHolder> getSelectedTokenHolders() {
        List<TokenHolder> selected = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selected.add(tokenHolderList.get(i));
            }
        }
        return selected;
    }

    public void clearAll() {
        tokenHolderList.clear();
        listContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<TokenHolder> getTokenHolderList() {
        return tokenHolderList;
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

    public void removeFromEnd(int count) {
        if (count <= 0 || count > tokenHolderList.size()) return;
        int size = tokenHolderList.size();
        for (int i = 0; i < count; i++) {
            listContainer.removeViewAt(listContainer.getChildCount() - 1);
            tokenHolderList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }
        notifyListChanged();
    }

    public void removeFromBeginning(int count) {
        if (count <= 0 || count > tokenHolderList.size()) return;
        for (int i = 0; i < count; i++) {
            listContainer.removeViewAt(0);
            tokenHolderList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }
        notifyListChanged();
    }

    public boolean removeTokenHolderById(String id) {
        if (id == null) return false;
        for (int i = 0; i < tokenHolderList.size(); i++) {
            TokenHolder holder = tokenHolderList.get(i);
            if (id.equals(holder.getId())) {
                if (i < listContainer.getChildCount()) {
                    listContainer.removeViewAt(i);
                }
                tokenHolderList.remove(i);
                if (i < checkBoxes.size()) {
                    checkBoxes.remove(i);
                }
                notifyListChanged();
                return true;
            }
        }
        return false;
    }

    public void sortByBalance(boolean ascending, boolean enableSort) {
        if (!enableSort || tokenHolderList.isEmpty()) return;
        sortTokenHolders((h1, h2) -> {
            Double b1 = h1.holder.getBalance();
            Double b2 = h2.holder.getBalance();
            if (b1 == null && b2 == null) return 0;
            if (b1 == null) return 1;
            if (b2 == null) return -1;
            return ascending ? b1.compareTo(b2) : b2.compareTo(b1);
        });
    }

    public void sortByTokenId(boolean ascending, boolean enableSort) {
        if (!enableSort || tokenHolderList.isEmpty()) return;
        sortTokenHolders((h1, h2) -> {
            String id1 = h1.holder.getTokenId();
            String id2 = h2.holder.getTokenId();
            if (id1 == null && id2 == null) return 0;
            if (id1 == null) return 1;
            if (id2 == null) return -1;
            return ascending ? id1.compareTo(id2) : id2.compareTo(id1);
        });
    }

    private void sortTokenHolders(Comparator<TokenHolderViewPair> comparator) {
        List<TokenHolderViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < tokenHolderList.size(); i++) {
            TokenHolder holder = tokenHolderList.get(i);
            View cardView = listContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new TokenHolderViewPair(holder, cardView, checkBox));
        }
        pairs.sort(comparator);

        tokenHolderList.clear();
        checkBoxes.clear();
        listContainer.removeAllViews();

        for (TokenHolderViewPair pair : pairs) {
            tokenHolderList.add(pair.holder);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            listContainer.addView(pair.cardView);
        }
    }

    private record TokenHolderViewPair(TokenHolder holder, View cardView, CompoundButton checkBox) {}
}
