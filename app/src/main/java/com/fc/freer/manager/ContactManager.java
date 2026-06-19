package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;
import static com.fc.fc_ajdk.constants.Values.ASC;
import static com.fc.fc_ajdk.constants.Values.DESC;
import static com.fc.fc_ajdk.constants.Values.FALSE;
import static com.fc.fc_ajdk.constants.Values.TRUE;

import android.content.Context;
import android.app.Activity;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.contact.DeleteContactActivity;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.freer.R;
import com.fc.freer.ui.UserConfirmDialog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * A singleton class to manage and share the Contact database across activities.
 * This provides a centralized way to access the Contact database from any activity.
 */
public class ContactManager extends FcManager<Contact> {
    private static final String TAG = "ContactManager";
    public static final int MAX_PAGES = 200;
    public static final String GUIDE = "Guide";
    public static final String MASTER = "Master";

    private static ContactManager instance;

    private ContactManager() {
        super(Contact.class, CONTACT);
    }

    /**
     * Gets the singleton instance of ContactManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The ContactManager instance
     */
    public static synchronized ContactManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new ContactManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized ContactManager getInstance() {
        return instance;
    }

    public static String getPubkeyGlobal(String fid, Context context) {
        String pubkey=null;

        Contact contact = getInstance().getContactByFid(fid);
        if(contact!=null){
            pubkey = contact.getPubkey();
        }

        if(pubkey ==null){
            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            if(fapiClient!=null)
                pubkey = fapiClient.getPubkey(fid);
        }

        if(pubkey ==null){
            ToastUtils.makeText(context, context.getString(R.string.failed_to_get_pubkey));
            return null;
        }
        return pubkey;
    }

    public Map<String,String> getCidLocally(List<String> fidList, Context context) {
        if(fidList==null)return null;
        List<String> missedFidList = new ArrayList<>();
        Map<String,String> hittedCidMap = null;

        //1. from cid_fid manager
        CidFidManager cidFidManager = CidFidManager.getInstance(context);
        if(cidFidManager!=null)
            hittedCidMap = cidFidManager.getCidsByFids(fidList);

        if(hittedCidMap!=null && !hittedCidMap.isEmpty())
            for(String fid:fidList){
                String cid = hittedCidMap.get(fid);
                if(cid==null)missedFidList.add(fid);
            }
        else hittedCidMap=new HashMap<>();

        //2. from contact manager
        Iterator<String> iterator = missedFidList.iterator();
        while(iterator.hasNext()){
            String fid = iterator.next();
            Contact contact = getContactByFid(fid);

            if(contact!=null && contact.getCid()!=null){
                hittedCidMap.put(fid,contact.getCid());
                iterator.remove();
            }
        }

        return hittedCidMap;
    }


    public Map<String,String> getCidGlobal(List<String> fidList, Context context) {
        if(fidList==null)return null;
        List<String> missedFidList = new ArrayList<>();
        Map<String,String> hittedCidMap = null;

        //1. from cid_fid manager
        CidFidManager cidFidManager = CidFidManager.getInstance(context);
        if(cidFidManager!=null)
            hittedCidMap = cidFidManager.getCidsByFids(fidList);

        if(hittedCidMap!=null && !hittedCidMap.isEmpty())
            for(String fid:fidList){
                String cid = hittedCidMap.get(fid);
                if(cid==null)missedFidList.add(fid);
            }
        else {
            hittedCidMap=new HashMap<>();
            missedFidList.addAll(fidList);
        }

        //2. from contact manager
        Iterator<String> iterator = missedFidList.iterator();
        while(iterator.hasNext()){
            String fid = iterator.next();
            Contact contact = getContactByFid(fid);

            if(contact!=null && contact.getCid()!=null){
                hittedCidMap.put(fid,contact.getCid());
                iterator.remove();
            }
        }

        if(missedFidList.isEmpty())return hittedCidMap;

        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if(fapiClient==null)return hittedCidMap;

        Map<String, String> fidCidMap = fapiClient.getFidCidMap(missedFidList);
        if(!fidCidMap.isEmpty()){
            if(cidFidManager!=null)
                cidFidManager.addAll(fidCidMap);
            hittedCidMap.putAll(fidCidMap);
        }
        return hittedCidMap;
    }

    private Map<String,String> getCid(List<String> fidList,Context context) {
        if(fidList==null)return null;
        List<String> missedFidList = new ArrayList<>();
        Map<String,String> hittedCidMap = null;

        //1. from cid_fid manager
        CidFidManager cidFidManager = CidFidManager.getInstance(context);
        if(cidFidManager!=null)
            hittedCidMap = cidFidManager.getCidsByFids(fidList);

        if(hittedCidMap!=null && !hittedCidMap.isEmpty())
            for(String fid:fidList){
                String cid = hittedCidMap.get(fid);
                if(cid==null)missedFidList.add(fid);
            }
        else {
            hittedCidMap=new HashMap<>();
            missedFidList.addAll(fidList);
        }

        if(missedFidList.isEmpty())return hittedCidMap;

        //3. from APIP
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        if(fapiClient==null)return hittedCidMap;

        Map<String, String> result;
        result = fapiClient.getFidCidMap(missedFidList);

        if(!result.isEmpty()){
            if(cidFidManager!=null)
                cidFidManager.addAll(result);
            hittedCidMap.putAll(result);
        }
        return hittedCidMap;
    }

    /**
     * Gets a Contact object by its ID.
     *
     * @param id The ID of the Contact object to get
     * @return The Contact object, or null if not found
     */
    public Contact getContactById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Contact object to the database.
     *
     * @param contact The Contact object to add
     */
    public void addContact(Contact contact) {
        addEntity(contact);
    }

    /**
     * Removes a Contact object from the database.
     *
     * @param contact The Contact object to remove
     */
    public void removeContact(Contact contact) {
        removeEntity(contact);
    }

    /**
     * Removes multiple Contact objects from the database.
     *
     * @param contacts The list of Contact objects to remove
     */
    public void removeContacts(List<Contact> contacts) {
        removeEntities(contacts);
    }

    /**
     * Updates an existing Contact in the database.
     * @param contact The Contact object to update
     */
    public void updateContact(Contact contact) {
        updateEntity(contact);
    }

    /**
     * Gets a paginated list of Contact objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of Contact objects for the requested page
     */

    public List<Contact> getPaginatedContacts(int pageSize, String lastID, boolean fromEnd, Context context) {
        List<Contact> pageContactList = getPaginatedEntities(pageSize, lastID, fromEnd);

        List<Contact> handledContactList = new ArrayList<>();

        for(Contact contact: pageContactList){
            if(contact.getCipher()!=null)
                handledContactList.add(contact);
        }

        if(handledContactList.isEmpty())return pageContactList;

        String prikeyCipher = FidManager.getInstance().getLiveKeyInfo().getPrikeyCipher();
        byte[] prikey;
        if(prikeyCipher==null)
            return pageContactList;

        prikey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(prikeyCipher);
        List<String> parsedFidList = new ArrayList<>();
        for(Contact contact:handledContactList) {
            if(contact.parseDetail(prikey)) {
                contact.setCipher(null);
                parsedFidList.add(contact.getFid());
            }
        }

        if(!handledContactList.isEmpty()){
            Map<String, String> parsedFidCidMap = getCid(parsedFidList, context);
            for(Contact contact:handledContactList){
                String cid = parsedFidCidMap.get(contact.getFid());
                if(cid!=null)
                    contact.setCid(cid);
            }

            entityDB.updateItemsOnly(handledContactList);
        }
        SecurePrikeyManager.erasePrikey(prikey);
        return pageContactList;
    }

    @Override
    protected void preprocessDB() {
        try {
            if(checkIfFidExisted(liveFid))return;
            Contact contactOfMyself = makeContactOfMyself();
            entityDB.put(contactOfMyself.getId(),contactOfMyself);
        }catch (Exception ignored){}
    }

    @Override
    protected void markEntityAsNew(Contact entity) {}

    public void checkGuideAndMaster() {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        String guideFid = liveKeyInfo.getGuide();
        String masterFid = liveKeyInfo.getMaster();

        Contact guide = getContactByFid(guideFid);

        if(guide == null) {
            Contact contactOfGuide = makeContact(guideFid);
            if (contactOfGuide != null) {
                contactOfGuide.setTitles(new ArrayList<>(List.of(GUIDE)));

                entityDB.put(contactOfGuide.getId(), contactOfGuide);
            }
        }else{
            List<String> title = guide.getTitles();
            if(title==null){
                title = new ArrayList<>();
            }else{
                title = new ArrayList<>(title); // Create mutable copy
            }
            if(!title.contains(GUIDE)) {
                title.add(GUIDE);
                guide.setTitles(title);
                entityDB.updateItemsOnly(List.of(guide));
            }
        }

        Contact master = getContactByFid(masterFid);

        if(master == null){
            Contact contactOfMaster = makeContact(masterFid);
            if (contactOfMaster != null) {
                contactOfMaster.setTitles(new ArrayList<>(List.of(MASTER)));

                entityDB.put(contactOfMaster.getId(), contactOfMaster);
            }
        }else {
            List<String> title = master.getTitles();
            if(title==null){
                title = new ArrayList<>();
            }else{
                title = new ArrayList<>(title); // Create mutable copy
            }
            if(!title.contains(MASTER)){
                title.add(MASTER);
                master.setTitles(title);
                entityDB.updateItemsOnly(List.of(master));
            }
        }
    }



    @NonNull
    private static Contact makeContactOfMyself() {
        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        Contact contact = new Contact();
        contact.setFid(liveKeyInfo.getId());
        contact.setCid(liveKeyInfo.getCid());
        contact.setPubkey(liveKeyInfo.getPubkey());
        contact.setTitles(Collections.singletonList("Myself"));
        contact.makeId();
        contact.setOnChain(false);
        contact.setLastHeight(Constants.MaX_HEIGHT);
        return contact;
    }

    private Contact makeContact(String guideFid) {
        if (guideFid == null || guideFid.trim().isEmpty()) {
            TimberLogger.i(TAG, "No guide FID available for liveKeyInfo");
            return null;
        }

        try {
            // Get FapiClient instance
            FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (fapiClient == null) {
                TimberLogger.w(TAG, "FapiClient not available for fetching guide info");
                return null;
            }
            // Request Freer information for the guide
            Freer guideFreer = fapiClient.getFreer(guideFid);

            if (guideFreer == null) {
                TimberLogger.w(TAG, "Failed to fetch Freer info for guide: %s", guideFid);
                return null;
            }

            // Create off-chain contact from guide's Freer info
            Contact contact = Contact.fromCid(guideFreer);
            contact.setOnChain(false);
            contact.setLastHeight(999999998L);

            TimberLogger.i(TAG, "Successfully created contact for guide: %s", guideFid);
            return contact;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating contact for guide %s: %s", guideFid, e.getMessage());
            return null;
        }
    }


    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(CONTACT);
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

    @Override
    protected void processEntitiesSequentially(Activity activity, List<Contact> entityList, int index, int savedCount) {
        processContactSequentially(activity, entityList, index, savedCount);
    }

    private void processContactSequentially(Activity activity, List<Contact> contactList, int index, int savedCount) {
        if (index >= contactList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.contacts_saved_successfully, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        Contact contact = contactList.get(index);

        if(contact.getId()==null) contact.checkIdWithCreate();
        if (checkIfExisted(contact.getId())) {
            String prompt = contact.getFid() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(contact);
                    processContactSequentially(activity, contactList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processContactSequentially(activity, contactList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.contacts_saved_successfully, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(contact);
            processContactSequentially(activity, contactList, index + 1, savedCount + 1);
        }
    }

    public boolean checkIfFidExisted(String fid) {
        List<Contact> result = searchEntities(fid);
        if(result==null || result.isEmpty())return false;
        for(Contact contact:result){
            if(contact.getFid().equals(fid)) return true;
        }
        return false;
    }

    /**
     * Gets a Contact object by FID.
     *
     * @param fid The FID of the contact to find
     * @return The Contact object if found, null otherwise
     */
    public Contact getContactByFid(String fid) {
        if (fid == null || fid.trim().isEmpty()) {
            return null;
        }

        List<Contact> result = searchEntities(fid);
        if (result == null || result.isEmpty()) {
            return null;
        }

        for (Contact contact : result) {
            if (fid.equals(contact.getFid())) {
                return contact;
            }
        }
        return null;
    }

    public long getContactDBSize() {
        return getEntityDBSize();
    }


    /**
     * Fetches all contacts for the liveFid from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active contacts
     * @param timeAsc Sort in time ascending order
     * @return List of all Contact objects, or null if failed
     */
    private List<Contact> fetchContacts(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, MAX_PAGES);
        if (result == null) {
            return null;
        }

        List<Contact> contactList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof Contact contact) {
                contact.setOnChain(true);
                contactList.add(contact);
            }
        }
        return contactList;
    }

    public List<Contact> fetchPageContacts(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<Contact> contactList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof Contact contact) {
                contactList.add(contact);
            }
        }

        return contactList;
    }

    /**
     * Converts Contact objects to Contact objects
     */
    @Override
    public List<Contact> makeEntityDetails(List<?> contactList, boolean decryptDeleted, Context context) {
        if (contactList == null || contactList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert contacts: contact list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<Contact> contactDetailList = new ArrayList<>();
//            List<Contact> failedToDecryptList = new ArrayList<>();

            // Get private key for decryption
            FidManager fidManager = FidManager.getInstance();
            byte[] priKey = null;
            if (fidManager != null) {
                priKey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(fidManager.getLiveKeyInfo().getPrikeyCipher());
            }

            if (priKey == null) {
                TimberLogger.w(TAG, "Cannot decrypt contacts: private key not available");
                for(Object obj : contactList){
                    Contact contact = createUndecryptedEntity(context, obj);
                    if(contact!=null)contactDetailList.add(contact);
                }
                return contactDetailList;
            }
            Set<String> fidSet = new HashSet<>();
            for (Object obj : contactList) {
                Contact contact = (Contact) obj;
                if (!decryptDeleted && Boolean.FALSE.equals(contact.getActive())) {
                    contactDetailList.add(contact);
                    continue;
                }
                try {
                    contact.parseDetail(priKey);
                    contactDetailList.add(contact);
                } catch (Exception decryptionException) {
                    TimberLogger.w(TAG, "Failed to decrypt contact %s: %s", contact.getId(), decryptionException.getMessage());
                    // Create a Contact without encrypted fields for failed decryption
                    Contact failedContact = createFailedDecryptionEntity(contact, context);
                    contactDetailList.add(failedContact);
                }
                if(contact.getFid()!=null)
                    fidSet.add(contact.getFid());
            }

//            Map<String, String> fidCidInfoMap = fapiClient.getFidCidMap(new ArrayList<>(fidSet), context);
            FapiClient fapiClient = loadFapiClient();

            Map<String, Freer> fidCidInfoMap = fapiClient.freerByIds(new ArrayList<>(fidSet));
            Map<String,String> fidCidMap = new HashMap<>();
            for(Contact contact:contactDetailList) {
                if(contact.getFid()==null)
                    continue;
                Freer freer = fidCidInfoMap.get(contact.getFid());
                if(freer ==null)
                    continue;
                contact.setCid(freer.getCid());
                contact.setPubkey(freer.getPubkey());
                contact.setNobody(freer.getNobody());
                contact.setNoticeFee(freer.getNoticeFee());
                contact.setHome(freer.getHome());
                contact.setMultisign(freer.getMultisign());
                fidCidMap.put(freer.getId(), freer.getCid());
            }

            CidFidManager.getInstance(context).addAll(fidCidMap);

            SecurePrikeyManager.erasePrikey(priKey);

            TimberLogger.i(TAG, "Successfully converted %d contacts to Contact objects",
                contactDetailList.size());
            return contactDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting contacts to Contact objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Refreshes contacts by loading newer changes including inactive contacts for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context       Activity context for dialog operations
     */
    public void refreshContactsFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer contacts changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of contact changes processed, or -1 if failed
     */
    public int loadNewerContactsFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid contacts from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid contact items fetched, or -1 if failed
     */
    public int loadEarlierContactsFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }

    /**
     * Searches for Contact objects by title, fid, and memo fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of Contact objects that match the search criteria
     */
    public List<Contact> searchContacts(String searchQuery) {
        return searchEntities(searchQuery);
    }

    // Abstract method implementations for ContactManager

    @Override
    protected String getEntityIndexName() {
        return CONTACT;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return Contact.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        Contact contact = (Contact) apiObject;
        return Arrays.asList(String.valueOf(contact.getLastHeight()), contact.getId());
    }

    @Override
    protected void preprocessEntity(Contact entity) {
        entity.checkIdWithCreate();
    }

    @Override
    protected boolean isEntityDeleted(Contact entity) {
        return Boolean.FALSE.equals(entity.getActive());
    }

    @Override
    protected Long getEntityUpdateHeight(Contact entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(Contact entity) {
        return entity.getOnChain();
    }

    @Override
    protected List<Contact> searchFromList(String query, List<Contact> results) {
        List<Contact> matchingContacts = new ArrayList<>();

        for (Contact contact : results) {
            boolean matches = false;

            // Search in fid
            if (contact.getFid() != null && contact.getFid().contains(query)) {
                matches = true;
            }

            // Search in titles
            if (!matches && contact.getTitles() != null) {
                for (String title : contact.getTitles()) {
                    if (title != null && title.contains(query)) {
                        matches = true;
                        break;
                    }
                }
            }

            // Search in memo
            if (!matches && contact.getMemo() != null && contact.getMemo().contains(query)) {
                matches = true;
            }

            // Search in cid
            if (!matches && contact.getCid() != null && contact.getCid().contains(query)) {
                matches = true;
            }

            if (matches) {
                matchingContacts.add(contact);
            }
        }
        return matchingContacts;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchContacts(context, after, active, timeAsc);
    }

    @Override
    protected Contact createFailedDecryptionEntity(Object apiObject, Context context) {
        Contact contact = (Contact) apiObject;
        contact.setMemo("This contact could not be decrypted with current key");
        return contact;
    }

    @Override
    protected Contact createUndecryptedEntity(Context context, Object apiObject) {
        if(apiObject instanceof Contact contact) {
            contact.setFid(context.getString(R.string.undecrypted));
            return contact;
        }
        return null;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        return DeleteContactActivity.class;
    }
}