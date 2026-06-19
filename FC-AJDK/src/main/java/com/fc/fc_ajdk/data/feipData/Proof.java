package com.fc.fc_ajdk.data.feipData;

import static com.fc.fc_ajdk.constants.FieldNames.ACTIVE;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.BIRTH_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.CONTENT;
import static com.fc.fc_ajdk.constants.FieldNames.COSIGNERS_INVITED;
import static com.fc.fc_ajdk.constants.FieldNames.DESTROYED;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.ISSUER;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TIME;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_TX_ID;
import static com.fc.fc_ajdk.constants.FieldNames.ON_CHAIN;
import static com.fc.fc_ajdk.constants.FieldNames.OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.TITLE;
import static com.fc.fc_ajdk.constants.FieldNames.TRANSFERABLE;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.Hex;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Proof extends FcObject {

	private String title;
	private String content;
	private List<String> cosignersInvited;
	private List<String> cosignersSigned;
	private Boolean transferable;
	private Boolean active;
	private Boolean destroyed;
	
	private String issuer;
	private String owner;

	private Long birthTime;
	private Long birthHeight;
	private String lastTxId;
	private Long lastTime;
	private Long lastHeight;
	private Boolean onChain;

	public String checkIdWithCreate(){
		if(this.id==null || this.id.isEmpty())
			id = Hex.toHex(Hash.sha256x2(this.toBytes()));
		return id;
	}

	/**
	 * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
	 * @return LinkedHashMap<fieldName, Map<language, displayName>>
	 */
	public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
		LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

		// Add Proof fields using constants
		addFieldToMap(fieldMap, TITLE, "Title", "标题");
		addFieldToMap(fieldMap, CONTENT, "Content", "内容");
		addFieldToMap(fieldMap, COSIGNERS_INVITED, "Cosigners Invited", "邀请共签人");
		addFieldToMap(fieldMap, "cosignersSigned", "Cosigners Signed", "已签共签人");
		addFieldToMap(fieldMap, TRANSFERABLE, "Transferable", "可转移");
		addFieldToMap(fieldMap, ACTIVE, "Active", "有效");
		addFieldToMap(fieldMap, DESTROYED, "Destroyed", "已销毁");
		addFieldToMap(fieldMap, ISSUER, "Issuer", "发行人");
		addFieldToMap(fieldMap, OWNER, "Owner", "所有人");
		addFieldToMap(fieldMap, BIRTH_TIME, "Birth Time", "创建时间");
		addFieldToMap(fieldMap, BIRTH_HEIGHT, "Birth Height", "创建高度");
		addFieldToMap(fieldMap, LAST_TX_ID, "Last Tx ID", "最新交易ID");
		addFieldToMap(fieldMap, LAST_TIME, "Last Time", "最新时间");
		addFieldToMap(fieldMap, LAST_HEIGHT, "Last Height", "最新高度");
		addFieldToMap(fieldMap, ON_CHAIN, "On Chain", "链上");
		addFieldToMap(fieldMap, ID, "ID", "ID");

		return fieldMap;
	}

	public static void addFieldToMap(LinkedHashMap<String, Map<String, String>> fieldMap, String fieldName, String englishName, String chineseName) {
		Map<String, String> languageMap = new HashMap<>();
		languageMap.put("en", englishName);
		languageMap.put("zh", chineseName);
		fieldMap.put(fieldName, languageMap);
	}

	public Boolean isDestroyed() {
		return destroyed;
	}

	public void setDestroyed(Boolean destroyed) {
		this.destroyed = destroyed;
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

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public List<String> getCosignersInvited() {
		return cosignersInvited;
	}

	public void setCosignersInvited(List<String> cosignersInvited) {
		this.cosignersInvited = cosignersInvited;
	}

	public List<String> getCosignersSigned() {
		return cosignersSigned;
	}

	public void setCosignersSigned(List<String> cosignersSigned) {
		this.cosignersSigned = cosignersSigned;
	}

	public Boolean isTransferable() {
		return transferable;
	}

	public void setTransferable(Boolean transferable) {
		this.transferable = transferable;
	}

	public Boolean isActive() {
		return active;
	}

	public void setActive(Boolean active) {
		this.active = active;
	}

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
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

	public Long getLastHeight() {
		return lastHeight;
	}

	public void setLastHeight(Long lastHeight) {
		this.lastHeight = lastHeight;
	}

	public Boolean getOnChain() {
		return onChain;
	}

	public void setOnChain(Boolean onChain) {
		this.onChain = onChain;
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
	 * Searchable fields: issuer, owner, title, content, cosignersInvited, id
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSearchableFields() {
		return filterFieldsByNames(ISSUER, OWNER, TITLE, CONTENT, COSIGNERS_INVITED, ID);
	}

	/**
	 * Returns sortable fields that can be used for sorting results.
	 * Sortable fields: issuer, owner, title, lastTime
	 *
	 * @return LinkedHashMap with field names and their display names in both languages
	 */
	public static LinkedHashMap<String, Map<String, String>> getSortableFields() {
		return filterFieldsByNames(ISSUER, OWNER, TITLE, LAST_TIME);
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
