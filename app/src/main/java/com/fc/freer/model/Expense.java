package com.fc.freer.model;


import static com.fc.fc_ajdk.constants.FieldNames.CASH_ID;
import static com.fc.fc_ajdk.constants.FieldNames.HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.ISSUER;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.TIME;
import static com.fc.fc_ajdk.constants.FieldNames.TO;
import static com.fc.fc_ajdk.constants.FieldNames.VALUE;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.data.fchData.Cash;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Expense extends FcObject {
    private String to;
    private Long value;
    private Long time;
    private Long height;
    private Boolean isNew;
    // Constructor and getters/setters
    public Expense(String id, String to, Long value, Long time, Long height) {
        this.id = id;
        this.to = to;
        this.value = value;
        this.time = time;
        this.height = height;
    }
    public static LinkedHashMap<String,Integer> getFieldWidthMap(){
        LinkedHashMap<String,Integer> map = new LinkedHashMap<>();
        map.put(ID, FcEntity.ID_DEFAULT_SHOW_SIZE);
        map.put(TO,FcEntity.ID_DEFAULT_SHOW_SIZE);
        map.put(VALUE,FcEntity.AMOUNT_DEFAULT_SHOW_SIZE);
        map.put(TIME,FcEntity.TIME_DEFAULT_SHOW_SIZE);
        map.put(HEIGHT,FcEntity.AMOUNT_DEFAULT_SHOW_SIZE);
        return map;
    }

    public static Expense fromCash(Cash cash) {
        if (cash == null) return null;

        return new Expense(
                cash.getId(),
                cash.getOwner(),
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
        
        // Add fields in their declaration order from source code (lines 23-26)
        addFieldToMap(fieldMap, "id", "ID", "ID");
        addFieldToMap(fieldMap, "to", "To", "去向");
        addFieldToMap(fieldMap, "value", "Value", "金额");
        addFieldToMap(fieldMap, "time", "Time", "时间");
        addFieldToMap(fieldMap, "height", "Height", "高度");
        
        return fieldMap;
    }

    // Getters and setters

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
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
