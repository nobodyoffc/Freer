package com.fc.fc_ajdk.data.feipData;

import com.fc.fc_ajdk.utils.JsonUtils;

public class Feip {
	public static Long CD_REQUIRED = 1L;
	public static long CDD_CHECK_HEIGHT=4032000;

	private String type;
	private String sn;
	private String ver;
	private String name;
	private String pid;
	private String did;
	private Object data;

	public Feip() {
	}


	public Feip(String sn, String ver, String name) {
		this.type = "FEIP";
		this.sn = sn;
		this.ver = ver;
		this.name = name;
	}

	public enum FeipProtocol {
		PROTOCOL("1","7", "FeipProtocol"),
		CODE("2","1", "Code"),
		CID("3","4", "CID"),
		NOBODY("4","1", "Nobody"),
		SERVICE("5","3", "Service"),
		MASTER("6","1", "Master"),
		MAIL("7","4", "Mail"),
		STATEMENT("8","1", "Statement"),
		HOME("9","1", "Home"),
		NOTICE_FEE("10","1", "NoticeFee"),
		NID("11","1", "NID"),
		CONTACT("12","3", "Contact"),
		BOX("13","1", "Box"),
		PROOF("14","1", "Proof"),
		APP("15","1", "APP"),
		REPUTATION("16","1", "Reputation"),
		SECRET("17","3", "Secret"),
		TEAM("18","1", "Team"),
		SQUARE("19","4", "Square"),  // FEIP protocol name "Square" for API compatibility; UI displays "Square"
		TOKEN("20","1", "Token"),
		TEXT("21","1", "Text"),
		REMARK("22","1", "Remark"),
		SOUND("23","1", "Sound"),
		IMAGE("24","1", "Image"),
		VIDEO("25","1", "Video");

		private final String sn;
		private final String ver;
		private final String name;

		FeipProtocol(String sn, String ver, String name) {
			this.sn = sn;
			this.ver = ver;
			this.name = name;
		}

		public Feip getFeip() {
			return new Feip(getSn(), getVer(), getName());
		}

		public String getSn() {
			return sn;
		}

		public String getVer() {
			return ver;
		}

		public String getName() {
			return name;
		}

		public static FeipProtocol fromName(String name) {
			if (name == null) {
				throw new IllegalArgumentException("FeipProtocol name cannot be null");
			}
			// "Square" maps to SQUARE (protocol name "Square" for API compatibility)
			if (name.equalsIgnoreCase("Square")) {
				return SQUARE;
			}
			for (FeipProtocol feipProtocol : FeipProtocol.values()) {
				if (feipProtocol.getName().equalsIgnoreCase(name)) {
					return feipProtocol;
				}
			}
			return null;
		}

		public static FeipProtocol fromSn(String sn) {
			if (sn == null) {
				return null;
			}
			for (FeipProtocol feipProtocol : FeipProtocol.values()) {
				if (feipProtocol.getSn().equalsIgnoreCase(sn)) {
					return feipProtocol;
				}
			}
			return null;
		}
	}

	public static void main(String[] args) {
		Feip feip = Feip.fromProtocolName(FeipProtocol.APP);
		System.out.println(feip.toJson());

		Feip feip2 = Feip.fromName("APP");
		System.out.println(feip2.toJson());

		Feip feip3 = Feip.fromProtocolName(FeipProtocol.TEAM);
		System.out.println(feip3.toJson());

		Feip feip4 = Feip.fromName("team");
		System.out.println(feip4.toJson());
	}

	public static Feip fromProtocolName(FeipProtocol feipProtocol) {
		return new Feip(feipProtocol.getSn(), feipProtocol.getVer(), feipProtocol.getName());
	}

	public static Feip fromName(String name) {
		FeipProtocol feipProtocol = FeipProtocol.valueOf(name.toUpperCase());
		return new Feip(feipProtocol.getSn(), feipProtocol.getVer(), feipProtocol.getName());
	}

	public String toJson(){
		return JsonUtils.toJson(this);
	}

	public String toNiceJson(){
		return JsonUtils.toNiceJson(this);
	}

	public String getDid() {
		return did;
	}

	public void setDid(String did) {
		this.did = did;
	}

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
	public String getPid() {
		return pid;
	}
	public void setPid(String pid) {
		this.pid = pid;
	}
	public Object getData() {
		return data;
	}
	public void setData(Object data) {
		this.data = data;
	}

}
