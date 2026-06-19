package com.fc.fc_ajdk.data.fchData;


import android.content.Context;
import android.widget.Toast;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.FcObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MultisigTxDetail extends FcObject {
    private String sender;
    private Map<String,String> cashIdAmountMap;
    private List<Cash> cashList;
    private String opReturn;
    private String mOfN;
    private List<String> signedFidList;
    private List<String> unSignedFidList;
    private Integer restSignNum;


    public static MultisigTxDetail fromMultiSigData(RawTxInfo rawTxInfo, Context context){
        MultisigTxDetail multisigTxDetail = new MultisigTxDetail();

        Multisig multisig = rawTxInfo.getSenderMultisig();
        if(multisig ==null){
            Toast.makeText(context,"Multisig can't be null.",Toast.LENGTH_LONG).show();
            return null;
        }
        if(multisig.getId()==null){
            Toast.makeText(context,"The FID of Multisig can't be null.",Toast.LENGTH_LONG).show();
            return null;
        }
        multisigTxDetail.setSender(multisig.getId());
        multisigTxDetail.setCashIdAmountMap(new HashMap<>());

        multisigTxDetail.setSendToList(rawTxInfo.getOutputs());
        multisigTxDetail.setOpReturn(rawTxInfo.getOpReturn());
        multisigTxDetail.setmOfN(multisig.getM() + "/"+ multisig.getN());
        multisigTxDetail.setUnSignedFidList(new ArrayList<>(rawTxInfo.getSenderMultisig().getFids()));

        Map<String, List<String>> fidSigMap = rawTxInfo.getFidSigMap();
        if(fidSigMap != null && !fidSigMap.isEmpty()) {
            Set<String> signedFidSet = fidSigMap.keySet();
            multisigTxDetail.setSignedFidList(new ArrayList<>(signedFidSet));
            multisigTxDetail.getUnSignedFidList().removeAll(multisigTxDetail.getSignedFidList());
        }

        int restSignNum = multisig.getM();
        if(fidSigMap!=null && !fidSigMap.isEmpty())
            for(String fid: multisig.getFids()){
                if(fidSigMap.get(fid)!=null)restSignNum--;
                if(restSignNum==0)break;
            }

        if(restSignNum<0)restSignNum=0;

        multisigTxDetail.setRestSignNum(restSignNum);
        return multisigTxDetail;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public Map<String, String> getCashIdAmountMap() {
        return cashIdAmountMap;
    }

    public void setCashIdAmountMap(Map<String, String> cashIdAmountMap) {
        this.cashIdAmountMap = cashIdAmountMap;
    }

    public List<Cash> getSendToList() {
        return cashList;
    }

    public void setSendToList(List<Cash> cashList) {
        this.cashList = cashList;
    }

    public String getmOfN() {
        return mOfN;
    }

    public void setmOfN(String mOfN) {
        this.mOfN = mOfN;
    }

    public List<String> getSignedFidList() {
        return signedFidList;
    }

    public void setSignedFidList(List<String> signedFidList) {
        this.signedFidList = signedFidList;
    }

    public List<String> getUnSignedFidList() {
        return unSignedFidList;
    }

    public void setUnSignedFidList(List<String> unSignedFidList) {
        this.unSignedFidList = unSignedFidList;
    }

    public Integer getRestSignNum() {
        return restSignNum;
    }

    public void setRestSignNum(Integer restSignNum) {
        this.restSignNum = restSignNum;
    }

    public String getOpReturn() {
        return opReturn;
    }

    public void setOpReturn(String opReturn) {
        this.opReturn = opReturn;
    }
}
