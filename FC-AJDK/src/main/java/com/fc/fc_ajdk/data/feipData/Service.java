package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.COMPONENTS;
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
import static com.fc.fc_ajdk.constants.FieldNames.TYPE;
import static com.fc.fc_ajdk.constants.FieldNames.HOME;
import static com.fc.fc_ajdk.constants.FieldNames.WAITERS;
import static com.fc.fc_ajdk.data.feipData.Service.ServiceType.OTHER;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.fc.fc_ajdk.data.fcData.FcObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;

public class Service extends FcObject {
//sid
	protected String stdName;
	protected Map<String, String> localNames;
	protected String desc;
	protected String type;
	protected List<String> components;
	protected String ver;
	protected String dealer;
	protected String dealerPubkey;
	protected Map<String,String> home;
	protected List<String> waiters;
	protected List<String> protocols;
	protected List<String> codes;
	protected List<String> services;

	// Pricing and service configuration fields (moved from Params)
	protected String pricePerKB;
	protected String pricePerKBIn;   // Price for incoming data (requests) - FCH per KB
	protected String pricePerKBOut;  // Price for outgoing data (responses) - FCH per KB
	protected String pricePerKBDay;  // Price for storage - FCH per KB per day
	protected String minPayment;
	protected String pricePerRequest;
	protected String sessionDays;
	protected String consumeViaShare;
	protected String orderViaShare;
	protected String currency;
	protected String minCredit;
	protected String maxDataSize;
	protected String dataExpiresInDays;

	private Object params;
	protected String owner;
	
	protected Long birthTime;
	protected Long birthHeight;
	protected String lastTxId;
	protected Long lastTime;
	protected Long lastHeight;
	protected Long tCdd;
	protected Float tRate;
	protected Boolean active;
	protected Boolean closed;
	protected String closeStatement;
	protected Boolean onChain;

	public static final String FAPI_NO1_NRC7 = "FAPI@No1_NrC7";

	public String toJson() {
		Gson gson = new Gson();
		return gson.toJson(this);
	}
	public String toNiceJson() {
		Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
		return gson.toJson(this);
	}



	public String getDesc() {
		return desc;
	}
	public void setDesc(String desc) {
		this.desc = desc;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public ServiceType fetchServiceType() {
		if (type == null) return OTHER;
		try {
			// Replace the first '@' with '_'
			if (type.contains("@")) {
				String tmpType = type.replaceFirst("@", "_");
				return ServiceType.valueOf(tmpType);
			}
			return ServiceType.valueOf(type);
		} catch (IllegalArgumentException e) {
			return OTHER;
		}
	}

	public void makeServiceType(ServiceType type) {
		if (type != null) {
			String typeStr = type.name();
			int underscoreIndex = typeStr.indexOf('_');
			if (underscoreIndex >= 0) {
				typeStr = typeStr.substring(0, underscoreIndex) + "@" + typeStr.substring(underscoreIndex + 1);
			}
			this.type = typeStr;
			return;
		}
		this.type = null;
	}

	public List<String> getComponents() {
		return components;
	}

	public void setComponents(List<String> components) {
		this.components = components;
	}

	public Map<String,String> getHome() {
		return home;
	}

	public void setHome(Map<String, String> home) {
		this.home = home;
	}

	public String getOwner() {
		return owner;
	}
	public void setOwner(String signer) {
		this.owner = signer;
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

	public List<String> getCodes() {
		return codes;
	}

	public void setCodes(List<String> codes) {
		this.codes = codes;
	}

	public List<String> getServices() {
		return services;
	}

	public void setServices(List<String> services) {
		this.services = services;
	}

	public Object getParams() {
		return params;
	}

	public void setParams(Object params) {
		this.params = params;
	}

	public void setActive(Boolean active) {
		this.active = active;
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

	public String getVer() {
		return ver;
	}

	public void setVer(String ver) {
		this.ver = ver;
	}

	public Boolean getActive() {
		return active;
	}

	public Boolean getClosed() {
		return closed;
	}

	public Boolean getOnChain() {
		return onChain;
	}

	public void setOnChain(Boolean onChain) {
		this.onChain = onChain;
	}

    public enum ServiceType {

        NASA_RPC,
		FAPI_No1_NrC7,
        NODE,
        OTHER;

		@NonNull
		@Override
		public String toString() {
			String typeStr = this.name();
			int underscoreIndex = typeStr.indexOf('_');
			if (underscoreIndex >= 0) {
				typeStr = typeStr.substring(0, underscoreIndex) + "@" + typeStr.substring(underscoreIndex + 1);
			}
			return typeStr;
		}

		// New method to check if a string matches a ServiceType
		public static ServiceType fromString(String input) {
			if (input == null) {
				return null;
			}
			if (input.contains("@")) {
				input = input.replaceFirst("@", "_");
			}
			for (ServiceType type : ServiceType.values()) {
				if (type.name().equalsIgnoreCase(input)) {
					return type;
				}
			}
			return null;
		}
    }

	public String getDealer() {
		return dealer;
	}

	public void setDealer(String dealer) {
		this.dealer = dealer;
	}

	public String getDealerPubkey() {
		return dealerPubkey;
	}

	public void setDealerPubkey(String dealerPubkey) {
		this.dealerPubkey = dealerPubkey;
	}

	public String getPricePerKB() {
		return pricePerKB;
	}

	public void setPricePerKB(String pricePerKB) {
		this.pricePerKB = pricePerKB;
	}

	public String getMinPayment() {
		return minPayment;
	}

	public void setMinPayment(String minPayment) {
		this.minPayment = minPayment;
	}

	public String getPricePerRequest() {
		return pricePerRequest;
	}

	public void setPricePerRequest(String pricePerRequest) {
		this.pricePerRequest = pricePerRequest;
	}

	public String getSessionDays() {
		return sessionDays;
	}

	public void setSessionDays(String sessionDays) {
		this.sessionDays = sessionDays;
	}

	public String getConsumeViaShare() {
		return consumeViaShare;
	}

	public void setConsumeViaShare(String consumeViaShare) {
		this.consumeViaShare = consumeViaShare;
	}

	public String getOrderViaShare() {
		return orderViaShare;
	}

	public void setOrderViaShare(String orderViaShare) {
		this.orderViaShare = orderViaShare;
	}

	public String getCurrency() {
		return currency;
	}

	public String getMinCredit() {
		return minCredit;
	}

	public void setMinCredit(String minCredit) {
		this.minCredit = minCredit;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	/**
	 * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
	 * @return LinkedHashMap with field name as key and language map (en, zh) as value
	 */
	public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
		LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

		addFieldToMap(fieldMap, STD_NAME, "Standard Name", "标准名称");
		addFieldToMap(fieldMap, LOCAL_NAMES, "Local Names", "本地名称");
		addFieldToMap(fieldMap, TYPE, "Type", "类型");
		addFieldToMap(fieldMap, DESC, "Description", "描述");
		addFieldToMap(fieldMap, COMPONENTS, "Components", "组件");
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
	 * Returns searchable fields that can be used in search queries.
	 * Searchable fields: stdName, localNames, type, desc, apiGroups, urls, waiters, protocols, codes, services, owner
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSearchableFields() {
		return filterFieldsByNames(STD_NAME, LOCAL_NAMES, TYPE, DESC, COMPONENTS, HOME, WAITERS, PROTOCOLS, CODES, SERVICES, OWNER);
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

	public String getPricePerKBIn() {
		return pricePerKBIn;
	}

	public void setPricePerKBIn(String pricePerKBIn) {
		this.pricePerKBIn = pricePerKBIn;
	}

	public String getPricePerKBOut() {
		return pricePerKBOut;
	}

	public void setPricePerKBOut(String pricePerKBOut) {
		this.pricePerKBOut = pricePerKBOut;
	}

	public String getPricePerKBDay() {
		return pricePerKBDay;
	}

	public void setPricePerKBDay(String pricePerKBDay) {
		this.pricePerKBDay = pricePerKBDay;
	}

	public String getMaxDataSize() {
		return maxDataSize;
	}

	public void setMaxDataSize(String maxDataSize) {
		this.maxDataSize = maxDataSize;
	}

	public String getDataExpiresInDays() {
		return dataExpiresInDays;
	}

	public void setDataExpiresInDays(String dataExpiresInDays) {
		this.dataExpiresInDays = dataExpiresInDays;
	}
}
