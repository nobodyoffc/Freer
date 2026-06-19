package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.NAME;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.IndicesNames.CODE;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.FALSE;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A singleton class to manage and share the Code database across activities.
 * This provides a centralized way to access the Code database from any activity.
 */
public class CodeManager extends FcManager<Code> {
    private static final String TAG = "CodeManager";
    public static final int MAX_PAGES = 200;

    private static CodeManager instance;

    private CodeManager() {
        super(Code.class, CODE);
    }

    /**
     * Gets the singleton instance of CodeManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The CodeManager instance
     */
    public static synchronized CodeManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new CodeManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized CodeManager getInstance() {
        return instance;
    }

    /**
     * Gets a Code object by its ID.
     *
     * @param id The ID of the Code object to get
     * @return The Code object, or null if not found
     */
    public Code getCodeById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Code object to the database.
     *
     * @param code The Code object to add
     */
    public void addCode(Code code) {
        addEntity(code);
    }

    /**
     * Removes a Code object from the database.
     *
     * @param code The Code object to remove
     */
    public void removeCode(Code code) {
        removeEntity(code);
    }

    /**
     * Removes multiple Code objects from the database.
     *
     * @param codes The list of Code objects to remove
     */
    public void removeCodes(List<Code> codes) {
        removeEntities(codes);
    }

    /**
     * Updates an existing Code in the database.
     * @param code The Code object to update
     */
    public void updateCode(Code code) {
        updateEntity(code);
    }

    /**
     * Gets a paginated list of Code objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of Code objects for the requested page
     */
    public List<Code> getPaginatedCodes(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    @Override
    protected void preprocessDB() {
        // No special preprocessing needed for Code database
    }

    @Override
    protected void markEntityAsNew(Code entity) {
        // No special marking needed for new Code entities
    }

    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(CODE);
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
     * Makes an FCDSL query for codes owned by the liveFid.
     */
    public Fcdsl makeFcdslForOwner(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = makeFcdsl(pageSize, after, active, timeAsc);
        fcdsl.addNewFilter().addNewTerms().addNewFields(OWNER).addNewValues(liveFid);
        return fcdsl;
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<Code> entityList, int index, int savedCount) {
        processCodeSequentially(activity, entityList, index, savedCount);
    }

    private void processCodeSequentially(Activity activity, List<Code> codeList, int index, int savedCount) {
        if (index >= codeList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        Code code = codeList.get(index);

        if (checkIfExisted(code.getId())) {
            String prompt = code.getName() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(code);
                    processCodeSequentially(activity, codeList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processCodeSequentially(activity, codeList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(code);
            processCodeSequentially(activity, codeList, index + 1, savedCount + 1);
        }
    }

    public long getCodeDBSize() {
        return getEntityDBSize();
    }

    /**
     * Fetches all codes from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active codes
     * @param timeAsc Sort in time ascending order
     * @return List of all Code objects, or null if failed
     */
    private List<Code> fetchCodes(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, MAX_PAGES);
        if (result == null) {
            return null;
        }

        List<Code> codeList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof Code code) {
                codeList.add(code);
            }
        }
        return codeList;
    }

    public List<Code> fetchPageCodes(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<Code> codeList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof Code code) {
                codeList.add(code);
                code.setOnChain(true);
            }
        }

        return codeList;
    }

    /**
     * Converts Code objects (no decryption needed)
     */
    @Override
    public List<Code> makeEntityDetails(List<?> codeList, boolean decryptDeleted, Context context) {
        if (codeList == null || codeList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert codes: code list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<Code> codeDetailList = new ArrayList<>();
            List<Code> stoppedCodeList = new ArrayList<>();
            for (Object obj : codeList) {
                Code code = (Code) obj;
                if(Boolean.FALSE.equals(code.isActive())){
                    stoppedCodeList.add(code);
                    continue;
                }
                code.setOnChain(true);
                codeDetailList.add(code);
            }

            if(!stoppedCodeList.isEmpty())
                entityDB.remove(stoppedCodeList);

            TimberLogger.i(TAG, "Successfully converted %d codes to Code objects",
                codeDetailList.size());
            return codeDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting codes to Code objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Refreshes codes by loading newer changes including inactive codes for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context Activity context for dialog operations
     */
    public void refreshCodesFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer code changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of code changes processed, or -1 if failed
     */
    public int loadNewerCodesFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid codes from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid code items fetched, or -1 if failed
     */
    public int loadEarlierCodesFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }

    /**
     * Searches for Code objects by name and desc fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of Code objects that match the search criteria
     */
    public List<Code> searchCodes(String searchQuery) {
        return searchEntities(searchQuery);
    }

    /**
     * Searches code data from API with custom field, sort, and order parameters.
     */
    public List<Code> searchCodesFromApi(Context context,
                                       String searchQuery, String inField, String sortField, String order, Code referenceCode) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search codes: FapiClient not available");
            return null;
        }

        TimberLogger.i(TAG, "Searching codes with query: %s in field: %s, sort: %s %s",
            searchQuery, inField, sortField, order);

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceCode, false);
            List<Code> codeItems = fetchPageCodes(fcdsl);
            if (codeItems == null) {
                TimberLogger.w(TAG, "Failed to search codes from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d code items from API", codeItems.size());
            return codeItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching codes from API: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Searches codes owned by the liveFid from API.
     */
    public List<Code> searchMyCodesFromApi(Context context, String searchQuery, String inField, 
                                          String sortField, String order, Code referenceCode) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search my codes: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceCode, true);
            List<Code> codeItems = fetchPageCodes(fcdsl);

            if (codeItems == null) {
                TimberLogger.w(TAG, "Failed to search my codes from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d of my code items from API", codeItems.size());
            return codeItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching my codes from API: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildSearchFcdsl(String searchQuery, String inField, String sortField, 
                                   String order, Code referenceCode, boolean ownerOnly) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(CODE);
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);

        // Add query
        fcdsl.addNewQuery();

        // Add owner filter if searching my codes
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
                    .addNewFields(NAME, FieldNames.DESC, OWNER, FieldNames.LANGS, FieldNames.PROTOCOLS)
                    .addNewValue(searchQuery);
            }
        }

        // Add sorting
        String normalizedOrder = normalizeOrder(order);
        addSortingToFcdsl(fcdsl, sortField, normalizedOrder);

        // Add pagination
        if (referenceCode != null) {
            List<String> last = Arrays.asList(String.valueOf(referenceCode.getLastHeight()), referenceCode.getId());
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    @NonNull
    private static String adjustFieldName(String displayName) {
        String fieldName = Code.getFieldNameByDisplayName(displayName);
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
     * Refreshes the entire code database by fetching all active codes from API
     * and replacing the local database contents.
     *
     * @param context Activity context for dialog operations
     * @return Number of code items refreshed, or 0 if failed
     */
    public int freshCodeDB(Context context) {
        return freshEntityDB(context);
    }

    // Abstract method implementations for CodeManager

    @Override
    protected String getEntityIndexName() {
        return CODE;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return Code.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        Code code = (Code) apiObject;
        return Arrays.asList(String.valueOf(code.getLastHeight()), code.getId());
    }

    @Override
    protected void preprocessEntity(Code entity) {
        // Code entities come with IDs from the chain, no preprocessing needed
    }

    @Override
    protected boolean isEntityDeleted(Code entity) {
        return Boolean.TRUE.equals(entity.isClosed());
    }

    @Override
    protected Long getEntityUpdateHeight(Code entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(Code entity) {
        Boolean onChain = entity.getOnChain();
        return onChain == null || onChain;
    }

    @Override
    protected List<Code> searchFromList(String query, List<Code> results) {
        List<Code> matchingCodes = new ArrayList<>();

        for (Code code : results) {
            boolean matches = false;

            // Search in name
            if (code.getName() != null && code.getName().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in desc
            if (!matches && code.getDesc() != null && code.getDesc().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in owner
            if (!matches && code.getOwner() != null && code.getOwner().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in langs
            if (!matches && code.getLangs() != null) {
                for (String lang : code.getLangs()) {
                    if (lang != null && lang.toLowerCase().contains(query.toLowerCase())) {
                        matches = true;
                        break;
                    }
                }
            }

            // Search in protocols
            if (!matches && code.getProtocols() != null) {
                for (String protocol : code.getProtocols()) {
                    if (protocol != null && protocol.toLowerCase().contains(query.toLowerCase())) {
                        matches = true;
                        break;
                    }
                }
            }

            if (matches) {
                matchingCodes.add(code);
            }
        }
        return matchingCodes;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchCodes(context, after, active, timeAsc);
    }

    @Override
    protected Code createFailedDecryptionEntity(Object apiObject, Context context) {
        return (Code) apiObject;
    }

    @Override
    protected Code createUndecryptedEntity(Context context, Object apiObject) {
        if (apiObject instanceof Code code) {
            return code;
        }
        return null;
    }

    public List<Code> getLocalHiddenCodes() {
        return getLocalDeletedList();
    }

    public long getLocalHiddenCodesSize() {
        Long size = getLocalDeletedListSize();
        return size != null ? size : 0;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        return null;
    }
}
