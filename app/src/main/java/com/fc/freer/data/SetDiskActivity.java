package com.fc.freer.data;

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

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.FapiCode;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCardContainer;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Activity for searching and selecting a DISK service.
 * Searches for FAPI services that have "disk" component.
 * Returns the selected service so the caller can set it as default DISK.
 */
public class SetDiskActivity extends BaseCryptoActivity {
    private static final String TAG = "SetDiskActivity";

    public static final String EXTRA_SELECTED_SERVICE = "selected_service";
    /** Optional: the service component to filter by (e.g. DISK or DOCK). Defaults to DISK. */
    public static final String EXTRA_COMPONENT = "component";
    /** Optional: toolbar title to show (e.g. "Select data server"). Defaults to "Set DISK". */
    public static final String EXTRA_TITLE = "title";
    /** Optional: a second component the service must also run (ROAD needs its MAP). */
    public static final String EXTRA_ALSO_COMPONENT = "alsoComponent";

    /** The service component this picker filters by; DISK unless overridden via {@link #EXTRA_COMPONENT}. */
    private String component() {
        String c = getIntent() != null ? getIntent().getStringExtra(EXTRA_COMPONENT) : null;
        return (c != null && !c.isEmpty()) ? c : Constants.DISK_NO1_NRC7;
    }

    /** Whether the service runs the component, and the second one if one is asked for. */
    private boolean runsComponents(java.util.List<String> components) {
        if (components == null) return false;
        String also = getIntent() != null ? getIntent().getStringExtra(EXTRA_ALSO_COMPONENT) : null;
        return components.stream().anyMatch(c -> component().equalsIgnoreCase(c))
                && (also == null || components.stream().anyMatch(also::equalsIgnoreCase));
    }

    private boolean isDiskComponent() {
        return Constants.DISK_NO1_NRC7.equalsIgnoreCase(component());
    }

    /** Short display label for the current component, e.g. "DISK" or "DOCK". */
    private String componentLabel() {
        return component().split("@")[0];
    }

    private LinearLayout apiCardsContainer;
    private android.widget.ScrollView scrollView;
    private TextView currentDiskText;
    private EditText apiSearchBox;
    private ImageButton searchButton;
    private ImageButton clearButton;
    private ImageButton doneButton;

    private LinearLayout timeSortButton;
    private ImageView timeSortIcon;
    private LinearLayout rateSortButton;
    private ImageView rateSortIcon;
    private LinearLayout cddSortButton;
    private ImageView cddSortIcon;

    private ApiCardContainer apiCardContainer;
    private List<ApiProvider> apiProviderList;
    private WaitingDialog waitingDialog;

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
        String title = getIntent() != null ? getIntent().getStringExtra(EXTRA_TITLE) : null;
        return (title != null && !title.isEmpty()) ? title : getString(R.string.set_disk);
    }

    @Override
    protected void initializeViews() {
        apiCardsContainer = findViewById(R.id.api_cards_container);
        scrollView = findViewById(R.id.scroll_view);
        currentDiskText = findViewById(R.id.current_disk_text);
        apiSearchBox = findViewById(R.id.api_search_box);
        searchButton = findViewById(R.id.do_button);
        clearButton = findViewById(R.id.clear_button);
        doneButton = findViewById(R.id.done_button);

        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortIcon = findViewById(R.id.time_sort_icon);
        rateSortButton = findViewById(R.id.rate_sort_button);
        rateSortIcon = findViewById(R.id.rate_sort_icon);
        cddSortButton = findViewById(R.id.cdd_sort_button);
        cddSortIcon = findViewById(R.id.cdd_sort_icon);

        apiCardContainer = new ApiCardContainer(this, apiCardsContainer, true);
        setupScrollListener();
    }

    @Override
    protected void setupButtons() {
        searchButton.setOnClickListener(v -> doSearch());
        clearButton.setOnClickListener(v -> apiCardContainer.clearSelection());
        doneButton.setOnClickListener(v -> returnSelectedService());

        timeSortButton.setOnClickListener(v -> handleSortClick(SortField.TIME));
        rateSortButton.setOnClickListener(v -> handleSortClick(SortField.RATE));
        cddSortButton.setOnClickListener(v -> handleSortClick(SortField.CDD));
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        // Show the currently configured DISK service (sid + url)
        loadCurrentDiskInfo();
        // Auto-search on open
        doSearch();
    }

    /**
     * Resolve and display the currently configured DISK service (from the live FID's
     * {@code home.DISK}): its service SID and the resolved API URL.
     */
    private void loadCurrentDiskInfo() {
        // The "current" display is DISK-specific (decrypts home.DISK); hide it for other components.
        if (!isDiskComponent()) {
            currentDiskText.setVisibility(android.view.View.GONE);
            return;
        }
        new Thread(() -> {
            com.fc.fc_ajdk.data.fcData.KeyInfo liveKeyInfo =
                    FidManager.getInstance() != null ? FidManager.getInstance().getLiveKeyInfo() : null;
            if (liveKeyInfo == null || !DiskHomeManager.isConfigured(liveKeyInfo)) {
                runOnUiThread(this::showNoCurrentDisk);
                return;
            }

            byte[] prikey = com.fc.freer.utils.SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
            String sid = DiskHomeManager.resolveSid(liveKeyInfo, prikey);
            if (sid == null) {
                runOnUiThread(this::showNoCurrentDisk);
                return;
            }

            // URL: prefer the already-cached DISK client, otherwise resolve & connect it.
            FapiClient diskClient = ApiCenter.getInstance().getCachedClient(ApiCenter.ConnectionRole.DISK);
            if (diskClient == null) {
                diskClient = DiskHomeManager.resolveAndCacheDiskClient(liveKeyInfo, prikey);
            }
            String url = diskClient != null ? diskClient.getServerUrl() : null;

            final String fSid = sid;
            final String fUrl = url != null ? url : "-";
            runOnUiThread(() -> {
                currentDiskText.setText(getString(R.string.current_disk_info, fSid, fUrl));
                currentDiskText.setVisibility(android.view.View.VISIBLE);
            });
        }).start();
    }

    private void showNoCurrentDisk() {
        currentDiskText.setText(R.string.current_disk_none);
        currentDiskText.setVisibility(android.view.View.VISIBLE);
    }

    private void setupScrollListener() {
        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (scrollView.getChildAt(0) == null) return;
            int scrollY = scrollView.getScrollY();
            int scrollViewHeight = scrollView.getHeight();
            int childHeight = scrollView.getChildAt(0).getHeight();
            if (scrollY + scrollViewHeight >= childHeight - 20) {
                loadMoreIfAvailable();
            }
        });
    }

    private void loadMoreIfAvailable() {
        if (apiCardContainer.getCardCount() >= FreerApplication.MAX_CONTAINER_SIZE) {
            if (hasMore) {
                hasMore = false;
                Toast.makeText(this, R.string.max_api_services_reached, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (hasMore && last != null && !isLoadingMore) {
            isLoadingMore = true;
            loadMore();
        }
    }

    private void loadMore() {
        new Thread(() -> {
            String searchQuery = apiSearchBox.getText().toString().trim();
            if (searchQuery.toLowerCase().startsWith("fudp://")) {
                runOnUiThread(() -> isLoadingMore = false);
                return;
            }
            List<ApiProvider> results = new ArrayList<>();
            searchDiskServices(searchQuery, results);
            runOnUiThread(() -> {
                if (!results.isEmpty()) {
                    apiCardContainer.addAllApiCard(results);
                    Toast.makeText(this, getString(R.string.loaded_more_services, results.size()), Toast.LENGTH_SHORT).show();
                }
                isLoadingMore = false;
            });
        }).start();
    }

    private void doSearch() {
        last = null;
        hasMore = true;

        runOnUiThread(() -> {
            waitingDialog = new WaitingDialog(this, getString(R.string.searching_services, componentLabel()));
            waitingDialog.show();
        });

        new Thread(() -> {
            String searchQuery = apiSearchBox.getText().toString().trim();
            List<ApiProvider> results = new ArrayList<>();

            try {
                if (searchQuery.toLowerCase().startsWith("fudp://")) {
                    discoverFromUrl(searchQuery, results);
                } else {
                    searchDiskServices(searchQuery, results);
                }

                runOnUiThread(() -> {
                    apiCardContainer.clearAll();
                    apiProviderList = results;
                    if (!apiProviderList.isEmpty()) {
                        apiCardContainer.addAllApiCard(apiProviderList);
                        apiCardContainer.selectCardAtPosition(0);
                        Toast.makeText(this, getString(R.string.got_api_services, apiProviderList.size()), Toast.LENGTH_SHORT).show();
                    } else {
                        showEmptyMessage();
                        Toast.makeText(this, R.string.no_disk_services_found, Toast.LENGTH_SHORT).show();
                    }
                    dismissWaiting();
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error searching DISK services: %s", e.getMessage());
                runOnUiThread(() -> {
                    ToastUtils.makeText(this, getString(R.string.error_searching_api_services, e.getMessage()));
                    dismissWaiting();
                });
            }
        }).start();
    }

    private void discoverFromUrl(String url, List<ApiProvider> results) {
        Setting setting = ApiCenter.getInstance().getCurrentSetting();
        if (setting == null) {
            runOnUiThread(() -> {
                ToastUtils.makeText(this, getString(R.string.error_no_setting));
                dismissWaiting();
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
                dismissWaiting();
            });
            return;
        }

        ApiProvider apiProvider = FapiClient.getApiProviderFromUrl(fudpNode, url, Service.ServiceType.FAPI_No1_NrC7);
        if (apiProvider != null) {
            // Verify it has the requested component
            if (runsComponents(apiProvider.getComponents())) {
                apiProvider.setApiUrl(url);
                results.add(apiProvider);
            } else {
                runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.service_has_no_disk)));
            }
        } else {
            runOnUiThread(() -> ToastUtils.makeText(this, getString(R.string.error_discover_service)));
        }
    }

    private void searchDiskServices(String searchQuery, List<ApiProvider> results) {
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if (fapiClient == null) return;

        Fcdsl fcdsl = buildDiskSearchFcdsl(searchQuery, last);
        FapiResponse response = fapiClient.search(fcdsl);
        if (response == null || response.getCode() != 0) return;

        Object obj = response.getData();
        if (obj == null) return;

        List<Service> serviceList = ObjectUtils.objectToList(obj, Service.class);
        if (serviceList == null) return;

        for (Service service : serviceList) {
            if (service == null) continue;
            // Filter: must have the requested component
            if (!runsComponents(service.getComponents())) {
                continue;
            }
            ApiProvider apiProvider = ApiProvider.fromService(service, Service.ServiceType.FAPI_No1_NrC7);
            if (apiProvider != null) {
                apiProvider.makeServiceType(Service.ServiceType.FAPI_No1_NrC7);
                results.add(apiProvider);
            }
        }

        last = response.getLast();
        if (serviceList.size() < FreerApplication.DEFAULT_REQUEST_SIZE) {
            last = null;
            hasMore = false;
        }
    }

    private Fcdsl buildDiskSearchFcdsl(String searchQuery, List<String> after) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(IndicesNames.SERVICE);
        fcdsl.addNewQuery();
        // No `type` clause: it is free text the publisher types, stored as a
        // keyword, so requiring FAPI@No1_NrC7 hid servers typed any other way.
        // The component filter below already means an FC service.
        fcdsl.getQuery().addNewEquals().addNewFields(FieldNames.ACTIVE).addNewValues(TRUE);

        // Filter for services with the requested component
        fcdsl.addNewFilter().addNewTerms().addNewFields(FieldNames.COMPONENTS).addNewValues(component());

        if (searchQuery != null && !searchQuery.isEmpty()) {
            fcdsl.getFilter().addNewMatch().addNewFields(
                    FieldNames.STD_NAME, FieldNames.LOCAL_NAMES, ID,
                    FieldNames.OWNER, FieldNames.DESC).addNewValue(searchQuery);
        }

        fcdsl.setSize(String.valueOf(FreerApplication.DEFAULT_REQUEST_SIZE));

        if (currentSortField == SortField.DEFAULT || currentSortOrder == SortOrder.NONE) {
            fcdsl.addSort(FieldNames.LAST_HEIGHT, DESC).addSort(ID, DESC);
        } else {
            String sortOrder = currentSortOrder == SortOrder.DESC ? DESC : ASC;
            String sortFieldName = switch (currentSortField) {
                case TIME -> FieldNames.BIRTH_TIME;
                case RATE -> FieldNames.T_RATE;
                case CDD -> FieldNames.T_CDD;
                default -> FieldNames.LAST_HEIGHT;
            };
            fcdsl.addSort(sortFieldName, sortOrder).addSort(ID, sortOrder);
        }

        if (after != null && !after.isEmpty()) {
            fcdsl.setAfter(after);
        }
        return fcdsl;
    }

    private void handleSortClick(SortField field) {
        last = null;
        hasMore = true;
        if (currentSortField == field) {
            currentSortOrder = getNextSortOrder(currentSortOrder);
            if (currentSortOrder == SortOrder.NONE) {
                currentSortField = SortField.DEFAULT;
            }
        } else {
            currentSortField = field;
            currentSortOrder = SortOrder.DESC;
        }
        updateSortIcons();
        if (apiCardContainer.getCardCount() > 0) {
            sortLocalCards();
        } else {
            doSearch();
        }
    }

    private SortOrder getNextSortOrder(SortOrder current) {
        return switch (current) {
            case NONE -> SortOrder.DESC;
            case DESC -> SortOrder.ASC;
            default -> SortOrder.NONE;
        };
    }

    private void updateSortIcons() {
        timeSortIcon.setImageResource(R.drawable.ic_sort_none);
        rateSortIcon.setImageResource(R.drawable.ic_sort_none);
        cddSortIcon.setImageResource(R.drawable.ic_sort_none);

        ImageView activeIcon = switch (currentSortField) {
            case TIME -> timeSortIcon;
            case RATE -> rateSortIcon;
            case CDD -> cddSortIcon;
            default -> null;
        };

        if (activeIcon != null) {
            activeIcon.setImageResource(currentSortOrder == SortOrder.DESC
                    ? R.drawable.ic_sort_desc : R.drawable.ic_sort_asc);
        }
    }

    private void sortLocalCards() {
        Comparator<ApiProvider> comparator = getComparator();
        if (comparator != null) {
            apiCardContainer.sortCards(comparator);
        }
    }

    private Comparator<ApiProvider> getComparator() {
        if (currentSortField == SortField.DEFAULT || currentSortOrder == SortOrder.NONE) {
            return (p1, p2) -> {
                Long h1 = p1.getLastHeight();
                Long h2 = p2.getLastHeight();
                if (h1 == null && h2 == null) return 0;
                if (h1 == null) return 1;
                if (h2 == null) return -1;
                return h2.compareTo(h1);
            };
        }

        return switch (currentSortField) {
            case TIME -> (p1, p2) -> {
                Long t1 = p1.getBirthTime();
                Long t2 = p2.getBirthTime();
                if (t1 == null && t2 == null) return 0;
                if (t1 == null) return 1;
                if (t2 == null) return -1;
                return currentSortOrder == SortOrder.DESC ? t2.compareTo(t1) : t1.compareTo(t2);
            };
            case RATE -> (p1, p2) -> {
                Float r1 = p1.gettRate();
                Float r2 = p2.gettRate();
                if (r1 == null && r2 == null) return 0;
                if (r1 == null) return 1;
                if (r2 == null) return -1;
                return currentSortOrder == SortOrder.DESC ? r2.compareTo(r1) : r1.compareTo(r2);
            };
            case CDD -> (p1, p2) -> {
                Long c1 = p1.gettCdd();
                Long c2 = p2.gettCdd();
                if (c1 == null && c2 == null) return 0;
                if (c1 == null) return 1;
                if (c2 == null) return -1;
                return currentSortOrder == SortOrder.DESC ? c2.compareTo(c1) : c1.compareTo(c2);
            };
            default -> null;
        };
    }

    private void returnSelectedService() {
        ApiProvider selected = apiCardContainer.getSelectedApiProvider();
        if (selected == null) {
            Toast.makeText(this, R.string.no_api_service_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        // Ensure home map has API url
        if (selected.getHome() == null && selected.getApiUrl() != null) {
            HashMap<String, String> urlMap = new HashMap<>();
            urlMap.put(API, selected.getApiUrl());
            selected.setHome(urlMap);
        }

        Intent resultIntent = new Intent();
        resultIntent.putExtra(EXTRA_SELECTED_SERVICE, selected.toJson());
        setResult(RESULT_OK, resultIntent);
        finish();
    }

    private void showEmptyMessage() {
        TextView emptyView = new TextView(this);
        emptyView.setText(R.string.no_disk_services_found);
        emptyView.setPadding(16, 16, 16, 16);
        emptyView.setTextColor(ContextCompat.getColor(this, R.color.hint));
        apiCardsContainer.addView(emptyView);
    }

    private void dismissWaiting() {
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
    }
}
