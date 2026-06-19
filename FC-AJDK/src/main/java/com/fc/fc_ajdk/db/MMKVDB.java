package com.fc.fc_ajdk.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.MapQueue;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * High-performance MMKV-based implementation of LocalDB interface.
 * MMKV is ~100x faster than SharedPreferences and supports efficient batch operations.
 * No encryption overhead as encryption is handled at the application level.
 *
 * @param <T> The type of entity to store, must extend FcEntity
 */
public class MMKVDB<T extends FcEntity> implements LocalDB<T> {
    private static final String TAG = "MMKVDB";
    private static final String SETTINGS_MAP = "settings";
    public static final String ITEMS = "items";
    public static final String ID_LIST = "id_list";
    public static final String META_MAP = "meta";
    public static final String STATE_MAP = "state";
    public static final String MAP_TYPES = "map_types";
    public static final String LOCAL_DELETED_LIST_NAME = "localDeletedList";

    public static final int QUEUE_SIZE = 200;
    private volatile boolean isClosed = false;

    // MMKV instance for this database
    private MMKV mmkv;
    private String mmkvId;
    private final Class<T> tclass;
    private final Gson gson;

    // Add read-write lock for thread safety
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    // Thread-local temporary variables for pagination
    private final ThreadLocal<Long> tempIndex = new ThreadLocal<>();
    private final ThreadLocal<String> tempId = new ThreadLocal<>();

    // Main storage - in-memory cache
    private final MapQueue<String, T> itemMap = new MapQueue<>(QUEUE_SIZE);
    private final List<String> idList = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> stateMap = new ConcurrentHashMap<>();

    // Named maps for additional storage
    private final Map<String, MapQueue<Object, Object>> namedMapsMap = new ConcurrentHashMap<>();
    private final Map<String, String> mapTypes = new ConcurrentHashMap<>();
    private final Map<String, String> listTypes = new ConcurrentHashMap<>();

    private Map<String, String> metaMap;

    public MMKVDB(Class<T> tClass) {
        this.tclass = tClass;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    @Override
    public String initialize(String passwordName, String fid, String sid, String dbPath, String dbName) {
        if (isClosed) {
            this.isClosed = false;
        }

        // Create a unique MMKV ID for this database instance
        this.mmkvId = createMMKVId(passwordName, fid, sid, dbName);

        // Initialize MMKV with the unique ID
        // MMKV.SINGLE_PROCESS_MODE for better performance if not shared across processes
        this.mmkv = MMKV.mmkvWithID(mmkvId, MMKV.SINGLE_PROCESS_MODE);

        // Load ID list
        loadIdList();

        // Load metadata and settings
        loadMetaMap();
        loadMapTypeMap();
        loadStateMap();

        createMap(SETTINGS_MAP, Object.class);
        createList(LOCAL_DELETED_LIST_NAME, tclass);

        saveMetaMap();

        TimberLogger.d(TAG, "Initialized MMKVDB with ID: %s", mmkvId);
        return mmkvId;
    }

    private void loadIdList() {
        String json = mmkv.decodeString(ID_LIST);
        if (json != null) {
            List<String> savedIdList = gson.fromJson(json, new TypeToken<List<String>>(){}.getType());
            if (savedIdList != null) {
                idList.clear();
                idList.addAll(savedIdList);
            }
        }
    }

    private Map<String, String> loadMapTypeMap() {
        String storedMapTypes = metaMap.get(MAP_TYPES);
        if (storedMapTypes != null) {
            Map<String, String> map = JsonUtils.jsonToMap(storedMapTypes, String.class, String.class);
            if (map != null) {
                mapTypes.putAll(map);
                return map;
            }
        }
        return null;
    }

    private void loadMetaMap() {
        String json = mmkv.decodeString(META_MAP);
        if (json != null) {
            metaMap = gson.fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
        }
        if (metaMap == null) {
            metaMap = new HashMap<>();
        }
    }

    private void loadStateMap() {
        String json = mmkv.decodeString(STATE_MAP);
        if (json != null) {
            Map<String, Object> stateMapFromStorage = gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
            stateMap.putAll(stateMapFromStorage != null ? stateMapFromStorage : new HashMap<>());
        }
    }

    /**
     * Creates a unique MMKV ID based on passwordName, fid, sid, and dbName
     */
    private String createMMKVId(String passwordName, String fid, String sid, String dbName) {
        StringBuilder id = new StringBuilder();
        if (passwordName != null && !passwordName.isEmpty()) {
            id.append(passwordName).append("_");
        }
        if (fid != null && !fid.isEmpty()) {
            id.append(fid).append("_");
        }
        if (sid != null && !sid.isEmpty()) {
            id.append(sid).append("_");
        }
        if (dbName != null && !dbName.isEmpty()) {
            id.append(dbName.toLowerCase()).append("_");
        }
        String result = id.toString();
        if (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        TimberLogger.d(TAG, "Created MMKV ID: %s with fid=%s, sid=%s, dbName=%s", result, fid, sid, dbName);
        return result;
    }

    @NonNull
    private String getItemKey(String key) {
        return ITEMS + "_" + key;
    }

    @Override
    public void put(String key, T value) {
        writeLock.lock();
        try {
            boolean isNew = !idList.contains(key);

            // Store item in cache
            itemMap.put(key, value);

            // Store item in MMKV
            String json = gson.toJson(value);
            mmkv.encode(getItemKey(key), json);

            // Add to ID list if new
            if (isNew) {
                idList.add(key);
                saveIdList();
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public T get(String key) {
        T value;
        readLock.lock();
        try {
            // Try to get from cache first
            value = itemMap.get(key);
            if (value == null) {
                // If not in cache, load from MMKV
                String json = mmkv.decodeString(getItemKey(key));
                if (json != null) {
                    value = gson.fromJson(json, tclass);
                    if (value != null) {
                        itemMap.put(key, value);
                    }
                }
            }
        } finally {
            readLock.unlock();
        }
        return value;
    }

    @Override
    public List<T> get(List<String> keys) {
        readLock.lock();
        try {
            List<T> values = new ArrayList<>();

            for (String key : keys) {
                T value = itemMap.get(key);
                if (value == null) {
                    String json = mmkv.decodeString(getItemKey(key));
                    if (json != null) {
                        value = gson.fromJson(json, tclass);
                        if (value != null) {
                            itemMap.put(key, value);
                        }
                    }
                }
                if (value != null) {
                    values.add(value);
                }
            }

            return values;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void remove(String key) {
        writeLock.lock();
        try {
            // Remove from ID list
            idList.remove(key);
            saveIdList();

            // Remove from cache and storage
            itemMap.remove(key);
            mmkv.removeValueForKey(getItemKey(key));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void remove(List<T> list) {
        if (list == null || list.isEmpty()) return;

        writeLock.lock();
        try {
            for (T item : list) {
                if (item == null) continue;

                String key = item.getId();
                if (key == null) continue;

                // Remove from ID list
                idList.remove(key);

                // Remove from cache and storage
                itemMap.remove(key);
                mmkv.removeValueForKey(getItemKey(key));
            }
            saveIdList();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void saveIdList() {
        String json = gson.toJson(idList);
        mmkv.encode(ID_LIST, json);
    }

    @Override
    public void saveStateMap() {
        String json = gson.toJson(stateMap);
        mmkv.encode(STATE_MAP, json);
    }

    private void saveMetaMap() {
        String json = gson.toJson(metaMap);
        mmkv.encode(META_MAP, json);
    }

    @Override
    public void commit() {
        // MMKV writes are synchronous and immediately persisted
        // This method can trigger a sync if needed
        mmkv.sync();
    }

    @Override
    public void close() {
        if (!isClosed) {
            saveIdList();
            saveStateMap();
            mmkv.sync();
            isClosed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public long getTempIndex() {
        Long value = tempIndex.get();
        return value != null ? value : 0L;
    }

    @Override
    public String getTempId() {
        return tempId.get();
    }

    @Override
    public Map<String, T> getItemMap() {
        return getAll();
    }

    @Override
    public List<String> getIdList() {
        return new ArrayList<>(idList);
    }

    @Override
    public Map<String, Object> getMetaMap() {
        String json = mmkv.decodeString(META_MAP);
        if (json != null) {
            return gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
        }
        return new HashMap<>();
    }

    @Override
    public Map<String, Object> getSettingsMap() {
        String json = mmkv.decodeString(SETTINGS_MAP);
        if (json != null) {
            return gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
        }
        return new HashMap<>();
    }

    @Override
    public Map<String, Object> getStateMap() {
        String json = mmkv.decodeString(STATE_MAP);
        if (json != null) {
            return gson.fromJson(json, new TypeToken<Map<String, Object>>(){}.getType());
        }
        return new HashMap<>();
    }

    @Override
    public void putSetting(String key, Object value) {
        if (key == null) {
            return;
        }

        writeLock.lock();
        try {
            String stringValue;

            if (value == null) {
                stringValue = null;
            } else if (value instanceof String) {
                stringValue = (String) value;
            } else if (value instanceof Number || value instanceof Boolean) {
                stringValue = value.toString();
            } else {
                stringValue = gson.toJson(value);
            }

            Map<String, Object> settingsMap = getSettingsMap();
            settingsMap.put(key, stringValue);
            String json = gson.toJson(settingsMap);
            mmkv.encode(SETTINGS_MAP, json);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeSetting(String key) {
        if (key == null) {
            return;
        }

        writeLock.lock();
        try {
            Map<String, Object> settingsMap = getSettingsMap();
            settingsMap.remove(key);
            String json = gson.toJson(settingsMap);
            mmkv.encode(SETTINGS_MAP, json);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeAllSettings() {
        writeLock.lock();
        try {
            mmkv.encode(SETTINGS_MAP, "{}");
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Map<String, String> getAllSettings() {
        readLock.lock();
        try {
            Map<String, Object> settingsMap = getSettingsMap();
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : settingsMap.entrySet()) {
                result.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : null);
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Object getSetting(String key) {
        if (key == null) {
            return null;
        }
        readLock.lock();
        try {
            Map<String, Object> settingsMap = getSettingsMap();
            return settingsMap.get(key);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void putState(String key, Object value) {
        writeLock.lock();
        try {
            stateMap.put(key, value);
            saveStateMap();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeState(String key) {
        writeLock.lock();
        try {
            stateMap.remove(key);
            saveStateMap();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void clearAllState() {
        writeLock.lock();
        try {
            stateMap.clear();
            saveStateMap();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Map<String, String> getAllState() {
        readLock.lock();
        try {
            Map<String, String> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : stateMap.entrySet()) {
                result.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : null);
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Object getState(String key) {
        readLock.lock();
        try {
            return stateMap.get(key);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public int getIndexById(String id) {
        return idList.indexOf(id);
    }

    @Override
    public String getIdByIndex(int index) {
        if (index < 0 || index >= idList.size()) {
            return null;
        }
        return idList.get(index);
    }

    @Override
    public T getByIndex(int index) {
        String id = getIdByIndex(index);
        return id != null ? get(id) : null;
    }

    @Override
    public int getSize() {
        return idList.size();
    }

    @Override
    public boolean isEmpty() {
        return idList.isEmpty();
    }

    @Override
    public Object getMeta(String key) {
        readLock.lock();
        try {
            TimberLogger.d(TAG, "Getting meta with key: %s", key);
            Map<String, Object> metaMap = getMetaMap();
            Object value = metaMap.get(key);
            TimberLogger.d(TAG, "Retrieved meta value: %s", value);
            return value;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void putMeta(String key, Object value) {
        writeLock.lock();
        try {
            TimberLogger.d(TAG, "Putting meta with key: %s, value: %s", key, value);
            Map<String, Object> metaMap = getMetaMap();
            metaMap.put(key, value);
            String json = gson.toJson(metaMap);
            mmkv.encode(META_MAP, json);
            // Update local reference
            this.metaMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : metaMap.entrySet()) {
                this.metaMap.put(entry.getKey(), entry.getValue().toString());
            }
            TimberLogger.d(TAG, "Updated metaMap: %s", metaMap);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeMeta(String key) {
        writeLock.lock();
        try {
            Map<String, Object> metaMap = getMetaMap();
            metaMap.remove(key);
            String json = gson.toJson(metaMap);
            mmkv.encode(META_MAP, json);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            // Clear in-memory cache
            itemMap.clear();

            // Clear ID list
            for (String key : idList) {
                mmkv.removeValueForKey(getItemKey(key));
            }
            idList.clear();

            // Save cleared ID list
            saveIdList();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void clearDB() {
        writeLock.lock();
        try {
            // Clear all items
            for (String key : idList) {
                mmkv.removeValueForKey(getItemKey(key));
            }

            // Clear ID list
            idList.clear();
            mmkv.removeValueForKey(ID_LIST);

            // Clear in-memory cache
            itemMap.clear();

            loadMetaMap();
            loadMapTypeMap();

            // Clear all named maps
            for (String mapName : mapTypes.keySet()) {
                String keysJson = mmkv.decodeString(getMapKeysKey(mapName));
                if (keysJson != null) {
                    Set<String> keys = gson.fromJson(keysJson, new TypeToken<Set<String>>(){}.getType());
                    if (keys != null) {
                        for (String key : keys) {
                            mmkv.removeValueForKey(getMapKey(mapName, key));
                        }
                    }
                }
                mmkv.removeValueForKey(getMapKeysKey(mapName));
            }

            // Clear all named Lists
            for (String listName : listTypes.keySet()) {
                mmkv.removeValueForKey(listName);
            }

            // Clear meta, settings and state maps
            mmkv.encode(META_MAP, "{}");
            mmkv.encode(SETTINGS_MAP, "{}");
            mmkv.encode(STATE_MAP, "{}");

            stateMap.clear();
            mapTypes.clear();
            listTypes.clear();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public LinkedHashMap<String, T> getMap(Integer size, String fromId, Integer fromIndex,
                                          boolean isFromInclude, String toId, Integer toIndex,
                                          boolean isToInclude, boolean isFromEnd) {
        readLock.lock();
        try {
            if (idList.isEmpty()) {
                return new LinkedHashMap<>();
            }

            // Determine start and end indices
            int startIdx = 0;
            int endIdx = idList.size();

            if (isFromEnd) {
                // When paginating from end (reverse order), fromIndex/fromId specifies where to stop (exclusive end)
                // and toIndex/toId specifies where to start (inclusive start from beginning)
                if (fromIndex != null) {
                    // fromIndex specifies the boundary - exclude items at or after this index
                    endIdx = isFromInclude ? fromIndex + 1 : fromIndex;
                } else if (fromId != null) {
                    int idx = idList.indexOf(fromId);
                    if (idx >= 0) {
                        // fromId specifies the boundary - exclude items at or after this ID
                        endIdx = isFromInclude ? idx + 1 : idx;
                    }
                }

                if (toIndex != null) {
                    startIdx = isToInclude ? toIndex : toIndex + 1;
                } else if (toId != null) {
                    int idx = idList.indexOf(toId);
                    if (idx >= 0) {
                        startIdx = isToInclude ? idx : idx + 1;
                    }
                }
            } else {
                // Normal forward pagination
                if (fromIndex != null) {
                    startIdx = isFromInclude ? fromIndex : fromIndex + 1;
                } else if (fromId != null) {
                    int idx = idList.indexOf(fromId);
                    if (idx >= 0) {
                        startIdx = isFromInclude ? idx : idx + 1;
                    }
                }

                if (toIndex != null) {
                    endIdx = isToInclude ? toIndex + 1 : toIndex;
                } else if (toId != null) {
                    int idx = idList.indexOf(toId);
                    if (idx >= 0) {
                        endIdx = isToInclude ? idx + 1 : idx;
                    }
                }
            }

            // Ensure valid range
            startIdx = Math.max(0, Math.min(startIdx, idList.size()));
            endIdx = Math.max(0, Math.min(endIdx, idList.size()));

            if (startIdx >= endIdx) {
                return new LinkedHashMap<>();
            }

            // Get the sublist
            List<String> subList = idList.subList(startIdx, endIdx);

            // Reverse if needed
            if (isFromEnd) {
                List<String> reversed = new ArrayList<>(subList);
                Collections.reverse(reversed);
                subList = reversed;
            }

            // Apply size limit
            if (size != null && size < subList.size()) {
                subList = subList.subList(0, size);
            }

            // Build result map
            LinkedHashMap<String, T> result = new LinkedHashMap<>();
            String lastId = null;
            int lastIndex = 0;

            for (String id : subList) {
                T item = itemMap.get(id);
                if (item == null) {
                    String json = mmkv.decodeString(getItemKey(id));
                    if (json != null) {
                        item = gson.fromJson(json, tclass);
                        if (item != null) {
                            itemMap.put(id, item);
                        }
                    }
                }
                if (item != null) {
                    result.put(id, item);
                    lastId = id;
                    lastIndex = idList.indexOf(id);
                }
            }

            // Store temp values for pagination
            if (lastId != null) {
                tempIndex.set((long) lastIndex);
                tempId.set(lastId);
            }

            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<T> getList(Integer size, String fromId, Integer fromIndex,
                          boolean isFromInclude, String toId, Integer toIndex,
                          boolean isToInclude, boolean isFromEnd) {
        return new ArrayList<>(getMap(size, fromId, fromIndex, isFromInclude, toId, toIndex, isToInclude, isFromEnd).values());
    }

    @Override
    public void put(Map<String, T> items) {
        if (items == null || items.isEmpty()) return;

        writeLock.lock();
        try {
            boolean idListChanged = false;

            // Update in-memory cache and track new items
            for (Map.Entry<String, T> entry : items.entrySet()) {
                String key = entry.getKey();
                T value = entry.getValue();

                // Store in cache
                itemMap.put(key, value);

                // Add to ID list if new
                if (!idList.contains(key)) {
                    idList.add(key);
                    idListChanged = true;
                }
            }

            // Batch write to MMKV - much more efficient than HawkDB!
            // MMKV batches writes internally for better performance
            for (Map.Entry<String, T> entry : items.entrySet()) {
                String json = gson.toJson(entry.getValue());
                mmkv.encode(getItemKey(entry.getKey()), json);
            }

            // Only save ID list if it changed
            if (idListChanged) {
                saveIdList();
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void updateItemsOnly(List<T> items) {
        if (items == null || items.isEmpty()) return;

        writeLock.lock();
        try {
            for (T item : items) {
                if (item == null) continue;

                String id = item.getId();
                if (id != null) {
                    if (idList.contains(id)) {
                        // Update existing item (keep position)
                        itemMap.put(id, item);
                        String json = gson.toJson(item);
                        mmkv.encode(getItemKey(id), json);
                    } else {
                        // Add new item at end
                        put(id, item);
                    }
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void addAll(List<T> items, String idField) {
        if (items == null || items.isEmpty()) return;

        writeLock.lock();
        try {
            for (T item : items) {
                if (item == null) continue;

                String id = item.getId();
                if (id != null) {
                    // Store item
                    itemMap.put(id, item);
                    String json = gson.toJson(item);
                    mmkv.encode(getItemKey(id), json);

                    // Add to ID list if new
                    if (!idList.contains(id)) {
                        idList.add(id);
                    }
                }
            }
            saveIdList();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Map<String, T> getAll() {
        readLock.lock();
        try {
            Map<String, T> result = new LinkedHashMap<>();

            for (String id : idList) {
                T item = itemMap.get(id);
                if (item == null) {
                    String json = mmkv.decodeString(getItemKey(id));
                    if (json != null) {
                        item = gson.fromJson(json, tclass);
                        if (item != null) {
                            itemMap.put(id, item);
                        }
                    }
                }
                if (item != null) {
                    result.put(id, item);
                }
            }

            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<T> searchString(String part) {
        readLock.lock();
        try {
            List<T> matches = new ArrayList<>();

            for (String id : idList) {
                T item = itemMap.get(id);
                if (item == null) {
                    String json = mmkv.decodeString(getItemKey(id));
                    if (json != null) {
                        item = gson.fromJson(json, tclass);
                        if (item != null) {
                            itemMap.put(id, item);
                        }
                    }
                }
                if (item != null) {
                    String json = gson.toJson(item);
                    if (json.contains(part)) {
                        matches.add(item);
                    }
                }
            }

            return matches;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void removeList(List<String> ids) {
        writeLock.lock();
        try {
            for (String key : ids) {
                idList.remove(key);
                itemMap.remove(key);
                mmkv.removeValueForKey(getItemKey(key));
            }
            saveIdList();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int insertAll(List<T> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }

        writeLock.lock();
        try {
            int insertedCount = 0;
            List<String> newIds = new ArrayList<>();

            Collections.reverse(items);

            for (T item : items) {
                if (item != null && item.getId() != null) {
                    String key = item.getId();

                    // Store the item
                    itemMap.put(key, item);
                    String json = gson.toJson(item);
                    mmkv.encode(getItemKey(key), json);

                    // Collect new IDs
                    if (!idList.contains(key)) {
                        newIds.add(key);
                        insertedCount++;
                    }
                }
            }

            // Insert all new IDs at the beginning
            idList.addAll(0, newIds);
            saveIdList();

            return insertedCount;
        } finally {
            writeLock.unlock();
        }
    }

    // ============== Named Map Operations ==============

    @Override
    public <V> void createMap(String mapName, Class<V> vClass) {
        if (mapName == null || vClass == null) {
            return;
        }

        writeLock.lock();
        try {
            if (!mapTypes.containsKey(mapName)) {
                registerMapType(mapName, vClass);
                mmkv.encode(mapName, "{}");
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Set<String> getMapNames() {
        return mapTypes.keySet();
    }

    @Override
    public <V> void putInMap(String mapName, String key, V value) {
        writeLock.lock();
        try {
            if (!mapTypes.containsKey(mapName)) {
                registerMapType(mapName, value.getClass());
            }

            String json = gson.toJson(value);
            mmkv.encode(getMapKey(mapName, key), json);

            // Update keys set
            Set<String> keys = getMapKeys(mapName);
            keys.add(key);
            saveMapKeys(mapName, keys);

            // Update in-memory cache
            MapQueue<Object, Object> map = getNamedMap(mapName);
            map.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }

    @NonNull
    private MapQueue<Object, Object> getNamedMap(String mapName) {
        return namedMapsMap.computeIfAbsent(mapName, k -> new MapQueue<>(QUEUE_SIZE));
    }

    @Override
    public <V> V getFromMap(String mapName, String key) {
        readLock.lock();
        if (mapTypes.get(mapName) == null) return null;
        try {
            // Try to get from cache first
            V value = null;
            MapQueue<Object, Object> map = getNamedMap(mapName);
            Object obj = map.get(key);
            if (obj != null) value = (V) obj;

            if (value == null) {
                // If not in cache, load from MMKV
                String json = mmkv.decodeString(getMapKey(mapName, key));
                if (json != null) {
                    Class<?> valueClass = getMapType(mapName);
                    if (valueClass != null) {
                        value = (V) gson.fromJson(json, valueClass);
                        if (value != null) {
                            map.put(key, value);
                        }
                    }
                }
            }
            return value;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <V> Map<String, V> getAllFromMap(String mapName) {
        readLock.lock();
        try {
            Set<String> keys = getMapKeys(mapName);
            Map<String, V> result = new HashMap<>();
            Class<?> valueClass = getMapType(mapName);

            for (String key : keys) {
                String json = mmkv.decodeString(getMapKey(mapName, key));
                if (json != null && valueClass != null) {
                    V value = (V) gson.fromJson(json, valueClass);
                    if (value != null) {
                        result.put(key, value);
                    }
                }
            }

            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void clearMap(String mapName) {
        writeLock.lock();
        try {
            Set<String> keys = getMapKeys(mapName);

            for (String key : keys) {
                mmkv.removeValueForKey(getMapKey(mapName, key));
            }

            mmkv.removeValueForKey(getMapKeysKey(mapName));

            MapQueue<Object, Object> map = getNamedMap(mapName);
            map.clear();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public <V> void putAllInMap(String mapName, Map<String, V> map) {
        if (mapName == null || map == null || map.isEmpty()) {
            return;
        }

        writeLock.lock();
        try {
            if (!mapTypes.containsKey(mapName)) {
                registerMapType(mapName, map.values().iterator().next().getClass());
            }

            // Batch write all entries
            for (Map.Entry<String, V> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    String json = gson.toJson(entry.getValue());
                    mmkv.encode(getMapKey(mapName, entry.getKey()), json);
                }
            }

            // Update keys set
            Set<String> keys = getMapKeys(mapName);
            for (Map.Entry<String, V> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    keys.add(entry.getKey());
                }
            }
            saveMapKeys(mapName, keys);

            // Update cache
            MapQueue<Object, Object> targetMap = getNamedMap(mapName);
            for (Map.Entry<String, V> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    targetMap.put(entry.getKey(), entry.getValue());
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeFromMap(String mapName, String key) {
        writeLock.lock();
        try {
            mmkv.removeValueForKey(getMapKey(mapName, key));

            Set<String> keys = getMapKeys(mapName);
            keys.remove(key);
            saveMapKeys(mapName, keys);

            MapQueue<Object, Object> map = getNamedMap(mapName);
            map.remove(key);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void removeFromMap(String mapName, List<String> keys) {
        writeLock.lock();
        try {
            for (String key : keys) {
                mmkv.removeValueForKey(getMapKey(mapName, key));
            }

            Set<String> existingKeys = getMapKeys(mapName);
            keys.forEach(existingKeys::remove);
            saveMapKeys(mapName, existingKeys);

            MapQueue<Object, Object> map = getNamedMap(mapName);
            for (String key : keys) {
                map.remove(key);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int getMapSize(String mapName) {
        readLock.lock();
        try {
            Set<String> keys = getMapKeys(mapName);
            return keys.size();
        } finally {
            readLock.unlock();
        }
    }

    private String getMapKey(String mapName, String key) {
        return mapName + "_entry_" + key;
    }

    private String getMapKeysKey(String mapName) {
        return mapName + "_keys";
    }

    private Set<String> getMapKeys(String mapName) {
        String json = mmkv.decodeString(getMapKeysKey(mapName));
        if (json != null) {
            Set<String> keys = gson.fromJson(json, new TypeToken<Set<String>>(){}.getType());
            return keys != null ? keys : new HashSet<>();
        }
        return new HashSet<>();
    }

    private void saveMapKeys(String mapName, Set<String> keys) {
        String json = gson.toJson(keys);
        mmkv.encode(getMapKeysKey(mapName), json);
    }

    @Override
    public void registerMapType(String mapName, Class<?> typeClass) {
        writeLock.lock();
        try {
            mapTypes.put(mapName, typeClass.getName());

            if (metaMap == null) {
                metaMap = new HashMap<>();
            }
            metaMap.put(MAP_TYPES_META_KEY, gson.toJson(mapTypes));
            saveMetaMap();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void registerListType(String listName, Class<?> typeClass) {
        writeLock.lock();
        try {
            listTypes.put(listName, typeClass.getName());
            metaMap.put(LIST_TYPES_META_KEY, gson.toJson(listTypes));
            saveMetaMap();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Class<?> getMapType(String mapName) {
        readLock.lock();
        try {
            String className = mapTypes.get(mapName);
            if (className != null) {
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException e) {
                    return null;
                }
            }
            return null;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Class<?> getListType(String listName) {
        readLock.lock();
        try {
            Object listTypesObj = metaMap.get(LIST_TYPES_META_KEY);
            Map<String, String> listTypes = ObjectUtils.objectToMap(listTypesObj, String.class, String.class);
            if (listTypes == null) return null;
            String className = listTypes.get(listName);
            if (className != null) {
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException e) {
                    return null;
                }
            }
            return null;
        } finally {
            readLock.unlock();
        }
    }

    // ============== Named List Operations ==============

    @Override
    public <V> void createList(String listName, Class<V> vClass) {
        if (listName == null || vClass == null) {
            return;
        }

        writeLock.lock();
        try {
            Set<String> listNames = getListNames();

            if (listNames == null)
                listNames = new HashSet<>();

            if (!listNames.contains(listName)) {
                listNames.add(listName);

                metaMap.put(LIST_NAMES_META_KEY, gson.toJson(listNames));

                registerListType(listName, vClass);
                List<V> emptyList = new ArrayList<>();
                String json = gson.toJson(emptyList);
                mmkv.encode(listName, json);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Set<String> getListNames() {
        Object result = metaMap.get(LIST_NAMES_META_KEY);
        if (result == null) return new HashSet<>();
        List<String> list = ObjectUtils.objectToList(result, String.class);
        if (list != null)
            return new HashSet<>(list);
        else return new HashSet<>();
    }

    @Override
    public <V> long addToList(String listName, V value) {
        if (listName == null || value == null) {
            return -1;
        }

        writeLock.lock();
        try {
            List<V> list = (List<V>) getList(listName);

            long index = list.size();
            list.add(value);

            saveListToMMKV(listName, list);

            return index;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public <V> long addAllToList(String listName, List<V> values) {
        if (listName == null || values == null || values.isEmpty()) {
            return -1;
        }

        writeLock.lock();
        try {
            List<Object> list = getList(listName);

            long startIndex = list.size();

            list.addAll(values);

            saveListToMMKV(listName, list);

            return startIndex;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public <V> V getFromList(String listName, long index, Class<V> vClass) {
        if (listName == null || index < 0) {
            return null;
        }

        readLock.lock();
        try {
            List<V> list = getTypedList(listName);

            if (list == null || index >= list.size()) {
                return null;
            }

            return list.get((int) index);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <V> List<V> getAllFromList(String listName) {
        if (listName == null) {
            return new ArrayList<>();
        }

        readLock.lock();
        try {
            List<V> list = getTypedList(listName);

            if (list == null) {
                return new ArrayList<>();
            }

            return new ArrayList<>(list);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <V> List<V> getRangeFromList(String listName, long startIndex, long endIndex) {
        if (listName == null || startIndex < 0 || endIndex <= startIndex) {
            return new ArrayList<>();
        }

        readLock.lock();
        try {
            List<V> list = getTypedList(listName);

            if (list == null || startIndex >= list.size()) {
                return new ArrayList<>();
            }

            endIndex = Math.min(endIndex, list.size());

            List<V> result = new ArrayList<>();
            for (long i = startIndex; i < endIndex; i++) {
                V value = list.get((int) i);
                result.add(value);
            }

            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <V> List<V> getRangeFromListReverse(String listName, long startIndex, long endIndex) {
        if (listName == null || startIndex < 0 || endIndex <= startIndex) {
            return new ArrayList<>();
        }

        readLock.lock();
        try {
            List<V> list = getTypedList(listName);

            if (list == null || list.isEmpty()) {
                return new ArrayList<>();
            }

            int count = list.size();

            int startFromBeginning = Math.max(0, count - (int) endIndex);
            int endFromBeginning = Math.min(count, count - (int) startIndex);

            if (startFromBeginning >= count || endFromBeginning <= 0) {
                return new ArrayList<>();
            }

            List<V> result = new ArrayList<>();
            for (int i = endFromBeginning - 1; i >= startFromBeginning; i--) {
                V value = list.get(i);
                result.add(value);
            }

            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean removeFromList(String listName, long index) {
        if (listName == null || index < 0) {
            return false;
        }

        writeLock.lock();
        try {
            List<Object> list = getList(listName);

            if (list == null || index >= list.size()) {
                return false;
            }

            list.remove((int) index);

            saveListToMMKV(listName, list);

            return true;
        } finally {
            writeLock.unlock();
        }
    }

    private List<Object> getList(String listName) {
        String json = mmkv.decodeString(listName);
        if (json != null) {
            return gson.fromJson(json, new TypeToken<List<Object>>(){}.getType());
        }
        return new ArrayList<>();
    }

    /**
     * Get a properly typed list by deserializing each element with the registered class type.
     * This prevents ClassCastException when Gson deserializes to LinkedTreeMap.
     */
    private <V> List<V> getTypedList(String listName) {
        String json = mmkv.decodeString(listName);
        if (json == null) {
            return new ArrayList<>();
        }

        Class<?> elementType = getListType(listName);
        if (elementType == null) {
            // Fallback to untyped list
            List<Object> untypedList = gson.fromJson(json, new TypeToken<List<Object>>(){}.getType());
            return (List<V>) untypedList;
        }

        // Parse as array of JSON objects first
        List<Object> rawList = gson.fromJson(json, new TypeToken<List<Object>>(){}.getType());
        if (rawList == null) {
            return new ArrayList<>();
        }

        // Convert each element to the proper type
        List<V> typedList = new ArrayList<>(rawList.size());
        for (Object obj : rawList) {
            if (obj == null) {
                typedList.add(null);
            } else {
                // Re-serialize and deserialize with proper type
                String elementJson = gson.toJson(obj);
                V typedElement = (V) gson.fromJson(elementJson, elementType);
                typedList.add(typedElement);
            }
        }

        return typedList;
    }

    @Override
    public int removeFromList(String listName, List<Long> indices) {
        if (listName == null || indices == null || indices.isEmpty()) {
            return 0;
        }

        writeLock.lock();
        try {
            List<Object> list = getList(listName);
            if (list == null) {
                return 0;
            }

            List<Long> sortedIndices = new ArrayList<>(indices);
            sortedIndices.sort(Collections.reverseOrder());

            int removedCount = 0;
            for (Long index : sortedIndices) {
                if (index != null && index >= 0 && index < list.size()) {
                    list.remove(index.intValue());
                    removedCount++;
                }
            }

            saveListToMMKV(listName, list);

            return removedCount;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public long getListSize(String listName) {
        if (listName == null) {
            return 0;
        }

        readLock.lock();
        try {
            List<Object> list = getList(listName);
            return list != null ? list.size() : 0;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void clearList(String listName) {
        if (listName == null) {
            return;
        }

        writeLock.lock();
        try {
            List<Object> list = new ArrayList<>();
            saveListToMMKV(listName, list);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public <V> long insertIntoList(String listName, long index, V value) {
        if (listName == null || value == null) {
            return -1;
        }

        writeLock.lock();
        try {
            List<Object> list = getList(listName);
            if (list == null) {
                list = new ArrayList<>();
            }

            if (index < 0 || index > list.size()) {
                return -1;
            }

            list.add((int) index, value);

            saveListToMMKV(listName, list);

            return index;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public <V> int insertAllIntoList(String listName, long index, List<V> values) {
        if (listName == null || values == null || values.isEmpty()) {
            return 0;
        }

        writeLock.lock();
        try {
            List<Object> list = getList(listName);
            if (list == null) {
                list = new ArrayList<>();
            }

            if (index < 0 || index > list.size()) {
                return 0;
            }

            int insertIndex = (int) index;
            int insertedCount = 0;

            for (V value : values) {
                if (value != null) {
                    list.add(insertIndex, value);
                    insertedCount++;
                }
            }

            saveListToMMKV(listName, list);

            return insertedCount;
        } finally {
            writeLock.unlock();
        }
    }

    private <V> void saveListToMMKV(String listName, List<V> list) {
        writeLock.lock();
        try {
            String json = gson.toJson(list);
            mmkv.encode(listName, json);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public <V> List<V> getFromMap(String mapName, List<String> keyList) {
        readLock.lock();
        try {
            List<V> result = new ArrayList<>();
            for (String key : keyList) {
                V value = getFromMap(mapName, key);
                if (value != null) {
                    result.add(value);
                }
            }
            return result;
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <V> void putAllInMap(String mapName, List<String> keyList, List<V> valueList) {
        if (keyList.size() != valueList.size()) {
            throw new IllegalArgumentException("Key list and value list must be the same size");
        }

        writeLock.lock();
        try {
            for (int i = 0; i < keyList.size(); i++) {
                putInMap(mapName, keyList.get(i), valueList.get(i));
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Nullable
    public Long getStateLong(String key) {
        Object obj = getState(key);
        if (obj == null) return null;
        return Long.valueOf((String) obj);
    }
}
