package com.fc.fc_ajdk.data.fcData;

import static com.fc.fc_ajdk.constants.FieldNames.ACT;
import static com.fc.fc_ajdk.constants.FieldNames.DOER;
import static com.fc.fc_ajdk.constants.FieldNames.HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.OBJECT_BRIEF;
import static com.fc.fc_ajdk.constants.FieldNames.OBJECT_NAME;
import static com.fc.fc_ajdk.constants.FieldNames.OBJECT_TYPE;
import static com.fc.fc_ajdk.constants.FieldNames.TIME;

import com.fc.fc_ajdk.constants.FieldNames;

import java.util.LinkedHashMap;
import java.util.Map;

public class News extends FcObject{
    private String doer;    //signer
    private String act;     //op
    private String objectType;  //protocol sn of Feip
    private String objectName;  //name, stdName or title
    private String objectBrief; //a brief of the content or desc
    private String objectId;    //id

    private Long height;    //height
    private Long time;      //time
    private Boolean isNew;


    public String getDoer() {
        return doer;
    }

    public void setDoer(String doer) {
        this.doer = doer;
    }

    public String getAct() {
        return act;
    }

    public void setAct(String act) {
        this.act = act;
    }

    public String getObjectType() {
        return objectType;
    }

    public void setObjectType(String objectType) {
        this.objectType = objectType;
    }

    public String getObjectId() {
        return objectId;
    }

    public void setObjectId(String objectId) {
        this.objectId = objectId;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public String getObjectBrief() {
        return objectBrief;
    }

    public void setObjectBrief(String objectBrief) {
        this.objectBrief = objectBrief;
    }

    public Long getHeight() {
        return height;
    }

    public void setHeight(Long height) {
        this.height = height;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public Boolean getNew() {
        return isNew;
    }

    public void setNew(Boolean aNew) {
        isNew = aNew;
    }

    /**
     * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
     * @return LinkedHashMap with field name as key and language map (en, zh) as value
     */
    public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
        LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

        addFieldToMap(fieldMap, DOER, "Doer", "执行者");
        addFieldToMap(fieldMap, ACT, "Action", "操作");
        addFieldToMap(fieldMap, OBJECT_TYPE, "Type", "对象类型");
        addFieldToMap(fieldMap, OBJECT_NAME, "Name", "对象名称");
        addFieldToMap(fieldMap, OBJECT_BRIEF, "Brief", "对象简介");
        addFieldToMap(fieldMap, ID, "ID", "对象ID");
        addFieldToMap(fieldMap, HEIGHT, "Height", "高度");
        addFieldToMap(fieldMap, TIME, "Time", "时间");
        addFieldToMap(fieldMap, FieldNames.IS_NEW, "Is New", "是否新建");

        return fieldMap;
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

    /**
     * Get English display name by field name
     * @param fieldName The field name
     * @return English display name if found, null otherwise
     */
    public static String getEnglishNameByFieldName(String fieldName) {
        return getDisplayNameByFieldName(fieldName, "en");
    }

    /**
     * Get Chinese display name by field name
     * @param fieldName The field name
     * @return Chinese display name if found, null otherwise
     */
    public static String getChineseNameByFieldName(String fieldName) {
        return getDisplayNameByFieldName(fieldName, "zh");
    }

    /**
     * Returns searchable fields that can be used in search queries.
     * These fields are suitable for text matching in the API.
     *
     * @return LinkedHashMap with field names and their display names in both languages
     */
    public static LinkedHashMap<String, Map<String, String>> getSearchableFields() {
        return filterFieldsByNames(DOER, OBJECT_TYPE, ACT, OBJECT_NAME, OBJECT_BRIEF, ID);
    }

    /**
     * Returns sortable fields that can be used for sorting results.
     *
     * @return LinkedHashMap with field names and their display names in both languages
     */
    public static LinkedHashMap<String, Map<String, String>> getSortableFields() {
        return filterFieldsByNames(DOER, OBJECT_TYPE, ACT, TIME, OBJECT_NAME);
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

}
