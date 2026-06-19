package com.fc.freer.manager;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.FROM;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.TO;
import static com.fc.fc_ajdk.constants.IndicesNames.MAIL;
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
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.freer.R;
import com.fc.freer.ui.UserConfirmDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Map;

/**
 * A singleton class to manage and share the Mail database across activities.
 * This provides a centralized way to access the Mail database from any activity.
 */
public class MailManager extends FcManager<Mail> {
    public static final String UNREAD_MAP = "unreadMails";
    public static final String MAX_PAYING_NOTICE_FEE = "max_paying_notice_fee";
    public static final String PAY_BACK_NOTICE_FEE = "pay_back_notice_fee";
    public static final String DEFAULT_MAX_PAYING_NOTICE_FEE = "100";
    public static final long DEFAULT_MAIL_FEE_SATOSHI = 10000L; // 0.0001 F
    private static final String TAG = "MailManager";
    public static final int MAX_PAGES = 200;

    private static MailManager instance;

    private MailManager() {
        super(Mail.class, MAIL);
    }

    /**
     * Gets the singleton instance of MailManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid The live FID for this instance
     * @return The MailManager instance
     */
    public static synchronized MailManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new MailManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized MailManager getInstance() {
        return instance;
    }

    public static void makeNames(List<Mail> mailList, Context context) {
        List<String> fidList = Mail.parseFidList(mailList);

        ContactManager contactManager = ContactManager.getInstance();
        if(contactManager==null)return;
        Map<String,String> fidCidMap = contactManager.getCidGlobal(fidList,context);
        if(fidCidMap==null || fidCidMap.isEmpty())return;
        for(Mail mail: mailList){
            String fromCid = fidCidMap.get(mail.getFrom());
            if(fromCid!=null)mail.setFromName(fromCid);
            else mail.setFromName(mail.getFrom());

            String toCid = fidCidMap.get(mail.getTo());
            if(toCid!=null)mail.setToName(toCid);
            else mail.setToName(mail.getTo());
        }
    }

    /**
     * Gets a Mail object by its ID.
     *
     * @param id The ID of the Mail object to get
     * @return The Mail object, or null if not found
     */
    public Mail getMailById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Mail object to the database.
     *
     * @param mail The Mail object to add
     */
    public void addMail(Mail mail) {
        addEntity(mail);
    }

    /**
     * Removes a Mail object from the database.
     *
     * @param mail The Mail object to remove
     */
    public void removeMail(Mail mail) {
        removeEntity(mail);
    }

    /**
     * Removes multiple Mail objects from the database.
     *
     * @param mails The list of Mail objects to remove
     */
    public void removeMails(List<Mail> mails) {
        removeEntities(mails);
    }

    /**
     * Updates an existing Mail in the database.
     * @param mail The Mail object to update
     */
    public void updateMail(Mail mail) {
        updateEntity(mail);
    }

    /**
     * Gets a paginated list of Mail objects.
     *
     * @param pageSize The number of items per page
     * @param lastID The ID of the last item from the previous page, or null for the first page
     * @param fromEnd Whether to sort in fromEnd order
     * @return A list of Mail objects for the requested page
     */

    public List<Mail> getPaginatedMails(int pageSize, String lastID, boolean fromEnd) {
        List<Mail> pageMailList = getPaginatedEntities(pageSize, lastID, fromEnd);

        List<Mail> handledMailList = new ArrayList<>();

        for(Mail mail: pageMailList){
            if(mail.getCipher()!=null )
                handledMailList.add(mail);
        }

        if(handledMailList.isEmpty())return pageMailList;

        String prikeyCipher = FidManager.getInstance().getLiveKeyInfo().getPrikeyCipher();
        byte[] prikey;
        if(prikeyCipher==null)
            return pageMailList;

        prikey = SecurePrikeyManager.fetchPrikeySilentAndPersistent(prikeyCipher);

        if(!handledMailList.isEmpty()){
            for(Mail mail:handledMailList) {
                mail.parseDetail(prikey);
            }
            entityDB.updateItemsOnly(handledMailList);
        }
        SecurePrikeyManager.erasePrikey(prikey);
        return pageMailList;
    }

    @Override
    protected void preprocessDB() {
        if(checkIfSenderExisted(liveFid))return;
        try {
            KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();

            // Create a sample mail entry for the current user
            Mail mail = new Mail();
            mail.setFrom(liveKeyInfo.getId());
            mail.setTo(liveKeyInfo.getId());
            mail.setContent("Welcome to Freer Mail System");
            mail.checkIdWithCreate();
            mail.setOnChain(false);
            mail.setLastHeight(Constants.MaX_HEIGHT);
            mail.setActive(true);

            entityDB.put(mail.getId(),mail);

            if(getSetting(MAX_PAYING_NOTICE_FEE)==null){
                setSetting(MAX_PAYING_NOTICE_FEE,DEFAULT_MAX_PAYING_NOTICE_FEE);
            }

            if(getSetting(PAY_BACK_NOTICE_FEE)==null){
                setSetting(PAY_BACK_NOTICE_FEE, TRUE);
            }
        }catch (Exception ignored){}
    }

    @Override
    protected void markEntityAsNew(Mail entity) {
        entity.setUnread(true);
        if (entityDB != null && entity.getId() != null) {
            entityDB.putInMap(UNREAD_MAP, entity.getId(), true);
        }
    }

    @Override
    protected void processEntitiesSequentially(Activity activity, List<Mail> entityList, int index, int savedCount) {
        processMailSequentially(activity, entityList, index, savedCount);
    }

    private void processMailSequentially(Activity activity, List<Mail> mailList, int index, int savedCount) {
        if (index >= mailList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.mails_saved, savedCount));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }
        Mail mail = mailList.get(index);

        if(mail.getId()==null) mail.checkIdWithCreate();
        if (checkIfExisted(mail.getId())) {
            String prompt = mail.getId() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(mail);
                    processMailSequentially(activity, mailList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processMailSequentially(activity, mailList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.mails_saved, savedCount));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(mail);
            processMailSequentially(activity, mailList, index + 1, savedCount + 1);
        }
    }

    public boolean checkIfSenderExisted(String sender) {
        List<Mail> result = searchEntities(sender);
        if(result==null || result.isEmpty())return false;
        for(Mail mail:result){
            if(mail.getFrom() != null && mail.getFrom().equals(sender)) return true;
        }
        return false;
    }

    public int getUnreadMailCount() {
        if (entityDB == null) return 0;
        return entityDB.getMapSize(UNREAD_MAP);
    }

    /**
     * Marks all mails in the database as read.
     * @return the number of mails that were marked as read
     */
    public int markAllMailsAsRead() {
        if (entityDB == null) return 0;
        Map<String, Object> unreadMap = entityDB.getAllFromMap(UNREAD_MAP);
        if (unreadMap == null || unreadMap.isEmpty()) return 0;
        int count = unreadMap.size();
        for (String id : unreadMap.keySet()) {
            Mail mail = entityDB.get(id);
            if (mail != null) {
                mail.setUnread(false);
                entityDB.put(id, mail);
            }
        }
        entityDB.clearMap(UNREAD_MAP);
        commit();
        return count;
    }

    /**
     * Marks a single mail as read by ID.
     * Updates the mail entity and removes from the unread map.
     *
     * @param mailId The ID of the mail to mark as read
     */
    public void markMailAsRead(String mailId) {
        if (entityDB == null || mailId == null) return;
        Mail mail = entityDB.get(mailId);
        if (mail != null && Boolean.TRUE.equals(mail.getUnread())) {
            mail.setUnread(false);
            entityDB.put(mailId, mail);
        }
        entityDB.removeFromMap(UNREAD_MAP, mailId);
        commit();
    }

    /**
     * Marks multiple mails as read by their IDs.
     * Updates the mail entities and removes from the unread map.
     *
     * @param mailIds The list of mail IDs to mark as read
     * @return the number of mails that were marked as read
     */
    public int markMailsAsRead(List<String> mailIds) {
        if (entityDB == null || mailIds == null || mailIds.isEmpty()) return 0;
        int count = 0;
        for (String id : mailIds) {
            Mail mail = entityDB.get(id);
            if (mail != null && Boolean.TRUE.equals(mail.getUnread())) {
                mail.setUnread(false);
                entityDB.put(id, mail);
                count++;
            }
        }
        entityDB.removeFromMap(UNREAD_MAP, mailIds);
        commit();
        return count;
    }

    public long getMailDBSize() {
        return getEntityDBSize();
    }

    /**
     * Creates FCDSL query for mail search
     */
    @NonNull
    @Override
    public Fcdsl makeFcdsl(int pageSize, List<String> after, Boolean active, boolean timeAsc) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(MAIL);
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
        fcdsl.getQuery().addNewEquals().addNewFields(FROM,TO).addNewValues(liveFid);

        fcdsl.addSize(pageSize);

        if (after != null && !after.isEmpty()) {
            fcdsl.addAfter(after);
        }
        return fcdsl;
    }

    /**
     * Fetches all mails for the liveFid from the API using pagination.
     * Delegates to the base class fetchEntities method.
     *
     * @param context Activity context for dialog operations
     * @param last    Starting point for pagination, null to fetch from beginning
     * @param active  Only fetch active mails
     * @param timeAsc Sort in time ascending order
     * @return List of all Mail objects, or null if failed
     */
    private List<Mail> fetchMails(Context context, List<String> last, Boolean active, boolean timeAsc) {
        List<?> result = fetchEntities(context, last, active, timeAsc, MAX_PAGES);
        if (result == null) {
            return null;
        }

        List<Mail> mailList = new ArrayList<>();
        for (Object obj : result) {
            if (obj instanceof Mail mail) {
                mail.setOnChain(true);
                mailList.add(mail);
            }
        }
        return mailList;
    }

    public List<Mail> fetchPageMails(Fcdsl fcdsl) {
        List<?> result = fetchPageEntities(fcdsl);
        if (result == null) {
            return null;
        }

        List<Mail> mailList = new ArrayList<>();

        for (Object obj : result) {
            if (obj instanceof Mail mail) {
                mailList.add(mail);
            }
        }

        return mailList;
    }

    /**
     * Converts Mail objects to Mail objects
     */
    @Override
    public List<Mail> makeEntityDetails(List<?> mailList, boolean decryptDeleted, Context context) {
        if (mailList == null || mailList.isEmpty()) {
            TimberLogger.w(TAG, "Cannot convert mails: mail list is null or empty");
            return new ArrayList<>();
        }

        try {
            List<Mail> mailDetailList = new ArrayList<>();

            // Get private key for decryption
            FidManager fidManager = FidManager.getInstance();
            byte[] priKey = null;
            if (fidManager != null) {
                priKey = com.fc.freer.utils.SecurePrikeyManager.fetchPrikeySilentAndPersistent(fidManager.getLiveKeyInfo().getPrikeyCipher());
            }

            if (priKey == null) {
                TimberLogger.w(TAG, "Cannot decrypt mails: private key not available");
                for(Object object: mailList){
                    Mail mail = createUndecryptedEntity(context,object);
                    if(mail!=null)mailDetailList.add(mail);
                }
                return mailDetailList;
            }

            for (Object obj : mailList) {
                Mail mail = (Mail) obj;
                if(mail.getTo()==null)mail.setTo(mail.getFrom());
                if(mail.getCipher()==null){
                    continue;
                }
                if (!decryptDeleted && Boolean.FALSE.equals(mail.getActive())) {
                    mailDetailList.add(mail);
                    continue;
                }
                try {
                    mail.parseDetail(priKey);
                    mailDetailList.add(mail);
                } catch (Exception decryptionException) {
                    TimberLogger.w(TAG, "Failed to decrypt mail %s: %s", mail.getId(), decryptionException.getMessage());
                    // Create a Mail without encrypted fields for failed decryption
                    Mail failedMail = createFailedDecryptionEntity(mail, context);
                    mailDetailList.add(failedMail);
                }
            }

            com.fc.freer.utils.SecurePrikeyManager.erasePrikey(priKey);

            TimberLogger.i(TAG, "Successfully converted %d mails to Mail objects",
                mailDetailList.size());

            MailManager.makeNames(mailDetailList,context);

            return mailDetailList;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting mails to Mail objects: %s", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Refreshes mails by loading newer changes including inactive mails for cleanup.
     * Used when there is existing lastUpdated state.
     *
     * @param context       Activity context for dialog operations
     */
    public void refreshMailsFromAPI(Context context) {
        refreshEntitiesFromAPI(context);
    }

    /**
     * Loads newer mails changes from API for explicit newer data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of mail changes processed, or -1 if failed
     */
    public int loadNewerMailsFromAPI(Context context) {
        return loadNewerEntitiesFromAPI(context);
    }

    /**
     * Loads earlier (older) valid mails from API.
     * Used for both initial access and explicit earlier data loading.
     *
     * @param context Activity context for dialog operations
     * @return Number of valid mail items fetched, or -1 if failed
     */
    public int loadEarlierMailsFromAPI(Context context) {
        return loadEarlierEntitiesFromAPI(context);
    }

    /**
     * Searches for Mail objects by sender, recipient, and content fields using database-level search
     *
     * @param searchQuery The search query string
     * @return A list of Mail objects that match the search criteria
     */
    public List<Mail> searchMails(String searchQuery) {
        return searchEntities(searchQuery);
    }

    // Abstract method implementations for MailManager

    @Override
    protected String getEntityIndexName() {
        return MAIL;
    }

    @Override
    protected Class<?> getApiObjectClass() {
        return Mail.class;
    }

    @Override
    protected List<String> makeSortList(Object apiObject) {
        Mail mail = (Mail) apiObject;
        return Arrays.asList(String.valueOf(mail.getLastHeight()), mail.getId());
    }

    @Override
    protected void preprocessEntity(Mail entity) {
        entity.checkIdWithCreate();
    }

    @Override
    protected boolean isEntityDeleted(Mail entity) {
        return Boolean.FALSE.equals(entity.getActive());
    }

    @Override
    protected Long getEntityUpdateHeight(Mail entity) {
        return entity.getLastHeight();
    }

    @Override
    protected Boolean isEntityOnChain(Mail entity) {
        return entity.getOnChain();
    }

    @Override
    protected List<Mail> searchFromList(String query, List<Mail> results) {
        List<Mail> matchingMails = new ArrayList<>();

        for (Mail mail : results) {
            boolean matches = false;

            // Search in sender
            if (mail.getFrom() != null && mail.getFrom().contains(query)) {
                matches = true;
            }

            // Search in recipient
            if (!matches && mail.getTo() != null && mail.getTo().contains(query)) {
                matches = true;
            }
            if (!matches && mail.getToName() != null && mail.getToName().contains(query)) {
                matches = true;
            }

            // Search in from
            if (!matches && mail.getFrom() != null && mail.getFrom().contains(query)) {
                matches = true;
            }

            if (!matches && mail.getFromName() != null && mail.getFromName().contains(query)) {
                matches = true;
            }

            // Search in content
            if (!matches && mail.getContent() != null && mail.getContent().contains(query)) {
                matches = true;
            }

            // Search in mailId
            if (!matches && mail.getId() != null && mail.getId().contains(query)) {
                matches = true;
            }

            if (matches) {
                matchingMails.add(mail);
            }
        }
        return matchingMails;
    }

    @Override
    protected List<?> fetchEntitiesFromAPI(Context context,
                                           List<String> after, Boolean active, boolean timeAsc, String index) {
        return fetchMails(context, after, active, timeAsc);
    }

    @Override
    protected Mail createFailedDecryptionEntity(Object apiObject, Context context) {
        Mail mail = (Mail) apiObject;
        mail.setContent("This mail could not be decrypted with current key");
        return mail;
    }

    @Override
    protected Mail createUndecryptedEntity(Context context, Object apiObject) {
        if(apiObject instanceof Mail mail) {
            mail.setContent(context.getString(R.string.undecrypted));
            return mail;
        }
        return null;
    }

    @Override
    protected Class<?> getDeleteActivityClass() {
        return com.fc.freer.secret.DeleteSecretActivity.class; // Placeholder - should create DeleteMailActivity
    }

    /**
     * Calculates the appropriate fee to pay for sending mail to a contact.
     * Checks if the contact has a notice fee and validates it against the configured limit.
     * Falls back to the default mail fee if no notice fee is set.
     *
     * @param fid The recipient
     * @return Fee amount in FCH
     */
    public double calculateMailFee(String fid, Context context)  {
        double defaultFee =FchUtils.satoshiToCoin(DEFAULT_MAIL_FEE_SATOSHI);
        if(fid ==null)return defaultFee;
        FapiClient fapiClient = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        String noticeFeeStr;
        if(fapiClient!=null){
            Freer freer =  fapiClient.freerById(fid);
            if(freer == null )return defaultFee;

            noticeFeeStr = freer.getNoticeFee();
            if(noticeFeeStr==null)return defaultFee;
            double amountToPay = Double.parseDouble(noticeFeeStr);
            Object maxPayingNoticeFeeObj = getSetting(MAX_PAYING_NOTICE_FEE);

            if (maxPayingNoticeFeeObj != null) {
                double payLimit = Double.parseDouble((String) maxPayingNoticeFeeObj);
                if (amountToPay > payLimit) {
                    return -1;
                }
            }
            return amountToPay;
        }
        return defaultFee;
    }

    @Override
    public void updateOnChainTotal(Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(getEntityIndexName());
        fcdsl.addNewQuery().addNewTerms().addNewFields(FROM,TO).addNewValues(FidManager.getInstance().getLiveFid());
        fcdsl.getQuery().addNewEquals().addNewFields(ACTIVE).addNewValues(TRUE);
        fcdsl.setSize("0");

        FapiClient fapiClient = loadFapiClient();
        if(fapiClient==null)return;
        fapiClient.search(fcdsl);
        Long receivedTotal = fapiClient.getLastResponse().getTotal();
        if(receivedTotal!=null){
            saveStateTotal(receivedTotal);
            totalOnChain = receivedTotal;
        }
    }
}