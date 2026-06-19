package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.Values.NEWER;
import static com.fc.fc_ajdk.constants.Values.OLDER;
import static com.fc.fc_ajdk.constants.Values.REFRESH;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.fcData.News;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.manager.FcObjectManager;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.NewsCardContainer;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import android.widget.ImageButton;

public class NewsActivity extends BaseCryptoActivity {
    private static final String TAG = "NewsActivity";

    private enum SortState { NONE, ASCENDING, DESCENDING }
    private enum SortType { NAME, DOER, TYPE }

    private NewsCardContainer newsCardContainer;
    private LinearLayout newsListContainer;
    private FcObjectManager fcObjectManager;
    private WaitingDialog waitingDialog;

    private final int pageSize = Math.min(FreerApplication.DEFAULT_REQUEST_SIZE, FreerApplication.MAX_CONTAINER_SIZE);
    private final List<News> newsList = new ArrayList<>();

    private ImageButton clearButton;
    private ImageButton backButton;


    private Button loadMoreButton;
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView newsScrollView;
    private TextView newsStatisticsTextView;
    private LinearLayout newsStatisticsContainer;

    private LinearLayout nameSortButton, doerSortButton, typeSortButton;
    private ImageView nameSortIcon, doerSortIcon, typeSortIcon;
    private TextView nameSortLabel, doerSortLabel, typeSortLabel;

    private SortState currentSortState = SortState.NONE;
    private SortType currentSortType = SortType.NAME;

    private boolean isLoadingNewer = false;
    private boolean isLoadingOlder = false;
    private boolean hasMoreOlderData = true;
    private boolean hasMoreNewerData = false;

    private News latestNews = null;
    private News earliestNews = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (liveFid != null) {
                fcObjectManager = FcObjectManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize FcObjectManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing FcObjectManager with liveFid: " + e.getMessage());
        }

        loadInitialData();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_news;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.news);
    }

    @Override
    protected void initializeViews() {
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        loadMoreButton = findViewById(R.id.load_more_button);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        newsScrollView = findViewById(R.id.news_scroll_view);
        newsStatisticsTextView = findViewById(R.id.news_statistics);
        newsStatisticsContainer = findViewById(R.id.news_statistics_container);

        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        nameSortLabel = findViewById(R.id.name_sort_label);
        doerSortButton = findViewById(R.id.doer_sort_button);
        doerSortIcon = findViewById(R.id.doer_sort_icon);
        doerSortLabel = findViewById(R.id.doer_sort_label);
        typeSortButton = findViewById(R.id.type_sort_button);
        typeSortIcon = findViewById(R.id.type_sort_icon);
        typeSortLabel = findViewById(R.id.type_sort_label);

        // Initialize search controls from base class
        setupSearchControls();

        setupSortButtons();
        setupSwipeRefreshNews();
        setupNewsSpinners();
        setDefaultSortColors();
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    @Override
    protected void setupButtons() {
        loadMoreButton.setOnClickListener(v -> loadOlderNews());

        // Use the clear button from layout (not search clear button)
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                // Clear search controls using base class method
                clearSearchControls();
                onExitSearchMode();
            });
        }

        // Set up back button to finish activity
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                hideKeyboard();
                finish();
            });
        }
    }

    @Override
    protected void onExitSearchMode() {
        // Clear boundary indicators when exiting search mode
        if (newsCardContainer != null) {
            newsCardContainer.clearAllBoundaryIndicators();
        }

        // Reload initial data
        loadInitialData();
    }

    @Override
    protected void onPerformSearch(String query, String searchField, String sortField, String sortOrder) {
        // Perform search without reference (initial search)
        performSearchFromAPI(null);
    }

    private void loadInitialData() {
        showWaitingDialog(getString(R.string.loading));

        new Thread(() -> {
            try {
                boolean networkAvailable = !NetworkUtils.isNetworkDownToast(this);

                if (networkAvailable) {
                    loadFromAPI(REFRESH);
                } else {
                    List<News> cachedNewsList = fcObjectManager.getCachedNewsList();

                    if (!cachedNewsList.isEmpty()) {
                        runOnUiThread(() -> {
                            newsList.clear();
                            newsList.addAll(cachedNewsList);
                            updateLatestAndEarliestNews();
                            updateButtonStates();
                            loadListContainer();
                            dismissWaitingDialog();
                        });
                    } else {
                        runOnUiThread(() -> {
                            dismissWaitingDialog();
                            showToast(getString(R.string.network_connection_failed));
                        });
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading initial data: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast(getString(R.string.error_loading_news, e.getMessage()));
                });
            }
        }).start();
    }

    private void loadFromAPI(String operationType) {
        try {
            List<News> apiNews = fcObjectManager.fetchNewsFromAPI(operationType, null);

            runOnUiThread(() -> {
                if (apiNews != null && !apiNews.isEmpty()) {
                    newsList.clear();
                    updateNewsListAndFlags(operationType, apiNews);
                    updateLatestAndEarliestNews();
                    loadListContainer();
                    dismissWaitingDialog();
                    showToast(getString(R.string.loaded_news_count, apiNews.size()));
                } else {
                    dismissWaitingDialog();
                    showToast(getString(R.string.no_news_found));
                }

                updateUI();
            });
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading from API: %s", e.getMessage());
            runOnUiThread(() -> {
                dismissWaitingDialog();
                showToast(getString(R.string.error_loading_news, e.getMessage()));
            });
        }
    }

    private void updateNewsListAndFlags(String operationType, List<News> apiNews) {
        switch (operationType) {
            case REFRESH:
                newsList.addAll(apiNews);
                hasMoreOlderData = apiNews.size() >= pageSize;
                hasMoreNewerData = false; // We start from latest, so no newer data initially
                break;
            case OLDER:
                newsList.addAll(apiNews);
                hasMoreOlderData = apiNews.size() >= pageSize;
                break;
            case NEWER:
                java.util.Collections.reverse(apiNews);
                newsList.addAll(0, apiNews);
                hasMoreNewerData = apiNews.size() >= pageSize;
                break;
        }
    }

    /**
     * Saves the current news list to cache for next loadInitialData
     */
    private void saveCurrentListToCache() {
        new Thread(() -> {
            try {
                int savedCount = fcObjectManager.saveCurrentNewsList(newsList);
                if (savedCount > 0) {
                    TimberLogger.i(TAG, "Saved %d news items to cache", savedCount);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error saving current list to cache: %s", e.getMessage());
            }
        }).start();
    }

    /**
     * Updates the latest and earliest news tracking based on current list.
     */
    private void updateLatestAndEarliestNews() {
        if (newsList.isEmpty()) {
            latestNews = null;
            earliestNews = null;
            return;
        }

        // Since the list is displayed in time DESC order, first item is latest, last item is earliest
        latestNews = newsList.get(0);
        earliestNews = newsList.get(newsList.size() - 1);
    }

    private void updateButtonStates() {
    }

    private void loadListContainer() {
        TimberLogger.d(TAG, "Loading list container with %d news items", newsList.size());

        newsListContainer = findViewById(R.id.fragment_container);
        newsCardContainer = new NewsCardContainer(this, newsListContainer, ChooseMode.CHOOSE_MULTI);

        // Set the ScrollView for scroll position management
        newsCardContainer.setScrollView(newsScrollView);

        newsCardContainer.setOnNewsListChangedListener(updatedNewsList -> updateUI());

        Map<String, String> cidMap = getCidMapForNewsList(newsList);

        for (News news : newsList) {
            newsCardContainer.addNewsCard(news, cidMap);
        }

        // Clear any boundary indicators when initially loading the container
        if (newsCardContainer != null) {
            newsCardContainer.clearAllBoundaryIndicators();
        }

        updateUI();

        TimberLogger.d(TAG, "List container loading completed");
    }

    private Map<String, String> getCidMapForNewsList(List<News> newsList) {
        CidFidManager cidFidManager = CidFidManager.getInstance(this);
        if (cidFidManager == null) {
            return null;
        }

        List<String> fidList = new ArrayList<>();
        for (News news : newsList) {
            fidList.add(news.getDoer());
        }
        return cidFidManager.getCidsByFids(fidList);
    }

    /**
     * Updates the statistics TextView with container size and total on-chain
     */
    private void updateStatistics() {
        if (newsStatisticsTextView == null || fcObjectManager == null) {
            return;
        }

        // Get container size
        int containerSize = newsList != null ? newsList.size() : 0;

        // Get total on-chain from FcObjectManager
        Long totalOnChain = fcObjectManager.getTotalNews();

        StringBuilder statsText = new StringBuilder();
        statsText.append(getString(R.string.above_in_statistics)).append(containerSize);

        if (totalOnChain != null) {
            statsText.append(getString(R.string.onchain_in_statistics)).append(totalOnChain);
        }

        newsStatisticsTextView.setText(statsText.toString());
        newsStatisticsContainer.setVisibility(View.VISIBLE);

        // Show or hide the More button based on hasMoreOlderData
        if (loadMoreButton != null) {
            loadMoreButton.setVisibility(hasMoreOlderData ? View.VISIBLE : View.GONE);
        }
    }

    private void updateUI() {
        updateButtonStates();
        updateStatistics();
        currentSortState = SortState.NONE;
        currentSortType = SortType.NAME;
        setDefaultSortColors();
    }


    /**
     * Performs the search from API with optional reference news for pagination
     */
    private void performSearchFromAPI(News referenceNews) {
        showWaitingDialog(getString(R.string.searching));

        new Thread(() -> {
            try {
                List<News> searchResults = fcObjectManager.searchNewsFromApi(
                        currentSearchQuery,
                    currentSearchField,
                    currentSortField,
                    currentSortOrder,
                    referenceNews
                );

                runOnUiThread(() -> {
                    if (searchResults != null && !searchResults.isEmpty()) {
                        if (referenceNews == null) {
                            // Initial search - replace all results
                            newsList.clear();
                            newsList.addAll(searchResults);
                            hasMoreOlderData = searchResults.size() >= pageSize;
                            hasMoreNewerData = false;
                        } else {
                            // Pagination - this will be handled by loadNewerNews or loadOlderNews
                        }

                        updateLatestAndEarliestNews();

                        if (newsCardContainer != null) {
                            newsCardContainer.clearAll();
                        }
                        loadListContainer();

                        dismissWaitingDialog();
                        showToast(getString(R.string.found_news_count, searchResults.size()));
                    } else {
                        dismissWaitingDialog();
                        showToast(getString(R.string.no_news_found));
                    }

                    updateUI();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error performing search: %s", e.getMessage());
                runOnUiThread(() -> {
                    dismissWaitingDialog();
                    showToast(getString(R.string.error_searching_news, e.getMessage()));
                });
            }
        }).start();
    }

    /**
     * Sets up the spinner dropdowns specific to News
     */
    private void setupNewsSpinners() {
        // Determine language for display names
        boolean useChinese = getResources().getConfiguration().locale.getLanguage().equals("zh");

        // Set up 'Search in' spinner with News searchable fields
        if (searchFieldSpinner != null) {
            // Get searchable fields from News class
            LinkedHashMap<String, Map<String, String>> searchableFields = News.getSearchableFields();

            // Build options array starting with the placeholder
            List<String> fieldOptionsList = new ArrayList<>();
            fieldOptionsList.add(getString(R.string.search_in)); // First item is placeholder

            // Add searchable field display names
            for (Map.Entry<String, Map<String, String>> entry : searchableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                fieldOptionsList.add(displayName);
            }

            String[] fieldOptions = fieldOptionsList.toArray(new String[0]);
            setupSpinner(searchFieldSpinner, fieldOptions, R.string.field);
        }

        // Set up sort spinner with News sortable fields
        if (sortFieldSpinner != null) {
            // Get sortable fields from News class
            LinkedHashMap<String, Map<String, String>> sortableFields = News.getSortableFields();

            // Build options array starting with the placeholder
            List<String> sortOptionsList = new ArrayList<>();
            sortOptionsList.add(getString(R.string.sort_by)); // First item is placeholder

            // Add sortable field display names
            for (Map.Entry<String, Map<String, String>> entry : sortableFields.entrySet()) {
                Map<String, String> languages = entry.getValue();
                String displayName = useChinese ? languages.get("zh") : languages.get("en");
                sortOptionsList.add(displayName);
            }

            String[] sortOptions = sortOptionsList.toArray(new String[0]);
            setupSpinner(sortFieldSpinner, sortOptions, R.string.field);
        }

        // Set up Order spinner
        if (sortOrderSpinner != null) {
            String[] orderOptions = new String[]{"Order", "DESC", "ASC"};
            setupSpinner(sortOrderSpinner, orderOptions, R.string.order);
        }
    }

    /**
     * Sets up all sort button listeners
     */
    private void setupSortButtons() {
        if (nameSortButton != null) {
            nameSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.NAME) {
                    currentSortType = SortType.NAME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortNewsList();
            });
        }

        if (doerSortButton != null) {
            doerSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.DOER) {
                    currentSortType = SortType.DOER;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortNewsList();
            });
        }

        if (typeSortButton != null) {
            typeSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.TYPE) {
                    currentSortType = SortType.TYPE;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortNewsList();
            });
        }
    }

    /**
     * Sets up swipe refresh functionality for loading newer news
     */
    private void setupSwipeRefreshNews() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                TimberLogger.i(TAG, "Swipe refresh triggered - loading newer news");
                loadNewerNews();
            });

            // Set colors for the refresh indicator
            swipeRefreshLayout.setColorSchemeResources(
                android.R.color.holo_blue_bright,
                android.R.color.holo_green_light,
                android.R.color.holo_orange_light,
                android.R.color.holo_red_light);
        }
    }

    /**
     * Loads newer news when user swipes down from top
     */
    private void loadNewerNews() {
        if (isLoadingNewer || isLoadingOlder) {
            swipeRefreshLayout.setRefreshing(false);
            return;
        }

        isLoadingNewer = true;

        new Thread(() -> {
            try {
                List<News> newerNews = fetchNewerNewsFromAPI();

                runOnUiThread(() -> {
                    handleNewerNewsResult(newerNews);
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading newer news: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_loading_newer_news, e.getMessage()));
                    isLoadingNewer = false;
                    swipeRefreshLayout.setRefreshing(false);
                });
            }
        }).start();
    }

    private List<News> fetchNewerNewsFromAPI() {
        if (isSearchMode && currentSearchQuery != null && !currentSearchQuery.isEmpty()) {
            return fcObjectManager.searchNewsFromApi(
                    currentSearchQuery,
                currentSearchField,
                currentSortField,
                currentSortOrder,
                latestNews
            );
        } else {
            return fcObjectManager.fetchNewsFromAPI(
                    NEWER,
                latestNews
            );
        }
    }

    private void handleNewerNewsResult(List<News> newerNews) {
        if (newerNews == null) {
            ToastUtils.makeText(this, getString(R.string.no_more_news));
            return;
        }

        if (newerNews.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_new_news_found));
            return;
        }

        int itemsToRemoveFromEnd = calculateItemsToRemove(newerNews.size());

        if (itemsToRemoveFromEnd > 0) {
            hasMoreOlderData = true;
            // Remove from end of data list
            removeItemsFromEnd(itemsToRemoveFromEnd);
        }

        // Reverse the list to get newest first
        java.util.Collections.reverse(newerNews);

        // Add new items to the beginning of data list
        newsList.addAll(0, newerNews);
        updateLatestAndEarliestNews();

        // Get CID map for the new items
        Map<String, String> cidMap = getCidMapForNewsList(newerNews);

        // Update the container with scroll preservation
        // This will remove items from bottom if needed and add new items to top
        // while adjusting the scroll position so user stays at the same visual location
        if (newsCardContainer != null) {
            newsCardContainer.addNewsCardsToBeginningWithBottomRemoval(newerNews, cidMap, FreerApplication.MAX_CONTAINER_SIZE);
        }

        hasMoreNewerData = newerNews.size() >= pageSize;

        updateStatistics();
        ToastUtils.makeText(this, getString(R.string.loaded_newer_news, newerNews.size()));

        TimberLogger.i(TAG, "Loaded %d newer news items, window size: %d/%d",
            newerNews.size(), newsList.size(), FreerApplication.MAX_CONTAINER_SIZE);
    }

    private void removeItemsFromEnd(int count) {
        TimberLogger.i(TAG, "Removing %d items from end to maintain window size", count);
        for (int i = 0; i < count && !newsList.isEmpty(); i++) {
            newsList.remove(newsList.size() - 1);
        }
    }



    /**
     * Loads older news when user clicks the More button
     */
    private void loadOlderNews() {
        if (isLoadingNewer || isLoadingOlder || !hasMoreOlderData) {
            return;
        }

        isLoadingOlder = true;

        // Disable the More button while loading
        if (loadMoreButton != null) {
            loadMoreButton.setEnabled(false);
            loadMoreButton.setAlpha(0.5f);
        }

        showToast(getString(R.string.loading_more_from_apip));

        new Thread(() -> {
            try {
                List<News> olderNews = fetchOlderNewsFromAPI();

                runOnUiThread(() -> {
                    handleOlderNewsResult(olderNews);
                    isLoadingOlder = false;

                    // Re-enable the More button after loading
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading older news: %s", e.getMessage());
                runOnUiThread(() -> {
                    isLoadingOlder = false;
                    showToast(getString(R.string.error_loading_more_news, e.getMessage()));

                    // Re-enable the More button after error
                    if (loadMoreButton != null) {
                        loadMoreButton.setEnabled(true);
                        loadMoreButton.setAlpha(1.0f);
                    }
                });
            }
        }).start();
    }

    private List<News> fetchOlderNewsFromAPI() {
        if (isSearchMode && currentSearchQuery != null && !currentSearchQuery.isEmpty()) {
            return fcObjectManager.searchNewsFromApi(
                    currentSearchQuery,
                currentSearchField,
                currentSortField,
                currentSortOrder,
                earliestNews
            );
        } else {
            return fcObjectManager.fetchNewsFromAPI(
                    OLDER,
                earliestNews
            );
        }
    }

    private void handleOlderNewsResult(List<News> olderNews) {
        if (olderNews != null && !olderNews.isEmpty()) {
            int itemsToRemoveFromBeginning = calculateItemsToRemove(olderNews.size());

            if (itemsToRemoveFromBeginning > 0) {
                hasMoreNewerData = true;
                // Remove from beginning of data list
                removeItemsFromBeginning(itemsToRemoveFromBeginning);
            }

            // Add new items to the end of data list
            newsList.addAll(olderNews);
            updateLatestAndEarliestNews();

            // Get CID map for the new items
            Map<String, String> cidMap = getCidMapForNewsList(olderNews);

            // Update the container with scroll preservation
            // This will remove items from top if needed and add new items to bottom
            // while preserving the scroll position so user can scroll down to see new items
            if (newsCardContainer != null) {
                newsCardContainer.addNewsCardsToEndWithTopRemoval(olderNews, cidMap, FreerApplication.MAX_CONTAINER_SIZE);
            }

            if (olderNews.size() < pageSize) {
                hasMoreOlderData = false;
            }

            updateStatistics();
            showToast(getString(R.string.loaded_more_news, olderNews.size()));

            TimberLogger.i(TAG, "Loaded %d older news items, window size: %d/%d",
                olderNews.size(), newsList.size(), FreerApplication.MAX_CONTAINER_SIZE);
        } else {
            hasMoreOlderData = false;
            showToast(getString(R.string.no_more_news));

            // Update the More button visibility
            if (loadMoreButton != null) {
                loadMoreButton.setVisibility(View.GONE);
            }
        }
    }

    private int calculateItemsToRemove(int newItemsCount) {
        int currentSize = newsList.size();
        int newTotalSize = currentSize + newItemsCount;
        return Math.max(0, newTotalSize - FreerApplication.MAX_CONTAINER_SIZE);
    }

    private void removeItemsFromBeginning(int count) {
        TimberLogger.i(TAG, "Removing %d items from beginning to maintain window size", count);
        for (int i = 0; i < count && !newsList.isEmpty(); i++) {
            newsList.remove(0);
        }
    }

    private void refreshNewsContainer() {
        if (newsCardContainer != null) {
            newsCardContainer.clearAll();
        }
        loadListContainer();
    }


    /**
     * Cycles through the sort states: NONE -> ASCENDING -> DESCENDING -> NONE
     */
    private void cycleSortState() {
        switch (currentSortState) {
            case NONE:
                currentSortState = SortState.ASCENDING;
                break;
            case ASCENDING:
                currentSortState = SortState.DESCENDING;
                break;
            case DESCENDING:
                currentSortState = SortState.NONE;
                break;
        }
    }

    /**
     * Sets default colors for all sort labels and icons
     */
    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);

        if (nameSortLabel != null) {
            nameSortLabel.setTextColor(hintColor);
        }
        if (nameSortIcon != null) {
            nameSortIcon.setColorFilter(hintColor);
        }

        if (doerSortLabel != null) {
            doerSortLabel.setTextColor(hintColor);
        }
        if (doerSortIcon != null) {
            doerSortIcon.setColorFilter(hintColor);
        }

        if (typeSortLabel != null) {
            typeSortLabel.setTextColor(hintColor);
        }
        if (typeSortIcon != null) {
            typeSortIcon.setColorFilter(hintColor);
        }
    }

    /**
     * Resets other sort icons to NONE when switching sort types
     */
    private void resetOtherSortIcons() {
        int hintColor = getResources().getColor(R.color.hint, null);
        currentSortState = SortState.NONE;
        if (currentSortType == SortType.NAME) {
            // Reset other sort icons and labels
            if (doerSortIcon != null) {
                doerSortIcon.setImageResource(R.drawable.ic_sort_none);
                doerSortIcon.setColorFilter(hintColor);
            }
            if (doerSortLabel != null) {
                doerSortLabel.setTextColor(hintColor);
            }
            if (typeSortIcon != null) {
                typeSortIcon.setImageResource(R.drawable.ic_sort_none);
                typeSortIcon.setColorFilter(hintColor);
            }
            if (typeSortLabel != null) {
                typeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.DOER) {
            // Reset other sort icons and labels
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) {
                nameSortLabel.setTextColor(hintColor);
            }
            if (typeSortIcon != null) {
                typeSortIcon.setImageResource(R.drawable.ic_sort_none);
                typeSortIcon.setColorFilter(hintColor);
            }
            if (typeSortLabel != null) {
                typeSortLabel.setTextColor(hintColor);
            }
        } else if (currentSortType == SortType.TYPE) {
            // Reset other sort icons and labels
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) {
                nameSortLabel.setTextColor(hintColor);
            }
            if (doerSortIcon != null) {
                doerSortIcon.setImageResource(R.drawable.ic_sort_none);
                doerSortIcon.setColorFilter(hintColor);
            }
            if (doerSortLabel != null) {
                doerSortLabel.setTextColor(hintColor);
            }
        }
    }

    /**
     * Updates the sort icons based on current sort state and type
     */
    private void updateSortIcons() {
        int iconResource;
        switch (currentSortState) {
            case NONE:
                iconResource = R.drawable.ic_sort_none;
                break;
            case ASCENDING:
                iconResource = R.drawable.ic_sort_asc;
                break;
            case DESCENDING:
                iconResource = R.drawable.ic_sort_desc;
                break;
            default:
                iconResource = R.drawable.ic_sort_none;
        }

        int color;
        switch (currentSortState) {
            case DESCENDING:
                color = getResources().getColor(R.color.accent, null);
                break;
            case ASCENDING:
                color = getResources().getColor(R.color.pink, null);
                break;
            default:
                color = getResources().getColor(R.color.hint, null);
                break;
        }

        if (currentSortType == SortType.NAME && nameSortIcon != null && nameSortLabel != null) {
            nameSortIcon.setImageResource(iconResource);
            nameSortIcon.setColorFilter(color);
            nameSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.DOER && doerSortIcon != null && doerSortLabel != null) {
            doerSortIcon.setImageResource(iconResource);
            doerSortIcon.setColorFilter(color);
            doerSortLabel.setTextColor(color);
        } else if (currentSortType == SortType.TYPE && typeSortIcon != null && typeSortLabel != null) {
            typeSortIcon.setImageResource(iconResource);
            typeSortIcon.setColorFilter(color);
            typeSortLabel.setTextColor(color);
        }
    }

    /**
     * Sorts the news list according to current sort state and type
     */
    private void sortNewsList() {
        if (newsCardContainer != null) {
            boolean ascending = currentSortState == SortState.ASCENDING;
            boolean enableSort = currentSortState != SortState.NONE;

            if (currentSortType == SortType.NAME) {
                newsCardContainer.sortByObjectName(ascending, enableSort);
            } else if (currentSortType == SortType.DOER) {
                newsCardContainer.sortByDoer(ascending, enableSort);
            } else if (currentSortType == SortType.TYPE) {
                newsCardContainer.sortByObjectType(ascending, enableSort);
            }

            // If all sort buttons are in NONE state, restore default order (desc by time)
            if (currentSortState == SortState.NONE) {
                newsCardContainer.sortByTime(false, true); // false = descending, true = enable sort
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

        // Save current list to cache when activity is closing
        saveCurrentListToCache();

        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
        waitingDialog = null;
    }
}
