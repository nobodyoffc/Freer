package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.STD_NAME;
import static com.fc.fc_ajdk.constants.IndicesNames.SERVICE;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.FALSE;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Service;
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
 * A singleton class to manage and share the Service database across activities.
 * This provides a centralized way to access the Service database from any activity.
 */
public class ServiceManager extends FcManager<Service> {
    private static final String TAG = "ServiceManager";
    public static final int MAX_PAGES = 200;

    private static ServiceManager instance;

    private ServiceManager() {
        super(Service.class, SERVICE);
    }

    /**
     * Gets the singleton instance of ServiceManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The ServiceManager instance
     */
    public static synchronized ServiceManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new ServiceManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized ServiceManager getInstance() {
        return instance;
    }

    /**
     * Gets a Service object by its ID.
     *
     * @param id The ID of the Service object to get
     * @return The Service object, or null if not found
     */
    public Service getServiceById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Service object to the database.
     *
     * @param service The Service object to add
     */
    public void addService(Service service) {
        addEntity(service);
    }

    /**
     * Removes a Service object from the database.
     *
     * @param service The Service object to remove
     */
    public void removeService(Service service) {
        removeEntity(service);
    }

    /**
     * Removes multiple Service objects from the database.
     *
     * @param services The list of Service objects to remove
     */
    public void removeServices(List<Service> services) {
        removeEntities(services);
    }

    /**
     * Updates an existing Service in the database.
     * @param service The Service object to update
     */
    public void updateService(Service service) {
        updateEntity(service);
    }

    /**
     * Gets a paginated list of Service objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of Service objects for the requested page
     */
    public List<Service> getPaginatedServices(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    @Override
    protected void preprocessDB() {
        // No special preprocessing needed for Service database
    }

    @Override
    protected void markEntityAsNew(Service entity) {
        // No special marking needed for new Service entities
    }

    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(SERVICE);
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
     * Makes an FCDSL query for services owned by the liveFid.
     */
    public Fcdsl makeFcdslForOwner(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = makeFcdsl(pageSize, after, active, timeAsc);
        fcdsl.addNewFilter().addNewTerms().addNewFields(OWNER).addNewValues(liveFid);
        return fcdsl;
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<Service> entityList, int index, int savedCount) {
        processServiceSequentially(activity, entityList, index, savedCount);
    }

    private void processServiceSequentially(Activity activity, List<Service> serviceList, int index, int savedCount) {
        if (index >= serviceList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        Service service = serviceList.get(index);

        if (checkIfExisted(service.getId())) {
            String prompt = service.getStdName() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(service);
                    processServiceSequentially(activity, serviceList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processServiceSequentially(activity, serviceList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(service);
            processServiceSequentially(activity, serviceList, index + 1, savedCount + 1);
        }
    }

    public long getServiceDBSize() {
        return getEntityDBSize();
    }

    /**
     * Fetches all services from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active services
     * @param timeAsc Sort in time ascending order
     * @return List of all Service objects, or null if failed
     */
    private List<Service> fetchServices(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, MAX_PAGES);
        if (result == null) {
            return null;
        }

        List<Service> serviceList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof Service service) {
                serviceList.add(service);
            }
        }
        return serviceList;
    }

    public List<Service> fetchPageServices(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<Service> serviceList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof Service service) {
                serviceList.add(service);
                service.setOnChain(true);
            }
        }

        return serviceList;
    }

    /**
     * Converts Service objects (no decryption needed)
     */
    @Override
    public List<Service> makeEntityDetails(List<?> serviceList, boolean decryptDeleted, Context context) {
        if (serviceList == null || serviceList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert services: service list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<Service> serviceDetailList = new ArrayList<>();
            List<Service> stoppedServiceList = new ArrayList<>();
            for (Object obj : serviceList) {
                Service service = (Service) obj;
                if(Boolean.FALSE.equals(service.getActive())){
                    stoppedServiceList.add(service);
                    continue;
                }
                service.setOnChain(true);
                serviceDetailList.add(service);
            }

            if(!stoppedServiceList.isEmpty())
                entityDB.remove(stoppedServiceList);

            TimberLogger.i(TAG, "Successfully converted %d services to Service objects",
                serviceDetailList.size());
            return serviceDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting services to Service objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Refreshes services by loading newer changes including inactive services for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context Activity context for dialog operations
     */
    public void refreshServicesFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer service changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of service changes processed, or -1 if failed
     */
    public int loadNewerServicesFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid services from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid service items fetched, or -1 if failed
     */
    public int loadEarlierServicesFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }

    /**
     * Searches for Service objects by stdName and desc fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of Service objects that match the search criteria
     */
    public List<Service> searchServices(String searchQuery) {
        return searchEntities(searchQuery);
    }

    /**
     * Searches service data from API with custom field, sort, and order parameters.
     */
    public List<Service> searchServicesFromApi(Context context,
                                       String searchQuery, String inField, String sortField, String order, Service referenceService) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search services: FapiClient not available");
            return null;
        }

        TimberLogger.i(TAG, "Searching services with query: %s in field: %s, sort: %s %s",
            searchQuery, inField, sortField, order);

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceService, false);
            List<Service> serviceItems = fetchPageServices(fcdsl);
            if (serviceItems == null) {
                TimberLogger.w(TAG, "Failed to search services from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d service items from API", serviceItems.size());
            return serviceItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching services from API: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Searches services owned by the liveFid from API.
     */
    public List<Service> searchMyServicesFromApi(Context context, String searchQuery, String inField, 
                                          String sortField, String order, Service referenceService) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search my services: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceService, true);
            List<Service> serviceItems = fetchPageServices(fcdsl);

            if (serviceItems == null) {
                TimberLogger.w(TAG, "Failed to search my services from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d of my service items from API", serviceItems.size());
            return serviceItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching my services from API: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildSearchFcdsl(String searchQuery, String inField, String sortField, 
                                   String order, Service referenceService, boolean ownerOnly) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(SERVICE);
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);

        // Add query
        fcdsl.addNewQuery();

        // Add owner filter if searching my services
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
                    .addNewFields(STD_NAME, FieldNames.DESC, OWNER, FieldNames.LOCAL_NAMES, FieldNames.TYPE)
                    .addNewValue(searchQuery);
            }
        }

        // Add sorting
        String normalizedOrder = normalizeOrder(order);
        addSortingToFcdsl(fcdsl, sortField, normalizedOrder);

        // Add pagination
        if (referenceService != null) {
            List<String> last = Arrays.asList(String.valueOf(referenceService.getLastHeight()), referenceService.getId());
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    @NonNull
    private static String adjustFieldName(String displayName) {
        String fieldName = Service.getFieldNameByDisplayName(displayName);
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
     * Refreshes the entire service database by fetching all active services from API
     * and replacing the local database contents.
     *
     * @param context Activity context for dialog operations
     * @return Number of service items refreshed, or 0 if failed
     */
    public int freshServiceDB(Context context) {
        return freshEntityDB(context);
    }

    // Abstract method implementations for ServiceManager

    @Override
    protected String getEntityIndexName() {
        return SERVICE;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return Service.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        Service service = (Service) apiObject;
        return Arrays.asList(String.valueOf(service.getLastHeight()), service.getId());
    }

    @Override
    protected void preprocessEntity(Service entity) {
        // Service entities come with IDs from the chain, no preprocessing needed
    }

    @Override
    protected boolean isEntityDeleted(Service entity) {
        return Boolean.TRUE.equals(entity.isClosed());
    }

    @Override
    protected Long getEntityUpdateHeight(Service entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(Service entity) {
        Boolean onChain = entity.getOnChain();
        return onChain == null || onChain;
    }

    @Override
    protected List<Service> searchFromList(String query, List<Service> results) {
        List<Service> matchingServices = new ArrayList<>();

        for (Service service : results) {
            boolean matches = false;

            // Search in stdName
            if (service.getStdName() != null && service.getStdName().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in desc
            if (!matches && service.getDesc() != null && service.getDesc().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in owner
            if (!matches && service.getOwner() != null && service.getOwner().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            // Search in localNames (locale code -> display name)
            if (!matches && service.getLocalNames() != null) {
                String q = query.toLowerCase();
                for (Map.Entry<String, String> e : service.getLocalNames().entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if ((k != null && k.toLowerCase().contains(q))
                            || (v != null && v.toLowerCase().contains(q))) {
                        matches = true;
                        break;
                    }
                }
            }

            // Search in type
            if (!matches && service.fetchServiceType() != null && service.fetchServiceType().toString().toLowerCase().contains(query.toLowerCase())) {
                matches = true;
            }

            if (matches) {
                matchingServices.add(service);
            }
        }
        return matchingServices;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchServices(context, after, active, timeAsc);
    }

    @Override
    protected Service createFailedDecryptionEntity(Object apiObject, Context context) {
        return (Service) apiObject;
    }

    @Override
    protected Service createUndecryptedEntity(Context context, Object apiObject) {
        if (apiObject instanceof Service service) {
            return service;
        }
        return null;
    }

    public List<Service> getLocalHiddenServices() {
        return getLocalDeletedList();
    }

    public long getLocalHiddenServicesSize() {
        Long size = getLocalDeletedListSize();
        return size != null ? size : 0;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        return null;
    }
}
