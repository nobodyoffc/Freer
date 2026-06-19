package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.COSIGNERS_INVITED;
import static com.fc.fc_ajdk.constants.FieldNames.ISSUER;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.IndicesNames.PROOF;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.FALSE;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.content.Context;
import android.app.Activity;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.freer.R;
import com.fc.freer.ui.UserConfirmDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * A singleton class to manage and share the Proof database across activities.
 * This provides a centralized way to access the Proof database from any activity.
 */
public class ProofManager extends FcManager<Proof> {
    private static final String TAG = "ProofManager";
    public static final int MAX_PAGES = 200;

    private static ProofManager instance;

    private ProofManager() {
        super(Proof.class, PROOF);
    }

    /**
     * Gets the singleton instance of ProofManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The ProofManager instance
     */
    public static synchronized ProofManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new ProofManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized ProofManager getInstance() {
        return instance;
    }

    /**
     * Gets a Proof object by its ID.
     *
     * @param id The ID of the Proof object to get
     * @return The Proof object, or null if not found
     */
    public Proof getProofById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Proof object to the database.
     *
     * @param proof The Proof object to add
     */
    public void addProof(Proof proof) {
        addEntity(proof);
    }

    /**
     * Removes a Proof object from the database.
     *
     * @param proof The Proof object to remove
     */
    public void removeProof(Proof proof) {
        removeEntity(proof);
    }

    /**
     * Removes multiple Proof objects from the database.
     *
     * @param proofs The list of Proof objects to remove
     */
    public void removeProofs(List<Proof> proofs) {
        removeEntities(proofs);
    }

    /**
     * Updates an existing Proof in the database.
     * @param proof The Proof object to update
     */
    public void updateProof(Proof proof) {
        updateEntity(proof);
    }

    /**
     * Gets a paginated list of Proof objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of Proof objects for the requested page
     */
    public List<Proof> getPaginatedProofs(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    @Override
    protected void preprocessDB() {
        // No special preprocessing needed for Proof database
    }

    @Override
    protected void markEntityAsNew(Proof entity) {
        // No special marking needed for new Proof entities
    }

    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean undestroyed, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(PROOF);
        fcdsl.addNewQuery();

        if(undestroyed != null) {
            if (undestroyed)
                fcdsl.getQuery().addNewTerms().addNewFields(FieldNames.DESTROYED).addNewValues(FALSE);
            else
                fcdsl.getQuery().addNewTerms().addNewFields(FieldNames.DESTROYED).addNewValues(TRUE);
        }

        if(timeAsc){
            fcdsl.addSort(LAST_HEIGHT, ASC).addSort(ID, ASC);
        }else {
            fcdsl.addSort(LAST_HEIGHT, DESC).addSort(ID, DESC);
        }
        fcdsl.getQuery().addNewEquals().addNewFields(OWNER,ISSUER,COSIGNERS_INVITED).addNewValues(liveFid);

        fcdsl.addSize(pageSize);

        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }
        return fcdsl;
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<Proof> entityList, int index, int savedCount) {
        processProofSequentially(activity, entityList, index, savedCount);
    }

    private void processProofSequentially(Activity activity, List<Proof> proofList, int index, int savedCount) {
        if (index >= proofList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        Proof proof = proofList.get(index);

        if(proof.getId()==null) proof.checkIdWithCreate();
        if (checkIfExisted(proof.getId())) {
            String prompt = proof.getTitle() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(proof);
                    processProofSequentially(activity, proofList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processProofSequentially(activity, proofList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.d_saved, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(proof);
            processProofSequentially(activity, proofList, index + 1, savedCount + 1);
        }
    }

    public long getProofDBSize() {
        return getEntityDBSize();
    }

    /**
     * Fetches all proofs for the liveFid from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active proofs
     * @param timeAsc Sort in time ascending order
     * @return List of all Proof objects, or null if failed
     */
    private List<Proof> fetchProofs(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, MAX_PAGES);
        if (result == null) {
            return null;
        }

        List<Proof> proofList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof Proof proof) {
                proof.setOnChain(true);
                proofList.add(proof);
            }
        }
        return proofList;
    }

    public List<Proof> fetchPageProofs(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<Proof> proofList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof Proof proof) {
                proofList.add(proof);
            }
        }

        return proofList;
    }

    /**
     * Converts Proof objects to Proof objects (no decryption needed)
     */
    @Override
    public List<Proof> makeEntityDetails(List<?> proofList, boolean decryptDeleted, Context context) {
        if (proofList == null || proofList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert proofs: proof list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<Proof> proofDetailList = new ArrayList<>();

            for (Object obj : proofList) {
                Proof proof = (Proof) obj;
                // No decryption needed for Proof entities
                proofDetailList.add(proof);
            }

            TimberLogger.i(TAG, "Successfully converted %d proofs to Proof objects",
                proofDetailList.size());
            return proofDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting proofs to Proof objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Refreshes proofs by loading newer changes including inactive proofs for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context       Activity context for dialog operations
     */
    public void refreshProofsFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer proofs changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of proof changes processed, or -1 if failed
     */
    public int loadNewerProofsFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid proofs from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid proof items fetched, or -1 if failed
     */
    public int loadEarlierProofsFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }

    /**
     * Searches for Proof objects by title and content fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of Proof objects that match the search criteria
     */
    public List<Proof> searchProofs(String searchQuery) {
        return searchEntities(searchQuery);
    }

    /**
     * Searches proof data from API with custom field, sort, and order parameters.
     *
     * @param context        Activity context for dialog operations
     * @param searchQuery    The search query string to match in the inField
     * @param inField        The field to search in (e.g., "title", "content")
     * @param sortField      The field to sort by (e.g., LAST_HEIGHT, ID)
     * @param order          Sort order (ASC or DESC)
     * @param referenceProof The reference proof for pagination, or null for first page
     * @return List of proof items matching the search, or null if failed
     */
    public List<Proof> searchProofsFromApi(Context context,
                                           String searchQuery, String inField, String sortField, String order, Proof referenceProof) {
        com.fc.fc_ajdk.fapi.client.FapiClient fapiClient = loadFapiClient();
        if (fapiClient == null) {
            TimberLogger.w(TAG, "Cannot search proofs: FapiClient not available");
            return null;
        }

        TimberLogger.i(TAG, "Searching proofs with query: %s in field: %s, sort: %s %s",
            searchQuery, inField, sortField, order);

        try {
            Fcdsl fcdsl = buildSearchFcdsl(searchQuery, inField, sortField, order, referenceProof);
            List<Proof> proofItems = fetchPageProofs(fcdsl);

            if (proofItems == null) {
                TimberLogger.w(TAG, "Failed to search proofs from API");
                return null;
            }

            TimberLogger.i(TAG, "Successfully searched %d proof items from API", proofItems.size());
            return proofItems;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error searching proofs from API: %s", e.getMessage());
            return null;
        }
    }

    private Fcdsl buildSearchFcdsl(String searchQuery, String inField, String sortField, String order, Proof referenceProof) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(PROOF);
        fcdsl.addSize(com.fc.freer.FreerApplication.DEFAULT_REQUEST_SIZE);

        // Add query
        fcdsl.addNewQuery();

        // Add owner/issuer/cosigner filter for liveFid
        fcdsl.getQuery().addNewEquals().addNewFields(OWNER, ISSUER, COSIGNERS_INVITED).addNewValues(liveFid);

        // Add active filter
        fcdsl.getQuery().addNewTerms().addNewFields(FieldNames.DESTROYED).addNewValues(FALSE);

        // Add search filter
        if (inField != null) {
            String adjustedField = adjustFieldName(inField);
            fcdsl.getQuery().addNewMatch().addNewFields(adjustedField).addNewValue(searchQuery);
        } else {
            fcdsl.getQuery().addNewMatch()
                .addNewFields(FieldNames.TITLE, FieldNames.CONTENT, ISSUER, OWNER, ID)
                .addNewValue(searchQuery);
        }

        // Add sorting
        String normalizedOrder = normalizeOrder(order);
        addSortingToFcdsl(fcdsl, sortField, normalizedOrder);

        // Add pagination
        if (referenceProof != null) {
            List<String> last = java.util.Arrays.asList(String.valueOf(referenceProof.getLastHeight()), referenceProof.getId());
            fcdsl.addAfter(last);
        }

        return fcdsl;
    }

    @androidx.annotation.NonNull
    private static String adjustFieldName(String displayName) {
        String fieldName = Proof.getFieldNameByDisplayName(displayName);
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
            String sortOrder = (order != null) ? order : com.fc.fc_ajdk.constants.Values.DESC;
            fcdsl.addSort(adjustedField, sortOrder);
            if (!adjustedField.equals(ID)) {
                fcdsl.addSort(ID, sortOrder);
            }
        } else if (order != null) {
            fcdsl.addSort(LAST_HEIGHT, order).addSort(ID, order);
        } else {
            // Default sort by lastHeight and ID in descending order
            fcdsl.addSort(LAST_HEIGHT, com.fc.fc_ajdk.constants.Values.DESC).addSort(ID, com.fc.fc_ajdk.constants.Values.DESC);
        }
    }

    /**
     * Refreshes the entire proof database by fetching all active proofs from API
     * and replacing the local database contents.
     *
     * @param context Activity context for dialog operations
     * @return Number of proof items refreshed, or 0 if failed
     */
    public int freshProofDB(Context context) {
        return freshEntityDB(context);
    }

    // Abstract method implementations for ProofManager

    @Override
    protected String getEntityIndexName() {
        return PROOF;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return Proof.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        Proof proof = (Proof) apiObject;
        return Arrays.asList(String.valueOf(proof.getLastHeight()), proof.getId());
    }

    @Override
    protected void preprocessEntity(Proof entity) {
        entity.checkIdWithCreate();
    }

    @Override
    protected boolean isEntityDeleted(Proof entity) {
        return Boolean.TRUE.equals(entity.isDestroyed());
    }

    @Override
    protected Long getEntityUpdateHeight(Proof entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(Proof entity) {
        return entity.getOnChain();
    }

    @Override
    protected List<Proof> searchFromList(String query, List<Proof> results) {
        List<Proof> matchingProofs = new ArrayList<>();

        for (Proof proof : results) {
            boolean matches = false;

            // Search in title
            if (proof.getTitle() != null && proof.getTitle().contains(query)) {
                matches = true;
            }

            // Search in content
            if (!matches && proof.getContent() != null && proof.getContent().contains(query)) {
                matches = true;
            }

            // Search in issuer
            if (!matches && proof.getIssuer() != null && proof.getIssuer().contains(query)) {
                matches = true;
            }

            // Search in owner
            if (!matches && proof.getOwner() != null && proof.getOwner().contains(query)) {
                matches = true;
            }

            if (matches) {
                matchingProofs.add(proof);
            }
        }
        return matchingProofs;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchProofs(context, after, active, timeAsc);
    }

    @Override
    protected Proof createFailedDecryptionEntity(Object apiObject, Context context) {
        // No decryption needed for Proof entities, just return as-is
        return (Proof) apiObject;
    }

    @Override
    protected Proof createUndecryptedEntity(Context context, Object apiObject) {
        // No decryption needed for Proof entities, just return as-is
        if(apiObject instanceof Proof proof) {
            return proof;
        }
        return null;
    }

    public List<Proof> getLocalHiddenProofs() {
        return getLocalDeletedList();
    }

    public long getLocalHiddenProofsSize() {
        Long size = getLocalDeletedListSize();
        return size != null ? size : 0;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        // TODO: Implement DeleteProofActivity when needed
        return null;
    }
}
