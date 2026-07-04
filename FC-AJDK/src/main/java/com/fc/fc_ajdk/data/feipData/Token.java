package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.CAPACITY;
import static com.fc.fc_ajdk.constants.FieldNames.CLOSABLE;
import static com.fc.fc_ajdk.constants.FieldNames.CLOSED;
import static com.fc.fc_ajdk.constants.FieldNames.CONSENSUS_ID;
import static com.fc.fc_ajdk.constants.FieldNames.DEPLOYER;
import static com.fc.fc_ajdk.constants.FieldNames.DESC;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.NAME;
import static com.fc.fc_ajdk.constants.FieldNames.TRANSFERABLE;

import com.fc.fc_ajdk.data.fcData.FcObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class Token extends FcObject {
	private String name;
	private String desc;
	private String consensusId;
	private String capacity;
	private String decimal;
	private Boolean transferable;
	private Boolean closable;
	private Boolean openIssue;
	private String maxAmtPerIssue;
	private String minCddPerIssue;
	private String maxIssuesPerAddr;
	private Boolean closed;
	
	private String deployer;
	private Double circulating;
	private Long birthTime;
	private Long birthHeight;
	private String lastTxId;
	private Long lastTime;
	private Long lastHeight;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}

	public String getConsensusId() {
		return consensusId;
	}

	public void setConsensusId(String consensusId) {
		this.consensusId = consensusId;
	}

	public String getCapacity() {
		return capacity;
	}

	public void setCapacity(String capacity) {
		this.capacity = capacity;
	}

	public String getDecimal() {
		return decimal;
	}

	public void setDecimal(String decimal) {
		this.decimal = decimal;
	}

	public Boolean getTransferable() {
		return transferable;
	}

	public void setTransferable(Boolean transferable) {
		this.transferable = transferable;
	}

	public Boolean getClosable() {
		return closable;
	}

	public void setClosable(Boolean closable) {
		this.closable = closable;
	}

	public Boolean getOpenIssue() {
		return openIssue;
	}

	public void setOpenIssue(Boolean openIssue) {
		this.openIssue = openIssue;
	}

	public String getMaxAmtPerIssue() {
		return maxAmtPerIssue;
	}

	public void setMaxAmtPerIssue(String maxAmtPerIssue) {
		this.maxAmtPerIssue = maxAmtPerIssue;
	}

	public String getMinCddPerIssue() {
		return minCddPerIssue;
	}

	public void setMinCddPerIssue(String minCddPerIssue) {
		this.minCddPerIssue = minCddPerIssue;
	}

	public String getMaxIssuesPerAddr() {
		return maxIssuesPerAddr;
	}

	public void setMaxIssuesPerAddr(String maxIssuesPerAddr) {
		this.maxIssuesPerAddr = maxIssuesPerAddr;
	}

	public Boolean getClosed() {
		return closed;
	}

	public void setClosed(Boolean closed) {
		this.closed = closed;
	}

	public String getDeployer() {
		return deployer;
	}

	public void setDeployer(String deployer) {
		this.deployer = deployer;
	}

	public Double getCirculating() {
		return circulating;
	}

	public void setCirculating(Double circulating) {
		this.circulating = circulating;
	}

	public Long getBirthTime() {
		return birthTime;
	}

	public void setBirthTime(Long birthTime) {
		this.birthTime = birthTime;
	}

	public Long getBirthHeight() {
		return birthHeight;
	}

	public void setBirthHeight(Long birthHeight) {
		this.birthHeight = birthHeight;
	}

	public String getLastTxId() {
		return lastTxId;
	}

	public void setLastTxId(String lastTxId) {
		this.lastTxId = lastTxId;
	}

	public Long getLastTime() {
		return lastTime;
	}

	public void setLastTime(Long lastTime) {
		this.lastTime = lastTime;
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

		addFieldToMap(fieldMap, NAME, "Name", "名称");
		addFieldToMap(fieldMap, DESC, "Description", "描述");
		addFieldToMap(fieldMap, CONSENSUS_ID, "Consensus ID", "共识ID");
		addFieldToMap(fieldMap, CAPACITY, "Capacity", "容量");
		addFieldToMap(fieldMap, TRANSFERABLE, "Transferable", "可转移");
		addFieldToMap(fieldMap, CLOSABLE, "Closable", "可关闭");
		addFieldToMap(fieldMap, CLOSED, "Closed", "已关闭");
		addFieldToMap(fieldMap, DEPLOYER, "Deployer", "部署人");
		addFieldToMap(fieldMap, "circulating", "Circulating", "流通量");
		addFieldToMap(fieldMap, BIRTH_TIME, "Birth Time", "创建时间");
		addFieldToMap(fieldMap, LAST_TIME, "Last Time", "最新时间");
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
	 * Searchable fields: name, desc, consensusId, deployer, id
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSearchableFields() {
		return filterFieldsByNames(NAME, DESC, CONSENSUS_ID, DEPLOYER, ID);
	}

	/**
	 * Returns sortable fields that can be used for sorting results.
	 * Sortable fields: name, deployer, circulating, birthTime, lastTime
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSortableFields() {
		return filterFieldsByNames(NAME, DEPLOYER, "circulating", BIRTH_TIME, LAST_TIME);
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
