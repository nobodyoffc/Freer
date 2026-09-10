package com.fc.freer.multisig;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.RadioGroup;
import android.widget.RadioButton;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.im.SearchFidsOnChainActivity;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.KeyboardUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.initiate.SettingManager;

import java.util.ArrayList;
import java.util.List;

public class CreateMultisigIdActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateMultisigIdActivity";
    private static final int QR_SCAN_KEY_REQUEST_CODE = 1001;
    
    private LinearLayout keyListContainer;
    private KeyCardContainer keyCardContainer;
    private RadioGroup signerNumberRadioGroup;
    private TextInputEditText keyInput;
    private LinearLayout buttonContainer;
    private ImageButton clearButton;
    private ImageButton addMemberButton;
    private ImageButton createButton;
    private List<KeyInfo> memberList;
    private KeyInfo selectedKeyInfo;
    private RadioButton[] radioButtons;
    private TxHandler txHandler = new TxHandler();

    private ActivityResultLauncher<Intent> qrScanLauncher;
    private ActivityResultLauncher<Intent> chooseContactLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize member list
        memberList = new ArrayList<>();
        
        // Initialize QR scan launcher
        qrScanLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String qrContent = result.getData().getStringExtra("qr_content");
                    if (qrContent != null) {
                        if (KeyTools.isPubkey(qrContent)) {
                            String fid = KeyTools.pubkeyToFchAddr(qrContent);
                            String text = "Pubkey of " + StringUtils.omitMiddle(fid, 13);
                            keyInput.setText(text);
                            keyInput.setTextColor(getResources().getColor(R.color.disabled, getTheme()));
                            keyInput.setEnabled(false);
                            keyInput.setTag(qrContent);
                        } else {
                            showToast(getString(R.string.invalid_public_key));
                        }
                    }
                }
            }
        );

        // Initialize choose contact launcher
        chooseContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    java.util.List<String> fids = result.getData()
                            .getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                    java.util.List<String> pubkeys = result.getData()
                            .getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_PUBKEYS);
                    if (fids != null && !fids.isEmpty()) {
                        handleSelectedFids(fids, pubkeys);
                    }
                }
            }
        );

        // Replace deprecated onBackPressed() with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        // Set up buttons
        setupButtons();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_multisign_id;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_multisign_fid);
    }

    @Override
    protected void initializeViews() {
        keyListContainer = findViewById(R.id.keyListContainer);
        keyCardContainer = new KeyCardContainer(this, keyListContainer, ChooseMode.WITHOUT_CHOOSE_WITH_DELETE);

        signerNumberRadioGroup = findViewById(R.id.signerNumberRadioGroup);
        View keyView = findViewById(R.id.keyView);
        keyInput = keyView.findViewById(R.id.keyInputWithPeopleAndScanLayout).findViewById(R.id.keyInput);
        keyInput.setHint(R.string.input_the_pubkey_or_redeem_script);
        buttonContainer = findViewById(R.id.buttonContainer);

        // Initialize radio buttons array
        radioButtons = new RadioButton[16];
        for (int i = 0; i < 16; i++) {
            int radioId = getResources().getIdentifier("radio" + (i + 1), "id", getPackageName());
            radioButtons[i] = findViewById(radioId);
            // Set initial color to hint color
            radioButtons[i].setTextColor(getResources().getColor(R.color.disabled, getTheme()));
            radioButtons[i].setButtonTintList(ColorStateList.valueOf(getResources().getColor(R.color.disabled, getTheme())));
            radioButtons[i].setEnabled(false);

            final int index = i;
            radioButtons[i].setOnClickListener(v -> {
                // Only allow selection if the number is not larger than memberList size
                if ((index + 1) <= memberList.size()) {
                    // Let the RadioGroup handle the selection
                    signerNumberRadioGroup.check(radioButtons[index].getId());
                } else {
                    // If the button is not selectable, uncheck it
                    radioButtons[index].setChecked(false);
                }
            });
        }

        // Set up RadioGroup listener
        signerNumberRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            // This will be called when a radio button is selected
            TimberLogger.d(TAG, "Radio button selected with ID: " + checkedId);
        });

        // Set up key input icons
        IoIconsView keyIcons = keyView.findViewById(R.id.peopleAndScanIcons);
        keyIcons.init(this, false, true, true, true,false);
        keyIcons.setSingleChoice(false);
        keyIcons.setOnPeopleClickListener(isSingleChoice -> {
            launchChooseContactActivity();
        });
        keyIcons.setOnScanClickListener(() -> startQrScan(QR_SCAN_KEY_REQUEST_CODE));

        // Set up keyboard hiding
        KeyboardUtils.setupKeyboardHiding(this);

        // Set up listener BEFORE adding default member
        keyCardContainer.setOnKeyListChangedListener(updatedKeyInfoList -> {
            if(memberList==null)memberList = new ArrayList<>();
            memberList.clear();
            memberList.addAll(updatedKeyInfoList);
            updateRadioButtonsState();
        });

        // Add current key info as default member
        KeyInfo currentKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (currentKeyInfo != null && currentKeyInfo.getPubkey() != null) {
            // Only add to container - the listener will update memberList and radio buttons
            keyCardContainer.addKeyCard(currentKeyInfo);
        }
    }

    @Override
    protected void setupButtons() {
        clearButton = findViewById(R.id.clearButton);
        addMemberButton = findViewById(R.id.addMemberButton);
        createButton = findViewById(R.id.createButton);
        
        // Set height for all buttons
        setButtonHeight(clearButton);
        setButtonHeight(addMemberButton);
        setButtonHeight(createButton);
        
        // Set click listeners
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });
        addMemberButton.setOnClickListener(v -> {
            hideKeyboard();
            handleAddMember();
        });
        createButton.setOnClickListener(v -> {
            hideKeyboard();
            handleCreate();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_KEY_REQUEST_CODE && qrContent != null) {
            if (KeyTools.isPubkey(qrContent)) {
                String fid = KeyTools.pubkeyToFchAddr(qrContent);
                String text = "Pubkey of " + StringUtils.omitMiddle(fid, 13);
                keyInput.setText(text);
                keyInput.setTextColor(getResources().getColor(R.color.disabled, getTheme()));
                keyInput.setEnabled(false);
                keyInput.setTag(qrContent);
            } else {
                showToast(getString(R.string.invalid_public_key));
            }
        }
    }

    private void updateRadioButtonsState() {
        int memberCount = memberList.size();
        for (int i = 0; i < radioButtons.length; i++) {
            boolean isEnabled = (i + 1) <= memberCount;
            radioButtons[i].setEnabled(isEnabled);
            if (!isEnabled) {
                radioButtons[i].setChecked(false);
                radioButtons[i].setTextColor(getResources().getColor(R.color.disabled, getTheme()));
                radioButtons[i].setButtonTintList(ColorStateList.valueOf(getResources().getColor(R.color.disabled, getTheme())));
            } else {
                radioButtons[i].setTextColor(getResources().getColor(R.color.text, getTheme()));
                radioButtons[i].setButtonTintList(ColorStateList.valueOf(getResources().getColor(R.color.text, getTheme())));
            }
        }
    }

    private void useCurrentKeyInfo() {
        KeyInfo currentKeyInfo = SettingManager.getInstance().getCurrentKeyInfo();
        if (currentKeyInfo == null) {
            showToast(getString(R.string.no_key_available));
            return;
        }
        
        if (memberList.size() + 1 > 16) {
            showToast(getString(R.string.the_members_can_t_more_than_16));
            return;
        }
        
        // Add current key to member list
        if (currentKeyInfo.getPubkey() != null) {
            memberList.add(currentKeyInfo);
            keyCardContainer.addKeyCard(currentKeyInfo);
            
            // Clear and reset key input
            keyInput.setText("");
            keyInput.setEnabled(true);
            keyInput.setTextColor(getResources().getColor(R.color.text, getTheme()));
            selectedKeyInfo = null;

            // Update radio buttons state
            updateRadioButtonsState();
        } else {
            showToast(getString(R.string.selected_keys_have_no_public_keys));
        }
    }

    private void clearInputs() {
        signerNumberRadioGroup.clearCheck();
        keyInput.setText("");
        keyInput.setEnabled(true);
        keyInput.setTextColor(getResources().getColor(R.color.text, getTheme()));
        selectedKeyInfo = null;
        memberList.clear();
        keyCardContainer.clearAll();
        updateRadioButtonsState();
    }

    private void handleAddMember() {
        if (selectedKeyInfo == null && keyInput.getText() == null) {
            showToast(getString(R.string.please_input_a_public_key));
            return;
        }
        if(memberList.size()>=16){
            showToast(getString(R.string.the_members_can_t_more_than_16));
            return;
        }
        String str = selectedKeyInfo == null ? keyInput.getText().toString() : selectedKeyInfo.getPubkey();
        if (!KeyTools.isPubkey(str)) {
            try {
                Multisig multisig = Multisig.parseMultisigRedeemScript(str);
                if(multisig ==null || multisig.getPubkeys()==null || multisig.getPubkeys().isEmpty()){
                    showToast(getString(R.string.invalid_public_key_or_redeem_script));
                    return;
                }
                for(String key: multisig.getPubkeys()){
                    KeyInfo newKeyInfo = new KeyInfo(null, key);
                    if(memberList==null)memberList = new ArrayList<>();
                    memberList.add(newKeyInfo);
                    keyCardContainer.addKeyCard(newKeyInfo);
                }
                
                // Set the radio button corresponding to multisig.getM() as selected
                int m = multisig.getM();
                if (m > 0 && m <= radioButtons.length) {
                    signerNumberRadioGroup.check(radioButtons[m - 1].getId());
                }
                
            }catch (Exception e){
                showToast(getString(R.string.invalid_public_key_or_redeem_script));
                return;
            }
        }else {
            // Add the KeyInfo to member list
            KeyInfo newKeyInfo = new KeyInfo(null, str);
            memberList.add(newKeyInfo);
            keyCardContainer.addKeyCard(newKeyInfo);
        }

        // Clear key input
        keyInput.setText("");
        keyInput.setEnabled(true);
        keyInput.setTextColor(getResources().getColor(R.color.text, getTheme()));
        selectedKeyInfo = null;

        // Update radio buttons state
        updateRadioButtonsState();
    }

    private void handleCreate() {
        // Get keys from memberList instead of KeyCardContainer
        if (memberList.isEmpty()) {
            showToast(getString(R.string.please_input_required_signer_number));
            return;
        }

        int selectedRadioId = signerNumberRadioGroup.getCheckedRadioButtonId();
        if (selectedRadioId == -1) {
            showToast(getString(R.string.please_input_required_signer_number));
            return;
        }

        int signerNumber = 0;
        for (int i = 0; i < radioButtons.length; i++) {
            if (radioButtons[i].getId() == selectedRadioId) {
                signerNumber = i + 1;
                break;
            }
        }
        
        if (memberList.size() < signerNumber) {
            showToast(getString(R.string.no_enough_members));
            return;
        }
        
        try {
            // Create Multisig
            List<byte[]> pubkeyList = new ArrayList<>();
            for (KeyInfo keyInfo : memberList) {
                if(keyInfo.getPubkey()==null){
                    showToast(getString(R.string.failed_to_get_pubkey_of, keyInfo.getId()));
                    return;
                }
                pubkeyList.add(Hex.fromHex(keyInfo.getPubkey()));
            }
            Multisig multisig = txHandler.createMultisign(pubkeyList, signerNumber);
            if(multisig ==null){
                showToast(getString(R.string.failed_to_create_multisign_id));
                return;
            }
            // Set save time before adding to database
            multisig.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(), DateUtils.TO_MINUTE));
            
            // Show label dialog
            showLabelDialog(multisig);
        } catch (Exception e) {
            String errorMessage = "Failed to create multisig ID: " + e.getMessage();
            showToast(getString(R.string.operation_failed_with_message, e.getMessage()));
            TimberLogger.e(TAG, errorMessage);
        }
    }

    private void showLabelDialog(Multisig multisig) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Label for Multisig ID");
        
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_multisign_label, null);
        builder.setView(dialogView);
        
        TextView idTextView = dialogView.findViewById(R.id.id_text);
        EditText labelInput = dialogView.findViewById(R.id.label_input);
        ProgressBar progressBar = dialogView.findViewById(R.id.progress_bar);
        
        idTextView.setText(multisig.getId());
        
        builder.setPositiveButton("OK", (dialog, which) -> {
            String label = labelInput.getText().toString().trim();
            if (!label.isEmpty()) {
                multisig.setLabel(label);
            }
            
            try {
                // Save multisig to current setting's multisignFidList and keyInfoMap
                com.fc.freer.model.Setting currentSetting = com.fc.freer.initiate.SettingManager.getInstance().getCurrentSetting();
                if (currentSetting != null) {
                    // Create a KeyInfo for the multisig FID
                    KeyInfo multisignKeyInfo = new KeyInfo();
                    multisignKeyInfo.setId(multisig.getId());
                    multisignKeyInfo.setLabel(multisig.getLabel());
                    multisignKeyInfo.setMultisign(multisig);

                    // Add to setting
                    currentSetting.addMultisigKeyInfo(multisignKeyInfo);

                    // Save the updated setting
                    com.fc.freer.initiate.SettingManager.getInstance().saveSettings(CreateMultisigIdActivity.this, currentSetting);

                    TimberLogger.d(TAG, "Saved multisig to setting: %s", multisig.getId());
                } else {
                    throw new IllegalStateException("Current setting is null");
                }

                dialog.dismiss();
                showToast(getString(R.string.multisign_id_created_successfully));

                // Launch MultisigDetailActivity to show the created multisig
                Intent intent = new Intent(CreateMultisigIdActivity.this, MultisigDetailActivity.class);
                intent.putExtra("multisig", multisig.toJson());
                startActivity(intent);

                finish();
            } catch (Exception e) {
                String errorMessage = "Failed to save multisig ID: " + e.getMessage();
                TimberLogger.e(TAG, errorMessage);
                showToast(getString(R.string.operation_failed_with_message, e.getMessage()));
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        
        AlertDialog dialog = builder.create();
        DialogUtils.show(dialog);
        
        // Hide keyboard when dialog is shown
        if(dialog.getWindow()!=null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void setButtonHeight(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.height = (int) getResources().getDimension(R.dimen.button_height);
        view.setLayoutParams(params);
    }

    /**
     * Launch on-chain FID search (also lets the user pick from contacts) to select members
     */
    private void launchChooseContactActivity() {
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        chooseContactLauncher.launch(intent);
    }

    /**
     * Handle selected FIDs (with aligned pubkeys) from SearchFidsOnChainActivity. Multisig members
     * must have a pubkey to build the redeem script, so any FID without one is skipped.
     */
    private void handleSelectedFids(List<String> fids, List<String> pubkeys) {
        if (fids == null || fids.isEmpty()) {
            return;
        }

        List<KeyInfo> toAdd = new java.util.ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < fids.size(); i++) {
            String fid = fids.get(i);
            String pubkey = (pubkeys != null && i < pubkeys.size()) ? pubkeys.get(i) : null;
            if (fid == null || fid.isEmpty()) continue;
            if (pubkey == null || pubkey.isEmpty()) {
                skipped++;
                continue;
            }
            KeyInfo keyInfo = new KeyInfo();
            keyInfo.setId(fid);
            keyInfo.setPubkey(pubkey);
            toAdd.add(keyInfo);
        }

        if (skipped > 0) {
            showToast(getString(R.string.failed_to_get_pubkey_of, "some members"));
        }

        if (toAdd.isEmpty()) {
            return;
        }

        // Check if adding these members would exceed the limit
        if (memberList.size() + toAdd.size() > 16) {
            showToast(getString(R.string.the_members_can_t_more_than_16));
            return;
        }

        for (KeyInfo keyInfo : toAdd) {
            memberList.add(keyInfo);
            keyCardContainer.addKeyCard(keyInfo);
        }

        // Update radio buttons state
        updateRadioButtonsState();

        // Show success message
        showToast(getString(R.string.added_new_entities, toAdd.size(), "member"));
    }
} 