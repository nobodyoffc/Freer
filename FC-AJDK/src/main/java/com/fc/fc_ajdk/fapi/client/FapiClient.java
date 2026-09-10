package com.fc.fc_ajdk.fapi.client;

import static com.fc.fc_ajdk.constants.FieldNames.FREER;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.ISSUED_CASHES_OWNER;
import static com.fc.fc_ajdk.constants.FieldNames.PUBKEY;
import static com.fc.fc_ajdk.constants.IndicesNames.TX;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fc.fc_ajdk.client.ApiClient;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.core.crypto.Hash;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.apipData.Utxo;

import com.fc.fc_ajdk.data.fcData.DiskItem;
import com.fc.fc_ajdk.data.fcData.DockItem;
import com.fc.fc_ajdk.data.fchData.*;
import com.fc.fc_ajdk.fapi.MapEntry;
import com.fc.fc_ajdk.data.feipData.*;
import com.fc.fc_ajdk.fapi.message.FapiRequest;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.fapi.message.UnifiedCodec;
import com.fc.fc_ajdk.fudp.message.ResponseMessage;
import com.fc.fc_ajdk.fudp.message.PongMessage;
import com.fc.fc_ajdk.fudp.node.FudpNode;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.utils.ObjectUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FAPI 客户端，封装 FUDP 请求，提供便捷的查询方法
 * 参考 ApipClient 的设计，但使用 FUDP 协议而非 HTTP
 * 
 * 实现 ApiClient 接口以提供统一的客户端访问方式
 */
public class FapiClient implements ApiClient {
    private static final String TAG = "FapiClient";
    
    // Via configuration constants (originally from FapiBalanceManager)
    public static final String KEY_DEFAULT_VIA = "defaultVia";
    public static final String DEFAULT_VIA = "freer";
    public static final String[] DEFAULT_BOOTSTRAP_APIS = new String[]{
//            "fudp://10.0.2.2:8500",

            "fudp://fapi.cid.cash:8500",
            "fudp://fapi.apip.cash:8500"
    };
    public ApiAccount apiAccount;
    public static final int DEFAULT_PORT = 8500;
    public static final long DEFAULT_HELLO_TIMEOUT_MS = 5000;
    public static final long DEFAULT_PING_TIMEOUT_MS = 5000;
    
    private final FudpNode fudpNode;
    private final String servicePeerId;  // FAPI 服务提供者的 Peer ID (FID)
    private final String serviceSid;      // Service ID (链上注册的交易ID)
    private final long requestTimeoutSeconds;
    private final BalanceVerifier balanceVerifier;
    private final Map<String, Object> settingMap;  // 配置映射
    private AutoRechargeManager autoRechargeManager;
    private final String via;  // 消费渠道标识
    private String serverUrl;  // URL of the FAPI server this client is connected to


    /**
     * 提供私钥的回调接口，用于自动充值功能
     */
    @FunctionalInterface
    public interface PrikeyProvider {
        byte[] decryptPrikey();
    }

    /**
     * 钱包UTXO提供者。让库内的自动花费（如自动充值）与钱包共用同一个
     * 本地、mempool感知的cash数据库，避免与钱包自建的交易双花冲突
     * （-26 txn-mempool-conflict）。
     */
    public interface UtxoProvider {
        /**
         * 从钱包本地cash库中选取足以覆盖amountFch加手续费的可花费cash。
         * fid不匹配当前钱包身份或余额不足时返回null。
         */
        List<Cash> selectInputs(String fid, double amountFch);

        /**
         * 交易广播成功后的记账回调：从本地库移除已花费的输入并登记找零。
         */
        void onTxSent(String fid, String signedTxHex);
    }

    // 用于自动充值的回调接口
    private PrikeyProvider prikeyProvider;
    private UtxoProvider utxoProvider;
    private String mainFid;
    
    private FapiResponse lastResponse;
    private Exception lastError;
    private HomeServiceResolver homeServiceResolver;
    private Long lastBalance;
    private Long lastBalanceSeq;
    private Long lastBalanceTimestampMillis;
    private Long lastCharged;
    private Long lastSuccessfulRequestTimestampMillis;
    private volatile Long cachedBestHeight;
    private Long maxDataSize;
    private static final long CONNECTION_STALE_THRESHOLD_MS = 5 * 60 * 1000; // 5 minutes
    private static final ThreadLocal<Boolean> inAutoRechargeFlow = ThreadLocal.withInitial(() -> false);
//    private final ApiEvent apiEvent = new ApiEvent();  // 兼容ApipClient
    
    public FapiClient(FudpNode fudpNode, String servicePeerId, String serviceSid) {
        this(fudpNode, servicePeerId, serviceSid, 30, null, null, null);
    }
    
    public FapiClient(FudpNode fudpNode, String servicePeerId, String serviceSid, long requestTimeoutSeconds) {
        this(fudpNode, servicePeerId, serviceSid, requestTimeoutSeconds, null, null, null);
    }

    public FapiClient(FudpNode fudpNode, String servicePeerId, String serviceSid, 
                      long requestTimeoutSeconds, Map<String, Object> settingMap) {
        this(fudpNode, servicePeerId, serviceSid, requestTimeoutSeconds, settingMap, null, null);
    }
    
    /**
     * 完整构造函数
     * @param fudpNode FUDP节点
     * @param servicePeerId 服务提供者的PeerID
     * @param serviceSid 服务ID
     * @param requestTimeoutSeconds 请求超时秒数
     * @param settingMap 配置映射
     * @param mainFid 主FID（用于自动充值）
     * @param prikeyProvider 私钥提供者（用于自动充值）
     */
    public FapiClient(FudpNode fudpNode, String servicePeerId, String serviceSid, 
                      long requestTimeoutSeconds, Map<String, Object> settingMap,
                      String mainFid, PrikeyProvider prikeyProvider) {
        this.fudpNode = fudpNode;
        this.servicePeerId = servicePeerId;
        this.serviceSid = serviceSid;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.settingMap = settingMap;
        this.mainFid = mainFid;
        this.prikeyProvider = prikeyProvider;
        this.balanceVerifier = BalanceVerifier.fromSettingMap(settingMap);
        this.autoRechargeManager = (settingMap != null && mainFid != null && prikeyProvider != null) 
            ? new AutoRechargeManager(this, settingMap, mainFid, prikeyProvider) 
            : null;
        
        // 从 settingMap 读取 via 配置
        if (settingMap != null) {
            Object viaObj = settingMap.get(KEY_DEFAULT_VIA);
            this.via = viaObj != null ? viaObj.toString() : DEFAULT_VIA;
        } else {
            this.via = DEFAULT_VIA;
        }
    }
    
    /**
     * 发送 FapiRequest 请求（新架构核心方法）
     */
    public FapiResponse request(FapiRequest fapiRequest) {
        try {
            if (balanceVerifier != null && balanceVerifier.isStopped()) {
                lastError = new IllegalStateException("Balance verification stopped due to drift");
                FapiResponse errorResp = buildErrorResponse(403, "Balance verification stopped");
                this.lastResponse = errorResp;
                return errorResp;
            }

            // The FudpNode is shared and owned by the Setting, not this client. If it has
            // been stopped (session re-lock, FID switch, client-group teardown) while this
            // client is still referenced, its scheduler is terminated and fudpNode.request()
            // throws RejectedExecutionException from scheduleIdleCheck. Fail fast with a
            // clear, actionable error instead of surfacing that raw executor exception.
            if (fudpNode == null || !fudpNode.isRunning()) {
                this.lastError = new IllegalStateException("FUDP node not running");
                FapiResponse errorResp = buildErrorResponse(503,
                        "Connection unavailable: network node not running. Please reconnect.");
                this.lastResponse = errorResp;
                TimberLogger.w(TAG, "FAPI request on stopped FUDP node: api=" +
                        (fapiRequest != null ? fapiRequest.getApi() : "null"));
                return errorResp;
            }
            
            // 自动填充 via（消费渠道）
            if (fapiRequest.getVia() == null && via != null) {
                fapiRequest.setVia(via);
            }
            
            // 使用统一编码格式编码请求
            byte[] requestData = UnifiedCodec.encodeRequest(fapiRequest);
            CompletableFuture<ResponseMessage> future = fudpNode.request(servicePeerId, serviceSid, requestData);
            
            ResponseMessage response = future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
            
            // Parse response body (server always encodes FapiResponse via UnifiedCodec, even for errors)
            FapiResponse fapiResponse = null;
            if (response.getData() != null && response.getData().length > 0) {
                fapiResponse = parseUnifiedResponse(response.getData());
            }

            // FUDP uses STATUS_SUCCESS = 0 for success, not HTTP 200
            if (response.getStatusCode() != ResponseMessage.STATUS_SUCCESS) {
                this.lastError = new IOException("Request failed with status: " + response.getStatusCode());
                if (fapiResponse != null && fapiResponse.getCode() != null) {
                    updateBalanceFromResponse(fapiResponse);
                    this.lastResponse = fapiResponse;
                } else {
                    this.lastResponse = buildErrorResponse(response.getStatusCode(),
                        response.getData() != null ? new String(response.getData()) : "Unknown error");
                }
                return lastResponse;
            }
            
            if (fapiResponse == null) {
                fapiResponse = buildErrorResponse(500, "Empty response");
            }
            updateBalanceFromResponse(fapiResponse);
            this.lastResponse = fapiResponse;
            this.lastError = null;
            
            // Track successful request timestamp for connection status
            if (fapiResponse.isSuccess()) {
                lastSuccessfulRequestTimestampMillis = System.currentTimeMillis();
            }
            
            return fapiResponse;
            
        } catch (TimeoutException e) {
            this.lastError = e;
            FapiResponse errorResp = buildErrorResponse(408, "Request timeout after " + requestTimeoutSeconds + "s");
            this.lastResponse = errorResp;
            TimberLogger.w(TAG, "FAPI request timeout (" + requestTimeoutSeconds + "s): api=" +
                    (fapiRequest != null ? fapiRequest.getApi() : "null"));
            return errorResp;
        } catch (Exception e) {
            this.lastError = e;
            FapiResponse errorResp = buildErrorResponse(500, e.getMessage());
            this.lastResponse = errorResp;
            TimberLogger.e(TAG, "Error sending FAPI request: api=" +
                    (fapiRequest != null ? fapiRequest.getApi() : "null"), e);
            return errorResp;
        }
    }
    
    /**
     * 解析统一协议响应
     * 兼容统一协议和纯JSON格式
     */
    private FapiResponse parseUnifiedResponse(byte[] data) {
        if (data == null || data.length == 0) {
            return buildErrorResponse(500, "Empty response");
        }
        
        // 尝试使用统一协议解析
        if (UnifiedCodec.isUnifiedProtocol(data)) {
            try {
                UnifiedCodec.UnifiedResponse unified = UnifiedCodec.decodeResponse(data);
                return unified.response();
            } catch (Exception e) {
                TimberLogger.d(TAG, "Failed to parse as unified protocol, falling back to JSON: %s", e.getMessage());
            }
        }
        
        // 回退到纯JSON解析
        try {
            String responseJson = new String(data, StandardCharsets.UTF_8);
            return JsonUtils.fromJson(responseJson, FapiResponse.class);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to parse response: %s", e.getMessage());
            return buildErrorResponse(500, "Failed to parse response");
        }
    }


    @NonNull
    public Map<String, String> getFidCidMap(List<String> missedFidList) {
        Map<String, Freer> result = freerByIds(missedFidList);
        Map<String,String> fidCidMap = new HashMap<>();
        if (result == null) {
            return fidCidMap;
        }
        for(String fid :result.keySet()){
            Freer freer = result.get(fid);
            if(freer!=null && freer.getCid()!=null)
                fidCidMap.put(fid,freer.getCid());
        }
        return fidCidMap;
    }

    /**
     * Resolve the owner FID of a CID that has ever been used on-chain by searching
     * the freer index on the {@code usedCids} field. A new CID is only available if
     * it matches no FID's used CIDs. Use this to test CID availability — unlike
     * {@link #getFidCidMap(List)}, which looks up by FID id and cannot do so.
     *
     * @param cid the CID to look up
     * @return the owner FID, or {@code null} if no FID has used this CID
     */
    public String getFidByUsedCid(String cid) {
        if (cid == null || cid.isEmpty()) {
            return null;
        }
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(IndicesNames.FREER);
        fcdsl.addNewFilter().addNewTerms().addNewFields(FieldNames.USED_CIDS).addNewValues(cid);
        List<Freer> result = freerSearch(fcdsl);
        if (result == null || result.isEmpty()) {
            return null;
        }
        for (Freer freer : result) {
            if (freer != null && freer.getUsedCids() != null && freer.getUsedCids().contains(cid)) {
                return freer.getId();
            }
        }
        return null;
    }

    public List<Multisig> myMultisigs(String myFid) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(IndicesNames.MULTISIG);
        fcdsl.addNewFilter().addNewTerms().addNewFields(FieldNames.FIDS).addNewValues(myFid);
        return entitySearch(IndicesNames.MULTISIG,fcdsl, Multisig.class);
    }

    public List<Freer> myServants(String myFid) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(IndicesNames.FREER);
        fcdsl.addNewFilter().addNewTerms().addNewFields(FieldNames.MASTER).addNewValues(myFid);
        return entitySearch(IndicesNames.FREER,fcdsl, Freer.class);
    }

    public FreerHist masterAnnouncement(String myFid) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(IndicesNames.FREER_HISTORY);
        fcdsl.addNewQuery().addNewExists(FieldNames.MASTER);
        fcdsl.addNewFilter().addNewTerms().addNewFields(FieldNames.SIGNER).addNewValues(myFid);
        List<FreerHist> result = entitySearch(IndicesNames.FREER_HISTORY, fcdsl, FreerHist.class);
        if(result==null || result.isEmpty())
            return null;
        return result.get(0);
    }

    public String getPubkey(String fid) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(FREER);
        fcdsl.addIds(fid);
        fcdsl.addFields(PUBKEY);

        FapiResponse response = query("base.getByIds", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        Map<String, Freer> map = ObjectUtils.objectToMap(response.getData(), String.class, Freer.class);
        if (map == null || map.size() != 1) {
            return null;
        }
        Freer freer = map.get(fid);
        if(freer ==null)return null;
        return freer.getPubkey();
    }

    public Freer freerById(String fid) {
        java.util.Map<String, Freer> freerMap = freerByIds(Collections.singletonList(fid));
        return (freerMap != null) ? freerMap.get(fid) : null;
    }

    public Map<String, Nobody> checkNobodies(ArrayList<String> fids) {
        if (fids == null || fids.isEmpty()) {
            return new HashMap<>();
        }
        return entityByIds(IndicesNames.NOBODY, Nobody.class, fids);
    }
    
    /**
     * 发送查询类请求
     * @param api API名称，如 "base.search"
     * @param fcdsl 查询参数
     */
    public FapiResponse query(String api, Fcdsl fcdsl) {
        return request(FapiRequest.query(api, fcdsl));
    }
    
    /**
     * 发送查询类请求（带费用限制）
     * @param api API名称，如 "base.search"
     * @param fcdsl 查询参数
     * @param maxCost 最大费用（聪），超过此限制服务端将拒绝请求
     */
    public FapiResponse query(String api, Fcdsl fcdsl, long maxCost) {
        return request(FapiRequest.query(api, fcdsl).withMaxCost(maxCost));
    }
    
    /**
     * 发送操作类请求
     * @param api API名称，如 "base.broadcastTx"
     * @param params 操作参数
     */
    public FapiResponse operation(String api, Object params) {
        return request(FapiRequest.operation(api, params));
    }
    
    /**
     * 发送操作类请求（带费用限制）
     * @param api API名称，如 "base.broadcastTx"
     * @param params 操作参数
     * @param maxCost 最大费用（聪），超过此限制服务端将拒绝请求
     */
    public FapiResponse operation(String api, Object params, long maxCost) {
        return request(FapiRequest.operation(api, params).withMaxCost(maxCost));
    }
    
    /**
     * 发送简单请求（无参数）
     * @param api API名称，如 "base.health"
     */
    public FapiResponse simple(String api) {
        return request(FapiRequest.simple(api));
    }
    
    /**
     * 发送简单请求（带费用限制）
     * @param api API名称，如 "base.health"
     * @param maxCost 最大费用（聪），超过此限制服务端将拒绝请求
     */
    public FapiResponse simple(String api, long maxCost) {
        return request(FapiRequest.simple(api).withMaxCost(maxCost));
    }
    
    /**
     * 根据实体名和 ID 列表查询
     */
    public <T> Map<String, T> entityByIds(String entityName, Class<T> clazz, List<String> ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(entityName);
        fcdsl.addIds(ids);
        
        FapiResponse response = query("base.getByIds", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, clazz);
    }

    public <T> T entityById(String entityName, Class<T> clazz, String id){
        Map<String, T> tMap = entityByIds(entityName, clazz, List.of(id));
        if(tMap==null || tMap.isEmpty())return null;
        return tMap.get(id);
    }

    /**
     * 根据实体名搜索
     */
    public <T> List<T> entitySearch(String entityName, Fcdsl fcdsl, Class<T> clazz) {
        fcdsl.setEntity(entityName.toLowerCase());
        FapiResponse response = query("base.search", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }

        return ObjectUtils.objectToList(response.getData(), clazz);
    }

    public FapiResponse search(Fcdsl fcdsl) {
        return query("base.search", fcdsl);
    }

    // ========== Block 相关方法 ==========
    
    public Block bestBlock() {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setEntity(IndicesNames.BLOCK);
        fcdsl.addSort(FieldNames.HEIGHT, Values.DESC);
        fcdsl.addSize(1);
        
        List<Block> blocks = entitySearch(IndicesNames.BLOCK, fcdsl, Block.class);
        return blocks != null && !blocks.isEmpty() ? blocks.get(0) : null;
    }
    
    public Long getBestHeight() {
        Block block = bestBlock();
        if (block != null && block.getHeight() != null) {
            cachedBestHeight = block.getHeight();
            return block.getHeight();
        }
        // The fresh query failed (e.g. transient network error or a call from
        // the main thread); fall back to the height carried by earlier responses.
        return cachedBestHeight;
    }

    /**
     * The height carried by the latest response, without querying — safe on the main thread.
     * It can only lag the chain.
     */
    public Long getCachedBestHeight() {
        return cachedBestHeight;
    }

    public Long bestHeight() {
        return getBestHeight();
    }

    /**
     * 获取链信息，可选指定高度
     */
    public FchChainInfo chainInfo() {
        return chainInfo(null);
    }

    public FchChainInfo chainInfo(Long height) {
        Map<String, Object> params = new HashMap<>();
        if (height != null) {
            params.put(FieldNames.HEIGHT, height);
        }

        FapiResponse response = operation("base.chainInfo", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }

        return ObjectUtils.objectToClass(response.getData(), FchChainInfo.class);
    }

    public Map<Long, Long> blockTimeHistory(Long startTime, Long endTime, Integer count) {
        Map<String, Object> params = buildHistoryParams(startTime, endTime, count);
        return sendHistoryRequest("base.blockTimeHistory", params, Long.class, Long.class);
    }

    public Map<Long, String> difficultyHistory(Long startTime, Long endTime, Integer count) {
        Map<String, Object> params = buildHistoryParams(startTime, endTime, count);
        return sendHistoryRequest("base.difficultyHistory", params, Long.class, String.class);
    }

    public Map<Long, String> hashRateHistory(Long startTime, Long endTime, Integer count) {
        Map<String, Object> params = buildHistoryParams(startTime, endTime, count);
        return sendHistoryRequest("base.hashRateHistory", params, Long.class, String.class);
    }
    
    /**
     * 获取所有实体的列表及其文档数量
     * @return Map<String, String> 实体名 -> 文档数量
     */
    public Map<String, String> totals() {
        FapiResponse response = simple("base.totals");
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return ObjectUtils.objectToMap(response.getData(), String.class, String.class);
    }
    
    /**
     * 健康检查
     */
    public Map<String, Object> health() {
        FapiResponse response = simple("base.health");
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return ObjectUtils.objectToMap(response.getData(), String.class, Object.class);
    }
    
    // ========== Wallet APIs ==========
    
    /**
     * 根据 FID 列表获取余额
     * @param fids FID 列表
     * @return Map<FID, Balance> FID -> 余额（单位：satoshi）
     */
    public Map<String, Long> balanceByIds(String... fids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(fids);
        
        FapiResponse response = query("base.balanceByIds", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return ObjectUtils.objectToMap(response.getData(), String.class, Long.class);
    }
    
    /**
     * 广播交易
     * @param rawTx 原始交易十六进制
     * @return 交易ID
     */
    public String broadcastTx(String rawTx) {
        Map<String, Object> params = new HashMap<>();
        params.put("rawTx", rawTx);
        
        FapiResponse response = operation("base.broadcastTx", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            if(response!=null)
                return response.getMessage();
            else return null;
        }
        return response.getData().toString();
    }
    
    /**
     * 解码交易
     * @param rawTx 原始交易十六进制
     * @return 解码后的交易对象
     */
    public Object decodeTx(String rawTx) {
        Map<String, Object> params = new HashMap<>();
        params.put("rawTx", rawTx);
        
        FapiResponse response = operation("base.decodeTx", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return response.getData();
    }
    
    /**
     * 估算手续费
     * @param nBlocks 区块数（可选，如果指定则使用 estimatesmartfee，否则使用 estimatefee）
     * @return 手续费率（单位：FCH/KB）
     */
    public Double estimateFee(Integer nBlocks) {
        Map<String, Object> params = new HashMap<>();
        if (nBlocks != null && nBlocks > 0) {
            params.put("nBlocks", nBlocks);
        }
        
        FapiResponse response = operation("base.estimateFee", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        Object data = response.getData();
        if (data instanceof Number) {
            return ((Number) data).doubleValue();
        }
        return null;
    }
    
    /**
     * 估算手续费（使用默认参数，不指定区块数）
     * @return 手续费率（单位：FCH/KB）
     */
    public Double estimateFee() {
        return estimateFee(null);
    }
    
    /**
     * 获取有效的 UTXO（简单查询）
     * @param fcdsl FCDSL 查询条件
     * @return Cash 列表
     */
    public List<Cash> cashValid(Fcdsl fcdsl) {
        FapiResponse response = query("base.cashValid", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return ObjectUtils.objectToList(response.getData(), Cash.class);
    }
    
    /**
     * 获取有效的 UTXO（用于交易）
     * @param fid FID
     * @param amount 需要的金额（单位：FCH，可为null）
     * @param cd 需要的 CD（可为null）
     * @param sinceHeight 起始高度（可为null）
     * @param outputSize 输出数量
     * @param msgSize 消息大小
     * @return Cash 列表
     */
    public List<Cash> cashValid(String fid, Double amount, Long cd, Long sinceHeight, 
                                Integer outputSize, Integer msgSize) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(fid);
        
        Map<String, Object> params = new HashMap<>();
        params.put("fid", fid);
        if (amount != null) params.put(FieldNames.AMOUNT, amount);
        if (cd != null) params.put(FieldNames.CD, cd);
        if (sinceHeight != null) params.put(FieldNames.SINCE_HEIGHT, sinceHeight);
        if (outputSize != null) params.put("outputSize", outputSize);
        if (msgSize != null) params.put("msgSize", msgSize);
        
        FapiRequest request = FapiRequest.query("base.cashValid", fcdsl);
        request.setParams(params);
        
        FapiResponse response = request(request);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return ObjectUtils.objectToList(response.getData(), Cash.class);
    }
    
    /**
     * 获取 UTXO
     * @param address FID 地址
     * @param amount 需要的金额（单位：FCH，可为null）
     * @param cd 需要的 CD（可为null）
     * @return Utxo 列表
     */
    public List<Utxo> getUtxo(String address, Double amount, Long cd) {
        Map<String, Object> params = new HashMap<>();
        params.put(FieldNames.ADDRESS, address);
        if (amount != null) params.put(FieldNames.AMOUNT, amount);
        if (cd != null) params.put(FieldNames.CD, cd);
        
        FapiResponse response = operation("base.getUtxo", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return ObjectUtils.objectToList(response.getData(), Utxo.class);
    }
    

    /**
     * 搜索 Cash（通用搜索方法）
     * @param fcdsl 查询条件
     * @return Cash 列表
     */
    public List<Cash> cashSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.CASH, fcdsl, Cash.class);
    }
    
    /**
     * 通过 ID 列表获取 Cash
     * @param ids Cash ID 列表
     * @return Map<ID, Cash>
     */
    public Map<String, Cash> cashByIds(List<String> ids) {
        return entityByIds(IndicesNames.CASH, Cash.class, ids);
    }
    
    /**
     * 搜索 OpReturn
     * @param fcdsl 查询条件
     * @return OpReturn 列表
     */
    public List<OpReturn> opReturnSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.OPRETURN, fcdsl, OpReturn.class);
    }
    
    /**
     * 搜索 Block
     * @param fcdsl 查询条件
     * @return Block 列表
     */
    public List<Block> blockSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.BLOCK, fcdsl, Block.class);
    }
    
    /**
     * 通过 ID 列表获取 Block
     * @param ids Block ID 列表
     * @return Map<ID, Block>
     */
    public Map<String, Block> blockByIds(List<String> ids) {
        return entityByIds(IndicesNames.BLOCK, Block.class, ids);
    }
    
    /**
     * 搜索 Tx
     * @param fcdsl 查询条件
     * @return Tx 列表
     */
    public List<Tx> txSearch(Fcdsl fcdsl) {
        return entitySearch(TX, fcdsl, Tx.class);
    }
    
    /**
     * 通过 ID 列表获取 Tx
     * @param ids Tx ID 列表
     * @return Map<ID, Tx>
     */
    public Map<String, Tx> txByIds(List<String> ids) {
        return entityByIds(TX, Tx.class, ids);
    }
    
    // ==================== FEIP 相关方法 ====================
    
    /**
     * 搜索 Contact
     */
    public List<Contact> contactSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.CONTACT, fcdsl, Contact.class);
    }
    
    public Map<String, Contact> contactByIds(List<String> ids) {
        return entityByIds(IndicesNames.CONTACT, Contact.class, ids);
    }
    
    /**
     * 搜索 Mail
     */
    public List<Mail> mailSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.MAIL, fcdsl, Mail.class);
    }
    
    public Map<String, Mail> mailByIds(List<String> ids) {
        return entityByIds(IndicesNames.MAIL, Mail.class, ids);
    }
    
    /**
     * 搜索 Secret
     */
    public List<Secret> secretSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.SECRET, fcdsl, Secret.class);
    }
    
    public Map<String, Secret> secretByIds(List<String> ids) {
        return entityByIds(IndicesNames.SECRET, Secret.class, ids);
    }
    
    /**
     * 搜索 Proof
     */
    public List<Proof> proofSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.PROOF, fcdsl, Proof.class);
    }
    
    public Map<String, Proof> proofByIds(List<String> ids) {
        return entityByIds(IndicesNames.PROOF, Proof.class, ids);
    }
    
    /**
     * 搜索 FreerHist
     */
    public List<FreerHist> cidHistSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.FREER_HISTORY, fcdsl, FreerHist.class);
    }
    
    /**
     * 搜索 Multisig
     */
    public List<Multisig> multisigSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.MULTISIG, fcdsl, Multisig.class);
    }

    public Map<String, Multisig> multisigByIds(List<String> ids) {
        return entityByIds(IndicesNames.MULTISIG, Multisig.class, ids);
    }
    
    /**
     * 搜索 Service
     */
    public List<Service> serviceSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.SERVICE, fcdsl, Service.class);
    }
    
    public Map<String, Service> serviceByIds(List<String> ids) {
        return entityByIds(IndicesNames.SERVICE, Service.class, ids);
    }

    public Service serviceById(String id) {
        Map<String, Service> result = entityByIds(IndicesNames.SERVICE, Service.class, Collections.singletonList(id));
        if(result==null)return null;
        return result.get(id);
    }
    
    /**
     * 搜索 Nobody
     */
    public List<Nobody> nobodySearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.NOBODY, fcdsl, Nobody.class);
    }
    
    public Map<String, Nobody> nobodyByIds(List<String> ids) {
        return entityByIds(IndicesNames.NOBODY, Nobody.class, ids);
    }
    
    /**
     * 搜索 Protocol
     */
    public List<Protocol> protocolSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.PROTOCOL, fcdsl, Protocol.class);
    }
    
    public Map<String, Protocol> protocolByIds(List<String> ids) {
        return entityByIds(IndicesNames.PROTOCOL, Protocol.class, ids);
    }

    public Protocol protocolById(String id) {
        Map<String, Protocol> map = protocolByIds(Collections.singletonList(id));
        return map != null ? map.get(id) : null;
    }
    
    /**
     * 搜索 Code
     */
    public List<Code> codeSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.CODE, fcdsl, Code.class);
    }
    
    public Map<String, Code> codeByIds(List<String> ids) {
        return entityByIds(IndicesNames.CODE, Code.class, ids);
    }

    public Code codeById(String id) {
        Map<String, Code> map = codeByIds(Collections.singletonList(id));
        return map != null ? map.get(id) : null;
    }
    
    /**
     * 搜索 App
     */
    public List<App> appSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.APP, fcdsl, App.class);
    }
    
    public Map<String, App> appByIds(List<String> ids) {
        return entityByIds(IndicesNames.APP, App.class, ids);
    }
    
    public App appById(String id) {
        Map<String, App> map = appByIds(Collections.singletonList(id));
        return map != null ? map.get(id) : null;
    }
    
    /**
     * 搜索 Square (FEIP index "square")
     */
    public List<Square> squareSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.SQUARE, fcdsl, Square.class);
    }
    
    public Map<String, Square> squareByIds(List<String> ids) {
        return entityByIds(IndicesNames.SQUARE, Square.class, ids);
    }
    
    /**
     * 搜索 Team
     */
    public List<Team> teamSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.TEAM, fcdsl, Team.class);
    }
    
    public Map<String, Team> teamByIds(List<String> ids) {
        return entityByIds(IndicesNames.TEAM, Team.class, ids);
    }
    
    /**
     * 搜索 Box
     */
    public List<Box> boxSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.BOX, fcdsl, Box.class);
    }
    
    public Map<String, Box> boxByIds(List<String> ids) {
        return entityByIds(IndicesNames.BOX, Box.class, ids);
    }
    
    /**
     * 搜索 Statement
     */
    public List<Statement> statementSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.STATEMENT, fcdsl, Statement.class);
    }
    
    public Map<String, Statement> statementByIds(List<String> ids) {
        return entityByIds(IndicesNames.STATEMENT, Statement.class, ids);
    }
    
    /**
     * 搜索 Token
     */
    public List<Token> tokenSearch(Fcdsl fcdsl) {
        return entitySearch(IndicesNames.TOKEN, fcdsl, Token.class);
    }
    
    public Map<String, Token> tokenByIds(List<String> ids) {
        return entityByIds(IndicesNames.TOKEN, Token.class, ids);
    }
    
    /**
     * 搜索 Freer（FID详情）
     */
    public List<Freer> freerSearch(Fcdsl fcdsl) {
        return entitySearch("freer", fcdsl, Freer.class);
    }
    
    public Map<String, Freer> freerByIds(List<String> fids) {
        if (fids == null || fids.isEmpty()) {
            return new HashMap<>();
        }
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(fids);
        FapiResponse response = query("base.freerByIds", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, Freer.class);
    }
    
    /**
     * 获取未确认交易信息
     * @param fids FID 列表
     * @return Map<FID, UnconfirmedInfo>
     */
    public Map<String, com.fc.fc_ajdk.data.apipData.UnconfirmedInfo> unconfirmed(String... fids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(fids);
        
        FapiResponse response = query("base.unconfirmed", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        
        return ObjectUtils.objectToMap(response.getData(), String.class, com.fc.fc_ajdk.data.apipData.UnconfirmedInfo.class);
    }
    
    /**
     * 获取未确认的 Cash
     * @param fids FID 列表（可选）
     * @return Map<FID, List<Cash>>
     */
    public Map<String, List<Cash>> unconfirmedCashes(List<String> fids) {
        Fcdsl fcdsl = new Fcdsl();
        if (fids != null && !fids.isEmpty()) {
            fcdsl.addIds(fids);
        }

        FapiResponse response = query("base.unconfirmedCashes", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }

        return ObjectUtils.objectToMapWithListValues(response.getData(), String.class, Cash.class);
    }
    
    
    // ========== 工具方法 ==========

    private Map<String, Object> buildHistoryParams(Long startTime, Long endTime, Integer count) {
        Map<String, Object> params = new HashMap<>();
        if (startTime != null) {
            params.put(FieldNames.START_TIME, startTime);
        }
        if (endTime != null) {
            params.put(FieldNames.END_TIME, endTime);
        }
        int finalCount = (count == null || count <= 0) ? (int) FchChainInfo.DEFAULT_COUNT : count;
        params.put(FieldNames.COUNT, finalCount);
        return params;
    }

    private <K, V> Map<K, V> sendHistoryRequest(String api, Map<String, Object> params, Class<K> keyClass, Class<V> valueClass) {
        FapiResponse response = operation(api, params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), keyClass, valueClass);
    }
    
    private FapiResponse buildErrorResponse(int code, String message) {
        FapiResponse response = new FapiResponse();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }

    /**
     * Build the {@link RuntimeException} for a failed response.
     * A locally generated request timeout (HTTP 408) is a transport/client
     * failure, not a verdict from the server, so its already-descriptive
     * message is reported as-is rather than wrapped in a misleading
     * "Server error" prefix.
     */
    private static RuntimeException responseError(FapiResponse response) {
        String message = response != null && response.getMessage() != null
                ? response.getMessage() : "Unknown error";
        Integer code = response != null ? response.getCode() : null;
        if (code != null && code == 408) {
            return new RuntimeException(message);
        }
        return new RuntimeException("Server error: " + message);
    }

    private void updateBalanceFromResponse(FapiResponse response) {
        if (response == null) return;

        // 每个成功响应都携带 bestHeight，缓存它作为 getBestHeight() 的兜底
        if (response.getBestHeight() != null) {
            cachedBestHeight = response.getBestHeight();
        }

        // 跟踪本次请求收费金额
        if (response.getCharged() != null) {
            lastCharged = response.getCharged();
        }
        
        if (response.getBalance() != null) {
            lastBalance = response.getBalance();
            lastBalanceSeq = response.getBalanceSeq();
            lastBalanceTimestampMillis = System.currentTimeMillis();
            if (balanceVerifier != null) {
                BalanceVerifier.Result result = balanceVerifier.observe(lastBalance);
                if (result.getType() == BalanceVerifier.Result.Type.WARN) {
                    TimberLogger.w(TAG, "Balance drift warning: drift={}, threshold={}", result.getDrift(), result.getThreshold());
                } else if (result.getType() == BalanceVerifier.Result.Type.STOP) {
                    TimberLogger.e(TAG, "Balance drift stop: drift={}, threshold={}", result.getDrift(), result.getThreshold());
                }
            }
            
            // 检查是否需要自动充值（异步执行，不阻塞当前请求）
            // Guard against infinite recursion: request() -> updateBalance -> checkRecharge -> getServiceInfo -> entityByIds -> request() -> ...
            if (autoRechargeManager != null && autoRechargeManager.isEnabled()
                    && !Boolean.TRUE.equals(inAutoRechargeFlow.get())) {
                inAutoRechargeFlow.set(true);
                try {
                    autoRechargeManager.checkAndRechargeIfNeeded(lastBalance)
                        .thenAccept(result -> {
                            if (result != null && result.isSuccess()) {
                                TimberLogger.i(TAG, "Auto-recharge completed: txId={}, amount={} FCH", 
                                        result.getTxId(), result.getAmountFch());
                            }
                        })
                        .exceptionally(e -> {
                            TimberLogger.e(TAG, "Auto-recharge failed: {}", e.getMessage());
                            return null;
                        });
                } finally {
                    inAutoRechargeFlow.set(false);
                }
            }
        }
    }
    
    public FapiResponse getLastResponse() {
        return lastResponse;
    }

    public Long getLastBalance() {
        return lastBalance;
    }

    public Long getLastBalanceSeq() {
        return lastBalanceSeq;
    }

    public Long getLastBalanceTimestampMillis() {
        return lastBalanceTimestampMillis;
    }
    
    public Long getLastCharged() {
        return lastCharged;
    }

    public BalanceVerifier getBalanceVerifier() {
        return balanceVerifier;
    }
    
    public AutoRechargeManager getAutoRechargeManager() {
        return autoRechargeManager;
    }
    
    public Map<String, Object> getSettingMap() {
        return settingMap;
    }
    
    public String getMainFid() {
        return mainFid;
    }
    
    public PrikeyProvider getPrikeyProvider() {
        return prikeyProvider;
    }

    public UtxoProvider getUtxoProvider() {
        return utxoProvider;
    }

    /**
     * 注入钱包的UTXO提供者，使自动充值从钱包本地cash库选币并回写记账。
     */
    public void setUtxoProvider(UtxoProvider utxoProvider) {
        this.utxoProvider = utxoProvider;
    }

    /**
     * 设置自动充值所需的信息
     */
    public void setAutoRechargeInfo(String mainFid, PrikeyProvider prikeyProvider) {
        this.mainFid = mainFid;
        this.prikeyProvider = prikeyProvider;
        if (autoRechargeManager == null && settingMap != null && mainFid != null && prikeyProvider != null) {
            autoRechargeManager = new AutoRechargeManager(this, settingMap, mainFid, prikeyProvider);
        }
    }
    
    /**
     * Sets the via FID for recharge transaction OP_RETURN.
     * The recharge TX will include {"via":"<viaFid>"} in its OP_RETURN output.
     */
    public void setAutoRechargeVia(String viaFid) {
        if (autoRechargeManager != null) {
            autoRechargeManager.setViaFid(viaFid);
        }
    }

    /**
     * Pre-cache service info in the auto-recharge manager so it can compute
     * payment amounts even when FAPI requests are blocked by credit limit.
     */
    public void cacheServiceForRecharge(Service service) {
        if (autoRechargeManager != null && service != null) {
            autoRechargeManager.setCachedService(service);
        }
    }

    /**
     * Trigger auto-recharge using the current (possibly negative) balance.
     * Called when credit is exceeded and the client needs to recharge ASAP.
     */
    public void triggerAutoRecharge() {
        if (autoRechargeManager == null || !autoRechargeManager.isEnabled()) {
            TimberLogger.w(TAG, "Auto-recharge not available or disabled");
            return;
        }
        Long balance = lastBalance;
        TimberLogger.i(TAG, "Triggering immediate auto-recharge, balance=%s", balance);
        autoRechargeManager.checkAndRechargeIfNeeded(balance != null ? balance : 0L)
            .thenAccept(result -> {
                if (result != null && result.isSuccess()) {
                    TimberLogger.i(TAG, "Immediate auto-recharge succeeded: txId=%s, amount=%.8f FCH",
                            result.getTxId(), result.getAmountFch());
                } else if (result != null) {
                    TimberLogger.w(TAG, "Immediate auto-recharge failed: %s", result.getMessage());
                }
            })
            .exceptionally(e -> {
                TimberLogger.e(TAG, "Immediate auto-recharge error: %s", e.getMessage());
                return null;
            });
    }
    
    public String getServicePeerId() {
        return servicePeerId;
    }
    
    public String getServiceSid() {
        return serviceSid;
    }
    
    public String getServerUrl() {
        return serverUrl;
    }

    public FudpNode getFudpNode() {
        return fudpNode;
    }

    public HomeServiceResolver getHomeServiceResolver() {
        if (homeServiceResolver == null) {
            homeServiceResolver = new HomeServiceResolver();
        }
        return homeServiceResolver;
    }
    
    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public Long getMaxDataSize() {
        return maxDataSize;
    }

    public void setMaxDataSize(Long maxDataSize) {
        this.maxDataSize = maxDataSize;
    }

    /**
     * Initialize maxDataSize from a Service
     */
    public void initMaxFileSizeFromService(Service service) {
        if (service == null || service.getParams() == null) return;
        String val = service.getMaxDataSize();
        if (val !=null) {
            try {
                this.maxDataSize = Long.parseLong( val);
            } catch (NumberFormatException ignored) {}
        }
    }

    public String getVia() {
        return via;
    }
    
    // ==================== ApiClient Interface Implementation ====================
    
    @Override
    public boolean isConnected() {
        // Check if FudpNode is running and we have a valid service peer
        if (fudpNode == null || !fudpNode.isRunning()) {
            return false;
        }
        if (servicePeerId == null || servicePeerId.isEmpty()) {
            return false;
        }
        // Also check if balance verifier hasn't stopped
        if (balanceVerifier != null && balanceVerifier.isStopped()) {
            return false;
        }
        // If we've never had a successful request, do a quick ping to verify
        if (lastSuccessfulRequestTimestampMillis == null) {
            return ping();
        }
        // If last successful request was too long ago, consider connection stale
        long timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessfulRequestTimestampMillis;
        if (timeSinceLastSuccess > CONNECTION_STALE_THRESHOLD_MS) {
            // Connection might be stale, try a quick ping
            return ping();
        }
        return true;
    }
    
    /**
     * Quick check if basic connectivity is configured (doesn't verify actual connection)
     * Use this for fast checks where you don't need to verify network connectivity
     */
    public boolean isConfigured() {
        if (fudpNode == null || !fudpNode.isRunning()) {
            return false;
        }
        if (servicePeerId == null || servicePeerId.isEmpty()) {
            return false;
        }
        if (balanceVerifier != null && balanceVerifier.isStopped()) {
            return false;
        }
        return true;
    }
    
    /**
     * Get the timestamp of the last successful request
     */
    public Long getLastSuccessfulRequestTimestampMillis() {
        return lastSuccessfulRequestTimestampMillis;
    }
    
    @Override
    public String getServiceId() {
        return serviceSid;
    }
    
    @Override
    public void close() {
        // FapiClient doesn't own the FudpNode, so we don't stop it here
        // Just clear any pending state
        lastResponse = null;
        lastError = null;
        TimberLogger.d(TAG, "FapiClient closed for service: %s", serviceSid);
    }
    
    @Override
    public String getLastError() {
        return lastError != null ? lastError.getMessage() : null;
    }
    
    /**
     * Get the last error exception
     */
    public Exception getLastErrorException() {
        return lastError;
    }
    
    // ==================== 充值相关方法 ====================
    
    /**
     * 手动充值
     * 
     * @param amountFch 充值金额（FCH），如果为 null 则使用默认计算值
     * @return 充值结果
     */
    public AutoRechargeManager.RechargeResult manualRecharge(Double amountFch) {
        if (autoRechargeManager == null) {
            TimberLogger.e(TAG, "AutoRechargeManager not initialized. Settings required.");
            return AutoRechargeManager.RechargeResult.failure("AutoRechargeManager not initialized");
        }
        return autoRechargeManager.manualRecharge(amountFch);
    }
    
    /**
     * 手动充值（使用默认金额）
     * 
     * @return 充值结果
     */
    public AutoRechargeManager.RechargeResult manualRecharge() {
        return manualRecharge(null);
    }
    
    /**
     * 设置充值回调
     * 
     * @param callback 回调函数，接收 (RechargeResult, message)
     */
    public void setRechargeCallback(java.util.function.BiConsumer<AutoRechargeManager.RechargeResult, String> callback) {
        if (autoRechargeManager != null) {
            autoRechargeManager.setRechargeCallback(callback);
        }
    }
    
    /**
     * 检查是否正在充值
     */
    public boolean isRecharging() {
        return autoRechargeManager != null && autoRechargeManager.isRecharging();
    }
    
    /**
     * 获取上次成功充值的交易ID
     */
    public String getLastRechargeTxId() {
        return autoRechargeManager != null ? autoRechargeManager.getLastRechargeTxId() : null;
    }

    /**
     * 使用默认引导 API 发现并连接到 FAPI 服务。
     */
    public static FapiClient bootstrap(FudpNode fudpNode) {
        return bootstrap(fudpNode, null, DEFAULT_HELLO_TIMEOUT_MS, DEFAULT_PING_TIMEOUT_MS);
    }

    public static FapiClient bootstrap(FudpNode fudpNode, Map<String, Object> settingMap) {
        return bootstrap(fudpNode, settingMap, DEFAULT_HELLO_TIMEOUT_MS, DEFAULT_PING_TIMEOUT_MS);
    }

    public static FapiClient bootstrap(FudpNode fudpNode, long helloTimeoutMs, long pingTimeoutMs) {
        return bootstrap(fudpNode, null, helloTimeoutMs, pingTimeoutMs);
    }

    public static FapiClient bootstrap(FudpNode fudpNode, Map<String, Object> settingMap, long helloTimeoutMs, long pingTimeoutMs) {
        List<Endpoint> endpoints = loadDefaultEndpoints();

        // Probe all endpoints concurrently and pick the fastest
        List<CompletableFuture<BootstrapCandidate>> futures = new ArrayList<>();
        for (Endpoint ep : endpoints) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                try {
                    DiscoveryResult result = discoverViaHelloAndPing(
                            fudpNode, ep.host(), ep.port(), helloTimeoutMs, pingTimeoutMs);
                    long elapsed = System.nanoTime() - start;
                    if (result != null && !result.getServices().isEmpty()) {
                        return new BootstrapCandidate(ep, result, elapsed);
                    }
                    TimberLogger.w(TAG, "Bootstrap endpoint {} returned no FAPI services", ep.hostPort());
                } catch (Throwable e) {
                    TimberLogger.w(TAG, "Bootstrap via {} failed: {}", ep.hostPort(), describeException(e));
                }
                return null;
            }));
        }

        // Wait for all probes, then pick the one with the lowest latency
        BootstrapCandidate best = null;
        for (CompletableFuture<BootstrapCandidate> f : futures) {
            try {
                BootstrapCandidate candidate = f.get(helloTimeoutMs + pingTimeoutMs + 1000, TimeUnit.MILLISECONDS);
                if (candidate != null && (best == null || candidate.latencyNanos < best.latencyNanos)) {
                    best = candidate;
                }
            } catch (Exception ignored) { }
        }

        if (best != null) {
            Service service = best.result.getServices().get(0);
            TimberLogger.i(TAG, "Bootstrap fastest via %s (%d ms): peerId=%s, sid=%s",
                    best.endpoint.hostPort(), best.latencyNanos / 1_000_000,
                    best.result.getPeerId(), service.getId());
            FapiClient client = new FapiClient(fudpNode, best.result.getPeerId(), service.getId(), 30, settingMap);
            client.setServerUrl("fudp://" + best.endpoint.host() + ":" + best.endpoint.port());
            return client;
        }

        TimberLogger.e(TAG, "No FAPI endpoint discovered via default bootstrap APIs");
        return null;
    }

    /**
     * Bootstrap with result - returns BootstrapResult containing client, discovery info, and service.
     * Uses default timeouts.
     */
    public static BootstrapResult bootstrapWithResult(FudpNode fudpNode, Service.ServiceType targetType) {
        return bootstrapWithResult(fudpNode, targetType, null, DEFAULT_HELLO_TIMEOUT_MS, DEFAULT_PING_TIMEOUT_MS);
    }

    /**
     * Bootstrap with result - returns BootstrapResult containing client, discovery info, and service.
     * Uses default timeouts.
     */
    public static BootstrapResult bootstrapWithResult(FudpNode fudpNode, Service.ServiceType targetType, Map<String, Object> settingMap) {
        return bootstrapWithResult(fudpNode, targetType, settingMap, DEFAULT_HELLO_TIMEOUT_MS, DEFAULT_PING_TIMEOUT_MS);
    }

    /**
     * Bootstrap with result - returns BootstrapResult containing client, discovery info, and service.
     * This method returns all information needed for persisting ApiProvider and ApiAccount.
     * 
     * @param fudpNode The FUDP node to use for connection
     * @param targetType The service type to find (e.g., FAPI, DISK)
     * @param settingMap Settings map for the client
     * @param helloTimeoutMs Timeout for HELLO handshake
     * @param pingTimeoutMs Timeout for PING discovery
     * @return BootstrapResult containing client and discovery info, or null if failed
     */
    public static BootstrapResult bootstrapWithResult(FudpNode fudpNode, Service.ServiceType targetType,
                                                       Map<String, Object> settingMap,
                                                       long helloTimeoutMs, long pingTimeoutMs) {
        List<Endpoint> endpoints = loadDefaultEndpoints();

        // Probe all endpoints concurrently and pick the fastest one with the target service type
        List<CompletableFuture<BootstrapCandidate>> futures = new ArrayList<>();
        for (Endpoint ep : endpoints) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                long start = System.nanoTime();
                try {
                    DiscoveryResult result = discoverViaHelloAndPing(
                            fudpNode, ep.host(), ep.port(), helloTimeoutMs, pingTimeoutMs);
                    long elapsed = System.nanoTime() - start;
                    if (result != null && !result.getServices().isEmpty()) {
                        Service service = findServiceByType(result.getServices(), targetType);
                        if (service != null) {
                            return new BootstrapCandidate(ep, result, elapsed, service);
                        }
                        TimberLogger.w(TAG, "Bootstrap endpoint {} has no service of type {}", ep.hostPort(), targetType);
                    } else {
                        TimberLogger.w(TAG, "Bootstrap endpoint {} returned no services", ep.hostPort());
                    }
                } catch (Throwable e) {
                    TimberLogger.w(TAG, "Bootstrap via {} failed: {}", ep.hostPort(), describeException(e));
                }
                return null;
            }));
        }

        // Wait for all probes, then pick the one with the lowest latency
        BootstrapCandidate best = null;
        for (CompletableFuture<BootstrapCandidate> f : futures) {
            try {
                BootstrapCandidate candidate = f.get(helloTimeoutMs + pingTimeoutMs + 1000, TimeUnit.MILLISECONDS);
                if (candidate != null && (best == null || candidate.latencyNanos < best.latencyNanos)) {
                    best = candidate;
                }
            } catch (Exception ignored) { }
        }

        if (best != null) {
            TimberLogger.i(TAG, "Bootstrap fastest via %s (%d ms): peerId=%s, sid=%s, type=%s",
                    best.endpoint.hostPort(), best.latencyNanos / 1_000_000,
                    best.result.getPeerId(), best.matchedService.getId(), targetType);
            FapiClient client = new FapiClient(fudpNode, best.result.getPeerId(), best.matchedService.getId(), 30, settingMap);
            return new BootstrapResult(client, best.result, best.matchedService, best.endpoint.hostPort());
        }

        TimberLogger.e(TAG, "No endpoint discovered via default bootstrap APIs for type: {}", targetType);
        return null;
    }

    /**
     * Discover peer public key via HELLO and list FAPI services via PING.
     */
    public static DiscoveryResult discoverViaHelloAndPing(
            FudpNode fudpNode,
            String host,
            int port,
            long helloTimeoutMs,
            long pingTimeoutMs
    ) throws Throwable {
        byte[] pubkey;
        try {
            pubkey = fudpNode.discoverPublicKey(host, port, helloTimeoutMs)
                    .get(helloTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
        String peerId = KeyTools.pubkeyToFchAddr(pubkey);
        fudpNode.addPeer(peerId, pubkey, host, port);
        // This address just answered the HELLO — make it the primary endpoint
        // so the ping below (and reconnects) target it instead of a stale one.
        fudpNode.promotePeerEndpoint(peerId, host, port);

        PongMessage pong;
        try {
            pong = fudpNode.pingAwaitPong(peerId, true, pingTimeoutMs)
                    .get(pingTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // Ping failed — remove the peer to stop retransmit attempts on the dead connection
            fudpNode.removePeer(peerId);
            throw e.getCause() != null ? e.getCause() : e;
        } catch (Exception e) {
            fudpNode.removePeer(peerId);
            throw e;
        }
        if (pong == null) {
            TimberLogger.w(TAG, "discoverViaHelloAndPing: null pong from {}:{} for {}", host, port, peerId);
        } else {
            byte[] data = pong.getData();
            TimberLogger.d(TAG, "discoverViaHelloAndPing: pong received from {}:{} len={} wantInfo={}", host, port, data == null ? 0 : data.length, true);
        }
        List<Service> services = parseFapiServicesFromPong(pong);

        return new DiscoveryResult(peerId, pubkey, services, pong);
    }

    public static List<Service> parseFapiServicesFromPong(PongMessage pong) {
        if (pong == null || pong.getData() == null || pong.getData().length == 0) {
            TimberLogger.d(TAG, "parseFapiServicesFromPong: empty pong data");
            return java.util.Collections.emptyList();
        }
        try {
            String json = new String(pong.getData(), StandardCharsets.UTF_8);
            TimberLogger.d(TAG, "parseFapiServicesFromPong: raw data %s", json);
            Map<?, ?> map = JsonUtils.fromJson(json, Map.class);
            Object svcObj = map.get(FieldNames.SERVICES);
            if (!(svcObj instanceof List<?> services)) {
                TimberLogger.d(TAG, "parseFapiServicesFromPong: no services field");
                return java.util.Collections.emptyList();
            }
            List<Service> result = new ArrayList<>();
            for (Object item : services) {
                if (!(item instanceof Map<?, ?> svcMap)) continue;
                
                // Check if type is FAPI
                if (!isFapiType(svcMap.get(FieldNames.TYPE))) continue;

                Service service = new Service();
                service.setId(stringVal(svcMap.get(FieldNames.SID)));
                service.setStdName(stringVal(svcMap.get(FieldNames.NAME)));
                service.setVer(stringVal(svcMap.get(FieldNames.VER)));
                service.setDealerPubkey(stringVal(svcMap.get(FieldNames.DEALER_PUBKEY)));
                service.setPricePerKB(stringVal(svcMap.get(FieldNames.PRICE_PER_K_B)));
                service.setMinPayment(stringVal(svcMap.get(FieldNames.MIN_PAYMENT)));
                service.setMinCredit(stringVal(svcMap.get(FieldNames.MIN_CREDIT)));
                service.setType(stringVal(svcMap.get(FieldNames.TYPE)));
                
                // Parse apiGroups field
                Object apiGroupsObj = svcMap.get(FieldNames.COMPONENTS);
                if (apiGroupsObj instanceof List<?> list) {
                    List<String> apiGroups = new ArrayList<>();
                    for (Object obj : list) {
                        apiGroups.add(obj.toString());
                    }
                    service.setComponents(apiGroups);
                }
                
                result.add(service);
            }
            return result;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to parse pong info: %s", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }
    
    /**
     * Check if the type value indicates a FAPI service
     */
    private static boolean isFapiType(Object typeObj) {
        if (typeObj == null) return false;
        String typeStr = String.valueOf(typeObj);
        return Service.FAPI_NO1_NRC7.equalsIgnoreCase(typeStr);
    }

    /**
     * Check if the service is of FAPI type.
     * @param service The service to check
     * @return true if the service type is FAPI
     */
    public static boolean isFapiType(Service service) {
        if (service == null) return false;
        return service.fetchServiceType() == Service.ServiceType.FAPI_No1_NrC7;
    }

    /**
     * Check if a service contains all required API components.
     * @param service The service to check
     * @param requiredComponents List of required component names, e.g., ["BASE", "MAP"]
     * @return true if the service contains all required components
     */
    public static boolean hasApiGroups(Service service, List<String> requiredComponents) {
        if (service == null || requiredComponents == null || requiredComponents.isEmpty()) {
            return false;
        }
        List<String> serviceGroups = service.getComponents();
        if (serviceGroups == null || serviceGroups.isEmpty()) {
            return false;
        }
        // Convert to uppercase for case-insensitive comparison
        java.util.Set<String> serviceGroupsUpper = serviceGroups.stream()
            .map(String::toUpperCase)
            .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> requiredUpper = requiredComponents.stream()
            .map(String::toUpperCase)
            .collect(java.util.stream.Collectors.toSet());
        return serviceGroupsUpper.containsAll(requiredUpper);
    }

    /**
     * Find a service from the list that matches the target service type.
     * 
     * @param services List of services to search
     * @param targetType The service type to find
     * @return The first matching service, or null if none found
     */
    public static Service findServiceByType(List<Service> services, Service.ServiceType targetType) {
        if (services == null || targetType == null) return null;
        for (Service service : services) {
            if (service.fetchServiceType() == targetType) {
                return service;
            }
        }
        return null;
    }

    public static List<Endpoint> loadDefaultEndpoints() {
        List<Endpoint> list = new ArrayList<>();
        String env = System.getenv("FAPI_BOOTSTRAP");
        String[] seeds = env != null && !env.isBlank() ? env.split(",") : DEFAULT_BOOTSTRAP_APIS;
        for (String seed : seeds) {
            Endpoint ep = parseEndpoint(seed.trim());
            if (ep != null) list.add(ep);
        }
        return list;
    }

    public static Endpoint parseEndpoint(String seed) {
        if (seed == null || seed.isBlank()) return null;
        // parseFudpUrl now accepts URLs with or without fudp:// scheme
        // and defaults port to DEFAULT_PORT when absent
        return parseFudpUrl(seed);
    }

    private static int parsePort(Object value, int defaultPort) {
        try {
            if (value == null) return defaultPort;
            if (value instanceof Number n) return n.intValue();
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return defaultPort;
        }
    }

    private static String stringVal(Object obj) {
        return obj == null ? null : obj.toString();
    }

    private static String describeException(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        if (t.getCause() != null && (msg == null || msg.isBlank())) {
            return describeException(t.getCause());
        }
        return msg != null ? msg : t.getClass().getSimpleName();
    }

    public static class DiscoveryResult {
        private final String peerId;
        private final byte[] publicKey;
        private final List<Service> services;
        private final PongMessage pong;

        public DiscoveryResult(String peerId, byte[] publicKey, List<Service> services, PongMessage pong) {
            this.peerId = peerId;
            this.publicKey = publicKey;
            this.services = services != null ? services : java.util.Collections.emptyList();
            this.pong = pong;
        }

        public String getPeerId() {
            return peerId;
        }

        public byte[] getPublicKey() {
            return publicKey;
        }

        public List<Service> getServices() {
            return services;
        }

        public PongMessage getPong() {
            return pong;
        }
    }

    public static class Endpoint {
        private final String host;
        private final int port;
        private final String source;

        Endpoint(String host, int port, String source) {
            this.host = host;
            this.port = port;
            this.source = source;
        }

        public String host() { return host; }
        public int port() { return port; }
        public String hostPort() { return host + ":" + port; }
        String source() { return source; }
    }

    /**
     * Result of a bootstrap operation containing all information needed for persistence.
     */
    public static class BootstrapResult {
        private final FapiClient client;
        private final DiscoveryResult discoveryResult;
        private final Service service;
        private final String endpointUrl;  // host:port used for connection

        public BootstrapResult(FapiClient client, DiscoveryResult discoveryResult, Service service, String endpointUrl) {
            this.client = client;
            this.discoveryResult = discoveryResult;
            this.service = service;
            this.endpointUrl = endpointUrl;
        }

        public FapiClient getClient() {
            return client;
        }

        public DiscoveryResult getDiscoveryResult() {
            return discoveryResult;
        }

        public Service getService() {
            return service;
        }

        public String getEndpointUrl() {
            return endpointUrl;
        }

        /**
         * Get the peer ID (dealer FID) for the service provider.
         */
        public String getPeerId() {
            return discoveryResult != null ? discoveryResult.getPeerId() : null;
        }

        /**
         * Get the public key bytes of the service provider.
         */
        public byte[] getPublicKey() {
            return discoveryResult != null ? discoveryResult.getPublicKey() : null;
        }
    }

    /**
     * Internal holder for a bootstrap probe result with its measured latency.
     */
    private static class BootstrapCandidate {
        final Endpoint endpoint;
        final DiscoveryResult result;
        final long latencyNanos;
        final Service matchedService; // may be null when used by bootstrap() which picks services[0]

        BootstrapCandidate(Endpoint endpoint, DiscoveryResult result, long latencyNanos) {
            this(endpoint, result, latencyNanos, null);
        }

        BootstrapCandidate(Endpoint endpoint, DiscoveryResult result, long latencyNanos, Service matchedService) {
            this.endpoint = endpoint;
            this.result = result;
            this.latencyNanos = latencyNanos;
            this.matchedService = matchedService;
        }
    }

    // ==================== ApipClient兼容方法 ====================
    // 这些方法用于兼容原有的ApipClient调用模式
    
    /**
     * 检查最后一次请求是否返回数据不存在
     */
    public boolean isDataNoFound() {
        if (lastResponse == null) return false;
        return lastResponse.getCode() == 1011; // Data not found code
    }
    
    /**
     * 按FID查询交易列表
     */
    public List<Tx> txByFid(String fid, String order, Integer size, List<String> last) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewTerms().addNewFields(FieldNames.SPENT_CASHES_OWNER,ISSUED_CASHES_OWNER).addNewValues(fid);
        if (order != null) {
            fcdsl.addSort(FieldNames.HEIGHT, order).addSort(FieldNames.TX_INDEX, order).addSort(ID,order);
        }
        if (size != null) {
            fcdsl.setSize(String.valueOf(size));
        }
        if (last != null && !last.isEmpty()) {
            fcdsl.setAfter(last);
        }
        return txSearch(fcdsl);
    }
    
    /**
     * 按高度查询区块
     */
    public Map<String, Block> blockByHeights(List<String> heights) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(heights);
        FapiResponse response = query(IndicesNames.BLOCK + ".byIds", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, Block.class);
    }
    
    /**
     * 从服务信息 Map 中提取 Service 对象
     * 
     * @param providerInfo getApiProvider 返回的 Map
     * @return Service 对象，如果提取失败返回 null
     */
    public static Service getServiceFromProviderInfo(Object providerInfo) {
        if (providerInfo instanceof Map<?, ?> map) {
            Object service = map.get("service");
            if (service instanceof Service) {
                return (Service) service;
            }
        }
        return null;
    }
    
    /**
     * 从 fudp:// URL 获取 ApiProvider
     * 通过 HELLO 获取公钥，通过 PING 获取服务信息，返回第一个 FAPI 服务的 ApiProvider
     * 
     * @param fudpNode 已初始化的 FudpNode 实例
     * @param fudpUrl fudp://host:port 格式的 URL
     * @return ApiProvider 对象，如果发现失败返回 null
     */
    public static ApiProvider getApiProviderFromUrl(FudpNode fudpNode, String fudpUrl) {
        return getApiProviderFromUrl(fudpNode, fudpUrl, Service.ServiceType.FAPI_No1_NrC7);
    }
    
    /**
     * 从 fudp:// URL 获取指定类型的 ApiProvider
     * 通过 HELLO 获取公钥，通过 PING 获取服务信息
     * 
     * @param fudpNode 已初始化的 FudpNode 实例
     * @param fudpUrl fudp://host:port 格式的 URL
     * @param targetType 目标服务类型（如 FAPI, DISK 等）
     * @return ApiProvider 对象，如果发现失败返回 null
     */
    public static ApiProvider getApiProviderFromUrl(FudpNode fudpNode, String fudpUrl, Service.ServiceType targetType) {
        return getApiProviderFromUrl(fudpNode, fudpUrl, targetType, DEFAULT_HELLO_TIMEOUT_MS, DEFAULT_PING_TIMEOUT_MS);
    }
    
    /**
     * 从 fudp:// URL 获取指定类型的 ApiProvider（完整参数版本）
     * 通过 HELLO 获取公钥，通过 PING 获取服务信息
     * 
     * @param fudpNode 已初始化的 FudpNode 实例
     * @param fudpUrl fudp://host:port 格式的 URL
     * @param targetType 目标服务类型（如 FAPI, DISK 等）
     * @param helloTimeoutMs HELLO 超时时间（毫秒）
     * @param pingTimeoutMs PING 超时时间（毫秒）
     * @return ApiProvider 对象，如果发现失败返回 null
     */
    public static ApiProvider getApiProviderFromUrl(FudpNode fudpNode, String fudpUrl, 
                                                     Service.ServiceType targetType,
                                                     long helloTimeoutMs, long pingTimeoutMs) {
        if (fudpNode == null || fudpUrl == null || fudpUrl.isEmpty()) {
            TimberLogger.e(TAG, "getApiProviderFromUrl: invalid parameters");
            return null;
        }

        // Normalize URL: add fudp:// scheme and default port if absent
        fudpUrl = normalizeUrl(fudpUrl);
        if (fudpUrl == null) {
            TimberLogger.e(TAG, "getApiProviderFromUrl: failed to normalize URL: {}", fudpUrl);
            return null;
        }

        // Parse fudp:// URL to extract host and port
        Endpoint endpoint = parseFudpUrl(fudpUrl);
        if (endpoint == null) {
            TimberLogger.e(TAG, "getApiProviderFromUrl: failed to parse URL: {}", fudpUrl);
            return null;
        }
        
        try {
            // Use HELLO to get public key, then PING to get service info
            DiscoveryResult discoveryResult = discoverViaHelloAndPing(
                    fudpNode, 
                    endpoint.host(), 
                    endpoint.port(), 
                    helloTimeoutMs, 
                    pingTimeoutMs
            );
            
            if (discoveryResult == null || discoveryResult.getServices().isEmpty()) {
                TimberLogger.w(TAG, "getApiProviderFromUrl: no services discovered from {}", fudpUrl);
                return null;
            }
            
            // Find service matching the target type
            Service service = findServiceByType(discoveryResult.getServices(), targetType);
            if (service == null) {
                // If no matching type found, use the first service
                service = discoveryResult.getServices().get(0);
                TimberLogger.w(TAG, "getApiProviderFromUrl: no service of type {} found, using first service", targetType);
            }
            
            // Create ApiProvider from the discovered service
            ApiProvider apiProvider = ApiProvider.fromService(service, targetType);
            if (apiProvider != null) {
                // Set the API URL to the fudp URL
                apiProvider.setApiUrl(fudpUrl);
                TimberLogger.i(TAG, "getApiProviderFromUrl: discovered service {} from {}", 
                        service.getId(), fudpUrl);
            }
            
            return apiProvider;
            
        } catch (Throwable e) {
            TimberLogger.e(TAG, "getApiProviderFromUrl: discovery failed for {}: {}", 
                    fudpUrl, describeException(e));
            return null;
        }
    }
    
    /**
     * Bootstrap from a specific fudp:// URL and return a BootstrapResult.
     * Unlike bootstrapFromUrl() which returns a raw FapiClient, this returns the full
     * BootstrapResult (including endpointUrl and Service) needed by ApiCenter.
     *
     * @param fudpNode    FUDP node
     * @param targetType  service type to find
     * @param url         fudp://host:port URL
     * @param settingMap  optional settings (can be null)
     * @return BootstrapResult or null on failure
     */
    public static BootstrapResult bootstrapFromUrlWithResult(FudpNode fudpNode, Service.ServiceType targetType,
                                                              String url, Map<String, Object> settingMap) {
        if (fudpNode == null || url == null || url.isEmpty()) return null;

        url = normalizeUrl(url);
        if (url == null) return null;

        Endpoint ep = parseFudpUrl(url);
        if (ep == null) return null;

        try {
            DiscoveryResult result = discoverViaHelloAndPing(
                    fudpNode, ep.host(), ep.port(), DEFAULT_HELLO_TIMEOUT_MS, DEFAULT_PING_TIMEOUT_MS);
            if (result == null || result.getServices().isEmpty()) return null;

            Service service = findServiceByType(result.getServices(), targetType);
            if (service == null) return null;

            TimberLogger.i(TAG, "bootstrapFromUrlWithResult: connected to %s, sid=%s, type=%s",
                    url, service.getId(), targetType);
            FapiClient client = new FapiClient(fudpNode, result.getPeerId(), service.getId(), 30, settingMap);
            return new BootstrapResult(client, result, service, ep.hostPort());
        } catch (Throwable e) {
            TimberLogger.w(TAG, "bootstrapFromUrlWithResult failed for %s: %s", url, describeException(e));
            return null;
        }
    }

    /**
     * Bootstrap FapiClient from a fudp:// URL.
     * Parses fudp://host:port, discovers services via HELLO+PING, returns client for first FAPI/DISK service.
     *
     * @param fudpNode    FUDP node
     * @param url         fudp://host:port URL
     * @param settingMap  optional settings (can be null)
     * @return FapiClient or null on failure
     */
    public static FapiClient bootstrapFromUrl(FudpNode fudpNode, String url, Map<String, Object> settingMap) {
        return bootstrapFromUrl(fudpNode, url, settingMap, DEFAULT_HELLO_TIMEOUT_MS, DEFAULT_PING_TIMEOUT_MS);
    }

    /**
     * Bootstrap FapiClient from a fudp:// URL with custom timeouts.
     */
    public static FapiClient bootstrapFromUrl(FudpNode fudpNode, String url, Map<String, Object> settingMap,
                                              long helloTimeoutMs, long pingTimeoutMs) {
        if (fudpNode == null || url == null || url.isEmpty()) {
            TimberLogger.e(TAG, "bootstrapFromUrl: invalid parameters");
            return null;
        }
        // Normalize URL: add fudp:// scheme and default port if absent
        url = normalizeUrl(url);
        if (url == null) {
            TimberLogger.e(TAG, "bootstrapFromUrl: failed to normalize URL: %s", url);
            return null;
        }
        Endpoint ep = parseFudpUrl(url);
        if (ep == null) {
            TimberLogger.e(TAG, "bootstrapFromUrl: failed to parse URL: %s", url);
            return null;
        }
        try {
            DiscoveryResult result = discoverViaHelloAndPing(fudpNode, ep.host(), ep.port(), helloTimeoutMs, pingTimeoutMs);
            if (result == null || result.getServices().isEmpty()) {
                TimberLogger.w(TAG, "bootstrapFromUrl: no services from %s", url);
                return null;
            }
            Service service = findServiceByType(result.getServices(), Service.ServiceType.FAPI_No1_NrC7);
            if (service == null) {
                service = result.getServices().get(0);
            }
            TimberLogger.i(TAG, "bootstrapFromUrl: connected to %s, sid=%s", url, service.getId());
            FapiClient client = new FapiClient(fudpNode, result.getPeerId(), service.getId(), 30, settingMap);
            client.setServerUrl(url);
            return client;
        } catch (Throwable e) {
            TimberLogger.e(TAG, "bootstrapFromUrl: failed for %s: %s", url, describeException(e));
            return null;
        }
    }

    /**
     * Bootstrap FapiClient from a service ID (SID) using the default client to resolve Service info.
     *
     * @param fudpNode       FUDP node
     * @param sid            Service ID
     * @param defaultClient  Already-connected FapiClient for entityByIds (e.g. to query Service)
     * @param settingMap     optional settings (can be null)
     * @return FapiClient or null on failure
     */
    public static FapiClient bootstrapFromSid(FudpNode fudpNode, String sid, FapiClient defaultClient,
                                              Map<String, Object> settingMap) {
        if (fudpNode == null || sid == null || sid.isEmpty() || defaultClient == null) {
            TimberLogger.e(TAG, "bootstrapFromSid: invalid parameters");
            return null;
        }
        Map<String, Service> map = defaultClient.entityByIds(IndicesNames.SERVICE, Service.class, Collections.singletonList(sid));
        if (map == null || map.isEmpty()) {
            TimberLogger.w(TAG, "bootstrapFromSid: Service not found for sid=%s", sid);
            return null;
        }
        Service service = map.get(sid);
        if (service == null) return null;
        String apiUrl = getHomeApiUrl(service);
        if (apiUrl == null || apiUrl.isEmpty()) {
            TimberLogger.w(TAG, "bootstrapFromSid: no API URL for sid=%s, home=%s", sid, service.getHome());
            return null;
        }
        return bootstrapFromUrl(fudpNode, apiUrl, settingMap);
    }

    /**
     * Look up a service's API endpoint from its {@code home} map, tolerating key-case differences
     * ("API" vs "api"). Services may register the URL under either spelling.
     */
    private static String getHomeApiUrl(Service service) {
        Map<String, String> home = service != null ? service.getHome() : null;
        if (home == null || home.isEmpty()) return null;
        for (Map.Entry<String, String> e : home.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("API")) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 解析 fudp:// URL 格式
     * 支持格式: fudp://host:port 或 fudp://host（默认端口 8500）
     * 
     * @param fudpUrl fudp:// 格式的 URL
     * @return Endpoint 对象，解析失败返回 null
     */
    public static Endpoint parseFudpUrl(String fudpUrl) {
        if (fudpUrl == null || fudpUrl.isEmpty()) {
            return null;
        }

        // FAPI always uses fudp — add scheme if absent
        if (!fudpUrl.toLowerCase().startsWith("fudp://")) {
            fudpUrl = "fudp://" + fudpUrl;
        }

        try {
            // Remove the fudp:// prefix
            String hostPort = fudpUrl.substring(7); // "fudp://".length() = 7
            
            // Remove any trailing path or query string
            int pathIndex = hostPort.indexOf('/');
            if (pathIndex > 0) {
                hostPort = hostPort.substring(0, pathIndex);
            }
            int queryIndex = hostPort.indexOf('?');
            if (queryIndex > 0) {
                hostPort = hostPort.substring(0, queryIndex);
            }
            
            String host;
            int port = DEFAULT_PORT; // Default FUDP port
            
            if (hostPort.contains(":")) {
                String[] parts = hostPort.split(":");
                host = parts[0];
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    port = Integer.parseInt(parts[1]);
                }
            } else {
                host = hostPort;
            }
            
            if (host.isEmpty()) {
                TimberLogger.w(TAG, "parseFudpUrl: empty host in URL: %s", fudpUrl);
                return null;
            }

            // Reject obviously invalid hostnames (e.g. double dots like "fapi.cid..cash")
            if (host.contains("..") || host.startsWith(".") || host.endsWith(".")) {
                TimberLogger.w(TAG, "parseFudpUrl: invalid hostname '%s' in URL: %s", host, fudpUrl);
                return null;
            }

            return new Endpoint(host, port, fudpUrl);
            
        } catch (NumberFormatException e) {
            TimberLogger.w(TAG, "parseFudpUrl: invalid port in URL: %s", fudpUrl);
            return null;
        } catch (Exception e) {
            TimberLogger.w(TAG, "parseFudpUrl: failed to parse URL %s: %s", fudpUrl, e.getMessage());
            return null;
        }
    }

    /**
     * Normalize a FAPI server URL to ensure it has the fudp:// scheme and a port.
     * If the port is absent, the default port 8500 is used.
     * <p>
     * Accepted formats:
     * <ul>
     *   <li>fudp://host:port → returned as-is</li>
     *   <li>fudp://host → fudp://host:8500</li>
     *   <li>host:port → fudp://host:port</li>
     *   <li>host → fudp://host:8500</li>
     * </ul>
     *
     * @param url the raw URL string
     * @return normalized fudp://host:port URL, or null if the input is invalid
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        url = url.trim();

        String hostPort;
        if (url.toLowerCase().startsWith("fudp://")) {
            hostPort = url.substring(7); // strip "fudp://"
        } else {
            hostPort = url;
        }

        // Strip trailing path / query
        int pathIdx = hostPort.indexOf('/');
        if (pathIdx > 0) hostPort = hostPort.substring(0, pathIdx);
        int queryIdx = hostPort.indexOf('?');
        if (queryIdx > 0) hostPort = hostPort.substring(0, queryIdx);

        String host;
        int port = DEFAULT_PORT;
        if (hostPort.contains(":")) {
            String[] parts = hostPort.split(":", 2);
            host = parts[0];
            if (!parts[1].isEmpty()) {
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    TimberLogger.w(TAG, "normalizeUrl: invalid port in URL: %s", url);
                    return null;
                }
            }
        } else {
            host = hostPort;
        }

        if (host.isEmpty()) return null;

        // Reject obviously invalid hostnames (e.g. double dots like "fapi.cid..cash")
        if (host.contains("..") || host.startsWith(".") || host.endsWith(".")) {
            TimberLogger.w(TAG, "normalizeUrl: invalid hostname '%s' in URL: %s", host, url);
            return null;
        }

        return "fudp://" + host + ":" + port;
    }

    @Nullable
    public Freer getFreer(String fid) {
        if (fid == null || fid.isEmpty()) return null;
        Map<String, Freer> result = freerByIds(Collections.singletonList(fid));
        if (result == null) return null;
        return result.get(fid);
    }

    public ApiAccount getApiAccount() {
        return apiAccount;
    }

    public void setApiAccount(ApiAccount apiAccount) {
        this.apiAccount = apiAccount;
    }

    // ========== Disk APIs (Unified Protocol) ==========

    /**
     * Store a file on DISK with expiration (temporary storage).
     * Uses unified protocol for efficient transfer.
     *
     * @param file The file to upload
     * @return DiskItem with did and metadata, or null on error
     */
    public DiskItem diskPut(java.io.File file) {
        return diskPut(file, null, null);
    }

    /**
     * Store a file on DISK with expiration (temporary storage).
     * Uses unified protocol for efficient transfer.
     *
     * @param file The file to upload
     * @param dataLifeDays Optional: number of days to store (null = use server default)
     * @return DiskItem with did and metadata, or null on error
     */
    public DiskItem diskPut(java.io.File file, Long dataLifeDays) {
        return diskPut(file, dataLifeDays, null);
    }
    
    /**
     * Store a file on DISK with expiration and progress tracking.
     *
     * @param file The file to upload
     * @param dataLifeDays Optional: number of days to store (null = use server default)
     * @param progressCallback Optional: callback receiving cumulative bytes transferred
     * @return DiskItem with did and metadata, or null on error
     */
    public DiskItem diskPut(java.io.File file, Long dataLifeDays, java.util.function.LongConsumer progressCallback) {
        return storeFile("disk.put", file, dataLifeDays, progressCallback);
    }

    /**
     * Store a file on DISK permanently (no expiration).
     * Uses unified protocol for efficient transfer.
     *
     * @param file The file to upload permanently
     * @return DiskItem with did and metadata, or null on error
     */
    public DiskItem diskCarve(java.io.File file) {
        return diskCarve(file, null);
    }
    
    /**
     * Store a file on DISK permanently with progress tracking.
     *
     * @param file The file to upload permanently
     * @param progressCallback Optional: callback receiving cumulative bytes transferred
     * @return DiskItem with did and metadata, or null on error
     */
    public DiskItem diskCarve(java.io.File file, java.util.function.LongConsumer progressCallback) {
        return storeFile("disk.carve", file, null, progressCallback);
    }

    /**
     * Internal file storage method for PUT/CARVE.
     * Uses streaming upload to avoid loading entire file into memory:
     * 1. Computes SHA256x2 hash incrementally (first pass over file)
     * 2. Streams the file content directly through FUDP transport (second pass)
     *
     * For small files (< 1MB), falls back to in-memory upload for simplicity.
     *
     * @param api              API name ("disk.put" or "disk.carve")
     * @param file             The file to upload
     * @param dataLifeDays     Expiration in days (null for default / permanent)
     * @param progressCallback Optional callback receiving cumulative bytes sent
     */
    private DiskItem storeFile(String api, java.io.File file, Long dataLifeDays,
                               java.util.function.LongConsumer progressCallback) {
        if (file == null || !file.exists() || !file.isFile()) {
            lastError = new IllegalArgumentException("File does not exist or is not a regular file");
            return null;
        }

        try {
            long fileSize = file.length();
            
            // Build request params
            Map<String, Object> params = new HashMap<>();
            if (dataLifeDays != null) {
                params.put("dataLifeDays", dataLifeDays);
            }
            
            // For small files (< 1MB), use the simple in-memory path
            if (fileSize < 1024 * 1024) {
                DiskItem result = storeFileInMemory(api, file, params);
                // Report completion for small files
                if (result != null && progressCallback != null) {
                    progressCallback.accept(fileSize);
                }
                return result;
            }
            
            // === Streaming upload path (avoids loading entire file into memory) ===
            
            // Pass 1: Compute file hash incrementally
            byte[] hashBytes;
            try (java.io.FileInputStream hashStream = new java.io.FileInputStream(file)) {
                hashBytes = Hash.sha256x2FromStream(hashStream);
            }
            if (hashBytes == null) {
                lastError = new RuntimeException("Failed to compute file hash");
                return null;
            }
            String dataHash = com.fc.fc_ajdk.utils.Hex.toHex(hashBytes);
            
            // Create binary operation request (with dataSize and dataHash)
            FapiRequest fapiRequest = FapiRequest.binaryOperation(api, params, fileSize, dataHash);
            
            // Auto-fill via
            if (fapiRequest.getVia() == null && via != null) {
                fapiRequest.setVia(via);
            }
            
            // Encode the request header only (without binary data)
            byte[] headerData = UnifiedCodec.encodeRequestHeaderOnly(fapiRequest);
            
            // Pass 2: Stream file content through FUDP transport with progress tracking
            CompletableFuture<ResponseMessage> future;
            java.util.concurrent.atomic.AtomicLong lastActivityMs =
                    new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            try (java.io.FileInputStream rawStream = new java.io.FileInputStream(file)) {
                java.io.InputStream fileStream;
                if (progressCallback != null) {
                    fileStream = new com.fc.fc_ajdk.utils.ProgressInputStream(rawStream, progressCallback);
                } else {
                    fileStream = rawStream;
                }
                // The activity tracker refreshes the idle deadline on any sign of life
                // from the server: ACKs while retransmissions of the upload are still
                // being absorbed (bytes == 0) and response data arriving (bytes > 0).
                future = fudpNode.requestWithStream(
                    servicePeerId, serviceSid, headerData, fileStream, fileSize,
                    bytes -> lastActivityMs.set(System.currentTimeMillis()));
            }

            // Idle-based wait: a large upload keeps the deadline alive via ACK
            // keepalives long after the local send loop has finished, so only
            // real silence (dead peer / hung server) times out.
            lastActivityMs.set(System.currentTimeMillis());
            ResponseMessage response = awaitWithIdleTimeout(future, requestTimeoutSeconds, lastActivityMs);
            
            // Parse response body (server encodes FapiResponse even for errors)
            FapiResponse fapiResp = null;
            if (response.getData() != null && response.getData().length > 0) {
                try {
                    UnifiedCodec.UnifiedResponse unified = UnifiedCodec.decodeResponse(response.getData());
                    fapiResp = unified.response();
                } catch (Exception parseEx) {
                    TimberLogger.d(TAG, "Failed to decode streaming response: %s", parseEx.getMessage());
                }
            }

            if (response.getStatusCode() != ResponseMessage.STATUS_SUCCESS) {
                this.lastError = new IOException("Request failed with status: " + response.getStatusCode());
                if (fapiResp != null && fapiResp.getCode() != null) {
                    updateBalanceFromResponse(fapiResp);
                    this.lastResponse = fapiResp;
                } else {
                    this.lastResponse = buildErrorResponse(response.getStatusCode(),
                        response.getData() != null ? new String(response.getData()) : "Unknown error");
                }
                return null;
            }
            
            if (fapiResp == null) {
                this.lastResponse = buildErrorResponse(500, "Empty response");
                lastError = new RuntimeException("Empty response from server");
                return null;
            }
            updateBalanceFromResponse(fapiResp);
            this.lastResponse = fapiResp;
            this.lastError = null;
            
            if (fapiResp.getCode() != 0) {
                lastError = responseError(fapiResp);
                return null;
            }
            
            return ObjectUtils.objectToClass(fapiResp.getData(), DiskItem.class);
            
        } catch (TimeoutException e) {
            lastError = e;
            TimberLogger.w(TAG, "FAPI streaming upload idle timeout (" + requestTimeoutSeconds + "s): api=" + api);
            return null;
        } catch (Exception e) {
            // The transport's own idle timer surfaces as ExecutionException(TimeoutException)
            if (e instanceof java.util.concurrent.ExecutionException && e.getCause() instanceof TimeoutException) {
                lastError = (TimeoutException) e.getCause();
                TimberLogger.w(TAG, "FAPI streaming upload transport idle timeout: api=" + api);
                return null;
            }
            lastError = e;
            TimberLogger.e(TAG, "Failed to store file: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Simple in-memory file upload for small files (< 1MB).
     */
    private DiskItem storeFileInMemory(String api, java.io.File file, Map<String, Object> params) throws Exception {
        byte[] fileContent = java.nio.file.Files.readAllBytes(file.toPath());

        // Compute file hash
        byte[] hashBytes = Hash.sha256x2(fileContent);
        String dataHash = BytesUtils.bytesToHexStringBE(hashBytes);

        // Create binary operation request
        FapiRequest fapiRequest = FapiRequest.binaryOperation(api, params, fileContent.length, dataHash);

        // Calculate dynamic timeout based on data size:
        // Base requestTimeoutSeconds + 1s per 25KB (assumes a ~25KB/s slow-link floor),
        // minimum requestTimeoutSeconds. Slow P2P/FUDP uploads need generous headroom.
        long dynamicTimeout = Math.max(requestTimeoutSeconds,
                requestTimeoutSeconds + (fileContent.length / (25 * 1024)));

        // Send request
        UnifiedCodec.UnifiedResponse unified = requestWithBinaryData(fapiRequest, fileContent, dynamicTimeout);

        if (unified == null || unified.response() == null) {
            lastError = new RuntimeException("No response from server");
            return null;
        }

        if (unified.response().getCode() != 0) {
            lastError = responseError(unified.response());
            return null;
        }

        return ObjectUtils.objectToClass(unified.response().getData(), DiskItem.class);
    }

    /**
     * Download a file from DISK by DID and write it to the output file.
     *
     * @param did SHA256x2 hash of content (64 hex chars)
     * @param outputFile The file to write the downloaded content to
     * @return DiskItem metadata if successful, or null on failure
     */
    public DiskItem diskGet(String did, java.io.File outputFile) {
        return diskGet(did, outputFile, null);
    }
    
    /**
     * Download a file from DISK by DID with progress tracking.
     * <p>
     * The progress callback receives cumulative response bytes assembled from the
     * network as they arrive (may run slightly past the file size due to protocol
     * framing overhead — consumers displaying a percentage should clamp). The wait
     * is idle-based: it fails only after {@code requestTimeoutSeconds} of silence,
     * never while data is still arriving, so large files on slow links complete.
     *
     * @param did              SHA256x2 hash of content (64 hex chars)
     * @param outputFile       The file to write the downloaded content to
     * @param progressCallback Optional: callback receiving cumulative bytes received
     * @return DiskItem metadata if successful, or null on failure
     */
    public DiskItem diskGet(String did, java.io.File outputFile,
                            java.util.function.LongConsumer progressCallback) {
        if (did == null || did.length() != 64) {
            lastError = new IllegalArgumentException("Invalid DID: must be 64 hex characters");
            return null;
        }

        try {
            // Build request params. The DISK server's disk.get expects the content id
            // under the "id" key (matching disk.check/disk.list's id/ids convention).
            Map<String, Object> params = new HashMap<>();
            params.put("id", did);

            FapiRequest fapiRequest = FapiRequest.operation("disk.get", params);

            // Send request; the response binary is streamed straight to outputFile so a
            // large download is never fully held in RAM. Returns only the metadata header.
            FapiResponse response = requestBinaryToFile(
                    fapiRequest, requestTimeoutSeconds, outputFile, progressCallback);

            if (response == null) {
                lastError = new RuntimeException("No response from server");
                TimberLogger.w(TAG, "diskGet: no response for did=%s (lastError=%s)",
                        did, lastError != null ? lastError.getMessage() : "null");
                deleteQuietly(outputFile);
                return null;
            }

            if (response.getCode() != null && response.getCode() != 0) {
                lastError = responseError(response);
                TimberLogger.w(TAG, "diskGet: server rejected did=%s code=%s message=%s",
                        did, response.getCode(), response.getMessage());
                deleteQuietly(outputFile);
                return null;
            }

            // Parse metadata (small JSON header); binary was already written to outputFile.
            return ObjectUtils.objectToClass(response.getData(), DiskItem.class);

        } catch (Exception e) {
            lastError = e;
            deleteQuietly(outputFile);
            TimberLogger.e(TAG, "Failed to get file: " + e.getMessage(), e);
            return null;
        }
    }

    private static void deleteQuietly(java.io.File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * Send a request and write the response's binary payload straight to
     * {@code outputFile}, streaming from the (possibly spilled-to-disk) response so
     * the content is never fully materialised in RAM. Returns the parsed
     * {@link FapiResponse} metadata header (its {@code data} holds the JSON metadata,
     * NOT the binary). On transport/timeout error returns a synthetic error response
     * and writes nothing.
     */
    private FapiResponse requestBinaryToFile(FapiRequest fapiRequest, long timeoutSeconds,
            java.io.File outputFile, java.util.function.LongConsumer receiveProgress) {
        ResponseMessage response = null;
        try {
            if (balanceVerifier != null && balanceVerifier.isStopped()) {
                this.lastError = new IllegalStateException("Balance verification stopped due to drift");
                FapiResponse err = buildErrorResponse(403, "Balance verification stopped");
                this.lastResponse = err;
                return err;
            }
            if (fapiRequest.getVia() == null && via != null) {
                fapiRequest.setVia(via);
            }

            byte[] requestData = UnifiedCodec.encodeRequest(fapiRequest, null);
            java.util.concurrent.atomic.AtomicLong lastActivityMs =
                    new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            java.util.function.LongConsumer activityTracker = bytes -> {
                lastActivityMs.set(System.currentTimeMillis());
                if (receiveProgress != null) receiveProgress.accept(bytes);
            };
            CompletableFuture<ResponseMessage> future = fudpNode.request(
                    servicePeerId, serviceSid, requestData, null, activityTracker);
            response = awaitWithIdleTimeout(future, timeoutSeconds, lastActivityMs);

            FapiResponse fapi = writeResponseBinaryToFile(response, outputFile);

            if (response.getStatusCode() != ResponseMessage.STATUS_SUCCESS) {
                this.lastError = new IOException("Request failed with status: " + response.getStatusCode());
                if (fapi != null && fapi.getCode() != null) {
                    updateBalanceFromResponse(fapi);
                    this.lastResponse = fapi;
                    return fapi;
                }
                FapiResponse err = buildErrorResponse(response.getStatusCode(), "Request failed");
                this.lastResponse = err;
                return err;
            }
            if (fapi == null) {
                FapiResponse err = buildErrorResponse(500, "Empty response");
                this.lastResponse = err;
                return err;
            }
            updateBalanceFromResponse(fapi);
            this.lastResponse = fapi;
            this.lastError = null;
            return fapi;

        } catch (TimeoutException e) {
            this.lastError = e;
            FapiResponse err = buildErrorResponse(408, "Request idle timeout after " + timeoutSeconds + "s without response data");
            this.lastResponse = err;
            return err;
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.ExecutionException
                    && e.getCause() instanceof TimeoutException) {
                this.lastError = (TimeoutException) e.getCause();
                FapiResponse err = buildErrorResponse(408, e.getCause().getMessage());
                this.lastResponse = err;
                return err;
            }
            this.lastError = e;
            FapiResponse err = buildErrorResponse(500, e.getMessage());
            this.lastResponse = err;
            return err;
        } finally {
            // The response owns the spill temp file (if any); delete it now that the
            // binary has been streamed to its destination.
            if (response != null && response.isFileBacked()) {
                response.deleteBackingFile();
            }
        }
    }

    /**
     * Parse the UnifiedCodec response framing ([headerLen(4)][JSON][binary]) from a
     * {@link ResponseMessage} and stream its binary portion to {@code outputFile}.
     * Works for both in-RAM and file-backed responses (both expose {@link ResponseMessage#openData()}).
     *
     * @return the parsed metadata {@link FapiResponse}, or null if the response had no data
     */
    private FapiResponse writeResponseBinaryToFile(ResponseMessage response, java.io.File outputFile)
            throws IOException {
        long dataLen = response.dataLength();
        if (dataLen <= 0) return null;

        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.BufferedInputStream(response.openData()))) {
            int headerLen = dis.readInt();
            if (headerLen < 0 || headerLen > dataLen - 4) {
                throw new IOException("Invalid unified response header length: " + headerLen);
            }
            byte[] jsonBytes = new byte[headerLen];
            dis.readFully(jsonBytes);
            FapiResponse fapi = JsonUtils.fromJson(
                    new String(jsonBytes, StandardCharsets.UTF_8), FapiResponse.class);

            long binaryLen = dataLen - 4 - headerLen;
            if (binaryLen > 0 && outputFile != null) {
                if (outputFile.getParentFile() != null) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.getParentFile().mkdirs();
                }
                try (java.io.OutputStream out = new java.io.BufferedOutputStream(
                        new java.io.FileOutputStream(outputFile))) {
                    byte[] buf = new byte[64 * 1024];
                    long copied = 0;
                    int n;
                    while (copied < binaryLen
                            && (n = dis.read(buf, 0, (int) Math.min(buf.length, binaryLen - copied))) > 0) {
                        out.write(buf, 0, n);
                        copied += n;
                    }
                }
            }
            return fapi;
        }
    }


    /**
     * 发送带二进制数据的请求（使用统一协议）
     * @param fapiRequest FAPI请求
     * @param binaryData 二进制数据
     * @return UnifiedResponse，包含响应和可选的二进制数据
     */
    public UnifiedCodec.UnifiedResponse requestWithBinaryData(FapiRequest fapiRequest, byte[] binaryData) {
        return requestWithBinaryData(fapiRequest, binaryData, requestTimeoutSeconds, null);
    }

    /**
     * Send a request with binary data using a custom timeout.
     */
    public UnifiedCodec.UnifiedResponse requestWithBinaryData(FapiRequest fapiRequest, byte[] binaryData, long timeoutSeconds) {
        return requestWithBinaryData(fapiRequest, binaryData, timeoutSeconds, null);
    }

    /**
     * Send a request with binary data, custom timeout, and optional send-progress callback.
     * @param fapiRequest    FAPI request
     * @param binaryData     binary data
     * @param timeoutSeconds custom timeout in seconds
     * @param sendProgress   callback receiving (bytesSent, totalBytes) — may be null
     * @return UnifiedResponse containing response and optional binary data
     */
    public UnifiedCodec.UnifiedResponse requestWithBinaryData(FapiRequest fapiRequest, byte[] binaryData,
            long timeoutSeconds, java.util.function.BiConsumer<Long, Long> sendProgress) {
        return requestWithBinaryData(fapiRequest, binaryData, timeoutSeconds, sendProgress, null);
    }

    /**
     * Send a request with binary data, custom timeout, send-progress and receive-progress callbacks.
     * The timeout is idle-based: it expires only after {@code timeoutSeconds} with no response
     * bytes arriving, so a large download on a slow link is never killed while still progressing.
     *
     * @param fapiRequest     FAPI request
     * @param binaryData      binary data
     * @param timeoutSeconds  idle timeout in seconds
     * @param sendProgress    callback receiving (bytesSent, totalBytes) — may be null
     * @param receiveProgress callback receiving cumulative response bytes assembled — may be null
     * @return UnifiedResponse containing response and optional binary data
     */
    public UnifiedCodec.UnifiedResponse requestWithBinaryData(FapiRequest fapiRequest, byte[] binaryData,
            long timeoutSeconds, java.util.function.BiConsumer<Long, Long> sendProgress,
            java.util.function.LongConsumer receiveProgress) {
        try {
            if (balanceVerifier != null && balanceVerifier.isStopped()) {
                lastError = new IllegalStateException("Balance verification stopped due to drift");
                FapiResponse errorResp = buildErrorResponse(403, "Balance verification stopped");
                this.lastResponse = errorResp;
                return new UnifiedCodec.UnifiedResponse(errorResp, null);
            }

            // 自动填充 via（消费渠道）
            if (fapiRequest.getVia() == null && via != null) {
                fapiRequest.setVia(via);
            }

            // 使用统一编码格式编码请求（包含二进制数据）
            byte[] requestData = UnifiedCodec.encodeRequest(fapiRequest, binaryData);
            java.util.concurrent.atomic.AtomicLong lastActivityMs =
                    new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            java.util.function.LongConsumer activityTracker = bytes -> {
                lastActivityMs.set(System.currentTimeMillis());
                if (receiveProgress != null) {
                    receiveProgress.accept(bytes);
                }
            };
            CompletableFuture<ResponseMessage> future = fudpNode.request(
                    servicePeerId, serviceSid, requestData, sendProgress, activityTracker);

            ResponseMessage response = awaitWithIdleTimeout(future, timeoutSeconds, lastActivityMs);

            // Parse response body
            UnifiedCodec.UnifiedResponse unifiedResponse = null;
            if (response.getData() != null && response.getData().length > 0) {
                try {
                    unifiedResponse = UnifiedCodec.decodeResponse(response.getData());
                } catch (Exception e) {
                    TimberLogger.d(TAG, "Failed to decode binary response: %s", e.getMessage());
                }
            }

            if (response.getStatusCode() != ResponseMessage.STATUS_SUCCESS) {
                this.lastError = new IOException("Request failed with status: " + response.getStatusCode());
                if (unifiedResponse != null && unifiedResponse.response() != null
                        && unifiedResponse.response().getCode() != null) {
                    updateBalanceFromResponse(unifiedResponse.response());
                    this.lastResponse = unifiedResponse.response();
                    return unifiedResponse;
                }
                FapiResponse errorResp = buildErrorResponse(response.getStatusCode(),
                        response.getData() != null ? new String(response.getData()) : "Unknown error");
                this.lastResponse = errorResp;
                return new UnifiedCodec.UnifiedResponse(errorResp, null);
            }

            if (unifiedResponse == null) {
                FapiResponse errorResp = buildErrorResponse(500, "Empty response");
                this.lastResponse = errorResp;
                return new UnifiedCodec.UnifiedResponse(errorResp, null);
            }
            updateBalanceFromResponse(unifiedResponse.response());
            this.lastResponse = unifiedResponse.response();
            this.lastError = null;

            return unifiedResponse;

        } catch (TimeoutException e) {
            this.lastError = e;
            FapiResponse errorResp = buildErrorResponse(408, "Request idle timeout after " + timeoutSeconds + "s without response data");
            this.lastResponse = errorResp;
            TimberLogger.w(TAG, "FAPI binary request idle timeout (" + timeoutSeconds + "s): api=" +
                    (fapiRequest != null ? fapiRequest.getApi() : "null"));
            return new UnifiedCodec.UnifiedResponse(errorResp, null);
        } catch (Exception e) {
            // The transport's own idle timer surfaces as ExecutionException(TimeoutException)
            if (e instanceof java.util.concurrent.ExecutionException
                    && e.getCause() instanceof TimeoutException) {
                this.lastError = (TimeoutException) e.getCause();
                FapiResponse errorResp = buildErrorResponse(408, e.getCause().getMessage());
                this.lastResponse = errorResp;
                TimberLogger.w(TAG, "FAPI binary request transport idle timeout: api=" +
                        (fapiRequest != null ? fapiRequest.getApi() : "null"));
                return new UnifiedCodec.UnifiedResponse(errorResp, null);
            }
            this.lastError = e;
            FapiResponse errorResp = buildErrorResponse(500, e.getMessage());
            this.lastResponse = errorResp;
            TimberLogger.e(TAG, "Error sending FAPI request with binary data: api=" +
                    (fapiRequest != null ? fapiRequest.getApi() : "null"), e);
            return new UnifiedCodec.UnifiedResponse(errorResp, null);
        }
    }

    /**
     * Waits for a response with an idle-based deadline: gives up only after
     * {@code idleTimeoutSeconds} with no response bytes arriving ({@code lastActivityMs}
     * is refreshed by the transport's receive-progress callback). A small grace period
     * lets the transport's own idle timer, which applies the same rule, fire first.
     */
    private ResponseMessage awaitWithIdleTimeout(CompletableFuture<ResponseMessage> future,
            long idleTimeoutSeconds, java.util.concurrent.atomic.AtomicLong lastActivityMs)
            throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
        long idleMs = idleTimeoutSeconds * 1000L + 2000L;
        while (true) {
            try {
                return future.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                if (System.currentTimeMillis() - lastActivityMs.get() >= idleMs) {
                    throw e;
                }
            }
        }
    }

    /**
     * Check if file exists and get metadata (single DID convenience method).
     * Uses standard JSON protocol.
     *
     * @param did SHA256x2 hash of content (64 hex chars)
     * @return DiskItem metadata, or null if not found
     */
    public DiskItem diskCheck(String did) {
        Map<String, DiskItem> results = diskCheck(Collections.singletonList(did));
        if (results == null) return null;
        return results.get(did);  // null if not found
    }
    
    /**
     * Check if files exist and get metadata for a list of DIDs.
     * Uses standard JSON protocol. Max 200 DIDs per request.
     *
     * @param dids List of SHA256x2 hashes (64 hex chars each)
     * @return Map of DID to DiskItem. Value is null if the file does not exist. Returns null on error.
     */
    public Map<String, DiskItem> diskCheck(List<String> dids) {
        if (dids == null || dids.isEmpty()) {
            lastError = new IllegalArgumentException("DID list cannot be empty");
            return null;
        }
        
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(dids);
        
        FapiResponse response = query("disk.check", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, DiskItem.class);
    }

    /**
     * List stored files with FCDSL query.
     * Uses standard JSON protocol.
     *
     * @param fcdsl Query parameters (filter, sort, size, etc.)
     * @return List of DiskItem, or null on error
     */
    public List<DiskItem> diskList(Fcdsl fcdsl) {
        if (fcdsl == null) {
            fcdsl = new Fcdsl();
        }

        FapiResponse response = query("disk.list", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            if (response != null && response.getCode() != 0) {
                TimberLogger.w(TAG, "disk.list failed: code=" + response.getCode() + ", message=" + response.getMessage());
            }
            return null;
        }
        List<DiskItem> result = ObjectUtils.objectToList(response.getData(), DiskItem.class);
        if (result == null) {
            TimberLogger.w(TAG, "disk.list: response data conversion failed. data type=" +
                    response.getData().getClass().getSimpleName());
        }
        return result;
    }

    // ========== DOCK APIs (Store-and-Forward Messaging) ==========

    /**
     * Store data for recipients (store-and-forward messaging).
     * Sender pays: ingress fee + storage fee (size * days * pricePerKBDay).
     * Uses unified protocol for efficient transfer.
     *
     * @param data The data to store
     * @param recipients List of recipient FIDs
     * @return DockItem with dockId and metadata, or null on error
     */
    public DockItem dockPut(byte[] data, List<String> recipients) {
        return dockPut(data, recipients, null);
    }

    /**
     * Store data for recipients with optional maxDays.
     *
     * @param data The data to store
     * @param recipients List of recipient FIDs
     * @param maxDays Optional: number of days to store (null = use server default)
     * @return DockItem with dockId and metadata, or null on error
     */
    public DockItem dockPut(byte[] data, List<String> recipients, Integer maxDays) {
        return dockPut(data, recipients, maxDays, null);
    }

    /**
     * Store data for recipients with optional maxDays and target DOCK URL for forwarding.
     * <p>
     * When targetDockUrl is specified, the local DOCK will forward the data to the target DOCK.
     * This is useful when sending messages to FIDs/teams/groups/rooms that use a different DOCK server.
     *
     * @param data The data to store
     * @param recipients List of recipient FIDs, team IDs, group IDs, or room IDs
     * @param maxDays Optional: number of days to store (null = use server default)
     * @param targetDockUrl Optional: URL of the target DOCK server to forward data to
     * @return DockItem with dockId and metadata, or null on error
     */
    public DockItem dockPut(byte[] data, List<String> recipients, Integer maxDays, String targetDockUrl) {
        return dockPut(data, recipients, maxDays, targetDockUrl, null);
    }

    /**
     * Store data for recipients with dataType and optional forwarding.
     *
     * @param data The data to store
     * @param recipients List of recipient FIDs, team IDs, group IDs, or room IDs
     * @param maxDays Optional: number of days to store (null = use server default)
     * @param targetDockUrl Optional: URL of target DOCK server to forward to
     * @param dataType Optional: type of data (e.g., "IM", "HAT", "SYMKEY")
     * @return DockItem with dockId and metadata, or null on error
     */
    public DockItem dockPut(byte[] data, List<String> recipients, Integer maxDays, String targetDockUrl, String dataType) {
        if (data == null || data.length == 0) {
            lastError = new IllegalArgumentException("Data content cannot be empty");
            return null;
        }
        if (recipients == null || recipients.isEmpty()) {
            lastError = new IllegalArgumentException("Recipients list cannot be empty");
            return null;
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put(FieldNames.RECIPIENTS, recipients);
            if (maxDays != null) {
                params.put(FieldNames.MAX_DAYS, maxDays);
            }
            if (targetDockUrl != null && !targetDockUrl.isEmpty()
                    && !targetDockUrl.equals(serverUrl)) {
                params.put(FieldNames.TARGET_DOCK_URL, targetDockUrl);
            }
            if (dataType != null && !dataType.isEmpty()) {
                params.put(FieldNames.DATA_TYPE, dataType);
            }

            byte[] hashBytes = Hash.sha256(data);
            String dataHash = BytesUtils.bytesToHexStringBE(hashBytes);

            FapiRequest fapiRequest = FapiRequest.binaryOperation("dock.put", params, data.length, dataHash);

            UnifiedCodec.UnifiedResponse unified = requestWithBinaryData(fapiRequest, data);

            if (unified == null || unified.response() == null) {
                lastError = new RuntimeException("No response from server");
                return null;
            }

            if (unified.response().getCode() != 0) {
                lastError = responseError(unified.response());
                return null;
            }

            return ObjectUtils.objectToClass(unified.response().getData(), DockItem.class);

        } catch (Exception e) {
            lastError = e;
            return null;
        }
    }

    /**
     * Resolve the DOCK URL for a target entity (FID, team, group, or room).
     * <p>
     * This method looks up the target's home.get("DOCK") value. If it's a URL, it returns it directly.
     * If it's a service ID (SID), it fetches the Service and returns service.urls.get("API").
     *
     * @param targetId The target FID, team ID, group ID, or room ID
     * @param targetType The type of target: "freer", "team", "square", or "room"
     * @return The DOCK URL for the target, or null if not found or same as current DOCK
     */
    public String resolveDockUrl(String targetId, String targetType) {
        if (targetId == null || targetId.isEmpty()) {
            return null;
        }

        try {
            Map<String, String> home = null;

            switch (targetType.toLowerCase()) {
                case "freer":
                case "fid":
                case "p2p":
                    Freer freer = freerById(targetId);
                    if (freer != null) {
                        home = freer.getHome();
                    }
                    break;
                case "team":
                    Team team = entityById(IndicesNames.TEAM, Team.class, targetId);
                    if (team != null) {
                        home = team.getHome();
                    }
                    break;
                case "square":
                    Square square = entityById(IndicesNames.SQUARE, Square.class, targetId);
                    if (square != null) {
                        home = square.getHome();
                    }
                    break;
                case "room":
                    return null;
                default:
                    return null;
            }

            return getHomeServiceResolver().resolveDockFromHome(home, this);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Retrieve data by dockId.
     * Recipient pays egress fee.
     * Uses unified protocol for efficient download.
     *
     * @param dockId The dock item ID
     * @return File content bytes, or null if not found
     */
    public byte[] dockGet(String dockId) {
        DockGetResult result = dockGetWithMetadata(dockId);
        return result != null ? result.content() : null;
    }

    /**
     * Retrieve data with metadata.
     * Uses unified protocol for efficient download.
     *
     * @param dockId The dock item ID
     * @return DockGetResult with metadata and content, or null if not found
     */
    public DockGetResult dockGetWithMetadata(String dockId) {
        if (dockId == null || dockId.isEmpty()) {
            lastError = new IllegalArgumentException("dockId is required");
            return null;
        }

        try {
            // Build request params
            Map<String, Object> params = new HashMap<>();
            params.put(FieldNames.ID, dockId);

            FapiRequest fapiRequest = FapiRequest.operation("dock.get", params);

            // Send request and get unified response
            UnifiedCodec.UnifiedResponse unified = requestWithBinaryData(fapiRequest, null);

            if (unified == null || unified.response() == null) {
                lastError = new RuntimeException("No response from server");
                return null;
            }

            if (unified.response().getCode() != 0) {
                lastError = responseError(unified.response());
                return null;
            }

            // Parse metadata from response
            @SuppressWarnings("unchecked")
            Map<String, Object> respData = unified.response().getData() != null
                    ? ObjectUtils.objectToMap(unified.response().getData(), String.class, Object.class)
                    : new HashMap<>();

            // Create a DockItem-like object from response data
            DockItem metadata = new DockItem();
            if (respData.containsKey(FieldNames.ID)) {
                metadata.setId(respData.get(FieldNames.ID).toString());
            }
            if (respData.containsKey(FieldNames.SENDER)) {
                metadata.setSender(respData.get(FieldNames.SENDER).toString());
            }
            if (respData.containsKey(FieldNames.SIZE)) {
                Object sizeObj = respData.get(FieldNames.SIZE);
                if (sizeObj instanceof Number) {
                    metadata.setSize(((Number) sizeObj).longValue());
                }
            }
            if (respData.containsKey(FieldNames.DATA_HASH)) {
                metadata.setDataHash(respData.get(FieldNames.DATA_HASH).toString());
            }
            if (respData.containsKey(FieldNames.CREATE_TIME)) {
                Object timeObj = respData.get(FieldNames.CREATE_TIME);
                if (timeObj instanceof Number) {
                    metadata.setCreateTime(((Number) timeObj).longValue());
                }
            }
            if (respData.containsKey(FieldNames.EXPIRE_HEIGHT)) {
                Object heightObj = respData.get(FieldNames.EXPIRE_HEIGHT);
                if (heightObj instanceof Number) {
                    metadata.setExpireHeight(((Number) heightObj).longValue());
                }
            }
            if (respData.containsKey(FieldNames.RECIPIENTS)) {
                @SuppressWarnings("unchecked")
                List<String> recipients = (List<String>) respData.get(FieldNames.RECIPIENTS);
                metadata.setRecipients(recipients != null ? recipients : new ArrayList<>());
            }

            // Binary data is in unified.binaryData()
            return new DockGetResult(metadata, unified.binaryData());

        } catch (Exception e) {
            lastError = e;
            return null;
        }
    }

    /**
     * List pending items for the caller.
     * Uses standard JSON protocol with FCDSL query.
     *
     * @param fcdsl Query parameters (filter, sort, size, etc.)
     * @return List of DockItem, or null on error
     */
    public List<DockItem> dockList(Fcdsl fcdsl) {
        if (fcdsl == null) {
            fcdsl = new Fcdsl();
        }

        FapiResponse response = query("dock.list", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToList(response.getData(), DockItem.class);
    }

    /**
     * Fetch dock items with inline Base64 data for multiple recipient IDs.
     * Supports pagination via fcdsl.after / response.last.
     *
     * @param recipientIds List of recipient IDs (FID, team, group, room)
     * @param fcdsl Optional: query parameters (size, sort, after for pagination)
     * @return FapiResponse containing list of dock items with dataBase64 field, or null on error
     */
    public FapiResponse dockFetch(List<String> recipientIds, Fcdsl fcdsl) {
        if (fcdsl == null) {
            fcdsl = new Fcdsl();
        }

        Map<String, Object> params = new HashMap<>();
        if (recipientIds != null && !recipientIds.isEmpty()) {
            params.put(FieldNames.RECIPIENT_IDS, recipientIds);
        }

        FapiRequest fapiRequest = new FapiRequest();
        fapiRequest.setApi("dock.fetch");
        fapiRequest.setFcdsl(fcdsl);
        if (!params.isEmpty()) {
            fapiRequest.setParams(params);
        }

        return request(fapiRequest);
    }

    /**
     * Check item status without downloading.
     * Uses standard JSON protocol.
     *
     * @param dockId The dock item ID
     * @return DockItem metadata, or null if not found
     */
    public DockItem dockCheck(String dockId) {
        if (dockId == null || dockId.isEmpty()) {
            lastError = new IllegalArgumentException("dockId is required");
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put(FieldNames.ID, dockId);

        FapiResponse response = operation("dock.check", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToClass(response.getData(), DockItem.class);
    }

    /**
     * Delete item (sender only, partial refund for unused storage).
     * Uses standard JSON protocol.
     *
     * @param dockId The dock item ID
     * @return Map with deletion result and refund info, or null on error
     */
    public Map<String, Object> dockDelete(String dockId) {
        if (dockId == null || dockId.isEmpty()) {
            lastError = new IllegalArgumentException("dockId is required");
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put(FieldNames.ID, dockId);

        FapiResponse response = operation("dock.delete", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, Object.class);
    }

    /**
     * Extend TTL for an item (sender only, pays additional storage fee).
     * Uses standard JSON protocol.
     *
     * @param dockId The dock item ID
     * @param extraDays Number of additional days to extend
     * @return Map with extension result and fee info, or null on error
     */
    public Map<String, Object> dockExtend(String dockId, Integer extraDays) {
        if (dockId == null || dockId.isEmpty()) {
            lastError = new IllegalArgumentException("dockId is required");
            return null;
        }
        if (extraDays == null || extraDays <= 0) {
            lastError = new IllegalArgumentException("extraDays must be positive");
            return null;
        }

        Map<String, Object> params = new HashMap<>();
        params.put(FieldNames.ID, dockId);
        params.put(FieldNames.EXTRA_DAYS, extraDays);

        FapiResponse response = operation("dock.extend", params);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, Object.class);
    }

    /**
     * Result of dockGetWithMetadata operation.
     */
    public record DockGetResult(DockItem metadata, byte[] content) {
    }

    // ========== MAP APIs (FID-to-Address Mapping) ==========

    /**
     * Register self on MAP service.
     * Zero-parameter: all info (FID, pubkey, IP:port) is extracted from FUDP connection.
     * Should be called every ~25 seconds to maintain NAT mappings.
     *
     * @return MapEntry with registration info, or null on error
     */
    public MapEntry mapRegister() {
        FapiResponse response = simple("map.register");
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToClass(response.getData(), MapEntry.class);
    }

    /**
     * Find a registered FID on MAP service.
     * Returns the FID's pubkey, observed IP, and port.
     *
     * @param fid The FID to look up
     * @return MapEntry with address info, or null if not found
     */
    public MapEntry mapFind(String fid) {
        if (fid == null || fid.isEmpty()) {
            lastError = new IllegalArgumentException("FID is required");
            return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(fid);

        FapiResponse response = query("map.find", fcdsl);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToClass(response.getData(), MapEntry.class);
    }

    /**
     * Unregister self from MAP service.
     *
     * @return true if successful, false otherwise
     */
    public boolean mapUnregister() {
        FapiResponse response = simple("map.unregister");
        return response != null && response.getCode() == 0;
    }

    /**
     * List all registered entries on MAP service.
     *
     * @return List of MapEntry, or null on error
     */
    public List<MapEntry> mapList() {
        FapiResponse response = simple("map.list");
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToList(response.getData(), MapEntry.class);
    }

    /**
     * Get MAP service statistics.
     *
     * @return Map with stats (totalEntries, freshEntries, staleEntries, etc.)
     */
    public Map<String, Object> mapStats() {
        FapiResponse response = simple("map.stats");
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, Object.class);
    }

    /**
     * Start a background keepalive task for MAP registration.
     * Calls mapRegister() every intervalSeconds to maintain NAT mappings.
     *
     * @param intervalSeconds Interval between registrations (recommended: 25)
     * @return ScheduledFuture that can be cancelled to stop the keepalive
     */
    public java.util.concurrent.ScheduledFuture<?> startMapKeepalive(int intervalSeconds) {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

        return scheduler.scheduleWithFixedDelay(() -> {
            try {
                MapEntry entry = mapRegister();
                if (entry != null) {
                    TimberLogger.d(TAG, "MAP keepalive: registered %s:%d", entry.getObservedIp(), entry.getObservedPort());
                } else {
                    TimberLogger.w(TAG, "MAP keepalive: registration failed");
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "MAP keepalive error: %s", e.getMessage());
            }
        }, 0, intervalSeconds, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ========== ROAD APIs (Data Relay) ==========

    /**
     * Relay data to multiple target FIDs using ROAD service.
     *
     * @param targetFids List of destination FIDs
     * @param data       The data to relay (can be encrypted or plain)
     * @return RoadRelayResult with success status and results for each target, or null on error
     */
    public RoadRelayResult roadRelay(List<String> targetFids, byte[] data) {
        return roadRelay(targetFids, data, 0, null);
    }

    /**
     * Relay data to a single target FID (convenience method).
     *
     * @param targetFid The destination FID
     * @param data      The data to relay
     * @return RoadRelayResult with success status and fee info, or null on error
     */
    public RoadRelayResult roadRelay(String targetFid, byte[] data) {
        return roadRelay(Collections.singletonList(targetFid), data, 0, null);
    }

    /**
     * Relay data to a single target FID with max cost protection.
     *
     * @param targetFid The destination FID
     * @param data      The data to relay
     * @param maxCost   Maximum satoshi willing to pay (0 = no limit)
     * @return RoadRelayResult with success status and fee info, or null on error
     */
    public RoadRelayResult roadRelay(String targetFid, byte[] data, long maxCost) {
        return roadRelay(Collections.singletonList(targetFid), data, maxCost, null);
    }

    /**
     * Relay data to a single target FID, specifying the remote ROAD URL.
     * The sender discovers targetRoad from the target's freer.home.ROAD field.
     *
     * @param targetFid  The destination FID
     * @param data       The data to relay
     * @param targetRoad URL of the ROAD service where targetFid is registered (from freer.home.ROAD)
     * @return RoadRelayResult with success status and fee info, or null on error
     */
    public RoadRelayResult roadRelay(String targetFid, byte[] data, String targetRoad) {
        return roadRelay(Collections.singletonList(targetFid), data, 0, targetRoad);
    }

    /**
     * Relay data to multiple target FIDs with max cost protection (no target ROAD).
     *
     * @param targetFids List of destination FIDs
     * @param data       The data to relay
     * @param maxCost    Maximum satoshi willing to pay for all relays (0 = no limit)
     * @return RoadRelayResult with success status and results for each target, or null on error
     */
    public RoadRelayResult roadRelay(List<String> targetFids, byte[] data, long maxCost) {
        return roadRelay(targetFids, data, maxCost, null);
    }

    /**
     * Relay data to multiple target FIDs with max cost protection and optional target ROAD URL.
     * <p>
     * If targetRoad is provided, the ROAD server will forward to that URL for targets
     * not found in the local MAP. The sender discovers targetRoad from the target's
     * freer.home.ROAD field (via freerByIds() or getFreer()).
     *
     * @param targetFids List of destination FIDs
     * @param data       The data to relay
     * @param maxCost    Maximum satoshi willing to pay for all relays (0 = no limit)
     * @param targetRoad URL of the remote ROAD for non-local targets (null = local only)
     * @return RoadRelayResult with success status and results for each target, or null on error
     */
    public RoadRelayResult roadRelay(List<String> targetFids, byte[] data, long maxCost, String targetRoad) {
        return roadRelay(targetFids, data, maxCost, targetRoad, null);
    }

    /**
     * Relay data to multiple target FIDs with progress callback.
     *
     * @param targetFids   List of destination FIDs
     * @param data         The data to relay
     * @param maxCost      Maximum satoshi willing to pay (0 = no limit)
     * @param targetRoad   URL of the remote ROAD for non-local targets (null = local only)
     * @param sendProgress callback receiving (bytesSent, totalBytes) — may be null
     * @return RoadRelayResult or null on error
     */
    public RoadRelayResult roadRelay(List<String> targetFids, byte[] data, long maxCost,
            String targetRoad, java.util.function.BiConsumer<Long, Long> sendProgress) {
        if (targetFids == null || targetFids.isEmpty()) {
            lastError = new IllegalArgumentException("targetFids is required");
            return null;
        }
        if (data == null || data.length == 0) {
            lastError = new IllegalArgumentException("data is required");
            return null;
        }

        try {
            Map<String, Object> params = new HashMap<>();
            params.put(FieldNames.TARGET_FIDS, targetFids);
            if (maxCost > 0) {
                params.put(FieldNames.MAX_COST, maxCost);
            }
            if (targetRoad != null && !targetRoad.isEmpty()
                    && !targetRoad.equals(serverUrl)) {
                params.put(FieldNames.TARGET_ROAD, targetRoad);
            }

            FapiRequest fapiRequest = FapiRequest.binaryOperation("road.relay", params, data.length, null);

            // Calculate dynamic timeout based on data size:
            // Base 30s + 1s per 100KB, minimum 30s
            long dynamicTimeout = Math.max(requestTimeoutSeconds,
                    requestTimeoutSeconds + (data.length / (100 * 1024)));

            // Send request
            UnifiedCodec.UnifiedResponse unified = requestWithBinaryData(fapiRequest, data, dynamicTimeout, sendProgress);

            if (unified == null || unified.response() == null) {
                lastError = new RuntimeException("No response from server");
                return null;
            }

            FapiResponse resp = unified.response();

            // Parse response data
            @SuppressWarnings("unchecked")
            Map<String, Object> respData = resp.getData() != null
                    ? ObjectUtils.objectToMap(resp.getData(), String.class, Object.class)
                    : new HashMap<>();

            long chargedIn = respData.containsKey(FieldNames.CHARGED_IN)
                    ? ((Number) respData.get(FieldNames.CHARGED_IN)).longValue() : 0;
            long chargedOut = respData.containsKey(FieldNames.CHARGED_OUT)
                    ? ((Number) respData.get(FieldNames.CHARGED_OUT)).longValue() : 0;
            int successCount = respData.containsKey("successCount")
                    ? ((Number) respData.get("successCount")).intValue() : 0;
            int failCount = respData.containsKey("failCount")
                    ? ((Number) respData.get("failCount")).intValue() : 0;
            int totalTargets = respData.containsKey("totalTargets")
                    ? ((Number) respData.get("totalTargets")).intValue() : targetFids.size();

            // Parse per-target results
            @SuppressWarnings("unchecked")
            Map<String, Object> relayResultsRaw = (Map<String, Object>) respData.get(FieldNames.RELAY_RESULTS);
            Map<String, TargetRelayResult> relayResults = new java.util.LinkedHashMap<>();

            if (relayResultsRaw != null) {
                for (Map.Entry<String, Object> entry : relayResultsRaw.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> targetData = (Map<String, Object>) entry.getValue();
                    boolean success = Boolean.TRUE.equals(targetData.get("success"));
                    int code = targetData.containsKey("code")
                            ? ((Number) targetData.get("code")).intValue() : 0;
                    String message = (String) targetData.get("message");
                    long targetChargedIn = targetData.containsKey(FieldNames.CHARGED_IN)
                            ? ((Number) targetData.get(FieldNames.CHARGED_IN)).longValue() : 0;
                    long targetChargedOut = targetData.containsKey(FieldNames.CHARGED_OUT)
                            ? ((Number) targetData.get(FieldNames.CHARGED_OUT)).longValue() : 0;
                    boolean chainRelayed = Boolean.TRUE.equals(targetData.get(FieldNames.CHAIN_RELAYED));
                    String relayedVia = (String) targetData.get(FieldNames.RELAYED_VIA);

                    relayResults.put(entry.getKey(), new TargetRelayResult(
                            success, code, message, targetChargedIn, targetChargedOut, chainRelayed, relayedVia));
                }
            }

            return new RoadRelayResult(
                    resp.isSuccess(),
                    resp.getCode(),
                    resp.getMessage(),
                    chargedIn,
                    chargedOut,
                    successCount,
                    failCount,
                    totalTargets,
                    relayResults
            );

        } catch (Exception e) {
            lastError = e;
            TimberLogger.e(TAG, "Failed to relay data: %s", e.getMessage());
            return null;
        }
    }

    /**
     * Get ROAD service statistics.
     *
     * @return Map with stats (totalRelays, successfulRelays, bytesIn, bytesOut, etc.)
     */
    public Map<String, Object> roadStats() {
        FapiResponse response = simple("road.stats");
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToMap(response.getData(), String.class, Object.class);
    }

    /**
     * Result of relay to a single target FID.
     */
    public record TargetRelayResult(
            boolean success,
            int code,
            String message,
            long chargedIn,
            long chargedOut,
            boolean chainRelayed,
            String relayedVia
    ) {
        public long totalCharged() {
            return chargedIn + chargedOut;
        }
    }

    /**
     * Result of roadRelay operation with multi-target support.
     */
    public record RoadRelayResult(
            boolean success,
            int code,
            String message,
            long chargedIn,
            long chargedOut,
            int successCount,
            int failCount,
            int totalTargets,
            Map<String, TargetRelayResult> relayResults
    ) {
        public long totalCharged() {
            return chargedIn + chargedOut;
        }

        /**
         * Check if all targets were successfully relayed.
         */
        public boolean allSucceeded() {
            return successCount == totalTargets && failCount == 0;
        }

        /**
         * Get result for a specific target FID.
         */
        public TargetRelayResult getResult(String targetFid) {
            return relayResults != null ? relayResults.get(targetFid) : null;
        }
    }

    // ========== Enhanced DOCK APIs ==========

    /**
     * List items for a specific recipient ID (FID, team, group, or room).
     * Uses standard JSON protocol with FCDSL query.
     *
     * @param fcdsl       Query parameters (filter, sort, size, etc.)
     * @param recipientId team/group/room ID to list items for (null = caller's FID)
     * @return List of DockItem, or null on error
     */
    public List<DockItem> dockList(Fcdsl fcdsl, String recipientId) {
        if (fcdsl == null) {
            fcdsl = new Fcdsl();
        }

        // Build params with recipientId if provided
        Map<String, Object> params = new HashMap<>();
        if (recipientId != null && !recipientId.isEmpty()) {
            params.put("recipientId", recipientId);
        }

        // Use operation with params and fcdsl
        FapiRequest fapiRequest = new FapiRequest();
        fapiRequest.setApi("dock.list");
        fapiRequest.setFcdsl(fcdsl);
        if (!params.isEmpty()) {
            fapiRequest.setParams(params);
        }

        FapiResponse response = request(fapiRequest);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            return null;
        }
        return ObjectUtils.objectToList(response.getData(), DockItem.class);
    }

    /**
     * Retrieve data by dockId on behalf of a team/group/room.
     * Recipient pays egress fee.
     *
     * @param dockId      The dock item ID
     * @param recipientId team/group/room ID to retrieve on behalf of
     * @return File content bytes, or null if not found
     */
    public byte[] dockGet(String dockId, String recipientId) {
        DockGetResult result = dockGetWithMetadata(dockId, recipientId);
        return result != null ? result.content() : null;
    }

    /**
     * Retrieve data with metadata on behalf of a team/group/room.
     * Uses unified protocol for efficient download.
     *
     * @param dockId      The dock item ID
     * @param recipientId team/group/room ID to retrieve on behalf of
     * @return DockGetResult with metadata and content, or null if not found
     */
    public DockGetResult dockGetWithMetadata(String dockId, String recipientId) {
        if (dockId == null || dockId.isEmpty()) {
            lastError = new IllegalArgumentException("dockId is required");
            return null;
        }

        try {
            // Build request params
            Map<String, Object> params = new HashMap<>();
            params.put(FieldNames.ID, dockId);
            if (recipientId != null && !recipientId.isEmpty()) {
                params.put("recipientId", recipientId);
            }

            FapiRequest fapiRequest = FapiRequest.operation("dock.get", params);

            // Send request and get unified response
            UnifiedCodec.UnifiedResponse unified = requestWithBinaryData(fapiRequest, null);

            if (unified == null || unified.response() == null) {
                lastError = new RuntimeException("No response from server");
                return null;
            }

            if (unified.response().getCode() != 0) {
                lastError = responseError(unified.response());
                return null;
            }

            // Parse metadata from response
            @SuppressWarnings("unchecked")
            Map<String, Object> respData = unified.response().getData() != null
                    ? ObjectUtils.objectToMap(unified.response().getData(), String.class, Object.class)
                    : new HashMap<>();

            // Create a DockItem-like object from response data
            DockItem metadata = new DockItem();
            if (respData.containsKey(FieldNames.ID)) {
                metadata.setId(respData.get(FieldNames.ID).toString());
            }
            if (respData.containsKey(FieldNames.SENDER)) {
                metadata.setSender(respData.get(FieldNames.SENDER).toString());
            }
            if (respData.containsKey(FieldNames.SIZE)) {
                Object sizeObj = respData.get(FieldNames.SIZE);
                if (sizeObj instanceof Number) {
                    metadata.setSize(((Number) sizeObj).longValue());
                }
            }
            if (respData.containsKey(FieldNames.DATA_HASH)) {
                metadata.setDataHash(respData.get(FieldNames.DATA_HASH).toString());
            }
            if (respData.containsKey(FieldNames.CREATE_TIME)) {
                Object timeObj = respData.get(FieldNames.CREATE_TIME);
                if (timeObj instanceof Number) {
                    metadata.setCreateTime(((Number) timeObj).longValue());
                }
            }
            if (respData.containsKey(FieldNames.EXPIRE_HEIGHT)) {
                Object heightObj = respData.get(FieldNames.EXPIRE_HEIGHT);
                if (heightObj instanceof Number) {
                    metadata.setExpireHeight(((Number) heightObj).longValue());
                }
            }
            if (respData.containsKey(FieldNames.RECIPIENTS)) {
                @SuppressWarnings("unchecked")
                List<String> recipients = (List<String>) respData.get(FieldNames.RECIPIENTS);
                metadata.setRecipients(recipients != null ? recipients : new ArrayList<>());
            }

            // Binary data is in unified.binaryData()
            return new DockGetResult(metadata, unified.binaryData());

        } catch (Exception e) {
            lastError = e;
            TimberLogger.e(TAG, "Failed to get data from DOCK: %s", e.getMessage());
            return null;
        }
    }

}
