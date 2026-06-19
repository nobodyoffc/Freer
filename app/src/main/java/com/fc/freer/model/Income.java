package com.fc.freer.model;

import static com.fc.fc_ajdk.constants.FieldNames.CASH_ID;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.ISSUER;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.TIME;
import static com.fc.fc_ajdk.constants.FieldNames.VALUE;

import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.data.fchData.Cash;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Income extends FcObject {
    private String from;
    private Long value;
    private Long time;
    private Long height;

    private Boolean isNew;



    // Constructor and getters/setters
    public Income(String id, String from, Long value, Long time, Long height) {
        this.id= id;
        this.from = from;
        this.value = value;
        this.time = time;
        this.height = height;
    }

    public static Income fromCash(Cash cash) {
        if (cash == null) return null;
        
        return new Income(
            cash.getId(),
            cash.getIssuer(),
            cash.getValue(),
            cash.getBirthTime(),
            cash.getBirthHeight()
        );
    }

    public static List<String> getTimestampFieldList(){
        return List.of(TIME);
    }

    public static List<String> getSatoshiFieldList(){
        return List.of(VALUE);
    }
    public static Map<String, String> getHeightToTimeFieldMap() {
        return  new HashMap<>();
    }

    public static Map<String, String> getShowFieldNameAsMap() {
        Map<String,String> map = new HashMap<>();
        map.put(ID,CASH_ID);
        return map;
    }

    public static List<String> getReplaceWithMeFieldList() {
        return List.of(OWNER,ISSUER);
    }

    public static Map<String, Object> getInputFieldDefaultValueMap() {
        return new HashMap<>();
    }

    /**
     * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
     * @return LinkedHashMap<fieldName, Map<language, displayName>>
     */
    public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
        LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();
        
        // Add fields in their declaration order from source code (lines 18-21)
        addFieldToMap(fieldMap, "id", "ID", "ID");
        addFieldToMap(fieldMap, "from", "From", "来源");
        addFieldToMap(fieldMap, "value", "Value", "金额");
        addFieldToMap(fieldMap, "time", "Time", "时间");
        addFieldToMap(fieldMap, "height", "Height", "高度");
        
        return fieldMap;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public Long getValue() {
        return value;
    }

    public void setValue(Long value) {
        this.value = value;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public Long getHeight() {
        return height;
    }

    public void setHeight(Long height) {
        this.height = height;
    }

    public Boolean getNew() {
        return isNew;
    }

    public void setNew(Boolean aNew) {
        isNew = aNew;
    }
}
