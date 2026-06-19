package com.fc.freer.home;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.ProofManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ProofCardContainer;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ProofDestroyedActivity extends BaseCryptoActivity {

    private static final String TAG = "ProofDestroyedActivity";

    private ProofCardContainer proofCardContainer;
    private LinearLayout proofListContainer;
    protected ProofManager proofManager;

    private Button recoverButton;
    private Button toggleModeButton;


    private Button loadMoreButton;
    private CheckBox selectAllCheckBox;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView proofScrollView;

    private LinearLayout sortButton;
    private ImageView sortIcon;
    private TextView sortLabel;
    private LinearLayout issuerSortButton;
    private ImageView issuerSortIcon;
    private TextView issuerSortLabel;
    private LinearLayout titleSortButton;
    private ImageView titleSortIcon;
    private TextView titleSortLabel;

    private TextView proofStatisticsTextView;

    private boolean isDestroyedMode = true;
    private Long totalDestroyed;

    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { LAST_TIME, ISSUER, TITLE }

    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.LAST_TIME;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<Proof> proofList = new ArrayList<>();
    private boolean isLoadingNewer = false;
    private boolean isLoadingEarlier = false;
    private boolean hasMoreEarlierData = true;
    private boolean hasMoreNewerData = false;

    private WaitingDialog waitingDialog;

    private List<String> latestSortKeys;
    private List<String> earliestSortKeys;

    private long lastScrollTime = 0;
    private static final int MIN_SCROLL_DISTANCE = 10;
    private static final long SCROLL_DELAY_MS = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FidManager fidManager = FidManager.getInstance();
        String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

        if (fidManager == null || liveFid == null) {
            ToastUtils.makeText(this, getString(R.string.proof_not_ready_try_later));
            finish();
            return;
        }

        proofManager = ProofManager.getInstance(this, liveFid);

        if (proofManager == null) {
            ToastUtils.makeText(this, getString(R.string.proof_not_ready_try_later));
            finish();
            return;
        }

        loadDestroyedProofs(this);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_proof_destroyed;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.destroyed_proofs);
    }

    @Override
    protected void initializeViews() {
        recoverButton = findViewById(R.id.recover_button);
        toggleModeButton = findViewById(R.id.toggle_mode_button);
        loadMoreButton = findViewById(R.id.load_more_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        proofScrollView = findViewById(R.id.proof_scroll_view);

        sortButton = findViewById(R.id.sort_button);
        sortIcon = findViewById(R.id.sort_icon);
        sortLabel = findViewById(R.id.sort_label);
        issuerSortButton = findViewById(R.id.issuer_sort_button);
        issuerSortIcon = findViewById(R.id.issuer_sort_icon);
        issuerSortLabel = findViewById(R.id.issuer_sort_label);
        titleSortButton = findViewById(R.id.title_sort_button);
        titleSortIcon = findViewById(R.id.title_sort_icon);
        titleSortLabel = findViewById(R.id.title_sort_label);

        proofStatisticsTextView = findViewById(R.id.proof_statistics);

        setDefaultSortColors();
        setupSortButton();
        setupIssuerSortButton();
        setupTitleSortButton();
        setupSwipeRefresh();
        setupSelectAllCheckBox();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    @Override
    protected void setupButtons() {
        recoverButton.setOnClickListener(v -> {
            if (proofCardContainer == null) {
                return;
            }
            if (isDestroyedMode) {
                ToastUtils.makeText(this, getString(R.string.recover_only_available_in_hided_mode));
                return;
            }

            List<Proof> selectedProofs = proofCardContainer.getSelectedProofs();
            if (selectedProofs.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_proofs_selected_for_recovery));
                return;
            }
            recoverHidedProofs(selectedProofs);
        });

        toggleModeButton.setOnClickListener(v -> toggleMode());

        if (loadMoreButton != null) {
            loadMoreButton.setOnClickListener(v -> {
                if (!isDestroyedMode || isLoadingEarlier || !hasMoreEarlierData) {
                    return;
                }
                loadMoreButton.setEnabled(false);
                loadMoreButton.setAlpha(0.5f);
                loadEarlierProofs();
                new Handler().postDelayed(() -> {
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                }, 1000);
            });
        }
    }

    private void loadDestroyedProofs(Context context) {
        showWaitingDialog(getString(R.string.loading_destroyed_proofs));

        new Thread(() -> {
            try {
                Fcdsl fcdsl = proofManager.makeFcdsl(pageSize, null, false, false);
                List<Proof> destroyedProofs = proofManager.fetchPageProofs(fcdsl);

                if (destroyedProofs == null) {
                    runOnUiThread(() -> {
                        dismissWaitingDialog();
                        ToastUtils.makeText(this, getString(R.string.no_destroyed_proofs_found));
                        clearProofList();
                    });
                    return;
                }

                if (proofManager.getFapiClient() != null
                    && proofManager.getFapiClient().getLastResponse() != null) {
                    totalDestroyed = proofManager.getFapiClient().getLastResponse().getTotal();
                }

                runOnUiThread(() -> {
                    proofList.clear();
                    proofList.addAll(destroyedProofs);
                    hasMoreEarlierData = destroyedProofs.size() == pageSize;
                    hasMoreNewerData = false;

                    latestSortKeys = getLatestSortKeys();
                    earliestSortKeys = getEarliestSortKeys();

                    updateUI();
                    loadProofCardList();
                    dismissWaitingDialog();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading destroyed proofs: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, String.format(getString(R.string.error_loading_destroyed_proofs), e.getMessage()));
                    clearProofList();
                });
            }
        }).start();
    }

    private void loadLocalHidedProofs() {
        showWaitingDialog(getString(R.string.loading_hided_proofs));

        new Thread(() -> {
            try {
                List<Proof> localHided = proofManager.getLocalHiddenProofs();
                runOnUiThread(() -> {
                    proofList.clear();
                    if (localHided != null) {
                        proofList.addAll(localHided);
                    }
                    totalDestroyed = proofManager.getLocalHiddenProofsSize();
                    hasMoreEarlierData = false;
                    hasMoreNewerData = false;
                    updateUI();
                    loadProofCardList();
                    dismissWaitingDialog();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading hided proofs: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, e.getMessage());
                    clearProofList();
                });
            }
        }).start();
    }

    private void loadProofCardList() {
        proofListContainer = findViewById(R.id.fragment_container);
        if (proofListContainer == null) {
            return;
        }

        if (proofCardContainer != null) {
            proofCardContainer.clearAll();
        }

        proofCardContainer = new ProofCardContainer(this, proofListContainer, ChooseMode.CHOOSE_MULTI);
        proofCardContainer.setOnProofListChangedListener(updatedProofList -> {
            proofList.clear();
            proofList.addAll(updatedProofList);
            latestSortKeys = getLatestSortKeys();
            earliestSortKeys = getEarliestSortKeys();
            updateUI();
        });

        Map<String, String> cidMap = proofCardContainer.getCidMap(proofList, this);
        for (Proof proof : proofList) {
            proofCardContainer.addProofCard(proof, cidMap);
        }

        setupScrollListener();
        updateUI();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox == null) {
            return;
        }

        selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (proofCardContainer == null || !buttonView.isPressed()) {
                return;
            }
            proofCardContainer.selectAll(isChecked);
        });
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox == null || proofCardContainer == null) {
            return;
        }

        selectAllCheckBox.setOnCheckedChangeListener(null);

        if (proofCardContainer.areAllSelected()) {
            selectAllCheckBox.setChecked(true);
        } else if (proofCardContainer.areNoneSelected()) {
            selectAllCheckBox.setChecked(false);
        } else {
            selectAllCheckBox.setChecked(false);
        }

        setupSelectAllCheckBox();
    }

    private void setupSortButton() {
        if (sortButton == null) {
            return;
        }
        sortButton.setOnClickListener(v -> {
            if (currentSortType != SortType.LAST_TIME) {
                currentSortType = SortType.LAST_TIME;
                resetOtherSortIcons();
            }
            cycleSortState();
            updateSortIcons();
            sortProofList();
        });
    }

    private void setupIssuerSortButton() {
        if (issuerSortButton == null) {
            return;
        }
        issuerSortButton.setOnClickListener(v -> {
            if (currentSortType != SortType.ISSUER) {
                currentSortType = SortType.ISSUER;
                resetOtherSortIcons();
            }
            cycleSortState();
            updateSortIcons();
            sortProofList();
        });
    }

    private void setupTitleSortButton() {
        if (titleSortButton == null) {
            return;
        }
        titleSortButton.setOnClickListener(v -> {
            if (currentSortType != SortType.TITLE) {
                currentSortType = SortType.TITLE;
                resetOtherSortIcons();
            }
            cycleSortState();
            updateSortIcons();
            sortProofList();
        });
    }

    private void setupSwipeRefresh() {
        if (swipeRefreshLayout == null) {
            return;
        }
        swipeRefreshLayout.setOnRefreshListener(() -> {
            if (!isDestroyedMode) {
                swipeRefreshLayout.setRefreshing(false);
                return;
            }
            loadNewerProofs();
        });
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light);
    }

    private void setupScrollListener() {
        if (proofScrollView == null) {
            return;
        }

        proofScrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!isDestroyedMode || isLoadingNewer || isLoadingEarlier) {
                return;
            }

            boolean atTop = isAtTop(proofScrollView);
            boolean scrollingDown = scrollY > oldScrollY;
            int scrollDistance = Math.abs(scrollY - oldScrollY);
            long currentTime = System.currentTimeMillis();

            if (scrollDistance < MIN_SCROLL_DISTANCE) {
                return;
            }

            if (atTop && scrollingDown && hasMoreNewerData) {
                if (currentTime - lastScrollTime > SCROLL_DELAY_MS) {
                    lastScrollTime = currentTime;
                    loadNewerProofs();
                }
            }
        });
    }

    private boolean isAtTop(View scrollView) {
        if (scrollView instanceof ScrollView sv) {
            return sv.getScrollY() <= 10;
        }
        return false;
    }

    private void loadNewerProofs() {
        if (!isDestroyedMode || isLoadingNewer || latestSortKeys == null) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        new Thread(() -> {
            try {
                Fcdsl fcdsl = proofManager.makeFcdsl(pageSize, latestSortKeys, false, true);
                List<Proof> newerProofs = proofManager.fetchPageProofs(fcdsl);

                runOnUiThread(() -> {
                    if (newerProofs != null && !newerProofs.isEmpty()) {
                        Collections.reverse(newerProofs);
                        int currentSize = proofList.size();
                        int newTotalSize = currentSize + newerProofs.size();
                        int itemsToRemoveFromEnd = Math.max(0, newTotalSize - FreerApplication.MAX_CONTAINER_SIZE);

                        proofCardContainer.addProofCardsToBeginningWithBottomRemoval(newerProofs, itemsToRemoveFromEnd, FreerApplication.MAX_CONTAINER_SIZE);

                        proofList.clear();
                        proofList.addAll(proofCardContainer.getProofList());

                        latestSortKeys = getLatestSortKeys();
                        earliestSortKeys = getEarliestSortKeys();

                        hasMoreNewerData = newerProofs.size() >= pageSize;
                        hasMoreEarlierData = true;
                        ToastUtils.makeText(this, getString(R.string.loaded_newer_destroyed_proofs, newerProofs.size()));
                    } else {
                        hasMoreNewerData = false;
                        ToastUtils.makeText(this, getString(R.string.no_new_proofs_found));
                    }

                    isLoadingNewer = false;
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                    updateUI();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer destroyed proofs: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingNewer = false;
                    if (swipeRefreshLayout != null) {
                        swipeRefreshLayout.setRefreshing(false);
                    }
                    ToastUtils.makeText(this, String.format(getString(R.string.error_loading_newer_destroyed_proofs), e.getMessage()));
                });
            }
        }).start();
    }

    private void loadEarlierProofs() {
        if (!isDestroyedMode || isLoadingEarlier || !hasMoreEarlierData || earliestSortKeys == null) {
            return;
        }

        isLoadingEarlier = true;

        new Thread(() -> {
            try {
                Fcdsl fcdsl = proofManager.makeFcdsl(pageSize, earliestSortKeys, false, false);
                List<Proof> earlierProofs = proofManager.fetchPageProofs(fcdsl);

                runOnUiThread(() -> {
                    if (earlierProofs != null && !earlierProofs.isEmpty()) {
                        int currentSize = proofList.size();
                        int newTotalSize = currentSize + earlierProofs.size();
                        int itemsToRemoveFromBeginning = Math.max(0, newTotalSize - FreerApplication.MAX_CONTAINER_SIZE);

                        proofCardContainer.addProofCardsToEndWithTopRemoval(earlierProofs, itemsToRemoveFromBeginning, FreerApplication.MAX_CONTAINER_SIZE);

                        proofList.clear();
                        proofList.addAll(proofCardContainer.getProofList());

                        latestSortKeys = getLatestSortKeys();
                        earliestSortKeys = getEarliestSortKeys();

                        if (earlierProofs.size() < pageSize) {
                            hasMoreEarlierData = false;
                        } else {
                            hasMoreNewerData = true;
                        }

                        ToastUtils.makeText(this, getString(R.string.loaded_earlier_destroyed_proofs, earlierProofs.size()));
                    } else {
                        hasMoreEarlierData = false;
                        ToastUtils.makeText(this, getString(R.string.no_more_earlier_proofs_found));
                    }

                    isLoadingEarlier = false;
                    updateUI();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading earlier destroyed proofs: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingEarlier = false;
                    ToastUtils.makeText(this, String.format(getString(R.string.error_loading_earlier_destroyed_proofs), e.getMessage()));
                });
            }
        }).start();
    }

    private void recoverHidedProofs(List<Proof> proofsToRecover) {
        showWaitingDialog(getString(R.string.recovering_proofs_locally));

        new Thread(() -> {
            try {
                for (Proof proof : proofsToRecover) {
                    proofManager.addProof(proof);
                    proofManager.removeFromLocalDeletedList(proof);
                }
                proofManager.commit();

                runOnUiThread(() -> {
                    proofCardContainer.removeSelectedProofs();
                    proofList.removeAll(proofsToRecover);
                    totalDestroyed = proofManager.getLocalHiddenProofsSize();
                    updateUI();
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, getString(R.string.proofs_recovered_successfully));
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to recover proofs: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    ToastUtils.makeText(this, String.format(getString(R.string.failed_to_recover_proofs), e.getMessage()));
                });
            }
        }).start();
    }

    private void toggleMode() {
        isDestroyedMode = !isDestroyedMode;
        toggleModeButton.setText(isDestroyedMode ? getString(R.string.hided) : getString(R.string.destroyed));

        clearProofList();

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setEnabled(isDestroyedMode);
            swipeRefreshLayout.setRefreshing(false);
        }

        if (isDestroyedMode) {
            loadDestroyedProofs(this);
        } else {
            loadLocalHidedProofs();
        }
    }

    private void clearProofList() {
        if (proofCardContainer != null) {
            proofCardContainer.clearAll();
        }
        proofList.clear();
        latestSortKeys = null;
        earliestSortKeys = null;
        hasMoreEarlierData = false;
        hasMoreNewerData = false;
        isLoadingNewer = false;
        isLoadingEarlier = false;
        totalDestroyed = null;
        updateUI();
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
        currentSortState = SortState.NONE;
        currentSortType = SortType.LAST_TIME;
        setDefaultSortColors();
    }

    private void updateButtonStates() {
        boolean hasSelected = proofCardContainer != null && !proofCardContainer.getSelectedProofs().isEmpty();

        recoverButton.setEnabled(hasSelected && !isDestroyedMode);
        recoverButton.setAlpha((hasSelected && !isDestroyedMode) ? 1.0f : 0.5f);

        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(isDestroyedMode && hasMoreEarlierData ? View.VISIBLE : View.GONE);
        }
    }

    private void updateStatistics() {
        if (proofStatisticsTextView == null) {
            return;
        }

        int containerSize = proofList.size();
        StringBuilder statsText = new StringBuilder();
        statsText.append(isDestroyedMode ? getString(R.string.destroyed) : getString(R.string.hided));
        statsText.append(": ").append(containerSize);

        if (isDestroyedMode && totalDestroyed != null) {
            statsText.append("      Total: ").append(totalDestroyed);
        }

        proofStatisticsTextView.setText(statsText.toString());

        LinearLayout statisticsContainer = findViewById(R.id.proof_statistics_container);
        if (statisticsContainer != null) {
            statisticsContainer.setVisibility(View.VISIBLE);
        }
    }

    private void cycleSortState() {
        switch (currentSortState) {
            case NONE -> currentSortState = SortState.ASCENDING;
            case ASCENDING -> currentSortState = SortState.DESCENDING;
            case DESCENDING -> currentSortState = SortState.NONE;
        }
    }

    private void resetOtherSortIcons() {
        currentSortState = SortState.NONE;
        int hintColor = getResources().getColor(R.color.hint, null);

        if (currentSortType == SortType.LAST_TIME) {
            if (issuerSortIcon != null) {
                issuerSortIcon.setImageResource(R.drawable.ic_sort_none);
                issuerSortIcon.setColorFilter(hintColor);
            }
            if (issuerSortLabel != null) {
                issuerSortLabel.setTextColor(hintColor);
            }
            if (titleSortIcon != null) {
                titleSortIcon.setImageResource(R.drawable.ic_sort_none);
                titleSortIcon.setColorFilter(hintColor);
            }
            if (titleSortLabel != null) {
                titleSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.ISSUER) {
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (titleSortIcon != null) {
                titleSortIcon.setImageResource(R.drawable.ic_sort_none);
                titleSortIcon.setColorFilter(hintColor);
            }
            if (titleSortLabel != null) {
                titleSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.TITLE) {
            if (sortIcon != null) {
                sortIcon.setImageResource(R.drawable.ic_sort_none);
                sortIcon.setColorFilter(hintColor);
            }
            if (sortLabel != null) {
                sortLabel.setTextColor(hintColor);
            }
            if (issuerSortIcon != null) {
                issuerSortIcon.setImageResource(R.drawable.ic_sort_none);
                issuerSortIcon.setColorFilter(hintColor);
            }
            if (issuerSortLabel != null) {
                issuerSortLabel.setTextColor(hintColor);
            }
        }
    }

    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);

        if (sortLabel != null) {
            sortLabel.setTextColor(hintColor);
        }
        if (sortIcon != null) {
            sortIcon.setColorFilter(hintColor);
        }
        if (issuerSortLabel != null) {
            issuerSortLabel.setTextColor(hintColor);
        }
        if (issuerSortIcon != null) {
            issuerSortIcon.setColorFilter(hintColor);
        }
        if (titleSortLabel != null) {
            titleSortLabel.setTextColor(hintColor);
        }
        if (titleSortIcon != null) {
            titleSortIcon.setColorFilter(hintColor);
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

        if (currentSortType == SortType.LAST_TIME && sortIcon != null && sortLabel != null) {
            sortIcon.setImageResource(iconResource);
            sortIcon.setColorFilter(color);
            sortLabel.setTextColor(color);
        } else if (currentSortType == SortType.ISSUER && issuerSortIcon != null && issuerSortLabel != null) {
            issuerSortIcon.setImageResource(iconResource);
            issuerSortIcon.setColorFilter(color);
            issuerSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TITLE && titleSortIcon != null && titleSortLabel != null) {
            titleSortIcon.setImageResource(iconResource);
            titleSortIcon.setColorFilter(color);
            titleSortLabel.setTextColor(color);
        }
    }

    private void sortProofList() {
        if (proofCardContainer == null) {
            return;
        }

        boolean ascending = currentSortState == SortState.ASCENDING;
        boolean enableSort = currentSortState != SortState.NONE;

        if (currentSortType == SortType.LAST_TIME) {
            proofCardContainer.sortByLastTime(ascending, enableSort);
        } else if (currentSortType == SortType.ISSUER) {
            proofCardContainer.sortByIssuer(ascending, enableSort);
        } else if (currentSortType == SortType.TITLE) {
            proofCardContainer.sortByTitle(ascending, enableSort);
        }

        if (currentSortState == SortState.NONE) {
            proofCardContainer.sortByLastTime(false, true);
        }
    }

    private List<String> getLatestSortKeys() {
        if (proofList.isEmpty()) {
            return null;
        }
        return makeSortList(proofList.get(0));
    }

    private List<String> getEarliestSortKeys() {
        if (proofList.isEmpty()) {
            return null;
        }
        return makeSortList(proofList.get(proofList.size() - 1));
    }

    private List<String> makeSortList(Proof proof) {
        return java.util.Arrays.asList(String.valueOf(proof.getLastHeight()), proof.getId());
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

