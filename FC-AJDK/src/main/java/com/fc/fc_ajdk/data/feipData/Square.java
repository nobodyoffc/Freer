package com.fc.fc_ajdk.data.feipData;

import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.JsonUtils;

import java.util.List;
import java.util.Map;

public class Square extends FcObject {
	private String name;
	private String desc;
	
	private List<String> namers;
	private List<String> members;
	private Long memberNum;
	private Long birthTime;
	private Long birthHeight;
	private String lastTxId;
	private Long lastTime;
	private Long lastHeight;
	private Long cddToUpdate;
	private Long tCdd;
	private Map<String, String> home;
	private Boolean onChain;

	public String toJson(){
		return JsonUtils.toJson(this);
	}

	public String toNiceJson(){
		return JsonUtils.toNiceJson(this);
	}

	public static Square fromJson(String json){
		return JsonUtils.fromJson(json, Square.class);
	}

	public Long getMemberNum() {
		return memberNum;
	}

	public void setMemberNum(Long memberNum) {
		this.memberNum = memberNum;
	}
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
	public Long getCddToUpdate() {
		return cddToUpdate;
	}
	public void setCddToUpdate(Long requiredCdd) {
		this.cddToUpdate = requiredCdd;
	}
	public Long gettCdd() {
		return tCdd;
	}
	public void settCdd(Long tCdd) {
		this.tCdd = tCdd;
	}

	public List<String> getNamers() {
		return namers;
	}

	public void setNamers(List<String> namers) {
		this.namers = namers;
	}

	public List<String> getMembers() {
		return members;
	}

	public void setMembers(List<String> members) {
		this.members = members;
	}

	public Map<String, String> getHome() {
		return home;
	}

	public void setHome(Map<String, String> home) {
		this.home = home;
	}

	public Boolean getOnChain() {
		return onChain;
	}

	public void setOnChain(Boolean onChain) {
		this.onChain = onChain;
	}
}
