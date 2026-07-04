package com.fc.freer.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.HatCardContainer;
import com.fc.freer.utils.KeyboardUtils;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.utils.ToastUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity for managing local data (HATs) and FAPI.DISK synchronization.
 * Provides:
 * - HAT list display sorted by last access time
 * - CRUD operations for HATs
 * - Upload/download to FAPI.DISK with encryption
 * - File viewing and editing
 * - Search functionality
 */
public class DataActivity extends BaseCryptoActivity {
    private static final String TAG = "DataActivity";
    private static final int PAGE_SIZE = Math.min(FreerApplication.DEFAULT_PAGE_SIZE, FreerApplication.MAX_CONTAINER_SIZE);

    // Core components
    private HatCardContainer hatCardContainer;
    private HatManager hatManager;
    private DataSyncManager dataSyncManager;

    // UI Elements
    private SwipeRefreshLayout swipeRefreshLayout;
    private ScrollView dataScrollView;
    private LinearLayout fragmentContainer;
    private TextView dataStatistics;
    private LinearLayout dataStatisticsContainer;
    private ImageButton loadMoreButton;

    // Search
    private EditText searchEditText;
    private ImageView searchIcon;
    private ImageView clearSearchIcon;

    // Sort buttons
    private LinearLayout timeSortButton, nameSortButton, sizeSortButton;
    private ImageView timeSortIcon, nameSortIcon, sizeSortIcon;
    private TextView timeSortLabel, nameSortLabel, sizeSortLabel;

    // Action buttons
    private ImageButton moreButton;
    private ImageButton deleteButton;
    private ImageButton uploadButton;
    private ImageButton downloadButton;
    private ImageButton addButton;
    private CheckBox selectAllCheckBox;

    // State
    private enum SortType { TIME, NAME, SIZE }
    private enum SortState { NONE, ASCENDING, DESCENDING }

    private SortType currentSortType = SortType.TIME;
    private SortState currentSortState = SortState.NONE;
    private boolean isSearchMode = false;
    private String currentSearchQuery = "";
    private final List<Hat> hatList = new ArrayList<>();
    private String lastId = null;
    private boolean hasMoreData = true;

    // Transfer progress bar
    private LinearLayout transferProgressContainer;
    private TextView transferProgressText;
    private ProgressBar transferProgressBar;
    private ImageButton transferCancelButton;
    private boolean isTransferring = false;
    private boolean isUploadTransfer = false;
    private boolean needsRefreshOnResume = false;
    private HatFileOpener hatFileOpener;

    private final BroadcastReceiver transferReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            TimberLogger.i(TAG, "DEBUG transferReceiver action=%s", action);

            switch (action) {
                case UploadService.ACTION_PROGRESS:
                case DownloadService.ACTION_PROGRESS:
                    int progress = intent.getIntExtra(
                            action.equals(UploadService.ACTION_PROGRESS)
                                    ? UploadService.EXTRA_PROGRESS
                                    : DownloadService.EXTRA_PROGRESS, 0);
                    onTransferProgress(progress);
                    break;
                case UploadService.ACTION_COMPLETE:
                case DownloadService.ACTION_COMPLETE:
                    onTransferComplete(intent, action.equals(UploadService.ACTION_COMPLETE));
                    break;
            }
        }
    };

    // Activity result launchers
    private ActivityResultLauncher<Intent> addFileLauncher;
    private ActivityResultLauncher<Intent> detailActivityLauncher;
    private ActivityResultLauncher<Intent> textEditorLauncher;
    private ActivityResultLauncher<Intent> uploadLauncher;
    private ActivityResultLauncher<Intent> downloadLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        initializeActivityResultLaunchers();
        initializeManagers();
        super.onCreate(savedInstanceState);
        // Session lost (process death while backgrounded): base is redirecting to re-auth.
        if (isSessionRedirected()) return;
        setupListeners();
        registerTransferReceiver();
        loadData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If a transfer just completed while we were in the background, refresh to show updated icons
        if (needsRefreshOnResume) {
            TimberLogger.i(TAG, "DEBUG onResume refreshing data");
            needsRefreshOnResume = false;
            refreshData();
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(transferReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_data;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.data);
    }

    private void initializeActivityResultLaunchers() {
        addFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        List<Uri> uris = new ArrayList<>();

                        // Multiple files selected
                        if (data.getClipData() != null) {
                            android.content.ClipData clipData = data.getClipData();
                            for (int i = 0; i < clipData.getItemCount(); i++) {
                                Uri uri = clipData.getItemAt(i).getUri();
                                if (uri != null) {
                                    uris.add(uri);
                                }
                            }
                        }
                        // Single file selected
                        else if (data.getData() != null) {
                            uris.add(data.getData());
                        }

                        for (Uri uri : uris) {
                            handleFileSelected(uri);
                        }
                    }
                }
        );

        detailActivityLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        refreshData();
                    }
                }
        );

        textEditorLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        refreshData();
                    }
                }
        );

        uploadLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        showTransferProgress(true);
                        needsRefreshOnResume = true;
                    }
                }
        );

        downloadLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        showTransferProgress(false);
                        needsRefreshOnResume = true;
                    }
                }
        );

    }

    private void initializeManagers() {
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            String mainFid = fidManager != null ? fidManager.getMainFid() : null;

            if (fidManager == null || liveFid == null) {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize managers");
                ToastUtils.showError(this, getString(R.string.error_no_live_fid));
                finish();
                return;
            }

            // The Data feature encrypts/decrypts with the FID's private key, which is only
            // available for the main FID. Disable Data when the live FID is not the main FID.
            if (!liveFid.equals(mainFid)) {
                TimberLogger.w(TAG, "Data disabled: liveFid != mainFid");
                ToastUtils.showError(this, getString(R.string.data_requires_main_fid));
                finish();
                return;
            }

            hatManager = HatManager.getInstance(this, liveFid);
            dataSyncManager = new DataSyncManager(this, hatManager);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing managers: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.initialization_failed));
            finish();
        }
    }

    @Override
    protected void initializeViews() {
        // Main views
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        dataScrollView = findViewById(R.id.data_scroll_view);
        fragmentContainer = findViewById(R.id.fragment_container);
        dataStatistics = findViewById(R.id.data_statistics);
        dataStatisticsContainer = findViewById(R.id.data_statistics_container);
        loadMoreButton = findViewById(R.id.load_more_button);

        // Search
        searchEditText = findViewById(R.id.search_edit_text);
        searchIcon = findViewById(R.id.search_icon);
        clearSearchIcon = findViewById(R.id.clear_search_icon);

        // Sort buttons
        timeSortButton = findViewById(R.id.time_sort_button);
        timeSortIcon = findViewById(R.id.time_sort_icon);
        timeSortLabel = findViewById(R.id.time_sort_label);
        nameSortButton = findViewById(R.id.name_sort_button);
        nameSortIcon = findViewById(R.id.name_sort_icon);
        nameSortLabel = findViewById(R.id.name_sort_label);
        sizeSortButton = findViewById(R.id.size_sort_button);
        sizeSortIcon = findViewById(R.id.size_sort_icon);
        sizeSortLabel = findViewById(R.id.size_sort_label);

        // Transfer progress bar
        transferProgressContainer = findViewById(R.id.transfer_progress_container);
        transferProgressText = findViewById(R.id.transfer_progress_text);
        transferProgressBar = findViewById(R.id.transfer_progress_bar);
        transferCancelButton = findViewById(R.id.transfer_cancel_button);

        // Action buttons
        moreButton = findViewById(R.id.more_button);
        deleteButton = findViewById(R.id.delete_button);
        uploadButton = findViewById(R.id.upload_button);
        downloadButton = findViewById(R.id.download_button);
        addButton = findViewById(R.id.add_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);

        // Initialize card container
        hatCardContainer = new HatCardContainer(this, fragmentContainer);
        hatCardContainer.setAlwaysShowCheckboxes(true);
        hatCardContainer.setClearButtonIcon(R.drawable.ic_play, getString(R.string.open));
        hatCardContainer.setOnHatClickListener(this::onHatClick);
        hatCardContainer.setOnHatLongClickListener(this::onHatLongClick);
        hatCardContainer.setOnHatRemoveListener((hat, position) -> hatFileOpener.open(hat));

        hatFileOpener = new HatFileOpener(this, hatManager);
        hatFileOpener.setTextEditorLauncher(textEditorLauncher);
    }

    @Override
    protected void setupButtons() {
        // Action buttons
        moreButton.setOnClickListener(this::showMoreMenu);
        deleteButton.setOnClickListener(v -> deleteSelectedHats());
        uploadButton.setOnClickListener(v -> launchUploadDataActivity());
        downloadButton.setOnClickListener(v -> launchDownloadDataActivity());
        addButton.setOnClickListener(v -> addNewFile());
        loadMoreButton.setOnClickListener(v -> loadMoreData());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void setupListeners() {
        // Swipe refresh
        swipeRefreshLayout.setOnRefreshListener(this::refreshData);

        // Search
        // Search icon click to perform search
        searchIcon.setOnClickListener(v -> {
            performSearch();
            KeyboardUtils.hideKeyboard(this);
        });

        // Clear icon click to clear search
        clearSearchIcon.setOnClickListener(v -> {
            searchEditText.setText("");
            clearSearchIcon.setVisibility(View.GONE);
            clearSearch();
            KeyboardUtils.hideKeyboard(this);
        });

        // Text change listener to show/hide clear icon
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearSearchIcon.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Search on keyboard action
        searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                performSearch();
                KeyboardUtils.hideKeyboard(this);
                return true;
            }
            return false;
        });

        // Sort buttons
        setupSortButtons();

        // Select all - only respond to user clicks, not programmatic changes
        setupSelectAllCheckBox();

        // Hide keyboard on scroll
        dataScrollView.setOnTouchListener((v, event) -> {
            KeyboardUtils.hideKeyboard(this);
            return false;
        });
    }

    private void loadData() {
        hatList.clear();
        lastId = null;
        hasMoreData = true;
        loadMoreData();
    }

    private void loadMoreData() {
        if (!hasMoreData) return;

        List<Hat> newHats = hatManager.getHatsSortedByLastDesc(PAGE_SIZE, lastId);

        if (newHats.isEmpty()) {
            hasMoreData = false;
        } else {
            hatList.addAll(newHats);
            lastId = newHats.get(newHats.size() - 1).getId();
            hasMoreData = newHats.size() >= PAGE_SIZE;

            updateUI();
        }

        updateStatistics();
        swipeRefreshLayout.setRefreshing(false);
    }

    private void refreshData() {
        hatCardContainer.clear();
        // Reset sort state on refresh
        currentSortState = SortState.NONE;
        currentSortType = SortType.TIME;
        setDefaultSortColors();
        loadData();
    }

    private void updateUI() {
        hatCardContainer.clear();
        hatCardContainer.addHats(hatList);
    }

    private void updateStatistics() {
        long totalCount = hatManager.getHatDBSize();
        int displayedCount = hatList.size();

        dataStatisticsContainer.setVisibility(View.VISIBLE);
        dataStatistics.setText(getString(R.string.showing_of_total, displayedCount, totalCount));
        loadMoreButton.setVisibility(hasMoreData ? View.VISIBLE : View.GONE);
    }

    private void performSearch() {
        String query = searchEditText.getText().toString().trim();
        if (query.isEmpty()) {
            clearSearch();
            return;
        }

        isSearchMode = true;
        currentSearchQuery = query;

        List<Hat> searchResults = hatManager.searchHats(query);
        if (searchResults != null) {
            hatList.clear();
            hatList.addAll(searchResults);
            updateUI();
            updateStatistics();
        }
    }

    private void clearSearch() {
        isSearchMode = false;
        currentSearchQuery = "";
        loadData();
    }

    /**
     * Sets up all sort button listeners following the NewsActivity pattern.
     */
    private void setupSortButtons() {
        if (timeSortButton != null) {
            timeSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.TIME) {
                    currentSortType = SortType.TIME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortHatList();
            });
        }

        if (nameSortButton != null) {
            nameSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.NAME) {
                    currentSortType = SortType.NAME;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortHatList();
            });
        }

        if (sizeSortButton != null) {
            sizeSortButton.setOnClickListener(v -> {
                if (currentSortType != SortType.SIZE) {
                    currentSortType = SortType.SIZE;
                    resetOtherSortIcons();
                }
                cycleSortState();
                updateSortIcons();
                sortHatList();
            });
        }

        setDefaultSortColors();
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
     * Sets default (hint) colors for all sort labels and icons.
     */
    private void setDefaultSortColors() {
        int hintColor = getResources().getColor(R.color.hint, null);

        if (timeSortLabel != null) timeSortLabel.setTextColor(hintColor);
        if (timeSortIcon != null) timeSortIcon.setColorFilter(hintColor);

        if (nameSortLabel != null) nameSortLabel.setTextColor(hintColor);
        if (nameSortIcon != null) nameSortIcon.setColorFilter(hintColor);

        if (sizeSortLabel != null) sizeSortLabel.setTextColor(hintColor);
        if (sizeSortIcon != null) sizeSortIcon.setColorFilter(hintColor);
    }

    /**
     * Resets other sort icons to NONE state when switching sort types.
     */
    private void resetOtherSortIcons() {
        int hintColor = getResources().getColor(R.color.hint, null);
        currentSortState = SortState.NONE;

        if (currentSortType != SortType.TIME) {
            if (timeSortIcon != null) {
                timeSortIcon.setImageResource(R.drawable.ic_sort_none);
                timeSortIcon.setColorFilter(hintColor);
            }
            if (timeSortLabel != null) timeSortLabel.setTextColor(hintColor);
        }

        if (currentSortType != SortType.NAME) {
            if (nameSortIcon != null) {
                nameSortIcon.setImageResource(R.drawable.ic_sort_none);
                nameSortIcon.setColorFilter(hintColor);
            }
            if (nameSortLabel != null) nameSortLabel.setTextColor(hintColor);
        }

        if (currentSortType != SortType.SIZE) {
            if (sizeSortIcon != null) {
                sizeSortIcon.setImageResource(R.drawable.ic_sort_none);
                sizeSortIcon.setColorFilter(hintColor);
            }
            if (sizeSortLabel != null) sizeSortLabel.setTextColor(hintColor);
        }
    }

    /**
     * Updates the sort icons based on current sort state and type.
     */
    private void updateSortIcons() {
        int iconResource;
        switch (currentSortState) {
            case ASCENDING:
                iconResource = R.drawable.ic_sort_asc;
                break;
            case DESCENDING:
                iconResource = R.drawable.ic_sort_desc;
                break;
            case NONE:
            default:
                iconResource = R.drawable.ic_sort_none;
                break;
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

        switch (currentSortType) {
            case TIME:
                if (timeSortIcon != null) {
                    timeSortIcon.setImageResource(iconResource);
                    timeSortIcon.setColorFilter(color);
                }
                if (timeSortLabel != null) timeSortLabel.setTextColor(color);
                break;
            case NAME:
                if (nameSortIcon != null) {
                    nameSortIcon.setImageResource(iconResource);
                    nameSortIcon.setColorFilter(color);
                }
                if (nameSortLabel != null) nameSortLabel.setTextColor(color);
                break;
            case SIZE:
                if (sizeSortIcon != null) {
                    sizeSortIcon.setImageResource(iconResource);
                    sizeSortIcon.setColorFilter(color);
                }
                if (sizeSortLabel != null) sizeSortLabel.setTextColor(color);
                break;
        }
    }

    /**
     * Sorts the hat list using HatCardContainer's in-place sort methods.
     */
    private void sortHatList() {
        if (hatCardContainer == null) return;

        boolean ascending = currentSortState == SortState.ASCENDING;
        boolean enableSort = currentSortState != SortState.NONE;

        switch (currentSortType) {
            case NAME:
                hatCardContainer.sortByName(ascending, enableSort);
                break;
            case SIZE:
                hatCardContainer.sortBySize(ascending, enableSort);
                break;
            case TIME:
                hatCardContainer.sortByTime(ascending, enableSort);
                break;
        }

        // If sort state is NONE, restore default order (descending by time)
        if (currentSortState == SortState.NONE) {
            hatCardContainer.sortByTime(false, true);
        }
    }

    private void onHatClick(Hat hat, int position) {
        openHatDetail(hat);
    }

    private boolean onHatLongClick(Hat hat, int position) {
        enterSelectionMode();
        return true;
    }

    private void enterSelectionMode() {
        hatCardContainer.setChooseMode(ChooseMode.CHOOSE_MULTI);
        deleteButton.setVisibility(View.VISIBLE);
    }

    private void exitSelectionMode() {
        hatCardContainer.setChooseMode(ChooseMode.WITHOUT_CHOOSE);
        hatCardContainer.deselectAll();
        deleteButton.setVisibility(View.GONE);
        updateSelectAllCheckBoxState();
    }

    /**
     * Sets up the select all checkbox listener.
     * Only responds to user clicks, not programmatic changes.
     */
    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    if (isChecked) {
                        hatCardContainer.selectAll();
                    } else {
                        hatCardContainer.deselectAll();
                    }
                }
            });
        }
    }

    /**
     * Updates the select all checkbox state based on current hat selection.
     */
    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && hatCardContainer != null) {
            // Temporarily remove listener to avoid infinite loops
            selectAllCheckBox.setOnCheckedChangeListener(null);

            List<Hat> selected = hatCardContainer.getSelectedHats();
            if (selected != null && !selected.isEmpty() && selected.size() == hatList.size()) {
                selectAllCheckBox.setChecked(true);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            // Re-attach listener
            setupSelectAllCheckBox();
        }
    }

    private void openHatDetail(Hat hat) {
        Intent intent = new Intent(this, DetailActivity.class);
        intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, hat.toJson());
        intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Hat.class.getName());
        detailActivityLauncher.launch(intent);
    }

    private void openFile(Hat hat) {
        hatFileOpener.open(hat);
    }

    private String findLocalPath(Hat hat) {
        List<String> locas = hat.getLocas();
        if (locas == null) return null;

        for (String loca : locas) {
            if (loca != null && loca.startsWith("local://")) {
                return loca.substring("local://".length());
            }
        }

        File defaultFile = new File(new File(getFilesDir(), "data"), hat.getId());
        if (defaultFile.exists()) {
            return defaultFile.getAbsolutePath();
        }
        return null;
    }

    private void addNewFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        addFileLauncher.launch(intent);
    }

    private void handleFileSelected(Uri uri) {
        File dataDir = new File(getFilesDir(), "data");
        dataDir.mkdirs();
        File tempFile = null;
        try {
            String fileName = getFileName(uri);
            String mimeType = getContentResolver().getType(uri);

            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) {
                ToastUtils.showError(this, getString(R.string.file_not_found));
                return;
            }

            // Stream to a temp file (avoids loading huge files into memory)
            tempFile = File.createTempFile("hat_", null, dataDir);
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[64 * 1024]; // 64KB buffer
                int n;
                while ((n = inputStream.read(buffer)) != -1) {
                    fos.write(buffer, 0, n);
                }
            } finally {
                inputStream.close();
            }

            long fileSize = tempFile.length();
            // Compute ID by streaming the file (Hash.sha256x2Bytes streams; no full load)
            byte[] didBytes = Hash.sha256x2Bytes(tempFile);
            if (didBytes == null) {
                ToastUtils.showError(this, getString(R.string.error_adding_file));
                return;
            }
            String did = Hex.toHex(didBytes);

            File localFile = new File(dataDir, did);
            if (!tempFile.renameTo(localFile)) {
                // Fallback: copy then delete (e.g. cross-filesystem)
                try (InputStream in = new FileInputStream(tempFile);
                     FileOutputStream out = new FileOutputStream(localFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
                tempFile.delete();
            }

            Hat hat = new Hat();
            hat.setName(fileName);
            hat.setSize(fileSize);
            hat.setBorn(System.currentTimeMillis());
            hat.setLast(System.currentTimeMillis());
            hat.setState(Hat.DataState.ACTIVE);

            if (mimeType != null) {
                List<String> types = new ArrayList<>();
                types.add(mimeType);
                hat.setTypes(types);
            }

            hat.setId(did);

            List<String> locas = new ArrayList<>();
            locas.add("local://" + localFile.getAbsolutePath());
            hat.setLocas(locas);

            hatManager.addHat(hat);
            hatManager.commit();

            ToastUtils.makeText(this, getString(R.string.file_added_successfully));
            refreshData();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error handling file: " + e.getMessage());
            ToastUtils.showError(this, getString(R.string.error_adding_file));
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    private void deleteSelectedHats() {
        List<Hat> selected = hatCardContainer.getSelectedHats();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_items_selected));
            return;
        }

        // Show confirmation dialog
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete)
                .setMessage(getString(R.string.confirm_delete_items, selected.size()))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    for (Hat hat : selected) {
                        hatManager.removeHat(hat);
                        hatList.remove(hat);
                    }
                    hatManager.commit();
                    exitSelectionMode();
                    updateUI();
                    updateStatistics();
                    ToastUtils.makeText(this, getString(R.string.items_deleted, selected.size()));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteCheckedHatsAndData() {
        List<Hat> selected = hatCardContainer.getSelectedHats();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_checked_hats));
            return;
        }

        // Show warning and confirmation dialog
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.delete_data)
                .setMessage(getString(R.string.confirm_delete_data, selected.size())
                        + "\n\n" + getString(R.string.confirm_delete_data_warning))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    int deletedCount = 0;
                    File dataDir = new File(getFilesDir(), "data");
                    for (Hat hat : selected) {
                        // Delete local file if exists
                        List<String> locas = hat.getLocas();
                        if (locas != null) {
                            for (String loca : locas) {
                                if (loca != null && loca.startsWith("local://")) {
                                    String filePath = loca.substring("local://".length());
                                    File localFile = new File(filePath);
                                    if (localFile.exists()) {
                                        localFile.delete();
                                    }
                                }
                            }
                        }
                        // Also try the default data dir location
                        if (hat.getId() != null) {
                            File defaultFile = new File(dataDir, hat.getId());
                            if (defaultFile.exists()) {
                                defaultFile.delete();
                            }
                        }
                        // Remove HAT from database
                        hatManager.removeHat(hat);
                        hatList.remove(hat);
                        deletedCount++;
                    }
                    hatManager.commit();
                    updateUI();
                    updateStatistics();
                    ToastUtils.makeText(this, getString(R.string.data_deleted_successfully, deletedCount));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void removeLocalDataFromSelectedHats() {
        List<Hat> selected = hatCardContainer.getSelectedHats();
        if (selected.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_checked_hats));
            return;
        }

        // Filter to only HATs that have local:// in locas
        List<Hat> hatsWithLocalData = new ArrayList<>();
        for (Hat hat : selected) {
            List<String> locas = hat.getLocas();
            if (locas != null) {
                for (String loca : locas) {
                    if (loca != null && loca.startsWith("local://")) {
                        hatsWithLocalData.add(hat);
                        break;
                    }
                }
            }
        }

        if (hatsWithLocalData.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_hats_with_local_data));
            return;
        }

        // Show confirmation dialog
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.remove_local_data)
                .setMessage(getString(R.string.confirm_remove_local_data, hatsWithLocalData.size())
                        + "\n\n" + getString(R.string.confirm_remove_local_data_warning))
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    int removedCount = 0;
                    for (Hat hat : hatsWithLocalData) {
                        List<String> locas = hat.getLocas();
                        if (locas == null) continue;

                        // Delete local files and collect non-local locas
                        List<String> updatedLocas = new ArrayList<>();
                        for (String loca : locas) {
                            if (loca != null && loca.startsWith("local://")) {
                                String filePath = loca.substring("local://".length());
                                File localFile = new File(filePath);
                                if (localFile.exists()) {
                                    localFile.delete();
                                }
                            } else {
                                updatedLocas.add(loca);
                            }
                        }

                        // Update the HAT's locas
                        hat.setLocas(updatedLocas);
                        hat.setLast(System.currentTimeMillis());
                        hatManager.updateHat(hat);
                        removedCount++;
                    }
                    hatManager.commit();
                    updateUI();
                    updateStatistics();
                    ToastUtils.makeText(this, getString(R.string.local_data_removed_successfully, removedCount));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchUploadDataActivity() {
        List<Hat> selected = hatCardContainer.getSelectedHats();
        // Filter to only local-only HATs (have local:// but no fudp:// or (sid))
        List<Hat> localOnlyHats = new ArrayList<>();
        File dataDir = new File(getFilesDir(), "data");
        int missingFileCount = 0;
        for (Hat hat : selected) {
            List<String> locas = hat.getLocas();
            boolean hasLocal = false;
            boolean hasDisk = false;
            boolean localFileExists = false;
            if (locas != null) {
                for (String loca : locas) {
                    if (loca != null) {
                        if (loca.startsWith("local://")) {
                            hasLocal = true;
                            String filePath = loca.substring("local://".length());
                            File file = new File(filePath);
                            if (file.exists()) {
                                localFileExists = true;
                            }
                        }
                        if (loca.startsWith("disk://") || loca.startsWith("fudp://") || loca.startsWith("(sid)")) hasDisk = true;
                    }
                }
            }
            // Also check default data dir as fallback
            if (!localFileExists && hat.getId() != null) {
                File defaultFile = new File(dataDir, hat.getId());
                if (defaultFile.exists()) {
                    localFileExists = true;
                    // Fix the locas to include the correct local path
                    if (locas == null) {
                        locas = new ArrayList<>();
                    }
                    String localPath = "local://" + defaultFile.getAbsolutePath();
                    if (!locas.contains(localPath)) {
                        locas.add(localPath);
                        hat.setLocas(locas);
                        hatManager.updateHat(hat);
                        hatManager.commit();
                    }
                    hasLocal = true;
                }
            }
            // Also check cipherIds for disk presence
            if (!hasDisk && hat.getCipherIds() != null && !hat.getCipherIds().isEmpty()) {
                hasDisk = true;
            }
            if (hasLocal && localFileExists && !hasDisk) {
                localOnlyHats.add(hat);
            } else if (hasLocal && !localFileExists && !hasDisk) {
                missingFileCount++;
                TimberLogger.w(TAG, "Local file not found for HAT: %s, locas: %s", hat.getId(),
                        hat.getLocas() != null ? hat.getLocas().toString() : "null");
            }
        }

        if (missingFileCount > 0) {
            ToastUtils.showWarning(this, getString(R.string.local_files_not_found, missingFileCount));
        }

        if (localOnlyHats.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_local_only_hats_checked));
            return;
        }

        // Pass local-only HAT IDs to UploadDataActivity
        ArrayList<String> hatIds = new ArrayList<>();
        for (Hat hat : localOnlyHats) {
            hatIds.add(hat.getId());
        }
        Intent intent = new Intent(this, UploadDataActivity.class);
        intent.putStringArrayListExtra(UploadDataActivity.EXTRA_HAT_IDS, hatIds);
        uploadLauncher.launch(intent);
    }

    private void launchDownloadDataActivity() {
        List<Hat> selected = hatCardContainer.getSelectedHats();
        // Filter to HATs that:
        // 1. locas do NOT contain "local://"
        // 2. cipherIds has value, OR locas contains "fudp://" or "(sid)"
        List<Hat> downloadableHats = new ArrayList<>();
        for (Hat hat : selected) {
            List<String> locas = hat.getLocas();
            boolean hasLocal = false;
            boolean hasDiskLocation = false;

            if (locas != null) {
                for (String loca : locas) {
                    if (loca != null) {
                        if (loca.contains("local://")) hasLocal = true;
                        if (loca.contains("fudp://") || loca.contains("(sid)")) hasDiskLocation = true;
                    }
                }
            }

            boolean hasCipherIds = hat.getCipherIds() != null && !hat.getCipherIds().isEmpty();

            if (!hasLocal && (hasCipherIds || hasDiskLocation)) {
                downloadableHats.add(hat);
            }
        }

        if (downloadableHats.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_downloadable_hats_checked));
            return;
        }

        // Pass downloadable HAT IDs to DownloadDataActivity
        ArrayList<String> hatIds = new ArrayList<>();
        for (Hat hat : downloadableHats) {
            hatIds.add(hat.getId());
        }
        Intent intent = new Intent(this, DownloadDataActivity.class);
        intent.putStringArrayListExtra(DownloadDataActivity.EXTRA_HAT_IDS, hatIds);
        downloadLauncher.launch(intent);
    }

    private void showMoreMenu(View anchor) {
        KeyboardUtils.hideKeyboard(this);
        View popupView = LayoutInflater.from(this).inflate(R.layout.popup_data_menu, null);
        PopupWindow popupWindow = new PopupWindow(popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true);

        // Configure popup window - these settings are required for the popup to display properly
        popupWindow.setElevation(10f);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(getResources().getColor(R.color.background_light, null)));

        popupView.findViewById(R.id.menu_import).setOnClickListener(v -> {
            popupWindow.dismiss();
            importHats();
        });

        popupView.findViewById(R.id.menu_export).setOnClickListener(v -> {
            popupWindow.dismiss();
            exportHats();
        });

        popupView.findViewById(R.id.menu_remove_local_data).setOnClickListener(v -> {
            popupWindow.dismiss();
            removeLocalDataFromSelectedHats();
        });

        popupView.findViewById(R.id.menu_delete_data).setOnClickListener(v -> {
            popupWindow.dismiss();
            deleteCheckedHatsAndData();
        });

        // Measure the popup view to get its height
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupHeight = popupView.getMeasuredHeight();

        // Show the popup above the anchor view to prevent it from being cut off at the bottom
        popupWindow.showAsDropDown(anchor, 0, -anchor.getHeight() - popupHeight);
    }

    private void importHats() {
        Intent intent = new Intent(this, ImportHatActivity.class);
        detailActivityLauncher.launch(intent);
    }

    private void exportHats() {
        if (hatList == null || hatList.isEmpty()) {
            ToastUtils.showWarning(this, getString(R.string.hat_list_is_empty));
            return;
        }
        String hatListJson = JsonUtils.toJson(hatList);
        Intent intent = new Intent(this, ExportHatActivity.class);
        intent.putExtra("hatList", hatListJson);
        detailActivityLauncher.launch(intent);
    }

    @Override
    public void onBackPressed() {
        if (hatCardContainer.getChooseMode() != ChooseMode.WITHOUT_CHOOSE) {
            exitSelectionMode();
        } else if (isSearchMode) {
            clearSearch();
            searchEditText.setText("");
        } else {
            super.onBackPressed();
        }
    }

    // ---- Transfer progress bar ----

    private void registerTransferReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UploadService.ACTION_PROGRESS);
        filter.addAction(UploadService.ACTION_COMPLETE);
        filter.addAction(DownloadService.ACTION_PROGRESS);
        filter.addAction(DownloadService.ACTION_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transferReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(transferReceiver, filter);
        }
    }

    private void showTransferProgress(boolean isUpload) {
        isTransferring = true;
        isUploadTransfer = isUpload;
        transferProgressContainer.setVisibility(View.VISIBLE);
        transferProgressBar.setProgress(0);
        transferProgressText.setText(getString(R.string.transfer_starting));
        transferCancelButton.setOnClickListener(v -> cancelTransfer());
    }

    private void onTransferProgress(int progress) {
        if (!isTransferring) {
            isTransferring = true;
            transferProgressContainer.setVisibility(View.VISIBLE);
        }
        transferProgressBar.setProgress(progress);
        String text = isUploadTransfer
                ? getString(R.string.uploading_data)
                : getString(R.string.downloading_data);
        transferProgressText.setText(text + " " + progress + "%");
    }

    private void onTransferComplete(Intent intent, boolean isUpload) {
        TimberLogger.i(TAG, "DEBUG onTransferComplete, calling refreshData");
        isTransferring = false;
        transferProgressContainer.setVisibility(View.GONE);
        needsRefreshOnResume = true;

        int total = intent.getIntExtra(isUpload ? UploadService.EXTRA_TOTAL : DownloadService.EXTRA_TOTAL, 0);
        int failed = intent.getIntExtra(isUpload ? UploadService.EXTRA_FAILED : DownloadService.EXTRA_FAILED, 0);
        if (failed > 0) {
            if (isUpload) {
                int uploaded = intent.getIntExtra(UploadService.EXTRA_UPLOADED, 0);
                int skipped = intent.getIntExtra(UploadService.EXTRA_SKIPPED, 0);
                ToastUtils.showError(this, getString(R.string.transfer_complete_upload, uploaded, skipped, failed));
            } else {
                int downloaded = intent.getIntExtra(DownloadService.EXTRA_DOWNLOADED, 0);
                ToastUtils.showError(this, getString(R.string.transfer_complete_download, downloaded, failed));
            }
        } else if (total > 0) {
            if (isUpload) {
                int uploaded = intent.getIntExtra(UploadService.EXTRA_UPLOADED, 0);
                int skipped = intent.getIntExtra(UploadService.EXTRA_SKIPPED, 0);
                ToastUtils.showInfo(this, getString(R.string.transfer_complete_upload, uploaded, skipped, 0));
            } else {
                int downloaded = intent.getIntExtra(DownloadService.EXTRA_DOWNLOADED, 0);
                ToastUtils.showInfo(this, getString(R.string.transfer_complete_download, downloaded, 0));
            }
        }

        refreshData();
    }

    private void cancelTransfer() {
        String action = isUploadTransfer
                ? UploadService.ACTION_CANCEL
                : DownloadService.ACTION_CANCEL;
        Intent cancelIntent = new Intent(action);
        cancelIntent.setPackage(getPackageName());
        sendBroadcast(cancelIntent);
        isTransferring = false;
        transferProgressContainer.setVisibility(View.GONE);
        ToastUtils.makeText(this, getString(R.string.transfer_cancelled));
    }
}
