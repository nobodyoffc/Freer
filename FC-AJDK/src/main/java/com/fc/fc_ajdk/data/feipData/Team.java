package com.fc.fc_ajdk.data.feipData;

import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.JsonUtils;

import java.util.List;
import java.util.Map;

public class Team extends FcObject {

	private String owner;
	private String stdName;
	private Map<String, String> localNames;
	private List<String> waiters;
	private List<String> accounts;
	private String consensusId;
	private String desc;
	private List<String> members;
	private Long memberNum;
	private List<String> exMembers;
	private List<String> managers;
	private String transferee;
	private List<String> invitees;
	private List<String> notAgreeMembers;
	
	private Long birthTime;
	private Long birthHeight;
	private String lastTxId;
	private Long lastTime;
	private Long lastHeight;
	private Long tCdd;
	private Float tRate;
	private Boolean active;
	private Map<String, String> home;

	public String toJson(){
		return JsonUtils.toJson(this);
	}
	public String toNiceJson(){
		return JsonUtils.toNiceJson(this);
	}
	public static Team fromJson(String json){
		return JsonUtils.fromJson(json, Team.class);
	}
	public String getOwner() {
		return owner;
	}
	public void setOwner(String owner) {
		this.owner = owner;
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

	public void setWaiters(List<String> waiters) {
		this.waiters = waiters;
	}

	public void setAccounts(List<String> accounts) {
		this.accounts = accounts;
	}

	public String getConsensusId() {
		return consensusId;
	}

	public void setConsensusId(String consensusId) {
		this.consensusId = consensusId;
	}

	public String getDesc() {
		return desc;
	}

	public void setDesc(String desc) {
		this.desc = desc;
	}

	public List<String> getMembers() {
		return members;
	}

	public void setMembers(List<String> members) {
		this.members = members;
	}

	public List<String> getExMembers() {
		return exMembers;
	}

	public void setExMembers(List<String> exMembers) {
		this.exMembers = exMembers;
	}

	public List<String> getManagers() {
		return managers;
	}

	public void setManagers(List<String> managers) {
		this.managers = managers;
	}

	public String getTransferee() {
		return transferee;
	}

	public void setTransferee(String transferee) {
		this.transferee = transferee;
	}

	public List<String> getInvitees() {
		return invitees;
	}

	public void setInvitees(List<String> invitees) {
		this.invitees = invitees;
	}

	public List<String> getNotAgreeMembers() {
		return notAgreeMembers;
	}

	public void setNotAgreeMembers(List<String> notAgreeMembers) {
		this.notAgreeMembers = notAgreeMembers;
	}

	public Boolean getActive() {
		return active;
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

	public Long getMemberNum() {
		return memberNum;
	}

	public List<String> getWaiters() {
		return waiters;
	}

	public List<String> getAccounts() {
		return accounts;
	}

	public void setMemberNum(Long memberNum) {
		this.memberNum = memberNum;
	}

	public Map<String, String> getHome() {
		return home;
	}

	public void setHome(Map<String, String> home) {
		this.home = home;
	}
}
