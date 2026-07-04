package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.IndicesNames.PROTOCOL;

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
import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.data.feipData.ProtocolOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.ProtocolManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.tx.TxSender;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ProtocolCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class StoppedProtocolActivity extends BaseCryptoActivity {
    private static final String TAG = "StoppedProtocolActivity";
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    private ProtocolCardContainer protocolCardContainer;
    private LinearLayout protocolListContainer;
    private ProtocolManager protocolManager;

    private Button recoverButton;
    private ImageButton backButton;
    private ImageButton loadMoreButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView protocolScrollView;
    private TextView protocolStatisticsTextView;

    // Sort UI elements
    private LinearLayout nameSortButton;
    private TextView nameSortLabel;
    private ImageView nameSortIcon;
    private LinearLayout timeSortButton;
    private TextView timeSortLabel;
    private ImageView timeSortIcon;

    // Sort state management
    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { NAME, TIME }
    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.TIME;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Protocol> protocolList = new ArrayList<>();
    private Long totalStopped;
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = true;
    private long lastScrollTime = 0;

    private WaitingDialog waitingDialog;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_stopped_protocol;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.stopped_protocols);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ProtocolManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                protocolManager = ProtocolManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ProtocolManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ProtocolManager with liveFid: " + e.getMessage());
        }

        if (protocolManager == null) {
            ToastUtils.makeText(this, getString(R.string.protocol_not_ready_try_later));
            finish();
            return;
        }

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
        setupSortButtons();
        setupSwipeRefresh();

        // Load initial data from API
        loadInitialData();
    }

    @Override
    protected void initializeViews() {
        recoverButton = findViewById(R.id.recover_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        protocolScrollView = findViewById(R.id.protocol_scroll_view);
        protocolStatisticsTextView = findViewById(R.id.protocol_statistics);

        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortLabel = findViewById(R.id.name_sort_label);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortLabel = findViewById(R.id.time_sort_label);
        timeSortIcon = findViewById(R.id.time_sort_icon);

        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading_stopped_protocols));

        new Thread(() -> {
            try {
                // Fetch stopped protocols from API (active = false)
                Fcdsl fcdsl = protocolManager.makeFcdslForOwner(pageSize, null, false, false);
                List<Protocol> stoppedProtocols = protocolManager.fetchPageProtocols(fcdsl);

                if (stoppedProtocols == null) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_stopped_protocols));
                        updateUI();
                    });
                    return;
                }

                // Get total count from response
                FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient != null && fapiClient.getLastResponse() != null) {
                    totalStopped = fapiClient.getLastResponse().getTotal();
                }

                runOnUiThread(() -> {
                    protocolList.clear();
                    protocolList.addAll(stoppedProtocols);

                    hasMoreEarlierData = stoppedProtocols.size() >= pageSize;
                    hasMoreNewerData = false;

                    updateUI();
                    loadProtocolCardList();
                    dismissWaitingDialog();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.error_loading_stopped_protocols, e.getMessage()));
                    updateUI();
                });
            }
        }).start();
    }

    private void loadProtocolCardList() {
        protocolListContainer = findViewById(R.id.fragment_container);

        protocolCardContainer = new ProtocolCardContainer(this, protocolListContainer, ChooseMode.CHOOSE_MULTI);
        protocolCardContainer.setHideEditButton(true);

        protocolCardContainer.setOnProtocolListChangedListener(updatedProtocolList -> {
            updateUI();
        });

        Map<String, String> cidMap = protocolCardContainer.getCidMap(protocolList, this);
        for (Protocol protocol : protocolList) {
            protocolCardContainer.addProtocolCard(protocol, cidMap);
        }

        // Disable checkboxes for closed protocols (they cannot be recovered)
        disableClosedProtocolCheckboxes();

        setupSelectAllCheckBox();
        setupScrollListener();
        updateUI();
    }

    private void disableClosedProtocolCheckboxes() {
        if (protocolCardContainer == null || protocolListContainer == null) return;

        for (int i = 0; i < protocolList.size(); i++) {
            Protocol protocol = protocolList.get(i);
            if (Boolean.TRUE.equals(protocol.isClosed())) {
                View cardView = protocolListContainer.getChildAt(i);
                if (cardView != null) {
                    CheckBox checkBox = cardView.findViewById(R.id.protocol_checkbox);
                    if (checkBox != null) {
                        checkBox.setEnabled(false);
                        checkBox.setAlpha(0.5f);
                    }
                }
            }
        }
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && protocolCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    selectRecoverableProtocols(isChecked);
                }
            });
        }
    }

    private void selectRecoverableProtocols(boolean select) {
        if (protocolCardContainer == null || protocolListContainer == null) return;

        for (int i = 0; i < protocolList.size(); i++) {
            Protocol protocol = protocolList.get(i);
            if (!Boolean.TRUE.equals(protocol.isClosed())) {
                View cardView = protocolListContainer.getChildAt(i);
                if (cardView != null) {
                    CheckBox checkBox = cardView.findViewById(R.id.protocol_checkbox);
                    if (checkBox != null && checkBox.isEnabled()) {
                        checkBox.setChecked(select);
                    }
                }
            }
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && protocolCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            List<Protocol> selectedProtocols = protocolCardContainer.getSelectedProtocols();
            int recoverableCount = 0;
            for (Protocol protocol : protocolList) {
                if (!Boolean.TRUE.equals(protocol.isClosed())) {
                    recoverableCount++;
                }
            }

            if (selectedProtocols.size() == recoverableCount && recoverableCount > 0) {
                selectAllCheckBox.setChecked(true);
            } else {
                selectAllCheckBox.setChecked(false);
            }

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
                sortProtocolList();
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
                sortProtocolList();
            });
        }
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(this::loadNewerProtocols);
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    private void setupScrollListener() {
        if (protocolScrollView != null) {
            protocolScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                boolean atTop = scrollY <= 10;
                boolean scrollingDown = scrollY > oldScrollY;
                int scrollDistance = Math.abs(scrollY - oldScrollY);
                long currentTime = System.currentTimeMillis();

                if (scrollDistance < MIN_SCROLL_DISTANCE) return;

                if (atTop && scrollingDown && !isLoadingNewer && !isLoadingEarlier && hasMoreNewerData) {
                    if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                        lastScrollTime = currentTime;
                        loadNewerProtocols();
                    }
                }
            });
        }
    }

    private void loadNewerProtocols() {
        if (isLoadingNewer || isLoadingEarlier) {
            stopSwipeRefresh();
            return;
        }

        isLoadingNewer = true;

        new Thread(() -> {
            try {
                List<String> latestSortKeys = getLatestProtocolSortKeys();
                Fcdsl fcdsl = protocolManager.makeFcdslForOwner(pageSize, latestSortKeys, false, true);
                List<Protocol> newerProtocols = protocolManager.fetchPageProtocols(fcdsl);

                runOnUiThread(() -> {
                    if (newerProtocols != null && !newerProtocols.isEmpty()) {
                        Collections.reverse(newerProtocols);
                        
                        int currentSize = protocolList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + newerProtocols.size();
                        int itemsToRemove = Math.max(0, newTotalSize - maxWindowSize);

                        if (itemsToRemove > 0 && protocolCardContainer != null) {
                            protocolCardContainer.removeFromEnd(itemsToRemove);
                            hasMoreEarlierData = true;
                        }

                        protocolList.addAll(0, newerProtocols);
                        if (protocolCardContainer != null) {
                            protocolCardContainer.addProtocolCardsToBeginning(newerProtocols);
                            disableClosedProtocolCheckboxes();
                        }

                        hasMoreNewerData = newerProtocols.size() >= pageSize;
                        ToastUtils.makeText(this, getString(R.string.loaded_newer_protocols, newerProtocols.size()));
                    } else {
                        hasMoreNewerData = false;
                    }

                    isLoadingNewer = false;
                    stopSwipeRefresh();
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer protocols: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingNewer = false;
                    stopSwipeRefresh();
                });
            }
        }).start();
    }

    private void loadEarlierProtocols() {
        if (isLoadingNewer || isLoadingEarlier || !hasMoreEarlierData) return;

        isLoadingEarlier = true;

        new Thread(() -> {
            try {
                List<String> earliestSortKeys = getEarliestProtocolSortKeys();
                Fcdsl fcdsl = protocolManager.makeFcdslForOwner(pageSize, earliestSortKeys, false, false);
                List<Protocol> earlierProtocols = protocolManager.fetchPageProtocols(fcdsl);

                runOnUiThread(() -> {
                    if (earlierProtocols != null && !earlierProtocols.isEmpty()) {
                        int currentSize = protocolList.size();
                        int maxWindowSize = FreerApplication.MAX_CONTAINER_SIZE;
                        int newTotalSize = currentSize + earlierProtocols.size();
                        int itemsToRemove = Math.max(0, newTotalSize - maxWindowSize);

                        if (itemsToRemove > 0 && protocolCardContainer != null) {
                            protocolCardContainer.removeFromBeginning(itemsToRemove);
                            hasMoreNewerData = true;
                        }

                        protocolList.addAll(earlierProtocols);
                        Map<String, String> cidMap = protocolCardContainer.getCidMap(earlierProtocols, this);
                        for (Protocol protocol : earlierProtocols) {
                            protocolCardContainer.addProtocolCard(protocol, cidMap);
                        }
                        disableClosedProtocolCheckboxes();

                        hasMoreEarlierData = earlierProtocols.size() >= pageSize;
                        ToastUtils.makeText(this, getString(R.string.loaded_earlier_protocols, earlierProtocols.size()));
                    } else {
                        hasMoreEarlierData = false;
                    }

                    isLoadingEarlier = false;
                    updateUI();
                });

            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier protocols: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingEarlier = false;
                });
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
        List<Protocol> selectedProtocols = protocolCardContainer != null ? protocolCardContainer.getSelectedProtocols() : new ArrayList<>();
        List<Protocol> recoverableSelected = new ArrayList<>();
        for (Protocol protocol : selectedProtocols) {
            if (!Boolean.TRUE.equals(protocol.isClosed())) {
                recoverableSelected.add(protocol);
            }
        }

        boolean hasRecoverable = !recoverableSelected.isEmpty();
        recoverButton.setEnabled(hasRecoverable);
        recoverButton.setAlpha(hasRecoverable ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (protocolStatisticsTextView == null) return;

        int containerSize = protocolList.size();
        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);
        if (totalStopped != null) {
            statsText.append(getString(R.string.total_in_statistics)).append(totalStopped);
        }

        protocolStatisticsTextView.setText(statsText.toString());

        LinearLayout statisticsContainer = findViewById(R.id.protocol_statistics_container);
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
            if (protocolCardContainer == null) return;
            
            List<Protocol> selectedProtocols = protocolCardContainer.getSelectedProtocols();
            List<Protocol> recoverableProtocols = new ArrayList<>();
            for (Protocol protocol : selectedProtocols) {
                if (!Boolean.TRUE.equals(protocol.isClosed())) {
                    recoverableProtocols.add(protocol);
                }
            }

            if (recoverableProtocols.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_recoverable_protocols_selected));
                return;
            }
            performRecoverOperation(recoverableProtocols);
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
                    loadEarlierProtocols();
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

    private void performRecoverOperation(List<Protocol> protocolsToRecover) {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, getString(R.string.no_active_key_available));
            return;
        }

        List<String> protocolIds = new ArrayList<>();
        for (Protocol protocol : protocolsToRecover) {
            if (protocol.getId() != null) {
                protocolIds.add(protocol.getId());
            }
        }

        if (protocolIds.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_valid_protocol_ids));
            return;
        }

        String feipJson = makeRecoverProtocolFeip(protocolIds);

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
                                for (Protocol protocol : protocolsToRecover) {
                                    protocol.setActive(true);
                                    protocol.setOnChain(null);
                                    protocolManager.updateProtocol(protocol);
                                    protocolCardContainer.removeProtocolById(protocol.getId());
                                }
                                protocolManager.commit();
                                protocolList.removeAll(protocolsToRecover);

                                updateUI();
                                setResult(Activity.RESULT_OK);
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(StoppedProtocolActivity.this,
                                    getString(R.string.failed_to_recover_protocols) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> {
                                ToastUtils.makeText(StoppedProtocolActivity.this, R.string.cannot_sign_transaction);
                                txSender.showUnsignedTxAsQR(StoppedProtocolActivity.this, rawTxInfo);
                            });
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> {
                                txSender.showSignedTxAsQR(StoppedProtocolActivity.this, signedTxHex);
                            });
                        }
                    });
            }).start();
        } else {
            ToastUtils.makeText(this, R.string.failed_to_get_private_key);
        }
    }

    private static String makeRecoverProtocolFeip(List<String> protocolIds) {
        Feip feip = Feip.fromName(PROTOCOL);
        ProtocolOpData protocolOpData = ProtocolOpData.makeRecover(protocolIds);
        feip.setData(protocolOpData);
        return feip.toJson();
    }

    private List<String> getLatestProtocolSortKeys() {
        if (protocolList.isEmpty()) return null;
        Protocol protocol = protocolList.get(0);
        return Arrays.asList(String.valueOf(protocol.getLastHeight()), protocol.getId());
    }

    private List<String> getEarliestProtocolSortKeys() {
        if (protocolList.isEmpty()) return null;
        Protocol protocol = protocolList.get(protocolList.size() - 1);
        return Arrays.asList(String.valueOf(protocol.getLastHeight()), protocol.getId());
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

    private void sortProtocolList() {
        if (protocolCardContainer != null) {
            if (currentSortType == SortType.NAME) {
                protocolCardContainer.sortByName(currentSortState == SortState.ASCENDING,
                    currentSortState != SortState.NONE);
            } else if (currentSortType == SortType.TIME) {
                protocolCardContainer.sortByLastTime(currentSortState == SortState.ASCENDING,
                    currentSortState != SortState.NONE);
            }

            if (currentSortState == SortState.NONE) {
                protocolCardContainer.sortByLastTime(false, true);
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

