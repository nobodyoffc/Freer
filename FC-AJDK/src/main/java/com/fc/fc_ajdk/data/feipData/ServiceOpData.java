package com.fc.fc_ajdk.data.feipData;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.Values;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

public class ServiceOpData {
	private String sid;
	private List<String> sids;
	private String op;
	private String stdName;
	private Map<String, String> localNames;
	private String desc;
	private String ver;
	private String type;
	private List<String> components;
	private Map<String,String> home;
	private List<String> waiters;
	private List<String> protocols;
	private List<String> codes;
	private Object params;
	private Integer rate;
	private String cause;
	private String closeStatement;
	private List<String> services;

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

	public enum Op {
		PUBLISH(FeipOp.PUBLISH),
		UPDATE(FeipOp.UPDATE),
		STOP(FeipOp.STOP),
		CLOSE(FeipOp.CLOSE),
		RECOVER(FeipOp.RECOVER),
		RATE(FeipOp.RATE);

		private final FeipOp feipOp;

		Op(FeipOp feipOp) {
			this.feipOp = feipOp;
		}

		public FeipOp getFeipOp() {
			return feipOp;
		}

		public static Op fromString(String text) {
			for (Op op : Op.values()) {
				if (op.name().equalsIgnoreCase(text)) {
					return op;
				}
			}
			throw new IllegalArgumentException("No constant with text " + text + " found");
		}

		public String toLowerCase() {
			return feipOp.getValue().toLowerCase();
		}
	}

	public static final Map<String, String[]> OP_FIELDS = new HashMap<>();

	static {
		OP_FIELDS.put(Op.PUBLISH.toLowerCase(), new String[]{
			FieldNames.STD_NAME, FieldNames.LOCAL_NAMES, Values.DESC, 
			FieldNames.TYPE, FieldNames.COMPONENTS,
			FieldNames.VER, FieldNames.HOME, FieldNames.WAITERS, 
			FieldNames.PROTOCOLS, FieldNames.CODES, FieldNames.SERVICES, FieldNames.PARAMS
		});
		OP_FIELDS.put(Op.UPDATE.toLowerCase(), new String[]{
			FieldNames.SID, FieldNames.STD_NAME, FieldNames.LOCAL_NAMES, Values.DESC, 
			FieldNames.TYPE, FieldNames.COMPONENTS,
			FieldNames.VER, FieldNames.HOME, FieldNames.WAITERS, 
			FieldNames.PROTOCOLS, FieldNames.CODES, FieldNames.SERVICES, FieldNames.PARAMS
		});
		OP_FIELDS.put(Op.STOP.toLowerCase(), new String[]{FieldNames.SIDS});
		OP_FIELDS.put(Op.CLOSE.toLowerCase(), new String[]{FieldNames.SIDS, FieldNames.CLOSE_STATEMENT});
		OP_FIELDS.put(Op.RECOVER.toLowerCase(), new String[]{FieldNames.SIDS});
		OP_FIELDS.put(Op.RATE.toLowerCase(), new String[]{FieldNames.SID, FieldNames.RATE});
	}


	public String getSid() {
		return sid;
	}

	public void setSid(String sid) {
		this.sid = sid;
	}

	public String getOp() {
		return op;
	}

	public void setOp(String op) {
		this.op = op;
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

	public void setLocalNames(Map<String, String> localNames) {
		this.localNames = localNames;
	}

	public List<String> getComponents() {
		return components;
	}

	public void setComponents(List<String> components) {
		this.components = components;
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

	public List<String> getCodes() {
		return codes;
	}

	public void setCodes(List<String> codes) {
		this.codes = codes;
	}

	public Object getParams() {
		return params;
	}

	public void setParams(Object params) {
		this.params = params;
	}

	public Integer getRate() {
		return rate;
	}

	public void setRate(Integer rate) {
		this.rate = rate;
	}
	public String getCause() {
		return cause;
	}
	public void setCause(String cause) {
		this.cause = cause;
	}

	public String getCloseStatement() {
		return closeStatement;
	}

	public void setCloseStatement(String closeStatement) {
		this.closeStatement = closeStatement;
	}

	public List<String> getServices() {
		return services;
	}

	public void setServices(List<String> services) {
		this.services = services;
	}

	public String getVer() {
		return ver;
	}

	public void setVer(String ver) {
		this.ver = ver;
	}

	public List<String> getSids() {
		return sids;
	}

	public void setSids(List<String> sids) {
		this.sids = sids;
	}

	public static ServiceOpData makePublish(String stdName, Map<String, String> localNames, String desc,
                                            String type, List<String> components,
                                            String ver, Map<String,String> home, List<String> waiters, List<String> protocols,
											List<String> codes, List<String> services, Object params) {
		ServiceOpData data = new ServiceOpData();
		data.setOp(Op.PUBLISH.toLowerCase());
		data.setStdName(stdName);
		data.setLocalNames(localNames);
		data.setDesc(desc);
		data.setType(type);
		data.setComponents(components);
		data.setVer(ver);
		data.setHome(home);
		data.setWaiters(waiters);
		data.setProtocols(protocols);
		data.setCodes(codes);
		data.setServices(services);
		data.setParams(params);
		return data;
	}

	public static ServiceOpData makeUpdate(String sid, String stdName, Map<String, String> localNames,
                                           String desc, String type, List<String> components,
                                           String ver, Map<String,String> home, List<String> waiters, List<String>protocols,
										   List<String> codes, List<String>services, Object params) {
		ServiceOpData data = new ServiceOpData();
		data.setOp(Op.UPDATE.toLowerCase());
		data.setSid(sid);
		data.setStdName(stdName);
		data.setLocalNames(localNames);
		data.setDesc(desc);
		data.setType(type);
		data.setComponents(components);
		data.setVer(ver);
		data.setHome(home);
		data.setWaiters(waiters);
		data.setProtocols(protocols);
		data.setCodes(codes);
		data.setServices(services);
		data.setParams(params);
		return data;
	}

	public static ServiceOpData makeStop(List<String> sids) {
		ServiceOpData data = new ServiceOpData();
		data.setOp(Op.STOP.toLowerCase());
		data.setSids(sids);
		return data;
	}

	public static ServiceOpData makeClose(List<String> sids, String closeStatement) {
		ServiceOpData data = new ServiceOpData();
		data.setOp(Op.CLOSE.toLowerCase());
		data.setSids(sids);
		data.setCloseStatement(closeStatement);
		return data;
	}

	public static ServiceOpData makeRecover(List<String> sids) {
		ServiceOpData data = new ServiceOpData();
		data.setOp(Op.RECOVER.toLowerCase());
		data.setSids(sids);
		return data;
	}

	/**
	 * @param rate  0 to 5
	 * @param cause optional free text saying why; pass null when blank so the
	 *              field is omitted rather than carved as an empty string
	 */
	public static ServiceOpData makeRate(String sid, Integer rate, String cause) {
		ServiceOpData data = new ServiceOpData();
		data.setOp(Op.RATE.toLowerCase());
		data.setSid(sid);
		data.setRate(rate);
		data.setCause(cause);
		return data;
	}

	public String getPricePerKB() {
		return pricePerKB;
	}

	public void setPricePerKB(String pricePerKB) {
		this.pricePerKB = pricePerKB;
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

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public String getMinCredit() {
		return minCredit;
	}

	public void setMinCredit(String minCredit) {
		this.minCredit = minCredit;
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
