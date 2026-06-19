package com.fc.freer.multisig;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.feature.avatar.AvatarMaker;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

import java.util.ArrayList;
import java.util.List;

public class MultisigKeyCardManager {
    private static final String TAG = "MultisigKeyCardManager";
    private final Context context;
    private final ViewGroup keyListContainer;
    private final List<Multisig> multisigList;
    private final List<RadioButton> radioButtons;
    private final ChooseMode chooseMode;

    public MultisigKeyCardManager(Context context, LinearLayout keyListContainer, ChooseMode chooseMode) {
        this.context = context;
        this.keyListContainer = keyListContainer;
        this.multisigList = new ArrayList<>();
        this.radioButtons = new ArrayList<>();
        this.chooseMode = chooseMode;
    }

    public void addKeyCard(Multisig multisig) {
        TimberLogger.d(TAG, "addKeyCard: Starting to add card for Multisig with ID: " + multisig.getId());

        View cardView = LayoutInflater.from(context).inflate(R.layout.item_key_card, keyListContainer, false);

        RadioButton checkboxButton = cardView.findViewById(R.id.key_checkbox);
        RadioButton radioButton = cardView.findViewById(R.id.key_radio);

        RadioButton activeButton = null;

        switch (chooseMode) {
            case CHOOSE_ONE:
                radioButton.setVisibility(View.VISIBLE);
                activeButton = radioButton;
                radioButtons.add(radioButton);
                radioButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        // Uncheck all other radio buttons
                        for (RadioButton rb : radioButtons) {
                            if (rb != radioButton) {
                                rb.setChecked(false);
                            }
                        }
                    }
                });
                break;
            case CHOOSE_MULTI:
                checkboxButton.setVisibility(View.VISIBLE);
                activeButton = checkboxButton;
                radioButtons.add(checkboxButton);
                break;
            case CHOOSE_ONE_RETURN:
            case WITHOUT_CHOOSE:
            default:
                // No selection controls visible
                break;
        }

        final RadioButton finalActiveButton = activeButton;

        ImageView avatar = cardView.findViewById(R.id.key_avatar);
        TextView keyLabel = cardView.findViewById(R.id.key_label);
        TextView keyId = cardView.findViewById(R.id.key_id);

        try {
            byte[] avatarBytes = AvatarMaker.createAvatar(multisig.getId(), context);
            if (avatarBytes != null) {
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
                avatar.setImageBitmap(bitmap);
                TimberLogger.d(TAG, "addKeyCard: Successfully created avatar for ID: " + multisig.getId());
            } else {
                TimberLogger.e(TAG, "addKeyCard: Failed to create avatar bytes for ID: " + multisig.getId());
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to create avatar for key ID %s: %s", multisig.getId(), e.getMessage());
            ToastUtils.makeText(context, R.string.failed_to_create_avatar);
        }
        keyLabel.setText(multisig.getLabel());
        keyLabel.setTextColor(context.getResources().getColor(R.color.field_name, context.getTheme()));
        keyLabel.setTypeface(keyLabel.getTypeface(), android.graphics.Typeface.BOLD);
        keyId.setText(multisig.getId());
        TimberLogger.d(TAG, "addKeyCard: Set label: " + multisig.getLabel() + ", ID: " + multisig.getId());

        avatar.setOnClickListener(v -> AvatarManager.showAvatarDialog(context, multisig.getId()));
        
        // Create a common long press listener
        View.OnLongClickListener longPressListener = v -> {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
            String[] options = {context.getString(R.string.delete), context.getString(R.string.create_tx), context.getString(R.string.add_to_fid_list), context.getString(R.string.clear_fid_list)};
            builder.setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // Delete
                        // Remove multisig from current setting's multisignFidList
                        com.fc.freer.model.Setting currentSetting = com.fc.freer.initiate.SettingManager.getInstance().getCurrentSetting();
                        if (currentSetting != null) {
                            currentSetting.removeMultisigKeyInfo(multisig.getId());
                            com.fc.freer.initiate.SettingManager.getInstance().saveSettings(context, currentSetting);
                        }
                        keyListContainer.removeView(cardView);
                        multisigList.remove(multisig);
                        break;
                    case 1: // Create TX
                        Intent createTxIntent = new Intent(context, com.fc.freer.tx.CreateTxActivity.class);
                        createTxIntent.putExtra("multisig", multisig);
                        RawTxInfo rawTxInfo = new RawTxInfo();
                        rawTxInfo.setSenderMultisig(multisig);
                        createTxIntent.putExtra("rawTxInfo", rawTxInfo);
                        context.startActivity(createTxIntent);
                        break;
                    case 2: // Add to FID list
                        FreerApplication.addFid(multisig.getId());
                        ToastUtils.makeText(context, R.string.added_to_fid_list);
                        break;
                    case 3: // Clear FID list
                        FreerApplication.clearFidList();
                        ToastUtils.makeText(context, R.string.fid_list_cleared);
                        break;
                }
            });
            builder.show();
            return true;
        };

        // Apply long press listener to both the card and ID text
        cardView.setOnLongClickListener(longPressListener);
        keyId.setOnLongClickListener(longPressListener);
        
        // Set click listeners for showing details
        cardView.setOnClickListener(v -> {
            if (finalActiveButton != null && v.getId() != R.id.key_checkbox && v.getId() != R.id.key_radio) {
                finalActiveButton.setChecked(!finalActiveButton.isChecked());
            } else if (chooseMode == ChooseMode.WITHOUT_CHOOSE || chooseMode == ChooseMode.CHOOSE_ONE_RETURN) {
                showKeyDetail(multisig);
            }
        });
        keyId.setOnClickListener(v -> showKeyDetail(multisig));

        keyListContainer.addView(cardView);
        multisigList.add(multisig);
        TimberLogger.d(TAG, "addKeyCard: Successfully added card to container. Total cards: " + multisigList.size());
    }

    public void addSenderKeyCard(Multisig multisig) {
        TimberLogger.d(TAG, "addSenderKeyCard: Starting to add card for Multisig with ID: " + multisig.getId());
        View cardView = LayoutInflater.from(context).inflate(R.layout.item_sender_key_card, keyListContainer, false);
        
        ImageView avatar = cardView.findViewById(R.id.key_avatar);
        TextView keyLabel = cardView.findViewById(R.id.key_label);
        TextView keyId = cardView.findViewById(R.id.key_id);

        try {
            byte[] avatarBytes = AvatarMaker.createAvatar(multisig.getId(), context);
            if (avatarBytes != null) {
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
                avatar.setImageBitmap(bitmap);
                TimberLogger.d(TAG, "addSenderKeyCard: Successfully created avatar for ID: " + multisig.getId());
            } else {
                TimberLogger.e(TAG, "addSenderKeyCard: Failed to create avatar bytes for ID: " + multisig.getId());
            }
        } catch (Exception e) {
            ToastUtils.makeText(context, R.string.failed_to_create_avatar);
        }
        keyLabel.setText(multisig.getLabel());
        keyLabel.setTextColor(context.getResources().getColor(R.color.field_name, context.getTheme()));
        keyLabel.setTypeface(keyLabel.getTypeface(), android.graphics.Typeface.BOLD);
        keyId.setText(multisig.getId());
        keyId.setTypeface(keyId.getTypeface(), android.graphics.Typeface.BOLD);
        TimberLogger.d(TAG, "addSenderKeyCard: Set label: " + multisig.getLabel() + ", ID: " + multisig.getId());

        avatar.setOnClickListener(v -> AvatarManager.showAvatarDialog(context, multisig.getId()));
        
        // Create a common long press listener
        View.OnLongClickListener longPressListener = v -> {
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
            String[] options = {"Delete", "Create TX", "Add to FID list", "Clear FID list"};
            builder.setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0: // Delete
                        // Remove multisig from current setting's multisignFidList
                        com.fc.freer.model.Setting currentSetting = com.fc.freer.initiate.SettingManager.getInstance().getCurrentSetting();
                        if (currentSetting != null) {
                            currentSetting.removeMultisigKeyInfo(multisig.getId());
                            com.fc.freer.initiate.SettingManager.getInstance().saveSettings(context, currentSetting);
                        }
                        keyListContainer.removeView(cardView);
                        multisigList.remove(multisig);
                        break;
                    case 1: // Create TX
                        Intent createTxIntent = new Intent(context, com.fc.freer.tx.CreateTxActivity.class);
                        createTxIntent.putExtra("multisig", multisig);
                        RawTxInfo rawTxInfo = new RawTxInfo();
                        rawTxInfo.setSenderMultisig(multisig);
                        createTxIntent.putExtra("rawTxInfo", rawTxInfo);
                        context.startActivity(createTxIntent);
                        break;
                    case 2: // Add to FID list
                        FreerApplication.addFid(multisig.getId());
                        ToastUtils.makeText(context, R.string.added_to_fid_list);
                        break;
                    case 3: // Clear FID list
                        FreerApplication.clearFidList();
                        ToastUtils.makeText(context, R.string.fid_list_cleared);
                        break;
                }
            });
            builder.show();
            return true;
        };

        // Apply long press listener to both the card and ID text
        cardView.setOnLongClickListener(longPressListener);
        keyId.setOnLongClickListener(longPressListener);
        
        // Set click listeners for showing details
        cardView.setOnClickListener(v -> showKeyDetail(multisig));
        keyId.setOnClickListener(v -> showKeyDetail(multisig));

        keyListContainer.addView(cardView);
        multisigList.add(multisig);
        TimberLogger.d(TAG, "addSenderKeyCard: Successfully added card to container. Total cards: " + multisigList.size());
    }

    private void copyKeyId(String keyId) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("Key ID", keyId);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(context, R.string.copied);
    }

    private void showKeyDetail(Multisig multisig) {
        Intent intent = new Intent(context, MultisigDetailActivity.class);
        intent.putExtra("multisig", multisig);
        context.startActivity(intent);
    }

    public List<Multisig> getSelectedKeys() {
        List<Multisig> selectedKeys = new ArrayList<>();
        for (int i = 0; i < radioButtons.size(); i++) {
            if (radioButtons.get(i).isChecked()) {
                selectedKeys.add(multisigList.get(i));
                TimberLogger.d(TAG, "getSelectedKeys: Selected key: " + multisigList.get(i).getId());
            }
        }
        return selectedKeys;
    }

    public void clearAll() {
        TimberLogger.d(TAG, "clearAll: Starting to clear all cards. Current count: " + multisigList.size());
        multisigList.clear();
        keyListContainer.removeAllViews();
        radioButtons.clear();
        TimberLogger.d(TAG, "clearAll: Successfully cleared all cards and radio buttons");
    }

    public List<Multisig> getMultisignList() {
        return multisigList;
    }

    public void addMultisignCards(LinearLayout container, List<Multisig> multisigs) {
        TimberLogger.d(TAG, "addMultisignCards: Starting to add " + (multisigs != null ? multisigs.size() : 0) + " cards");
        if (multisigs == null || multisigs.isEmpty()) {
            TimberLogger.w(TAG, "addMultisignCards: No cards to add - multisigs is null or empty");
            return;
        }
        for (Multisig multisig : multisigs) {
            addKeyCard(multisig);
        }
        TimberLogger.d(TAG, "addMultisignCards: Finished adding all cards. Total cards in list: " + multisigList.size());
    }
} 