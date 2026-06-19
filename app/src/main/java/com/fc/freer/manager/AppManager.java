package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.STD_NAME;
import static com.fc.fc_ajdk.constants.IndicesNames.APP;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.FALSE;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A singleton class to manage and share the App database across activities.
 * This provides a centralized way to access the App database from any activity.
 */
public class AppManager extends FcManager<App> {
    private static final String TAG = "AppManager";
    public static final int MAX_PAGES = 200;

    private static AppManager instance;

    private AppManager() {
        super(App.class, APP);
    }

    /**
     * Gets the singleton instance of AppManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The AppManager instance
     */
    public static synchronized AppManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new AppManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized AppManager getInstance() {
        return instance;
    }

    /**
     * Gets an App object by its ID.
     *
     * @param id The ID of the App object to get
     * @return The App object, or null if not found
     */
    public App getAppById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds an App object to the database.
     *
     * @param app The App object to add
     */
    public void addApp(App app) {
        addEntity(app);
    }

    /**
     * Removes an App object from the database.
     *
     * @param app The App object to remove
     */
    public void removeApp(App app) {
        removeEntity(app);
    }

    /**
     * Removes multiple App objects from the database.
     *
     * @param apps The list of App objects to remove
     */
    public void removeApps(List<App> apps) {
        removeEntities(apps);
    }

    /**
     * Updates an existing App in the database.
     * @param app The App object to update
     */
    public void updateApp(App app) {
        updateEntity(app);
    }

    /**
     * Gets a paginated list of App objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of App objects for the requested page
     */
    public List<App> getPaginatedApps(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    @Override
    protected void preprocessDB() {
        // No special preprocessing needed for App database
    }

    @Override
    protected void markEntityAsNew(App entity) {
        // No special marking needed for new App entities
    }

    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(APP);
        fcdsl.addNewQuery();

        if (active != null) {
            if (active)
                fcdsl.getQuery().addNewTerms().addNewFields(ACTIVE).addNewValues(TRUE);
            else
                fcdsl.getQuery().addNewTerms().addNewFields(ACTIVE).addNewValues(FALSE);
        }

        if (timeAsc) {
            fcdsl.addSort(LAST_HEIGHT, ASC).addSort(ID, ASC);
        } else {
            fcdsl.addSort(LAST_HEIGHT, DESC).addSort(ID, DESC);
        }

        fcdsl.addSize(pageSize);

        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }
        return fcdsl;
    }

    /**
     * Makes an FCDSL query for apps owned by the liveFid.
     */
    public Fcdsl makeFcdslForOwner(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = makeFcdsl(pageSize, after, active, timeAsc);
        fcdsl.addNewFilter().addNewTerms().addNewFields(OWNER).addNewValues(liveFid);
        return fcdsl;
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<App> entityList, int index, int savedCount) {
        processAppSequentially(activity, entityList, index, savedCount);
    }

    private void processAppSequentially(Activity activity, List<App> appList, int index, int savedCount) {
        if (index >= appList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        App app = appList.get(index);

        if (checkIfExisted(app.getId())) {
            String prompt = app.getStdName() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(app);
                    processAppSequentially(activity, appList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processAppSequentially(activity, appList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(app);
            processAppSequentially(activity, appList, index + 1, savedCount + 1);
        }
    }

    public long getAppDBSize() {
        return getEntityDBSize();
    }

    /**
     * Fetches all apps from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active apps
     * @param timeAsc Sort in time ascending order
     * @return List of all App objects, or null if failed
     */
    private List<App> fetchApps(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, MAX_PAGES);
        if (result == null) {
            return null;
        }

        List<App> appList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof App app) {
                appList.add(app);
            }
        }
        return appList;
    }

    public List<App> fetchPageApps(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<App> appList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof App app) {
                appList.add(app);
                app.setOnChain(true);
            }
        }

        return appList;
    }

    /**
     * Converts App objects (no decryption needed)
     */
    @Override
    public List<App> makeEntityDetails(List<?> appList, boolean decryptDeleted, Context context) {
        if (appList == null || appList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert apps: app list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<App> appDetailList = new ArrayList<>();
            List<App> stopedAppList = new ArrayList<>();
            for (Object obj : appList) {
                App app = (App) obj;
                if(Boolean.FALSE.equals(app.isActive())){
                    stopedAppList.add(app);
                    continue;
                }
                app.setOnChain(true);
                // No decryption needed for App entities
                appDetailList.add(app);
            }

            if(!stopedAppList.isEmpty())
                entityDB.remove(stopedAppList);

            TimberLogger.i(TAG, "Successfully converted %d apps to App objects",
                appDetailList.size());
            return appDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting apps to App objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Refreshes apps by loading newer changes including inactive apps for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context Activity context for dialog operations
     */
    public void refreshAppsFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer app changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of app changes processed, or -1 if failed
     */
    public int loadNewerAppsFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid apps from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid app items fetched, or -1 if failed
     */
    public int loadEarlierAppsFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }

    /**
     * Searches for App objects by stdName and desc fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of App objects that match the search criteria
     */
    public List<App> searchApps(String searchQuery) {
        return searchEntities(searchQuery);
    }

    /**
     * Searches app data from API with custom field, sort, and order parameters.
     *
     * @param context        Activity context for dialog operations
     * @param searchQuery    The search query string to match in the inField
     * @param inField        The field to search in (e.g., "stdName", "desc")
     * @param sortField      The field to sort by (e.g., LAST_TIME, ID)
     * @param order          Sort order (ASC or DESC)
     * @param referenceApp   The reference app for pagination, or null for first page
     * @return List of app items matching the search, or null if failed
     */
    public List<App> searchAppsFromApi(Context context,
                                       String searchQuery, String inField, String sortField, String order, App referenceApp) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search apps: FapiClient not available");
            return null;
        }

        TimberLogger.i(TAG, "Searching apps with query: %s in field: %s, sort: %s %s",
            searchQuery, inField, sortField, order);

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceApp, false);
            List<App> appItems = fetchPageApps(fcdsl);
            if (appItems == null) {
                TimberLogger.w(TAG, "Failed to search apps from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d app items from API", appItems.size());
            return appItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching apps from API: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Searches apps owned by the liveFid from API.
     */
    public List<App> searchMyAppsFromApi(Context context, String searchQuery, String inField, 
                                          String sortField, String order, App referenceApp) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search my apps: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceApp, true);
            List<App> appItems = fetchPageApps(fcdsl);

            if (appItems == null) {
                TimberLogger.w(TAG, "Failed to search my apps from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d of my app items from API", appItems.size());
            return appItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching my apps from API: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildSearchFcdsl(String searchQuery, String inField, String sortField, 
                                   String order, App referenceApp, boolean ownerOnly) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(APP);
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);

        // Add query
        fcdsl.addNewQuery();

        // Add owner filter if searching my apps
        if (ownerOnly) {
            fcdsl.getQuery().addNewEquals().addNewFields(OWNER).addNewValues(liveFid);
        }

        // Add active filter
        fcdsl.getQuery().addNewTerms().addNewFields(ACTIVE).addNewValues(TRUE);

        // Add search filter
        if (searchQuery != null && !searchQuery.isEmpty()) {
            if (inField != null) {
                String adjustedField = adjustFieldName(inField);
                fcdsl.getQuery().addNewMatch().addNewFields(adjustedField).addNewValue(searchQuery);
            } else {
                fcdsl.getQuery().addNewMatch()
                    .addNewFields(STD_NAME, FieldNames.DESC, OWNER, FieldNames.LOCAL_NAMES, FieldNames.TYPES)
                    .addNewValue(searchQuery);
            }
        }

        // Add sorting
        String normalizedOrder = normalizeOrder(order);
        addSortingToFcdsl(fcdsl, sortField, normalizedOrder);

        // Add pagination
        if (referenceApp != null) {
            List<String> last = Arrays.asList(String.valueOf(referenceApp.getLastHeight()), referenceApp.getId());
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    @NonNull
    private static String adjustFieldName(String displayName) {
        String fieldName = App.getFieldNameByDisplayName(displayName);
        return fieldName != null ? fieldName : displayName;
    }

    private String normalizeOrder(String order) {
        if (order == null) {
            return null;
        }
        String lowerOrder = order.toLowerCase();
        return (lowerOrder.equals(com.fc.fc_ajdk.constants.Values.DESC.toLowerCase()) ||
                lowerOrder.equals(com.fc.fc_ajdk.constants.Values.ASC.toLowerCase())) ? lowerOrder : null;
    }

    private void addSortingToFcdsl(Fcdsl fcdsl, String sortField, String order) {
        if (sortField != null) {
            String adjustedField = adjustFieldName(sortField);
            String sortOrder = (order != null) ? order : DESC;
            fcdsl.addSort(adjustedField, sortOrder);
            if (!adjustedField.equals(ID)) {
                fcdsl.addSort(ID, sortOrder);
            }
        } else if (order != null) {
            fcdsl.addSort(LAST_TIME, order).addSort(ID, order);
        } else {
            // Default sort by lastTime and ID in descending order
            fcdsl.addSort(LAST_TIME, DESC).addSort(ID, DESC);
        }
    }

    /**
     * Refreshes the entire app database by fetching all active apps from API
     * and replacing the local database contents.
     *
     * @param context Activity context for dialog operations
     * @return Number of app items refreshed, or 0 if failed
     */
    public int freshAppDB(Context context) {
        return freshEntityDB(context);
    }

    // Abstract method implementations for AppManager

    @Override
    protected String getEntityIndexName() {
        return APP;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return App.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        App app = (App) apiObject;
        return Arrays.asList(String.valueOf(app.getLastHeight()), app.getId());
    }

    @Override
    protected void preprocessEntity(App entity) {
        // App entities come with IDs from the chain, no preprocessing needed
    }

    @Override
    protected boolean isEntityDeleted(App entity) {
        return Boolean.TRUE.equals(entity.isClosed());
    }

    @Override
    protected Long getEntityUpdateHeight(App entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(App entity) {
        // Check the onChain field if set, otherwise assume on-chain
        Boolean onChain = entity.getOnChain();
        return onChain == null || onChain;
    }

    @Override
    protected List<App> searchFromList(String query, List<App> results) {
        List<App> matchingApps = new ArrayList<>();

        for (App app : results) {
            boolean matches = false;

            // Search in stdName
            if (app.getStdName() != null && app.getStdName().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in desc
            if (!matches && app.getDesc() != null && app.getDesc().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in owner
            if (!matches && app.getOwner() != null && app.getOwner().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in localNames (locale code -> display name)
            if (!matches && app.getLocalNames() != null) {
                String q = query.toLowerCase();
                for (Map.Entry<String, String> e : app.getLocalNames().entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if ((k != null && k.toLowerCase().contains(q))
                            || (v != null && v.toLowerCase().contains(q))) {
                        matches = true;
                        break;
                    }
                }
            }

            // Search in types
            if (!matches && app.getTypes() != null) {
                for (String type : app.getTypes()) {
                    if (type != null && type.toLowerCase().contains(query.toLowerCase())) {
                        matches = true;
                        break;
                    }
                }
            }

            if (matches) {
                matchingApps.add(app);
            }
        }
        return matchingApps;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchApps(context, after, active, timeAsc);
    }

    @Override
    protected App createFailedDecryptionEntity(Object apiObject, Context context) {
        // No decryption needed for App entities, just return as-is
        return (App) apiObject;
    }

    @Override
    protected App createUndecryptedEntity(Context context, Object apiObject) {
        // No decryption needed for App entities, just return as-is
        if (apiObject instanceof App app) {
            return app;
        }
        return null;
    }

    public List<App> getLocalHiddenApps() {
        return getLocalDeletedList();
    }

    public long getLocalHiddenAppsSize() {
        Long size = getLocalDeletedListSize();
        return size != null ? size : 0;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        // TODO: Implement when needed
        return null;
    }
}

