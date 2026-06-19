package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.utils.Hex;

import java.util.List;

public class Hat extends FcObject {
    //basic
    private String hAlg; //hash algorithm
    private Long size;  //size in bytes
    private Long born;  //time being created
    private Long last; //last time being used.

    //extend
    private String name;    //name of the data
    private String desc;    //description of the data
    private List<String> types; //types of the data
    private List<String> aids;  //APP IDs
    private List<String> pids;  //protocol IDs

    //version
    private String srcDid;  //DID of the first version
    private String preDid;  //DID of the previous version

    //slice
    private String tDid;    //DID of the whole data
    private Long tSize;     //size of the whole data
    private Long offset;    //offset of this data in the whole data

    //crypto
    private String rawDid;  //DID of the raw data
    private String key; //the symkey
    private String kCipher; //cipher of the key
    private Boolean Leaked; //if key leaked
    private List<String> cipherIds; //DID of the cipher data

    //manage
    private Integer rank;   //rank of the data
    private DataState state;    //state of the data
    private List<String> locas; //locations of the data

    public void checkIdWithCreate() {
        if(id == null){
            byte[] idBytes = Hash.sha256x2(this.toBytes());
            id = Hex.toHex(idBytes);
        }
    }


    public enum DataState{
        ACTIVE((byte) 1),
        DELETED((byte) 0),
        OUTDATED((byte) 2),
        ARCHIVED((byte) 3)
        ;

        public final byte number;
        DataState(byte number){
            this.number = number;
        }
    }
    public String getHAlg() {
        return hAlg;
    }

    public void setHAlg(String hAlg) {
        this.hAlg = hAlg;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getTypes() {
        return types;
    }

    public void setTypes(List<String> types) {
        this.types = types;
    }

    public List<String> getAids() {
        return aids;
    }

    public void setAids(List<String> aids) {
        this.aids = aids;
    }

    public List<String> getPids() {
        return pids;
    }

    public void setPids(List<String> pids) {
        this.pids = pids;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public Long getBorn() {
        return born;
    }

    public void setBorn(Long born) {
        this.born = born;
    }

    public String getSrcDid() {
        return srcDid;
    }

    public void setSrcDid(String srcDid) {
        this.srcDid = srcDid;
    }

    public String getPreDid() {
        return preDid;
    }

    public void setPreDid(String preDid) {
        this.preDid = preDid;
    }

    public String gettDid() {
        return tDid;
    }

    public void settDid(String tDid) {
        this.tDid = tDid;
    }

    public Long gettSize() {
        return tSize;
    }

    public void settSize(Long tSize) {
        this.tSize = tSize;
    }

    public Long getOffset() {
        return offset;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public String getkCipher() {
        return kCipher;
    }

    public void setkCipher(String kCipher) {
        this.kCipher = kCipher;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public DataState getState() {
        return state;
    }

    public void setState(DataState state) {
        this.state = state;
    }

    public List<String> getLocas() {
        return locas;
    }

    public void setLocas(List<String> locas) {
        this.locas = locas;
    }

    public Long getLast() {
        return last;
    }

    public void setLast(Long last) {
        this.last = last;
    }

    public Boolean getLeaked() {
        return Leaked;
    }

    public void setLeaked(Boolean leaked) {
        Leaked = leaked;
    }

    public String getRawDid() {
        return rawDid;
    }

    public void setRawDid(String rawDid) {
        this.rawDid = rawDid;
    }

    public String gethAlg() {
        return hAlg;
    }

    public void sethAlg(String hAlg) {
        this.hAlg = hAlg;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<String> getCipherIds() {
        return cipherIds;
    }

    public void setCipherIds(List<String> cipherIds) {
        this.cipherIds = cipherIds;
    }
}
