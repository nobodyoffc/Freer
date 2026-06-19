package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.DEPLOYER;
import static com.fc.fc_ajdk.constants.FieldNames.FID;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.RECIPIENT;
import static com.fc.fc_ajdk.constants.FieldNames.SIGNER;
import static com.fc.fc_ajdk.constants.FieldNames.TIME;
import static com.fc.fc_ajdk.constants.FieldNames.TOKEN_ID;
import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN;
import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN_HISTORY;
import static com.fc.fc_ajdk.constants.IndicesNames.TOKEN_HOLDER;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHistory;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.utils.ApiCenter;
import com.fc.fc_ajdk.data.feipData.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A singleton class to manage TokenHolder, Token, and TokenHistory data.
 * TokenHolder is the primary entity stored in the database.
 * Token and TokenHistory are cached in memory like News in FcObjectManager.
 */
public class TokenManager extends FcManager<TokenHolder> {
    private static final String TAG = "TokenManager";
    public static final int MAX_PAGES = 200;

    private static final String TOKEN_LIST = "token_list";
    private static final String TOKEN_HISTORY_LIST = "token_history_list";
    private static final String LAST_HEIGHT_OF_TOKEN_HISTORY = "last_height_of_token_history";

    private static TokenManager instance;
    private Long totalTokens;
    private Long totalTokenHistory;

    private TokenManager() {
        super(TokenHolder.class, TOKEN_HOLDER);
    }

    /**
     * Gets the singleton instance of TokenManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The TokenManager instance
     */
    public static synchronized TokenManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new TokenManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized TokenManager getInstance() {
        return instance;
    }

    // ========================================
    // TokenHolder Methods (Primary Entity)
    // ========================================

    /**
     * Gets a TokenHolder by its ID.
     */
    public TokenHolder getTokenHolderById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a TokenHolder to the database.
     */
    public void addTokenHolder(TokenHolder tokenHolder) {
        addEntity(tokenHolder);
    }

    /**
     * Removes a TokenHolder from the database.
     */
    public void removeTokenHolder(TokenHolder tokenHolder) {
        removeEntity(tokenHolder);
    }

    /**
     * Removes multiple TokenHolders from the database.
     */
    public void removeTokenHolders(List<TokenHolder> tokenHolders) {
        removeEntities(tokenHolders);
    }

    /**
     * Gets a paginated list of TokenHolder entities.
     */
    public List<TokenHolder> getPaginatedTokenHolders(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    public long getTokenHolderDBSize() {
        return getEntityDBSize();
    }

    /**
     * Fetches TokenHolders from API for the current liveFid.
     */
    public List<TokenHolder> fetchTokenHoldersFromAPI(Context context) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch token holders: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = new Fcdsl();
            fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);
            fcdsl.addNewQuery().addNewTerms().addNewFields(FID).addNewValues(liveFid);
            fcdsl.addSort(LAST_HEIGHT, DESC).addSort(ID, DESC);

            List<TokenHolder> tokenHolders = fapiClient.entitySearch(TOKEN_HOLDER, fcdsl, TokenHolder.class);
            if (tokenHolders != null) {
                TimberLogger.i(TAG, "Fetched %d token holders from API", tokenHolders.size());
            }
            return tokenHolders;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching token holders: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Refreshes token holders from API and saves to database.
     */
    public int refreshTokenHoldersFromAPI(Context context) {
        List<TokenHolder> tokenHolders = fetchTokenHoldersFromAPI(context);
        if (tokenHolders == null) {
            return -1;
        }

        // Clear existing and add new
        entityDB.clear();
        for (TokenHolder holder : tokenHolders) {
            addTokenHolder(holder);
        }
        commit();
        return tokenHolders.size();
    }

    // ========================================
    // Token Methods (Cached like News)
    // ========================================

    /**
     * Fetches tokens from API based on operation type.
     *
     * @param operationType "refresh", "newer", or "older"
     * @param referenceToken The reference token for pagination
     * @return List of tokens fetched
     */
    public List<Token> fetchTokensFromAPI(String operationType, Token referenceToken) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch tokens: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = buildTokenFcdsl(operationType, referenceToken, null);
            if (fcdsl == null) return null;

            List<Token> tokens = fapiClient.entitySearch(TOKEN, fcdsl, Token.class);
            
            if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getTotal() != null) {
                totalTokens = fapiClient.getLastResponse().getTotal();
            }

            if (tokens != null) {
                TimberLogger.i(TAG, "Fetched %d tokens from API", tokens.size());
            }
            return tokens;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching tokens: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches tokens where deployer = liveFid.
     */
    public List<Token> fetchMyTokensFromAPI(Token referenceToken) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch my tokens: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = buildTokenFcdsl("refresh", referenceToken, liveFid);
            if (fcdsl == null) return null;

            List<Token> tokens = fapiClient.entitySearch(TOKEN, fcdsl, Token.class);
            
            if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getTotal() != null) {
                totalTokens = fapiClient.getLastResponse().getTotal();
            }

            if (tokens != null) {
                TimberLogger.i(TAG, "Fetched %d of my tokens from API", tokens.size());
            }
            return tokens;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching my tokens: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildTokenFcdsl(String operationType, Token referenceToken, String deployerFilter) {
        String order;
        List<String> last = null;

        switch (operationType) {
            case "refresh":
                order = DESC;
                break;
            case "newer":
                order = ASC;
                if (referenceToken != null) {
                    last = Arrays.asList(String.valueOf(referenceToken.getLastHeight()), referenceToken.getId());
                }
                break;
            case "older":
                order = DESC;
                if (referenceToken != null) {
                    last = Arrays.asList(String.valueOf(referenceToken.getLastHeight()), referenceToken.getId());
                }
                break;
            default:
                TimberLogger.w(TAG, "Invalid operation type: %s", operationType);
                return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);
        
        if (deployerFilter != null) {
            fcdsl.addNewQuery().addNewTerms().addNewFields(DEPLOYER).addNewValues(deployerFilter);
        }
        
        fcdsl.addSort(LAST_HEIGHT, order).addSort(ID, order);
        
        if (last != null) {
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    /**
     * Fetches a single token by ID.
     */
    public Token fetchTokenById(String tokenId) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            return null;
        }

        try {
            Map<String, Token> tokens = fapiClient.entityByIds(TOKEN, Token.class, List.of(tokenId));
            if (tokens != null && !tokens.isEmpty()) {
                return tokens.get(tokenId);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching token by ID: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Saves the current tokens list to local DB as a simple cache.
     */
    public int saveCurrentTokensList(List<Token> tokenList) {
        if (entityDB == null) {
            TimberLogger.w(TAG, "Cannot save tokens list: database not available");
            return -1;
        }

        if (tokenList == null || tokenList.isEmpty()) {
            return 0;
        }

        try {
            entityDB.createList(TOKEN_LIST, Token.class);
            entityDB.clearList(TOKEN_LIST);
            entityDB.addAllToList(TOKEN_LIST, tokenList);
            TimberLogger.i(TAG, "Saved %d tokens to cache", tokenList.size());
            return tokenList.size();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving tokens list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Gets the cached tokens list from local DB.
     */
    public List<Token> getCachedTokensList() {
        if (entityDB == null) {
            return new ArrayList<>();
        }

        try {
            long listSize = entityDB.getListSize(TOKEN_LIST);
            if (listSize == 0) {
                return new ArrayList<>();
            }

            List<Token> tokens = entityDB.getRangeFromList(TOKEN_LIST, 0, listSize);
            return tokens != null ? tokens : new ArrayList<>();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting cached tokens: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ========================================
    // TokenHistory Methods (Cached like News)
    // ========================================

    /**
     * Fetches token history from API.
     *
     * @param tokenId If not null, filter by tokenId
     * @param fid If not null, filter by signer OR recipient = fid
     * @param operationType "refresh", "newer", or "older"
     * @param referenceHistory Reference for pagination
     * @return List of token history items
     */
    public List<TokenHistory> fetchTokenHistoryFromAPI(String tokenId, String fid, 
                                                        String operationType, TokenHistory referenceHistory) {
        FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot fetch token history: FapiClient not available");
            return null;
        }

        try {
            Fcdsl fcdsl = buildTokenHistoryFcdsl(tokenId, fid, operationType, referenceHistory);
            if (fcdsl == null) return null;

            List<TokenHistory> historyList = fapiClient.entitySearch(TOKEN_HISTORY, fcdsl, TokenHistory.class);
            
            if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getTotal() != null) {
                totalTokenHistory = fapiClient.getLastResponse().getTotal();
            }

            if (historyList != null) {
                TimberLogger.i(TAG, "Fetched %d token history items from API", historyList.size());
            }
            return historyList;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error fetching token history: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildTokenHistoryFcdsl(String tokenId, String fid, 
                                          String operationType, TokenHistory referenceHistory) {
        String order;
        List<String> last = null;

        switch (operationType) {
            case "refresh":
                order = DESC;
                break;
            case "newer":
                order = ASC;
                if (referenceHistory != null) {
                    last = Arrays.asList(String.valueOf(referenceHistory.getTime()), referenceHistory.getId());
                }
                break;
            case "older":
                order = DESC;
                if (referenceHistory != null) {
                    last = Arrays.asList(String.valueOf(referenceHistory.getTime()), referenceHistory.getId());
                }
                break;
            default:
                TimberLogger.w(TAG, "Invalid operation type: %s", operationType);
                return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSize(FreerApplication.DEFAULT_REQUEST_SIZE);

        // Add filters
        if (tokenId != null) {
            fcdsl.addNewQuery().addNewTerms().addNewFields(TOKEN_ID).addNewValues(tokenId);
        } else if (fid != null) {
            // Filter by signer OR recipient = fid
            fcdsl.addNewQuery().addNewTerms().addNewFields(SIGNER, RECIPIENT).addNewValues(fid);
        }

        fcdsl.addSort(TIME, order).addSort(ID, order);
        
        if (last != null) {
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    /**
     * Saves the current token history list to local DB.
     */
    public int saveCurrentTokenHistoryList(List<TokenHistory> historyList) {
        if (entityDB == null) {
            return -1;
        }

        if (historyList == null || historyList.isEmpty()) {
            return 0;
        }

        try {
            entityDB.createList(TOKEN_HISTORY_LIST, TokenHistory.class);
            entityDB.clearList(TOKEN_HISTORY_LIST);
            entityDB.addAllToList(TOKEN_HISTORY_LIST, historyList);
            TimberLogger.i(TAG, "Saved %d token history items to cache", historyList.size());
            return historyList.size();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error saving token history list: %s", e.getMessage());
            return -1;
        }
    }

    /**
     * Gets the cached token history list from local DB.
     */
    public List<TokenHistory> getCachedTokenHistoryList() {
        if (entityDB == null) {
            return new ArrayList<>();
        }

        try {
            long listSize = entityDB.getListSize(TOKEN_HISTORY_LIST);
            if (listSize == 0) {
                return new ArrayList<>();
            }

            List<TokenHistory> history = entityDB.getRangeFromList(TOKEN_HISTORY_LIST, 0, listSize);
            return history != null ? history : new ArrayList<>();
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting cached token history: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ========================================
    // Getters
    // ========================================

    public Long getTotalTokens() {
        return totalTokens;
    }

    public Long getTotalTokenHistory() {
        return totalTokenHistory;
    }

    public List<TokenHolder> getLocalHiddenTokenHolders() {
        return getLocalDeletedList();
    }

    public long getLocalHiddenTokenHoldersSize() {
        Long size = getLocalDeletedListSize();
        return size != null ? size : 0;
    }

    // ========================================
    // Abstract Method Implementations
    // ========================================

    @Override
    protected void preprocessDB() {
        // No preprocessing needed
    }

    @Override
    protected void markEntityAsNew(TokenHolder entity) {
        // No marking needed
    }

    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addSize(pageSize);
        fcdsl.addNewQuery().addNewTerms().addNewFields(FID).addNewValues(liveFid);

        if (timeAsc) {
            fcdsl.addSort(LAST_HEIGHT, ASC).addSort(ID, ASC);
        } else {
            fcdsl.addSort(LAST_HEIGHT, DESC).addSort(ID, DESC);
        }

        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }
        return fcdsl;
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<TokenHolder> entityList, int index, int savedCount) {
        // Not needed for TokenHolder
    }

    @Override
    protected String getEntityIndexName() {
        return TOKEN_HOLDER;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return TokenHolder.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        TokenHolder holder = (TokenHolder) apiObject;
        return Arrays.asList(String.valueOf(holder.getLastHeight()), holder.getId());
    }

    @Override
    protected void preprocessEntity(TokenHolder entity) {
        // No preprocessing needed
    }

    @Override
    protected boolean isEntityDeleted(TokenHolder entity) {
        return false;
    }

    @Override
    protected Long getEntityUpdateHeight(TokenHolder entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(TokenHolder entity) {
        return true;
    }

    @Override
    protected List<TokenHolder> searchFromList(String query, List<TokenHolder> results) {
        List<TokenHolder> matching = new ArrayList<>();
        for (TokenHolder holder : results) {
            if (holder.getTokenId() != null && holder.getTokenId().toLowerCase().contains(query.toLowerCase())) {
                matching.add(holder);
            }
        }
        return matching;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context, List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchTokenHoldersFromAPI(context);
    }

    @Override
    protected TokenHolder createFailedDecryptionEntity(Object apiObject, Context context) {
        return (TokenHolder) apiObject;
    }

    @Override
    protected TokenHolder createUndecryptedEntity(Context context, Object apiObject) {
        return apiObject instanceof TokenHolder ? (TokenHolder) apiObject : null;
    }

    @Override
    public List<TokenHolder> makeEntityDetails(List<?> apiObjects, boolean decryptDeleted, Context context) {
        if (apiObjects == null) return new ArrayList<>();
        List<TokenHolder> result = new ArrayList<>();
        for (Object obj : apiObjects) {
            if (obj instanceof TokenHolder) {
                result.add((TokenHolder) obj);
            }
        }
        return result;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        return null;
    }
}
