package com.fc.fc_ajdk.data.feipData;

import java.util.HashMap;
import java.util.Map;

import com.fc.fc_ajdk.constants.FieldNames;

public class HomeOpData {
	
	private String op;
	private Map<String,String> home;
	
	public enum Op {
		REGISTER(FeipOp.REGISTER),
		UNREGISTER(FeipOp.UNREGISTER);

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
			return this.name().toLowerCase();
		}
	}

	public static final Map<String, String[]> OP_FIELDS = new HashMap<>();

	static {
		OP_FIELDS.put(Op.REGISTER.toLowerCase(), new String[]{FieldNames.HOME});
		OP_FIELDS.put(Op.UNREGISTER.toLowerCase(), new String[]{FieldNames.HOME});
	}

	// Existing getters and setters
	public String getOp() {
		return op;
	}
	public void setOp(String op) {
		this.op = op;
	}

	public Map<String, String> getHome() {
		return home;
	}

	public void setHome(Map<String, String> home) {
		this.home = home;
	}
}
