package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.DESC;
import static com.fc.fc_ajdk.constants.FieldNames.FREER;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.USED_CIDS;

import android.content.Intent;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.contact.ChooseContactActivity;
import com.fc.freer.ui.MenuItem;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SearchFidsOnChainActivity extends BaseCryptoActivity {
    private static final String TAG = "SearchFidsOnChain";

    public static final String EXTRA_CHOOSE_MODE = "extra_choose_mode";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_SELECTED_FIDS = "extra_selected_fids";
    public static final String EXTRA_FID_LIST = "extra_fid_list";

    private TextView searchResultsHint;
    private EditText fidSearchEditText;
    private View searchIcon;
    private View clearSearchIcon;
    private ImageButton contactButton;
    private LinearLayout searchResultsLayout;
    private ImageButton confirmButton;
    private ImageButton moreButton;
    private ImageButton clearAllButton;
    private ImageButton addToSelectedButton;
    private TextView moreResultsHint;

    private FapiClient fapiClient;
    private KeyCardContainer keyCardContainer;
    private WaitingDialog waitingDialog;
    private ChooseMode chooseMode;

    private List<Freer> currentSearchResults = new ArrayList<>();
    private String currentSearchTerm = null;
    private boolean isExactFidSearch = false;
    private List<String> lastValue;
    private boolean hasFidListParam = false;

    private final List<KeyInfo> selectedKeyInfoList = new ArrayList<>();
    private final Set<String> selectedFidSet = new HashSet<>();
    private boolean isShowingSearchResults = false;

    private ActivityResultLauncher<Intent> chooseContactLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_search_fids_on_chain;
    }

    @Override
    protected String getActivityTitle() {
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        return title != null ? title : getString(R.string.search_fids_on_chain);
    }

    @Override
    protected void initializeViews() {
        searchResultsHint = findViewById(R.id.searchResultsHint);
        fidSearchEditText = findViewById(R.id.fidSearchEditText);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        contactButton = findViewById(R.id.contactButton);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        confirmButton = findViewById(R.id.confirmButton);
        moreButton = findViewById(R.id.moreButton);
        clearAllButton = findViewById(R.id.clearAllButton);
        addToSelectedButton = findViewById(R.id.addToSelectedButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);

        String modeStr = getIntent().getStringExtra(EXTRA_CHOOSE_MODE);
        chooseMode = ChooseMode.CHOOSE_ONE_RETURN;
        if (modeStr != null) {
            try {
                chooseMode = ChooseMode.valueOf(modeStr);
            } catch (IllegalArgumentException e) {
                TimberLogger.w(TAG, "Invalid choose mode: %s", modeStr);
            }
        }

        confirmButton.setEnabled(false);
        confirmButton.setAlpha(0.5f);

        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN) {
            confirmButton.setVisibility(View.GONE);
            addToSelectedButton.setVisibility(View.GONE);
        }

        ArrayList<String> fidList = getIntent().getStringArrayListExtra(EXTRA_FID_LIST);
        hasFidListParam = fidList != null && !fidList.isEmpty();
        if (hasFidListParam) {
            contactButton.setVisibility(View.GONE);
        }

        initializeActivityResultLaunchers();
        buildContainerForCurrentMode();

        if (hasFidListParam && isMultiSelectMode()) {
            loadFidListIntoSelected(fidList);
        }

        ApiCenter apiCenter = ApiCenter.getInstance();
        if (apiCenter != null) {
            fapiClient = (FapiClient) apiCenter.getClient(
                    com.fc.fc_ajdk.data.feipData.Service.ServiceType.FAPI_No1_NrC7);
        }
    }

    private void initializeActivityResultLaunchers() {
        chooseContactLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        handleContactResult(result.getData());
                    }
                }
        );
    }

    private boolean isMultiSelectMode() {
        return chooseMode == ChooseMode.CHOOSE_MULTI || chooseMode == ChooseMode.CHOOSE_ONE;
    }

    // ================================
    // CONTAINER MODE MANAGEMENT
    // ================================

    private void buildContainerForCurrentMode() {
        if (isShowingSearchResults) {
            buildSearchResultsContainer();
        } else {
            buildSelectedCardsContainer();
        }
    }

    private void buildSearchResultsContainer() {
        searchResultsLayout.removeAllViews();
        isShowingSearchResults = true;

        ChooseMode containerMode = isMultiSelectMode() ? ChooseMode.CHOOSE_MULTI : ChooseMode.CHOOSE_ONE_RETURN;
        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(MenuItem.createDetailMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, searchResultsLayout, containerMode, menuItems, true);

        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN) {
            keyCardContainer.setOnKeyClickedListener(this::onSingleFidSelected);
        } else {
            keyCardContainer.setOnKeyListChangedListener(list -> updateAddToSelectedButtonVisibility());
        }

        searchResultsHint.setText(R.string.search_results);
    }

    private void buildSelectedCardsContainer() {
        searchResultsLayout.removeAllViews();
        isShowingSearchResults = false;
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
        addToSelectedButton.setVisibility(View.GONE);

        if (!isMultiSelectMode()) {
            keyCardContainer = null;
            searchResultsHint.setVisibility(View.GONE);
            return;
        }

        keyCardContainer = new KeyCardContainer(this, searchResultsLayout, ChooseMode.WITHOUT_CHOOSE_WITH_DELETE, null, false);
        keyCardContainer.setShowDefaultMenuItems(false);
        keyCardContainer.setOnKeyListChangedListener(list -> {
            syncSelectedFromContainer();
            updateHintAndConfirmState();
        });
        keyCardContainer.setOnMenuItemClickListener((menuItemId, keyInfo) -> {
            if ("delete".equals(menuItemId)) {
                selectedFidSet.remove(keyInfo.getId());
                selectedKeyInfoList.removeIf(k -> k.getId().equals(keyInfo.getId()));
            }
        });

        for (KeyInfo keyInfo : new ArrayList<>(selectedKeyInfoList)) {
            keyCardContainer.addKeyCard(keyInfo);
        }

        updateHintAndConfirmState();
    }

    private void syncSelectedFromContainer() {
        if (keyCardContainer == null) return;
        selectedKeyInfoList.clear();
        selectedFidSet.clear();
        for (KeyInfo ki : keyCardContainer.getKeyInfoList()) {
            selectedKeyInfoList.add(ki);
            selectedFidSet.add(ki.getId());
        }
    }

    private void switchToSelectedView() {
        buildSelectedCardsContainer();
    }

    private void updateHintAndConfirmState() {
        int count = selectedKeyInfoList.size();
        if (count > 0) {
            searchResultsHint.setVisibility(View.VISIBLE);
            searchResultsHint.setText(getString(R.string.selected_fids_count, count));
        } else {
            searchResultsHint.setVisibility(View.GONE);
        }
        confirmButton.setEnabled(count > 0);
        confirmButton.setAlpha(count > 0 ? 1.0f : 0.5f);
    }

    // ================================
    // BUTTONS
    // ================================

    @Override
    protected void setupButtons() {
        searchIcon.setOnClickListener(v -> {
            hideKeyboard();
            performSearch();
        });
        clearSearchIcon.setOnClickListener(v -> {
            fidSearchEditText.setText("");
            hideKeyboard();
            clearSearchAndShowSelected();
        });
        contactButton.setOnClickListener(v -> {
            hideKeyboard();
            openContactPicker();
        });
        confirmButton.setOnClickListener(v -> {
            hideKeyboard();
            confirmSelection();
        });
        clearAllButton.setOnClickListener(v -> {
            hideKeyboard();
            clearAll();
        });
        addToSelectedButton.setOnClickListener(v -> {
            hideKeyboard();
            addCheckedToSelected();
        });
        moreButton.setOnClickListener(v -> loadMoreResults());
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);

        fidSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchIcon.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        fidSearchEditText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) hideKeyboard();
        });
    }

    @Override
    protected void setupBackButton() {
        backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                setResult(RESULT_CANCELED);
                finish();
            });
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    // ================================
    // CONTACT PICKER
    // ================================

    private void openContactPicker() {
        Intent intent = new Intent(this, ChooseContactActivity.class);
        if (isMultiSelectMode()) {
            intent.putExtra(ChooseContactActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
        } else {
            intent.putExtra(ChooseContactActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE_RETURN.name());
        }
        chooseContactLauncher.launch(intent);
    }

    private void handleContactResult(Intent data) {
        if (isMultiSelectMode()) {
            String contactsJson = data.getStringExtra(ChooseContactActivity.EXTRA_SELECTED_CONTACTS);
            if (contactsJson != null) {
                List<Contact> contacts = JsonUtils.listFromJson(contactsJson, Contact.class);
                if (contacts != null && !contacts.isEmpty()) {
                    addContactFidsToSelected(contacts);
                }
            }
        } else {
            String contactJson = data.getStringExtra(ChooseContactActivity.EXTRA_SELECTED_CONTACT);
            if (contactJson != null) {
                Contact contact = Contact.fromJson(contactJson, Contact.class);
                if (contact != null && contact.getFid() != null) {
                    returnSingleFid(contact.getFid());
                }
            }
        }
    }

    private void addContactFidsToSelected(List<Contact> contacts) {
        List<String> fidsToLookup = new ArrayList<>();
        for (Contact contact : contacts) {
            String fid = contact.getFid();
            if (fid != null && !fid.isEmpty() && !selectedFidSet.contains(fid)) {
                fidsToLookup.add(fid);
            }
        }

        if (fidsToLookup.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_fid_selected));
            return;
        }

        if (fapiClient == null) {
            for (String fid : fidsToLookup) {
                addFidToSelectedData(fid, null);
            }
            switchToSelectedView();
            return;
        }

        waitingDialog = new WaitingDialog(this, getString(R.string.searching));
        waitingDialog.show();

        new Thread(() -> {
            Map<String, Freer> freerMap = fapiClient.freerByIds(fidsToLookup);
            runOnUiThread(() -> {
                dismissWaitingDialog();
                for (String fid : fidsToLookup) {
                    Freer freer = freerMap != null ? freerMap.get(fid) : null;
                    if (freer != null) {
                        KeyInfo keyInfo = KeyInfo.fromCid(freer);
                        if (keyInfo != null) {
                            addKeyInfoToSelectedData(keyInfo);
                        }
                    } else {
                        addFidToSelectedData(fid, null);
                    }
                }
                switchToSelectedView();
            });
        }).start();
    }

    private void addFidToSelectedData(String fid, String cid) {
        if (selectedFidSet.contains(fid)) return;
        KeyInfo keyInfo = new KeyInfo();
        keyInfo.setId(fid);
        if (cid != null) keyInfo.setCid(cid);
        selectedFidSet.add(fid);
        selectedKeyInfoList.add(keyInfo);
    }

    private void addKeyInfoToSelectedData(KeyInfo keyInfo) {
        if (keyInfo == null || selectedFidSet.contains(keyInfo.getId())) return;
        selectedFidSet.add(keyInfo.getId());
        selectedKeyInfoList.add(keyInfo);
    }

    // ================================
    // SEARCH METHODS
    // ================================

    private void performSearch() {
        String searchString = fidSearchEditText.getText().toString().trim();
        if (TextUtils.isEmpty(searchString)) {
            ToastUtils.makeText(this, getString(R.string.please_enter_search_term));
            return;
        }

        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.apip_client_not_available));
            return;
        }

        waitingDialog = new WaitingDialog(this, getString(R.string.searching));
        waitingDialog.show();

        currentSearchTerm = searchString;
        isExactFidSearch = KeyTools.isGoodFid(searchString);
        currentSearchResults.clear();

        if (isExactFidSearch) {
            searchExactFid(searchString);
        } else {
            searchPartialCid(searchString, null);
        }
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
                        moreButton.setVisibility(View.GONE);
                    } else {
                        ToastUtils.makeText(this, getString(R.string.fid_not_found));
                        clearSearchAndShowSelected();
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching exact FID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    clearSearchAndShowSelected();
                });
            }
        }).start();
    }

    private void searchPartialCid(String searchString, List<String> afterValue) {
        new Thread(() -> {
            try {
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewQuery().addNewPart().addNewFields(ID, USED_CIDS).addNewValue(searchString);
                fcdsl.setSize(String.valueOf(FreerApplication.DEFAULT_PAGE_SIZE));
                fcdsl.addSort(LAST_HEIGHT, DESC).addSort(ID, DESC);
                if (afterValue != null) {
                    fcdsl.setAfter(afterValue);
                }

                List<Freer> results = fapiClient.entitySearch(FREER, fcdsl, Freer.class);

                if (fapiClient.getLastResponse() != null &&
                        fapiClient.getLastResponse().getLast() != null) {
                    lastValue = fapiClient.getLastResponse().getLast();
                }

                runOnUiThread(() -> {
                    dismissWaitingDialog();

                    if (results != null && !results.isEmpty()) {
                        if (afterValue == null) {
                            currentSearchResults.clear();
                            currentSearchResults.addAll(results);
                            displaySearchResults(currentSearchResults);
                        } else {
                            currentSearchResults.addAll(results);
                            appendSearchResults(results);
                        }
                        updateMoreButtonVisibility(results.size());
                    } else {
                        if (afterValue == null) {
                            ToastUtils.makeText(this, getString(R.string.no_matching_cids_found));
                            clearSearchAndShowSelected();
                        } else {
                            moreButton.setVisibility(View.GONE);
                            ToastUtils.makeText(this, getString(R.string.no_more_results));
                        }
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching partial CID: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.search_failed));
                    clearSearchAndShowSelected();
                });
            }
        }).start();
    }

    private void displaySearchResults(List<Freer> results) {
        buildSearchResultsContainer();
        searchResultsHint.setVisibility(View.VISIBLE);
        moreResultsHint.setVisibility(View.GONE);

        for (Freer freer : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                keyCardContainer.addKeyCard(keyInfo);
            }
        }

        updateAddToSelectedButtonVisibility();
    }

    private void appendSearchResults(List<Freer> newResults) {
        if (keyCardContainer == null) return;
        for (Freer freer : newResults) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
    }

    private void clearSearchAndShowSelected() {
        currentSearchTerm = null;
        currentSearchResults.clear();
        isExactFidSearch = false;
        switchToSelectedView();
    }

    private void clearAll() {
        fidSearchEditText.setText("");
        currentSearchTerm = null;
        currentSearchResults.clear();
        isExactFidSearch = false;
        selectedKeyInfoList.clear();
        selectedFidSet.clear();
        switchToSelectedView();
    }

    private void updateMoreButtonVisibility(int resultsSize) {
        if (resultsSize >= FreerApplication.DEFAULT_PAGE_SIZE && !isExactFidSearch) {
            moreButton.setVisibility(View.VISIBLE);
            moreButton.setEnabled(true);
            moreButton.setAlpha(1.0f);
            updateMoreResultsHint();
        } else {
            moreButton.setVisibility(View.GONE);
            moreResultsHint.setVisibility(View.GONE);
        }
    }

    private void loadMoreResults() {
        if (currentSearchTerm == null || fapiClient == null) return;

        waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        waitingDialog.show();

        if (lastValue != null) {
            searchPartialCid(currentSearchTerm, lastValue);
        } else {
            dismissWaitingDialog();
            ToastUtils.makeText(this, getString(R.string.no_more_results));
            moreButton.setVisibility(View.GONE);
            moreResultsHint.setVisibility(View.GONE);
        }
    }

    private void updateMoreResultsHint() {
        try {
            if (fapiClient != null &&
                    fapiClient.getLastResponse() != null &&
                    fapiClient.getLastResponse().getTotal() != null) {
                long totalResults = fapiClient.getLastResponse().getTotal();
                int currentCount = currentSearchResults.size();
                long remaining = totalResults - currentCount;
                if (remaining > 0) {
                    moreResultsHint.setText(String.format("%d left", remaining));
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

    // ================================
    // SELECTION MANAGEMENT
    // ================================

    private void addCheckedToSelected() {
        if (keyCardContainer == null || !isShowingSearchResults) return;

        List<KeyInfo> checkedKeys = keyCardContainer.getSelectedKeys();
        if (checkedKeys.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_fid_selected));
            return;
        }

        for (KeyInfo keyInfo : checkedKeys) {
            addKeyInfoToSelectedData(keyInfo);
        }

        fidSearchEditText.setText("");
        currentSearchTerm = null;
        currentSearchResults.clear();
        isExactFidSearch = false;
        switchToSelectedView();
    }

    private void updateAddToSelectedButtonVisibility() {
        if (!isMultiSelectMode() || !isShowingSearchResults) {
            addToSelectedButton.setVisibility(View.GONE);
            return;
        }
        boolean hasResults = keyCardContainer != null && !keyCardContainer.getKeyInfoList().isEmpty();
        addToSelectedButton.setVisibility(hasResults ? View.VISIBLE : View.GONE);
    }

    private void onSingleFidSelected(KeyInfo keyInfo) {
        returnSingleFid(keyInfo.getId());
    }

    private void returnSingleFid(String fid) {
        ArrayList<String> result = new ArrayList<>();
        result.add(fid);
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_SELECTED_FIDS, result);
        setResult(RESULT_OK, data);
        finish();
    }

    private void confirmSelection() {
        if (selectedKeyInfoList.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_fid_selected);
            return;
        }

        ArrayList<String> selectedFids = new ArrayList<>();
        for (KeyInfo ki : selectedKeyInfoList) {
            selectedFids.add(ki.getId());
        }

        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_SELECTED_FIDS, selectedFids);
        setResult(RESULT_OK, data);
        finish();
    }

    // ================================
    // FID LIST PARAMETER HANDLING
    // ================================

    private void loadFidListIntoSelected(ArrayList<String> fidList) {
        if (fidList == null || fidList.isEmpty()) return;

        if (fapiClient == null) {
            for (String fid : fidList) {
                addFidToSelectedData(fid, null);
            }
            switchToSelectedView();
            return;
        }

        waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        waitingDialog.show();

        new Thread(() -> {
            Map<String, Freer> freerMap = fapiClient.freerByIds(fidList);
            runOnUiThread(() -> {
                dismissWaitingDialog();
                for (String fid : fidList) {
                    Freer freer = freerMap != null ? freerMap.get(fid) : null;
                    if (freer != null) {
                        KeyInfo keyInfo = KeyInfo.fromCid(freer);
                        if (keyInfo != null) {
                            addKeyInfoToSelectedData(keyInfo);
                        }
                    } else {
                        addFidToSelectedData(fid, null);
                    }
                }
                switchToSelectedView();
            });
        }).start();
    }

    // ================================
    // LIFECYCLE
    // ================================

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
