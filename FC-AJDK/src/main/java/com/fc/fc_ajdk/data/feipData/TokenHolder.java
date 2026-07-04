package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.BALANCE;
import static com.fc.fc_ajdk.constants.FieldNames.FID;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.TOKEN_ID;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.Hex;

import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class TokenHolder extends FcObject {
    private String fid;
    private String tokenId;
    private Double balance;
    private Long firstHeight;
    private Long lastHeight;

    public static String getTokenHolderId(String fid, String tokenId) {
        return Hex.toHex(Hash.sha256((fid + tokenId).getBytes()));
    }

    @Override
    public String getId() {
        if(this.id==null)
            this.id = Hex.toHex(Hash.sha256((fid + tokenId).getBytes()));
        return this.id;
    }

    public String getFid() {
        return fid;
    }

    public void setFid(String fid) {
        this.fid = fid;
    }

    public String getTokenId() {
        return tokenId;
    }

    public void setTokenId(String tokenId) {
        this.tokenId = tokenId;
    }

    public Double getBalance() {
        return balance;
    }

    public void setBalance(Double balance) {
        this.balance = balance;
    }

    public Long getFirstHeight() {
        return firstHeight;
    }

    public void setFirstHeight(Long firstHeight) {
        this.firstHeight = firstHeight;
    }

    public Long getLastHeight() {
        return lastHeight;
    }

    public void setLastHeight(Long lastHeight) {
        this.lastHeight = lastHeight;
    }

    /**
     * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
     * @return LinkedHashMap<fieldName, Map<language, displayName>>
     */
    public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
        LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

        addFieldToMap(fieldMap, FID, "FID", "FID");
        addFieldToMap(fieldMap, TOKEN_ID, "Token ID", "通证ID");
        addFieldToMap(fieldMap, BALANCE, "Balance", "余额");
        addFieldToMap(fieldMap, "firstHeight", "First Height", "首次高度");
        addFieldToMap(fieldMap, LAST_HEIGHT, "Last Height", "最新高度");
        addFieldToMap(fieldMap, ID, "ID", "ID");

        return fieldMap;
    }

    public static void addFieldToMap(LinkedHashMap<String, Map<String, String>> fieldMap, String fieldName, String englishName, String chineseName) {
        Map<String, String> languageMap = new HashMap<>();
        languageMap.put("en", englishName);
        languageMap.put("zh", chineseName);
        fieldMap.put(fieldName, languageMap);
    }

    /**
     * Returns searchable fields that can be used in search queries.
     * Searchable fields: fid, tokenId, id
     *
     * @return LinkedHashMap with field names and their display names in both languages
     */
    public static LinkedHashMap<String, Map<String, String>> getSearchableFields() {
        return filterFieldsByNames(FID, TOKEN_ID, ID);
    }

    /**
     * Returns sortable fields that can be used for sorting results.
     * Sortable fields: balance, firstHeight, lastHeight
     *
     * @return LinkedHashMap with field names and their display names in both languages
     */
    public static LinkedHashMap<String, Map<String, String>> getSortableFields() {
        return filterFieldsByNames(BALANCE, "firstHeight", LAST_HEIGHT);
    }

    /**
     * Helper method to filter fields from getFieldNameMap() by specified field names
     */
    private static LinkedHashMap<String, Map<String, String>> filterFieldsByNames(String... fieldNames) {
        LinkedHashMap<String, Map<String, String>> filteredFields = new LinkedHashMap<>();
        LinkedHashMap<String, Map<String, String>> allFields = getFieldNameMap();

        for (String fieldName : fieldNames) {
            if (allFields.containsKey(fieldName)) {
                filteredFields.put(fieldName, allFields.get(fieldName));
            }
        }

        return filteredFields;
    }

    /**
     * Get field name by display name (searches in all languages)
     * @param displayName The display name to search for (English or Chinese)
     * @return Field name if found, null otherwise
     */
    public static String getFieldNameByDisplayName(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, Map<String, String>> entry : getFieldNameMap().entrySet()) {
            if (entry.getValue().containsValue(displayName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Get display name by field name and language
     * @param fieldName The field name
     * @param language The language code ("en" or "zh")
     * @return Display name if found, null otherwise
     */
    public static String getDisplayNameByFieldName(String fieldName, String language) {
        if (fieldName == null || fieldName.isEmpty() || language == null) {
            return null;
        }

        Map<String, String> languages = getFieldNameMap().get(fieldName);
        return languages != null ? languages.get(language) : null;
    }
}
