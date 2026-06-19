package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.CLOSED;
import static com.fc.fc_ajdk.constants.FieldNames.DESC;
import static com.fc.fc_ajdk.constants.FieldNames.DID;
import static com.fc.fc_ajdk.constants.FieldNames.HOME;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LANG;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.NAME;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.PRE_PID;
import static com.fc.fc_ajdk.constants.FieldNames.SN;
import static com.fc.fc_ajdk.constants.FieldNames.T_CDD;
import static com.fc.fc_ajdk.constants.FieldNames.T_RATE;
import static com.fc.fc_ajdk.constants.FieldNames.TYPE;
import static com.fc.fc_ajdk.constants.FieldNames.VER;
import static com.fc.fc_ajdk.constants.FieldNames.WAITERS;

import com.fc.fc_ajdk.data.fcData.FcObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Protocol extends FcObject {

	private String type;
	private String sn;
	private String ver;
	private String did;
	private String name;
	private String lang;
	private String desc;
	private String prePid;
	private Map<String, String> home;
	private String title;
	private String owner;
	private List<String> waiters;
	private String birthTxId;
	private Long birthTime;
	private Long birthHeight;
	private String lastTxId;
	private Long lastTime;
	private Long lastHeight;
	private Long tCdd;
	private Float tRate;
	private Boolean active;
	private Boolean closed;
	private String closeStatement;
	private Boolean onChain;

	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public String getSn() {
		return sn;
	}
	public void setSn(String sn) {
		this.sn = sn;
	}
	public String getVer() {
		return ver;
	}
	public void setVer(String ver) {
		this.ver = ver;
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getLang() {
		return lang;
	}
	public void setLang(String lang) {
		this.lang = lang;
	}
	public String getDesc() {
		return desc;
	}
	public void setDesc(String desc) {
		this.desc = desc;
	}
	public String getPrePid() {
		return prePid;
	}
	public void setPrePid(String prePid) {
		this.prePid = prePid;
	}
	public Map<String, String> getHome() {
		return home;
	}
	public void setHome(Map<String, String> home) {
		this.home = home;
	}
	public String getTitle() {
		return title;
	}
	public void setTitle(String title) {
		this.title = title;
	}
	public String getOwner() {
		return owner;
	}
	public void setOwner(String owner) {
		this.owner = owner;
	}
	public String getBirthTxId() {
		return birthTxId;
	}
	public void setBirthTxId(String birthTxId) {
		this.birthTxId = birthTxId;
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
	public Long gettCdd() {
		return tCdd;
	}
	public void settCdd(Long tCdd) {
		this.tCdd = tCdd;
	}
	public Float gettRate() {
		return tRate;
	}
	public void settRate(Float tRate) {
		this.tRate = tRate;
	}
	public Boolean isActive() {
		return active;
	}
	public void setActive(Boolean active) {
		this.active = active;
	}
	public String getDid() {
		return did;
	}
	public void setDid(String did) {
		this.did = did;
	}
	public Boolean isClosed() {
		return closed;
	}
	public void setClosed(Boolean closed) {
		this.closed = closed;
	}
	public String getCloseStatement() {
		return closeStatement;
	}
	public void setCloseStatement(String closeStatement) {
		this.closeStatement = closeStatement;
	}

	public List<String> getWaiters() {
		return waiters;
	}

	public void setWaiters(List<String> waiters) {
		this.waiters = waiters;
	}

	public Boolean getOnChain() {
		return onChain;
	}

	public void setOnChain(Boolean onChain) {
		this.onChain = onChain;
	}

	/**
	 * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
	 * @return LinkedHashMap with field name as key and language map (en, zh) as value
	 */
	public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
		LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

		addFieldToMap(fieldMap, NAME, "Name", "名称");
		addFieldToMap(fieldMap, TYPE, "Type", "类型");
		addFieldToMap(fieldMap, SN, "Serial Number", "序列号");
		addFieldToMap(fieldMap, VER, "Version", "版本");
		addFieldToMap(fieldMap, DID, "DID", "DID");
		addFieldToMap(fieldMap, LANG, "Language", "语言");
		addFieldToMap(fieldMap, DESC, "Description", "描述");
		addFieldToMap(fieldMap, PRE_PID, "Previous PID", "前序PID");
		addFieldToMap(fieldMap, HOME, "Home", "主页");
		addFieldToMap(fieldMap, WAITERS, "Waiters", "服务员");
		addFieldToMap(fieldMap, OWNER, "Owner", "所有者");
		addFieldToMap(fieldMap, BIRTH_TIME, "Birth Time", "创建时间");
		addFieldToMap(fieldMap, LAST_TIME, "Last Time", "最新时间");
		addFieldToMap(fieldMap, T_CDD, "Total CDD", "总CDD");
		addFieldToMap(fieldMap, T_RATE, "Total Rate", "总评分");
		addFieldToMap(fieldMap, ACTIVE, "Active", "活跃");
		addFieldToMap(fieldMap, CLOSED, "Closed", "已关闭");
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
	 * Returns searchable fields that can be used in search queries.
	 * Searchable fields: name, type, sn, desc, lang, home, waiters, owner
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSearchableFields() {
		return filterFieldsByNames(NAME, TYPE, SN, DESC, LANG, HOME, WAITERS, OWNER);
	}

	/**
	 * Returns sortable fields that can be used for sorting results.
	 * Sortable fields: name, owner, birthTime, lastTime, tCdd, tRate, active, closed
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSortableFields() {
		return filterFieldsByNames(NAME, OWNER, BIRTH_TIME, LAST_TIME, T_CDD, T_RATE, ACTIVE, CLOSED);
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
