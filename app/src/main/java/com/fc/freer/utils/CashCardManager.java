package com.fc.freer.utils;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;
import android.widget.ImageButton;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CashCardManager {
    private static final String TAG = "CashCardManager";
    private final Context context;
    private final ViewGroup cashListContainer;
    private final List<Cash> cashList;
    private final List<CompoundButton> checkBoxes;
    private final Boolean isSingleChoice;
    private OnCashListChangedListener onCashListChangedListener;
    private List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;

    public interface OnCashListChangedListener {
        void onCashListChanged(List<Cash> updatedCashList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Cash cash);
    }

    public CashCardManager(Context context, LinearLayout cashListContainer) {
        this(context, cashListContainer, null);
    }

    public CashCardManager(Context context, LinearLayout cashListContainer, Boolean isSingleChoice) {
        this(context, cashListContainer, isSingleChoice, null);
    }

    public CashCardManager(Context context, LinearLayout cashListContainer, Boolean isSingleChoice, List<String> menuItems) {
        this.context = context;
        this.cashListContainer = cashListContainer;
        this.cashList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.isSingleChoice = isSingleChoice;
        this.menuItems = menuItems;
    }

    public void setOnCashListChangedListener(OnCashListChangedListener listener) {
        this.onCashListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setMenuItems(List<String> menuItems) {
        this.menuItems = menuItems;
    }

    private void notifyCashListChanged() {
        if (onCashListChangedListener != null) {
            onCashListChangedListener.onCashListChanged(new ArrayList<>(cashList));
        }
    }

    public void addCashCard(Cash cash) {
        int layoutResId;
        if (isSingleChoice == null) {
            layoutResId = R.layout.item_cash_card;
        } else if (isSingleChoice) {
            layoutResId = R.layout.item_cash_card_radio;
        } else {
            layoutResId = R.layout.item_cash_card_checkbox;
        }
        
        View cardView = LayoutInflater.from(context).inflate(layoutResId, cashListContainer, false);
        
        CompoundButton checkBox;
        if (isSingleChoice != null) {
            checkBox = cardView.findViewById(R.id.cash_checkbox);
            checkBoxes.add(checkBox);
            if (isSingleChoice) {
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        // Uncheck all other checkboxes
                        for (CompoundButton cb : checkBoxes) {
                            if (cb != checkBox) {
                                cb.setChecked(false);
                            }
                        }
                    }
                });
            } else {
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    notifyCashListChanged();
                });
            }
        } else {
            checkBox = null;
        }

        TextView amountValue = cardView.findViewById(R.id.cash_amount_value);
        TextView cdValue = cardView.findViewById(R.id.cash_cd_value);
        ImageButton deleteButton = cardView.findViewById(R.id.deleteButton);

        // Set up click listeners for the card view
        cardView.setOnClickListener(v -> {
            if (isSingleChoice != null && v.getId() != R.id.cash_checkbox) {
                checkBox.setChecked(!checkBox.isChecked());
            } else {
                showCashDetailActivity(cash);
            }
        });

        // Set amount value (no label)
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(cash.getValue())) + " F";
        amountValue.setText(amountText);

        // Set CD value (no label)
        Long cd = cash.getCd();
        if (cd != null) {
            cdValue.setText(String.format(Locale.US, "%d cd", cd));
        } else {
            cdValue.setText("0 cd");
        }

        // Add long press listeners for text views
        View.OnLongClickListener longPressListener = v -> {
            if (menuItems != null && !menuItems.isEmpty()) {
                PopupMenu popup = new PopupMenu(context, v);
                for (String menuItem : menuItems) {
                    popup.getMenu().add(menuItem);
                }
                
                popup.setOnMenuItemClickListener(item -> {
                    String title = item.getTitle().toString();
                    if ("Delete".equals(title)) {
                        // Remove the card from the container
                        cashListContainer.removeView(cardView);
                        // Remove the cash from the list
                        int index = cashList.indexOf(cash);
                        if (index != -1) {
                            cashList.remove(index);
                            if (index < checkBoxes.size()) {
                                checkBoxes.remove(index);
                            }
                        }
                        notifyCashListChanged();
                        return true;
                    } else if (context.getString(R.string.add_to_fid_list).equals(title)) {
                        FreerApplication.addFid(cash.getOwner());
                        Toast.makeText(context, context.getString(R.string.added_to_fid_list), Toast.LENGTH_SHORT).show();
                        return true;
                    } else if (context.getString(R.string.clear_fid_list).equals(title)) {
                        FreerApplication.clearFidList();
                        Toast.makeText(context, R.string.fid_list_cleared, Toast.LENGTH_SHORT).show();
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, cash);
                        return true;
                    }
                    return false;
                });
                
                // Add the new menu items
                popup.getMenu().add(context.getString(R.string.add_to_fid_list));
                popup.getMenu().add(context.getString(R.string.clear_fid_list));
                
                popup.show();
                return true;
            }
            return false;
        };

        // Only set long press listeners for visible elements
        amountValue.setOnLongClickListener(longPressListener);
        cdValue.setOnLongClickListener(longPressListener);
        cardView.setOnLongClickListener(longPressListener);

        amountValue.setOnClickListener(v -> copyAmount(cash.getValue()));
        cdValue.setOnClickListener(v -> copyCd(cash.getCd()));

        // Add delete button functionality
        deleteButton.setOnClickListener(v -> {
            // Remove the card from the container
            cashListContainer.removeView(cardView);
            // Remove the cash from the list
            int index = cashList.indexOf(cash);
            if (index != -1) {
                cashList.remove(index);
                if (index < checkBoxes.size()) {
                    checkBoxes.remove(index);
                }
            }
            notifyCashListChanged();
        });

        cashListContainer.addView(cardView);
        cashList.add(cash);
    }

    private void copyAmount(Long value) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        DecimalFormat df = new DecimalFormat("0.########");
        df.setDecimalSeparatorAlwaysShown(false);
        String amountText = df.format(FchUtils.satoshiToCoin(value)) + " F";
        android.content.ClipData clip = android.content.ClipData.newPlainText("Amount", amountText);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void copyCd(Long cd) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        String cdText = cd != null ? String.format(Locale.US, "%d cd", cd) : "0 cd";
        android.content.ClipData clip = android.content.ClipData.newPlainText("CD", cdText);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void showCashDetailActivity(Cash cash) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, cash.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Cash.class.getName());
        context.startActivity(intent);
    }

    public List<Cash> getSelectedCashes() {
        List<Cash> selectedCashes = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedCashes.add(cashList.get(i));
            }
        }
        return selectedCashes;
    }

    public void clearAll() {
        cashList.clear();
        cashListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Cash> getCashList() {
        return cashList;
    }
} 