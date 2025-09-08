package com.fc.freer.model;

import com.fc.fc_ajdk.data.fcData.Op;

import java.util.List;

public class FcReplyBody {
    protected String id;
        protected String requestId;
        protected Op op;
        protected Integer code;
        protected String message;
        protected Integer nonce;
        protected Long time;
        protected Long balance;
        protected Object data;
        protected List<String> last;
        protected Long got;
        protected Long total;
        protected Long bestHeight;
        protected String bestBlockId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Op getOp() {
        return op;
    }

    public void setOp(Op op) {
        this.op = op;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getNonce() {
        return nonce;
    }

    public void setNonce(Integer nonce) {
        this.nonce = nonce;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public Long getBalance() {
        return balance;
    }

    public void setBalance(Long balance) {
        this.balance = balance;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    public List<String> getLast() {
        return last;
    }

    public void setLast(List<String> last) {
        this.last = last;
    }

    public Long getGot() {
        return got;
    }

    public void setGot(Long got) {
        this.got = got;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Long getBestHeight() {
        return bestHeight;
    }

    public void setBestHeight(Long bestHeight) {
        this.bestHeight = bestHeight;
    }

    public String getBestBlockId() {
        return bestBlockId;
    }

    public void setBestBlockId(String bestBlockId) {
        this.bestBlockId = bestBlockId;
    }
}
