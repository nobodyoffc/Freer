package com.fc.fc_ajdk.data.fchData;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.JsonUtils;

import java.io.IOException;
import java.util.*;

@SuppressWarnings("unused")
public class FchChainInfo extends FcObject {
    public static final long MAX_REQUEST_COUNT = 1000;
    public static final long DEFAULT_COUNT = 100;
    private String time;
    private String height;
    private String blockId;
    private String totalSupply;
    private String circulating;
    private String difficulty;
    private String hashRate;
    private String chainSize;
    private String coinbaseMine;
    private String coinbaseFund;
    private final String initialCoinbaseMine= Constants.INITIAL_COINBASE_MINE;
    private final String initialCoinbaseFund= Constants.INITIAL_COINBASE_FUND;
    private final String mineReductionRatio = Constants.MINE_REDUCTION_RATIO;
    private final String fundReductionRatio = Constants.FUND_REDUCTION_RATIO;
    private final String reducePerBlocks = Constants.REDUCE_PER_BLOCKS;
    private final String reductionStopsAtHeight = Constants.REDUCTION_STOPS_AT_HEIGHT;
    private final String stableAnnualIssuance = Constants.STABLE_ANNUAL_ISSUANCE;
    private final String mineMatureDays = Constants.MINE_MATURE_DAYS;
    private final String fundMatureDays = Constants.FUND_MATURE_DAYS;
    private final String daysPerYear = Constants.DAYS_PER_YEAR_STR;
    private final String blockTimeMinute = Constants.BLOCK_TIME_MINUTE;
    private final String genesisBlockId = Constants.GENESIS_BLOCK_ID;
    private final String startTime = DateUtils.longToTime((Constants.START_TIME) *1000,DateUtils.LONG_FORMAT);
    private String year;
    private String daysToNextYear;
    private String heightOfNextYear;
//
//    public static void main(String[] args) throws IOException {
//
//        long height1 = 2000000;
//        ChainInfo freecashInfo = new ChainInfo();
//        freecashInfo.infoBest("http://localhost:8332","username","password");
//        System.out.println(freecashInfo.toNiceJson());
//
//        ChainInfo freecashInfo1 = new ChainInfo();
//        NewEsClient newEsClient = new NewEsClient();
//        ElasticsearchClient esClient = newEsClient.getSimpleEsClient();
//        freecashInfo1.infoByHeight(height1,esClient);
//        System.out.println(freecashInfo1.toNiceJson());
//
//        Map<Long, String> timeDiffMap = difficultyHistory(0, 1704321137,100 ,esClient);
//        JsonTools.gsonPrint(timeDiffMap);
//
//        Map<Long, String> timeHashRateMap = hashRateHistory(0, 1704321137,18 ,esClient);
//        JsonTools.gsonPrint(timeHashRateMap);
//
//        Map<Long, Long> blockTimefMap = blockTimeHistory(0, 1704321137,1000 ,esClient);
//
//        System.out.println(timeDiffMap.size());
//        System.out.println(timeHashRateMap.size());
//        System.out.println(blockTimefMap.size());
//
//        newEsClient.shutdownClient();
//    }

    public String toNiceJson(){
        return JsonUtils.toNiceJson(this);
    }

    public String getTotalSupply() {
        return totalSupply;
    }

    public void setTotalSupply(String totalSupply) {
        this.totalSupply = totalSupply;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getHashRate() {
        return hashRate;
    }

    public void setHashRate(String hashRate) {
        this.hashRate = hashRate;
    }

    public long estimateHeight() {
        return Long.parseLong(height);
    }

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public String getChainSize() {
        return chainSize;
    }

    public void setChainSize(String chainSize) {
        this.chainSize = chainSize;
    }

    public String getYear() {
        return year;
    }

    public void setYear(String year) {
        this.year = year;
    }

    public String getCoinbaseMine() {
        return coinbaseMine;
    }

    public void setCoinbaseMine(String coinbaseMine) {
        this.coinbaseMine = coinbaseMine;
    }

    public String getCoinbaseFund() {
        return coinbaseFund;
    }

    public void setCoinbaseFund(String coinbaseFund) {
        this.coinbaseFund = coinbaseFund;
    }

    public String getDaysToNextYear() {
        return daysToNextYear;
    }

    public void setDaysToNextYear(String daysToNextYear) {
        this.daysToNextYear = daysToNextYear;
    }

    public String getHeightOfNextYear() {
        return heightOfNextYear;
    }

    public void setHeightOfNextYear(String heightOfNextYear) {
        this.heightOfNextYear = heightOfNextYear;
    }

    public String getDaysPerYear() {
        return Constants.DAYS_PER_YEAR_STR;
    }

    public String getMineMutualDays() {
        return Constants.MINE_MATURE_DAYS;
    }

    public String getFundMutualDays() {
        return Constants.FUND_MATURE_DAYS;
    }

    public String getBlockTimeMinute() {
        return Constants.BLOCK_TIME_MINUTE;
    }

    public String getInitialCoinbaseMine() {
        return Constants.INITIAL_COINBASE_MINE;
    }
    public String getInitialCoinbaseFund() {
        return Constants.INITIAL_COINBASE_FUND;
    }

    public String getMineReductionRatio() {
        return Constants.MINE_REDUCTION_RATIO;
    }

    public long getStartTime() {
        return Constants.START_TIME;
    }

    public String getGenesisBlockId() {
        return genesisBlockId;
    }

    public String getCirculating() {
        return circulating;
    }

    public void setCirculating(String circulating) {
        this.circulating = circulating;
    }

    public String getMineMatureDays() {
        return Constants.MINE_MATURE_DAYS;
    }

    public String getFundMatureDays() {
        return Constants.FUND_MATURE_DAYS;
    }

    public String getFundReductionRatio() {
        return Constants.FUND_REDUCTION_RATIO;
    }

    public String getReducePerBlocks() {
        return Constants.REDUCE_PER_BLOCKS;
    }

    public String getReductionStopsAtHeight() {
        return Constants.REDUCTION_STOPS_AT_HEIGHT;
    }

    public String getStableAnnualIssuance() {
        return Constants.STABLE_ANNUAL_ISSUANCE;
    }

    public String getHeight() {
        return height;
    }

    public void setHeight(String height) {
        this.height = height;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
