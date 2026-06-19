package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.CODE;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.data.feipData.CodeOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.CodeManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.CodeCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class StoppedCodeActivity extends BaseCryptoActivity {
    private static final String TAG = "StoppedCodeActivity";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    private CodeCardContainer codeCardContainer;
    private LinearLayout codeListContainer;
    private CodeManager codeManager;

    private Button recoverButton;
    private ImageButton backButton;
    private ImageButton loadMoreButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView codeScrollView;
    private TextView codeStatisticsTextView;

    private LinearLayout nameSortButton;
    private TextView nameSortLabel;
    private ImageView nameSortIcon;
    private LinearLayout timeSortButton;
    private TextView timeSortLabel;
    private ImageView timeSortIcon;

    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { NAME, TIME }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.TIME;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Code> codeList = new ArrayList<>();
    private Long totalStopped;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    private WaitingDialog waitingDialog;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_stopped_code;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.stopped_codes);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                codeManager = CodeManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize CodeManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing CodeManager: " + e.getMessage());
        }

        if (codeManager == null) {
            ToastUtils.makeText(this, getString(R.string.code_not_ready_try_later));
            finish();
            return;
        }

        ToolbarUtils.setupToolbar(this, getActivityTitle());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        setupSortButtons();
        setupSwipeRefresh();
        loadInitialData();
    }

    @Override
    protected void initializeViews() {
        recoverButton = findViewById(R.id.recover_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        codeScrollView = findViewById(R.id.code_scroll_view);
        codeStatisticsTextView = findViewById(R.id.code_statistics);

        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortLabel = findViewById(R.id.name_sort_label);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortLabel = findViewById(R.id.time_sort_label);
        timeSortIcon = findViewById(R.id.time_sort_icon);

        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading_stopped_codes));

        new Thread(() -> {
            try {
                Fcdsl fcdsl = codeManager.makeFcdslForOwner(pageSize, null, false, false);
                List<Code> stoppedCodes = codeManager.fetchPageCodes(fcdsl);

                if (stoppedCodes == null) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_stopped_codes));
                        updateUI();
                    });
                    return;
                }

                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient != null && fapiClient.getLastResponse() != null) {
                    totalStopped = fapiClient.getLastResponse().getTotal();
                }

                runOnUiThread(() -> {
                    codeList.clear();
                    codeList.addAll(stoppedCodes);

                    hasMoreEarlierData = stoppedCodes.size() >= pageSize;
                    hasMoreNewerData = false;

                    updateUI();
                    loadCodeCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.error_loading_stopped_codes, e.getMessage()));
                    updateUI();
                });
            }
        }).start();
    }

    private void loadCodeCardList() {
        codeListContainer = findViewById(R.id.fragment_container);

        codeCardContainer = new CodeCardContainer(this, codeListContainer, ChooseMode.CHOOSE_MULTI);
        codeCardContainer.setHideEditButton(true);

        codeCardContainer.setOnCodeListChangedListener(updatedCodeList -> updateUI());

        Map<String, String> cidMap = codeCardContainer.getCidMap(codeList, this);
        for (Code code : codeList) {
            codeCardContainer.addCodeCard(code, cidMap);
        }

        disableClosedCodeCheckboxes();
        setupSelectAllCheckBox();
        setupScrollListener();
        updateUI();
    }

    private void disableClosedCodeCheckboxes() {
        if (codeCardContainer == null || codeListContainer == null) return;

        for (int i = 0; i < codeList.size(); i++) {
            Code code = codeList.get(i);
            if (Boolean.TRUE.equals(code.isClosed())) {
                View cardView = codeListContainer.getChildAt(i);
                if (cardView != null) {
                    CheckBox checkBox = cardView.findViewById(R.id.code_checkbox);
                    if (checkBox != null) {
                        checkBox.setEnabled(false);
                        checkBox.setAlpha(0.5f);
                    }
                }
            }
        }
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && codeCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    selectRecoverableCodes(isChecked);
                }
            });
        }
    }

    private void selectRecoverableCodes(boolean select) {
        if (codeCardContainer == null || codeListContainer == null) return;

        for (int i = 0; i < codeList.size(); i++) {
            Code code = codeList.get(i);
            if (!Boolean.TRUE.equals(code.isClosed())) {
                View cardView = codeListContainer.getChildAt(i);
                if (cardView != null) {
                    CheckBox checkBox = cardView.findViewById(R.id.code_checkbox);
                    if (checkBox != null && checkBox.isEnabled()) {
                        checkBox.setChecked(select);
                    }
                }
            }
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && codeCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            List<Code> selectedCodes = codeCardContainer.getSelectedCodes();
            int recoverableCount = 0;
            for (Code code : codeList) {
                if (!Boolean.TRUE.equals(code.isClosed())) {
                    recoverableCount++;
                }
            }

            selectAllCheckBox.setChecked(selectedCodes.size() == recoverableCount && recoverableCount > 0);
            setupSelectAllCheckBox();
        }
    }

    private void setupSortButtons() {
        if (nameSortButton != null) {
            nameSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.NAME) {
                    currentSortType = SortType.NAME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortCodeList();
            });
        }

        if (timeSortButton != null) {
            timeSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.TIME) {
                    currentSortType = SortType.TIME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortCodeList();
            });
        }
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::loadNewerCodes);
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright, android.R.color.holo_green_light,
                android.R.color.holo_orange_light, android.R.color.holo_red_light);
        }
    }

    private void setupScrollListener() {
        if (codeScrollView != null) {
            codeScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = scrollY <= 10;
                boolean scrollingDown = scrollY > oldScrollY;
                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                if (scrollDistance < MIN_SCROLL_DISTANCE) return;

                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData) {
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerCodes();
                    }
                }
            });
        }
    }

    private void loadNewerCodes() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;

        new Thread(() -> {
            try {
                List<String> latestSortKeys = getLatestCodeSortKeys();
                Fcdsl fcdsl = codeManager.makeFcdslForOwner(pageSize, latestSortKeys, false, true);
                List<Code> newerCodes = codeManager.fetchPageCodes(fcdsl);

                runOnUiThread(() -> {
                    if (newerCodes != null && !newerCodes.isEmpty()) {
                        Collections.reverse(newerCodes);

                        int currentSize = codeList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + newerCodes.size();
                        int itemsToRemove = Math.max(0, newTotalSize - maxWindowSize);

                        if (itemsToRemove > 0 && codeCardContainer != null) {
                            codeCardContainer.removeFromEnd(itemsToRemove);
                            hasMoreEarlierData = true;
                        }

                        codeList.addAll(0, newerCodes);
                        if (codeCardContainer != null) {
                            codeCardContainer.addCodeCardsToBeginning(newerCodes);
                            disableClosedCodeCheckboxes();
                        }

                        hasMoreNewerData = newerCodes.size() >= pageSize;
                        ToastUtils.makeText(this, getString(R.string.loaded_newer_codes, newerCodes.size()));
                    } else {
                        hasMoreNewerData = false;
                    }

                    isLoadingNewer = false;
                    stopSwipeRefresh();
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer codes: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingNewer = false;
                    stopSwipeRefresh();
                });
            }
        }).start();
    }

    private void loadEarlierCodes() {
        if (isLoadingNewer || isLoadingEarlier || !hasMoreEarlierData) return;

        isLoadingEarlier = true;

        new Thread(() -> {
            try {
                List<String> earliestSortKeys = getEarliestCodeSortKeys();
                Fcdsl fcdsl = codeManager.makeFcdslForOwner(pageSize, earliestSortKeys, false, false);
                List<Code> earlierCodes = codeManager.fetchPageCodes(fcdsl);

                runOnUiThread(() -> {
                    if (earlierCodes != null && !earlierCodes.isEmpty()) {
                        int currentSize = codeList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + earlierCodes.size();
                        int itemsToRemove = Math.max(0, newTotalSize - maxWindowSize);

                        if (itemsToRemove > 0 && codeCardContainer != null) {
                            codeCardContainer.removeFromBeginning(itemsToRemove);
                            hasMoreNewerData = true;
                        }

                        codeList.addAll(earlierCodes);
                        Map<String, String> cidMap = codeCardContainer.getCidMap(earlierCodes, this);
                        for (Code code : earlierCodes) {
                            codeCardContainer.addCodeCard(code, cidMap);
                        }
                        disableClosedCodeCheckboxes();

                        hasMoreEarlierData = earlierCodes.size() >= pageSize;
                        ToastUtils.makeText(this, getString(R.string.loaded_earlier_codes, earlierCodes.size()));
                    } else {
                        hasMoreEarlierData = false;
                    }

                    isLoadingEarlier = false;
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier codes: %s", e.getMessage());
                runOnUiThread(() -> isLoadingEarlier = false);
            }
        }).start();
    }

    private void stopSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setRefreshing(false);
        }
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    private void updateButtonStates() {
        List<Code> selectedCodes = codeCardContainer != null ? codeCardContainer.getSelectedCodes() : new ArrayList<>();
        List<Code> recoverableSelected = new ArrayList<>();
        for (Code code : selectedCodes) {
            if (!Boolean.TRUE.equals(code.isClosed())) {
                recoverableSelected.add(code);
            }
        }

        boolean hasRecoverable = !recoverableSelected.isEmpty();
        recoverButton.setEnabled(hasRecoverable);
        recoverButton.setAlpha(hasRecoverable ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (codeStatisticsTextView == null) return;

        int containerSize = codeList.size();
        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        if (totalStopped != null) {
            statsText.append(getString(R.string.total_in_statistics)).append(totalStopped);
        }

        codeStatisticsTextView.setText(statsText.toString());

        LinearLayout statisticsContainer = findViewById(R.id.code_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }

        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreEarlierData ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void setupButtons() {
        recoverButton.setOnClickListener(v -> {
            hideKeyboard();
            if (codeCardContainer == null) return;

            List<Code> selectedCodes = codeCardContainer.getSelectedCodes();
            List<Code> recoverableCodes = new ArrayList<>();
            for (Code code : selectedCodes) {
                if (!Boolean.TRUE.equals(code.isClosed())) {
                    recoverableCodes.add(code);
                }
            }

            if (recoverableCodes.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_recoverable_codes_selected));
                return;
            }
            performRecoverOperation(recoverableCodes);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });

        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> {
                if (!isLoadingEarlier && hasMoreEarlierData) {
                    loadMoreButton.setEnabled(false);
                    loadMoreButton.setAlpha(0.5f);
                    loadEarlierCodes();
                    new android.os.Handler().postDelayed(() -> {
                        if (loadMoreButton != null) {
                            loadMoreButton.setEnabled(true);
                            loadMoreButton.setAlpha(1.0f);
                        }
                    }, 1000);
                }
            });
        }
    }

    private void performRecoverOperation(List<Code> codesToRecover) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        List<String> codeIds = new ArrayList<>();
        for (Code code : codesToRecover) {
            if (code.getId() != null) {
                codeIds.add(code.getId());
            }
        }

        if (codeIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_code_ids));
            return;
        }

        String feipJson = makeRecoverCodeFeip(codeIds);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            runOnUiThread(() -> {
                                for (Code code : codesToRecover) {
                                    code.setActive(true);
                                    code.setOnChain(null);
                                    codeManager.updateCode(code);
                                    codeCardContainer.removeCodeById(code.getId());
                                }
                                codeManager.commit();
                                codeList.removeAll(codesToRecover);

                                ToastUtils.makeText(StoppedCodeActivity.this,
                                    getString(R.string.codes_recovered_successfully, txId));
                                updateUI();
                                setResult(Activity.RESULT_OK);
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(StoppedCodeActivity.this,
                                    getString(R.string.failed_to_recover_codes) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(StoppedCodeActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(StoppedCodeActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> txSender.showSignedTxAsQR(StoppedCodeActivity.this, signedTxHex));
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private static String makeRecoverCodeFeip(List<String> codeIds) {
        Feip feip = Feip.fromName(CODE);
        CodeOpData codeOpData = CodeOpData.makeRecover(codeIds);
        feip.setData(codeOpData);
        return feip.toJson();
    }

    private List<String> getLatestCodeSortKeys() {
        if (codeList.isEmpty()) return null;
        Code code = codeList.get(0);
        return Arrays.asList(String.valueOf(code.getLastHeight()), code.getId());
    }

    private List<String> getEarliestCodeSortKeys() {
        if (codeList.isEmpty()) return null;
        Code code = codeList.get(codeList.size() - 1);
        return Arrays.asList(String.valueOf(code.getLastHeight()), code.getId());
    }

    private void cycleSortState() {
        switch (currentSortState) {
            case NONE: currentSortState = SortState.ASCENDING; break;
            case ASCENDING: currentSortState = SortState.DESCENDING; break;
            case DESCENDING: currentSortState = SortState.NONE; break;
        }
    }

    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);
        if (nameSortLabel != null) nameSortLabel.setTextColor(hintColor);
        if (nameSortIcon != null) nameSortIcon.setColorFilter(hintColor);
        if (timeSortLabel != null) timeSortLabel.setTextColor(hintColor);
        if (timeSortIcon != null) timeSortIcon.setColorFilter(hintColor);
    }

    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);
        if (currentSortType == SortType.NAME) {
            if (timeSortIcon != null) {
                timeSortIcon.setImageResource(R.drawable.ic_sort_none);
                timeSortIcon.setColorFilter(hintColor);
            }
            if (timeSortLabel != null) timeSortLabel.setTextColor(hintColor);
        } else {
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) nameSortLabel.setTextColor(hintColor);
        }
    }

    private void updateSortIcons() {
        int iconResource = switch (currentSortState) {
            case ASCENDING -> R.drawable.ic_sort_asc;
            case DESCENDING -> R.drawable.ic_sort_desc;
            default -> R.drawable.ic_sort_none;
        };

        int color = switch (currentSortState) {
            case DESCENDING -> getResources().getColor(R.color.accent, null);
            case ASCENDING -> getResources().getColor(R.color.pink, null);
            default -> getResources().getColor(R.color.hint, null);
        };

        if (currentSortType == SortType.NAME && nameSortIcon != null && nameSortLabel != null) {
            nameSortIcon.setImageResource(iconResource);
            nameSortIcon.setColorFilter(color);
            nameSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TIME && timeSortIcon != null && timeSortLabel != null) {
            timeSortIcon.setImageResource(iconResource);
            timeSortIcon.setColorFilter(color);
            timeSortLabel.setTextColor(color);
        }
    }

    private void sortCodeList() {
        if (codeCardContainer != null) {
            if (currentSortType == SortType.NAME) {
                codeCardContainer.sortByName(currentSortState == SortState.ASCENDING,
                    currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.TIME) {
                codeCardContainer.sortByLastTime(currentSortState == SortState.ASCENDING,
                    currentSortState != SortState.NONE);
            }

            if (currentSortState == SortState.NONE) {
                codeCardContainer.sortByLastTime(false, true);
            }
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
