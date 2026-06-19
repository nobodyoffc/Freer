package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.fchData.CashMask;
import org.jetbrains.annotations.NotNull;

import com.fc.fc_ajdk.data.fchData.Tx;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.FchUtils;

import java.util.List;
import java.util.ArrayList;

import static com.fc.fc_ajdk.constants.IndicesNames.OPRETURN;

public class FidTxMask extends FcEntity{
    private String fid;
    private Long time;
    private Long height;
    private Integer index;
    private Double balance;
    private Double fee;
    private String to;
    private String from;
    private Integer in;
    private Integer out;
    private Boolean isNew;

    @NotNull
    public static FidTxMask fromTxInfo(String fid, Tx tx) {
        FidTxMask fidTxMask = new FidTxMask();
        long sum = 0;
        for(CashMask issuedCash : tx.getIssuedCashes()){
            if(issuedCash.getOwner().equals(fid))
                sum += issuedCash.getValue();
        }
        for(CashMask spentCash : tx.getSpentCashes()){
            if(spentCash.getOwner().equals(fid))
                sum -= spentCash.getValue();
        }

        fidTxMask.setBalance(FchUtils.satoshiToCoin(sum));
        if(tx.getFee()!=null)fidTxMask.setFee(FchUtils.satoshiToCoin(tx.getFee()));
        fidTxMask.setHeight(tx.getHeight());
        fidTxMask.setTime(tx.getBlockTime());
        fidTxMask.setId(tx.getId());
        fidTxMask.setIn(tx.getInCount());
        fidTxMask.setOut(tx.getOutCount());
        fidTxMask.setIndex(tx.getTxIndex());
        fidTxMask.setFid(fid);
        if(sum>0){
            fidTxMask.setTo(fid);
            if(tx.getSpentCashes().size()>0) {
                CashMask cashMask = tx.getSpentCashes().get(0);
                if (cashMask != null) fidTxMask.setFrom(cashMask.getOwner());
                else fidTxMask.setFrom(Constants.COINBASE);
            }else fidTxMask.setFrom(Constants.COINBASE);
        }else{
            fidTxMask.setFrom(fid);
            CashMask cashMask = tx.getIssuedCashes().get(0);
            if(cashMask !=null && cashMask.getOwner().equals(OPRETURN)) cashMask = tx.getIssuedCashes().get(1);
            if(cashMask !=null)fidTxMask.setTo(cashMask.getOwner());
            else fidTxMask.setTo(Constants.MINER);
        }
        return fidTxMask;
    }

    @NotNull
    public static List<FidTxMask> fromTxInfo(String fid, List<Tx> txList) {
        List<FidTxMask> fidTxMaskList = new ArrayList<>();
        if (txList == null || txList.isEmpty()) {
            return fidTxMaskList;
        }
        for (Tx tx : txList) {
            fidTxMaskList.add(fromTxInfo(fid, tx));
        }
        return fidTxMaskList;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public Long getHeight() {
        return height;
    }

    public void setHeight(Long height) {
        this.height = height;
    }

    public Double getBalance() {
        return balance;
    }

    public void setBalance(Double balance) {
        this.balance = balance;
    }

    public Double getFee() {
        return fee;
    }

    public void setFee(Double fee) {
        this.fee = fee;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFid() {
        return fid;
    }

    public void setFid(String fid) {
        this.fid = fid;
    }

    public Integer getIn() {
        return in;
    }

    public void setIn(Integer in) {
        this.in = in;
    }

    public Integer getOut() {
        return out;
    }

    public void setOut(Integer out) {
        this.out = out;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }

    public Boolean getNew() {
        return isNew;
    }

    public void setNew(Boolean aNew) {
        isNew = aNew;
    }
}