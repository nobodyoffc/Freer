package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN;

import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.data.feipData.TokenOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenHolderCardContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MyTokenActivity extends BaseCryptoActivity {
    private static final String TAG = "MyTokenActivity";

    private TokenHolderCardContainer cardContainer;
    protected TokenManager tokenManager;
    private WaitingDialog waitingDialog;

    private ImageButton moreButton;
    private ImageButton tokensButton;
    private ImageButton clearButton;
    private ImageButton historyButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private Button loadMoreButton;

    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView scrollView;
    private TextView statisticsTextView;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<TokenHolder> tokenHolderList = new ArrayList<>();
    private Map<String, Token> tokenInfoMap = new HashMap<>();

    private ActivityResultLauncher<Intent> tokensLauncher;
    private ActivityResultLauncher<Intent> historyLauncher;
    private ActivityResultLauncher<Intent> sendTokenLauncher;
    private ActivityResultLauncher<Intent> hiddenLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeActivityResultLaunchers();

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                tokenManager = TokenManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize TokenManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing TokenManager: " + e.getMessage());
        }

        if (tokenManager == null) {
            ToastUtils.makeText(this, getString(R.string.token_not_ready_try_later));
            finish();
            return;
        }

        loadInitialData();
    }

    private void initializeActivityResultLaunchers() {
        tokensLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        historyLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {}
        );

        sendTokenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );

        hiddenLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    refreshList();
                }
            }
        );
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_my_token;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.my_tokens);
    }

    @Override
    protected void initializeViews() {
        moreButton = findViewById(R.id.more_button);
        tokensButton = findViewById(R.id.tokens_button);
        clearButton = findViewById(R.id.clear_button);
        historyButton = findViewById(R.id.history_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);

        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        scrollView = findViewById(R.id.token_holder_scroll_view);
        statisticsTextView = findViewById(R.id.statistics_text);

        setupSearchControls();
        setupTokenHolderSpinners();

        setupSelectAllCheckBox();
        setupSwipeRefresh();
    }

    /**
     * Sets up the spinner dropdowns specific to TokenHolder
     */
    private void setupTokenHolderSpinners() {
        // Determine language for display names
        boolean useChinese = getResources().getConfiguration().locale.getLanguage().equals("zh");

        // Set up 'Search in' spinner with TokenHolder searchable fields
        if (searchFieldSpinner != null) {
            LinkedHashMap<String, Map<String, String>> searchableFields = TokenHolder.getSearchableFields();

            List<String> fieldOptionsList = new ArrayList<>();
            fieldOptionsList.add(getString(R.string.search_in)); // First item is placeholder

            for (Map.Entry<String, Map<String, String>> entry : searchableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                fieldOptionsList.add(displayName);
            }

            setupSpinner(searchFieldSpinner, fieldOptionsList.toArray(new String[0]), R.string.field);
        }

        // Set up sort spinner with TokenHolder sortable fields
        if (sortFieldSpinner != null) {
            LinkedHashMap<String, Map<String, String>> sortableFields = TokenHolder.getSortableFields();

            List<String> sortOptionsList = new ArrayList<>();
            sortOptionsList.add(getString(R.string.sort_by)); // First item is placeholder

            for (Map.Entry<String, Map<String, String>> entry : sortableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                sortOptionsList.add(displayName);
            }

            setupSpinner(sortFieldSpinner, sortOptionsList.toArray(new String[0]), R.string.field);
        }

        // Set up Order spinner
        if (sortOrderSpinner != null) {
            String[] orderOptions = new String[]{"Order", "DESC", "ASC"};
            setupSpinner(sortOrderSpinner, orderOptions, R.string.order);
        }
    }

    @Override
    protected void onExitSearchMode() {
        loadFirstPage();
    }

    @Override
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        performSearchFromAPI();
    }

    /**
     * Loads the first page of token holders from the local database
     */
    private void loadFirstPage() {
        if (cardContainer != null) {
            cardContainer.clearAll();
        }
        tokenHolderList.clear();

        showWaitingDialog(getString(R.string.loading_token_holders));

        new Thread(() -> {
            try {
                List<TokenHolder> holders = tokenManager.getPaginatedTokenHolders(pageSize, null, true);
                fetchTokenInfo(holders);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showItemList(holders);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.no_token_holders_found));
                });
            }
        }).start();
    }

    /**
     * Performs the search from API using the current search parameters
     */
    private void performSearchFromAPI() {
        if (tokenManager == null) {
            return;
        }

        showWaitingDialog(getString(R.string.searching));

        new Thread(() -> {
            try {
                List<TokenHolder> searchResults = tokenManager.searchTokenHoldersFromApi(
                    currentSearchQuery,
                    currentSearchField,
                    currentSortField,
                    currentSortOrder,
                    null);

                fetchTokenInfo(searchResults);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    if (searchResults != null && !searchResults.isEmpty()) {
                        showItemList(searchResults);
                        ToastUtils.makeText(this, getString(R.string.found_token_holders_count, searchResults.size()));
                    } else {
                        ToastUtils.makeText(this, getString(R.string.no_token_holders_found));
                        updateUI();
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error performing search: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.search_failed) + ": " + e.getMessage());
                });
            }
        }).start();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading_token_holders));

        new Thread(() -> {
            try {
                int count = tokenManager.refreshTokenHoldersFromAPI(this);
                List<TokenHolder> holders = tokenManager.getPaginatedTokenHolders(pageSize, null, true);
                
                // Fetch token info for display
                fetchTokenInfo(holders);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showItemList(holders);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.no_token_holders_found));
                });
            }
        }).start();
    }

    private void fetchTokenInfo(List<TokenHolder> holders) {
        if (holders == null || holders.isEmpty()) return;

        List<String> missingIds = new ArrayList<>();
        for (TokenHolder holder : holders) {
            if (holder.getTokenId() != null && !tokenInfoMap.containsKey(holder.getTokenId())
                && !missingIds.contains(holder.getTokenId())) {
                missingIds.add(holder.getTokenId());
            }
        }
        if (missingIds.isEmpty()) return;

        Map<String, Token> tokens = tokenManager.fetchTokensByIds(missingIds);
        if (tokens != null) {
            for (Map.Entry<String, Token> entry : tokens.entrySet()) {
                if (entry.getValue() != null) {
                    tokenInfoMap.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void showItemList(List<TokenHolder> newList) {
        if (newList == null || newList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_token_holders_found));
            updateUI();
            return;
        }

        tokenHolderList.clear();
        tokenHolderList.addAll(newList);
        updateUI();
        loadCardList();
    }

    private void loadCardList() {
        LinearLayout listContainer = findViewById(R.id.fragment_container);

        if (cardContainer != null) {
            cardContainer.clearAll();
        }

        cardContainer = new TokenHolderCardContainer(this, listContainer, ChooseMode.CHOOSE_MULTI);
        cardContainer.setTokenInfoMap(tokenInfoMap);

        cardContainer.setOnListChangedListener(updatedList -> updateUI());

        cardContainer.setOnSendIconClickListener(this::handleSendToken);

        cardContainer.setOnBurnIconClickListener(this::confirmBurnToken);

        for (TokenHolder holder : tokenHolderList) {
            cardContainer.addTokenHolderCard(holder);
        }

        updateUI();
    }

    private void handleSendToken(TokenHolder tokenHolder) {
        Intent intent = new Intent(this, SendTokenActivity.class);
        intent.putExtra(SendTokenActivity.EXTRA_TOKEN_HOLDER_JSON, tokenHolder.toJson());
        sendTokenLauncher.launch(intent);
    }

    private void confirmBurnToken(TokenHolder tokenHolder) {
        if (tokenHolder.getBalance() == null || tokenHolder.getBalance() <= 0) {
            ToastUtils.makeText(this, R.string.no_balance_to_burn);
            return;
        }

        String tokenName = tokenHolder.getTokenId();
        Token token = tokenInfoMap.get(tokenHolder.getTokenId());
        if (token != null && token.getName() != null) {
            tokenName = token.getName();
        }

        DialogUtils.show(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.burn_token)
            .setMessage(getString(R.string.burn_token_confirm, tokenName))
            .setPositiveButton(R.string.confirm, (dialog, which) -> burnToken(tokenHolder))
            .setNegativeButton(R.string.cancel, null));
    }

    private void burnToken(TokenHolder tokenHolder) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_key_available);
            return;
        }

        // The destroy op burns the whole balance of the signer
        TokenOpData tokenOpData = TokenOpData.makeDestroy(tokenHolder.getTokenId());

        Feip feip = Feip.fromName(TOKEN);
        feip.setData(tokenOpData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
            return;
        }

        showWaitingDialog(getString(R.string.sending_transaction));

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            refreshList();
                        });
                    }

                    @Override
                    public void onError(String errorMessage) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            ToastUtils.makeText(MyTokenActivity.this,
                                getString(R.string.failed_to_burn_token) + ": " + errorMessage);
                        });
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            ToastUtils.makeText(MyTokenActivity.this, R.string.cannot_sign_transaction);
                            txSender.showUnsignedTxAsQR(MyTokenActivity.this, rawTxInfo);
                        });
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            txSender.showSignedTxAsQR(MyTokenActivity.this, signedTxHex);
                        });
                    }
                });
        }).start();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed() && cardContainer != null) {
                    cardContainer.selectAll(isChecked);
                }
            });
        }
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::refreshList);
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    @Override
    protected void setupButtons() {
        if (moreButton != null) {
            moreButton.setOnClickListener(this::showMoreMenu);
        }

        if (tokensButton != null) {
            tokensButton.setOnClickListener(v -> {
                hideKeyboard();
                Intent intent = new Intent(this, TokensActivity.class);
                tokensLauncher.launch(intent);
            });
        }

        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                hideKeyboard();
                boolean wasSearchMode = isSearchMode;
                clearSearchControls();
                // clearSearchControls only reloads when exiting search mode
                if (!wasSearchMode) {
                    refreshList();
                }
            });
        }

        if (historyButton != null) {
            historyButton.setOnClickListener(v -> {
                hideKeyboard();
                Intent intent = new Intent(this, TokenHistoryActivity.class);
                intent.putExtra(TokenHistoryActivity.EXTRA_FID, FidManager.getInstance().getLiveFid());
                historyLauncher.launch(intent);
            });
        }

        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    private void showMoreMenu(View anchorView) {
        hideKeyboard();
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_menu_my_token_more, null);
        PopupWindow popupWindow = new PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        );

        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.refresh_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            clearAndReload();
        });

        popupView.findViewById(R.id.hide_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            handleHideTokenHolders();
        });

        popupView.findViewById(R.id.hidden_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(this, HiddenTokenHolderActivity.class);
            hiddenLauncher.launch(intent);
        });

        popupView.findViewById(R.id.about_item).setOnClickListener(v -> {
            popupWindow.dismiss();
            com.fc.freer.utils.AboutDialog.show(this, R.string.about_token_title, R.string.about_token_message);
        });

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();
        popupWindow.showAsDropDown(anchorView, 0, -anchorView.getHeight() - popupHeight);
    }

    private void handleHideTokenHolders() {
        if (cardContainer == null) return;
        List<TokenHolder> selected = cardContainer.getSelectedTokenHolders();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, R.string.no_items_selected);
            return;
        }

        tokenManager.addToLocalDeletedList(selected);
        tokenManager.removeTokenHolders(selected);
        tokenManager.commit();

        refreshList();
        ToastUtils.makeText(this, getString(R.string.toast_hidden_token_holders, selected.size()));
    }

    private void refreshList() {
        if (cardContainer != null) {
            cardContainer.clearAll();
        }
        tokenHolderList.clear();
        
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }

        loadInitialData();
    }

    private void clearAndReload() {
        showWaitingDialog(getString(R.string.loading_token_holders));

        new Thread(() -> {
            try {
                int count = tokenManager.refreshTokenHoldersFromAPI(this);
                List<TokenHolder> holders = tokenManager.getPaginatedTokenHolders(pageSize, null, true);
                fetchTokenInfo(holders);

                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    if (cardContainer != null) {
                        cardContainer.clearAll();
                    }
                    tokenHolderList.clear();
                    showItemList(holders);
                    ToastUtils.makeText(this, getString(R.string.refresh));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.toast_error_detail, e.getMessage()));
                });
            }
        }).start();
    }

    private void updateUI() {
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && cardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);
            selectAllCheckBox.setChecked(cardContainer.areAllSelected());
            setupSelectAllCheckBox();
        }
    }

    private void updateStatistics() {
        if (statisticsTextView == null) return;

        int containerSize = tokenHolderList.size();
        long dbSize = tokenManager != null ? tokenManager.getTokenHolderDBSize() : 0;

        String statsText = getString(R.string.above_in_statistics) + containerSize +
                          getString(R.string.local_in_statistics) + dbSize;
        statisticsTextView.setText(statsText);

        LinearLayout statisticsContainer = findViewById(R.id.statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }
    }

    private void showWaitingDialog(String message) {
        if (waitingDialog == null) {
            waitingDialog = new WaitingDialog(this, message);
        } else {
            waitingDialog.setHint(message);
        }
        if (!isFinishing() && !waitingDialog.isShowing()) {
            waitingDialog.show();
        }
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing() && !isFinishing()) {
            waitingDialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
        waitingDialog = null;
    }
}
