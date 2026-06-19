package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.CLOSED;
import static com.fc.fc_ajdk.constants.FieldNames.CODES;
import static com.fc.fc_ajdk.constants.FieldNames.DESC;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.LOCAL_NAMES;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.PROTOCOLS;
import static com.fc.fc_ajdk.constants.FieldNames.SERVICES;
import static com.fc.fc_ajdk.constants.FieldNames.STD_NAME;
import static com.fc.fc_ajdk.constants.FieldNames.T_CDD;
import static com.fc.fc_ajdk.constants.FieldNames.T_RATE;
import static com.fc.fc_ajdk.constants.FieldNames.TYPES;
import static com.fc.fc_ajdk.constants.FieldNames.HOME;
import static com.fc.fc_ajdk.constants.FieldNames.WAITERS;

import com.fc.fc_ajdk.data.fcData.FcObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class App extends FcObject {
	private String stdName;
	private Map<String, String> localNames;
	private List<String> types;
	private String desc;
	private String ver;
	private Map<String, String> home;
	private List<Download> downloads;
	private List<String> waiters;
	private List<String> protocols;
	private List<String> codes;
	private List<String> services;
	
	private String owner;
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

	public String getStdName() {
		return stdName;
	}
	public void setStdName(String stdName) {
		this.stdName = stdName;
	}
	public Map<String, String> getLocalNames() {
		return localNames;
	}
	public void setLocalNames(Map<String, String> localNames) {
		this.localNames = localNames;
	}
	public List<String> getTypes() {
		return types;
	}
	public void setTypes(List<String> types) {
		this.types = types;
	}
	public String getDesc() {
		return desc;
	}
	public void setDesc(String desc) {
		this.desc = desc;
	}
	public Map<String, String> getHome() {
		return home;
	}
	public void setHome(Map<String, String> home) {
		this.home = home;
	}
	public List<String> getWaiters() {
		return waiters;
	}
	public void setWaiters(List<String> waiters) {
		this.waiters = waiters;
	}
	public List<String> getProtocols() {
		return protocols;
	}
	public void setProtocols(List<String> protocols) {
		this.protocols = protocols;
	}
	public List<String> getServices() {
		return services;
	}
	public void setServices(List<String> services) {
		this.services = services;
	}
	public String getOwner() {
		return owner;
	}
	public void setOwner(String owner) {
		this.owner = owner;
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
	public List<String> getCodes() {
		return codes;
	}
	public void setCodes(List<String> codes) {
		this.codes = codes;
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
	public Boolean getOnChain() {
		return onChain;
	}
	public void setOnChain(Boolean onChain) {
		this.onChain = onChain;
	}
	public List<Download> getDownloads() {
		return downloads;
	}
	public void setDownloads(List<Download> downloads) {
		this.downloads = downloads;
	}

	public String getVer() {
		return ver;
	}

	public void setVer(String ver) {
		this.ver = ver;
	}

	/**
	 * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
	 * @return LinkedHashMap with field name as key and language map (en, zh) as value
	 */
	public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
		LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

		addFieldToMap(fieldMap, STD_NAME, "Standard Name", "标准名称");
		addFieldToMap(fieldMap, LOCAL_NAMES, "Local Names", "本地名称");
		addFieldToMap(fieldMap, TYPES, "Types", "类型");
		addFieldToMap(fieldMap, DESC, "Description", "描述");
		addFieldToMap(fieldMap, HOME, "Home", "主页");
		addFieldToMap(fieldMap, WAITERS, "Waiters", "服务员");
		addFieldToMap(fieldMap, PROTOCOLS, "Protocols", "协议");
		addFieldToMap(fieldMap, CODES, "Codes", "代码");
		addFieldToMap(fieldMap, SERVICES, "Services", "服务");
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
	 * Searchable fields: stdName, localNames, types, desc, urls, waiters, protocols, codes, services, owner
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSearchableFields() {
		return filterFieldsByNames(STD_NAME, LOCAL_NAMES, TYPES, DESC, HOME, WAITERS, PROTOCOLS, CODES, SERVICES, OWNER);
	}

	/**
	 * Returns sortable fields that can be used for sorting results.
	 * Sortable fields: stdName, owner, birthTime, lastTime, tCdd, tRate, active, closed
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSortableFields() {
		return filterFieldsByNames(STD_NAME, OWNER, BIRTH_TIME, LAST_TIME, T_CDD, T_RATE, ACTIVE, CLOSED);
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

	public static class Download {

		private String os;
		private String link;
		private String did;

		
		public String getOs() {
			return os;
		}
		public void setOs(String os) {
			this.os = os;
		}
		public String getLink() {
			return link;
		}
		public void setLink(String link) {
			this.link = link;
		}

		public String getDid() {
			return did;
		}

		public void setDid(String did) {
			this.did = did;
		}

	}

}
