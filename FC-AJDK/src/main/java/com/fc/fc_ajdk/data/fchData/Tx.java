package com.fc.fc_ajdk.data.fchData;

import com.fc.fc_ajdk.core.fch.FchMainNetwork;
import com.fc.fc_ajdk.core.fch.RawTxParser;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.Hex;

import org.bitcoinj.core.Transaction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Tx extends FcObject {
	
	//from block;
	private Integer version;		//version
	private Long lockTime;	//lockTime
	private Long blockTime;		//blockTime
	private String blockId;		//block ID, hash of block head
	private Integer txIndex;		//the index of this tx in the block
	private String coinbase;	//string of the coinbase script
	private Integer outCount;		//number of outputs
	private Integer inCount;		//number of inputs
	private Long height;		//block height of the block
	
	private String opReBrief; 	//Former 30 bytes of OP_RETURN data in String.
	
	//calculated
	private Long inValueT;		//total amount of inputs
	private Long outValueT;		//total amount of outputs
	private Long fee;		//tx fee
	
	private Long cdd;
	private String rawTx;

	private ArrayList<CashMask> spentCashes;
	private ArrayList<CashMask> issuedCashes;

	public static Tx fromRawTx(String rawTx, List<Cash> inputCashList) {
		Transaction transaction = new Transaction(FchMainNetwork.MAINNETWORK, Hex.fromHex(rawTx));
		return fromTransaction(transaction,inputCashList);
	}

	public static Tx fromTransaction(Transaction transaction, List<Cash> inputCashList) {
		Tx tx = new Tx();
		tx.setId(transaction.getTxId().toString());
		tx.setVersion((int) transaction.getVersion());
		tx.setLockTime(transaction.getLockTime());
		tx.setInCount(transaction.getInputs().size());
		tx.setOutCount(transaction.getOutputs().size());
//        tx.setCdd(cdd);

		// Calculate total output value
		long totalOutputValue = 0;
		for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
			totalOutputValue += output.getValue().value;
		}
		tx.setOutValueT(totalOutputValue);

		long totalInputValue = 0;
		ArrayList<CashMask> spentCashes = new ArrayList<>();

		for (Cash cash : inputCashList) {
			if(cash.getId()==null)cash.makeId();
			spentCashes.add(new CashMask(cash.getOwner(),cash.getValue(),cash.getId(),cash.getCd()));
			totalInputValue += cash.getValue();
		}

		tx.setFee(totalInputValue-totalOutputValue);

		// Set other fields to null or default values as they require additional context
		tx.setBlockTime(null);
		tx.setBlockId(null);
		tx.setTxIndex(null);
		tx.setCoinbase(null);
		tx.setHeight(null);
		tx.setOpReBrief(null);
		tx.setInValueT(null);

		tx.setSpentCashes(spentCashes);

		tx.setIssuedCashes(new ArrayList<>());
		for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
			String fid = null;
			try {
				fid = output.getAddressFromP2PKHScript(FchMainNetwork.MAINNETWORK).toBase58();
			}catch (Exception e){
				try {
					fid = output.getAddressFromP2SH(FchMainNetwork.MAINNETWORK).toBase58();
				}catch (Exception ignore){
					try {
						byte[] script = output.getScriptBytes();
						tx.setOpReBrief(RawTxParser.parseOpReturn(script));
					} catch (IOException ex) {
						continue;
					}
				}
			}

			if(fid!=null) tx.getIssuedCashes().add(new CashMask(fid,output.getValue().value,null,null));
		}

		return tx;
	}

	/**
	 * Returns a LinkedHashMap with field names in their declaration order and their display names in multiple languages
	 * @return LinkedHashMap<fieldName, Map<language, displayName>>
	 */
	public static LinkedHashMap<String, Map<String, String>> getFieldNameMap() {
		LinkedHashMap<String, Map<String, String>> fieldMap = new LinkedHashMap<>();

		// Add fields in their declaration order from source code (lines 23-44)
		addFieldToMap(fieldMap, "id", "TXID", "交易ID");
		addFieldToMap(fieldMap, "inCount", "Input Count", "输入数量");
		addFieldToMap(fieldMap, "outCount", "Output Count", "输出数量");
		addFieldToMap(fieldMap, "inValueT", "Total Input Value", "总输入金额");
		addFieldToMap(fieldMap, "outValueT", "Total Output Value", "总输出金额");
		addFieldToMap(fieldMap, "fee", "Fee", "手续费");
		addFieldToMap(fieldMap, "opReBrief", "OP_RETURN Brief", "刻字摘要");
		addFieldToMap(fieldMap, "cdd", "CDD", "币天销毁");
		addFieldToMap(fieldMap, "version", "Version", "版本");
		addFieldToMap(fieldMap, "coinbase", "Coinbase", "币基");
		addFieldToMap(fieldMap, "lockTime", "Lock Time", "锁定时间");
		addFieldToMap(fieldMap, "height", "Height", "高度");
		addFieldToMap(fieldMap, "blockTime", "Block Time", "区块时间");
		addFieldToMap(fieldMap, "blockId", "Block ID", "区块ID");
		addFieldToMap(fieldMap, "txIndex", "TX Index", "交易索引");
		addFieldToMap(fieldMap, "spentCashes", "Spent Cashes", "花费币");
		addFieldToMap(fieldMap, "issuedCashes", "Issued Cashes", "发行币");

		return fieldMap;
	}

	public String getRawTx() {
		return rawTx;
	}

	public void setRawTx(String rawTx) {
		this.rawTx = rawTx;
	}

	public Integer getVersion() {
		return version;
	}

	public void setVersion(Integer version) {
		this.version = version;
	}

	public Long getLockTime() {
		return lockTime;
	}

	public void setLockTime(Long lockTime) {
		this.lockTime = lockTime;
	}

	public Long getBlockTime() {
		return blockTime;
	}

	public void setBlockTime(Long blockTime) {
		this.blockTime = blockTime;
	}

	public String getBlockId() {
		return blockId;
	}

	public void setBlockId(String blockId) {
		this.blockId = blockId;
	}

	public Integer getTxIndex() {
		return txIndex;
	}

	public void setTxIndex(Integer txIndex) {
		this.txIndex = txIndex;
	}

	public String getCoinbase() {
		return coinbase;
	}

	public void setCoinbase(String coinbase) {
		this.coinbase = coinbase;
	}

	public Integer getOutCount() {
		return outCount;
	}

	public void setOutCount(Integer outCount) {
		this.outCount = outCount;
	}

	public Integer getInCount() {
		return inCount;
	}

	public void setInCount(Integer inCount) {
		this.inCount = inCount;
	}

	public Long getHeight() {
		return height;
	}

	public void setHeight(Long height) {
		this.height = height;
	}

	public String getOpReBrief() {
		return opReBrief;
	}

	public void setOpReBrief(String opReBrief) {
		this.opReBrief = opReBrief;
	}

	public Long getInValueT() {
		return inValueT;
	}

	public void setInValueT(Long inValueT) {
		this.inValueT = inValueT;
	}

	public Long getOutValueT() {
		return outValueT;
	}

	public void setOutValueT(Long outValueT) {
		this.outValueT = outValueT;
	}

	public Long getFee() {
		return fee;
	}

	public void setFee(Long fee) {
		this.fee = fee;
	}

	public Long getCdd() {
		return cdd;
	}

	public void setCdd(Long cdd) {
		this.cdd = cdd;
	}

	public ArrayList<CashMask> getSpentCashes() {
		return spentCashes;
	}

	public void setSpentCashes(ArrayList<CashMask> spentCashes) {
		this.spentCashes = spentCashes;
	}

	public ArrayList<CashMask> getIssuedCashes() {
		return issuedCashes;
	}

	public void setIssuedCashes(ArrayList<CashMask> issuedCashes) {
		this.issuedCashes = issuedCashes;
	}
}
