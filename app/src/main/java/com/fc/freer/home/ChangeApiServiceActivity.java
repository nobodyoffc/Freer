package com.fc.freer.home;

import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.Strings.API;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.fc.fc_ajdk.fapi.FapiCode;
import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.freer.model.Configure;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.freer.model.Setting;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCardContainer;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChangeApiServiceActivity extends BaseCryptoActivity {
    private static final String TAG = "ChangeApiServiceActivity";

    public static final String EXTRA_SERVICE_TYPE = "service_type";
    public static final String EXTRA_OLD_PROVIDER_ID = "old_provider_id";
    public static final String EXTRA_SELECTED_SERVICE = "selected_service";

    private LinearLayout apiCardsContainer;
    private android.widget.ScrollView scrollView;
    private EditText apiSearchBox;
    private ImageButton searchButton;
    private ImageButton clearButton;
    private ImageButton doneButton;

    // Sort buttons
    private LinearLayout timeSortButton;
    private ImageView timeSortIcon;
    private LinearLayout rateSortButton;
    private ImageView rateSortIcon;
    private LinearLayout cddSortButton;
    private ImageView cddSortIcon;

    private ApiCardContainer apiCardContainer;
    private Service.ServiceType serviceType;
    private String oldProviderId;
//    private ApiProvider currentProvider;
    private List<ApiProvider> apiProviderList;
    private WaitingDialog waitingDialog;

    // Sort state
    private enum SortField { DEFAULT, TIME, RATE, CDD }
    private enum SortOrder { NONE, ASC, DESC }
    private SortField currentSortField = SortField.DEFAULT;
    private SortOrder currentSortOrder = SortOrder.NONE;

    private List<String> last = null;
    private boolean hasMore = true;
    private boolean isLoadingMore = false;


    @Override
    protected int getLayoutId() {
        return R.layout.activity_change_api_service;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.change_api_service);
    }

    @Override
    protected void initializeViews() {
        apiCardsContainer = findViewById(R.id.api_cards_container);
        scrollView = findViewById(R.id.scroll_view);
        apiSearchBox = findViewById(R.id.api_search_box);
        searchButton = findViewById(R.id.do_button);
        clearButton = findViewById(R.id.clear_button);
        doneButton = findViewById(R.id.done_button);

        // Initialize sort buttons
        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortIcon = findViewById(R.id.time_sort_icon);
        rateSortButton = findViewById(R.id.rate_sort_button);
        rateSortIcon = findViewById(R.id.rate_sort_icon);
        cddSortButton = findViewById(R.id.cdd_sort_button);
        cddSortIcon = findViewById(R.id.cdd_sort_icon);

        // Initialize ApiCardContainer with selectable mode
        apiCardContainer = new ApiCardContainer(this, apiCardsContainer, true);

        // Setup scroll listener for infinite scroll
        setupScrollListener();
    }

    @Override
    protected void setupButtons() {
        searchButton.setOnClickListener(v -> doApiServices());
        clearButton.setOnClickListener(v -> clearSelection());
        doneButton.setOnClickListener(v -> returnSelectedService());

        // Setup sort button listeners
        timeSortButton.setOnClickListener(v -> handleSortClick(SortField.TIME));
        rateSortButton.setOnClickListener(v -> handleSortClick(SortField.RATE));
        cddSortButton.setOnClickListener(v -> handleSortClick(SortField.CDD));
    }

    /**
     * Setup scroll listener to detect when user scrolls to bottom
     */
    private void setupScrollListener() {
        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (scrollView.getChildAt(0) == null) {
                return;
            }

            int scrollY = scrollView.getScrollY();
            int scrollViewHeight = scrollView.getHeight();
            int childHeight = scrollView.getChildAt(0).getHeight();

            // Check if scrolled to bottom (with a small threshold)
            if (scrollY + scrollViewHeight >= childHeight - 20) {
                // User has scrolled to the bottom
                loadMoreIfAvailable();
            }
        });
    }

    /**
     * Load more services if available
     */
    private void loadMoreIfAvailable() {
        // Check if we've reached the maximum container size
        if (apiCardContainer.getCardCount() >= FreerApplication.MAX_CONTAINER_SIZE) {
            if (hasMore) {
                hasMore = false;
                Toast.makeText(this, R.string.max_api_services_reached, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (hasMore && last != null && !isLoadingMore) {
            isLoadingMore = true;
            loadMoreServices();
        }
    }

    /**
     * Load more services from API
     */
    private void loadMoreServices() {
        new Thread(() -> {
            String searchQuery = apiSearchBox.getText().toString().trim();

            // Don't load more for URL-based searches
            if (searchQuery.toLowerCase().startsWith("http")) {
                runOnUiThread(() -> isLoadingMore = false);
                return;
            }

            List<ApiProvider> tempApiProviderList = new ArrayList<>();

            try {
                if (searchServiceFromApi(searchQuery, tempApiProviderList)) {
                    // Error occurred
                    runOnUiThread(() -> isLoadingMore = false);
                    return;
                }

                // Update UI on main thread
                runOnUiThread(() -> {
                    if (!tempApiProviderList.isEmpty()) {
                        apiCardContainer.addAllApiCard(tempApiProviderList);
                        Toast.makeText(this, getString(R.string.loaded_more_services, tempApiProviderList.size()), Toast.LENGTH_SHORT).show();
                    }
                    isLoadingMore = false;
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error loading more services: " + e.getMessage(), e);
                runOnUiThread(() -> isLoadingMore = false);
            }
        }).start();
    }

    /**
     * Handle sort button clicks - cycle through NONE -> DESC -> ASC -> NONE
     */
    private void handleSortClick(SortField field) {
        last = null;
        hasMore = true;
        if (currentSortField == field) {
            // Cycle through sort orders for the same field
            currentSortOrder = getNextSortOrder(currentSortOrder);
            if (currentSortOrder == SortOrder.NONE) {
                // Reset to default
                currentSortField = SortField.DEFAULT;
            }
        } else {
            // New field selected, start with DESC
            currentSortField = field;
            currentSortOrder = SortOrder.DESC;
        }

        updateSortIcons();

        // If we have local data, sort it locally. Otherwise, search with new sort
        if (apiCardContainer.getCardCount() > 0) {
            sortLocalCards();
        } else {
            doApiServices();
        }
    }

    /**
     * Get next sort order in the cycle: NONE -> DESC -> ASC -> NONE
     */
    private SortOrder getNextSortOrder(SortOrder current) {
        return switch (current) {
            case NONE -> SortOrder.DESC;
            case DESC -> SortOrder.ASC;
            default -> SortOrder.NONE;
        };
    }

    /**
     * Update sort icons based on current sort state
     */
    private void updateSortIcons() {
        // Reset all icons
        timeSortIcon.setImageResource(R.drawable.ic_sort_none);
        rateSortIcon.setImageResource(R.drawable.ic_sort_none);
        cddSortIcon.setImageResource(R.drawable.ic_sort_none);

        // Set active icon based on current sort
        ImageView activeIcon;
        switch (currentSortField) {
            case TIME:
                activeIcon = timeSortIcon;
                break;
            case RATE:
                activeIcon = rateSortIcon;
                break;
            case CDD:
                activeIcon = cddSortIcon;
                break;
            case DEFAULT:
            default:
                // No active icon for default sort
                return;
        }

        if (activeIcon != null) {
            switch (currentSortOrder) {
                case DESC:
                    activeIcon.setImageResource(R.drawable.ic_sort_desc);
                    break;
                case ASC:
                    activeIcon.setImageResource(R.drawable.ic_sort_asc);
                    break;
                case NONE:
                default:
                    activeIcon.setImageResource(R.drawable.ic_sort_none);
                    break;
            }
        }
    }

    /**
     * Sort the local cards based on current sort state
     */
    private void sortLocalCards() {
        Comparator<ApiProvider> comparator = getComparator();
        if (comparator != null) {
            apiCardContainer.sortCards(comparator);
        }
    }

    /**
     * Get comparator based on current sort state
     */
    private Comparator<ApiProvider> getComparator() {
        if (currentSortField == SortField.DEFAULT || currentSortOrder == SortOrder.NONE) {
            // Default sort: lastHeight desc, id desc
            // ApiProvider now extends Service, so access properties directly
            return (p1, p2) -> {
                // Compare by lastHeight
                Long h1 = p1.getLastHeight();
                Long h2 = p2.getLastHeight();

                if (h1 == null && h2 == null) return compareById(p1, p2, false);
                if (h1 == null) return 1;
                if (h2 == null) return -1;

                int result = h2.compareTo(h1); // DESC
                if (result != 0) return result;

                return compareById(p1, p2, false);
            };
        }

        return switch (currentSortField) {
            case TIME ->
                // ApiProvider now extends Service, so access properties directly
                    (p1, p2) -> {
                        Long t1 = p1.getBirthTime();
                        Long t2 = p2.getBirthTime();

                        if (t1 == null && t2 == null) return compareById(p1, p2, false);
                        if (t1 == null) return 1;
                        if (t2 == null) return -1;

                        int result = currentSortOrder == SortOrder.DESC ? t2.compareTo(t1) : t1.compareTo(t2);
                        if (result != 0) return result;

                        return compareById(p1, p2, false);
                    };
            case RATE ->
                // ApiProvider now extends Service, so access properties directly
                    (p1, p2) -> {
                        Float r1 = p1.gettRate();
                        Float r2 = p2.gettRate();

                        if (r1 == null && r2 == null) return compareById(p1, p2, false);
                        if (r1 == null) return 1;
                        if (r2 == null) return -1;

                        int result = currentSortOrder == SortOrder.DESC ? r2.compareTo(r1) : r1.compareTo(r2);
                        if (result != 0) return result;

                        return compareById(p1, p2, false);
                    };
            case CDD ->
                // ApiProvider now extends Service, so access properties directly
                    (p1, p2) -> {
                        Long c1 = p1.gettCdd();
                        Long c2 = p2.gettCdd();

                        if (c1 == null && c2 == null) return compareById(p1, p2, false);
                        if (c1 == null) return 1;
                        if (c2 == null) return -1;

                        int result = currentSortOrder == SortOrder.DESC ? c2.compareTo(c1) : c1.compareTo(c2);
                        if (result != 0) return result;

                        return compareById(p1, p2, false);
                    };
            default -> null;
        };
    }

    /**
     * Compare by ID as a tiebreaker
     */
    private int compareById(ApiProvider p1, ApiProvider p2, boolean ascending) {
        String id1 = p1.getId();
        String id2 = p2.getId();

        if (id1 == null && id2 == null) return 0;
        if (id1 == null) return 1;
        if (id2 == null) return -1;

        return ascending ? id1.compareTo(id2) : id2.compareTo(id1);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Get extras from intent before calling super
        Intent intent = getIntent();
        if (intent.hasExtra(EXTRA_SERVICE_TYPE)) {
            String serviceTypeName = intent.getStringExtra(EXTRA_SERVICE_TYPE);
            try {
                serviceType = Service.ServiceType.valueOf(serviceTypeName);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Invalid service type: " + serviceTypeName, e);
                finish();
                return;
            }
        }

        // Get current provider if passed (null means adding new)
        this.oldProviderId = intent.getStringExtra(EXTRA_OLD_PROVIDER_ID);

        super.onCreate(savedInstanceState);

        // Load initial data (empty for now, will be populated by search)
        loadInitialServices();
    }

    /**
     * Load initial services - load all providers of the same service type from Configure
     */
    private void loadInitialServices() {
        apiCardContainer.clearAll();

        try {
            // Get current configure
            Configure configure = ConfigureManager.getInstance().getConfigure();
            if (configure == null || configure.getApiProviderMap() == null) {
                TimberLogger.w(TAG, "No configure or apiProviderMap found");
                showEmptyMessage();
                return;
            }

            // Get all providers with the same service type
            Map<String, ApiProvider> apiProviderMap = configure.getApiProviderMap();
            List<String> providerIds = new ArrayList<>();

            for (Map.Entry<String, ApiProvider> entry : apiProviderMap.entrySet()) {
                ApiProvider provider = entry.getValue();
                if (provider.fetchServiceType() == serviceType) {
                    // Add card using ApiCardContainer (don't show converter icon)
                    apiCardContainer.addApiCard(serviceType, apiCardContainer.getCardCount(), provider, false);

                    if (provider.getId() != null) {
                        providerIds.add(provider.getId());
                    }
                }
            }

            // Show empty message if no cards were added
            if (apiCardContainer.getCardCount() == 0) {
                showEmptyMessage();
                return;
            }

            // Auto-select first card
            apiCardContainer.selectCardAtPosition(0);

            // Fetch service info for all providers in batch
            if (!providerIds.isEmpty()) {

                new Thread(() -> {
                    try {
                        FapiClient fapiClient = (FapiClient)
                            ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);

                        if (fapiClient != null) {
                            Map<String, Service> serviceMap = fapiClient.serviceByIds(
                                providerIds
                            );

                            if (serviceMap != null) {
                                // Update UI on main thread
                                runOnUiThread(() -> {
                                    List<ApiProvider> providers = apiCardContainer.getApiProviderList();
                                    for (int i = 0; i < providers.size(); i++) {
                                        ApiProvider provider = providers.get(i);
                                        if (provider.getId() != null) {
                                            Service service = serviceMap.get(provider.getId());
                                            if (service != null) {
                                                provider.updateWithService(service);
                                                apiCardContainer.updateCardStateAtPosition(i, provider);
                                            }
                                        }
                                    }
                                });
                            }
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error fetching service info batch: " + e.getMessage(), e);
                    }
                }).start();
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading initial services: " + e.getMessage(), e);
            showEmptyMessage();
        }
    }

    /**
     * Search for API services using FAPI client
     */
    private void doApiServices() {
        // Reset pagination state
        last = null;
        hasMore = true;

        // Show waiting dialog
        runOnUiThread(() -> {
            waitingDialog = new WaitingDialog(this, getString(R.string.searching_api_services));
            waitingDialog.show();
        });

        new Thread(() -> {
            String searchQuery = apiSearchBox.getText().toString().trim();

            List<ApiProvider> tempApiProviderList = new ArrayList<>();

            try {
                if(searchQuery.toLowerCase().startsWith("fudp://")){
                    // Get FudpNode from Setting
                    Setting setting = ApiCenter.getInstance().getCurrentSetting();
                    if (setting == null) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(this, getString(R.string.error_no_setting));
                            if (waitingDialog != null && waitingDialog.isShowing()) {
                                waitingDialog.dismiss();
                            }
                        });
                        return;
                    }
                    
                    FudpNode fudpNode = setting.getFudpNode();
                    if (fudpNode == null || !fudpNode.isRunning()) {
                        String dataDir = getFilesDir().getAbsolutePath() + "/fudp";
                        fudpNode = setting.initFudpNode(dataDir);
                    }
                    
                    if (fudpNode == null) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(this, getString(R.string.error_init_fudp_node));
                            if (waitingDialog != null && waitingDialog.isShowing()) {
                                waitingDialog.dismiss();
                            }
                        });
                        return;
                    }
                    
                    // Discover service via HELLO + PING
                    ApiProvider apiProvider = FapiClient.getApiProviderFromUrl(fudpNode, searchQuery, serviceType);
                    if (apiProvider == null) {
                        runOnUiThread(() -> {
                            ToastUtils.makeText(this, getString(R.string.error_discover_service));
                            if (waitingDialog != null && waitingDialog.isShowing()) {
                                waitingDialog.dismiss();
                            }
                        });
                        return;
                    }
                    
                    apiProvider.makeServiceType(serviceType);
                    apiProvider.setApiUrl(searchQuery);
                    tempApiProviderList.add(apiProvider);
                }
                else {
                    if (searchServiceFromApi(searchQuery, tempApiProviderList)) return;
                }

                // Update UI on main thread
                runOnUiThread(() -> {
                    apiCardContainer.clearAll();
                    apiProviderList = tempApiProviderList;

                    // Add all cards at once and refresh UI
                    if (!apiProviderList.isEmpty()) {
                        apiCardContainer.addAllApiCard(apiProviderList);
                        // Auto-select first card
                        apiCardContainer.selectCardAtPosition(0);
                        Toast.makeText(this, getString(R.string.got_api_services, apiProviderList.size()), Toast.LENGTH_SHORT).show();
                    } else {
                        showEmptyMessage();
                        Toast.makeText(this, getString(R.string.no_api_services_found), Toast.LENGTH_SHORT).show();
                    }

                    TimberLogger.d(TAG, "Search completed: " + apiProviderList.size() + " results found");

                    // Dismiss waiting dialog
                    if (waitingDialog != null && waitingDialog.isShowing()) {
                        waitingDialog.dismiss();
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching API services: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_searching_api_services,e.getMessage()));

                    // Dismiss waiting dialog
                    if (waitingDialog != null && waitingDialog.isShowing()) {
                        waitingDialog.dismiss();
                    }
                });
            }
        }).start();
    }

    private boolean searchServiceFromApi(String searchQuery, List<ApiProvider> tempApiProviderList) {
        boolean res = true;
        Fcdsl fcdsl = makeSearchServiceFcdsl(searchQuery, last);
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);

        if (fapiClient != null) {
            FapiResponse result = fapiClient.search(fcdsl);
            if (result == null || result.getCode() != 0) {
                if (result != null && result.getCode().equals(FapiCode.NOT_FOUND)) {
                    runOnUiThread(() -> {
                        showEmptyMessage();
                        Toast.makeText(this, getString(R.string.no_api_services_found), Toast.LENGTH_SHORT).show();
                        if (waitingDialog != null && waitingDialog.isShowing()) {
                            waitingDialog.dismiss();
                        }
                    });
                }else{
                    runOnUiThread(() -> {
                        showEmptyMessage();
                        Toast.makeText(this, getString(R.string.no_api_services_found), Toast.LENGTH_SHORT).show();
                        if (waitingDialog != null && waitingDialog.isShowing()) {
                            waitingDialog.dismiss();
                        }
                    });
                }
            } else {
                Object obj = result.getData();
                if (obj != null) {
                    List<Service> serviceList = ObjectUtils.objectToList(obj, Service.class);
                    if (serviceList != null) {
                        for (Service service : serviceList) {
                            if (service == null) continue;
                            ApiProvider apiProvider = ApiProvider.fromService(service, serviceType);
                            if (apiProvider == null) continue;
                            apiProvider.makeServiceType(serviceType);
                            tempApiProviderList.add(apiProvider);
                        }
                        last = result.getLast();
                        if (serviceList.size() < FreerApplication.DEFAULT_REQUEST_SIZE) {
                            last = null;
                            hasMore = false;
                        }
                        res = false;
                    }
                }
            }
        }

        return res;
    }

    private Fcdsl makeSearchServiceFcdsl(String searchQuery,List<String> after) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(IndicesNames.SERVICE);
        fcdsl.addNewQuery();
        fcdsl.getQuery().addNewMatch().addNewFields(FieldNames.TYPE).addNewValue(serviceType.toString());
        fcdsl.getQuery().addNewEquals().addNewFields(FieldNames.ACTIVE).addNewValues(TRUE);

        if(searchQuery!=null && !searchQuery.isEmpty())
            fcdsl.addNewFilter().addNewMatch().addNewFields(FieldNames.STD_NAME,FieldNames.LOCAL_NAMES, ID,FieldNames.OWNER,FieldNames.DESC,FieldNames.URLS).addNewValue(searchQuery);

        fcdsl.setSize(String.valueOf(FreerApplication.DEFAULT_REQUEST_SIZE));

        // Apply sort based on current sort state
        if (currentSortField == SortField.DEFAULT || currentSortOrder == SortOrder.NONE) {
            // Default sort: lastHeight desc, id desc
            fcdsl.addSort(FieldNames.LAST_HEIGHT, DESC).addSort(ID, DESC);
        } else {
            // Apply custom sort - clear existing sort first
            String sortOrder = currentSortOrder == SortOrder.DESC ? DESC : ASC;
            String sortFieldName = switch (currentSortField) {
                case TIME -> FieldNames.BIRTH_TIME;
                case RATE -> FieldNames.T_RATE;
                case CDD -> FieldNames.T_CDD;
                default -> FieldNames.LAST_HEIGHT;
            };
            fcdsl.addSort(sortFieldName, sortOrder).addSort(ID, sortOrder);
        }

        if(after!=null && !after.isEmpty())
            fcdsl.setAfter(after);
        return fcdsl;
    }

    /**
     * Show empty message when no API services are found
     */
    private void showEmptyMessage() {
        TextView emptyView = new TextView(this);
        emptyView.setText(R.string.no_api_services_found);
        emptyView.setPadding(16, 16, 16, 16);
        emptyView.setTextColor(ContextCompat.getColor(this, R.color.hint));
        apiCardsContainer.addView(emptyView);
    }

    /**
     * Clear search box and results
     */
    private void clearSearch() {
        apiSearchBox.setText("");
        apiCardContainer.clearAll();
    }

    /**
     * Clear current selection
     */
    private void clearSelection() {
        apiCardContainer.clearSelection();
    }

    /**
     * Return selected service to calling activity
     */
    private void returnSelectedService() {
        ApiProvider selectedApiProvider = apiCardContainer.getSelectedApiProvider();

        if (selectedApiProvider == null) {
            Toast.makeText(this, R.string.no_api_service_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        if (oldProviderId != null && oldProviderId.equals(selectedApiProvider.getId())) {
            Toast.makeText(this, R.string.selected_service_is_current, Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure urls map is set if apiUrl is available
        if (selectedApiProvider.getHome() == null && selectedApiProvider.getApiUrl() != null) {
            HashMap<String, String> urlMap = new HashMap<>();
            urlMap.put(API, selectedApiProvider.getApiUrl());
            selectedApiProvider.setHome(urlMap);
        }

        Intent resultIntent = new Intent();
        // Serialize the ApiProvider (which is now a Service)
        String json = selectedApiProvider.toJson();
        resultIntent.putExtra(EXTRA_SELECTED_SERVICE, json);
        resultIntent.putExtra(EXTRA_SERVICE_TYPE, serviceType.name());
        resultIntent.putExtra(EXTRA_OLD_PROVIDER_ID, oldProviderId);
        setResult(RESULT_OK, resultIntent);
        finish();
    }
}
