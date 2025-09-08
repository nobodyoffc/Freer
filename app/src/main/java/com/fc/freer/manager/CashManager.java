package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.Constants.COINBASE;
import static com.fc.fc_ajdk.constants.Constants.FUND_MATURE_DAYS_INT;
import static com.fc.fc_ajdk.constants.Constants.MINE_MATURE_DAYS_INT;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.CASH_ID;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.ISSUER;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.content.Context;
import android.widget.Toast;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.core.fch.TxCreator;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisign;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.network.ApipClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.fc_ajdk.utils.http.RequestMethod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A singleton class to manage and share the Cash database across activities.
 * This provides a centralized way to access the Cash database from any activity.
 */
public class CashManager extends FcManager{
    private static final String TAG = "CashManager";
    public static final int DEFAULT_PAGE_SIZE = FreerApplication.DEFAULT_PAGE_SIZE;
    public static final int DEFAULT_LOAD_SIZE = 10;
    public static final int MAX_PAGE_COUNT = 2;

    public static final String LAST_UPDATED = "lastUpdated";
    public static final String EARLIEST_VALID = "earliestValid";

    private static CashManager instance;
    private LocalDB<Cash> cashDB;
    private String mainFid;
    private ApipClient apipClient;
    private Long bestHeight;


    private CashManager() {
        // Private constructor to prevent direct instantiation
    }

    /**
     * Gets the singleton instance of CashManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @return The CashManager instance
     */
    public static synchronized CashManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.mainFid)) {
            instance = new CashManager();
            instance.mainFid = fid;
            instance.initialize(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized CashManager getInstance() {
        return instance;
    }

    /**
     * Initializes the CashManager with the given context.
     *
     * @param context The context to use for getting the DatabaseManager
     */
    public void  initialize(Context context) {
        DatabaseManager dbManager = DatabaseManager.getInstance(context);
        // Close existing database if it exists
        if (cashDB != null) {
            try {
                cashDB.close();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error closing database: " + e.getMessage());
            }
        }
        cashDB = dbManager.getEntityDatabase(mainFid, Cash.class, LocalDB.SortType.BIRTH_ORDER, BIRTH_TIME);
        if(cashDB==null){
            TimberLogger.w(TAG, "No Cash database available from DatabaseManager when initiating CashManager.");
        }

        apipClient = loadApipClient();
        if(apipClient==null){
            TimberLogger.w(TAG, "No ApipClient available from ApiCenter when initiating CashManager.");
        }

        updateValidCash(context,AuthType.FC_SIGN_BODY,RequestMethod.POST, true);
    }

    /**
     * Gets the Cash database.
     * 
     * @return The Cash database
     */
    public LocalDB<Cash> getCashDB() {
        return cashDB;
    }

    /**
     * Gets all Cash objects from the database.
     * 
     * @return A map of Cash objects with their IDs as keys
     */
    public Map<String, Cash> getAllCashes() {
        if(cashDB==null)return new HashMap<>();
        return cashDB.getAll();
    }

    /**
     * Gets a list of all Cash objects from the database.
     * 
     * @return A list of all Cash objects
     */
    public List<Cash> getAllCashList() {
        if(cashDB==null)return new ArrayList<>();
        Map<String, Cash> all = cashDB.getAll();
        if(all == null || all.isEmpty())return new ArrayList<>();
        return new ArrayList<>(all.values());
    }

    /**
     * Gets a Cash object by its ID.
     * 
     * @param id The ID of the Cash object to get
     * @return The Cash object, or null if not found
     */
    public Cash getCashById(String id) {
        return cashDB.get(id);
    }

    /**
     * Adds a Cash object to the database.
     * 
     * @param cash The Cash object to add
     */
    public void addValidCash(Cash cash) {
        if (cash.getId() == null) {
            cash.makeId();
        }
        cashDB.put(cash.getId(), cash);
        TimberLogger.i(TAG, "Added Cash with ID: %s", cash.getId());
    }

    /**
     * Adds multiple Cash objects to the database.
     *
     * @param cashList     The list of Cash objects to add
     */
    public int updateValidCashList(List<Cash> cashList, boolean isAppend) {
        List<Cash> validCashList = new ArrayList<>();
        List<Cash> spentCashList = new ArrayList<>();

        int added = 0;
        int removed = 0;
        for(Cash cash: cashList) {
            if(cash.getId() == null) {
                cash.makeId();
            }
            if(cash.isValid()) {
                validCashList.add(cash);
                added++;
            }else{
                spentCashList.add(cash);
                removed++;
            }
        }
        if(added>0) {
            if(isAppend)cashDB.addAll(validCashList,ID);
            else cashDB.insertAll(validCashList);
            TimberLogger.i(TAG, "%s cashes added.", added);
        }
        if(removed>0) {
            cashDB.remove(spentCashList);
            TimberLogger.i(TAG, "%s cashes removed.", removed);
        }
        return added;
    }

    /**
     * Removes a Cash object from the database.
     * 
     * @param cash The Cash object to remove
     */
    public void removeCash(Cash cash) {
        cashDB.remove(cash.getId());
    }

    public void removeCash(String cashId) {
        cashDB.remove(cashId);
    }

    /**
     * Removes multiple Cash objects from the database.
     * 
     * @param cashes The list of Cash objects to remove
     */
    public void removeCashList(List<Cash> cashes) {
        cashDB.remove(cashes);
    }

    /**
     * Commits changes to the database.
     */
    public void commit() {
        cashDB.commit();
    }

    /**
     * Gets a paginated list of Cash objects.
     * 
     * @param pageSize The number of items per page
     * @param lastIndex The index of the last item from the previous page, or null for the first page
     * @param timeAsc Whether to sort in timeAsc order
     * @return A list of Cash objects for the requested page
     */
    public List<Cash> getPaginatedCashes(int pageSize, Long lastIndex, boolean timeAsc) {
        return cashDB.getList(pageSize, null, lastIndex, true, null, null, false, !timeAsc);
    }

    /**
     * Gets the index of a Cash object by its ID.
     * 
     * @param id The ID of the Cash object
     * @return The index of the Cash object
     */
    public Long getIndexById(String id) {
        return cashDB.getIndexById(id);
    }

    /**
     * Checks if a Cash with the given ID already exists in the database.
     * 
     * @param id The ID to check
     * @return true if the cash exists, false otherwise
     */
    public boolean checkIfExisted(String id) {
        return cashDB.get(id) != null;
    }

    /**
     * Utility method to save multiple Cash objects, commit, show a toast, set result, and finish the activity.
     */
    public static void saveAndFinish(android.app.Activity activity, List<Cash> cashList) {
        CashManager cashManager = CashManager.getInstance();
        int added = cashManager.updateValidCashList(cashList, true);
        if(added>0)
            Toast.makeText(activity, activity.getString(com.fc.freer.R.string.added_new_cash, added), Toast.LENGTH_SHORT).show();
        cashManager.commit();
        activity.setResult(android.app.Activity.RESULT_OK);
        activity.finish();
    }

    public String getMainFid() {
        return mainFid;
    }

    public void setMainFid(String mainFid) {
        this.mainFid = mainFid;
    }

    public List<Cash> getValidCashes(double payValue, Long cd, int outputSize, int msgSize, double feeRate, Multisign multisign) {
        long payValueLong = FchUtils.coinToSatoshi(payValue);
        List<Cash> inputCashes = new ArrayList<>();
        
        // 从数据库按索引倒序获取所有 cash
        List<Cash> allCashes = cashDB.getList(null, null, null, false, null, null, false, true);
        
        long totalValue = 0;
        long totalCd = 0;
        long fee = 0;
        
        // 按索引倒序遍历所有 cash
        for (Cash cash : allCashes) {
            // 只处理有效的 cash
            if (cash.isValid() == null || !cash.isValid()) {
                continue;
            }
            
            // 计算并更新该 cash 的 cd
            Long currentCd = cash.makeCd();
            if (currentCd == null) {
                continue; // 跳过无法计算 cd 的 cash
            }
            
            // 将 cash 添加到输入列表
            inputCashes.add(cash);
            
            // 更新总值和总 cd
            totalValue += cash.getValue();
            totalCd += currentCd;
            
            // 重新计算手续费（因为输入数量发生了变化）
            boolean isMultiSign= multisign != null;
            fee = TxCreator.calcFee(inputCashes.size(), outputSize, msgSize, feeRate, isMultiSign, multisign);
            
            // 检查是否满足条件：总值 >= 支付值 + 手续费，且总 cd >= 要求的 cd
            if (totalValue >= (payValueLong + fee) && totalCd >= cd) {
                break;
            }
        }
        
        // 如果满足条件，返回收集到的 cash 列表
        if (totalValue >= (payValueLong + fee) && totalCd >= cd) {
            return inputCashes;
        } else {
            // 如果不满足条件，返回空列表
            return new ArrayList<>();
        }
    }

    /**
     * Gets the ApipClient from ApiCenter for API operations.
     * 
     * @return ApipClient instance, or null if not available
     */
    public ApipClient loadApipClient() {
        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            Object client = apiCenter.getClient(Service.ServiceType.APIP);
            if (client instanceof ApipClient) {
                return (ApipClient) client;
            }
            TimberLogger.w(TAG, "No ApipClient available from ApiCenter");
            return null;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting ApipClient from ApiCenter: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Saves a status string list as a comma-separated string to the state map.
     * 
     * @param statusKey The key for the status (e.g., "latest" or "earliest")
     * @param statusList The list of strings to save
     */
    private void saveStatusList(String statusKey, List<String> statusList) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot save status: Cash database not available");
            return;
        }
        
        if (statusList == null || statusList.isEmpty()) {
            cashDB.removeState(statusKey);
            TimberLogger.i(TAG, "Removed status for key: %s", statusKey);
            return;
        }
        
        String statusString = String.join(",", statusList);
        cashDB.putState(statusKey, statusString);
        TimberLogger.i(TAG, "Saved status for key '%s': %s", statusKey, statusString);
    }

    /**
     * Retrieves a status string list from the state map.
     * 
     * @param statusKey The key for the status (e.g., "latest" or "earliest")
     * @return List of strings, or empty list if not found or empty
     */
    private List<String> getStatusList(String statusKey) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot get status: Cash database not available");
            return new ArrayList<>();
        }
        
        Object statusObj = cashDB.getState(statusKey);
        if (statusObj == null) {
            return new ArrayList<>();
        }
        
        String statusString = statusObj.toString();
        if (statusString.isEmpty()) {
            return new ArrayList<>();
        }
        
        return Arrays.asList(statusString.split(","));
    }

    /**
     * Saves the "latest" status list.
     * 
     * @param latest List of status strings
     */
    public void saveStateLatestIncome(List<String> latest) {
        saveStatusList(FieldNames.LATEST_INCOME, latest);
        TimberLogger.d(TAG, "Saved %s state: %s", FieldNames.LATEST_INCOME, latest);

    }

    /**
     * Gets the "latest" status list.
     * 
     * @return List of status strings, or empty list if not found
     */
    public List<String> getStateLatestIncome() {
        return getStatusList(FieldNames.LATEST_INCOME);
    }

    /**
     * Saves the "earliest" status list.
     * 
     * @param earliest List of status strings
     */
    public void saveStateEarliestIncome(List<String> earliest) {
        saveStatusList(FieldNames.EARLIEST_INCOME, earliest);
        TimberLogger.d(TAG, "Saved %s state: %s", FieldNames.EARLIEST_INCOME, earliest);

    }
    public List<String> getStateEarliestIncome() {
        return getStatusList(FieldNames.EARLIEST_INCOME);
    }
    /**
     * Saves the "lastHeight" value.
     * 
     * @param height The height value to save
     */
    public void saveLastHeight(Long height) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot save lastHeight: Cash database not available");
            return;
        }
        
        if (height == null) {
            cashDB.removeState(FieldNames.LAST_HEIGHT);
            TimberLogger.i(TAG, "Removed lastHeight");
            return;
        }
        
        cashDB.putState(FieldNames.LAST_HEIGHT, height.toString());
        TimberLogger.i(TAG, "Saved lastHeight: %s", height);
    }

    /**
     * Gets the "lastHeight" value.
     * 
     * @return The height value, or null if not found
     */
    public Long getLastHeight() {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot get lastHeight: Cash database not available");
            return null;
        }
        
        Object heightObj = cashDB.getState(FieldNames.LAST_HEIGHT);
        if (heightObj == null) {
            return null;
        }
        
        try {
            return Long.parseLong(heightObj.toString());
        } catch (NumberFormatException e) {
            TimberLogger.e(TAG, "Invalid lastHeight format: %s", heightObj.toString());
            return null;
        }
    }

    /**
     * Removes a status from the state map.
     * 
     * @param statusKey The key for the status to remove
     */
    public void removeStatus(String statusKey) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot remove status: Cash database not available");
            return;
        }
        
        cashDB.removeState(statusKey);
        TimberLogger.i(TAG, "Removed status for key: %s", statusKey);
    }

    /**
     * Gets all saved status entries from the state map.
     * 
     * @return Map of all status entries (key -> comma-separated string)
     */
    public Map<String, String> getAllStatus() {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot get all status: Cash database not available");
            return new HashMap<>();
        }
        return cashDB.getAllState();
    }

    /**
     * Clears all status entries from the state map.
     */
    public void clearAllStatus() {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot clear all status: Cash database not available");
            return;
        }
        
        cashDB.clearAllState();
        TimberLogger.i(TAG, "Cleared all status entries");
    }

    /**
     * Fetches all valid cash for the mainFid from the API using pagination.
     * Creates an FCDSL query with terms 'valid=true' and equals 'owner=mainFid'.
     * Uses pagination with size=200 to fetch all results.
     *
     * @param context       Activity context for dialog operations
     * @param authType      Authentication type
     * @param requestMethod Request method to use
     * @param last          Starting point for pagination, null to fetch from beginning
     * @param onlyValid
     * @param timeAsc
     * @return List of all valid Cash objects, or null if failed
     */
    private List<Cash> fetchAllCash(Context context, AuthType authType, RequestMethod requestMethod, List<String> last, boolean onlyValid, boolean timeAsc) {
        if (apipClient == null) {
            TimberLogger.w(TAG, "Cannot fetch valid cash: ApipClient not available");
            return null;
        }

        if (mainFid == null || mainFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch valid cash: mainFid not set");
            return null;
        }

        List<Cash> allCashList = new ArrayList<>();
        final int pageSize = DEFAULT_LOAD_SIZE;
        int pageCount = 0;

        TimberLogger.i(TAG, "Starting to fetch all valid cash for mainFid: %s, starting from: %s", mainFid, last);
        List<String> after = last;

        while (true) {
            pageCount++;
            TimberLogger.d(TAG, "Fetching page %d with size %d", pageCount, pageSize);

            List<Cash> pageCashList = fetchCash(context, authType, requestMethod, pageSize, after,onlyValid, timeAsc);

            if (pageCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch cash list on page %d", pageCount);
                return allCashList.isEmpty() ? null : allCashList;
            }

            if (pageCashList.isEmpty()) {
                TimberLogger.i(TAG, "No more cash found, ending pagination at page %d", pageCount);
                break;
            }

            // Add to total list
            allCashList.addAll(pageCashList);

            TimberLogger.d(TAG, "Page %d returned %d cash items, total so far: %d", 
                pageCount, pageCashList.size(), allCashList.size());

            // Check if we need to continue pagination
            if (pageCashList.size() < pageSize) {
                TimberLogger.i(TAG, "Received less than page size (%d < %d), ending pagination", 
                    pageCashList.size(), pageSize);
                break;
            }

            // Get 'last' values for next page from the response body
            try {
                if (apipClient.getApiEvent() != null && 
                    apipClient.getApiEvent().getResponseBody() != null) {

                    after = apipClient.getApiEvent().getResponseBody().getLast();
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

            // Safety check to prevent infinite loops
            if (pageCount >= MAX_PAGE_COUNT) {
                TimberLogger.w(TAG, "Reached maximum page limit, stopping pagination");
                break;
            }
        }

        TimberLogger.i(TAG, "Completed fetching valid cash: %d items total in %d pages", 
            allCashList.size(), pageCount);

        // Save the new 'lastUpdated' state from response
        try {
            List<String> first = new ArrayList<>();
            if(!timeAsc){ //load all valid cash from the end
                Cash cash = allCashList.get(0);
                first.add(String.valueOf(cash.getLastTime()));
                first.add(cash.getId());
            }

            if(!timeAsc) {
                saveStateEarliestValid(after);
                saveStateLastUpdated(first);
            }
            else {
                saveStateLastUpdated(after);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving 'lastUpdated' state: %s", e.getMessage());
        }

        // Remove immature cashes if bestHeight is available
        removeImmatureCashes(allCashList, bestHeight);
        return allCashList;
    }

    private List<Cash> fetchCash(Context context, AuthType authType, RequestMethod requestMethod, int pageSize, List<String> after, boolean onlyValid, boolean timeAsc) {
        // Create FCDSL query
        Fcdsl fcdsl = new Fcdsl();
        // Add query with terms for valid=true
        fcdsl.addNewQuery();
        if(onlyValid)fcdsl.getQuery().addNewTerms().addNewFields(FieldNames.VALID).addNewValues(TRUE);

        if(timeAsc){
            fcdsl.addSort(LAST_TIME,ASC).addSort(ID,ASC);
        }else {
            fcdsl.addSort(LAST_TIME,DESC).addSort(ID,ASC);
        }
        // Add equals for owner=mainFid
        fcdsl.getQuery().addNewEquals().addNewFields(OWNER).addNewValues(mainFid);

        // Set page size
        fcdsl.addSize(pageSize);

        // Add after parameter for pagination (if not first page)
        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }

        // Make API request
        List<Cash> cashList = apipClient.cashSearch(fcdsl, requestMethod, authType, context);
        Long receivedBestHeight = apipClient.getApiEvent().getResponseBody().getBestHeight();
        if(receivedBestHeight!=null)bestHeight = receivedBestHeight;
        return cashList;
    }

    /**
     * Refreshes the entire valid cash database by fetching all valid cash from API
     * and replacing the local database contents.
     * 
     * @param context Activity context for dialog operations
     * @param authType Authentication type
     * @param requestMethod Request method to use
     * @return Number of cash items refreshed, or 0 if failed
     */
    public int freshValidCashDB(Context context, AuthType authType, RequestMethod requestMethod) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot refresh valid cash DB: Cash database not available");
            return 0;
        }

        TimberLogger.i(TAG, "Starting to refresh valid cash database for mainFid: %s", mainFid);
        saveStateLastUpdated(null);
        saveStateEarliestValid(null);

        try {
            // 1. Fetch all valid cash from API
            List<Cash> validCashList = fetchAllCash(context, authType, requestMethod, null, true, false);

            if (validCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch valid cash from API, database not refreshed");
                return 0;
            }

            removeImmatureCashes(validCashList,bestHeight);

            if (validCashList.isEmpty()) {
                TimberLogger.i(TAG, "No valid cash found on API");
            }

            // 2. Clear the existing cash database
            TimberLogger.i(TAG, "Clearing existing cash database");
            cashDB.clear();

            // 3. Save the new valid cash list
            int savedCount = 0;
            if (!validCashList.isEmpty()) {
                savedCount = updateValidCashList(validCashList, true);
            }

            // 4. Commit the changes
            cashDB.commit();
            
            TimberLogger.i(TAG, "Successfully refreshed valid cash database: %d items", savedCount);
            return savedCount;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error refreshing valid cash database: %s", e.getMessage());
            return 0;
        }
    }

    /**
     * Fetches income cash (received but not issued by mainFid) from the API.
     * Income cash is defined as: owner=mainFid AND valid=false AND issuer!=mainFid
     * 
     * @param context Activity context for dialog operations
     * @param order Sort order: "asc" for ascending, "desc" for descending
     * @param authType Authentication type
     * @param requestMethod Request method to use
     * @return List of income Cash objects, or null if failed
     */
    public List<Cash> fetchIncome(Context context, Integer size, List<String> last, String order, AuthType authType, RequestMethod requestMethod) {
        if (apipClient == null) {
            TimberLogger.w(TAG, "Cannot fetch income: ApipClient not available");
            return null;
        }

        if (mainFid == null || mainFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch income: mainFid not set");
            return null;
        }

        if (order == null || (!order.equals("asc") && !order.equals("desc"))) {
            TimberLogger.w(TAG, "Invalid order parameter: %s. Must be 'asc' or 'desc'", order);
            return null;
        }

        TimberLogger.i(TAG, "Fetching income cash for mainFid: %s with order: %s", mainFid, order);

        try {
            // Create FCDSL query
            Fcdsl fcdsl = new Fcdsl();
            fcdsl.setIndex(IndicesNames.CASH);  // Cash index name

            // Add query with terms for owner=mainFid
            fcdsl.addNewQuery().addNewTerms().addNewFields(OWNER).addNewValues(mainFid);

            // Add except with equals for issuer=mainFid (exclude cash issued by mainFid)
            fcdsl.addNewExcept();
            fcdsl.getExcept().addNewEquals().addNewFields(ISSUER).addNewValues(mainFid);
            
            // Set page size to default 20
            fcdsl.addSize(size==null? DEFAULT_PAGE_SIZE :size);
            // Add sorting based on order
            if (ASC.equals(order)) {
                fcdsl.addSort(BIRTH_TIME, ASC);
                fcdsl.addSort(CASH_ID, ASC);
                if(last==null)last = getStateLatestIncome();
                if(last!=null)fcdsl.addAfter(last);
            } else { // "desc"
                fcdsl.addSort(BIRTH_TIME, DESC);
                fcdsl.addSort(CASH_ID, DESC);
                if(last==null)last = getStateEarliestIncome();
                if(last!=null)fcdsl.addAfter(last);
            }

            // Make API request
            List<Cash> incomeCashList = apipClient.cashSearch(fcdsl, requestMethod, authType, context);
            
            if (incomeCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch income cash list");
                return null;
            }

            TimberLogger.i(TAG, "Fetched %d income cash items", incomeCashList.size());

            // Save the response body 'last' values as state
            try {
                if (apipClient.getApiEvent() != null && 
                    apipClient.getApiEvent().getResponseBody() != null) {
                    last = apipClient.getApiEvent().getResponseBody().getLast();
                    
                    if (last != null && !last.isEmpty()) {
                        if(ASC.equals(order))saveStateLatestIncome(last);
                        else saveStateEarliestIncome(last);
                    } else {
                        TimberLogger.d(TAG, "No 'last' field in response for income fetch");
                    }
                } else {
                    TimberLogger.w(TAG, "Cannot access response body for income state saving");
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error saving income state: %s", e.getMessage());
            }

            return incomeCashList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching income cash: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Saves the "latest expense" status list.
     * 
     * @param latest List of status strings
     */
    public void saveStateLatestExpense(List<String> latest) {
        saveStatusList(FieldNames.LATEST_EXPENSE, latest);
        TimberLogger.d(TAG, "Saved %s state: %s", FieldNames.LATEST_EXPENSE, latest);
    }

    /**
     * Gets the "latest expense" status list.
     * 
     * @return List of status strings, or empty list if not found
     */
    public List<String> getStateLatestExpense() {
        return getStatusList(FieldNames.LATEST_EXPENSE);
    }

    /**
     * Saves the "earliest expense" status list.
     * 
     * @param earliest List of status strings
     */
    public void saveStateEarliestExpense(List<String> earliest) {
        saveStatusList(FieldNames.EARLIEST_EXPENSE, earliest);
        TimberLogger.d(TAG, "Saved %s state: %s", FieldNames.EARLIEST_EXPENSE, earliest);

    }

    /**
     * Gets the "earliest expense" status list.
     * 
     * @return List of status strings, or empty list if not found
     */
    public List<String> getStateEarliestExpense() {
        return getStatusList(FieldNames.EARLIEST_EXPENSE);
    }

    /**
     * Fetches expense cash (issued by mainFid but not owned by mainFid) from the API.
     * Expense cash is defined as: issuer=mainFid AND owner!=mainFid
     * 
     * @param context Activity context for dialog operations
     * @param order Sort order: "asc" for ascending, "desc" for descending
     * @param authType Authentication type
     * @param requestMethod Request method to use
     * @return List of expense Cash objects, or null if failed
     */
    public List<Cash> fetchExpense(Context context, String order, AuthType authType, RequestMethod requestMethod) {
        if (apipClient == null) {
            TimberLogger.w(TAG, "Cannot fetch expense: ApipClient not available");
            return null;
        }

        if (mainFid == null || mainFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch expense: mainFid not set");
            return null;
        }

        if ((!ASC.equals(order) && !DESC.equals(order))) {
            TimberLogger.w(TAG, "Invalid order parameter: %s. Must be 'asc' or 'desc'", order);
            return null;
        }

        TimberLogger.i(TAG, "Fetching expense cash for mainFid: %s with order: %s", mainFid, order);

        try {
            // Create FCDSL query
            Fcdsl fcdsl = new Fcdsl();
            fcdsl.setIndex(IndicesNames.CASH);  // Cash index name

            // Add query with terms for issuer=mainFid
            fcdsl.addNewQuery().addNewTerms().addNewFields(ISSUER).addNewValues(mainFid);

            // Add except with equals for owner=mainFid (exclude cash owned by mainFid)
            fcdsl.addNewExcept();
            fcdsl.getExcept().addNewEquals().addNewFields(OWNER).addNewValues(mainFid);
            
            // Set page size to default 20
            fcdsl.addSize(DEFAULT_PAGE_SIZE);
            
            // Add sorting based on order and load pagination state
            if (ASC.equals(order)) {
                fcdsl.addSort(BIRTH_TIME, ASC);
                fcdsl.addSort(CASH_ID, ASC);
                List<String> last = getStateLatestExpense();
                if(last != null && !last.isEmpty()) {
                    fcdsl.addAfter(last);
                }
            } else { // "desc"
                fcdsl.addSort(BIRTH_TIME, DESC);
                fcdsl.addSort(CASH_ID, DESC);
                List<String> last = getStateEarliestExpense();
                if(last != null && !last.isEmpty()) {
                    fcdsl.addAfter(last);
                }
            }

            // Make API request
            List<Cash> expenseCashList = apipClient.cashSearch(fcdsl, requestMethod, authType, context);
            
            if (expenseCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch expense cash list");
                return null;
            }

            TimberLogger.i(TAG, "Fetched %d expense cash items", expenseCashList.size());

            // Save the response body 'last' values as state
            try {
                if (apipClient.getApiEvent() != null && 
                    apipClient.getApiEvent().getResponseBody() != null) {
                    List<String> last = apipClient.getApiEvent().getResponseBody().getLast();
                    
                    if (last != null && !last.isEmpty()) {
                        if (ASC.equals(order)) {
                            saveStateLatestExpense(last);
                        } else {
                            saveStateEarliestExpense(last);
                        }
                        TimberLogger.d(TAG, "Saved expense state for order %s: %s", order, last);
                    } else {
                        TimberLogger.d(TAG, "No 'last' field in response for expense fetch");
                    }
                } else {
                    TimberLogger.w(TAG, "Cannot access response body for expense state saving");
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error saving expense state: %s", e.getMessage());
            }

            return expenseCashList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching expense cash: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Simplifies a Cash object for income storage by keeping only essential fields:
     * id, issuer, value, birthTime, birthTxId
     * 
     * @param originalCash The original Cash object
     * @return Simplified Cash object for income
     */
    private Cash simplifyIncomeCash(Cash originalCash) {
        if (originalCash == null) {
            return null;
        }
        
        Cash simplifiedCash = new Cash();
        simplifiedCash.setId(originalCash.getId());
        simplifiedCash.setIssuer(originalCash.getIssuer());
        simplifiedCash.setValue(originalCash.getValue());
        simplifiedCash.setBirthTime(originalCash.getBirthTime());
        simplifiedCash.setBirthTxId(originalCash.getBirthTxId());
        
        return simplifiedCash;
    }

    /**
     * Simplifies a Cash object for expense storage by keeping only essential fields:
     * id, owner, value, birthTime, birthTxId
     * 
     * @param originalCash The original Cash object
     * @return Simplified Cash object for expense
     */
    private Cash simplifyExpenseCash(Cash originalCash) {
        if (originalCash == null) {
            return null;
        }
        
        Cash simplifiedCash = new Cash();
        simplifiedCash.setId(originalCash.getId());
        simplifiedCash.setOwner(originalCash.getOwner());
        simplifiedCash.setValue(originalCash.getValue());
        simplifiedCash.setBirthTime(originalCash.getBirthTime());
        simplifiedCash.setBirthTxId(originalCash.getBirthTxId());
        
        return simplifiedCash;
    }

    /**
     * Saves a list of income cash objects to the income list in LocalDB after simplifying them.
     * Only keeps essential fields: id, issuer, value, birthTime, birthTxId
     * 
     * @param incomeCashList List of income Cash objects to save
     * @param appendToEnd If true, append to end of list; if false, insert at beginning
     * @return Number of cash items saved, or -1 if failed
     */
    public int saveIncomes(List<Cash> incomeCashList, boolean appendToEnd) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot save income cash list: Cash database not available");
            return -1;
        }

        if (incomeCashList == null || incomeCashList.isEmpty()) {
            TimberLogger.i(TAG, "No income cash items to save");
            return 0;
        }

        try {
            // Create income list if it doesn't exist
            cashDB.createOrderedList(FieldNames.INCOME_LIST, Cash.class);

            // Simplify each cash object for income storage
            List<Cash> simplifiedIncomes = new ArrayList<>();
            for (Cash originalCash : incomeCashList) {
                Cash simplifiedCash = simplifyIncomeCash(originalCash);
                if (simplifiedCash != null) {
                    simplifiedIncomes.add(simplifiedCash);
                }
            }

            if (simplifiedIncomes.isEmpty()) {
                TimberLogger.w(TAG, "No valid income cash items after simplification");
                return 0;
            }

            int savedCount = 0;
            if (appendToEnd) {
                // Add to end of list (normal order)
                savedCount = (int) cashDB.addAllToList(FieldNames.INCOME_LIST, simplifiedIncomes);
                TimberLogger.i(TAG, "Appended %d simplified income cash items to end of income list", savedCount);
            } else {
                // Insert at beginning using new insertAllIntoList method
                savedCount = cashDB.insertAllIntoList(FieldNames.INCOME_LIST, 0, simplifiedIncomes);
                TimberLogger.i(TAG, "Inserted %d simplified income cash items at beginning of income list", savedCount);
            }
            
            return savedCount;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving income cash list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Saves a list of expense cash objects to the expense list in LocalDB after simplifying them.
     * Only keeps essential fields: id, owner, value, birthTime, birthTxId
     * 
     * @param expenseCashList List of expense Cash objects to save
     * @param appendToEnd If true, append to end of list; if false, insert at beginning
     * @return Number of cash items saved, or -1 if failed
     */
    public int saveExpenses(List<Cash> expenseCashList, boolean appendToEnd) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot save expense cash list: Cash database not available");
            return -1;
        }

        if (expenseCashList == null || expenseCashList.isEmpty()) {
            TimberLogger.i(TAG, "No expense cash items to save");
            return 0;
        }

        try {
            // Create expense list if it doesn't exist
            cashDB.createOrderedList("expenseList", Cash.class);

            // Simplify each cash object for expense storage
            List<Cash> simplifiedExpenses = new ArrayList<>();
            for (Cash originalCash : expenseCashList) {
                Cash simplifiedCash = simplifyExpenseCash(originalCash);
                if (simplifiedCash != null) {
                    simplifiedExpenses.add(simplifiedCash);
                }
            }

            if (simplifiedExpenses.isEmpty()) {
                TimberLogger.w(TAG, "No valid expense cash items after simplification");
                return 0;
            }

            int savedCount = 0;
            if (appendToEnd) {
                // Add to end of list (normal order)
                savedCount = (int) cashDB.addAllToList("expenseList", simplifiedExpenses);
                TimberLogger.i(TAG, "Appended %d simplified expense cash items to end of expense list", savedCount);
            } else {
                // Insert at beginning using new insertAllIntoList method
                savedCount = cashDB.insertAllIntoList("expenseList", 0, simplifiedExpenses);
                TimberLogger.i(TAG, "Inserted %d simplified expense cash items at beginning of expense list", savedCount);
            }
            
            return savedCount;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving expense cash list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Updates income by fetching all new incomes from the API using pagination.
     * Starts with order='asc' and last=null in the first round, then uses the 
     * birthTime and id of the last cash as 'last' in subsequent rounds until 
     * the returned list size is less than the page size.
     * 
     * @param context Activity context for dialog operations
     * @param authType Authentication type
     * @param requestMethod Request method to use
     * @return Total number of income cash items fetched, or -1 if failed
     */
    public int updateIncome(Context context, AuthType authType, RequestMethod requestMethod) {
        if (apipClient == null) {
            TimberLogger.w(TAG, "Cannot update income: ApipClient not available");
            return -1;
        }

        if (mainFid == null || mainFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot update income: mainFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting to update income for mainFid: %s", mainFid);

        List<Cash> allNewIncomes = new ArrayList<>();
        List<String> last = null;
        int roundCount = 0;
        final int pageSize = DEFAULT_PAGE_SIZE;

        try {
            while (true) {
                roundCount++;
                TimberLogger.d(TAG, "Fetching income round %d with last: %s", roundCount, last);

                // Fetch income with current pagination state
                List<Cash> roundIncomes = fetchIncome(context, pageSize, last, ASC, authType, requestMethod);
                
                if (roundIncomes == null) {
                    TimberLogger.w(TAG, "Failed to fetch income in round %d", roundCount);
                    return allNewIncomes.isEmpty() ? -1 : allNewIncomes.size();
                }

                if (roundIncomes.isEmpty()) {
                    TimberLogger.i(TAG, "No more income found, ending pagination at round %d", roundCount);
                    break;
                }

                // Add to total list
                allNewIncomes.addAll(roundIncomes);
                TimberLogger.d(TAG, "Round %d returned %d income items, total so far: %d", 
                    roundCount, roundIncomes.size(), allNewIncomes.size());

                // Check if we need to continue pagination
                if (roundIncomes.size() < pageSize) {
                    TimberLogger.i(TAG, "Received less than page size (%d < %d), ending pagination", 
                        roundIncomes.size(), pageSize);
                    break;
                }

                // Get the last cash item to prepare for next round
                Cash lastCash = roundIncomes.get(roundIncomes.size() - 1);
                if (lastCash.getBirthTime() == null || lastCash.getId() == null) {
                    TimberLogger.w(TAG, "Last cash missing birthTime or id, cannot continue pagination");
                    break;
                }

                // Prepare 'last' parameter for next round using birthTime and id
                last = Arrays.asList(String.valueOf(lastCash.getBirthTime()), lastCash.getId());
                TimberLogger.d(TAG, "Prepared 'last' for next round: [%s, %s]", 
                    lastCash.getBirthTime(), lastCash.getId());

                // Safety check to prevent infinite loops
                if (roundCount > 100) {
                    TimberLogger.w(TAG, "Reached maximum round limit (100), stopping pagination");
                    break;
                }
            }

            TimberLogger.i(TAG, "Completed updating income: %d items total in %d rounds", 
                allNewIncomes.size(), roundCount);

            // Save the fetched incomes if any were found
            if (!allNewIncomes.isEmpty()) {
                int savedCount = saveIncomes(allNewIncomes, false); // insert at beginning
                if (savedCount > 0) {
                    commit(); // Commit the changes to database
                    TimberLogger.i(TAG, "Successfully saved %d new income items to database", savedCount);
                } else {
                    TimberLogger.w(TAG, "Failed to save income items to database");
                }
            }

            return allNewIncomes.size();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating income: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Updates expense by fetching all new expenses from the API using pagination.
     * Starts with order='asc' and uses the saved state for pagination. Uses the 
     * birthTime and id of the last cash as 'last' in subsequent rounds until 
     * the returned list size is less than the page size.
     * 
     * @param context Activity context for dialog operations
     * @param authType Authentication type
     * @param requestMethod Request method to use
     * @return Total number of expense cash items fetched, or -1 if failed
     */
    public int updateExpense(Context context, AuthType authType, RequestMethod requestMethod) {
        if (apipClient == null) {
            TimberLogger.w(TAG, "Cannot update expense: ApipClient not available");
            return -1;
        }

        if (mainFid == null || mainFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot update expense: mainFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting to update expense for mainFid: %s", mainFid);

        List<Cash> allNewExpenses = new ArrayList<>();
        int roundCount = 0;
        final int pageSize = DEFAULT_PAGE_SIZE;

        try {
            while (true) {
                roundCount++;
                TimberLogger.d(TAG, "Fetching expense round %d", roundCount);

                // Fetch expense with current pagination state
                List<Cash> roundExpenses = fetchExpense(context, ASC, authType, requestMethod);
                
                if (roundExpenses == null) {
                    TimberLogger.w(TAG, "Failed to fetch expense in round %d", roundCount);
                    return allNewExpenses.isEmpty() ? -1 : allNewExpenses.size();
                }

                if (roundExpenses.isEmpty()) {
                    TimberLogger.i(TAG, "No more expense found, ending pagination at round %d", roundCount);
                    break;
                }

                // Add to total list
                allNewExpenses.addAll(roundExpenses);
                TimberLogger.d(TAG, "Round %d returned %d expense items, total so far: %d", 
                    roundCount, roundExpenses.size(), allNewExpenses.size());

                // Check if we need to continue pagination
                if (roundExpenses.size() < pageSize) {
                    TimberLogger.i(TAG, "Received less than page size (%d < %d), ending pagination", 
                        roundExpenses.size(), pageSize);
                    break;
                }

                // Safety check to prevent infinite loops
                if (roundCount > 100) {
                    TimberLogger.w(TAG, "Reached maximum round limit (100), stopping pagination");
                    break;
                }
            }

            TimberLogger.i(TAG, "Completed updating expense: %d items total in %d rounds", 
                allNewExpenses.size(), roundCount);

            // Save the fetched expenses if any were found
            if (!allNewExpenses.isEmpty()) {
                int savedCount = saveExpenses(allNewExpenses, false); // insert at beginning
                if (savedCount > 0) {
                    commit(); // Commit the changes to database
                    TimberLogger.i(TAG, "Successfully saved %d new expense items to database", savedCount);
                } else {
                    TimberLogger.w(TAG, "Failed to save expense items to database");
                }
            }

            return allNewExpenses.size();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating expense: %s", e.getMessage());
            return -1;
        }
    }

    public static boolean removeImmatureCashes(List<Cash> cashList, Long bestHeight){
        if(cashList==null || bestHeight==null)return false;
        boolean removed = false;
        Iterator<Cash> iterator = cashList.iterator();
        while (iterator.hasNext()) {
            Cash cash = iterator.next();
            if (isImmature(cash, bestHeight)) {
                iterator.remove();
                removed = true;
            }
        }
        return removed;
    }

    public static Boolean isImmature(Cash cash, Long bestHeight) {
        if(bestHeight==null)return null;
        if(! COINBASE.equals(cash.getIssuer()))return false;

        if(cash.getOwner().equals(Constants.FUND_FID)){
            if( (bestHeight - cash.getBirthHeight()) < FUND_MATURE_DAYS_INT)return true;
        }

        return bestHeight - cash.getBirthHeight() < MINE_MATURE_DAYS_INT;
    }
//
//    public FcReplyBody send(@Nullable List<Cash> cashList,  @Nullable Long cd, @Nullable List<SendTo> sendToList, @Nullable String opReturn, Double feeRate, String changeTo, MainNetParams mainNetParams, @Nullable BufferedReader br, Multisign multisign){
//        TxResultType resultType = TxResultType.NULL;
//        if(feeRate==null)feeRate=DEFAULT_FEE_RATE;
//        List<Cash> meetList;
//        int opReturnSize = opReturn == null ? 0 : opReturn.getBytes().length;
//        double amount;
//
//        if(cashList!=null)meetList = cashList;
//        else {
//            if(sendToList!=null && !sendToList.isEmpty())
//                amount = sendToList.stream().mapToDouble(SendTo::getAmount).sum();
//            else return null;
//            meetList = getValidCashes(amount,cd,sendToList.size(),opReturnSize,feeRate, multisign);
//            if(meetList==null || meetList.isEmpty()){
//                System.out.println("Can't get the valid cash list to make the TX");
//                RawTxInfo rawTxInfo = new RawTxInfo(mainFid, null, sendToList, opReturn, cd, feeRate, multisign, "2");
//                String result = rawTxInfo.toNiceJson();
//                return FcReplyBody.replyError(CodeMessage.Code2007CashNoFound);
//            }
//        }
//
//        long destroyingCd = Cash.sumCashCd(meetList);
//        if(cd!=null && destroyingCd < cd){
//            String error = "The required CD is not enough:"+destroyingCd+" < "+cd;
//            resultType = TxResultType.ERROR_STRING;
//            return resultType.addTypeAheadResult(error);
//        }
//
//        System.out.println("Destroying CD:"+destroyingCd);
//
//        if(br!=null && !askIfYes(br,"\nAre you sure to send?"))return resultType+"";
//
//
//        Integer maxJumpNum = getMaxJumpNum(meetList);
//        if(maxJumpNum>= Constants.MAX_JUMP_NUM){
//            updateCashesIfOverJumped();
//            String error = "The jump number is too large:"+ maxJumpNum;
//            resultType = TxResultType.ERROR_STRING;
//            return resultType.addTypeAheadResult(error);
//        }
//        String result;
//
//        if(prikey ==null){
//            RawTxInfo rawTxInfo = new RawTxInfo(mainFid, meetList, sendToList,opReturn,cd,feeRate, multisign,"2");
//            resultType = TxResultType.UNSIGNED_JSON;
//            result = rawTxInfo.toNiceJson();
//        }else {
//            String signedTx = signTx(meetList, sendToList, opReturn, multisign, feeRate, changeTo, mainNetParams);
//
//            if (nasaClient != null) {
//                result = nasaClient.sendRawTransaction(signedTx);
//                if(Hex.isHexString(result))resultType = TxResultType.TX_ID;
//                else resultType = TxResultType.ERROR_STRING;
//            }
//            else if (apipClient != null) {
//                result = apipClient.broadcastTx(signedTx, RequestMethod.POST, AuthType.FC_SIGN_BODY);
//                if(Hex.isHexString(result))resultType = TxResultType.TX_ID;
//                else resultType = TxResultType.ERROR_STRING;
//            }
//            else {
//                System.out.println("No way to broadcast the TX:");
//                result = signedTx;
//                resultType = TxResultType.SINGED_HEX;
//                System.out.println(result);
//                QRCodeUtils.generateQRCode(result);
//            }
//        }
//
//        double restAmount = calcRestAmount(meetList, sendToList, opReturnSize, null, false, null);
//
//        if(cid!=null) {
//            updateLocalInfo(meetList,sendToList, maxJumpNum, result, restAmount);
//        }
//        maxJumpNum = getMaxJumpNum();
//        if(maxJumpNum>=Constants.MAX_JUMP_NUM){
//            updateCashesIfOverJumped();
//        }
//        Iterator<Cash> iterator = meetList.iterator();
//        while(iterator.hasNext()){
//            Cash cash = iterator.next();
//            remove(cash.getId());
//            iterator.remove();
//        }
//
//        return resultType.addTypeAheadResult(result);
//    }

    /**
     * Updates valid cash by fetching new cashes from API based on saved 'lastValid' state.
     * If 'lastValid' state is null, fetches all valid cash from API.
     * Otherwise, fetches valid cash since the last saved state.
     *
     * @param context       Activity context for dialog operations
     * @param authType      Authentication type
     * @param requestMethod Request method to use
     * @return Number of valid cash items fetched, or -1 if failed
     */
    public int updateValidCash(Context context, AuthType authType, RequestMethod requestMethod, boolean isForEarlier) {
        if (apipClient == null) {
            TimberLogger.w(TAG, "Cannot update valid cash: ApipClient not available");
            return -1;
        }

        if (mainFid == null || mainFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot update valid cash: mainFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting to update valid cash for mainFid: %s", mainFid);

        try {
            // Get the saved 'lastUpdated' state from database
            List<String> lastUpdated = getStateLastUpdated();
            List<String> last;
            // Fetch valid cash from API
            boolean onlyValid;
            boolean timeAsc;
            if(isForEarlier || lastUpdated==null){
                last = getStateEarliestValid();
                isForEarlier = true;
                onlyValid = true;
                timeAsc = false;
            }else{
                last = lastUpdated;
                onlyValid = false;
                timeAsc = true;
            }

            List<Cash> changedCashList = fetchAllCash(context, authType, requestMethod, last, onlyValid, timeAsc);

            if (changedCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch valid cash from API");
                return 0;
            }

            if (changedCashList.isEmpty()) {
                TimberLogger.i(TAG, "No new valid cash items fetched");
                return 0;
            }

            // Add the fetched cash to database
            int added = updateValidCashList(changedCashList,!isForEarlier);
            commit();

            TimberLogger.i(TAG, "Successfully updated valid cash: %d items", changedCashList.size());
            return added;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating valid cash: %s", e.getMessage());
            return -1;
        }
    }

    private List<String> getStateLastUpdated() {
        Object obj = cashDB.getState(LAST_UPDATED);
        if(obj==null)return null;
        String lastStr = obj.toString();
        return  Arrays.asList(lastStr.split(","));

    }

    private void saveStateLastUpdated(List<String> statusList) {
        saveStatusList(LAST_UPDATED,statusList);
        if(statusList!=null)TimberLogger.i(TAG, "Last updated cash state saved: %s", statusList.get(0));

    }

    private List<String> getStateEarliestValid() {
        Object obj = cashDB.getState(EARLIEST_VALID);
        if(obj==null)return null;
        String lastStr = obj.toString();
        return  Arrays.asList(lastStr.split(","));

    }

    private void saveStateEarliestValid(List<String> statusList) {
        saveStatusList(EARLIEST_VALID,statusList);
        if(statusList!=null)TimberLogger.i(TAG, "Earliest valid cash state saved: %s", statusList.get(0));

    }

}