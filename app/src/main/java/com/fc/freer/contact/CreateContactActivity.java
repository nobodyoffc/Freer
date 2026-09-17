package com.fc.freer.contact;

import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.ContactOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.nobody.NobodyGuard;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class CreateContactActivity extends BaseCryptoActivity {
    private static final String TAG = "CreateContactActivity";

    /** Optional: a FID to look up and select straight away, e.g. the guide from Getting started. */
    public static final String EXTRA_FID = "extra_fid";

    // FID Search Components
    private TextView searchResultsHint;
    private EditText fidSearchEditText;
    private ImageButton searchButton;
    private LinearLayout searchResultsLayout;
    private ImageButton clearFidButton;
    private Button moreFidButton;
    private TextView moreResultsHint;

    // Input Components
    private LinearLayout inputContainer;
    private TextInputEditText titlesInput;
    private TextInputEditText memoInput;
    private CheckBox seeStatementCheckBox;
    private CheckBox seeWritingsCheckBox;

    // Action Buttons
    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton carveButton;

    // FID Search State
    private String selectedFid = null;
    private Freer selectedFreer = null;
    private View selectedCardView = null;
    private FapiClient fapiClient;
    private KeyCardContainer keyCardContainer;
    private WaitingDialog waitingDialog;
    private List<Freer> currentSearchResults = new ArrayList<>();
    private String currentSearchTerm = null;
    private boolean isExactFidSearch = false;

    // Define request codes for QR scan
    private static final int QR_SCAN_TITLES_REQUEST_CODE = 2001;
    private static final int QR_SCAN_MEMO_REQUEST_CODE = 2002;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_create_contact;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.create_contact);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set up root layout touch listener to hide keyboard
        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

        // Setup toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());

        // Replace deprecated onBackPressed() with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        setupData();

        String prefillFid = getIntent().getStringExtra(EXTRA_FID);
        if (prefillFid != null && !prefillFid.isEmpty() && fidSearchEditText != null) {
            fidSearchEditText.setText(prefillFid);
            performFidSearch();
        }

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.titlesView, R.id.scanIcon, QR_SCAN_TITLES_REQUEST_CODE);
        TextIconsUtils.setupTextIcons(this, R.id.memoView, R.id.scanIcon, QR_SCAN_MEMO_REQUEST_CODE);
    }

    @Override
    protected void initializeViews() {
        // FID Search Components
        searchResultsHint = findViewById(R.id.searchResultsHint);
        fidSearchEditText = findViewById(R.id.fidSearchEditText);
        searchButton = findViewById(R.id.searchButton);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        clearFidButton = findViewById(R.id.clearFidButton);
        moreFidButton = findViewById(R.id.moreFidButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);

        // Input Components
        inputContainer = findViewById(R.id.inputContainer);

        View titlesView = findViewById(R.id.titlesView);
        View memoView = findViewById(R.id.memoView);

        titlesInput = titlesView.findViewById(R.id.textInput);
        titlesInput.setHint(R.string.input_titles_comma_separated);
        memoInput = memoView.findViewById(R.id.textInput);
        memoInput.setHint(getString(R.string.input_the_memo) + " (optional)");

        seeStatementCheckBox = findViewById(R.id.seeStatementCheckBox);
        seeWritingsCheckBox = findViewById(R.id.seeWritingsCheckBox);

        // Action Buttons
        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        carveButton = findViewById(R.id.carveButton);

        // Set default checkbox values
        seeStatementCheckBox.setChecked(true);
        seeWritingsCheckBox.setChecked(true);

        // Initialize FID search KeyCardContainer with single choice and clickToReturn enabled
        List<com.fc.freer.ui.MenuItem> menuItems = new ArrayList<>();
        menuItems.add(com.fc.freer.ui.MenuItem.createDetailMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, searchResultsLayout, ChooseMode.CHOOSE_ONE_RETURN,menuItems,true); // Enable CID metrics display

        keyCardContainer.setOnKeyClickedListener(this::onFidSelected);
        keyCardContainer.setOnMenuItemClickListener(this::onMenuItemClicked);

        // Initially hide search results
        searchResultsHint.setVisibility(View.GONE);
        moreFidButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
    }

    @Override
    protected void setupButtons() {
        searchButton.setOnClickListener(v -> performFidSearch());
        clearFidButton.setOnClickListener(v -> clearFidSearch());
        moreFidButton.setOnClickListener(v -> loadMoreResults());

        clearButton.setOnClickListener(v -> clearAllInputs());
        saveButton.setOnClickListener(v -> saveContactToDatabase());
        carveButton.setOnClickListener(v -> carveContact());

        // Hide keyboard when fidSearchEditText loses focus
        fidSearchEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard();
            }
        });
    }

    private void setupData() {
        try {
            // Get FAPI client
            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter != null) {
                fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up data: %s", e.getMessage());
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_TITLES_REQUEST_CODE) {
            titlesInput.setText(qrContent);
        } else if (requestCode == QR_SCAN_MEMO_REQUEST_CODE) {
            memoInput.setText(qrContent);
        }
    }

    // FID Search Methods (similar to AddWatchedFidActivity)

    private void performFidSearch() {
        TimberLogger.d(TAG, "performFidSearch() called");

        String searchString = fidSearchEditText.getText().toString().trim();
        if (TextUtils.isEmpty(searchString)) {
            ToastUtils.makeText(this, getString(R.string.please_enter_search_term));
            return;
        }

        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.apip_client_not_available));
            return;
        }

        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.searching));
        waitingDialog.show();

        // Store current search parameters
        currentSearchTerm = searchString;
        isExactFidSearch = isValidFidFormat(searchString);

        // Clear previous results
        currentSearchResults.clear();

        // Check if it's a valid FID format
        if (isExactFidSearch) {
            // Search for exact FID
            searchExactFid(searchString);
        } else {
            // Search for partial match in CID
            searchPartialCid(searchString, null);
        }
    }

    private boolean isValidFidFormat(String fid) {
        // Basic FID format validation - should start with F and be proper length
        return KeyTools.isGoodFid(fid);
    }

    private void searchExactFid(String fid) {
        new Thread(() -> {
            try {
                Freer freerInfo = fapiClient.getFreer(fid);

                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (freerInfo != null) {
                        List<Freer> results = new ArrayList<>();
                        results.add(freerInfo);
                        currentSearchResults.clear();
                        currentSearchResults.addAll(results);
                        displaySearchResults(results);
                        moreFidButton.setVisibility(View.GONE); // No pagination for exact FID search
                    } else {
                        ToastUtils.makeText(this, getString(R.string.fid_not_found));
                        clearSearchResults();
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching exact FID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    clearSearchResults();
                });
            }
        }).start();
    }

    private void searchPartialCid(String searchString, List<String> afterValue) {
        new Thread(() -> {
            try {
                // Create Fcdsl for partial search in id or usedCids
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewQuery().addNewPart().addNewFields("id", "usedCids").addNewValue(searchString);
                fcdsl.setSize(String.valueOf(FreerApplication.DEFAULT_PAGE_SIZE));

                // Add 'after' parameter for pagination
                if (afterValue != null) {
                    fcdsl.setAfter(afterValue);
                }

                List<Freer> results = fapiClient.freerSearch(fcdsl);

                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (results != null && !results.isEmpty()) {
                        if (afterValue == null) {
                            // New search - replace results
                            currentSearchResults.clear();
                            currentSearchResults.addAll(results);
                            displaySearchResults(currentSearchResults);
                        } else {
                            // Load more - append results
                            currentSearchResults.addAll(results);
                            appendSearchResults(results);
                        }

                        // Show/hide More button based on result size
                        updateMoreButtonVisibility(results.size());
                    } else {
                        if (afterValue == null) {
                            TimberLogger.d(TAG, "No CID results found, showing toast");
                            ToastUtils.makeText(this, getString(R.string.no_matching_cids_found));
                            clearSearchResults();
                        } else {
                            // No more results
                            moreFidButton.setVisibility(View.GONE);
                            ToastUtils.makeText(this, getString(R.string.no_more_results));
                        }
                    }
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching partial CID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    clearSearchResults();
                });
            }
        }).start();
    }

    private void displaySearchResults(List<Freer> results) {
        // Clear existing results and selection
        keyCardContainer.clearAll();
        selectedCardView = null;

        // Show search results hint
        searchResultsHint.setVisibility(View.VISIBLE);

        // Hide more results hint when displaying new results
        moreResultsHint.setVisibility(View.GONE);

        // Convert Freer objects to KeyInfo objects and add them to KeyCardContainer
        for (Freer freer : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                // Don't set label - let CID value show in the CID field instead
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
    }

    private void clearSearchResults() {
        TimberLogger.d(TAG, "clearSearchResults() called");
        keyCardContainer.clearAll();
        searchResultsHint.setVisibility(View.GONE);
        moreFidButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
    }

    private void clearFidSearch() {
        // Clear the search input
        fidSearchEditText.setText("");

        // Clear search results
        clearSearchResults();

        // Reset selection state
        selectedFid = null;
        selectedFreer = null;
        selectedCardView = null;

        // Clear search parameters
        currentSearchTerm = null;
        currentSearchResults.clear();
        isExactFidSearch = false;

        ToastUtils.makeText(this, getString(R.string.cleared));
    }

    private void appendSearchResults(List<Freer> newResults) {
        // Convert Freer objects to KeyInfo objects and add them to KeyCardContainer
        for (Freer freer : newResults) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
    }

    private void updateMoreButtonVisibility(int resultsSize) {
        // Show More button if we got the full page size (20), indicating there might be more results
        if (resultsSize >= FreerApplication.DEFAULT_PAGE_SIZE && !isExactFidSearch) {
            moreFidButton.setVisibility(View.VISIBLE);
            moreFidButton.setEnabled(true);
            moreFidButton.setAlpha(1.0f);

            // Show hint text with remaining results count
            updateMoreResultsHint();
        } else {
            moreFidButton.setVisibility(View.GONE);
            moreResultsHint.setVisibility(View.GONE);
        }
    }

    private void loadMoreResults() {
        if (currentSearchTerm == null || fapiClient == null) {
            return;
        }

        // Show waiting dialog
        waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        waitingDialog.show();

        try {
            // Get the 'last' value from the last response
            List<String> afterValue = null;
            if (fapiClient.getLastResponse() != null &&
                fapiClient.getLastResponse().getLast() != null) {
                afterValue = fapiClient.getLastResponse().getLast();
            }

            if (afterValue != null) {
                searchPartialCid(currentSearchTerm, afterValue);
            } else {
                dismissWaitingDialog();
                ToastUtils.makeText(this, getString(R.string.no_more_results));
                moreFidButton.setVisibility(View.GONE);
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more results: %s", e.getMessage());
            dismissWaitingDialog();
            ToastUtils.makeText(this, getString(R.string.failed_to_load_more));
        }
    }

    private void onFidSelected(KeyInfo keyInfo) {
        // A nobody contact can be impersonated by anyone
        NobodyGuard.confirm(this, java.util.Collections.singletonList(keyInfo.getId()),
                R.string.nobody_consequence_contact, () -> selectFid(keyInfo));
    }

    private void selectFid(KeyInfo keyInfo) {
        selectedFid = keyInfo.getId();

        // Convert KeyInfo back to Freer for compatibility with existing logic
        selectedFreer = new Freer();
        selectedFreer.setId(keyInfo.getId());
        selectedFreer.setCid(keyInfo.getCid());
        selectedFreer.setPubkey(keyInfo.getPubkey());

        // Update card selection highlighting
        updateCardSelection(keyInfo);
        // Show selected FID in the search EditText with middle truncation
        String displayText = selectedFid;
        if (keyInfo.getCid() != null && !keyInfo.getCid().trim().isEmpty()) {
            displayText = keyInfo.getCid() + ": " + displayText;
        }
        fidSearchEditText.setText(displayText);
        ToastUtils.makeText(this, getString(R.string.toast_fid_selected));
    }

    private void onMenuItemClicked(String menuItemId, KeyInfo keyInfo) {
        if ("detail".equals(menuItemId)) {
            showKeyInfoDetail(keyInfo);
        }
    }

    private void showKeyInfoDetail(KeyInfo keyInfo) {
        try {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, keyInfo.toJson());
            intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, KeyInfo.class.getName());
            startActivity(intent);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error showing KeyInfo detail: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.error_showing_detail));
        }
    }

    private void updateCardSelection(KeyInfo selectedKeyInfo) {
        // Reset previous selection
        if (selectedCardView != null) {
            selectedCardView.setBackgroundResource(R.drawable.key_card_background);
        }

        // Find and highlight the selected card
        for (int i = 0; i < searchResultsLayout.getChildCount(); i++) {
            View cardView = searchResultsLayout.getChildAt(i);
            TextView keyIdView = cardView.findViewById(R.id.key_id);
            if (keyIdView != null && selectedKeyInfo.getId().equals(keyIdView.getText().toString())) {
                cardView.setBackgroundResource(R.drawable.key_card_selected_background);
                selectedCardView = cardView;
                break;
            }
        }
    }

    private void updateMoreResultsHint() {
        try {
            if (fapiClient != null &&
                    fapiClient.getLastResponse() != null &&
                fapiClient.getLastResponse().getTotal() != null) {

                long totalResults = fapiClient.getLastResponse().getTotal();
                int currentResultsCount = currentSearchResults.size();
                long remainingResults = totalResults - currentResultsCount;

                if (remainingResults > 0) {
                    String hintText = String.format("%d left", remainingResults);
                    moreResultsHint.setText(hintText);
                    moreResultsHint.setVisibility(View.VISIBLE);
                } else {
                    moreResultsHint.setVisibility(View.GONE);
                }
            } else {
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating more results hint: %s", e.getMessage());
            moreResultsHint.setVisibility(View.GONE);
        }
    }

    // Input handling and action methods

    private void clearAllInputs() {
        fidSearchEditText.setText("");
        titlesInput.setText("");
        memoInput.setText("");
        seeStatementCheckBox.setChecked(true);
        seeWritingsCheckBox.setChecked(true);

        // Clear FID selection
        selectedFid = null;
        selectedFreer = null;
        selectedCardView = null;
        clearSearchResults();

        ToastUtils.makeText(this, getString(R.string.cleared));
    }

    private void saveContactToDatabase() {
        if (selectedFid == null) {
            String inputtedText = fidSearchEditText.getText().toString().trim();
            if(inputtedText.isEmpty()){
                ToastUtils.makeText(this, getString(R.string.please_select_a_fid_first));
                return;
            }

            if(!KeyTools.isGoodFid(inputtedText)){
                ToastUtils.makeText(this, getString(R.string.invalid_fid));
                return;
            }
            selectedFid = inputtedText;
        }

        String titlesText = titlesInput.getText() != null ? titlesInput.getText().toString().trim() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString().trim() : "";
        boolean seeStatement = seeStatementCheckBox.isChecked();
        boolean seeWritings = seeWritingsCheckBox.isChecked();

        // Parse titles by comma
        List<String> titlesList = new ArrayList<>();
        if (!titlesText.isEmpty()) {
            String[] titlesArray = titlesText.split(",");
            for (String title : titlesArray) {
                String trimmedTitle = title.trim();
                if (!trimmedTitle.isEmpty()) {
                    titlesList.add(trimmedTitle);
                }
            }
        }



        Contact contact = new Contact();
        contact.setFid(selectedFid);
        if (!titlesList.isEmpty()) contact.setTitles(titlesList);
        if (!memo.isEmpty()) contact.setMemo(memo);
        contact.setSeeStatement(seeStatement);
        contact.setSeeWritings(seeWritings);

        if (selectedFreer != null) {
            if(selectedFreer.getCid()!=null) {
                CidFidManager cidFidManager = CidFidManager.getInstance(this);
                cidFidManager.add(selectedFreer.getId(), selectedFreer.getCid());
            }
            contact.setPubkey(selectedFreer.getPubkey());
            String fee = selectedFreer.getNoticeFee();
            if (fee != null) {
                contact.setNoticeFee(fee);
            }
        }
        // Encrypt contact details and save to database only (no blockchain operation)
        contact.setOnChain(false); // Mark as off-chain
        contact.setLastHeight(Constants.MaX_HEIGHT); // Set update height to a high value
        contact.checkIdWithCreate();


        ContactManager contactManager = ContactManager.getInstance();
        boolean existed = contactManager.checkIfExisted(contact.getId());
        if (existed) {
            DialogUtils.show(new AlertDialog.Builder(this)
                    .setTitle(R.string.contact_existed_title)
                    .setMessage(R.string.contact_already_exists_message)
                    .setPositiveButton(R.string.replace, (dialog, which) -> {
                        contactManager.saveAndFinish(this, contact,false);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    );
        } else {
            contactManager.saveAndFinish(this, contact,false);
        }
    }

    private void carveContact() {
        if (selectedFid == null) {
            ToastUtils.makeText(this, getString(R.string.toast_please_select_fid));
            return;
        }

        String titlesText = titlesInput.getText() != null ? titlesInput.getText().toString().trim() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString().trim() : "";
        boolean seeStatement = seeStatementCheckBox.isChecked();
        boolean seeWritings = seeWritingsCheckBox.isChecked();

        // Parse titles by comma
        List<String> titlesList = new ArrayList<>();
        if (!titlesText.isEmpty()) {
            String[] titlesArray = titlesText.split(",");
            for (String title : titlesArray) {
                String trimmedTitle = title.trim();
                if (!trimmedTitle.isEmpty()) {
                    titlesList.add(trimmedTitle);
                }
            }
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String pubkey = liveKeyInfo.getPubkey();

        Contact contact = new Contact();
        contact.setFid(selectedFid);
        if (!titlesList.isEmpty()) contact.setTitles(titlesList);
        if (!memo.isEmpty()) contact.setMemo(memo);
        contact.setSeeStatement(seeStatement);
        contact.setSeeWritings(seeWritings);

        if (selectedFreer != null) {
            contact.setCid(selectedFreer.getCid());
            contact.setPubkey(selectedFreer.getPubkey());
            String fee = selectedFreer.getNoticeFee();
            if (fee != null) {
                contact.setNoticeFee(fee);
            }
        }

        String feipJson = makeAddContactFeip(contact, pubkey);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            contact.setId(txId);
                            contact.setOnChain(null);
                            encryptContactContent(contact, pubkey);
                            contact.setLastHeight(Constants.MaX_HEIGHT);
                            ContactManager.getInstance().saveAndFinish(CreateContactActivity.this, contact,true);
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(CreateContactActivity.this, getString(R.string.toast_failed_carve_feip, errorMessage));
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(CreateContactActivity.this, getString(R.string.toast_sign_tx_failed_unsigned));
                            txSender.showUnsignedTxAsQR(CreateContactActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            // Show signed transaction as QR code for manual broadcasting
                            txSender.showSignedTxAsQR(CreateContactActivity.this, signedTxHex);
                        });
                    }
                });
            }).start();
        } else {
            contact.checkIdWithCreate();

            ContactManager contactManager = ContactManager.getInstance();
            boolean existed = contactManager.checkIfExisted(contact.getId());
            if (existed) {
                DialogUtils.show(new AlertDialog.Builder(this)
                        .setTitle(R.string.contact_existed_title)
                        .setMessage(R.string.contact_already_exists_message)
                        .setPositiveButton(R.string.replace, (dialog, which) -> {
                            encryptContactContent(contact, pubkey);
                            contact.setOnChain(null);
                            contactManager.saveAndFinish(this, contact,true);
                        })
                        .setNegativeButton(R.string.cancel, null)
                        );
            } else {
                encryptContactContent(contact, pubkey);
                contact.setOnChain(null);
                contactManager.saveAndFinish(this, contact,true);
            }
        }
    }

    private static String makeAddContactFeip(Contact contact, String pubkey) {
        String contactDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(contact.toJson(), pubkey).toJson();
        Feip feip = Feip.fromName(CONTACT);
        ContactOpData contactOpData = ContactOpData.makeAdd(null, contactDetailCipher);
        feip.setData(contactOpData);
        return feip.toJson();
    }

    private static void encryptContactContent(Contact contact, String pubkey) {
        if (pubkey == null || contact == null) return;
        // Encrypt contact details
        String contactCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptStrByAsyOneWay(contact.toJson(), pubkey).toJson();
        contact.setCipher(contactCipher);
    }

    @Override
    protected void onDestroy() {
        dismissWaitingDialog();
        super.onDestroy();
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
            waitingDialog = null;
        }
    }
}