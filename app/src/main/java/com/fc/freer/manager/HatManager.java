package com.fc.freer.manager;

import android.app.Activity;
import android.content.Context;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * A singleton class to manage and share the Hat database across activities.
 * This provides a centralized way to access the Hat database from any activity.
 * Hat objects are managed locally without API synchronization or encryption.
 * 
 * Extends LocalEntityManager for local-only entity management without blockchain sync.
 */
public class HatManager extends LocalEntityManager<Hat> {
    private static final String TAG = "HatManager";
    private static final String ENTITY_NAME = "hat";

    private static HatManager instance;

    private HatManager() {
        super(Hat.class, ENTITY_NAME);
    }

    /**
     * Gets the singleton instance of HatManager.
     *
     * @param context The context to use for getting the DatabaseManager
     * @param fid     The live FID for this instance
     * @return The HatManager instance
     */
    public static synchronized HatManager getInstance(Context context, String fid) {
        if (instance == null || !fid.equals(instance.liveFid)) {
            instance = new HatManager();
            instance.initialize(context.getApplicationContext(), fid);
        }
        return instance;
    }

    public static synchronized HatManager getInstance() {
        return instance;
    }

    // ========================================
    // Hat-Specific Query Methods
    // ========================================

    /**
     * Gets a Hat object by its ID.
     *
     * @param id The ID of the Hat object to get
     * @return The Hat object, or null if not found
     */
    public Hat getHatById(String id) {
        return getEntityById(id);
    }

    /**
     * Adds a Hat object to the database.
     *
     * @param hat The Hat object to add
     */
    public void addHat(Hat hat) {
        addEntity(hat);
    }

    /**
     * Removes a Hat object from the database.
     *
     * @param hat The Hat object to remove
     */
    public void removeHat(Hat hat) {
        removeEntity(hat);
    }

    /**
     * Removes multiple Hat objects from the database.
     *
     * @param hats The list of Hat objects to remove
     */
    public void removeHats(List<Hat> hats) {
        removeEntities(hats);
    }

    /**
     * Updates an existing Hat in the database.
     *
     * @param hat The Hat object to update
     */
    public void updateHat(Hat hat) {
        updateEntity(hat);
    }

    /**
     * Gets a paginated list of Hat objects.
     *
     * @param pageSize The number of items per page
     * @param lastID   The ID of the last item from the previous page, or null for the first page
     * @param fromEnd  Whether to sort in fromEnd order
     * @return A list of Hat objects for the requested page
     */
    public List<Hat> getPaginatedHats(int pageSize, String lastID, boolean fromEnd) {
        return getPaginatedEntities(pageSize, lastID, fromEnd);
    }

    /**
     * Searches for Hat objects by name, description, types, aids, pids, and DID fields.
     * Excludes cipher HATs (those with rawDid != null) from results.
     *
     * @param searchQuery The search query string
     * @return A list of Hat objects that match the search criteria (excluding cipher HATs)
     */
    public List<Hat> searchHats(String searchQuery) {
        List<Hat> results = searchEntities(searchQuery);
        return filterOutCipherHats(results);
    }

    /**
     * Gets all Hat objects with a specific data state.
     *
     * @param state The data state to filter by
     * @return A list of Hat objects with the specified state
     */
    public List<Hat> getHatsByState(Hat.DataState state) {
        List<Hat> allHats = getPaginatedHats(Integer.MAX_VALUE, null, false);
        List<Hat> filteredHats = new ArrayList<>();

        for (Hat hat : allHats) {
            if (hat.getState() == state) {
                filteredHats.add(hat);
            }
        }

        return filteredHats;
    }

    /**
     * Gets all Hat objects for a specific source DID (first version).
     *
     * @param srcDid The source DID
     * @return A list of Hat objects with the specified source DID
     */
    public List<Hat> getHatsBySrcDid(String srcDid) {
        List<Hat> allHats = searchEntities(srcDid);
        List<Hat> filteredHats = new ArrayList<>();

        if (allHats != null) {
            for (Hat hat : allHats) {
                if (srcDid.equals(hat.getSrcDid())) {
                    filteredHats.add(hat);
                }
            }
        }

        return filteredHats;
    }

    /**
     * Gets HATs sorted by last access time in descending order.
     * This is the primary display order for DataActivity.
     * Excludes cipher HATs (those with rawDid != null) from results.
     *
     * @param pageSize The number of items per page
     * @param afterId  The ID after which to start (for pagination), or null for the first page
     * @return A list of Hat objects sorted by last descending (excluding cipher HATs)
     */
    public List<Hat> getHatsSortedByLastDesc(int pageSize, String afterId) {
        // Get all hats and filter out cipher HATs
        List<Hat> allHats = filterOutCipherHats(
                getPaginatedHats(Integer.MAX_VALUE, null, false));
        
        // Sort by last descending (most recent first)
        allHats.sort((h1, h2) -> {
            Long last1 = h1.getLast();
            Long last2 = h2.getLast();
            if (last1 == null && last2 == null) return 0;
            if (last1 == null) return 1;
            if (last2 == null) return -1;
            return last2.compareTo(last1); // Descending
        });

        // Apply pagination
        if (afterId != null) {
            int startIndex = -1;
            for (int i = 0; i < allHats.size(); i++) {
                if (afterId.equals(allHats.get(i).getId())) {
                    startIndex = i + 1;
                    break;
                }
            }
            if (startIndex > 0 && startIndex < allHats.size()) {
                allHats = allHats.subList(startIndex, allHats.size());
            } else if (startIndex >= allHats.size()) {
                return new ArrayList<>();
            }
        }

        // Limit to page size
        if (allHats.size() > pageSize) {
            return new ArrayList<>(allHats.subList(0, pageSize));
        }
        return allHats;
    }

    /**
     * Gets HATs that have been modified since a given timestamp.
     * Used for incremental backup.
     *
     * @param timestamp The timestamp to compare against
     * @return A list of Hat objects modified since the timestamp
     */
    public List<Hat> getHatsModifiedSince(long timestamp) {
        List<Hat> allHats = getPaginatedHats(Integer.MAX_VALUE, null, false);
        List<Hat> modifiedHats = new ArrayList<>();

        for (Hat hat : allHats) {
            Long last = hat.getLast();
            if (last != null && last > timestamp) {
                modifiedHats.add(hat);
            }
        }

        return modifiedHats;
    }

    /**
     * Gets HATs by location prefix.
     * Used for finding HATs stored on a specific DISK service.
     *
     * @param locationPrefix The location prefix to match (e.g., "disk://serviceId")
     * @return A list of Hat objects with matching locations
     */
    public List<Hat> getHatsByLocation(String locationPrefix) {
        List<Hat> allHats = getPaginatedHats(Integer.MAX_VALUE, null, false);
        List<Hat> matchingHats = new ArrayList<>();

        if (locationPrefix == null || locationPrefix.isEmpty()) {
            return matchingHats;
        }

        for (Hat hat : allHats) {
            List<String> locas = hat.getLocas();
            if (locas != null) {
                for (String loca : locas) {
                    if (loca != null && loca.startsWith(locationPrefix)) {
                        matchingHats.add(hat);
                        break;
                    }
                }
            }
        }

        return matchingHats;
    }

    /**
     * Updates the locations list for a HAT.
     *
     * @param hatId     The ID of the HAT to update
     * @param locations The new locations list
     */
    public void updateHatLocations(String hatId, List<String> locations) {
        Hat hat = getHatById(hatId);
        if (hat != null) {
            hat.setLocas(locations);
            hat.setLast(System.currentTimeMillis());
            updateHat(hat);
        }
    }

    /**
     * Adds a location to a HAT's locations list.
     *
     * @param hatId    The ID of the HAT to update
     * @param location The location to add
     */
    public void addHatLocation(String hatId, String location) {
        Hat hat = getHatById(hatId);
        if (hat != null) {
            List<String> locas = hat.getLocas();
            if (locas == null) {
                locas = new ArrayList<>();
            }
            if (!locas.contains(location)) {
                locas.add(location);
                hat.setLocas(locas);
                hat.setLast(System.currentTimeMillis());
                updateHat(hat);
            }
        }
    }

    /**
     * Gets the database size in number of HATs.
     *
     * @return The number of HATs in the database
     */
    public long getHatDBSize() {
        return getEntityDBSize();
    }

    // ========================================
    // Sequential Processing for Import
    // ========================================

    /**
     * Processes Hat objects sequentially with user confirmation for duplicates.
     *
     * @param activity   The activity context
     * @param hatList    List of hats to process
     * @param index      Current index being processed
     * @param savedCount Number of hats successfully saved so far
     */
    public void processHatsSequentially(Activity activity, List<Hat> hatList, int index, int savedCount) {
        if (index >= hatList.size()) {
            commit();
            ToastUtils.makeText(activity, activity.getString(R.string.entity_saved_successfully, String.valueOf(savedCount)));
            activity.setResult(Activity.RESULT_OK);
            activity.finish();
            return;
        }

        Hat hat = hatList.get(index);

        if (hat.getId() == null) {
            hat.checkIdWithCreate();
        }

        if (checkIfExisted(hat.getId())) {
            String prompt = hat.getId() + " existed. Replace it?";
            UserConfirmDialog dialog = new UserConfirmDialog(activity, "Replace Item", prompt, choice -> {
                if (choice == UserConfirmDialog.Choice.YES) {
                    addEntity(hat);
                    processHatsSequentially(activity, hatList, index + 1, savedCount + 1);
                } else if (choice == UserConfirmDialog.Choice.NO) {
                    processHatsSequentially(activity, hatList, index + 1, savedCount);
                } else if (choice == UserConfirmDialog.Choice.STOP) {
                    commit();
                    ToastUtils.makeText(activity, activity.getString(R.string.entity_saved_successfully, String.valueOf(savedCount)));
                    activity.setResult(Activity.RESULT_OK);
                    activity.finish();
                }
            });
            dialog.show();
        } else {
            addEntity(hat);
            processHatsSequentially(activity, hatList, index + 1, savedCount + 1);
        }
    }

    // ========================================
    // LocalEntityManager Abstract Method Implementations
    // ========================================

    @Override
    protected void preprocessEntity(Hat entity) {
        if (entity != null) {
            entity.checkIdWithCreate();

            // Set born timestamp if not set
            if (entity.getBorn() == null) {
                entity.setBorn(System.currentTimeMillis());
            }

            // Update last used timestamp
            entity.setLast(System.currentTimeMillis());
        }
    }

    @Override
    protected boolean isEntityDeleted(Hat entity) {
        return entity != null && entity.getState() == Hat.DataState.DELETED;
    }

    // ========================================
    // Cipher HAT Helper Methods
    // ========================================

    /**
     * Filters out cipher HATs from a list.
     * Cipher HATs are identified by having a non-null rawDid field.
     *
     * @param hats The list of hats to filter
     * @return A new list containing only raw data HATs (rawDid == null)
     */
    private List<Hat> filterOutCipherHats(List<Hat> hats) {
        if (hats == null) return new ArrayList<>();
        List<Hat> filtered = new ArrayList<>();
        for (Hat hat : hats) {
            if (hat.getRawDid() == null) {
                filtered.add(hat);
            }
        }
        return filtered;
    }

    /**
     * Adds a cipher DID to a raw HAT's cipherIds list.
     *
     * @param rawHatId The ID of the raw HAT
     * @param cipherId The cipher DID to add
     */
    public void addCipherId(String rawHatId, String cipherId) {
        Hat rawHat = getHatById(rawHatId);
        if (rawHat == null) {
            TimberLogger.w(TAG, "Cannot add cipherId: raw HAT not found: " + rawHatId);
            return;
        }
        List<String> cipherIds = rawHat.getCipherIds();
        if (cipherIds == null) {
            cipherIds = new ArrayList<>();
        }
        if (!cipherIds.contains(cipherId)) {
            cipherIds.add(cipherId);
            rawHat.setCipherIds(cipherIds);
            rawHat.setLast(System.currentTimeMillis());
            updateHat(rawHat);
        }
    }

    /**
     * Creates and saves a cipher HAT for an encrypted file.
     * The cipher HAT's ID is the DID of the cipher file,
     * and it references the raw data's DID via the rawDid field.
     *
     * @param cipherId The DID of the cipher file (used as the HAT ID)
     * @param rawDid   The DID of the raw (unencrypted) data
     * @param kCipher  The encrypted symmetric key (JSON string)
     * @param size     The size of the cipher file in bytes
     * @return The created cipher Hat object
     */
    public Hat createCipherHat(String cipherId, String rawDid, String kCipher, long size) {
        Hat cipherHat = new Hat();
        cipherHat.setId(cipherId);
        cipherHat.setRawDid(rawDid);
        cipherHat.setkCipher(kCipher);
        cipherHat.setSize(size);
        cipherHat.setBorn(System.currentTimeMillis());
        cipherHat.setLast(System.currentTimeMillis());
        cipherHat.setState(Hat.DataState.ACTIVE);
        addHat(cipherHat);
        return cipherHat;
    }

    // ========================================
    // LocalEntityManager Abstract Method Implementations
    // ========================================

    @Override
    protected List<Hat> searchFromList(String query, List<Hat> results) {
        if (query == null || query.trim().isEmpty() || results == null) {
            return new ArrayList<>();
        }

        List<Hat> matchingHats = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (Hat hat : results) {
            boolean matches = false;

            // Search in ID
            if (hat.getId() != null && hat.getId().toLowerCase().contains(lowerQuery)) {
                matches = true;
            }

            // Search in name
            if (!matches && hat.getName() != null && hat.getName().toLowerCase().contains(lowerQuery)) {
                matches = true;
            }

            // Search in description
            if (!matches && hat.getDesc() != null && hat.getDesc().toLowerCase().contains(lowerQuery)) {
                matches = true;
            }

            // Search in types
            if (!matches && hat.getTypes() != null) {
                for (String type : hat.getTypes()) {
                    if (type != null && type.toLowerCase().contains(lowerQuery)) {
                        matches = true;
                        break;
                    }
                }
            }

            // Search in AIDs
            if (!matches && hat.getAids() != null) {
                for (String aid : hat.getAids()) {
                    if (aid != null && aid.toLowerCase().contains(lowerQuery)) {
                        matches = true;
                        break;
                    }
                }
            }

            // Search in PIDs
            if (!matches && hat.getPids() != null) {
                for (String pid : hat.getPids()) {
                    if (pid != null && pid.toLowerCase().contains(lowerQuery)) {
                        matches = true;
                        break;
                    }
                }
            }

            // Search in DID fields
            if (!matches && hat.getSrcDid() != null && hat.getSrcDid().toLowerCase().contains(lowerQuery)) {
                matches = true;
            }

            if (!matches && hat.getPreDid() != null && hat.getPreDid().toLowerCase().contains(lowerQuery)) {
                matches = true;
            }

            if (!matches && hat.gettDid() != null && hat.gettDid().toLowerCase().contains(lowerQuery)) {
                matches = true;
            }

            if (!matches && hat.getRawDid() != null && hat.getRawDid().toLowerCase().contains(lowerQuery)) {
                matches = true;
            }

            // Search in locations
            if (!matches && hat.getLocas() != null) {
                for (String loca : hat.getLocas()) {
                    if (loca != null && loca.toLowerCase().contains(lowerQuery)) {
                        matches = true;
                        break;
                    }
                }
            }

            if (matches) {
                matchingHats.add(hat);
            }
        }

        return matchingHats;
    }
}
