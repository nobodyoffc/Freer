package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.IndicesNames.SECRET;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.FALSE;
import static com.fc.fc_ajdk.constants.Values.TRUE;
import static com.fc.fc_ajdk.data.feipData.Secret.Type.TOTP;

import android.content.Context;
import android.app.Activity;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.Base32;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.FreerApplication;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.freer.R;
import com.fc.freer.ui.UserConfirmDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 * A singleton class to manage and share the Secret database across activities.
 * This provides a centralized way to access the Secret database from any activity.
 */
public class SecretManager extends FcManager<Secret>{
    private static final String TAG = "SecretManager";

    private static SecretManager instance;

    private SecretManager() {
        super(Secret.class,SECRET);
    }

    /**
     * Gets the singleton instance of SecretManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The SecretManager instance
     */
    public static synchronized SecretManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new SecretManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized SecretManager getInstance() {
        return instance;
    }

    /**
     * Gets a Secret object by its ID.
     *
     * @param id The ID of the Secret object to get
     * @return The Secret object, or null if not found
     */
    public Secret getSecretDetailById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Secret object to the database.
     *
     * @param secret The Secret object to add
     */
    public void addSecretDetail(Secret secret) {
        addEntity(secret);
    }

    /**
     * Removes a Secret object from the database.
     *
     * @param secret The Secret object to remove
     */
    public void removeSecretDetail(Secret secret) {
        removeEntity(secret);
    }

    /**
     * Removes multiple Secret objects from the database.
     *
     * @param secrets The list of Secret objects to remove
     */
    public void removeSecretDetails(List<Secret> secrets) {
        removeEntities(secrets);
    }

    /**
     * Updates an existing Secret in the database.
     * @param secret The Secret object to update
     */
    public void updateSecret(Secret secret) {
        updateEntity(secret);
    }

    /**
     * Gets a paginated list of Secret objects.
     *
     * @param pageSize The number of items per page
     * @param lastID The ID of the last item from the previous page, or null for the first page
     * @param fromEnd Whether to sort in fromEnd order
     * @return A list of Secret objects for the requested page
     */
    public List<Secret> getPaginatedSecrets(int pageSize, String lastID, boolean fromEnd) {
        List<Secret> pageSecretList = getPaginatedEntities(pageSize, lastID, fromEnd);

        List<Secret> handledSecretList = new ArrayList<>();

        for(Secret secret: pageSecretList){
            if(secret.getContentCipher()==null && secret.getContent()==null && secret.getCipher()!=null)
                handledSecretList.add(secret);
        }

        if(handledSecretList.isEmpty())return pageSecretList;

        String prikeyCipher = FidManager.getInstance().getLiveKeyInfo().getPrikeyCipher();
        byte[] prikey=null;
        byte[] pubkey = null;
        if(prikeyCipher==null)
            return pageSecretList;

        prikey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(prikeyCipher);
        if(prikey!=null)
            pubkey = KeyTools.prikeyToPubkey(prikey);

        for(Secret secret:handledSecretList)
            secret.parseDetail(prikey, pubkey);

        if(!handledSecretList.isEmpty()){
            entityDB.updateItemsOnly(handledSecretList);
        }

        SecurePrikeyManager.erasePrikey(prikey);
        return pageSecretList;
    }

    @Override
    protected void preprocessDB() {
        String defaultSecretId = "defaultSecret";
        if(checkIfExisted(defaultSecretId))return;

        Secret initialSecret = new Secret();
        initialSecret.setOnChain(false);
        byte[] randomBytes = BytesUtils.getRandomBytes(16);
        String base32 = Base32.toBase32(randomBytes);
        initialSecret.setContent(base32);
        initialSecret.setTitle("Sample: My TOTP");
        initialSecret.setType(TOTP.displayName);
        initialSecret.setLastHeight(Constants.MaX_HEIGHT);
        initialSecret.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(),DateUtils.TO_MINUTE));
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if(liveKeyInfo!=null && liveKeyInfo.getPrikeyCipher()!=null){
            String pubkeyHex = liveKeyInfo.getPubkey();

            if(pubkeyHex!=null){
                CryptoDataByte result = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptByAsyOneWay(initialSecret.getContent().getBytes(), Hex.fromHex(pubkeyHex));
                if(result!=null && result.getCode()==0 && result.getCipher()!=null){
                    initialSecret.setContentCipher(result.toJson());
                    initialSecret.setContent(null);
                }
            }
        }
        initialSecret.setId(defaultSecretId);

        entityDB.put(initialSecret.getId(),initialSecret);
    }

    @Override
    protected void markEntityAsNew(Secret entity) {}

    @Override
    protected void processEntitiesSequentially(Activity activity, List<Secret> entityList, int index, int savedCount) {
        String pubkey = FidManager.getInstance().getLiveKeyInfo().getPubkey();
        byte[] pubkeyBytes = Hex.fromHex(pubkey);
        processSecretSequentially(activity, entityList, pubkeyBytes, index, savedCount);
    }

    private void processSecretSequentially(Activity activity, List<Secret> secretList, byte[] pubkey, int index, int savedCount) {
        if (index >= secretList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.secrets_saved_successfully, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        Secret secret = secretList.get(index);

        if(secret.getContent()!=null){
            secret.setContentCipher(new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7).encryptByAsyOneWay(secret.getContent().getBytes(),pubkey).toJson());
            secret.setContent(null);
        }
        if(secret.getId()==null) secret.makeId();
        if (checkIfExisted(secret.getId())) {
            String prompt = secret.getTitle() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item",prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(secret);
                    processSecretSequentially(activity, secretList, pubkey, index + 1,savedCount+1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processSecretSequentially(activity, secretList, pubkey, index +1,savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.secrets_saved_successfully, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(secret);
            processSecretSequentially(activity, secretList, pubkey, index + 1,savedCount+1);
        }
    }

    public long getSecretDBSize() {
        return getEntityDBSize();
    }

    /**
     * Creates FCDSL query for secret search
     */
    public static Fcdsl secretSearchFcdsl(String fid, String order, int size, List<String> last, Boolean active) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(SECRET);
        fcdsl.addNewQuery()
                .addNewTerms()
                .addNewFields(FieldNames.OWNER)
                .addNewValues(fid);
        
        if(active!=null)
            fcdsl.getQuery()
                    .addNewEquals()
                    .addNewFields(ACTIVE)
                    .addNewValues(String.valueOf(active));
        
        if(order!=null){
            if(order.equals(ASC)){
                fcdsl.addSort(LAST_HEIGHT, ASC);
                fcdsl.addSort(ID, ASC);
            }else if(order.equals(DESC)){
                fcdsl.addSort(LAST_HEIGHT, DESC);
                fcdsl.addSort(ID, DESC);
            }
        }
        
        if (last != null) {
            fcdsl.addAfter(last);
        }
        
        if (size != 0)
            fcdsl.addSize(size);
        return fcdsl;
    }

    /**
     * Fetches all secrets for the liveFid from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active secrets
     * @param timeAsc Sort in time ascending order
     * @return List of all Secret objects, or null if failed
     */
    private List<Secret> fetchSecrets(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, FreerApplication.DEFAULT_REQUEST_PAGE_COUNT);
        if (result == null) {
            return null;
        }

        List<Secret> secretList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof Secret) {
                secretList.add((Secret) obj);
            }
        }
        return secretList;
    }

    public List<Secret> fetchPageSecrets(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<Secret> secretList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof Secret secret) {
                secretList.add(secret);
            }
        }

        return secretList;
    }

    /**
     * Converts Secret objects to Secret objects
     */
    @Override
    public List<Secret> makeEntityDetails(List<?> secretList, boolean decryptDeleted, Context context) {
        if (secretList == null || secretList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert secrets: secret list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<Secret> secretDetailList = new ArrayList<>();

            // Get private key for decryption
            FidManager fidManager = FidManager.getInstance();
            byte[] priKey = null;
            if (fidManager != null) {
                priKey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(fidManager.getLiveKeyInfo().getPrikeyCipher());
            }

            if (priKey == null) {
                TimberLogger.w(TAG, "Cannot decrypt secrets: private key not available");
                for(Object obj : secretList){
                    if(! (obj instanceof Secret secret))continue;
                    Secret secretDetail = createUndecryptedEntity(context, secret);
                    secretDetailList.add(secretDetail);
                }
                return secretDetailList;
            }

            byte[] pubkey = KeyTools.prikeyToPubkey(priKey);

            for (Object obj : secretList) {
                Secret secret = (Secret) obj;
                secret.setOnChain(true);
                if(!decryptDeleted && Boolean.FALSE.equals(secret.getActive())){
                    secretDetailList.add(secret);
                    continue;
                }

                try {
                    if (secret.parseDetail(priKey, pubkey)) {
                        secret.setSaveTime(DateUtils.longShortToTime(secret.getBirthTime(),DateUtils.TO_MINUTE));
                    } else {
                        // Create a Secret without encrypted fields for failed decryption
                        createFailedDecryptionEntity(secret, context);
                    }
                    secretDetailList.add(secret);

                } catch (Exception decryptionException) {
                    TimberLogger.w(TAG, "Failed to decrypt secret %s: %s", secret.getId(), decryptionException.getMessage());
                    // Create a Secret without encrypted fields for failed decryption
                    Secret failedSecret = createFailedDecryptionEntity(secret, context);
                    secretDetailList.add(failedSecret);
                }
            }

            SecurePrikeyManager.erasePrikey(priKey);

            TimberLogger.i(TAG, "Successfully converted %d secrets to Secret objects",
                secretDetailList.size());
            return secretDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting secrets to Secret objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    protected Secret createFailedDecryptionEntity(Object apiObject, Context context) {
        Secret secret = (Secret) apiObject;
        Secret secretDetail = new Secret();
        secretDetail.setId(secret.getId());
        secretDetail.setLastHeight(secret.getLastHeight());
        secretDetail.setSaveTime(DateUtils.longShortToTime(secret.getBirthTime(),DateUtils.TO_MINUTE));
        secretDetail.setTitle(context.getString(R.string.undecrypted));
        return secretDetail;
    }

    @Override
    protected Secret createUndecryptedEntity(Context context, Object apiObject) {
        Secret secret = (Secret) apiObject;
        secret.setSaveTime(DateUtils.longShortToTime(secret.getBirthTime(),DateUtils.TO_MINUTE));
        secret.setTitle(secret.getId());
        secret.setOwner(secret.getOwner());

        return secret;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        return com.fc.freer.secret.DeleteSecretActivity.class;
    }

    /**
     * Refreshes secrets by loading newer changes including inactive secrets for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context       Activity context for dialog operations
     */
    public void refreshSecretsFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer secrets changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of secret changes processed, or -1 if failed
     */
    public int loadNewerSecretsFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid secrets from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid secret items fetched, or -1 if failed
     */
    public int loadEarlierSecretsFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }


    /**
     * Searches for Secret objects by title, type, and memo fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of Secret objects that match the search criteria
     */
    public List<Secret> searchSecrets(String searchQuery) {
        return searchEntities(searchQuery);
    }

    // Abstract method implementations for SecretManager

    @Override
    protected String getEntityIndexName() {
        return SECRET;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return Secret.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        Secret secret = (Secret) apiObject;
        return Arrays.asList(String.valueOf(secret.getLastHeight()), secret.getId());
    }

    @Override
    protected void preprocessEntity(Secret entity) {
        if(entity.getSaveTime()==null)
            entity.setSaveTime(DateUtils.longToTime(System.currentTimeMillis(),DateUtils.TO_MINUTE));
        entity.checkIdWithCreate();
        // Clear content field for security - only store contentCipher
        entity.setContent(null);
    }

    @Override
    protected boolean isEntityDeleted(Secret entity) {
        return Boolean.FALSE.equals(entity.getActive());
    }

    @Override
    protected Long getEntityUpdateHeight(Secret entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(Secret entity) {
        return entity.getOnChain();
    }

    @Override
    protected List<Secret> searchFromList(String query, List<Secret> results) {
        List<Secret> matchingSecrets = new ArrayList<>();

        for (Secret secret : results) {
            boolean matches = false;

            // Search in title
            if (secret.getTitle() != null && secret.getTitle().contains(query)) {
                matches = true;
            }

            // Search in type
            if (!matches && secret.getType() != null && secret.getType().contains(query)) {
                matches = true;
            }

            // Search in memo
            if (!matches && secret.getMemo() != null && secret.getMemo().contains(query)) {
                matches = true;
            }

            if (matches) {
                matchingSecrets.add(secret);
            }
        }
        return matchingSecrets;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchSecrets(context, after, active, timeAsc);
    }

    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(SECRET);
        fcdsl.addNewQuery();
        if(active != null) {
            if (active)
                fcdsl.getQuery().addNewTerms().addNewFields(ACTIVE).addNewValues(TRUE);
            else
                fcdsl.getQuery().addNewTerms().addNewFields(ACTIVE).addNewValues(FALSE);
        }

        if(timeAsc){
            fcdsl.addSort(LAST_HEIGHT,ASC).addSort(ID,ASC);
        }else {
            fcdsl.addSort(LAST_HEIGHT,DESC).addSort(ID,DESC);
        }
        fcdsl.getQuery().addNewEquals().addNewFields(OWNER).addNewValues(liveFid);

        fcdsl.addSize(pageSize);

        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }
        return fcdsl;
    }

}