package com.fc.fc_ajdk.db;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.List;
import java.util.Map;

/**
 * Utility class for migrating data from HawkDB to MMKVDB.
 * This allows seamless transition for existing users without data loss.
 */
public class DBMigrationUtil {
    private static final String TAG = "DBMigrationUtil";

    /**
     * Migrates all data from a HawkDB instance to an MMKVDB instance.
     *
     * @param hawkDB Source HawkDB instance
     * @param mmkvDB Destination MMKVDB instance
     * @param <T> Entity type
     * @return true if migration was successful, false otherwise
     */
    public static <T extends FcEntity> boolean migrateDatabase(HawkDB<T> hawkDB, MMKVDB<T> mmkvDB) {
        try {
            TimberLogger.d(TAG, "Starting database migration from HawkDB to MMKVDB");

            // Check if source database is empty
            if (hawkDB.isEmpty()) {
                TimberLogger.d(TAG, "Source HawkDB is empty, no migration needed");
                return true;
            }

            // Check if destination already has data (avoid re-migration)
            if (!mmkvDB.isEmpty()) {
                TimberLogger.w(TAG, "Destination MMKVDB already has data, skipping migration");
                return false;
            }

            long startTime = System.currentTimeMillis();

            // 1. Migrate main items
            Map<String, T> allItems = hawkDB.getAll();
            if (allItems != null && !allItems.isEmpty()) {
                TimberLogger.d(TAG, "Migrating %d items", allItems.size());
                mmkvDB.put(allItems);
            }

            // 2. Migrate settings
            Map<String, String> allSettings = hawkDB.getAllSettings();
            if (allSettings != null && !allSettings.isEmpty()) {
                TimberLogger.d(TAG, "Migrating %d settings", allSettings.size());
                for (Map.Entry<String, String> entry : allSettings.entrySet()) {
                    mmkvDB.putSetting(entry.getKey(), entry.getValue());
                }
            }

            // 3. Migrate state
            Map<String, String> allState = hawkDB.getAllState();
            if (allState != null && !allState.isEmpty()) {
                TimberLogger.d(TAG, "Migrating %d state entries", allState.size());
                for (Map.Entry<String, String> entry : allState.entrySet()) {
                    mmkvDB.putState(entry.getKey(), entry.getValue());
                }
            }

            // 4. Migrate metadata
            Map<String, Object> metaMap = hawkDB.getMetaMap();
            if (metaMap != null && !metaMap.isEmpty()) {
                TimberLogger.d(TAG, "Migrating %d metadata entries", metaMap.size());
                for (Map.Entry<String, Object> entry : metaMap.entrySet()) {
                    mmkvDB.putMeta(entry.getKey(), entry.getValue());
                }
            }

            // 5. Commit all changes
            mmkvDB.commit();

            long duration = System.currentTimeMillis() - startTime;
            TimberLogger.d(TAG, "Migration completed successfully in %d ms", duration);
            TimberLogger.d(TAG, "Migrated %d items from HawkDB to MMKVDB", allItems.size());

            return true;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Migration failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Checks if migration is needed by comparing HawkDB and MMKVDB states.
     *
     * @param hawkDB Source HawkDB instance
     * @param mmkvDB Destination MMKVDB instance
     * @param <T> Entity type
     * @return true if migration is needed (HawkDB has data, MMKVDB is empty)
     */
    public static <T extends FcEntity> boolean isMigrationNeeded(HawkDB<T> hawkDB, MMKVDB<T> mmkvDB) {
        try {
            // Migration is needed if:
            // 1. HawkDB has data AND
            // 2. MMKVDB is empty
            return !hawkDB.isEmpty() && mmkvDB.isEmpty();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error checking migration status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates that the migration was successful by comparing data.
     *
     * @param hawkDB Source HawkDB instance
     * @param mmkvDB Destination MMKVDB instance
     * @param <T> Entity type
     * @return true if validation passed
     */
    public static <T extends FcEntity> boolean validateMigration(HawkDB<T> hawkDB, MMKVDB<T> mmkvDB) {
        try {
            TimberLogger.d(TAG, "Validating migration...");

            // Check if sizes match
            int hawkSize = hawkDB.getSize();
            int mmkvSize = mmkvDB.getSize();

            if (hawkSize != mmkvSize) {
                TimberLogger.e(TAG, "Validation failed: Size mismatch (HawkDB: %d, MMKVDB: %d)",
                    hawkSize, mmkvSize);
                return false;
            }

            // Check if ID lists match
            List<String> hawkIds = hawkDB.getIdList();
            List<String> mmkvIds = mmkvDB.getIdList();

            if (!hawkIds.equals(mmkvIds)) {
                TimberLogger.e(TAG, "Validation failed: ID list mismatch");
                return false;
            }

            TimberLogger.d(TAG, "Validation passed: %d items successfully migrated", mmkvSize);
            return true;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Validation error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Performs a complete migration with validation.
     *
     * @param hawkDB Source HawkDB instance
     * @param mmkvDB Destination MMKVDB instance
     * @param <T> Entity type
     * @return true if migration and validation both succeeded
     */
    public static <T extends FcEntity> boolean migrateAndValidate(HawkDB<T> hawkDB, MMKVDB<T> mmkvDB) {
        boolean migrationSuccess = migrateDatabase(hawkDB, mmkvDB);
        if (!migrationSuccess) {
            return false;
        }

        boolean validationSuccess = validateMigration(hawkDB, mmkvDB);
        if (!validationSuccess) {
            TimberLogger.e(TAG, "Migration completed but validation failed!");
            return false;
        }

        return true;
    }
}
