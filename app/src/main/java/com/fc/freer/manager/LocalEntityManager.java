package com.fc.freer.manager;

import static com.fc.fc_ajdk.db.LocalDB.LOCAL_DELETED_LIST_NAME;

import android.app.Activity;
import android.content.Context;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.utils.ToastUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for managing local-only entities (not synced with blockchain).
 * This is a simplified version of FcManager without blockchain sync features.
 * 
 * Use this for entities like Hat that are stored locally and optionally
 * synced to external storage (like FAPI.DISK) without blockchain tracking.
 *
 * @param <T> The entity type, must extend FcEntity
 */
public abstract class LocalEntityManager<T extends FcEntity> {
    private static final String TAG = "LocalEntityManager";
    public static final int LOCAL_DELETED_LIST_LIMIT = 500;

    // Settings keys
    public static final String LAST_BACKUP_TIME = "lastBackupTime";

    protected LocalDB<T> entityDB;
    protected String liveFid;
    protected Class<T> entityClass;
    protected String entityName;

    protected LocalEntityManager(Class<T> entityClass, String entityName) {
        this.entityClass = entityClass;
        if (entityName != null) {
            this.entityName = entityName.toLowerCase();
        } else {
            this.entityName = entityClass.getSimpleName().toLowerCase();
        }
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
        if (entityDB == null) {
            TimberLogger.w(TAG, "No " + entityClass.getSimpleName() + " database available from DatabaseManager when initiating " + getClass().getSimpleName() + ".");
        }
        preprocessDB();
    }

    /**
     * Called after database initialization. Override to perform any setup.
     */
    protected void preprocessDB() {
        // Default: no preprocessing needed
    }

    // ========================================
    // Core CRUD Operations
    // ========================================

    /**
     * Gets an entity object by its ID.
     *
     * @param id The ID of the entity object to get
     * @return The entity object, or null if not found
     */
    public T getEntityById(String id) {
        if (entityDB == null) return null;
        return entityDB.get(id);
    }

    /**
     * Adds an entity object to the database.
     *
     * @param entity The entity object to add
     */
    public void addEntity(T entity) {
        if (entityDB == null || entity == null) return;
        preprocessEntity(entity);
        entityDB.put(entity.getId(), entity);
    }

    /**
     * Removes an entity object from the database.
     *
     * @param entity The entity object to remove
     */
    public void removeEntity(T entity) {
        if (entityDB == null || entity == null) return;
        entityDB.remove(entity.getId());
    }

    /**
     * Removes multiple entity objects from the database.
     *
     * @param entities The list of entity objects to remove
     */
    public void removeEntities(List<T> entities) {
        if (entityDB == null || entities == null) return;
        entityDB.remove(entities);
    }

    /**
     * Updates an existing entity in the database.
     *
     * @param entity The entity object to update
     */
    public void updateEntity(T entity) {
        if (entityDB == null || entity == null) return;
        preprocessEntity(entity);
        entityDB.put(entity.getId(), entity);
    }

    /**
     * Commits changes to the database.
     */
    public void commit() {
        if (entityDB != null) {
            entityDB.commit();
        }
    }

    /**
     * Checks if an entity with the given ID already exists in the database.
     *
     * @param id The ID to check
     * @return true if the entity exists, false otherwise
     */
    public boolean checkIfExisted(String id) {
        if (entityDB == null) return false;
        return entityDB.get(id) != null;
    }

    // ========================================
    // Pagination and Search
    // ========================================

    /**
     * Gets a paginated list of entity objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of entity objects for the requested page
     */
    public List<T> getPaginatedEntities(int pageSize, String lastID, boolean fromEnd) {
        if (entityDB == null) return new ArrayList<>();
        return entityDB.getList(pageSize, lastID, null, false, null, null, false, fromEnd);
    }

    /**
     * Searches for entity objects by searchable fields using database-level search.
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
            TimberLogger.e(TAG, "Error performing database search: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Gets the size of the entity database.
     *
     * @return The number of entities in the database
     */
    public long getEntityDBSize() {
        if (entityDB == null) return 0;
        return entityDB.getSize();
    }

    // ========================================
    // Settings Storage
    // ========================================

    /**
     * Gets a setting value by key.
     *
     * @param key The setting key
     * @return The setting value, or null if not found
     */
    public Object getSetting(String key) {
        if (entityDB == null) return null;
        return entityDB.getSetting(key);
    }

    /**
     * Sets a setting value.
     *
     * @param key   The setting key
     * @param value The setting value
     */
    public void setSetting(String key, Object value) {
        if (entityDB != null) {
            entityDB.putSetting(key, value);
        }
    }

    /**
     * Gets the last backup time.
     *
     * @return The last backup timestamp, or 0 if never backed up
     */
    public long getLastBackupTime() {
        Object value = getSetting(LAST_BACKUP_TIME);
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Sets the last backup time.
     *
     * @param timestamp The backup timestamp
     */
    public void setLastBackupTime(long timestamp) {
        setSetting(LAST_BACKUP_TIME, timestamp);
    }

    // ========================================
    // Export/Import Support
    // ========================================

    /**
     * Exports entities to a stream in JSONL format.
     * First line is the ExportMeta, followed by one entity per line.
     *
     * @param out      The output stream to write to
     * @param appName  The application name for the export metadata
     * @throws IOException If an I/O error occurs
     */
    public void exportToStream(OutputStream out, String appName) throws IOException {
        List<T> allEntities = getPaginatedEntities(Integer.MAX_VALUE, null, false);
        exportToStream(out, appName, allEntities);
    }

    /**
     * Exports specific entities to a stream in JSONL format.
     *
     * @param out      The output stream to write to
     * @param appName  The application name for the export metadata
     * @param entities The entities to export
     * @throws IOException If an I/O error occurs
     */
    public void exportToStream(OutputStream out, String appName, List<T> entities) throws IOException {
        if (out == null || entities == null) return;

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            // Write metadata header
            ExportMeta meta = ExportMeta.create(liveFid, entities.size(), appName, entityName);
            writer.write(meta.toJson());
            writer.newLine();

            // Write each entity as a JSON line
            for (T entity : entities) {
                String json = JsonUtils.toJson(entity);
                writer.write(json);
                writer.newLine();
            }
        }
    }

    /**
     * Imports entities from a stream in JSONL format.
     *
     * @param in The input stream to read from
     * @return The number of entities imported
     * @throws IOException If an I/O error occurs
     */
    public int importFromStream(InputStream in) throws IOException {
        if (in == null) return 0;

        int importedCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean isFirstLine = true;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                if (isFirstLine) {
                    // First line is metadata, skip it
                    isFirstLine = false;
                    continue;
                }

                try {
                    T entity = JsonUtils.fromJson(line, entityClass);
                    if (entity != null && entity.getId() != null) {
                        addEntity(entity);
                        importedCount++;
                    }
                } catch (Exception e) {
                    TimberLogger.w(TAG, "Failed to parse entity: %s", e.getMessage());
                }
            }
        }

        if (importedCount > 0) {
            commit();
        }
        return importedCount;
    }

    /**
     * Parses the export metadata from an input stream without consuming the entities.
     *
     * @param in The input stream to read from
     * @return The ExportMeta, or null if parsing failed
     * @throws IOException If an I/O error occurs
     */
    public ExportMeta parseExportMeta(InputStream in) throws IOException {
        if (in == null) return null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String firstLine = reader.readLine();
            if (firstLine != null && !firstLine.trim().isEmpty()) {
                return ExportMeta.fromJson(firstLine);
            }
        }
        return null;
    }

    // ========================================
    // Local Deleted List Management
    // ========================================

    /**
     * Adds a deleted entity to the local deleted list and maintains the size limit.
     *
     * @param entity The entity to add to the deleted list
     */
    public synchronized void addToLocalDeletedList(T entity) {
        if (entityDB == null || entity == null) {
            return;
        }

        try {
            entityDB.addToList(LOCAL_DELETED_LIST_NAME, entity);

            long currentSize = entityDB.getListSize(LOCAL_DELETED_LIST_NAME);
            if (currentSize > LOCAL_DELETED_LIST_LIMIT) {
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
     * Adds multiple deleted entities to the local deleted list.
     *
     * @param entities The list of entities to add to the deleted list
     */
    public synchronized void addToLocalDeletedList(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }

        try {
            entityDB.addAllToList(LOCAL_DELETED_LIST_NAME, entities);

            long currentSize = entityDB.getListSize(LOCAL_DELETED_LIST_NAME);
            if (currentSize > LOCAL_DELETED_LIST_LIMIT) {
                long itemsToRemove = currentSize - LOCAL_DELETED_LIST_LIMIT;
                List<Long> indicesToRemove = new ArrayList<>();
                for (int i = 0; i < itemsToRemove; i++) {
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
     * Gets a copy of the local deleted list.
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
     * Clears the local deleted list.
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
     * Gets the size of the local deleted list.
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
     * Removes a specific entity from the local deleted list.
     *
     * @param entity The entity to remove from the deleted list
     * @return true if the entity was found and removed, false otherwise
     */
    public synchronized boolean removeFromLocalDeletedList(T entity) {
        if (entityDB == null || entity == null) {
            return false;
        }

        try {
            List<T> currentList = entityDB.getAllFromList(LOCAL_DELETED_LIST_NAME);
            if (currentList == null || currentList.isEmpty()) {
                return false;
            }

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

    // ========================================
    // Utility Methods
    // ========================================

    /**
     * Saves an entity, commits, shows a toast, and finishes the activity.
     *
     * @param activity The activity context
     * @param entity   The entity to save
     */
    public void saveAndFinish(Activity activity, T entity) {
        addEntity(entity);
        commit();
        ToastUtils.makeText(activity, activity.getString(R.string.entity_saved_successfully, capitalize(entityName)));
        activity.setResult(Activity.RESULT_OK);
        activity.finish();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public String getLiveFid() {
        return liveFid;
    }

    public void setLiveFid(String liveFid) {
        this.liveFid = liveFid;
    }

    public String getEntityName() {
        return entityName;
    }

    // ========================================
    // Abstract Methods
    // ========================================

    /**
     * Preprocesses an entity before adding or updating.
     * Called by addEntity() and updateEntity().
     *
     * @param entity The entity to preprocess
     */
    protected abstract void preprocessEntity(T entity);

    /**
     * Checks if an entity is marked as deleted.
     *
     * @param entity The entity to check
     * @return true if deleted, false otherwise
     */
    protected abstract boolean isEntityDeleted(T entity);

    /**
     * Filters search results from a list.
     * Called by searchEntities() after database search.
     *
     * @param query   The search query
     * @param results The results from database search
     * @return Filtered results
     */
    protected abstract List<T> searchFromList(String query, List<T> results);
}
