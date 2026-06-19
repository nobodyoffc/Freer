package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACT;
import static com.fc.fc_ajdk.constants.FieldNames.DOER;
import static com.fc.fc_ajdk.constants.FieldNames.OBJECT_BRIEF;
import static com.fc.fc_ajdk.constants.FieldNames.OBJECT_NAME;
import static com.fc.fc_ajdk.constants.FieldNames.OBJECT_TYPE;
import static com.fc.fc_ajdk.constants.IndicesNames.FC_OBJECT;
import static com.fc.fc_ajdk.constants.FieldNames.TIME;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;

import android.content.Context;
import android.app.Activity;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.data.fcData.News;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.fc_ajdk.fapi.client.FapiClient;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * A singleton class to manage and share the FcObject database across activities.
 * This provides a centralized way to access the FcObject database from any activity.
 *
 * FcObject entities do not have encrypted fields, so no encryption/decryption is needed.
 */
public class FcObjectManager extends FcManager<FcObject> {
    private static final String TAG = "FcObjectManager";
    public static final int MAX_PAGES = 200;

    private static final String LAST_HEIGHT_OF_NEWS = "last_height_of_news";
    private static final String NEWS_LIST = "news_list";

    private static FcObjectManager instance;
    private Long totalNews;

    private FcObjectManager() {
        super(FcObject.class, FC_OBJECT);
    }

    /**
     * Gets the singleton instance of FcObjectManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The FcObjectManager instance
     */
    public static synchronized FcObjectManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new FcObjectManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized FcObjectManager getInstance() {
        return instance;
    }

    /**
     * Gets an FcObject by its ID.
     *
     * @param id The ID of the FcObject to get
     * @return The FcObject, or null if not found
     */
    public FcObject getFcObjectById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds an FcObject to the database.
     *
     * @param fcObject The FcObject to add
     */
    public void addFcObject(FcObject fcObject) {
        addEntity(fcObject);
    }

    /**
     * Removes an FcObject from the database.
     *
     * @param fcObject The FcObject to remove
     */
    public void removeFcObject(FcObject fcObject) {
        removeEntity(fcObject);
    }

    /**
     * Removes multiple FcObjects from the database.
     *
     * @param fcObjects The list of FcObjects to remove
     */
    public void removeFcObjects(List<FcObject> fcObjects) {
        removeEntities(fcObjects);
    }


    /**
     * Gets a paginated list of FcObject entities.
     *
     * @param pageSize The number of items per page
     * @param lastID The ID of the last item from the previous page, or null for the first page
     * @param fromEnd Whether to sort in fromEnd order
     * @return A list of FcObject entities for the requested page
     */
    public List<FcObject> getPaginatedFcObjects(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    @Override
    protected void preprocessDB() {
        // No preprocessing needed for FcObject
    }


    public long getFcObjectDBSize() {
        return getEntityDBSize();
    }

    /**
     * Converts API objects to FcObject entities.
     * Since FcObject has no encrypted fields, this is a simple conversion.
     */
    @Override
    public List<FcObject> makeEntityDetails(List<?> apiObjects, boolean decryptDeleted, Context context) {
        return null;
    }

    // ========================================
    // News Management - Wrapper Methods
    // ========================================

    /**
     * Searches news data from API with custom field, sort, and order parameters.
     *
     * @param searchQuery   The search query string to match in the inField
     * @param inField       The field to search in (e.g., "title", "content")
     * @param sortField     The field to sort by (e.g., TIME, ID)
     * @param order         Sort order (ASC or DESC)
     * @param referenceNews The reference news for pagination, or null for first page
     * @return List of news items matching the search, or null if failed
     */
    public List<News> searchNewsFromApi(String searchQuery, String inField, String sortField, String order, News referenceNews) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search news: FapiClient not available");
            return null;
        }

        TimberLogger.i(TAG, "Searching news with query: %s in field: %s, sort: %s %s",
            searchQuery, inField, sortField, order);

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceNews);
            List<News> newsItems = fapiClient.entitySearch(IndicesNames.NEWS,fcdsl,News.class);

            if (newsItems == null) {
                TimberLogger.w(TAG, "Failed to search news from API");
                return null;
            }

            // Capture total count from API response
            if (fapiClient.getLastResponse() != null &&
                fapiClient.getLastResponse().getTotal() != null) {
                totalNews = fapiClient.getLastResponse().getTotal();
                TimberLogger.d(TAG, "Updated totalNews from search API: %d", totalNews);
            }

            markNewNewsItems(newsItems);

            TimberLogger.i(TAG, "Successfully searched %d news items from API", newsItems.size());
            return newsItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching news from API: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildSearchFcdsl(String searchQuery, String inField, String sortField, String order, News referenceNews) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);

        // Add search filter
        if (inField != null) {
            String adjustedField = adjustFieldName(inField);
            if(adjustedField.equalsIgnoreCase(DOER) && Freer.isCid(searchQuery)){
                String fid = CidFidManager.getInstance().getFidByCid(searchQuery);
                if(fid!=null)
                    searchQuery = fid;
            }
            fcdsl.addNewQuery().addNewMatch().addNewFields(adjustedField).addNewValue(searchQuery);
        } else {
            fcdsl.addNewQuery().addNewMatch()
                .addNewFields(DOER, OBJECT_TYPE, OBJECT_NAME, OBJECT_BRIEF, ACT, ID)
                .addNewValue(searchQuery);
        }

        // Add sorting
        String normalizedOrder = normalizeOrder(order);
        addSortingToFcdsl(fcdsl, sortField, normalizedOrder);

        // Add pagination
        if (referenceNews != null) {
            List<String> last = Arrays.asList(String.valueOf(referenceNews.getTime()), referenceNews.getId());
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    private String normalizeOrder(String order) {
        if (order == null) {
            return null;
        }
        String lowerOrder = order.toLowerCase();
        return (lowerOrder.equals(DESC.toLowerCase()) || lowerOrder.equals(ASC.toLowerCase())) ? lowerOrder : null;
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
            fcdsl.addSort(TIME, order).addSort(ID, order);
        }
    }

    private void markNewNewsItems(List<News> newsItems) {
        if (newsItems.isEmpty()) {
            return;
        }

        Long lastHeight = entityDB.getStateLong(LAST_HEIGHT_OF_NEWS);
        for (News news : newsItems) {
            if (lastHeight == null || news.getHeight() > lastHeight) {
                news.setNew(true);
            }
        }
    }

    @NonNull
    private static String adjustFieldName(String displayName) {
        String fieldName = News.getFieldNameByDisplayName(displayName);
        return fieldName != null ? fieldName : displayName;
    }

    /**
     * Fetches news data from API based on operation type.
     *
     * @param operationType "refresh" for initial load (DESC from now), "newer" for swipe down (ASC after latest), "older" for scroll bottom (DESC after earliest)
     * @param referenceNews the reference news for pagination: latest news for "newer", earliest news for "older", null for "refresh"
     * @return List of news items fetched, or null if failed
     */
    public List<News> fetchNewsFromAPI(String operationType, News referenceNews) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch news: FapiClient not available");
            return null;
        }

        TimberLogger.i(TAG, "Fetching news, operation: %s", operationType);

        try {
            Fcdsl fcdsl = buildFetchFcdsl(operationType, referenceNews);
            if (fcdsl == null) {
                return null;
            }

            List<News> newsItems = fapiClient.entitySearch(IndicesNames.NEWS,fcdsl,News.class);

            if (newsItems == null) {
                TimberLogger.w(TAG, "Failed to fetch news from API");
                return null;
            }

            // Capture total count from API response
            if (fapiClient.getLastResponse() != null &&
                fapiClient.getLastResponse().getTotal() != null) {
                totalNews = fapiClient.getLastResponse().getTotal();
                TimberLogger.d(TAG, "Updated totalNews from fetch API: %d", totalNews);
            }

            markNewNewsItems(newsItems);
            updateLastHeightIfNeeded(operationType, referenceNews, fapiClient);

            TimberLogger.i(TAG, "Successfully fetched %d news items from API", newsItems.size());
            return newsItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching news from API: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildFetchFcdsl(String operationType, News referenceNews) {
        String order;
        List<String> last = null;

        switch (operationType) {
            case "refresh":
                order = DESC;
                break;
            case "newer":
                order = ASC;
                if (referenceNews != null) {
                    last = Arrays.asList(String.valueOf(referenceNews.getTime()), referenceNews.getId());
                }
                break;
            case "older":
                order = DESC;
                if (referenceNews != null) {
                    last = Arrays.asList(String.valueOf(referenceNews.getTime()), referenceNews.getId());
                }
                break;
            default:
                TimberLogger.w(TAG, "Invalid operation type: %s", operationType);
                return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);
        fcdsl.addSort(TIME, order);
        fcdsl.addSort(ID, order);
        if (last != null) {
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    private void updateLastHeightIfNeeded(String operationType, News referenceNews, FapiClient fapiClient) {
        if ("refresh".equals(operationType) || ("newer".equals(operationType) && referenceNews != null)) {
            Long lastHeight = entityDB.getStateLong(LAST_HEIGHT_OF_NEWS);
            Long bestHeight = fapiClient.getBestHeight();
            if (lastHeight == null || (bestHeight != null && bestHeight > lastHeight)) {
                entityDB.putState(LAST_HEIGHT_OF_NEWS, String.valueOf(bestHeight));
            }
        }
    }

    /**
     * Saves the current news list to local DB as a simple cache.
     *
     * @param newsList List of news objects to cache
     * @return Number of news items saved, or -1 if failed
     */
    public int saveCurrentNewsList(List<News> newsList) {
        if (entityDB == null) {
            TimberLogger.w(TAG, "Cannot save current news list: database not available");
            return -1;
        }

        if (newsList == null || newsList.isEmpty()) {
            return 0;
        }

        try {
            entityDB.createList(NEWS_LIST, News.class);
            entityDB.clearList(NEWS_LIST);
            entityDB.addAllToList(NEWS_LIST, newsList);
            TimberLogger.i(TAG, "Saved %d news items to cache", newsList.size());
            return newsList.size();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving current news list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Gets the cached news list from local DB.
     *
     * @return A list of cached News objects, or empty list if none found
     */
    public List<News> getCachedNewsList() {
        if (entityDB == null) {
            TimberLogger.w(TAG, "Cannot get cached news list: database not available");
            return new ArrayList<>();
        }

        try {
            long listSize = entityDB.getListSize(NEWS_LIST);
            if (listSize == 0) {
                return new ArrayList<>();
            }

            List<News> newsList = entityDB.getRangeFromList(NEWS_LIST, 0, listSize);
            TimberLogger.i(TAG, "Retrieved %d cached news from database",
                newsList != null ? newsList.size() : 0);

            return newsList != null ? newsList : new ArrayList<>();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting cached news list: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    public Long getTotalNews() {
        return totalNews;
    }

    public void setTotalNews(Long totalNews) {
        this.totalNews = totalNews;
    }

    // ========================================
    // Abstract Method Implementations
    // ========================================

    @Override
    protected String getEntityIndexName() {
        return FC_OBJECT;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return News.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        if (apiObject instanceof News) {
            News news = (News) apiObject;
            return Arrays.asList(String.valueOf(news.getTime()), news.getId());
        }
        return null;
    }

    @Override
    protected void preprocessEntity(FcObject entity) {
        // No preprocessing needed for FcObject
    }

    @Override
    protected boolean isEntityDeleted(FcObject entity) {
        return false;
    }

    @Override
    protected Long getEntityUpdateHeight(FcObject entity) {
        if (entity instanceof News) {
            return ((News) entity).getHeight();
        }
        return null;
    }

    @Override
    protected Boolean isEntityOnChain(FcObject entity) {
        if (entity instanceof News) {
            return ((News) entity).getHeight() != null;
        }
        return null;
    }

    @Override
    protected List<FcObject> searchFromList(String query, List<FcObject> results) {
        // FcObject doesn't have searchable fields implemented yet
        return results;
    }

    @Override
    @NonNull
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSize(pageSize);

        // News doesn't filter by active or owner - fetch all
        // Sort by time and id
        if (timeAsc) {
            fcdsl.addSort(TIME, ASC);
            fcdsl.addSort(ID, ASC);
        } else {
            fcdsl.addSort(TIME, DESC);
            fcdsl.addSort(ID, DESC);
        }

        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }

        return fcdsl;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            return null;
        }

        Fcdsl fcdsl = makeFcdsl(FreerApplication.DEFAULT_REQUEST_SIZE, after, active, timeAsc);
        return fapiClient.entitySearch(IndicesNames.NEWS,fcdsl,News.class);
    }

    @Override
    protected FcObject createFailedDecryptionEntity(Object apiObject, Context context) {
        // FcObject/News has no encrypted fields
        return null;
    }

    @Override
    protected FcObject createUndecryptedEntity(Context context, Object apiObject) {
        // FcObject/News has no encrypted fields
        return null;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        // FcObject doesn't support deletion
        return null;
    }

    @Override
    protected void markEntityAsNew(FcObject entity) {
        if (entity instanceof News) {
            ((News) entity).setNew(true);
        }
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<FcObject> entityList, int index, int savedCount) {
        // No sequential processing needed for FcObject
    }
}
