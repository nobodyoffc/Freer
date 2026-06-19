package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.FieldNames.FREER;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class AddFidActivity extends BaseCryptoActivity {
    private static final String TAG = "AddFidActivity";
    private static final int QR_SCAN_FID_REQUEST_CODE = 1001;

    // FID Search Components
    private TextView searchResultsHint;
    private EditText fidSearchEditText;
    private ImageButton searchButton;
    private LinearLayout searchResultsLayout;
    private ImageButton clearFidButton;
    private ImageButton moreFidButton;
    private TextView moreResultsHint;

    // Original Input Components
    private LinearLayout fidInputContainer;
    private LinearLayout buttonContainer;
    private TextInputEditText fidInput;
    private ImageButton clearButton;
    private ImageButton addButton;

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

        setupData();
        TimberLogger.i(TAG, "onCreate started");
    }

    private void initViews() {
        // FID Search Components
        searchResultsHint = findViewById(R.id.searchResultsHint);
        fidSearchEditText = findViewById(R.id.fidSearchEditText);
        searchButton = findViewById(R.id.searchButton);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        clearFidButton = findViewById(R.id.clearFidButton);
        moreFidButton = findViewById(R.id.moreFidButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);

        // Original Input Components
        fidInputContainer = findViewById(R.id.fidInputContainer);
        buttonContainer = findViewById(R.id.buttonContainer);

        fidInput = fidInputContainer.findViewById(R.id.fidInput).findViewById(R.id.textInput);
        fidInput.setHint(R.string.input_fid_or_fid_list_separated_by_comma_or_space);

        clearButton = findViewById(R.id.clearButton);
        addButton = findViewById(R.id.addButton);

        setupTextIcons(R.id.fidInput, R.id.scanIcon, QR_SCAN_FID_REQUEST_CODE);

        // Initialize FID search KeyCardContainer with CHOOSE_ONE_RETURN mode
        List<com.fc.freer.ui.MenuItem> menuItems = new ArrayList<>();
        menuItems.add(com.fc.freer.ui.MenuItem.createDetailMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, searchResultsLayout, ChooseMode.CHOOSE_ONE_RETURN, menuItems);
        keyCardContainer.setOnKeyClickedListener(this::onFidSelected);
        keyCardContainer.setOnMenuItemClickListener(this::onMenuItemClicked);

        // Initially hide search results
        searchResultsHint.setVisibility(View.GONE);
        moreFidButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
    }

    private void setupListeners() {
        // FID Search listeners
        searchButton.setOnClickListener(v -> performFidSearch());
        clearFidButton.setOnClickListener(v -> clearFidSearch());
        moreFidButton.setOnClickListener(v -> loadMoreResults());

        // Original listeners
        clearButton.setOnClickListener(v -> {
            // Hide keyboard
            View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
            fidInput.setText("");
            // Clear search results as well
            clearFidSearch();
        });

        addButton.setOnClickListener(v -> {
            String text = fidInput.getText() != null ? fidInput.getText().toString() : "";

            // Clean the input text
            text = text.replace("[", "").replace("]", "").replace("\"", "").trim();

            // Split by common separators and clean each FID
            String[] fids = text.split("[, \n\t]+");
            boolean hasValidFid = false;

            for (String fid : fids) {
                fid = fid.trim();
                if (!fid.isEmpty() && KeyTools.isGoodFid(fid)) {
                    FreerApplication.addFid(fid);
                    hasValidFid = true;
                }
            }

            if (!hasValidFid) {
                ToastUtils.makeText(this, R.string.invalid_fid);
                finish();
                return;
            }

            setResult(RESULT_OK);
            finish();
        });

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
    protected int getLayoutId() {
        return R.layout.activity_add_fid;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.add_fid);
    }

    @Override
    protected void initializeViews() {
        initViews();
    }

    @Override
    protected void setupButtons() {
        setupListeners();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (requestCode == QR_SCAN_FID_REQUEST_CODE) {
            fidInput.setText(qrContent);
        }
    }

    public static Intent createIntent(Context context) {
        return new Intent(context, AddFidActivity.class);
    }

    // FID Search Methods

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

                List<Freer> results = fapiClient.entitySearch(FREER,fcdsl,Freer.class);

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
        selectedFid = keyInfo.getId();

        // Convert KeyInfo back to Freer for compatibility with existing logic
        selectedFreer = new Freer();
        selectedFreer.setId(keyInfo.getId());
        selectedFreer.setCid(keyInfo.getCid());
        selectedFreer.setPubkey(keyInfo.getPubkey());

        // Update card selection highlighting
        updateCardSelection(keyInfo);

        // Add the selected FID to the text input
        String currentText = fidInput.getText() != null ? fidInput.getText().toString().trim() : "";
        if (!currentText.isEmpty()) {
            currentText += ", ";
        }
        currentText += selectedFid;
        fidInput.setText(currentText);

        ToastUtils.makeText(this, "FID added to input");
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