package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.NAME;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.IndicesNames.PROTOCOL;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.FALSE;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Protocol;
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
 * A singleton class to manage and share the Protocol database across activities.
 * This provides a centralized way to access the Protocol database from any activity.
 */
public class ProtocolManager extends FcManager<Protocol> {
    private static final String TAG = "ProtocolManager";
    public static final int MAX_PAGES = 200;

    private static ProtocolManager instance;

    private ProtocolManager() {
        super(Protocol.class, PROTOCOL);
    }

    /**
     * Gets the singleton instance of ProtocolManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The ProtocolManager instance
     */
    public static synchronized ProtocolManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new ProtocolManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized ProtocolManager getInstance() {
        return instance;
    }

    /**
     * Gets a Protocol object by its ID.
     *
     * @param id The ID of the Protocol object to get
     * @return The Protocol object, or null if not found
     */
    public Protocol getProtocolById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Protocol object to the database.
     *
     * @param protocol The Protocol object to add
     */
    public void addProtocol(Protocol protocol) {
        addEntity(protocol);
    }

    /**
     * Removes a Protocol object from the database.
     *
     * @param protocol The Protocol object to remove
     */
    public void removeProtocol(Protocol protocol) {
        removeEntity(protocol);
    }

    /**
     * Removes multiple Protocol objects from the database.
     *
     * @param protocols The list of Protocol objects to remove
     */
    public void removeProtocols(List<Protocol> protocols) {
        removeEntities(protocols);
    }

    /**
     * Updates an existing Protocol in the database.
     * @param protocol The Protocol object to update
     */
    public void updateProtocol(Protocol protocol) {
        updateEntity(protocol);
    }

    /**
     * Gets a paginated list of Protocol objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of Protocol objects for the requested page
     */
    public List<Protocol> getPaginatedProtocols(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    @Override
    protected void preprocessDB() {
        // No special preprocessing needed for Protocol database
    }

    @Override
    protected void markEntityAsNew(Protocol entity) {
        // No special marking needed for new Protocol entities
    }

    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(PROTOCOL);
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
     * Makes an FCDSL query for protocols owned by the liveFid.
     */
    public Fcdsl makeFcdslForOwner(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = makeFcdsl(pageSize, after, active, timeAsc);
        fcdsl.addNewFilter().addNewTerms().addNewFields(OWNER).addNewValues(liveFid);
        return fcdsl;
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<Protocol> entityList, int index, int savedCount) {
        processProtocolSequentially(activity, entityList, index, savedCount);
    }

    private void processProtocolSequentially(Activity activity, List<Protocol> protocolList, int index, int savedCount) {
        if (index >= protocolList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        Protocol protocol = protocolList.get(index);

        if (checkIfExisted(protocol.getId())) {
            String prompt = protocol.getName() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(protocol);
                    processProtocolSequentially(activity, protocolList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processProtocolSequentially(activity, protocolList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(protocol);
            processProtocolSequentially(activity, protocolList, index + 1, savedCount + 1);
        }
    }

    public long getProtocolDBSize() {
        return getEntityDBSize();
    }

    /**
     * Fetches all protocols from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active protocols
     * @param timeAsc Sort in time ascending order
     * @return List of all Protocol objects, or null if failed
     */
    private List<Protocol> fetchProtocols(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, MAX_PAGES);
        if (result == null) {
            return null;
        }

        List<Protocol> protocolList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof Protocol protocol) {
                protocolList.add(protocol);
            }
        }
        return protocolList;
    }

    public List<Protocol> fetchPageProtocols(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<Protocol> protocolList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof Protocol protocol) {
                protocolList.add(protocol);
                protocol.setOnChain(true);
            }
        }

        return protocolList;
    }

    /**
     * Converts Protocol objects (no decryption needed)
     */
    @Override
    public List<Protocol> makeEntityDetails(List<?> protocolList, boolean decryptDeleted, Context context) {
        if (protocolList == null || protocolList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert protocols: protocol list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<Protocol> protocolDetailList = new ArrayList<>();
            List<Protocol> stoppedProtocolList = new ArrayList<>();
            for (Object obj : protocolList) {
                Protocol protocol = (Protocol) obj;
                if(Boolean.FALSE.equals(protocol.isActive())){
                    stoppedProtocolList.add(protocol);
                    continue;
                }
                protocol.setOnChain(true);
                protocolDetailList.add(protocol);
            }

            if(!stoppedProtocolList.isEmpty())
                entityDB.remove(stoppedProtocolList);

            TimberLogger.i(TAG, "Successfully converted %d protocols to Protocol objects",
                protocolDetailList.size());
            return protocolDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting protocols to Protocol objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Refreshes protocols by loading newer changes including inactive protocols for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context Activity context for dialog operations
     */
    public void refreshProtocolsFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer protocol changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of protocol changes processed, or -1 if failed
     */
    public int loadNewerProtocolsFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid protocols from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid protocol items fetched, or -1 if failed
     */
    public int loadEarlierProtocolsFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }

    /**
     * Searches for Protocol objects by name and desc fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of Protocol objects that match the search criteria
     */
    public List<Protocol> searchProtocols(String searchQuery) {
        return searchEntities(searchQuery);
    }

    /**
     * Searches protocol data from API with custom field, sort, and order parameters.
     */
    public List<Protocol> searchProtocolsFromApi(Context context,
                                       String searchQuery, String inField, String sortField, String order, Protocol referenceProtocol) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search protocols: FapiClient not available");
            return null;
        }

        TimberLogger.i(TAG, "Searching protocols with query: %s in field: %s, sort: %s %s",
            searchQuery, inField, sortField, order);

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceProtocol, false);
            List<Protocol> protocolItems = fetchPageProtocols(fcdsl);
            if (protocolItems == null) {
                TimberLogger.w(TAG, "Failed to search protocols from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d protocol items from API", protocolItems.size());
            return protocolItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching protocols from API: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Searches protocols owned by the liveFid from API.
     */
    public List<Protocol> searchMyProtocolsFromApi(Context context, String searchQuery, String inField, 
                                          String sortField, String order, Protocol referenceProtocol) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search my protocols: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceProtocol, true);
            List<Protocol> protocolItems = fetchPageProtocols(fcdsl);

            if (protocolItems == null) {
                TimberLogger.w(TAG, "Failed to search my protocols from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d of my protocol items from API", protocolItems.size());
            return protocolItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching my protocols from API: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildSearchFcdsl(String searchQuery, String inField, String sortField, 
                                   String order, Protocol referenceProtocol, boolean ownerOnly) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(PROTOCOL);
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);

        // Add query
        fcdsl.addNewQuery();

        // Add owner filter if searching my protocols
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
                    .addNewFields(NAME, FieldNames.DESC, OWNER, FieldNames.TYPE, FieldNames.SN)
                    .addNewValue(searchQuery);
            }
        }

        // Add sorting
        String normalizedOrder = normalizeOrder(order);
        addSortingToFcdsl(fcdsl, sortField, normalizedOrder);

        // Add pagination
        if (referenceProtocol != null) {
            List<String> last = Arrays.asList(String.valueOf(referenceProtocol.getLastHeight()), referenceProtocol.getId());
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    @NonNull
    private static String adjustFieldName(String displayName) {
        String fieldName = Protocol.getFieldNameByDisplayName(displayName);
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
     * Refreshes the entire protocol database by fetching all active protocols from API
     * and replacing the local database contents.
     *
     * @param context Activity context for dialog operations
     * @return Number of protocol items refreshed, or 0 if failed
     */
    public int freshProtocolDB(Context context) {
        return freshEntityDB(context);
    }

    // Abstract method implementations for ProtocolManager

    @Override
    protected String getEntityIndexName() {
        return PROTOCOL;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return Protocol.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        Protocol protocol = (Protocol) apiObject;
        return Arrays.asList(String.valueOf(protocol.getLastHeight()), protocol.getId());
    }

    @Override
    protected void preprocessEntity(Protocol entity) {
        // Protocol entities come with IDs from the chain, no preprocessing needed
    }

    @Override
    protected boolean isEntityDeleted(Protocol entity) {
        return Boolean.TRUE.equals(entity.isClosed());
    }

    @Override
    protected Long getEntityUpdateHeight(Protocol entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(Protocol entity) {
        Boolean onChain = entity.getOnChain();
        return onChain == null || onChain;
    }

    @Override
    protected List<Protocol> searchFromList(String query, List<Protocol> results) {
        List<Protocol> matchingProtocols = new ArrayList<>();

        for (Protocol protocol : results) {
            boolean matches = false;

            // Search in name
            if (protocol.getName() != null && protocol.getName().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in desc
            if (!matches && protocol.getDesc() != null && protocol.getDesc().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in owner
            if (!matches && protocol.getOwner() != null && protocol.getOwner().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in type
            if (!matches && protocol.getType() != null && protocol.getType().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in sn
            if (!matches && protocol.getSn() != null && protocol.getSn().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            if (matches) {
                matchingProtocols.add(protocol);
            }
        }
        return matchingProtocols;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchProtocols(context, after, active, timeAsc);
    }

    @Override
    protected Protocol createFailedDecryptionEntity(Object apiObject, Context context) {
        return (Protocol) apiObject;
    }

    @Override
    protected Protocol createUndecryptedEntity(Context context, Object apiObject) {
        if (apiObject instanceof Protocol protocol) {
            return protocol;
        }
        return null;
    }

    public List<Protocol> getLocalHiddenProtocols() {
        return getLocalDeletedList();
    }

    public long getLocalHiddenProtocolsSize() {
        Long size = getLocalDeletedListSize();
        return size != null ? size : 0;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        return null;
    }
}
