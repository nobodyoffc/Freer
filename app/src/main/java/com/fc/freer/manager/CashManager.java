package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.Constants.COINBASE;
import static com.fc.fc_ajdk.constants.Constants.FUND_MATURE_DAYS_INT;
import static com.fc.fc_ajdk.constants.Constants.MINE_MATURE_DAYS_INT;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_INDEX;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TX_INDEX;
import static com.fc.fc_ajdk.constants.FieldNames.CASH;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.ISSUER;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.VALID;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.NEWER;
import static com.fc.fc_ajdk.constants.Values.OLDER;
import static com.fc.fc_ajdk.constants.Values.OP_RETURN;
import static com.fc.fc_ajdk.constants.Values.REFRESH;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.data.fchData.Block;
import com.fc.fc_ajdk.data.fchData.Tx;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.freer.FreerApplication;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.FidTxMask;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.model.Expense;
import com.fc.freer.model.Income;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A singleton class to manage and share the Cash database across activities.
 * This provides a centralized way to access the Cash database from any activity.
 */
public class CashManager {
    private static final String TAG = "CashManager";

    private static final String LAST_HEIGHT_OF_INCOME = "last_height_of_income";
    private static final String LAST_HEIGHT_OF_EXPENSE = "last_height_of_expense";
    private static final String LAST_HEIGHT_OF_TX = "last_height_of_tx";

    public interface NewCashListener {
        void onNewCashChanged(int newCashCount);
    }

    private static CashManager instance;
    private LocalDB<Cash> cashDB;
    private String liveFid;
//    private FapiClient fapiClient;
    private Long bestHeight;
    private Long totalCashOnChain;
    private Long totalIncome;
    private Long totalExpense;
    private Long totalTx;
    private NewCashListener newCashListener;

    public void setNewCashListener(NewCashListener listener) {
        this.newCashListener = listener;
    }

    private void notifyNewCashChanged() {
        if (newCashListener != null) {
            newCashListener.onNewCashChanged(getNewCashCount());
        }
    }

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
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new CashManager();
            instance.liveFid = fid;
            // Reset all cached totals when switching FIDs
            instance.totalCashOnChain = null;
            instance.totalIncome = null;
            instance.totalExpense = null;
            instance.totalTx = null;
            instance.bestHeight = null;
            instance.initialize(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized CashManager getInstance() {
        return instance;
    }

    /**
     * Cleans up the CashManager instance and resets the singleton.
     * This should be called when switching passwords or clearing session data.
     */
    public static synchronized void cleanup() {
        if (instance != null) {
            try {
                // Close the database if it exists
                if (instance.cashDB != null) {
                    instance.cashDB.close();
                    TimberLogger.d(TAG, "CashManager database closed during cleanup");
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error closing CashManager database during cleanup: " + e.getMessage());
            }

            // Clear all cached data
            instance.liveFid = null;
            instance.cashDB = null;
            instance.bestHeight = null;
            instance.totalCashOnChain = null;
            instance.totalIncome = null;
            instance.totalExpense = null;
            instance.totalTx = null;

            // Reset the singleton instance
            instance = null;
            TimberLogger.d(TAG, "CashManager instance cleaned up and reset");
        }
    }

    public static void updateCashOfTx(String signedTxHex, Context context) {
        try {
            TxSender txSender = new TxSender();
            CashManager cashManager = getInstance();
            FidManager fidManager = FidManager.getInstance();
            String sender = fidManager.getLiveFid();

            if (sender != null && cashManager != null) {
                boolean updated = txSender.updateCashOfTx(signedTxHex, sender, context, cashManager);
                if (!updated) {
                    TimberLogger.w(TAG, "Failed to update cash DB after broadcast");
                }
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating cash DB: " + e.getMessage());
        }
    }

    /**
     * Initializes the CashManager with the given context.
     *
     * @param context The context to use for getting the DatabaseManager
     */
    public void  initialize(Context context) {
        DatabaseManager dbManager = DatabaseManager.getInstance();
        // Close existing database if it exists
        if (cashDB != null) {
            try {
                cashDB.close();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error closing database: " + e.getMessage());
            }
        }
        cashDB = dbManager.getEntityDatabase(liveFid, Cash.class, Cash.class);
        if(cashDB==null){
            TimberLogger.w(TAG, "No Cash database available from DatabaseManager when initiating CashManager.");
        }

        FapiClient fapiClient = loadFapiClient();
        if(fapiClient==null){
            TimberLogger.w(TAG, "No FapiClient available from ApiCenter when initiating CashManager.");
        }

        // Load cached totals from database state for the new FID
        totalCashOnChain = getStateTotal();

        if(Boolean.TRUE.equals(isRollback(context))) rollBack(context);

        // Try to load cash data - use refreshCashFromAPI if we have existing state, otherwise loadEarlierCashFromAPI
        refreshCashFromAPI(context);

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
     * Gets the count of new (unseen) cash items.
     */
    public int getNewCashCount() {
        List<Cash> allCash = getAllCashList();
        int count = 0;
        for (Cash cash : allCash) {
            if (Boolean.TRUE.equals(cash.getNew())) {
                count++;
            }
        }
        return count;
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
     * Updates a Cash object in the database.
     *
     * @param cash The Cash object to update
     */
    public void updateCash(Cash cash) {
        if (cash.getId() == null) {
            TimberLogger.w(TAG, "Cannot update Cash: ID is null");
            return;
        }
        cashDB.updateItemsOnly(Collections.singletonList(cash));
        TimberLogger.i(TAG, "Updated Cash with ID: %s", cash.getId());
    }

    /**
     * Updates multiple Cash objects in the database in a batch.
     *
     * @param cashList The list of Cash objects to update
     */
    public void updateCash(List<Cash> cashList) {
        if (cashList == null || cashList.isEmpty()) {
            TimberLogger.i(TAG, "No cash items to update");
            return ;
        }
        cashDB.updateItemsOnly(cashList);
    }

    /**
     * Adds multiple Cash objects to the database.
     *
     * @param cashList     The list of Cash objects to add
     */
    public int updateValidToDB(List<Cash> cashList, boolean isAppend) {
        List<Cash> validCashList = new ArrayList<>();
        List<Cash> spentCashList = new ArrayList<>();

        int added = 0;
        int removed = 0;
        boolean hasNewCash = false;
        for(Cash cash: cashList) {
            if(cash.getId() == null) {
                cash.makeId();
            }
            if(cash.isValid()) {
                validCashList.add(cash);
                added++;
                if (Boolean.TRUE.equals(cash.getNew())) {
                    hasNewCash = true;
                }
            }else{
                spentCashList.add(cash);
                removed++;
            }
        }
        if(added>0) {
            if(isAppend){
                cashDB.addAll(validCashList,ID);
            }
            else {
                java.util.Collections.reverse(cashList);
                cashDB.insertAll(validCashList);
            }
            TimberLogger.i(TAG, "%s cashes added.", added);
        }
        if(removed>0) {
            cashDB.remove(spentCashList);
            TimberLogger.i(TAG, "%s cashes removed.", removed);
        }
        if (hasNewCash) {
            notifyNewCashChanged();
        }
        return added;
    }

    /**
     * Updates database for earlier data loading.
     */
    private int updateToDBForEarlier(List<Cash> cashList) {
        if (cashList == null || cashList.isEmpty()) return 0;

        // Insert earlier data at the beginning
        cashDB.insertAll(cashList);

        int added = cashList.size();
        TimberLogger.i(TAG, "Successfully inserted earlier cash: %d added", added);
        return added;
    }

    /**
     * Updates database for newer data loading (includes cleanup of spent cash).
     */
    private int updateToDBForNewer(List<Cash> cashList) {
        if (cashList == null || cashList.isEmpty()) return 0;

        int added = 0;
        int removed = 0;

        // Load newer updated data with cleanup
        List<Cash> cashListToAdd = new ArrayList<>();
        List<Cash> cashListToRemove = new ArrayList<>();

        for (Cash cash : cashList) {
            try {
                if (Boolean.FALSE.equals(cash.isValid())) {
                    // Cash is inactive, remove from database
                    cashListToRemove.add(cash);
                    removed++;
                    continue;
                }
                cash.setNew(true);
                cashListToAdd.add(cash);
                added++;
            } catch (Exception e) {
                continue;
            }
        }

        // Batch operations
        if (!cashListToRemove.isEmpty()) {
            removeCashList(cashListToRemove);
        }

        if (!cashListToAdd.isEmpty()) {
            java.util.Collections.reverse(cashListToAdd);
            cashDB.addAll(cashListToAdd, ID);
        }

        TimberLogger.i(TAG, "Successfully updated newer cash: %d added, %d removed", added, removed);
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
     * @param pageSize  The number of items per page
     * @param lastID The ID of the last item from the previous page, or null for the first page
     * @param fromEnd   Whether to sort in fromEnd order
     * @return A list of Cash objects for the requested page
     */
    public List<Cash> getPaginatedCashes(int pageSize, String lastID, boolean fromEnd) {
        return cashDB.getList(pageSize, lastID, null, false, null, null, false, fromEnd);
    }

    /**
     * Gets the index of a Cash object by its ID.
     *
     * @param id The ID of the Cash object
     * @return The index of the Cash object
     */
    public Integer getIndexById(String id) {
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
        int added = cashManager.updateValidToDB(cashList, true);
        if(added>0)
            ToastUtils.makeText(activity, activity.getString(com.fc.freer.R.string.added_new_cash, added));
        cashManager.commit();
        activity.setResult(android.app.Activity.RESULT_OK);
        activity.finish();
    }

    public String getLiveFid() {
        return liveFid;
    }

    public void setLiveFid(String liveFid) {
        this.liveFid = liveFid;
    }

    public Long getTotalCashOnChain() {
        return totalCashOnChain;
    }

    public void setTotalCashOnChain(Long totalCashOnChain) {
        saveStateTotal(totalCashOnChain);
    }

    public Long getTotalIncome() {
        return totalIncome;
    }

    public void setTotalIncome(Long totalIncome) {
        this.totalIncome = totalIncome;
    }

    public Long getTotalExpense() {
        return totalExpense;
    }

    public void setTotalExpense(Long totalExpense) {
        this.totalExpense = totalExpense;
    }

    public Long getTotalTx() {
        return totalTx;
    }

    public void setTotalTx(Long totalTx) {
        this.totalTx = totalTx;
    }

    public long getCashDBSize() {
        if(cashDB == null) return 0;
        return cashDB.getSize();
    }

    /**
     * Gets valid cashes sufficient for the given payment amount.
     * Does not show toasts or require Context. Safe to call from background/auto-recharge.
     *
     * @return list of valid cashes, or null if insufficient
     */
    public List<Cash> getValidCashesForAmount(double payValue) {
        if (cashDB == null) return null;
        long payValueLong = FchUtils.coinToSatoshi(payValue);
        List<Cash> inputCashes = new ArrayList<>();
        List<Cash> allCashes = cashDB.getList(null, null, null, false, null, null, false, true);
        if (allCashes == null || allCashes.isEmpty()) return null;

        long totalValue = 0;
        long fee = 0;

        FapiClient fc = loadFapiClient();
        Long bestHeight = (fc != null) ? fc.getBestHeight() : null;

        for (Cash cash : allCashes) {
            if (cash.isValid() == null || !cash.isValid()) continue;
            if (Boolean.TRUE.equals(cash.getConflicted())) continue;
            if (bestHeight != null && cash.getLockTime() != null
                    && TxHandler.isLockTimeUnlocked(cash.getLockTime(), bestHeight))
                continue;

            inputCashes.add(cash);
            totalValue += cash.getValue();
            fee = TxHandler.calcFee(inputCashes.size(), 1, 0,
                    TxHandler.DEFAULT_FEE_RATE, false, null);
            if (totalValue >= (payValueLong + fee)) {
                return inputCashes;
            }
        }
        return null;
    }

    /**
     * Checks whether there are any valid cashes in the local database.
     */
    public boolean hasValidCashes() {
        boolean result = cashDB != null && cashDB.getSize() > 0;
        TimberLogger.d(TAG, "hasValidCashes: cashDB=%s, size=%d, result=%b",
                cashDB != null ? "available" : "null",
                cashDB != null ? cashDB.getSize() : 0,
                result);
        return result;
    }

    public List<Cash> getValidCashes(double payValue, Long cd, int outputSize, int msgSize, double feeRate, Multisig multisig, Context context) {
        long payValueLong = FchUtils.coinToSatoshi(payValue);
        List<Cash> inputCashes = new ArrayList<>();

        // 从数据库按索引倒序获取所有 cash
        List<Cash> allCashes = cashDB.getList(null, null, null, false, null, null, false, true);

        long totalValue = 0;
        long totalCd = 0;
        long fee = 0;
        if(cd==null)cd=0L;

        FapiClient fapiClientForHeight = loadFapiClient();
        Long bestHeight = (fapiClientForHeight != null) ? fapiClientForHeight.getBestHeight() : null;

        // 按索引倒序遍历所有 cash
        for (Cash cash : allCashes) {
            // 只处理有效的 cash
            if (cash.isValid() == null || !cash.isValid()) {
                continue;
            }

            // Skip conflicted cashes (spent in mempool)
            if (Boolean.TRUE.equals(cash.getConflicted())) {
                continue;
            }

            if(bestHeight!=null && cash.getLockTime()!=null)
                if(TxHandler.isLockTimeUnlocked(cash.getLockTime(), bestHeight))
                    continue;

            // 计算并更新该 cash 的 cd（基于区块高度）
            Long currentCd = (bestHeight != null) ? cash.makeCd(bestHeight) : null;
            if (currentCd == null)
                currentCd=0L;

            if(cd > 0 && currentCd <= 0) //当需要CD时，跳过CD为0的cash
                continue;

            // 将 cash 添加到输入列表
            inputCashes.add(cash);

            // 更新总值和总 cd
            totalValue += cash.getValue();
            totalCd += currentCd;

            // 重新计算手续费（因为输入数量发生了变化）
            boolean isMultiSign= multisig != null;
            fee = TxHandler.calcFee(inputCashes.size(), outputSize, msgSize, feeRate, isMultiSign, multisig);

            // 检查是否满足条件：总值 >= 支付值 + 手续费，且总 cd >= 要求的 cd
            if (totalValue >= (payValueLong + fee) && totalCd >= cd) {
                break;
            }
        }

        // 如果满足条件，返回收集到的 cash 列表
        if (totalValue < (payValueLong + fee) ) {
            ToastUtils.makeText(context, context.getString(R.string.not_enough_fch));
            return new ArrayList<>();
        }

        if(totalCd < cd){
            ToastUtils.makeText(context, context.getString(R.string.not_enough_cd));
            return new ArrayList<>();
        }

        return inputCashes;
    }


    /**
     * Gets the FapiClient from ApiCenter for API operations.
     *
     * @return FapiClient instance, or null if not available
     */
    public FapiClient loadFapiClient() {
        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            Object client = apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (client instanceof FapiClient) {
                return (FapiClient) client;
            }
            TimberLogger.w(TAG, "No FapiClient available from ApiCenter");
            return null;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting FapiClient from ApiCenter: %s", e.getMessage());
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
     * @param context   Activity context for dialog operations
     * @param last      Starting point for pagination, null to fetch from beginning
     * @param onlyValid Only fetch valid cash
     * @param timeAsc   Sort in time ascending order
     * @return List of all valid Cash objects, or null if failed
     */
    private List<Cash> fetchAllCash(Context context, List<String> last, boolean onlyValid, boolean timeAsc) {
        FapiClient fapiClient = loadFapiClient();

        if (fapiClient == null) {
            ToastUtils.showError(context, context.getString(R.string.apip_client_not_available));
            TimberLogger.w(TAG, "Cannot fetch valid cash: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch valid cash: mainFid not set");
            return null;
        }

        List<Cash> allCashList = new ArrayList<>();
        final int pageSize = FreerApplication.DEFAULT_REQUEST_SIZE;
        int pageCount = 0;

        TimberLogger.i(TAG, "Starting to fetch all valid cash for mainFid: %s, starting from: %s", liveFid, last);
        List<String> after = last;
        while (true) {
            pageCount++;
            TimberLogger.d(TAG, "Fetching page %d with size %d", pageCount, pageSize);

            List<Cash> pageCashList = fetchCash(context, pageSize, after,onlyValid, timeAsc);

            if (pageCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch cash list on page %d", pageCount);
                return allCashList.isEmpty() ? null : allCashList;
            }

            for(Cash cash :pageCashList){
                cash.setOnChain(true);
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
                if(onlyValid && !timeAsc)
                    saveStateHasMoreEarlier(Boolean.FALSE);
                else if(!onlyValid && timeAsc)
                    saveStateHasMoreNewer(Boolean.FALSE);
                TimberLogger.i(TAG, "Received less than page size (%d < %d), ending pagination",
                    pageCashList.size(), pageSize);
                break;
            }

            // Get 'last' values for next page from the response body
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

            // Safety check to prevent infinite loops
            if (pageCount >= FreerApplication.DEFAULT_REQUEST_PAGE_COUNT) {

                if(onlyValid && !timeAsc)
                    saveStateHasMoreEarlier(Boolean.TRUE);
                else if(!onlyValid && !timeAsc)
                    saveStateHasMoreNewer(Boolean.TRUE);
                TimberLogger.w(TAG, "Reached maximum page limit, stopping pagination");
                break;
            }
        }

        saveBestBlockInfo();

        TimberLogger.i(TAG, "Completed fetching valid cash: %d items total in %d pages", 
            allCashList.size(), pageCount);

        return allCashList;
    }

    private void saveStateHasMoreNewer(Boolean hasMore) {
        cashDB.putState(DatabaseManager.HAS_MORE_NEWER, String.valueOf(hasMore));
    }

    private Boolean getStateHasMoreNewer() {
        Object object = cashDB.getState(DatabaseManager.HAS_MORE_NEWER);
        if (object==null)return null;
        return Boolean.valueOf((String)object);
    }

    private List<Cash> fetchCash(Context context, int pageSize, List<String> after, boolean onlyValid, boolean timeAsc) {
        // Create FCDSL query
        Fcdsl fcdsl = new Fcdsl();
        // Add query with terms for valid=true
//        fcdsl.setIndex(CASH);
        fcdsl.addNewQuery();
        if(onlyValid)fcdsl.getQuery().addNewTerms().addNewFields(FieldNames.VALID).addNewValues(TRUE);

        if(timeAsc){
            fcdsl.addSort(LAST_HEIGHT,ASC).addSort(BIRTH_TX_INDEX,ASC).addSort(BIRTH_INDEX,ASC).addSort(ID,ASC);
        }else {
            fcdsl.addSort(LAST_HEIGHT,DESC).addSort(BIRTH_TX_INDEX,DESC).addSort(BIRTH_INDEX,DESC).addSort(ID,DESC);
        }
        // Add equals for owner=mainFid
        fcdsl.getQuery().addNewEquals().addNewFields(OWNER).addNewValues(liveFid);

        // Set page size
        fcdsl.addSize(pageSize);

        // Add after parameter for pagination (if not first page)
        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }

        // Make API request
        FapiClient fapiClient = loadFapiClient();

        if(fapiClient == null){
            ToastUtils.showError(context, context.getString(R.string.apip_client_not_available));
            return null;
        }

        return fapiClient.cashSearch(fcdsl);
    }

    /**
     * Refreshes the entire valid cash database by fetching all valid cash from API
     * and replacing the local database contents.
     *
     * @param context Activity context for dialog operations
     * @return Number of cash items refreshed, or 0 if failed
     */
    public int freshValidCashDB(Context context) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot refresh valid cash DB: Cash database not available");
            return 0;
        }

        TimberLogger.i(TAG, "Starting to refresh valid cash database for mainFid: %s", liveFid);
        saveStateLastUpdated(null);
        saveStateEarliestValid(null);

        try {
            // 1. Fetch all valid cash from API

            List<Cash> validCashList = fetchAllCash(context, null, true, false);

            if (validCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch valid cash from API, database not refreshed");
                return 0;
            }

            if (validCashList.isEmpty()) {
                return 0;
            }

            updateApiStatesForEarlier(validCashList);

            // Remove immature cashes if bestHeight is available
            int removed = removeImmatureCashes(validCashList,bestHeight);
            if(removed>0){
                ToastUtils.makeText(context, context.getString(R.string.removed_immature_cash, removed));
            }

            if (validCashList.isEmpty()) {
                TimberLogger.i(TAG, "No valid cash found on API");
            }

            // 2. Clear the existing cash database
            TimberLogger.i(TAG, "Clearing existing cash database");
            cashDB.clear();

            // 3. Save the new valid cash list
            int savedCount = 0;
            if (!validCashList.isEmpty()) {
                savedCount = updateValidToDB(validCashList, false);
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
     * Updates API states for earlier data loading.
     */
    private void updateApiStatesForEarlier(List<Cash> cashList) {
        if (cashList == null || cashList.isEmpty()) return;

        try {
            // Update earliest pagination info
            Cash earliest = cashList.get(cashList.size() - 1);
            List<String> earliestInfo = makeSortList(earliest);
            saveStateEarliestValid(earliestInfo);
            TimberLogger.i(TAG, "Updated earliest cash pagination: %s", earliestInfo);

            // If this is the first load (no lastUpdated), also set the latest
            List<String> lastUpdated = getStateLastUpdated();
            if (lastUpdated == null || lastUpdated.isEmpty()) {
                Cash latest = cashList.get(0);
                List<String> latestInfo = makeSortList(latest);
                saveStateLastUpdated(latestInfo);
                TimberLogger.i(TAG, "Updated latest cash pagination: %s", latestInfo);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating cash pagination info for earlier: %s", e.getMessage());
        }
    }

    @NonNull
    private static List<String> makeSortList(Cash latest) {
        return Arrays.asList(String.valueOf(latest.getLastHeight()), String.valueOf(latest.getBirthTxIndex()), String.valueOf(latest.getBirthIndex()), latest.getId());
    }

    /**
     * Updates API states for newer data loading.
     */
    private void updateApiStatesForNewer(List<Cash> cashList) {
        if (cashList == null || cashList.isEmpty()) return;

        try {
            // Update latest pagination info
            Cash latest = cashList.get(cashList.size() - 1);
            List<String> latestInfo = makeSortList(latest);
            saveStateLastUpdated(latestInfo);
            TimberLogger.i(TAG, "Updated latest cash pagination: %s", latestInfo);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating cash pagination info for newer: %s", e.getMessage());
        }
    }

    /**
     * Fetches expense cash (issued by mainFid but not owned by mainFid) from the API.
     * Expense cash is defined as: issuer=mainFid AND owner!=mainFid
     *
     * @param order Sort order: "asc" for ascending, "desc" for descending
     * @return List of expense Cash objects, or null if failed
     */
    public List<Expense> fetchExpense(Integer size, List<String> last, String order) {
        FapiClient fapiClient = loadFapiClient();

        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch expense: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch expense: mainFid not set");
            return null;
        }

        if (order == null || (!order.equals("asc") && !order.equals("desc"))) {
            TimberLogger.w(TAG, "Invalid order parameter: %s. Must be 'asc' or 'desc'", order);
            return null;
        }

        TimberLogger.i(TAG, "Fetching expense cash for mainFid: %s with order: %s", liveFid, order);

        try {
            // Create FCDSL query
            Fcdsl fcdsl = new Fcdsl();

            // Add query with terms for issuer=liveFid
            fcdsl.addNewQuery().addNewTerms().addNewFields(ISSUER).addNewValues(liveFid);

            // Add except with equals for owner=liveFid (exclude cash owned by liveFid)
            fcdsl.addNewExcept();
            fcdsl.getExcept().addNewTerms().addNewFields(OWNER).addNewValues(liveFid,OP_RETURN);

            // Set page size to default 20
            fcdsl.addSize(size==null? FreerApplication.DEFAULT_REQUEST_SIZE :size);
            // Add sorting based on order
            if (ASC.equals(order)) {
                fcdsl.addSort(BIRTH_TIME, ASC);
                fcdsl.addSort(ID, ASC);
                if(last!=null && !last.isEmpty())fcdsl.addAfter(last);
            } else { // "desc"
                fcdsl.addSort(BIRTH_TIME, DESC);
                fcdsl.addSort(ID, ASC);
                if(last!=null&& !last.isEmpty())fcdsl.addAfter(last);
            }

            // Make API request
            List<Expense> expenseList = new ArrayList<>();
            List<Cash> expenseCashList = fapiClient.cashSearch(fcdsl);
            
            if (expenseCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch expense cash list");
                return null;
            }

            TimberLogger.i(TAG, "Fetched %d expense cash items", expenseCashList.size());

            // Capture total from API response for statistics
            try {
                if (fapiClient.getLastResponse() != null) {
                    Long receivedTotal = fapiClient.getLastResponse().getTotal();
                    if (receivedTotal != null) {
                        setTotalExpense(receivedTotal);
                        TimberLogger.d(TAG, "Captured total expense from API: %d", receivedTotal);
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error capturing total expense from API response: %s", e.getMessage());
            }

            if(!expenseCashList.isEmpty())
                for (Cash cash : expenseCashList)
                    expenseList.add(Expense.fromCash(cash));

            return expenseList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching expense cash: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches income cash (received but not issued by mainFid) from the API.
     * Income cash is defined as: owner=mainFid AND valid=false AND issuer!=mainFid
     *
     * @param order Sort order: "asc" for ascending, "desc" for descending
     * @return List of income Cash objects, or null if failed
     */
    public List<Income> fetchIncome(Integer size, List<String> last, String order) {
        FapiClient fapiClient = loadFapiClient();

        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch income: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch income: mainFid not set");
            return null;
        }

        if (order == null || (!order.equals("asc") && !order.equals("desc"))) {
            TimberLogger.w(TAG, "Invalid order parameter: %s. Must be 'asc' or 'desc'", order);
            return null;
        }

        TimberLogger.i(TAG, "Fetching income cash for mainFid: %s with order: %s", liveFid, order);

        try {
            // Create FCDSL query
            Fcdsl fcdsl = new Fcdsl();

            // Add query with terms for owner=mainFid
            fcdsl.addNewQuery().addNewTerms().addNewFields(OWNER).addNewValues(liveFid);

            // Add except with equals for issuer=mainFid (exclude cash issued by mainFid)
            fcdsl.addNewExcept();
            fcdsl.getExcept().addNewEquals().addNewFields(ISSUER).addNewValues(liveFid);
            
            // Set page size to default 20
            fcdsl.addSize(size==null? FreerApplication.DEFAULT_REQUEST_SIZE :size);
            // Add sorting based on order
            if (ASC.equals(order)) {
                fcdsl.addSort(BIRTH_TIME, ASC);
                fcdsl.addSort(ID, ASC);
                if(last!=null && !last.isEmpty())fcdsl.addAfter(last);
            } else { // "desc"
                fcdsl.addSort(BIRTH_TIME, DESC);
                fcdsl.addSort(ID, DESC);
                if(last!=null&& !last.isEmpty())fcdsl.addAfter(last);
            }

            // Make API request
            List<Income> incomeList = new ArrayList<>();
            List<Cash> incomeCashList = fapiClient.cashSearch(fcdsl);
            
            if (incomeCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch income cash list");
                return null;
            }

            for (Cash cash : incomeCashList) {
                Income income = Income.fromCash(cash);
                incomeList.add(income);
            }

            TimberLogger.i(TAG, "Fetched %d income cash items", incomeCashList.size());

            // Capture total from API response for statistics
            try {
                if (fapiClient.getLastResponse() != null) {
                    Long receivedTotal = fapiClient.getLastResponse().getTotal();
                    if (receivedTotal != null) {
                        setTotalIncome(receivedTotal);
                        TimberLogger.d(TAG, "Captured total income from API: %d", receivedTotal);
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error capturing total income from API response: %s", e.getMessage());
            }

            return incomeList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching income cash: %s", e.getMessage());
            return null;
        }
    }

    private void updateLastHeight(String bestHeightOfIncomeKey, Long bestHeight, Long lastHeight, List<String> last, String order, FapiClient fapiClient) {
        if(last ==null || order.equals(ASC)) {
            if( lastHeight == null || (bestHeight !=null && bestHeight > lastHeight)) {
                saveStatusList(bestHeightOfIncomeKey, List.of(String.valueOf(fapiClient.getBestHeight())));
            }
        }
    }

    /**
     * Saves the current income list to local DB as a simple cache.
     * This replaces any existing cached income list.
     * 
     * @param incomeList List of income objects to cache
     * @return Number of income items saved, or -1 if failed
     */
    public int saveCurrentIncomeList(List<Income> incomeList) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot save current income list: Cash database not available");
            return -1;
        }

        if (incomeList == null || incomeList.isEmpty()) {
            TimberLogger.i(TAG, "No income items to save");
            return 0;
        }

        try {
            // Clear existing income list and save new one
            cashDB.createList(FieldNames.INCOME_LIST, Income.class);
            cashDB.clearList(FieldNames.INCOME_LIST);
            int savedCount = (int) cashDB.addAllToList(FieldNames.INCOME_LIST, incomeList);

            TimberLogger.i(TAG, "Saved %d income items to current list cache", savedCount);
            return savedCount;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving current income list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Saves the current expense list to local DB as a simple cache.
     * This replaces any existing cached expense list.
     * 
     * @param expenseList List of expense objects to cache
     * @return Number of expense items saved, or -1 if failed
     */
    public int saveCurrentExpenseList(List<Expense> expenseList) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot save current expense list: Cash database not available");
            return -1;
        }

        if (expenseList == null || expenseList.isEmpty()) {
            TimberLogger.i(TAG, "No expense items to save");
            return 0;
        }

        try {
            // Clear existing expense list and save new one
            cashDB.createList(FieldNames.EXPENSE_LIST, Expense.class);
            cashDB.clearList(FieldNames.EXPENSE_LIST);
            cashDB.addAllToList(FieldNames.EXPENSE_LIST, expenseList);
            int savedCount = expenseList.size();
            TimberLogger.i(TAG, "Saved %d expense items to current list cache", savedCount);
            return savedCount;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving current expense list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Gets the cached expense list from local DB.
     * 
     * @return A list of cached Expense objects, or empty list if none found
     */
    public List<Expense> getCachedExpenseList() {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot get cached expense list: Cash database not available");
            return new ArrayList<>();
        }
        
        try {
            // Check if expense list exists
            long listSize = cashDB.getListSize(FieldNames.EXPENSE_LIST);
            if (listSize == 0) {
                TimberLogger.i(TAG, "No cached expense list found in local database");
                return new ArrayList<>();
            }
            
            // Get entire cached list
            List<Expense> expenseList = cashDB.getRangeFromList(FieldNames.EXPENSE_LIST, 0, listSize);
            TimberLogger.i(TAG, "Retrieved %d cached expenses from local database", 
                expenseList != null ? expenseList.size() : 0);
            
            return expenseList != null ? expenseList : new ArrayList<>();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting cached expense list: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Fetches expense data from API based on operation type.
     *
     * @param context          Activity context for dialog operations
     * @param operationType    "refresh" for initial load (DESC from now), "newer" for swipe down (ASC after latest), "older" for scroll bottom (DESC after earliest)
     * @param referenceExpense the reference expense for pagination: latest expense for "newer", earliest expense for "older", null for "refresh"
     * @return List of expense items fetched, or null if failed
     */
    public List<Expense> fetchExpenseFromAPI(Context context, String operationType, Expense referenceExpense) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch expense: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch expense: mainFid not set");
            return null;
        }

        TimberLogger.i(TAG, "Fetching expense for mainFid: %s, operation: %s", liveFid, operationType);

        try {
            String order;
            List<String> last = null;
            
            switch (operationType) {
                case "refresh":
                    // Initial load: get expenses with DESC order from now to earlier
                    order = DESC;
                    last = null; // Start from now
                    break;
                case "newer":
                    // Swipe down: get newer expenses with ASC order after latest expense
                    order = ASC;
                    if (referenceExpense != null) {
                        last = Arrays.asList(String.valueOf(referenceExpense.getTime()), referenceExpense.getId());
                    }
                    break;
                case "older":
                    // Scroll bottom: get older expenses with DESC order after earliest expense
                    order = DESC;
                    if (referenceExpense != null) {
                        last = Arrays.asList(String.valueOf(referenceExpense.getTime()), referenceExpense.getId());
                    }
                    break;
                default:
                    TimberLogger.w(TAG, "Invalid operation type: %s", operationType);
                    return null;
            }

            // Fetch expense from API
            List<Expense> expenses = fetchExpense(FreerApplication.DEFAULT_REQUEST_SIZE, last, order);
            
            if (expenses == null) {
                TimberLogger.w(TAG, "Failed to fetch expense from API");
                return null;
            }

            Long lastHeight = getStateLong(LAST_HEIGHT_OF_EXPENSE);

            for(Expense expense:expenses){
                if(lastHeight == null || expense.getHeight() > lastHeight)
                    expense.setNew(true);
            }

            updateLastHeight(LAST_HEIGHT_OF_EXPENSE, fapiClient.getBestHeight(), lastHeight, last, order, fapiClient);

            TimberLogger.i(TAG, "Successfully fetched %d expense items from API", expenses.size());
            return expenses;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching expense from API: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Gets the cached income list from local DB.
     * 
     * @return A list of cached Income objects, or empty list if none found
     */
    public List<Income> getCachedIncomeList() {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot get cached income list: Cash database not available");
            return new ArrayList<>();
        }
        
        try {
            // Check if income list exists
            long listSize = cashDB.getListSize(FieldNames.INCOME_LIST);
            if (listSize == 0) {
                TimberLogger.i(TAG, "No cached income list found in local database");
                return new ArrayList<>();
            }
            
            // Get entire cached list
            List<Income> incomeList = cashDB.getRangeFromList(FieldNames.INCOME_LIST, 0, listSize);
            TimberLogger.i(TAG, "Retrieved %d cached incomes from local database", 
                incomeList != null ? incomeList.size() : 0);
            
            return incomeList != null ? incomeList : new ArrayList<>();
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting cached income list: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Fetches income data from API based on operation type.
     *
     * @param context         Activity context for dialog operations
     * @param operationType   "refresh" for initial load (DESC from now), "newer" for swipe down (ASC after latest), "older" for scroll bottom (DESC after earliest)
     * @param referenceIncome the reference income for pagination: latest income for "newer", earliest income for "older", null for "refresh"
     * @return List of income items fetched, or null if failed
     */
    public List<Income> fetchIncomeFromAPI(Context context, String operationType, Income referenceIncome) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch income: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch income: mainFid not set");
            return null;
        }

        TimberLogger.i(TAG, "Fetching income for mainFid: %s, operation: %s", liveFid, operationType);

        try {
            String order;
            List<String> last = null;
            
            switch (operationType) {
                case REFRESH:
                    // Initial load: get incomes with DESC order from now to earlier
                    order = DESC;
                    last = null; // Start from now
                    break;
                case NEWER:
                    // Swipe down: get newer incomes with ASC order after latest income
                    order = ASC;
                    if (referenceIncome != null) {
                        last = Arrays.asList(String.valueOf(referenceIncome.getTime()), referenceIncome.getId());
                    }
                    break;
                case OLDER:
                    // Scroll bottom: get older incomes with DESC order after earliest income
                    order = DESC;
                    if (referenceIncome != null) {
                        last = Arrays.asList(String.valueOf(referenceIncome.getTime()), referenceIncome.getId());
                    }
                    break;
                default:
                    TimberLogger.w(TAG, "Invalid operation type: %s", operationType);
                    return null;
            }

            // Fetch income from API
            List<Income> incomes = fetchIncome(FreerApplication.DEFAULT_REQUEST_SIZE, last, order);

            if (incomes == null) {
                TimberLogger.w(TAG, "Failed to fetch income from API");
                return null;
            }

            Long lastHeight = getStateLong(LAST_HEIGHT_OF_INCOME);

            if(!incomes.isEmpty())
                for (Income income : incomes) {
                    if(lastHeight == null || income.getHeight() > lastHeight)
                        income.setNew(true);
                }

            updateLastHeight(LAST_HEIGHT_OF_INCOME, fapiClient.getBestHeight(), lastHeight, last, order, fapiClient);

            TimberLogger.i(TAG, "Successfully fetched %d income items from API", incomes.size());
            return incomes;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching income from API: %s", e.getMessage());
            return null;
        }
    }

    public static int removeImmatureCashes(List<Cash> cashList, Long bestHeight){
        int removed = 0;

        if(cashList==null || bestHeight==null)return removed;
        for (Cash cash : cashList) {
            if (isImmature(cash, bestHeight)) {
                cashList.remove(cash);
                removed++;
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


    /**
     * Loads earlier (older) valid cash from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid cash items fetched, or -1 if failed
     */
    public int loadEarlierCashFromAPI(Context context) {

        if(com.fc.freer.network.NetworkUtils.isNetworkDownToast(context))
            return -1;

        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot load earlier cash: FapiClient not available");
            return -1;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot load earlier cash: mainFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting to load earlier valid cash for mainFid: %s", liveFid);

        try {
            // Check if we have more earlier data available
            Boolean hasMore = getStateHasMoreEarlier();
            if (hasMore != null && !hasMore) {
                TimberLogger.i(TAG, "No more earlier cash available");
                return 0;
            }

            // Get starting point for pagination
            List<String> earliest = getStateEarliestValid();

            // Fetch earlier valid cash only, in descending time order
            List<Cash> changedCashList = fetchAllCash(context, earliest, true, false);

            if (changedCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch earlier cash from API");
                return 0;
            }

            if (changedCashList.isEmpty()) {
                TimberLogger.i(TAG, "No earlier valid cash items fetched");
                return 0;
            }

            Long receivedTotal = fapiClient.getLastResponse().getTotal();
            setTotalCashOnChain(receivedTotal);
            // Update API states for earlier loading
            updateApiStatesForEarlier(changedCashList);

            // Remove immature cashes if bestHeight is available
            int removed = removeImmatureCashes(changedCashList, bestHeight);
            if(removed>0){
                ToastUtils.makeText(context, context.getString(R.string.removed_immature_cash, removed));
            }
            // Add the fetched cash to database
            int added = updateToDBForEarlier(changedCashList);
            commit();

            return added;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading earlier cash: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Refreshes cash by loading newer changes including spent cash for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context       Activity context for dialog operations
     */
    public void refreshCashFromAPI(Context context) {
        List<String> lastUpdated = getStateLastUpdated();
        totalCashOnChain = getStateTotal();

        int added = 0;
        if (lastUpdated != null && !lastUpdated.isEmpty()) {
            added = loadNewerCashFromAPI(context);

        } else {
            added = loadEarlierCashFromAPI(context);
        }

        if(added>0) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                ToastUtils.makeText(context, context.getString(R.string.added_new_cash, added));
            } else {
                int finalAdded = added;
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(() ->
                        ToastUtils.makeText(context, context.getString(R.string.added_new_cash, finalAdded))
                    );
                } else {
                    // Use Handler to post to main thread when context is not an Activity
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        ToastUtils.makeText(context, context.getString(R.string.added_new_cash, finalAdded))
                    );
                }
            }
        }
    }

    /**
     * Loads newer cash changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of cash changes processed, or -1 if failed
     */
    public int loadNewerCashFromAPI(Context context) {
        if(com.fc.freer.network.NetworkUtils.isNetworkDownToast(context))
            return -1;

        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot load newer cash: FapiClient not available");
            return -1;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot load newer cash: mainFid not set");
            return -1;
        }

        TimberLogger.i(TAG, "Starting to load newer cash for mainFid: %s", liveFid);

        try {
            // Get the saved lastUpdated state
            List<String> lastUpdated = getStateLastUpdated();

            // Fetch newer changes including spent cash for cleanup
            List<Cash> changedCashList = fetchAllCash(context, lastUpdated, false, true);

            if (changedCashList == null) {
                TimberLogger.w(TAG, "Failed to fetch newer cash from API");
                return 0;
            }

            if (changedCashList.isEmpty()) {
                TimberLogger.i(TAG, "No newer cash changes fetched");
                return 0;
            }
            updateTotal(context);

            // Update API states for newer loading
            updateApiStatesForNewer(changedCashList);

            // Remove immature cashes if bestHeight is available
            int removed = removeImmatureCashes(changedCashList, bestHeight);
            if(removed>0){
                ToastUtils.makeText(context, context.getString(R.string.removed_immature_cash, removed));
            }
            // Add the fetched cash to database
            int added = updateToDBForNewer(changedCashList);
            commit();

            if (added > 0) {
                notifyNewCashChanged();
            }

            return added;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error loading newer cash: %s", e.getMessage());
            return -1;
        }
    }

    private void updateTotal(Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(CASH);
        fcdsl.addNewQuery().addNewTerms().addNewFields(OWNER).addNewValues(FidManager.getInstance().getLiveFid());
        fcdsl.getQuery().addNewEquals().addNewFields(VALID).addNewValues(TRUE);
        fcdsl.setSize("0");
        FapiClient fapiClient = loadFapiClient();
        if(fapiClient==null)return;
        fapiClient.search(fcdsl);
        Long receivedTotal = fapiClient.getLastResponse().getTotal();
        if(receivedTotal!=null){
            saveStateTotal(receivedTotal);
        }
    }


    private List<String> getStateLastUpdated() {
        Object obj = cashDB.getState(DatabaseManager.LAST_UPDATED);
        if(obj==null)return null;
        String lastStr = obj.toString();
        return  Arrays.asList(lastStr.split(","));

    }

    private void saveStateLastUpdated(List<String> statusList) {
        saveStatusList(DatabaseManager.LAST_UPDATED,statusList);
        if(statusList!=null)TimberLogger.i(TAG, "Last updated cash state saved: %s", statusList.get(0));

    }

    private List<String> getStateEarliestValid() {
        Object obj = cashDB.getState(DatabaseManager.EARLIEST_VALID);
        if(obj==null)return null;
        String lastStr = obj.toString();
        return  Arrays.asList(lastStr.split(","));

    }

    private Long getStateLong(String key) {
        Object obj = cashDB.getState(key);
        if(obj==null)return null;
        try {
            String value = obj.toString();
            return Long.valueOf(value);
        }catch (Exception e){
            return null;
        }
    }

    private void saveStateEarliestValid(List<String> statusList) {
        saveStatusList(DatabaseManager.EARLIEST_VALID,statusList);
        if(statusList!=null)TimberLogger.i(TAG, "Earliest valid cash state saved: %s", statusList.get(0));

    }

    private void saveStateHasMoreEarlier(Boolean hasMore) {
        cashDB.putState(DatabaseManager.HAS_MORE_EARLIER,String.valueOf(hasMore));
    }

    private Boolean getStateHasMoreEarlier() {
        Object obj = cashDB.getState(DatabaseManager.HAS_MORE_EARLIER);
        if(obj==null)return null;
        return Boolean.valueOf((String)obj);
    }

    private void saveStateTotal(Long total) {
        this.totalCashOnChain = total;
        cashDB.putState(DatabaseManager.TOTAL, String.valueOf(total));
    }

    private Long getStateTotal() {
        Object obj = cashDB.getState(DatabaseManager.TOTAL);
        if(obj==null)return null;
        return Long.valueOf((String)obj);
    }

    /**
     * Fetches transaction info (FidTxMask) from the API.
     *
     * @param context Activity context for dialog operations
     * @param size    Page size, or null for default
     * @param last    Last values for pagination
     * @param order   Sort order: "asc" for ascending, "desc" for descending
     * @return List of FidTxMask objects, or null if failed
     */
    public List<FidTxMask> fetchTx(Context context, Integer size, List<String> last, String order) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch tx: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch tx: mainFid not set");
            return null;
        }

        if (order == null || (!order.equals("asc") && !order.equals("desc"))) {
            TimberLogger.w(TAG, "Invalid order parameter: %s. Must be 'asc' or 'desc'", order);
            return null;
        }

        TimberLogger.i(TAG, "Fetching tx for mainFid: %s with order: %s", liveFid, order);

        try {
            // Make API request to txHasSearch
            List<Tx> txList = fapiClient.txByFid(liveFid, order,size, last);
            if (txList == null) {
                if(fapiClient.getLastResponse().getCode()==1011)
                    return new ArrayList<>();
                TimberLogger.w(TAG, "Failed to fetch tx list");
                return null;
            }

            List<FidTxMask> fidTxMastList = FidTxMask.fromTxInfo(liveFid,txList);

            TimberLogger.i(TAG, "Fetched %d tx items", fidTxMastList.size());

            // Capture total from API response for statistics
            try {
                if (fapiClient.getLastResponse() != null) {
                    Long receivedTotal = fapiClient.getLastResponse().getTotal();
                    if (receivedTotal != null) {
                        setTotalTx(receivedTotal);
                        TimberLogger.d(TAG, "Captured total tx from API: %d", receivedTotal);
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error capturing total tx from API response: %s", e.getMessage());
            }

            Long lastHeight = getStateLong(LAST_HEIGHT_OF_TX);

            for(FidTxMask tx:fidTxMastList){
                if(lastHeight == null || tx.getHeight() > lastHeight)
                    tx.setNew(true);
            }

            updateLastHeight(LAST_HEIGHT_OF_TX, fapiClient.getBestHeight(), lastHeight, last, order, fapiClient);

            return fidTxMastList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching tx: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Saves the current tx list to local DB as a simple cache.
     * This replaces any existing cached tx list.
     *
     * @param txList List of FidTxMask objects to cache
     * @return Number of tx items saved, or -1 if failed
     */
    public int saveCurrentTxList(List<FidTxMask> txList) {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot save current tx list: Cash database not available");
            return -1;
        }

        if (txList == null || txList.isEmpty()) {
            TimberLogger.i(TAG, "No tx items to save");
            return 0;
        }

        try {
            // Clear existing tx list and save new one
            cashDB.createList(FieldNames.TX_LIST, FidTxMask.class);
            cashDB.clearList(FieldNames.TX_LIST);
            cashDB.addAllToList(FieldNames.TX_LIST, txList);
            int savedCount = txList.size();
            TimberLogger.i(TAG, "Saved %d tx items to current list cache", savedCount);
            return savedCount;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving current tx list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Gets the cached tx list from local DB.
     *
     * @return A list of cached FidTxMask objects, or empty list if none found
     */
    public List<FidTxMask> getCachedTxList() {
        if (cashDB == null) {
            TimberLogger.w(TAG, "Cannot get cached tx list: Cash database not available");
            return new ArrayList<>();
        }

        try {
            // Check if tx list exists
            long listSize = cashDB.getListSize(FieldNames.TX_LIST);
            if (listSize == 0) {
                TimberLogger.i(TAG, "No cached tx list found in local database");
                return new ArrayList<>();
            }

            // Get entire cached list
            List<FidTxMask> txList = cashDB.getRangeFromList(FieldNames.TX_LIST, 0, listSize);
            TimberLogger.i(TAG, "Retrieved %d cached tx from local database",
                txList != null ? txList.size() : 0);

            return txList != null ? txList : new ArrayList<>();

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting cached tx list: %s", e.getMessage());
            return new ArrayList<>();
        }
    }


    /**
     * Fetches tx data from API based on operation type.
     *
     * @param context       Activity context for dialog operations
     * @param operationType "refresh" for initial load (DESC from now), "newer" for swipe down (ASC after latest), "older" for scroll bottom (DESC after earliest)
     * @param referenceTx   the reference tx for pagination: latest tx for "newer", earliest tx for "older", null for "refresh"
     * @return List of tx items fetched, or null if failed
     */
    public List<FidTxMask> fetchTxFromAPI(Context context, String operationType, FidTxMask referenceTx) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch tx: FapiClient not available");
            return null;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot fetch tx: mainFid not set");
            return null;
        }

        TimberLogger.i(TAG, "Fetching tx for mainFid: %s, operation: %s", liveFid, operationType);

        try {
            String order;
            List<String> last = null;

            switch (operationType) {
                case "refresh":
                    // Initial load: get tx with DESC order from now to earlier
                    order = DESC;
                    break;
                case "newer":
                    // Swipe down: get newer tx with ASC order after latest tx
                    order = ASC;
                    if (referenceTx != null) {
                        last = Arrays.asList(
                            String.valueOf(referenceTx.getHeight()),
                                String.valueOf(referenceTx.getIndex()),
                            referenceTx.getId()
                        );
                    }
                    break;
                case "older":
                    // Scroll bottom: get older tx with DESC order after earliest tx
                    order = DESC;
                    if (referenceTx != null) {
                        last = Arrays.asList(
                            String.valueOf(referenceTx.getHeight()),
                                String.valueOf(referenceTx.getIndex()),
                            referenceTx.getId()
                        );
                    }
                    break;
                default:
                    TimberLogger.w(TAG, "Invalid operation type: %s", operationType);
                    return null;
            }

            // Fetch tx from API
            List<FidTxMask> txs = fetchTx(context, FreerApplication.DEFAULT_REQUEST_SIZE, last, order);

            if (txs == null) {
                TimberLogger.w(TAG, "Failed to fetch tx from API");
                return null;
            }

            return txs;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching tx from API: %s", e.getMessage());
            return null;
        }
    }
    private void saveBestBlockInfo(){
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient != null) {
            FapiResponse responseBody = fapiClient.getLastResponse();
            if (responseBody != null) {
                Long bestHeight = responseBody.getBestHeight();
                cashDB.putState(DatabaseManager.BEST_HEIGHT, String.valueOf(bestHeight));
                String bestBlockId = responseBody.getBestBlockId();
                cashDB.putState(DatabaseManager.BEST_BLOCK_ID, bestBlockId);
            }
        }
    }

    public Long getStateBestHeight() {
        return cashDB.getStateLong(DatabaseManager.BEST_HEIGHT);
    }

    public String getStateBestBlockId() {
        return (String) cashDB.getState(DatabaseManager.BEST_BLOCK_ID);
    }

    //TODO test unfinished
    public Boolean isRollback(Context context) {

        if(!NetworkUtils.isNetworkAvailable(context))return null;

        Long height = getStateBestHeight();
        String localBestBlockId = getStateBestBlockId();
        if(height==null ||localBestBlockId==null)return false;


        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot check rollback: FapiClient not available");
            return false;
        }
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

        if (cashDB == null) {
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
            // 1. Remove all items with lastHeight > rollbackHeight from database
            int removedCount = removeItemsAfterHeight(rollbackHeight);
            TimberLogger.i(TAG, "Removed %d items with height > %d", removedCount, rollbackHeight);

            if (removedCount<=0)
                return removedCount;

            // 2. Fetch all updated secrets since rollbackHeight from API
            List<Cash> updatedSecrets = fetchCashFromHeight(context, rollbackHeight);
            if (updatedSecrets == null) {
                TimberLogger.w(TAG, "Failed to fetch updated secrets for rollback");
                return -1;
            }

            // 3. Convert secrets to Secret objects

            // 4. Update database with active/inactive secrets
            int processedCount = updateDatabaseAfterRollback(updatedSecrets);

            // 5. Update total count
            updateTotal(context);

            // 6. Update pagination states
            updateStatesAfterRollback(updatedSecrets);

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


    /**
     * Removes all Secret items with lastHeight > rollbackHeight from database
     */
    private int removeItemsAfterHeight(Long rollbackHeight) {
        if (cashDB == null) return 0;

        List<Cash> toRemove = new ArrayList<>();

        List<Cash> pageCashList;
        boolean end = false;
        String fromId = null;

        while(!end) {
            pageCashList = cashDB.getList(FreerApplication.MAX_CONTAINER_SIZE,fromId,null,false,null,null,true,true);

            for (Cash item : pageCashList) {
                if (item.getLastHeight() != null && item.getLastHeight() > rollbackHeight) {
                    toRemove.add(item);
                }else {
                    if (!toRemove.isEmpty()) {
                        removeCashList(toRemove);
                        TimberLogger.i(TAG, "Removed %d secrets with height > %d", toRemove.size(), rollbackHeight);
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

    /**
     * Fetches all secrets that have been updated since the rollback height from API
     */
    private List<Cash> fetchCashFromHeight(Context context, Long rollbackHeight) {
        List<Cash> allUpdatedCash = new ArrayList<>();
        final int pageSize = FreerApplication.DEFAULT_REQUEST_SIZE;
        int pageCount = 0;
        List<String> after = null;

        TimberLogger.i(TAG, "Fetching secrets updated since height: %d", rollbackHeight);

        while (true) {
            pageCount++;
            TimberLogger.d(TAG, "Fetching rollback page %d with size %d", pageCount, pageSize);

            // Create FCDSL query for secrets updated since rollbackHeight
            Fcdsl fcdsl = new Fcdsl();
            fcdsl.setEntity(IndicesNames.CASH);
            fcdsl.addNewQuery().addNewTerms().addNewFields(OWNER).addNewValues(liveFid);
            fcdsl.getQuery().addNewRange().addNewFields(LAST_HEIGHT).addGt(String.valueOf(rollbackHeight));
            fcdsl.addSort(LAST_HEIGHT, ASC).addSort(ID, ASC);
            fcdsl.addSize(pageSize);

            if (after != null && !after.isEmpty()) {
                fcdsl.addAfter(after);
            }

            // Make API request
            FapiClient fapiClient = loadFapiClient();
            FapiResponse result = fapiClient.search(fcdsl);
            if (result == null || result.getData() == null) {
                if(!fapiClient.isDataNoFound())
                    TimberLogger.w(TAG, "Failed to fetch secrets for rollback on page %d", pageCount);
                return  allUpdatedCash;
            }

            List<Cash> pageCashList = ObjectUtils.objectToList(result.getData(), Cash.class);

            if (pageCashList == null || pageCashList.isEmpty()) {
                TimberLogger.i(TAG, "No more updated secrets found for rollback at page %d", pageCount);
                break;
            }

            allUpdatedCash.addAll(pageCashList);
            TimberLogger.d(TAG, "Rollback page %d returned %d secrets, total so far: %d",
                    pageCount, pageCashList.size(), allUpdatedCash.size());

            // Check if we need to continue pagination
            if (pageCashList.size() < pageSize) {
                TimberLogger.i(TAG, "Received less than page size for rollback, ending pagination");
                break;
            }

            // Get 'last' values for next page
            if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getLast() != null) {
                after = fapiClient.getLastResponse().getLast();
            }else break;
        }

        TimberLogger.i(TAG, "Completed fetching updated secrets for rollback: %d items in %d pages",
                allUpdatedCash.size(), pageCount);

        return allUpdatedCash;
    }

    /**
     * Updates database after rollback with fetched secrets
     */
    private int updateDatabaseAfterRollback(List<Cash> cashList) {
        if (cashList == null || cashList.isEmpty()) return 0;

        int added = 0;
        int removed = 0;

        for (Cash item : cashList) {
            try {
                if (Boolean.TRUE.equals(item.isValid())) {
                    // Secret is inactive/deleted, remove from database if exists
                    if (checkIfExisted(item.getId())) {
                        removeCash(item);
                        removed++;
                    }
                } else {
                    // Secret is active, add or update in database
                    addValidCash(item);
                    added++;
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error processing secret during rollback: %s", e.getMessage());
                continue;
            }
        }

        TimberLogger.i(TAG, "Rollback database update: %d added, %d removed", added, removed);
        return added;
    }

    /**
     * Updates pagination states after rollback
     */
    private void updateStatesAfterRollback(List<Cash> updatedSecrets) {
        if (updatedSecrets == null || updatedSecrets.isEmpty()) {
            return;
        }

        try {
            // Update lastUpdated to the latest secret from the rollback
            Cash latest = updatedSecrets.get(updatedSecrets.size() - 1);
            List<String> latestInfo = makeSortList(latest);
            saveStateLastUpdated(latestInfo);
            TimberLogger.i(TAG, "Updated lastUpdated state after rollback: %s", latestInfo);

            // Reset pagination flags since we've done a fresh fetch
            saveStateHasMoreNewer(false);

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating states after rollback: %s", e.getMessage());
        }
    }

    /**
     * Updates conflicted status of cashes by checking unconfirmed transactions in mempool.
     * This marks cashes that are spent in unconfirmed transactions as conflicted.
     *
     * IMPORTANT: This method performs network I/O and must be called from a background thread!
     *
     * @param context Activity context for API operations
     * @return Number of cashes marked as conflicted, or -1 if failed
     */
    public int updateConflictedCashes(Context context) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot update conflicted cashes: FapiClient not available");
            return -1;
        }

        if (liveFid == null || liveFid.isEmpty()) {
            TimberLogger.w(TAG, "Cannot update conflicted cashes: liveFid not set");
            return -1;
        }

        try {
            // Call unconfirmedCashes API to get all unconfirmed transactions
            // WARNING: This is a blocking network call!
            Map<String, List<Cash>> unconfirmedMap = fapiClient.unconfirmedCashes(
                    Collections.singletonList(liveFid)
            );

            if (unconfirmedMap == null || unconfirmedMap.isEmpty()) {
                TimberLogger.i(TAG, "No unconfirmed cashes found");
                // Clear all conflicted flags since there are no unconfirmed txs
                clearAllConflictedFlags();
                return 0;
            }

            // Get the list of cashes for this FID
            List<Cash> unconfirmedCashes = unconfirmedMap.get(liveFid);
            if (unconfirmedCashes == null || unconfirmedCashes.isEmpty()) {
                TimberLogger.i(TAG, "No unconfirmed cashes found for FID: %s", liveFid);
                clearAllConflictedFlags();
                return 0;
            }

            int conflictedCount = 0;
            List<Cash> cashesToUpdate = new ArrayList<>();

            // Find cashes that are marked as invalid (spent in mempool)
            for (Cash unconfirmedCash : unconfirmedCashes) {
                if (Boolean.FALSE.equals(unconfirmedCash.isValid())) {
                    // This cash is spent in mempool - check if it exists in our DB
                    String cashId = unconfirmedCash.getId();
                    if (cashId != null) {
                        Cash existingCash = cashDB.get(cashId);
                        if (existingCash != null && Boolean.TRUE.equals(existingCash.isValid())) {
                            // Mark as conflicted
                            existingCash.setConflicted(true);
                            cashesToUpdate.add(existingCash);
                            conflictedCount++;
                            TimberLogger.d(TAG, "Marked cash as conflicted: %s", cashId);
                        }
                    }
                }
            }

            // Update all conflicted cashes in database
            if (!cashesToUpdate.isEmpty()) {
                updateCash(cashesToUpdate);
                commit();
                TimberLogger.i(TAG, "Updated %d conflicted cashes", conflictedCount);
            }

            // Clear conflicted flag for cashes that are no longer in mempool
            clearNonConflictedCashes(unconfirmedCashes);

            return conflictedCount;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating conflicted cashes: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Updates conflicted status of cashes asynchronously on a background thread.
     * Safe to call from the main thread.
     *
     * @param context Activity context for API operations
     * @param callback Optional callback to receive the result (called on background thread)
     */
    public void updateConflictedCashesAsync(Context context, ConflictUpdateCallback callback) {
        new Thread(() -> {
            try {
                int count = updateConflictedCashes(context);
                if (callback != null) {
                    callback.onComplete(count);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error in async conflicted update: %s", e.getMessage());
                if (callback != null) {
                    callback.onError(e);
                }
            }
        }).start();
    }

    /**
     * Callback interface for async conflicted cash updates
     */
    public interface ConflictUpdateCallback {
        void onComplete(int conflictedCount);
        void onError(Exception e);
    }

    /**
     * Clears conflicted flag for cashes that are no longer spent in mempool
     */
    private void clearNonConflictedCashes(List<Cash> unconfirmedCashes) {
        try {
            // Get all currently conflicted cashes from DB
            List<Cash> allCashes = getAllCashList();
            List<Cash> cashesToClear = new ArrayList<>();

            // Build set of IDs that are still conflicted in mempool
            java.util.Set<String> conflictedIds = new java.util.HashSet<>();
            for (Cash unconfirmedCash : unconfirmedCashes) {
                if (Boolean.FALSE.equals(unconfirmedCash.isValid()) && unconfirmedCash.getId() != null) {
                    conflictedIds.add(unconfirmedCash.getId());
                }
            }

            // Find cashes that are marked conflicted but no longer in mempool
            for (Cash cash : allCashes) {
                if (Boolean.TRUE.equals(cash.getConflicted()) && !conflictedIds.contains(cash.getId())) {
                    cash.setConflicted(false);
                    cashesToClear.add(cash);
                    TimberLogger.d(TAG, "Cleared conflicted flag for cash: %s", cash.getId());
                }
            }

            if (!cashesToClear.isEmpty()) {
                updateCash(cashesToClear);
                commit();
                TimberLogger.i(TAG, "Cleared conflicted flag for %d cashes", cashesToClear.size());
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error clearing non-conflicted cashes: %s", e.getMessage());
        }
    }

    /**
     * Clears all conflicted flags (used when there are no unconfirmed transactions)
     */
    private void clearAllConflictedFlags() {
        try {
            List<Cash> allCashes = getAllCashList();
            List<Cash> cashesToClear = new ArrayList<>();

            for (Cash cash : allCashes) {
                if (Boolean.TRUE.equals(cash.getConflicted())) {
                    cash.setConflicted(false);
                    cashesToClear.add(cash);
                }
            }

            if (!cashesToClear.isEmpty()) {
                updateCash(cashesToClear);
                commit();
                TimberLogger.i(TAG, "Cleared all conflicted flags for %d cashes", cashesToClear.size());
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error clearing all conflicted flags: %s", e.getMessage());
        }
    }
}