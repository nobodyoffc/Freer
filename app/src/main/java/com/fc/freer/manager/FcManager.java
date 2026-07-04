package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.IndicesNames.SECRET;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.TRUE;
import static com.fc.fc_ajdk.db.LocalDB.LOCAL_DELETED_LIST_NAME;
import static com.fc.freer.manager.DatabaseManager.HAS_MORE_NEWER;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.data.fchData.Block;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.freer.R;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import android.app.Activity;

public abstract class FcManager<T extends FcEntity> {
    private static final String TAG = "FcManager";
    public static final int LOCAL_DELETED_LIST_LIMIT = 500;

    protected String entityName;

    protected LocalDB<T> entityDB;
    protected String liveFid;
//    protected FapiClient fapiClient;
    protected Long totalOnChain;
    protected Class<T> entityClass;

    // Failed decryption handling
//    private List<T> pendingFailedDecryptions;

    public enum ManagerType{
        TEST,
        ACCOUNT,
        APP,
        CASH,
        CONTACT,
        MAIL,
        MULTISIG,
        SECRET,
        KEY_INFO,
        AVATAR,
        CID,
        FC_OBJECT,
        PROOF;
        @NonNull
        @Override
        public String toString() {
            return this.name();
        }

        // New method to check if a string matches a ServiceType
        public static ManagerType fromString(String input) {
            if (input == null) {
                return null;
            }
            for (ManagerType type : ManagerType.values()) {
                if (type.name().equalsIgnoreCase(input)) {
                    return type;
                }
            }
            return null;
        }
    }

    protected FcManager(Class<T> entityClass, String entityName) {
        if (entityName!=null)this.entityName = entityName.toLowerCase();
        else this.entityName = entityClass.getSimpleName().toLowerCase();
        this.entityClass = entityClass;
    }

    /**
     * Initializes the manager with the given context and fid.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid     The live FID for this instance
     */
    public void initialize(Context context, String fid) {
        this.liveFid = fid;
        DatabaseManager dbManager = DatabaseManager.getInstance();
        if (entityDB != null) {
            try {
                entityDB.close();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error closing database: " + e.getMessage());
            }
        }
        entityDB = dbManager.getEntityDatabase(liveFid, entityClass, entityClass);
        if(entityDB==null){
            TimberLogger.w(TAG, "No " + entityClass.getSimpleName() + " database available from DatabaseManager when initiating " + getClass().getSimpleName() + ".");
        }
        preprocessDB();

        if(Boolean.TRUE.equals(isRollback(context))) rollBack(context);

        new Thread(() -> refreshEntitiesFromAPI(context)).start();
    }

    protected abstract void preprocessDB();

    /**
     * Gets an entity object by its ID.
     *
     * @param id The ID of the entity object to get
     * @return The entity object, or null if not found
     */
    public T getEntityById(String id) {
        return entityDB.get(id);
    }

    /**
     * Adds an entity object to the database.
     *
     * @param entity The entity object to add
     */
    public void addEntity(T entity) {
        preprocessEntity(entity);
        entityDB.put(entity.getId(), entity);
    }

    /**
     * Removes an entity object from the database.
     *
     * @param entity The entity object to remove
     */
    public void removeEntity(T entity) {
        entityDB.remove(entity.getId());
    }

    /**
     * Removes multiple entity objects from the database.
     *
     * @param entities The list of entity objects to remove
     */
    public void removeEntities(List<T> entities) {
        entityDB.remove(entities);
    }

    /**
     * Updates an existing entity in the database.
     * @param entity The entity object to update
     */
    public void updateEntity(T entity) {
        preprocessEntity(entity);
        entityDB.put(entity.getId(), entity);
    }

    /**
     * Commits changes to the database.
     */
    public void commit() {
        entityDB.commit();
    }

    /**
     * Gets a paginated list of entity objects.
     *
     * @param pageSize The number of items per page
     * @param lastID The ID of the last item from the previous page, or null for the first page
     * @param fromEnd Whether to sort in fromEnd order
     * @return A list of entity objects for the requested page
     */
    public List<T> getPaginatedEntities(int pageSize, String lastID, boolean fromEnd) {
        return entityDB.getList(pageSize, lastID, null, false, null, null, false, fromEnd);
    }

    /**
     * Checks if an entity with the given ID already exists in the database.
     *
     * @param id The ID to check
     * @return true if the entity exists, false otherwise
     */
    public boolean checkIfExisted(String id) {
        return entityDB.get(id) != null;
    }

    public String getLiveFid() {
        return liveFid;
    }

    public void setLiveFid(String liveFid) {
        this.liveFid = liveFid;
    }

    public long getEntityDBSize() {
        if(entityDB == null) return 0;
        return entityDB.getSize();
    }

    public Long getTotalOnChain() {
        return totalOnChain;
    }

    public void setTotalOnChain(Long totalOnChain) {
        this.totalOnChain = totalOnChain;
        saveStateTotal(totalOnChain);
    }

    /**
     * Gets the FapiClient from ApiCenter for API operations.
     * Includes retry logic for cases when the client is still initializing.
     *
     * @return FapiClient instance, or null if not available after retries
     */
    public FapiClient loadFapiClient() {
        return loadFapiClient(3, 1000); // Default: 3 retries with 1 second delay
    }
    
    /**
     * Gets the FapiClient from ApiCenter with configurable retry parameters.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param retryDelayMs Delay between retries in milliseconds
     * @return FapiClient instance, or null if not available after retries
     */
    public FapiClient loadFapiClient(int maxRetries, long retryDelayMs) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                Object client = apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (client instanceof FapiClient fapiClient) {
                    // Use isConfigured() for quick check to avoid excessive pings during retry
                    if (fapiClient.isConfigured()) {
                        return fapiClient;
                    }
                    TimberLogger.w(TAG, "FapiClient exists but is not configured (attempt %d/%d)", 
                            attempt + 1, maxRetries + 1);
                } else if (attempt < maxRetries) {
                    TimberLogger.d(TAG, "FapiClient not ready, retrying in %dms (attempt %d/%d)", 
                            retryDelayMs, attempt + 1, maxRetries + 1);
                }
                
                // Wait before retry (except on last attempt)
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error getting FapiClient from ApiCenter (attempt %d/%d): %s", 
                        attempt + 1, maxRetries + 1, e.getMessage());
            }
        }
        TimberLogger.w(TAG, "No FapiClient available from ApiCenter after %d attempts", maxRetries + 1);
        return null;
    }

    /**
     * Refreshes entities by loading newer changes including inactive entities for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context Activity context for dialog operations
     */
    public void refreshEntitiesFromAPI(Context context) {
        List<String> lastUpdated = getStateLastUpdated();
        totalOnChain = getStateTotal();
        int added = 0;
        if (lastUpdated != null && !lastUpdated.isEmpty()) {
            added = loadNewerEntitiesFromAPI(context);
        } else {
            added = loadEarlierEntitiesFromAPI(context);
        }

        if(added > 0) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                ToastUtils.makeText(context, context.getString(R.string.added_new_entities, added, entityName));
            } else {
                int finalAdded = added;
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(() ->
                        ToastUtils.makeText(context, context.getString(R.string.added_new_entities, finalAdded,entityName))
                    );
                } else {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        ToastUtils.makeText(context, context.getString(R.string.added_new_entities, finalAdded,entityName))
                    );
                }
            }
        }
    }

    /**
     * Loads newer entity changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of entity changes processed, or -1 if failed
     */
    public int loadNewerEntitiesFromAPI(Context context) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot load newer entities: FapiClient not available");
            return -1;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot load newer entities: liveFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting to load newer " + entityClass.getSimpleName() + " for liveFid: %s", liveFid);

        try {
            List<String> lastUpdated = getStateLastUpdated();
            List<?> fetchedApiObjects = fetchEntitiesFromAPI(context, lastUpdated, null, true, SECRET);

            if (fetchedApiObjects == null) {
                TimberLogger.w(TAG, "Failed to fetch newer " + entityClass.getSimpleName() + " from API");
                return 0;
            }

            if (fetchedApiObjects.isEmpty()) {
                TimberLogger.i(TAG, "No newer " + entityClass.getSimpleName() + " changes fetched");
                return 0;
            }

            updateApiStatesForNewer(fetchedApiObjects);
            List<T> entityList = makeEntityDetails(fetchedApiObjects, false,context );
            int added = updateToDBForNewer(entityList);
            commit();

            return added;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer " + entityClass.getSimpleName() + ": %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Loads earlier (older) valid entities from API.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid entity items fetched, or -1 if failed
     */
    public int loadEarlierEntitiesFromAPI(Context context) {
        FapiClient fapiClient = loadFapiClient();

        if(fapiClient==null) {
            TimberLogger.w(TAG, "Cannot load earlier entities: FapiClient not available");
            return -1;
        }


        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot load earlier entities: liveFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting to load earlier valid " + entityClass.getSimpleName() + " for liveFid: %s", liveFid);

        try {
            Boolean hasMore = getStateHasMoreEarlier();
            if (hasMore != null && !hasMore) {
                TimberLogger.i(TAG, "No more earlier " + entityClass.getSimpleName() + " available");
                return 0;
            }

            List<String> earliest = getStateEarliestActive();
            List<?> fetchedApiObjects = fetchEntitiesFromAPI(context, earliest, true, false, SECRET);

            if (fetchedApiObjects == null) {
                TimberLogger.w(TAG, "Failed to fetch earlier " + entityClass.getSimpleName() + " from API");
                return 0;
            }

            if (fapiClient.getLastResponse()!=null) {
                Long receivedTotal = fapiClient.getLastResponse().getTotal();
                if (receivedTotal != null) {
                    setTotalOnChain(receivedTotal);
                }
            }

            if (fetchedApiObjects.isEmpty()) {
                TimberLogger.i(TAG, "No earlier valid " + entityClass.getSimpleName() + " items fetched");
                return 0;
            }

            List<T> entityList = makeEntityDetails(fetchedApiObjects, false,context );
            int added = updateToDBForEarlier(entityList);
            updateApiStatesForEarlier(fetchedApiObjects);
            commit();
            return added;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading earlier " + entityClass.getSimpleName() + ": %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Updates database for earlier data loading.
     */
    private int updateToDBForEarlier(List<T> entityList) {
        if (entityList == null || entityList.isEmpty()) return 0;

        entityDB.insertAll(entityList);
        int added = entityList.size();
        TimberLogger.i(TAG, "Successfully inserted earlier " + entityClass.getSimpleName() + ": %d added", added);
        return added;
    }

    /**
     * Updates database for newer data loading (includes cleanup of inactive entities).
     */
    private int updateToDBForNewer(List<T> entityList) {
        if (entityList == null || entityList.isEmpty()) return 0;

        int added = 0;
        int removed = 0;

        List<T> entitiesToAdd = new ArrayList<>();
        List<T> entitiesToRemove = new ArrayList<>();

        for (T entity : entityList) {
            try {
                if (isEntityDeleted(entity)) {
                    entitiesToRemove.add(entity);
                    removed++;
                    continue;
                }
                entitiesToAdd.add(entity);
                markEntityAsNew(entity);
                added++;
            } catch (Exception e) {
                continue;
            }
        }

        if (!entitiesToRemove.isEmpty()) {
            removeEntities(entitiesToRemove);
        }

        if (!entitiesToAdd.isEmpty()) {
            java.util.Collections.reverse(entitiesToAdd);
            entityDB.addAll(entitiesToAdd, ID);
        }

        TimberLogger.i(TAG, "Successfully updated newer " + entityClass.getSimpleName() + ": %d added, %d removed", added, removed);
        return added;
    }

    protected abstract void markEntityAsNew(T entity);

    /**
     * Gets the latest entity pagination info for continuing from where we left off
     */
    private List<String> getStateLastUpdated() {
        Object obj = entityDB.getState(DatabaseManager.LAST_UPDATED);
        if(obj == null) return null;
        String lastStr = obj.toString();
        return Arrays.asList(lastStr.split(","));
    }

    protected void saveStateLastUpdated(List<String> statusList) {
        saveStatusList(DatabaseManager.LAST_UPDATED, statusList);
        if(statusList != null)
            TimberLogger.i(TAG, "Last updated " + entityClass.getSimpleName() + " state saved: %s", statusList.get(0));
    }

    /**
     * Gets the earliest entity pagination info for loading older entities
     */
    private List<String> getStateEarliestActive() {
        Object obj = entityDB.getState(DatabaseManager.EARLIEST_ACTIVE);
        if(obj == null) return null;
        String lastStr = obj.toString();
        return Arrays.asList(lastStr.split(","));
    }

    protected void saveStateEarliestActive(List<String> statusList) {
        saveStatusList(DatabaseManager.EARLIEST_ACTIVE, statusList);
        if(statusList != null) TimberLogger.i(TAG, "Earliest active " + entityClass.getSimpleName() + " state saved: %s", statusList.get(0));
    }

    protected void saveStateHasMoreEarlier(Boolean hasMore) {
        entityDB.putState(DatabaseManager.HAS_MORE_EARLIER, String.valueOf(hasMore));
    }

    private Boolean getStateHasMoreEarlier() {
        Object obj = entityDB.getState(DatabaseManager.HAS_MORE_EARLIER);
        if(obj == null) return null;
        return Boolean.valueOf((String) obj);
    }

    protected void saveStateHasMoreNewer(Boolean hasMore) {
        entityDB.putState(HAS_MORE_NEWER, String.valueOf(hasMore));
    }

    private Boolean getStateHasMoreNewer() {
        Object obj = entityDB.getState(HAS_MORE_NEWER);
        if(obj == null) return null;
        return Boolean.valueOf((String) obj);
    }

    protected void saveStateTotal(Long total) {
        entityDB.putState(DatabaseManager.TOTAL, String.valueOf(total));
    }

    private Long getStateTotal() {
        String key = DatabaseManager.TOTAL;
        return entityDB.getStateLong(key);
    }

    /**
     * Saves a status string list as a comma-separated string to the state map.
     *
     * @param statusKey The key for the status (e.g., "latest" or "earliest")
     * @param statusList The list of strings to save
     */
    public void saveStatusList(String statusKey, List<String> statusList) {
        if (entityDB == null) {
            TimberLogger.w(TAG, "Cannot save status: " + entityClass.getSimpleName() + " database not available");
            return;
        }

        if (statusList == null || statusList.isEmpty()) {
            entityDB.removeState(statusKey);
            TimberLogger.i(TAG, "Removed status for key: %s", statusKey);
            return;
        }

        String statusString = String.join(",", statusList);
        entityDB.putState(statusKey, statusString);
        TimberLogger.i(TAG, "Saved status for key '%s': %s", statusKey, statusString);
    }

    /**
     * Updates API states for earlier data loading.
     */
    private void updateApiStatesForEarlier(List<?> apiObjectList) {
        if (apiObjectList == null || apiObjectList.isEmpty()) return;

        try {
            Object earliest = apiObjectList.get(apiObjectList.size() - 1);
            List<String> earliestInfo = makeSortList(earliest);
            saveStateEarliestActive(earliestInfo);
            TimberLogger.i(TAG, "Updated earliest " + entityClass.getSimpleName() + " pagination: %s", earliestInfo);

            List<String> lastUpdated = getStateLastUpdated();
            if (lastUpdated == null || lastUpdated.isEmpty()) {
                Object latest = apiObjectList.get(0);
                List<String> latestInfo = makeSortList(latest);
                saveStateLastUpdated(latestInfo);
                TimberLogger.i(TAG, "Updated latest " + entityClass.getSimpleName() + " pagination: %s", latestInfo);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating " + entityClass.getSimpleName() + " pagination info for earlier: %s", e.getMessage());
        }
    }

    /**
     * Updates API states for newer data loading.
     */
    private void updateApiStatesForNewer(List<?> apiObjectList) {
        if (apiObjectList == null || apiObjectList.isEmpty()) return;

        try {
            Object latest = apiObjectList.get(apiObjectList.size() - 1);
            List<String> latestInfo = makeSortList(latest);
            saveStateLastUpdated(latestInfo);
            TimberLogger.i(TAG, "Updated latest " + entityClass.getSimpleName() + " pagination: %s", latestInfo);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating " + entityClass.getSimpleName() + " pagination info for newer: %s", e.getMessage());
        }
    }

    /**
     * Searches for entity objects by searchable fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of entity objects that match the search criteria
     */
    public List<T> searchEntities(String searchQuery) {
        if (entityDB == null) {
            return new ArrayList<>();
        }

        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            return null;
        }

        String query = searchQuery.trim();

        try {
            List<T> results = entityDB.searchString(query);
            return searchFromList(query, results);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error performing database search, falling back to memory search: %s", e.getMessage());
            return null;
        }
    }

    public Long getStateBestHeight() {
        return entityDB.getStateLong(DatabaseManager.BEST_HEIGHT);
    }

    public String getStateBestBlockId() {
        return (String) entityDB.getState(DatabaseManager.BEST_BLOCK_ID);
    }

    protected void saveBestBlockInfo(){
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) return;

        if (fapiClient.getLastResponse() != null) {
            FapiResponse responseBody = fapiClient.getLastResponse();
            if (responseBody != null) {
                Long bestHeight = responseBody.getBestHeight();
                entityDB.putState(DatabaseManager.BEST_HEIGHT, String.valueOf(bestHeight));
                String bestBlockId = responseBody.getBestBlockId();
                entityDB.putState(DatabaseManager.BEST_BLOCK_ID, bestBlockId);
            }
        }
    }

    public Boolean isRollback(Context context) {
        if(!NetworkUtils.isNetworkAvailable(context))return null;
        Long height = getStateBestHeight();
        String localBestBlockId = getStateBestBlockId();
        if(height==null ||localBestBlockId==null)return false;
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) return null;

        Map<String, Block> heightBlockMap = fapiClient.blockByHeights(Collections.singletonList(String.valueOf(height)));
        if (heightBlockMap == null || heightBlockMap.isEmpty()) {
            return null;
        }
        Block block = heightBlockMap.get(String.valueOf(height));
        if(block ==null)return null;
        return !localBestBlockId.equals(block.getId());
    }

    public int rollBack(Context context) {
        Long lastHeight = getStateBestHeight();
        if(lastHeight==null || lastHeight<30) return 0;

        Long rollbackHeight = lastHeight-30;
        if(rollbackHeight<=0)return 0;

        if (entityDB == null) {
            TimberLogger.w(TAG, "Cannot rollback: database not available");
            return -1;
        }
        FapiClient fapiClient = loadFapiClient();

        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot rollback: FapiClient not available");
            return -1;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot rollback: liveFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting rollback to height: %d for liveFid: %s", rollbackHeight, liveFid);

        try {
            int removedCount = removeItemsAfterHeight(rollbackHeight);
            TimberLogger.i(TAG, "Removed %d items with height > %d", removedCount, rollbackHeight);

            if (removedCount<=0)
                return removedCount;

            List<?> updatedApiObjects = fetchEntitiesFromHeight(context, rollbackHeight);
            if (updatedApiObjects == null) {
                TimberLogger.w(TAG, "Failed to fetch updated " + entityClass.getSimpleName() + " for rollback");
                return -1;
            }

            List<T> updatedEntities = makeEntityDetails(updatedApiObjects, false,context );
            int processedCount = updateDatabaseAfterRollback(updatedEntities);
            updateOnChainTotal(context);
            updateStatesAfterRollback(updatedApiObjects);

            commit();
            TimberLogger.i(TAG, "Rollback completed: removed %d, processed %d items", removedCount, processedCount);

            if(processedCount>0){
                ToastUtils.makeText(context, context.getString(R.string.rollback_success,processedCount));
            }
            return processedCount;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error during rollback: %s", e.getMessage());
            return -1;
        }
    }

    private int removeItemsAfterHeight(Long rollbackHeight) {
        if (entityDB == null) return 0;

        List<T> toRemove = new ArrayList<>();
        List<T> pageEntities;
        boolean end = false;
        String fromId = null;

        while(!end) {
            pageEntities = entityDB.getList(FreerApplication.MAX_CONTAINER_SIZE,fromId,null,false,null,null,true,true);

            for (T entity : pageEntities) {
                Long updateHeight = getEntityUpdateHeight(entity);
                if (updateHeight != null && updateHeight > rollbackHeight) {
                    if(Boolean.TRUE.equals(isEntityOnChain(entity)))
                        toRemove.add(entity);
                }else {
                    if (!toRemove.isEmpty()) {
                        removeEntities(toRemove);
                        TimberLogger.i(TAG, "Removed %d " + entityClass.getSimpleName() + " with height > %d", toRemove.size(), rollbackHeight);
                    }
                    end = true;
                    break;
                }
            }

            if(!toRemove.isEmpty())
                fromId = toRemove.get(toRemove.size()-1).getId();
        }
        return toRemove.size();
    }

    private List<?> fetchEntitiesFromHeight(Context context, Long rollbackHeight) {
        List<Object> allUpdatedEntities = new ArrayList<>();
        final int pageSize = FreerApplication.DEFAULT_REQUEST_SIZE;
        int pageCount = 0;
        List<String> after = null;

        TimberLogger.i(TAG, "Fetching " + entityClass.getSimpleName() + " updated since height: %d", rollbackHeight);

        while (true) {
            pageCount++;
            TimberLogger.d(TAG, "Fetching rollback page %d with size %d", pageCount, pageSize);

            Fcdsl fcdsl = new Fcdsl();
            fcdsl.setEntity(getEntityIndexName());
            fcdsl.addNewQuery().addNewTerms().addNewFields(OWNER).addNewValues(liveFid);
            fcdsl.getQuery().addNewRange().addNewFields(LAST_HEIGHT).addGt(String.valueOf(rollbackHeight));
            fcdsl.addSort(LAST_HEIGHT, ASC).addSort(ID, ASC);
            fcdsl.addSize(pageSize);

            if (after != null && !after.isEmpty()) {
                fcdsl.addAfter(after);
            }
            FapiClient fapiClient = loadFapiClient();
            if (fapiClient == null) return allUpdatedEntities;

            FapiResponse result = fapiClient.search(fcdsl);
            if (result == null || result.getData() == null) {
                if(!fapiClient.isDataNoFound())
                    TimberLogger.w(TAG, "Failed to fetch " + entityClass.getSimpleName() + " for rollback on page %d", pageCount);
                return  allUpdatedEntities;
            }

            List<?> pageEntities = ObjectUtils.objectToList(result.getData(), getApiObjectClass());

            if (pageEntities == null || pageEntities.isEmpty()) {
                TimberLogger.i(TAG, "No more updated " + entityClass.getSimpleName() + " found for rollback at page %d", pageCount);
                break;
            }

            allUpdatedEntities.addAll(pageEntities);
            TimberLogger.d(TAG, "Rollback page %d returned %d " + entityClass.getSimpleName() + ", total so far: %d",
                pageCount, pageEntities.size(), allUpdatedEntities.size());

            if (pageEntities.size() < pageSize) {
                TimberLogger.i(TAG, "Received less than page size for rollback, ending pagination");
                break;
            }

            if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getLast() != null) {
                after = fapiClient.getLastResponse().getLast();
            }else break;
        }

        TimberLogger.i(TAG, "Completed fetching updated " + entityClass.getSimpleName() + " for rollback: %d items in %d pages",
            allUpdatedEntities.size(), pageCount);

        return allUpdatedEntities;
    }

    private int updateDatabaseAfterRollback(List<T> entityList) {
        if (entityList == null || entityList.isEmpty()) return 0;

        int added = 0;
        int removed = 0;

        for (T entity : entityList) {
            try {
                if (isEntityDeleted(entity)) {
                    if (checkIfExisted(entity.getId())) {
                        removeEntity(entity);
                        removed++;
                    }
                } else {
                    addEntity(entity);
                    added++;
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error processing " + entityClass.getSimpleName() + " during rollback: %s", e.getMessage());
                continue;
            }
        }

        TimberLogger.i(TAG, "Rollback database update: %d added, %d removed", added, removed);
        return added;
    }

    private void updateStatesAfterRollback(List<?> updatedApiObjects) {
        if (updatedApiObjects == null || updatedApiObjects.isEmpty()) {
            return;
        }

        try {
            Object latest = updatedApiObjects.get(updatedApiObjects.size() - 1);
            List<String> latestInfo = makeSortList(latest);
            saveStateLastUpdated(latestInfo);
            TimberLogger.i(TAG, "Updated lastUpdated state after rollback: %s", latestInfo);

            saveStateHasMoreNewer(false);

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating states after rollback: %s", e.getMessage());
        }
    }

    public void updateOnChainTotal(Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(getEntityIndexName());
        fcdsl.addNewQuery().addNewTerms().addNewFields(OWNER).addNewValues(FidManager.getInstance().getLiveFid());
        fcdsl.getQuery().addNewEquals().addNewFields(ACTIVE).addNewValues(TRUE);
        fcdsl.setSize("0");

        FapiClient fapiClient = loadFapiClient();
        if(fapiClient==null)return;
        fapiClient.search(fcdsl);
        Long receivedTotal = fapiClient.getLastResponse().getTotal();
        if(receivedTotal!=null){
            saveStateTotal(receivedTotal);
            totalOnChain = receivedTotal;
        }
    }

    public FapiClient getFapiClient() {
        return loadFapiClient();
    }

    /**
     * Utility method to save an entity, commit, show a toast, set result, and finish the activity.
     */
    public void saveAndFinish(Activity activity, T entity, boolean addTotalOnChain) {
        addEntity(entity);
        Long totalOnChain = getTotalOnChain();
        if(totalOnChain == null) totalOnChain = 0L;
        if(addTotalOnChain)setTotalOnChain(totalOnChain + 1);
        commit();
        ToastUtils.makeText(activity, activity.getString(R.string.entity_saved_successfully, StringUtils.capitalize(entityName)));
        activity.setResult(Activity.RESULT_OK);
        activity.finish();
    }

    /**
     * Utility method to save multiple entities with sequential processing.
     * Uses abstract method for entity-specific processing logic.
     */
    public void saveAndFinish(Activity activity, List<T> entities) {
        processEntitiesSequentially(activity, entities, 0, 0);
    }

    /**
     * Abstract method for processing entities sequentially.
     * Subclasses should implement entity-specific logic like encryption, validation, etc.
     *
     * @param activity The activity context
     * @param entityList List of entities to process
     * @param index Current index being processed
     * @param savedCount Number of entities successfully saved so far
     */
    protected abstract void processEntitiesSequentially(Activity activity, List<T> entityList, int index, int savedCount);

    // Abstract methods that subclasses must implement
    protected abstract String getEntityIndexName();
    protected abstract Class<?> getApiObjectClass();
    protected abstract List<T> makeEntityDetails(List<?> apiObjects, boolean decryptDeleted, Context context);
    protected abstract List<String> makeSortList(Object apiObject);
    /**
     * Fetches all entities for the liveFid from the API using pagination.
     * Creates an FCDSL query with terms 'active=true' (if active) and equals 'owner=liveFid'.
     * Uses pagination with size=200 to fetch all results.
     *
     * @param context  Activity context for dialog operations
     * @param last     Starting point for pagination, null to fetch from beginning
     * @param active   Only fetch active entities
     * @param timeAsc  Sort in time ascending order
     * @param maxPages
     * @return List of all API objects, or null if failed
     */
    protected List<?> fetchEntities(Context context, List<String> last, Boolean active, boolean timeAsc, int maxPages) {
        FapiClient fapiClient = loadFapiClient();

        if (fapiClient == null) {
            ToastUtils.showError(context, context.getString(R.string.apip_client_not_available));
            TimberLogger.w(TAG, "Cannot fetch valid entities: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch valid entities: liveFid not set");
            return null;
        }

        List<Object> allEntityList = new ArrayList<>();
        final int pageSize = FreerApplication.DEFAULT_REQUEST_SIZE;
        int pageCount = 0;

        TimberLogger.i(TAG, "Starting to fetch all valid " + entityClass.getSimpleName() + " for liveFid: %s, starting from: %s", liveFid, last);
        List<String> after = last;
        while (true) {
            pageCount++;
            TimberLogger.d(TAG, "Fetching page %d with size %d", pageCount, pageSize);
            Fcdsl fcdsl = makeFcdsl(pageSize, after, active, timeAsc);
            List<?> pageEntityList = fetchPageEntities(fcdsl);

            if (pageEntityList == null) {
                TimberLogger.w(TAG, "Failed to fetch " + entityClass.getSimpleName() + " list on page %d", pageCount);
                return allEntityList;
            }

            if (pageEntityList.isEmpty()) {
                TimberLogger.i(TAG, "No more " + entityClass.getSimpleName() + " found, ending pagination at page %d", pageCount);
                break;
            }

            allEntityList.addAll(pageEntityList);

            TimberLogger.d(TAG, "Page %d returned %d " + entityClass.getSimpleName() + " items, total so far: %d",
                pageCount, pageEntityList.size(), allEntityList.size());

            if (pageEntityList.size() < pageSize) {
                if(!timeAsc)
                    saveStateHasMoreEarlier(Boolean.FALSE);
                else {
                    saveStateHasMoreNewer(Boolean.FALSE);
                }
                TimberLogger.i(TAG, "Received less than page size (%d < %d), ending pagination",
                    pageEntityList.size(), pageSize);
                break;
            }

            try {
                if (fapiClient.getLastResponse() != null) {

                    after = fapiClient.getLastResponse().getLast();

                    if (after == null || after.isEmpty()) {
                        TimberLogger.i(TAG, "No 'last' field in response, ending pagination");
                        break;
                    }
                    TimberLogger.d(TAG, "Got 'last' values for next page: %s", after);
                } else {
                    TimberLogger.w(TAG, "Cannot access response body for pagination info, ending");
                    break;
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error getting pagination info: %s", e.getMessage());
                break;
            }

            if (pageCount >= FreerApplication.DEFAULT_REQUEST_PAGE_COUNT) {
                if(active && !timeAsc)
                    saveStateHasMoreEarlier(Boolean.TRUE);

                TimberLogger.w(TAG, "Reached maximum page limit, stopping pagination");
                break;
            }
        }

        saveBestBlockInfo();

        TimberLogger.i(TAG, "Completed fetching valid " + entityClass.getSimpleName() + ": %d items total in %d pages",
            allEntityList.size(), pageCount);

        return allEntityList;
    }

    /**
     * Fetches a single page of entities from the API.
     *
     * @param fcdsl   The FCDSL query to execute
     * @return List of API objects for the page, or null if failed
     */
    public List<?> fetchPageEntities(Fcdsl fcdsl) {
        FapiClient fapiClient = loadFapiClient();

        if(fapiClient==null)
            return null;
        FapiResponse result = fapiClient.search(fcdsl);
        if(result == null || result.getData() == null){
            if(fapiClient.isDataNoFound())
                return new ArrayList<>();
            return null;
        }

        return ObjectUtils.objectToList(result.getData(), getApiObjectClass());
    }

    @NonNull
    public abstract Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc);


    /**
     * Refreshes the entire entity database by fetching all active entities from API
     * and replacing the local database contents.
     *
     * @param context Activity context for dialog operations
     * @return Number of entity items refreshed, or 0 if failed
     */
    public int freshEntityDB(Context context) {
        if (entityDB == null) {
            TimberLogger.w(TAG, "Cannot refresh " + entityName + ": database not available");
            return 0;
        }

        TimberLogger.i(TAG, "Starting to refresh " + entityName + " database for liveFid: %s", liveFid);
        saveStateLastUpdated(null);
        saveStateEarliestActive(null);

        try {
            // 1. Fetch all active entities from API
            List<?> allApiObjects = fetchEntitiesFromAPI(context, null, true, false, getEntityIndexName());

            if (allApiObjects == null) {
                TimberLogger.w(TAG, "Failed to fetch " + entityName + " from API, database not refreshed");
                return 0;
            }

            if (allApiObjects.isEmpty()) {
                TimberLogger.i(TAG, "No active " + entityName + " found on API");
            }

            // Convert to entity detail objects
            List<T> allEntityDetails = makeEntityDetails(allApiObjects, false,context );

            // 2. Clear the existing database
            TimberLogger.i(TAG, "Clearing existing " + entityName + " database");
            entityDB.clear();

            // 3. Save the new active entities list
            int savedCount = 0;
            if (!allEntityDetails.isEmpty()) {
                for (T entity : allEntityDetails) {
                    addEntity(entity);
                    savedCount++;
                }
            }

            // 4. Commit the changes
            entityDB.commit();

            TimberLogger.i(TAG, "Successfully refreshed " + entityName + " database: %d items", savedCount);
            return savedCount;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error refreshing " + entityName + " database: %s", e.getMessage());
            return 0;
        }
    }

    protected abstract List<?> fetchEntitiesFromAPI(Context context,
                                                    List<String> after, Boolean active, boolean timeAsc, String index);
    protected abstract void preprocessEntity(T entity);
    protected abstract boolean isEntityDeleted(T entity);
    protected abstract Long getEntityUpdateHeight(T entity);
    protected abstract Boolean isEntityOnChain(T entity);
    protected abstract List<T> searchFromList(String query, List<T> results);

    // ========================================
    // Failed Decryption Handling Framework
    // ========================================

    /**
     * Creates a failed decryption entity for items that could not be decrypted
     */
    protected abstract T createFailedDecryptionEntity(Object apiObject, Context context);

    protected abstract T createUndecryptedEntity(Context context, Object apiObject) ;

    /**
     * Gets the delete activity class for this entity type
     */
    protected abstract Class<?> getDeleteActivityClass();

    /**
     * Launches the appropriate delete activity for failed decryption entities
     */
    private void launchDeleteActivity(Activity activity, List<T> failedEntities) {
        try {
            android.content.Intent intent = new android.content.Intent(activity, getDeleteActivityClass());
            String entityListJson = com.fc.fc_ajdk.utils.JsonUtils.toJson(failedEntities);
            intent.putExtra(entityName + "List", entityListJson);
            intent.putExtra("isFailedDecryption", true);
            activity.startActivity(intent);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error launching delete activity for %s: %s", entityName, e.getMessage());
            ToastUtils.makeText(activity, activity.getString(R.string.toast_error_launching_delete));
        }
    }

    // ========================================
    // Local Deleted List Management
    // ========================================

    /**
     * Adds a deleted entity to the local deleted list and maintains the size limit
     *
     * @param entity The entity to add to the deleted list
     */
    public synchronized void addToLocalDeletedList(T entity) {
        if (entityDB == null || entity == null) {
            return;
        }

        try {
            // Add to the end of the list (most recent)
            entityDB.addToList(LOCAL_DELETED_LIST_NAME, entity);

            // Check and maintain size limit
            long currentSize = entityDB.getListSize(LOCAL_DELETED_LIST_NAME);
            if (currentSize > LOCAL_DELETED_LIST_LIMIT) {
                // Remove oldest entries (from the beginning)
                long itemsToRemove = currentSize - LOCAL_DELETED_LIST_LIMIT;
                List<Long> indicesToRemove = new ArrayList<>();
                for (long i = 0; i < itemsToRemove; i++) {
                    indicesToRemove.add(i);
                }
                entityDB.removeFromList(LOCAL_DELETED_LIST_NAME, indicesToRemove);
            }

            TimberLogger.i(TAG, "Added %s to localDeletedList, current size: %d", entityName, entityDB.getListSize(LOCAL_DELETED_LIST_NAME));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding %s to localDeletedList: %s", entityName, e.getMessage());
        }
    }

    /**
     * Adds multiple deleted entities to the local deleted list
     *
     * @param entities The list of entities to add to the deleted list
     */
    public synchronized void addToLocalDeletedList(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        try {
            // Add to the end of the list (most recent)
            entityDB.addAllToList(LOCAL_DELETED_LIST_NAME, entities);

            // Check and maintain size limit
            long currentSize = entityDB.getListSize(LOCAL_DELETED_LIST_NAME);
            if (currentSize > LOCAL_DELETED_LIST_LIMIT) {
                // Remove oldest entries (from the beginning)
                long itemsToRemove = currentSize - LOCAL_DELETED_LIST_LIMIT;
                List<Long> indicesToRemove = new ArrayList<>();
                for(int i = 0; i < itemsToRemove; i++) {
                    indicesToRemove.add((long) i);
                }
                entityDB.removeFromList(LOCAL_DELETED_LIST_NAME, indicesToRemove);
            }

            TimberLogger.i(TAG, "Added %s to localDeletedList, current size: %d", entityName, entityDB.getListSize(LOCAL_DELETED_LIST_NAME));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error adding %s to localDeletedList: %s", entityName, e.getMessage());
        }
    }

    /**
     * Gets a copy of the local deleted list
     *
     * @return A copy of the local deleted list
     */
    public synchronized List<T> getLocalDeletedList() {
        if (entityDB == null) {
            return new ArrayList<>();
        }

        try {
            return entityDB.getAllFromList(LOCAL_DELETED_LIST_NAME);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting localDeletedList for %s: %s", entityName, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Clears the local deleted list
     */
    public synchronized void clearLocalDeletedList() {
        if (entityDB == null) {
            return;
        }

        try {
            entityDB.clearList(LOCAL_DELETED_LIST_NAME);
            TimberLogger.i(TAG, "Cleared localDeletedList for %s", entityName);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error clearing localDeletedList for %s: %s", entityName, e.getMessage());
        }
    }

    /**
     * Gets the size of the local deleted list
     *
     * @return The number of items in the local deleted list
     */
    public synchronized long getLocalDeletedListSize() {
        if (entityDB == null) {
            return 0;
        }

        try {
            return entityDB.getListSize(LOCAL_DELETED_LIST_NAME);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting localDeletedList size for %s: %s", entityName, e.getMessage());
            return 0;
        }
    }

    /**
     * Removes a specific entity from the local deleted list
     *
     * @param entity The entity to remove from the deleted list
     * @return true if the entity was found and removed, false otherwise
     */
    public synchronized boolean removeFromLocalDeletedList(T entity) {
        if (entityDB == null || entity == null) {
            return false;
        }

        try {
            // Get the current list to find the entity's index
            List<T> currentList = entityDB.getAllFromList(LOCAL_DELETED_LIST_NAME);
            if (currentList == null || currentList.isEmpty()) {
                return false;
            }

            // Find the entity by ID and remove it
            for (int i = 0; i < currentList.size(); i++) {
                T listEntity = currentList.get(i);
                if (listEntity != null && entity.getId() != null && entity.getId().equals(listEntity.getId())) {
                    entityDB.removeFromList(LOCAL_DELETED_LIST_NAME, List.of((long) i));
                    TimberLogger.i(TAG, "Removed %s from localDeletedList at index %d", entityName, i);
                    return true;
                }
            }

            TimberLogger.w(TAG, "Entity with ID %s not found in localDeletedList", entity.getId());
            return false;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error removing %s from localDeletedList: %s", entityName, e.getMessage());
            return false;
        }
    }

    /**
     * Removes multiple entities from the local deleted list
     *
     * @param entities The list of entities to remove from the deleted list
     * @return Number of entities successfully removed
     */
    public synchronized int removeFromLocalDeletedList(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return 0;
        }

        int removedCount = 0;
        for (T entity : entities) {
            if (removeFromLocalDeletedList(entity)) {
                removedCount++;
            }
        }
        return removedCount;
    }

    /**
     * Deletes off-chain entities from the database and adds them to localDeletedList
     *
     * @param entities List of entities to check and delete if off-chain
     * @return List of remaining on-chain entities that were not deleted
     */
    public synchronized List<T> deleteOffChainEntities(List<T> entities) {
        List<T> offChainEntities = new ArrayList<>();
        List<T> onChainEntities = new ArrayList<>();

        // Separate off-chain and on-chain entities
        for (T entity : entities) {
            Boolean onChain = isEntityOnChain(entity);
            if (Boolean.FALSE.equals(onChain)) {
                // Off-chain or unknown status - treat as off-chain
                offChainEntities.add(entity);
            } else {
                // On-chain entity
                onChainEntities.add(entity);
            }
        }

        // Delete off-chain entities from database and add to localDeletedList
        if (!offChainEntities.isEmpty()) {
            removeEntities(offChainEntities);
            addToLocalDeletedList(offChainEntities);
            TimberLogger.i(TAG, "Deleted %d off-chain %s from database", offChainEntities.size(), entityName);
        }

        return onChainEntities;
    }

    public Object getSetting(String key){
        return entityDB.getSetting(key);
    }

    public void setSetting(String key,Object value){
        entityDB.putSetting(key,value);
    }
}
