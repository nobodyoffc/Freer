package com.fc.freer.manager;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.db.MMKVDB;
import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.HashMap;
import java.util.Map;

public class DatabaseManager {
    public static final String LAST_UPDATED = "lastUpdated";
    public static final String EARLIEST_ACTIVE = "earliestActive";
    public static final String HAS_MORE_EARLIER = "hasMoreEarlier";
    public static final String HAS_MORE_NEWER = "hasMoreNewer";
    public static final String EARLIEST_VALID = "earliestValid";
    public static final String TOTAL = "total";
    public static final String BEST_HEIGHT = "bestHeight";
    public static final String BEST_BLOCK_ID = "bestBlockId";
    private static DatabaseManager instance;
//    private final Context context;
    private String currentPasswordName;
    private final Map<String, LocalDB<? extends FcEntity>> databases;
    private final Map<String, Class<? extends FcEntity>> databaseClasses = new HashMap<>();

    private DatabaseManager() {
//        this.context = context.getApplicationContext();
        this.databases = new HashMap<>();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    public void setCurrentPasswordName(String passwordName) {
        if (currentPasswordName != null && !currentPasswordName.equals(passwordName)) {
            TimberLogger.d("DatabaseManager", "Switching password from " + currentPasswordName + " to " + passwordName);
            closeCurrentDatabases();
        }
        currentPasswordName = passwordName;
    }

    private void closeCurrentDatabases() {
        for (LocalDB<? extends FcEntity> db : databases.values()) {
            if (db != null) {
                try {
                    db.close();
                } catch (Exception e) {
                    TimberLogger.e("DatabaseManager", "Error closing database: " + e.getMessage());
                }
            }
        }
        databases.clear();
        databaseClasses.clear();
    }

    public <T extends FcEntity> LocalDB<T> createEntityDatabase(String fid, String dbName, String passwordName, Class<T> entityClass, Class<T> tClass) {
        TimberLogger.d("DatabaseManager", "Creating new database for " + dbName + " with password " + passwordName);
        // Use MMKVDB for much better performance (~100x faster than HawkDB)
        LocalDB<T> db = new MMKVDB<>(tClass);
        String dbKey = db.initialize(passwordName, fid, null, null, dbName);

        databases.put(dbKey, db);
        databaseClasses.put(dbKey, entityClass);
        TimberLogger.d("DatabaseManager", "Created MMKVDB instance with key: " + dbKey);
        return db;
    }

    /**
     * Gets or creates an encrypted database for the specified parameters.
     *
     * @param fid
     * @param entityClass The entity class for the database
     * @param tClass
     * @return The encrypted database
     */
    public <T extends FcEntity> LocalDB<T> getEntityDatabase(String fid, Class<T> entityClass, Class<T> tClass) {
        String dbKey = makeDatabaseKey(currentPasswordName,fid , entityClass.getSimpleName());
        
        // Check if database already exists for this password
        if (databases.containsKey(dbKey)) {
            return (LocalDB<T>) databases.get(dbKey);
        }
        
        // Create a new encrypted database for this password
        return createEntityDatabase(fid, entityClass.getSimpleName(), currentPasswordName, entityClass, tClass);
    }

    public Class<? extends FcEntity> getDatabaseClass(String fid,String dbName) {
        return databaseClasses.get(makeDatabaseKey(currentPasswordName, fid, dbName));
    }

    public String makeDatabaseKey(String passwordName, String fid, String dbName) {
        return passwordName + "_" + fid +"_" + dbName;
    }

    public void clearDatabase(String fid,String dbName) {
        String dbKey = makeDatabaseKey(currentPasswordName, fid, dbName);
        LocalDB<?> db = databases.get(dbKey);
        if (db != null) {
            TimberLogger.d("DatabaseManager", "Clearing database: " + dbName);
            db.clearDB();
            databases.remove(dbKey);
        }
    }

    public void clearAllDatabases() {
        TimberLogger.d("DatabaseManager", "Clearing all databases for password: " + currentPasswordName);
        for (String dbKey : databases.keySet()) {
            if (dbKey.startsWith(currentPasswordName)) {
                LocalDB<?> db = databases.get(dbKey);
                if (db != null) {
                    db.clearDB();
                }
            }
        }
        databases.clear();
    }

    public void removeAllDatabases() {
        if(currentPasswordName==null)return;
        TimberLogger.d("DatabaseManager", "Removing all databases for password: " + currentPasswordName);
        databases.entrySet().removeIf(entry -> {
            String dbKey = entry.getKey();
            if (dbKey.startsWith(currentPasswordName)) {
                LocalDB<?> db = entry.getValue();
                if (db != null) {
                    try {
                        db.close();
                        db.clearDB();
                    } catch (Exception e) {
                        TimberLogger.e("DatabaseManager", "Error closing database: " + e.getMessage());
                    }
                }
                databaseClasses.remove(dbKey);
                return true;
            }
            return false;
        });
    }

    public void removeAllDatabasesOfFid(String fid) {
        if(fid==null||currentPasswordName==null)return;
        TimberLogger.d("DatabaseManager", "Removing all databases for FID: " + fid);
        databases.entrySet().removeIf(entry -> {
            String dbKey = entry.getKey();
            if (dbKey.startsWith(currentPasswordName+"_"+fid)) {
                LocalDB<?> db = entry.getValue();
                if (db != null) {
                    try {
                        db.clearDB();
                    } catch (Exception e) {
                        TimberLogger.e("DatabaseManager", "Error closing database: " + e.getMessage());
                    }
                }
                databaseClasses.remove(dbKey);
                return true;
            }
            return false;
        });
    }

    public String getCurrentPasswordName() {
        return currentPasswordName;
    }

    /**
     * Shuts down the DatabaseManager and properly closes all databases.
     * This method should be called when the application is terminating.
     * It does not delete any data, it just releases resources.
     */
    public static void shutdown() {
        if (instance != null) {
            TimberLogger.d("DatabaseManager", "Shutting down DatabaseManager");
            for (LocalDB<?> db : instance.databases.values()) {
                db.close();
            }
            instance = null;
        }
    }
} 