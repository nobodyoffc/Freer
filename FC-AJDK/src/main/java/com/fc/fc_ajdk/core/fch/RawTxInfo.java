package com.fc.fc_ajdk.core.fch;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.fc_ajdk.data.fchData.RawTxForCsV1;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.JsonUtils;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RawTxInfo extends FcEntity {
    public static final String VERSION_2 = "2";
    private String sender;
    private Double feeRate;
    private List<Cash> inputs;
    private List<Cash> outputs;
    private String opReturn;
    private String changeTo;
    private Long lockTime;
    private Long cd;
//    private Multisig multisig;
    private String ver;
    private KeyInfo senderInfo;
    private Long cdd;
    private Map<String, List<String>> fidSigMap;

    public RawTxInfo() {
        this.inputs = new ArrayList<>();
        this.outputs = new ArrayList<>();

    }


    public RawTxInfo(String sender, List<Cash> inList, List<Cash> outList, String opReturn, Long cd, Double feeRate, Multisig multisig, String ver) {
        super();
        this.sender = sender;
        this.setOutputs(outList);
        this.setOpReturn(opReturn);
        if(cd==null)this.cd=0L;
        else this.setCd(cd);
        if(feeRate==null)this.setFeeRate(TxHandler.DEFAULT_FEE_RATE);
        else this.setFeeRate(feeRate);
        this.setSenderMultisig(multisig);
        if(ver ==null)this.ver="2";
        else this.setVer(ver);
        this.setInputs(Cash.makeCashListForPay(inList));
    }


    @Override
    public String toJson() {
        this.senderInfo = null;
        return super.toJson();
    }

    @Override
    public String toNiceJson() {
        this.senderInfo = null;
        return super.toNiceJson();
    }

    public String toJsonWithSenderInfo() {
        return super.toJson();
    }

    public static RawTxInfo fromString(String offLineTx) {
        RawTxInfo rawTxInfo = new RawTxInfo();
        if (offLineTx != null) {
            try {
                rawTxInfo = JsonUtils.fromJson(offLineTx, RawTxInfo.class);
            } catch (Exception e) {
                List<RawTxForCsV1> rawTxForCsV1List = JsonUtils.listFromJson(offLineTx, RawTxForCsV1.class);
                rawTxInfo = fromRawTxForCs(rawTxForCsV1List);
                return rawTxInfo;
            }
        }
        return rawTxInfo;
    }


//    public static RawTxInfo createMultisignTx(RawTxInfo rawTxInfo) {
//        Transaction tx = TxCreator.createUnsignedTx(rawTxInfo, FchMainNetwork.get());
//        if(tx ==null)return null;
//        byte[] rawTx = tx.bitcoinSerialize();
//
//        return new RawTxInfo(rawTx, rawTxInfo);
//    }

    @androidx.annotation.Nullable
    public static Transaction createMultisigTx(RawTxInfo rawTxInfo, Multisig multisig, MainNetParams mainNetwork) {
        rawTxInfo.setSenderMultisig(multisig);
        return new TxHandler(mainNetwork).createTx(rawTxInfo, mainNetwork);
    }

    public Double getFeeRate() {
        return feeRate;
    }

    public List<Cash> getInputs() {
        return inputs;
    }

    public void setInputs(List<Cash> inputs) {
        this.inputs = inputs;
    }

    public List<Cash> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<Cash> outputs) {
        this.outputs = outputs;
    }

    public String getOpReturn() {
        return opReturn;
    }

    public void setOpReturn(String opReturn) {
        this.opReturn = opReturn;
    }

    public Multisig getSenderMultisig() {
        if(senderInfo==null || senderInfo.getMultisign()==null)
            return null;
        return senderInfo.getMultisign();
    }

    public void setSenderMultisig(Multisig multisig) {
        if(this.senderInfo==null)
            this.senderInfo= new KeyInfo();
        this.senderInfo.setMultisign(multisig);
        if(this.senderInfo.getId()==null)
            this.senderInfo.setId(multisig.getId());
    }

    public static RawTxInfo fromRawTxForCs(String csTxJson)  {
        List<RawTxForCsV1> csTxList = JsonUtils.listFromJson(csTxJson, RawTxForCsV1.class);
        if(csTxList.isEmpty())return null;
        return RawTxInfo.fromRawTxForCs(csTxList);
    }

    /**
     * Converts a list of RawTxForCs objects to an OffLineTxData object
     *
     * @param rawTxForCsV1List List of RawTxForCs objects
     * @return A new OffLineTxData object
     */
    public static RawTxInfo fromRawTxForCs(List<RawTxForCsV1> rawTxForCsV1List) {
        if (rawTxForCsV1List == null || rawTxForCsV1List.isEmpty()) return null;

        RawTxInfo rawTxInfo = new RawTxInfo();
        rawTxInfo.setSender(rawTxForCsV1List.get(0).getAddress());

        // Process inputs
        List<Cash> inputs = new ArrayList<>();

        // Process outputs
        List<Cash> outputs = new ArrayList<>();

        // Process message
        String msg = null;

        for (RawTxForCsV1 rawTx : rawTxForCsV1List) {
            switch (rawTx.getDealType()) {
                case 1 -> {
                    Cash cash = new Cash();
                    cash.setOwner(rawTx.getAddress());
                    cash.setValue(FchUtils.coinToSatoshi(rawTx.getAmount()));
                    cash.setBirthTxId(rawTx.getTxid());
                    cash.setBirthIndex(rawTx.getIndex());
                    inputs.add(cash);
                }
                case 2 -> {
                    Cash cash = new Cash();
                    cash.setOwner(rawTx.getAddress());
                    cash.setAmount(rawTx.getAmount());
                    outputs.add(cash);
                }
                case 3 -> msg = rawTx.getMsg();
            }
        }
        rawTxInfo.setInputs(inputs);
        rawTxInfo.setOutputs(outputs);
        rawTxInfo.setOpReturn(msg);

        return rawTxInfo;
    }

    public static RawTxInfo fromTransaction(Transaction tx) {
        if (tx == null) return null;

        RawTxInfo rawTxInfo = new RawTxInfo();
        rawTxInfo.setId(tx.getTxId().toString());
        rawTxInfo.setVer("2");
        rawTxInfo.setLockTime(tx.getLockTime());

        // Process inputs
        List<Cash> inputs = new ArrayList<>();
        for (org.bitcoinj.core.TransactionInput input : tx.getInputs()) {
            Cash cash = new Cash();
            cash.setBirthTxId(input.getOutpoint().getHash().toString());
            cash.setBirthIndex((int) input.getOutpoint().getIndex());
            if (input.getValue() != null) {
                cash.setValue(input.getValue().getValue());
            }
            inputs.add(cash);
        }
        rawTxInfo.setInputs(inputs);

        // Process outputs
        List<Cash> outputs = new ArrayList<>();
        String opReturn = null;
        String changeTo = null;

        for (org.bitcoinj.core.TransactionOutput output : tx.getOutputs()) {
            if (output.getScriptPubKey().isOpReturn()) {
                // Extract OP_RETURN data
                byte[] opReturnData = output.getScriptPubKey().getChunks().get(1).data;
                if (opReturnData != null) {
                    opReturn = new String(opReturnData);
                }
            } else {
                Cash cash = new Cash();
                try {
                    org.bitcoinj.core.Address address = output.getScriptPubKey().getToAddress(MainNetParams.get());
                    cash.setOwner(address.toString());
                    cash.setValue(output.getValue().getValue());
                    outputs.add(cash);
                    
                    // Set first output address as changeTo if not set
                    if (changeTo == null) {
                        changeTo = address.toString();
                    }
                } catch (Exception e) {
                    // Handle non-standard scripts
                }
            }
        }

        rawTxInfo.setOutputs(outputs);
        rawTxInfo.setOpReturn(opReturn);
        rawTxInfo.setChangeTo(changeTo);

        return rawTxInfo;
    }

    /**
     * Converts this OffLineTxData object to a list of RawTxForCs objects
     *
     * @return List of RawTxForCs objects
     */
    public List<RawTxForCsV1> toRawTxForCsList() {
        List<RawTxForCsV1> result = new ArrayList<>();

        // Convert inputs
        if (inputs != null) {
            for (int i = 0; i < inputs.size(); i++) {
                Cash cash = inputs.get(i);
                RawTxForCsV1 rawTx = RawTxForCsV1.newInput(
                        cash.getOwner(),
                        FchUtils.satoshiToCoin(cash.getValue()),
                        cash.getBirthTxId(),
                        cash.getBirthIndex(),
                        i
                );
                result.add(rawTx);
            }
        }

        // Convert outputs
        int j = 0;
        if (outputs != null) {
            for (j = 0; j < outputs.size(); j++) {
                Cash cash = outputs.get(j);
                RawTxForCsV1 rawTx = RawTxForCsV1.newOutput(
                        cash.getOwner(),
                        cash.getAmount(),
                        j
                );
                result.add(rawTx);
            }
        }

        // Add OP_RETURN message if present
        if (opReturn != null && !opReturn.isEmpty()) {
            RawTxForCsV1 rawTx = RawTxForCsV1.newOpReturn(opReturn, j);
            result.add(rawTx);
        }

        return result;
    }



    public String makeRawTxHex() {
        Transaction tx = new TxHandler().createTx(this, FchMainNetwork.MAINNETWORK);
        if(tx==null)return null;
        return Hex.toHex( tx.bitcoinSerialize());
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getVer() {
        return ver;
    }

    public void setVer(String ver) {
        this.ver = ver;
    }

    public void setFeeRate(Double feeRate) {
        this.feeRate = feeRate;
    }

    public Long getCd() {
        return cd;
    }

    public void setCd(Long cd) {
        this.cd = cd;
    }

    public String getChangeTo() {
        return changeTo;
    }

    public void setChangeTo(String changeTo) {
        this.changeTo = changeTo;
    }

    public Long getLockTime() {
        return lockTime;
    }

    public void setLockTime(Long lockTime) {
        this.lockTime = lockTime;
    }

    public KeyInfo getSenderInfo() {
        return senderInfo;
    }

    public void setSenderInfo(KeyInfo senderInfo) {
        this.senderInfo = senderInfo;
    }

    public Long getCdd() {
        return cdd;
    }

    public void setCdd(Long cdd) {
        this.cdd = cdd;
    }

    public Map<String, List<String>> getFidSigMap() {
        return fidSigMap;
    }

    public void setFidSigMap(Map<String, List<String>> fidSigMap) {
        this.fidSigMap = fidSigMap;
    }

}
