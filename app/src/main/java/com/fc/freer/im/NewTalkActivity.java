package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.CID;
import static com.fc.fc_ajdk.constants.FieldNames.DESC;
import static com.fc.fc_ajdk.constants.FieldNames.FREER;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
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
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fcData.TalkPartner;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.im.SearchFidsOnChainActivity;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.model.Setting;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.MenuItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Activity for finding and choosing a FID to start a new P2P chat.
 * Allows searching FIDs on-chain or choosing from contacts.
 */
public class NewTalkActivity extends BaseCryptoActivity {
    private static final String TAG = "NewTalkActivity";

    private TextView searchResultsHint;
    private EditText fidSearchEditText;
    private View searchIcon;
    private View clearSearchIcon;
    private LinearLayout searchResultsLayout;
    private ImageButton clearButton;
    private ImageButton contactButton;
    private ImageButton moreButton;
    private TextView moreResultsHint;

    private String selectedFid = null;
    private Freer selectedFreer = null;
    private FapiClient fapiClient;
    private KeyCardContainer keyCardContainer;
    private WaitingDialog waitingDialog;
    private Setting currentSetting;
    private List<Freer> currentSearchResults = new ArrayList<>();
    private String currentSearchTerm = null;
    private boolean isExactFidSearch = false;
    private List<String> lastSearchAfter = null;

    private ActivityResultLauncher<Intent> chooseContactLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_new_talk;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.new_chat);
    }

    @Override
    protected void initializeViews() {
        initViews();
        setupData();
        initActivityResultLaunchers();
    }

    @Override
    protected void setupButtons() {
        setupListeners();
    }

    @Override
    protected void setupBackButton() {
        backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        if (qrContent != null && !qrContent.isEmpty()) {
            fidSearchEditText.setText(qrContent);
            performSearch();
        }
    }

    private void initViews() {
        searchResultsHint = findViewById(R.id.searchResultsHint);
        fidSearchEditText = findViewById(R.id.fidSearchEditText);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);
        searchResultsLayout = findViewById(R.id.searchResultsLayout);
        clearButton = findViewById(R.id.clearButton);
        contactButton = findViewById(R.id.contactButton);
        moreButton = findViewById(R.id.moreButton);
        moreResultsHint = findViewById(R.id.moreResultsHint);

        List<MenuItem> menuItems = new ArrayList<>();
        menuItems.add(MenuItem.createDetailMenuItem(this));
        keyCardContainer = new KeyCardContainer(this, searchResultsLayout, ChooseMode.CHOOSE_ONE_RETURN, menuItems, true);
        keyCardContainer.setOnKeyClickedListener(this::onFidSelected);
        keyCardContainer.setOnMenuItemClickListener(this::onMenuItemClicked);
    }

    private void setupData() {
        try {
            currentSetting = SettingManager.getInstance().getCurrentSetting();
            if (currentSetting == null) {
                ToastUtils.makeText(this, getString(R.string.current_setting_not_available));
                finish();
                return;
            }

            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter != null) {
                fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting up data: %s", e.getMessage());
        }
    }

    private void initActivityResultLaunchers() {
        chooseContactLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        java.util.List<String> fids = result.getData()
                                .getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty()) {
                            openChatWithFid(fids.get(0));
                        }
                    }
                }
        );
    }

    private void openChatWithFid(String fid) {
        if (fid == null || fid.isEmpty()) return;
        try {
            ensureTalkPartner(fid, null, null, null, TalkPartner.SOURCE_CONTACT);

            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.P2P.name());
            intent.putExtra(ChatActivity.EXTRA_TARGET_ID, fid);
            startActivity(intent);
            finish();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error opening chat: %s", e.getMessage());
            ToastUtils.makeText(this, getString(R.string.error_showing_detail));
        }
    }

    private void setupListeners() {
        searchIcon.setOnClickListener(v -> {
            hideKeyboard();
            performSearch();
        });
        clearSearchIcon.setOnClickListener(v -> {
            fidSearchEditText.setText("");
            hideKeyboard();
        });
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearAll();
        });
        contactButton.setOnClickListener(v -> {
            hideKeyboard();
            openContactPicker();
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
            if (!hasFocus) {
                hideKeyboard();
            }
        });
    }

    private void openContactPicker() {
        Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE_RETURN.name());
        chooseContactLauncher.launch(intent);
    }

    private void performSearch() {
        String searchString = fidSearchEditText.getText().toString().trim();
        if (TextUtils.isEmpty(searchString)) {
            ToastUtils.makeText(this, getString(R.string.please_enter_search_term));
            return;
        }

        if (searchString.toLowerCase().startsWith("fudp://")) {
            connectViaFudp(searchString);
            return;
        }

        if (fapiClient == null) {
            ToastUtils.makeText(this, getString(R.string.apip_client_not_available));
            return;
        }

        waitingDialog = new WaitingDialog(this, getString(R.string.searching));
        waitingDialog.show();

        currentSearchTerm = searchString;
        isExactFidSearch = isValidFidFormat(searchString);

        currentSearchResults.clear();

        if (isExactFidSearch) {
            searchExactFid(searchString);
        } else {
            searchPartialCid(searchString, null);
        }
    }

    private void connectViaFudp(String fudpUrl) {
        FudpNode fudpNode = currentSetting.getFudpNode();
        if (fudpNode == null || !fudpNode.isRunning()) {
            ToastUtils.makeText(this, getString(R.string.fudp_node_not_running));
            return;
        }

        FapiClient.Endpoint endpoint = FapiClient.parseFudpUrl(fudpUrl);
        if (endpoint == null) {
            ToastUtils.makeText(this, getString(R.string.invalid_fudp_url));
            return;
        }

        waitingDialog = new WaitingDialog(this, getString(R.string.connecting_fudp));
        waitingDialog.show();

        new Thread(() -> {
            try {
                byte[] pubkeyBytes = fudpNode.discoverPublicKey(
                        endpoint.host(), endpoint.port(), FapiClient.DEFAULT_HELLO_TIMEOUT_MS
                ).get(FapiClient.DEFAULT_HELLO_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);

                if (pubkeyBytes == null || pubkeyBytes.length == 0) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.fudp_hello_failed));
                    });
                    return;
                }

                String fid = KeyTools.pubkeyToFchAddr(pubkeyBytes);
                String pubkeyHex = Hex.toHex(pubkeyBytes);

                fudpNode.addPeer(fid, pubkeyBytes, endpoint.host(), endpoint.port());

                Freer freer = null;
                if (fapiClient != null) {
                    try {
                        freer = fapiClient.getFreer(fid);
                    } catch (Exception e) {
                        TimberLogger.w(TAG, "Failed to fetch Freer from chain: %s", e.getMessage());
                    }
                }

                if (freer == null) {
                    freer = new Freer();
                    freer.setId(fid);
                    freer.setPubkey(pubkeyHex);
                    Map<String, String> home = new HashMap<>();
                    home.put("FUDP@No1_NrC7", fudpUrl);
                    freer.setHome(home);
                }

                if (freer.getHome() == null) {
                    Map<String, String> home = new HashMap<>();
                    home.put("FUDP@No1_NrC7", fudpUrl);
                    freer.setHome(home);
                } else if (!freer.getHome().containsKey("FUDP@No1_NrC7")) {
                    freer.getHome().put("FUDP@No1_NrC7", fudpUrl);
                }

                TalkPartner partner = new TalkPartner(fid, pubkeyHex);
                partner.setCid(freer.getCid());
                partner.setHome(freer.getHome());
                partner.setSource(TalkPartner.SOURCE_FUDP_DISCOVER);

                ImManager imManager = currentSetting.getImManager();
                if (imManager != null) {
                    imManager.addTalkPartner(partner);
                }

                final Freer resultFreer = freer;
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.fudp_connected_successfully));

                    String chatDisplayName = resultFreer.getCid();
                    Intent intent = new Intent(this, ChatActivity.class);
                    intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.P2P.name());
                    intent.putExtra(ChatActivity.EXTRA_TARGET_ID, fid);
                    if (chatDisplayName != null) {
                        intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, chatDisplayName);
                    }
                    startActivity(intent);
                    finish();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "FUDP connect failed: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.fudp_connect_failed, e.getMessage()));
                });
            }
        }).start();
    }

    private boolean isValidFidFormat(String fid) {
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
                        moreButton.setVisibility(View.GONE);
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
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewQuery().addNewPart().addNewFields(ID, CID).addNewValue(searchString);
                fcdsl.setSize(String.valueOf(FreerApplication.DEFAULT_PAGE_SIZE));
                fcdsl.addSort(LAST_HEIGHT,DESC).addSort(ID,DESC);

                if (afterValue != null) {
                    fcdsl.setAfter(afterValue);
                }

                List<Freer> results = fapiClient.entitySearch(FREER, fcdsl, Freer.class);

                if (fapiClient.getLastResponse() != null) {
                    lastSearchAfter = fapiClient.getLastResponse().getLast();
                } else {
                    lastSearchAfter = null;
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
                            clearSearchResults();
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
                    clearSearchResults();
                });
            }
        }).start();
    }

    private void displaySearchResults(List<Freer> results) {
        keyCardContainer.clearAll();

        searchResultsHint.setVisibility(View.VISIBLE);
        moreResultsHint.setVisibility(View.GONE);

        for (Freer freer : results) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
    }

    private void clearSearchResults() {
        keyCardContainer.clearAll();
        searchResultsHint.setVisibility(View.GONE);
        moreButton.setVisibility(View.GONE);
        moreResultsHint.setVisibility(View.GONE);
    }

    private void clearAll() {
        fidSearchEditText.setText("");
        clearSearchResults();

        selectedFid = null;
        selectedFreer = null;

        currentSearchTerm = null;
        currentSearchResults.clear();
        isExactFidSearch = false;
        lastSearchAfter = null;

        ToastUtils.makeText(this, getString(R.string.cleared));
    }

    private void appendSearchResults(List<Freer> newResults) {
        for (Freer freer : newResults) {
            KeyInfo keyInfo = KeyInfo.fromCid(freer);
            if (keyInfo != null) {
                keyCardContainer.addKeyCard(keyInfo);
            }
        }
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
        if (currentSearchTerm == null || fapiClient == null) {
            return;
        }

        waitingDialog = new WaitingDialog(this, getString(R.string.loading));
        waitingDialog.show();

        try {
            if (lastSearchAfter != null) {
                searchPartialCid(currentSearchTerm, lastSearchAfter);
            } else {
                dismissWaitingDialog();
                ToastUtils.makeText(this, getString(R.string.no_more_results));
                moreButton.setVisibility(View.GONE);
                moreResultsHint.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading more results: %s", e.getMessage());
            dismissWaitingDialog();
            ToastUtils.makeText(this, getString(R.string.failed_to_load_more));
        }
    }

    private void onFidSelected(KeyInfo keyInfo) {
        hideKeyboard();

        selectedFid = keyInfo.getId();

        selectedFreer = new Freer();
        selectedFreer.setId(keyInfo.getId());
        selectedFreer.setCid(keyInfo.getCid());
        selectedFreer.setPubkey(keyInfo.getPubkey());

        confirmAndOpenChat();
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

    private void confirmAndOpenChat() {
        if (selectedFid == null) {
            ToastUtils.makeText(this, getString(R.string.please_select_a_fid));
            return;
        }

        String displayName = null;
        String pubkey = null;
        Map<String, String> home = null;
        if (selectedFreer != null) {
            displayName = selectedFreer.getCid();
            pubkey = selectedFreer.getPubkey();
            home = selectedFreer.getHome();
        }

        ensureTalkPartner(selectedFid, pubkey, displayName, home, TalkPartner.SOURCE_SEARCH);

        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_TYPE, ImType.P2P.name());
        intent.putExtra(ChatActivity.EXTRA_TARGET_ID, selectedFid);
        if (displayName != null) {
            intent.putExtra(ChatActivity.EXTRA_DISPLAY_NAME, displayName);
        }
        startActivity(intent);
        finish();
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

    /**
     * Checks if a TalkPartner exists for the given FID; if not, creates and saves one.
     */
    private void ensureTalkPartner(String fid, String pubkey, String cid,
                                   Map<String, String> home, String source) {
        ImManager imManager = currentSetting.getImManager();
        if (imManager == null || fid == null) return;

        TalkPartner existing = imManager.getTalkPartner(fid);
        if (existing != null) return;

        TalkPartner partner = new TalkPartner(fid, pubkey);
        partner.setCid(cid);
        partner.setHome(home);
        partner.setSource(source);
        imManager.addTalkPartner(partner);
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
