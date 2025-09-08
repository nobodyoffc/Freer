package com.fc.freer.network;

import static com.fc.fc_ajdk.constants.FieldNames.FIDS;
import static com.fc.fc_ajdk.constants.FieldNames.MASTER;
import static com.fc.freer.network.ApipClient.ApipApiNames.CASH_BY_IDS;
import static com.fc.freer.network.ApipClient.ApipApiNames.CID_SEARCH;
import static com.fc.freer.network.ApipClient.ApipApiNames.MULTISIGN_BY_IDS;
import static com.fc.freer.network.ApipClient.ApipApiNames.SN_2;

import android.content.Context;

import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.core.crypto.EncryptType;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.CidInfo;
import com.fc.fc_ajdk.data.fcData.FidTxMask;
import com.fc.fc_ajdk.data.fchData.Block;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Cid;
import com.fc.fc_ajdk.data.fchData.Multisign;
import com.fc.fc_ajdk.data.fchData.OpReturn;
import com.fc.fc_ajdk.data.feipData.App;
import com.fc.fc_ajdk.data.feipData.Box;
import com.fc.fc_ajdk.data.feipData.CidHist;
import com.fc.fc_ajdk.data.feipData.Code;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Group;
import com.fc.fc_ajdk.data.feipData.Mail;
import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Statement;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.freer.model.ApiAccount;
import com.fc.freer.model.ApiProvider;
import com.fc.fc_ajdk.utils.TimberLogger;

import com.fc.fc_ajdk.utils.http.RequestMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.fc_ajdk.constants.FieldNames;

import java.util.HashMap;
import com.fc.fc_ajdk.data.apipData.BlockInfo;
import com.fc.fc_ajdk.data.apipData.Utxo;
import com.fc.fc_ajdk.data.apipData.TxInfo;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.fc_ajdk.data.fchData.FchChainInfo;
import com.fc.fc_ajdk.data.fchData.Nobody;

import com.fc.fc_ajdk.data.apipData.UnconfirmedInfo;
import com.fc.fc_ajdk.data.apipData.EncryptIn;
import com.fc.freer.model.FcReplyBody;


/**
 * APIP客户端 - 专门处理APIP服务的API调用
 * 负责所有APIP相关的业务API请求，不包括ping和getService等基础服务发现功能
 */
public class ApipClient extends FcClient{
    private static final String TAG = "ApipClient";
    public static final String[] defaultAPIs = new String[]{
//            "http://10.0.2.2:8081/APIP",
            "https://apip.cash/APIP",
            "https://freecash.info/APIP"
    };

    public ApipClient(Context context) {
        super(context);
    }

    public ApipClient(Context context,String urlHead) {
        super(context,urlHead);
    }

    public ApipClient(Context context, ApiProvider apiProvider, ApiAccount apiAccount) {
        super(context, apiProvider, apiAccount);
    }

    public static ApipClient createClient(Context context, String urlHead, byte[] symKey) {
        ApiProvider apiProvider = new ApiProvider();
        apiProvider.setApiUrl(urlHead);
        ApiAccount apiAccount = new ApiAccount();
        apiAccount.setApiUrl(urlHead);
        return createClient(context, apiAccount, apiProvider, symKey);
    }

    public static ApipClient createClient(Context context, ApiAccount apiAccount, ApiProvider apiProvider, byte[] symKey) {
        try {
            // 创建ApipClient
            ApipClient apipClient = new ApipClient(context, apiProvider, apiAccount);
            
            // 从ApiCenter获取defaultApipClient并设置
            try {
                com.fc.freer.utils.ApiCenter apiCenter = com.fc.freer.utils.ApiCenter.getInstance();
                ApipClient defaultApipClient = apiCenter.getDefaultApipClient();
                if (defaultApipClient != null) {
                    apipClient.setDefaultApipClient(defaultApipClient);
                    TimberLogger.d(TAG, "Set defaultApipClient from ApiCenter for account: %s", apiAccount.getId());
                }
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to get defaultApipClient from ApiCenter: %s", e.getMessage());
            }
            
            // 初始化客户端
            if(apiAccount.getSession()==null) {
                boolean updated = apipClient.updateService(context);
                if (!updated) {
                    TimberLogger.w(TAG, "Failed to initialize ApipClient for account: %s", apiAccount.getId());
                    return null;
                }
            }
            // 连接客户端
            if(apipClient.ping(context, RequestMethod.POST)){
                apipClient.setConnected(true);
                apiAccount.setClient(apipClient);
                return apipClient;
            }
            
            return null;
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating ApipClient for account %s: %s", apiAccount.getId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取有效的现金列表
     */
    public List<Cash> cashValid(String userId, double payValue, Long cd, int outputSize, Integer msgSize, AuthType authType, RequestMethod requestMethod, Context activityContext) {

        if (userId == null || userId.isEmpty()) {
            TimberLogger.w(TAG, "UserId is null or empty, cannot get cash list");
            return null;
        }
        
        // 创建Fcdsl对象
        Map<String, String> paramMap = new HashMap<>();
        
        // 设置参数
        if (payValue > 0) {
            paramMap.put(FieldNames.AMOUNT, String.valueOf(payValue));
        }
        paramMap.put(FieldNames.FID, userId);
        if (payValue <= 0 && cd != null) {
            paramMap.put(FieldNames.CD, String.valueOf(cd));
        }
        if (outputSize > 0) {
            paramMap.put(FieldNames.OUTPUT_SIZE, String.valueOf(outputSize));
        }
        if (msgSize != null && msgSize > 0) {
            paramMap.put(FieldNames.MSG_SIZE, String.valueOf(msgSize));
        }
        
        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.CASH_VALID, requestMethod, null, paramMap, authType,
            responseBody -> ObjectUtils.objectToList(responseBody.getData(), Cash.class),activityContext);
    }

    /**
     * 广播交易
     */
    public String broadcastTx(String signedTx, RequestMethod requestMethod, AuthType authType, Context contextForDialog) {
        if (signedTx == null) {
            TimberLogger.w(TAG, "signedTx is null");
            return null;
        }
        
        // 处理交易数据格式
        String txHex = signedTx;
        if (!Hex.isHexString(txHex)) {
            txHex = StringUtils.base64ToHex(txHex);
        }
        if (txHex == null) {
            TimberLogger.w(TAG, "Failed to convert signedTx to hex format");
            return null;
        }
        
        // 创建Fcdsl对象
        Fcdsl fcdsl = new Fcdsl();
        Map<String, String> otherMap = new HashMap<>();
        otherMap.put(FieldNames.RAW_TX, txHex);
        fcdsl.addOther(otherMap);
        
        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.BROADCAST_TX, requestMethod, fcdsl, null, authType,
            responseBody -> (String) responseBody.getData(), contextForDialog);
    }

    /**
     * 根据ID获取现金信息
     */
    public Map<String, Cash> cashByIds(RequestMethod requestMethod, AuthType authType, List<String>idList, Context context) {
        if (idList == null || idList.isEmpty()) {
            TimberLogger.w(TAG, "cashId is null or empty");
            return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(idList);

        return request(SN_2, ApipApiNames.VERSION_1, CASH_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Cash.class), context);
    }

    public Map<String, Multisign> multisignByIds(RequestMethod requestMethod, AuthType authType, List<String> idList, Context context) {
        if (idList == null || idList.isEmpty()) {
            TimberLogger.w(TAG, "cashId is null or empty");
            return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(idList);

        return request(SN_2, ApipApiNames.VERSION_1, MULTISIGN_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Multisign.class), context);
    }


    public List<CidInfo> cidInfoSearch(Fcdsl fcdsl,RequestMethod requestMethod, AuthType authType, Context context) {
        return request(SN_2, ApipApiNames.VERSION_1, CID_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(),  CidInfo.class), context);
    }

    /**
     * 搜索现金信息
     */
    public List<Cash> cashSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        if (fcdsl == null) {
            TimberLogger.w(TAG, "fcdsl is null");
            return null;
        }

        return request(SN_2, ApipApiNames.VERSION_1, ApipApiNames.CASH_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), Cash.class), context);
    }

    /**
     * 根据ID获取CID信息
     */
    public String cidByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        if (ids == null || ids.length == 0) {
            TimberLogger.w(TAG, "ids is null or empty");
            return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.CID_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToString(responseBody.getData()), context);
    }

    /**
     * 根据ID获取CID信息
     */
    public Map<String, Cid> cidInfoByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        if (ids == null || ids.length == 0) {
            TimberLogger.w(TAG, "ids is null or empty");
            return null;
        }

        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.CID_INFO_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Cid.class), context);
    }

    /**
     * 根据单个ID获取CID信息
     */
    public Cid cidInfoById(String id, RequestMethod requestMethod, AuthType authType, Context context) {
        if (id == null || id.isEmpty()) {
            TimberLogger.w(TAG, "id is null or empty");
            return null;
        }

        Map<String, Cid> map = cidInfoByIds(requestMethod, authType, context, id);
        if (map != null && map.containsKey(id)) {
            return map.get(id);
        }
        return null;
    }

    /**
     * 搜索CID信息
     */
    public List<Cid> cidSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        if (fcdsl == null) {
            TimberLogger.w(TAG, "fcdsl is null");
            return null;
        }

        return request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.CID_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), Cid.class), context);
    }

    /**
     * 获取总数统计
     */
    public Map<String, String> totals(RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_0, ApipApiNames.VERSION_1, ApipApiNames.TOTALS, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, String.class), context);
    }

    /**
     * 获取最佳区块高度
     */
    public Long bestHeight(Context context) {
        Block block = bestBlock(RequestMethod.POST, AuthType.FC_SIGN_BODY, context);
        if (block == null) return null;
        return block.getHeight();
    }

    /**
     * 获取最佳区块
     */
    public Block bestBlock(RequestMethod requestMethod, AuthType authType, Context context) {
        Object data;
        if (requestMethod.equals(RequestMethod.POST)) {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.BEST_BLOCK, requestMethod, null, null, authType,
                    responseBody -> responseBody.getData(), context);
        } else {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.BEST_BLOCK, requestMethod, null, null, AuthType.FREE,
                    responseBody -> responseBody.getData(), context);
        }
        return ObjectUtils.objectToClass(data, Block.class);
    }

    /**
     * 通用查询
     */
    public FcReplyBody general(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_1, ApipApiNames.VERSION_1, ApipApiNames.GENERAL, requestMethod, fcdsl, null, authType,
                responseBody -> responseBody, context);
    }

    /**
     * 解码交易
     */
    public String decodeTx(String txHex, RequestMethod requestMethod, AuthType authType, Context context) {
        Map<String, String> otherMap = new HashMap<>();
        otherMap.put(FieldNames.RAW_TX, txHex);
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(otherMap);
        
        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.DECODE_TX, requestMethod, fcdsl, null, authType,
                responseBody -> responseBody.getData() == null ? null : JsonUtils.toNiceJson(responseBody.getData()), context);
    }

    /**
     * 获取免费现金列表
     */
    public List<Cash> getCashesFree(String fid, int size, List<String> after, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewTerms().addNewFields(FieldNames.OWNER).addNewValues(fid);
        fcdsl.addNewFilter().addNewTerms().addNewFields(FieldNames.VALID).addNewValues("true");
        fcdsl.addSort(FieldNames.CD, "asc").addSort(FieldNames.ID, "asc");
        if (size > 0) fcdsl.addSize(size);
        if (after != null) fcdsl.addAfter(after);
        
        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.CASH_SEARCH, RequestMethod.POST, fcdsl, null, AuthType.FC_SIGN_BODY,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), Cash.class), context);
    }

    /**
     * 根据高度获取区块信息
     */
    public Map<String, BlockInfo> blockByHeights(RequestMethod requestMethod, AuthType authType, Context context, String... heights) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewTerms().addNewFields(FieldNames.HEIGHT).addNewValues(heights);

        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.BLOCK_BY_HEIGHTS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, BlockInfo.class), context);
    }

    /**
     * 获取公钥
     */
    public String getPubkey(String fid, RequestMethod requestMethod, AuthType authType, Context context) {
        Cid cid = cidInfoById(fid, null, null, null);
        return cid == null ? null : cid.getPubkey();
    }

    /**
     * 根据ID获取区块信息
     */
    public Map<String, BlockInfo> blockByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.BLOCK_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, BlockInfo.class), context);
    }

    /**
     * 搜索区块
     */
    public List<BlockInfo> blockSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        try {
            Object data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.BLOCK_SEARCH, requestMethod, fcdsl, null, authType,
                    responseBody -> responseBody.getData(), context);
            
            if (data == null) {
                TimberLogger.w(TAG, "Received null response from server");
                return null;
            }
            return ObjectUtils.objectToList(data, BlockInfo.class);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in blockSearch: %s", e.getMessage());
            return null;
        }
    }

    /**
     * 获取余额
     */
    public Map<String, Long> balanceByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.BALANCE_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Long.class), context);
    }

    /**
     * 获取UTXO
     */
    public List<Utxo> getUtxo(String id, double amount, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewTerms().addNewFields(FieldNames.FID).addNewValues(id);
        fcdsl.addOther(Map.of(FieldNames.AMOUNT, String.valueOf(amount)));

        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.GET_UTXO, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), Utxo.class), context);
    }

    /**
     * 根据ID获取FID信息
     */
    public Map<String, Cid> fidByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.FID_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Cid.class), context);
    }

    /**
     * 搜索FID信息
     */
    public List<Cid> fidSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.FID_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), Cid.class), context);
    }

    /**
     * 根据ID获取OP_RETURN信息
     */
    public Map<String, OpReturn> opReturnByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.OP_RETURN_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, OpReturn.class), context);
    }

    /**
     * 搜索OP_RETURN信息
     */
    public List<OpReturn> opReturnSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.OP_RETURN_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), OpReturn.class), context);
    }

    public List<Multisign> myMultisigns(String fid, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewTerms().addNewFields(FIDS).addNewValues(fid);
        return multisignSearch(fcdsl, requestMethod, authType, context);
    }

    /**
     * 搜索多重签名
     */
    public List<Multisign> multisignSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.MULTISIGN_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), Multisign.class), context);
    }

    public List<CidHist> myServants(String fid, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.setIndex(IndicesNames.CID_HISTORY);
        fcdsl.addNewQuery().addNewTerms().addNewFields(MASTER).addNewValues(fid);
        FcReplyBody result = general(fcdsl, requestMethod, authType, context);
        if (result == null || result.getData() == null) return null;
        return ObjectUtils.objectToList(result.getData(), CidHist.class);
    }

    /**
     * 根据ID获取交易信息
     */
    public Map<String, TxInfo> txByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.TX_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, TxInfo.class), context);
    }

    /**
     * 搜索交易信息
     */
    public List<TxInfo> txSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.TX_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), TxInfo.class), context);
    }

    /**
     * 根据FID获取交易信息
     */
    public List<FidTxMask> txByFid(String fid, int size, String[] last, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = txByFidQuery(fid, size, last);
        return request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.TX_BY_FID, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), FidTxMask.class), context);
    }

    /**
     * 构建txByFid查询
     */
    public static Fcdsl txByFidQuery(String fid, int size, String[] last) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery()
                .addNewTerms()
                .addNewFields("inMarks.owner", "outMarks.owner")
                .addNewValues(fid);
        if (last != null) {
            fcdsl.addAfter(java.util.List.of(last));
        }
        if (size != 0)
            fcdsl.addSize(size);
        return fcdsl;
    }

    /**
     * 获取链信息
     */
    public FchChainInfo chainInfo(Context context) {
        return chainInfo(null, RequestMethod.GET, AuthType.FREE, context);
    }

    /**
     * 获取链信息（带高度）
     */
    public FchChainInfo chainInfo(Long height, RequestMethod requestMethod, AuthType authType, Context context) {
        Map<String, String> params = null;
        if (height != null) {
            params = new HashMap<>();
            params.put(FieldNames.HEIGHT, String.valueOf(height));
        }
        Object data;
        if (requestMethod.equals(RequestMethod.POST)) {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.CHAIN_INFO, requestMethod, null, params, authType,
                    responseBody -> responseBody.getData(), context);
        } else {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.CHAIN_INFO, requestMethod, null, params, AuthType.FREE,
                    responseBody -> responseBody.getData(), context);
        }
        return ObjectUtils.objectToClass(data, FchChainInfo.class);
    }

    /**
     * 获取区块时间历史
     */
    public Map<Long, Long> blockTimeHistory(Long startTime, Long endTime, Integer count, RequestMethod requestMethod, AuthType authType, Context context) {
        Map<String, String> params = makeHistoryParams(startTime, endTime, count);

        Object data;
        if (requestMethod.equals(RequestMethod.POST)) {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.BLOCK_TIME_HISTORY, requestMethod, null, params, authType,
                    responseBody -> responseBody.getData(), context);
        } else {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.BLOCK_TIME_HISTORY, requestMethod, null, params, AuthType.FREE,
                    responseBody -> responseBody.getData(), context);
        }
        return ObjectUtils.objectToMap(data, Long.class, Long.class);
    }

    /**
     * 获取难度历史
     */
    public Map<Long, String> difficultyHistory(Long startTime, Long endTime, Integer count, RequestMethod requestMethod, AuthType authType, Context context) {
        Map<String, String> params = makeHistoryParams(startTime, endTime, count);
        Object data;
        if (requestMethod.equals(RequestMethod.POST)) {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.DIFFICULTY_HISTORY, requestMethod, null, params, authType,
                    responseBody -> responseBody.getData(), context);
        } else {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.DIFFICULTY_HISTORY, requestMethod, null, params, AuthType.FREE,
                    responseBody -> responseBody.getData(), context);
        }
        return ObjectUtils.objectToMap(data, Long.class, String.class);
    }

    /**
     * 构建历史参数
     */
    private static Map<String, String> makeHistoryParams(Long startTime, Long endTime, Integer count) {
        if (count == null || count == 0) count = 200; // DEFAULT_SIZE

        Map<String, String> params = new HashMap<>();
        if (startTime != null) params.put(FieldNames.START_TIME, String.valueOf(startTime));
        if (endTime != null) params.put(FieldNames.END_TIME, String.valueOf(endTime));
        params.put(FieldNames.COUNT, String.valueOf(count));
        return params;
    }

    /**
     * 获取哈希率历史
     */
    public Map<Long, String> hashRateHistory(Long startTime, Long endTime, Integer count, RequestMethod requestMethod, AuthType authType, Context context) {
        Map<String, String> params = makeHistoryParams(startTime, endTime, count);
        Object data;
        if (requestMethod.equals(RequestMethod.POST)) {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.HASH_RATE_HISTORY, requestMethod, null, params, authType,
                    responseBody -> responseBody.getData(), context);
        } else {
            data = request(ApipApiNames.SN_2, ApipApiNames.VERSION_1, ApipApiNames.HASH_RATE_HISTORY, requestMethod, null, params, AuthType.FREE,
                    responseBody -> responseBody.getData(), context);
        }
        return ObjectUtils.objectToMap(data, Long.class, String.class);
    }

    /**
     * 获取FID CID映射
     */
    public Cid getFidCid(String id, Context context) {
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put(FieldNames.ID, id);
        Object data = request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.GET_FID_CID, RequestMethod.GET, null, paramMap, AuthType.FREE,
                responseBody -> responseBody.getData(), context);
        return ObjectUtils.objectToClass(data, Cid.class);
    }

    /**
     * 获取FID CID映射列表
     */
    public Map<String, String> getFidCidMap(List<String> fidList, Context context) {
        fidList.remove(null);
        Map<String, Cid> cidInfoMap = this.cidInfoByIds(RequestMethod.POST, AuthType.FC_SIGN_BODY, context, fidList.toArray(new String[0]));
        if (cidInfoMap == null) return null;
        Map<String, String> fidCidMap = new HashMap<>();
        for (String fid : cidInfoMap.keySet()) {
            Cid cid = cidInfoMap.get(fid);
            if (cid != null) fidCidMap.put(fid, cid.getCid());
        }
        return fidCidMap;
    }

    /**
     * FID CID搜索
     */
    public Map<String, String[]> fidCidSeek(String searchStr, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewPart().addNewFields(FieldNames.ID, FieldNames.CID).addNewValue(searchStr);
        Object data = request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.FID_CID_SEEK, requestMethod, fcdsl, null, authType,
                responseBody -> responseBody.getData(), context);
        return ObjectUtils.objectToMap(data, String.class, String[].class);
    }

    /**
     * FID CID搜索（免费版本）
     */
    public Map<String, String[]> fidCidSeek(String fid_or_cid, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addNewQuery().addNewPart().addNewFields(FieldNames.ID, FieldNames.CID).addNewValue(fid_or_cid);
        Map<String, String> map = new HashMap<>();
        map.put("part", fid_or_cid);
        Object data = request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.FID_CID_SEEK, RequestMethod.GET, null, map, AuthType.FREE,
                responseBody -> responseBody.getData(), context);
        return ObjectUtils.objectToMap(data, String.class, String[].class);
    }

    /**
     * 根据ID获取Nobody信息
     */
    public Map<String, Nobody> nobodyByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.NOBODY_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Nobody.class), context);
    }

    /**
     * 搜索Nobody信息
     */
    public List<Nobody> nobodySearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.NOBODY_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), Nobody.class), context);
    }

    /**
     * 获取头像
     */
    public Map<String, String> avatars(String[] fids, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(fids);
        Object data = request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.AVATARS, requestMethod, fcdsl, null, authType,
                responseBody -> responseBody.getData(), context);
        return ObjectUtils.objectToMap(data, String.class, String.class);
    }

    /**
     * 获取头像数据
     */
    public byte[] getAvatar(String fid, Context context) {
        Map<String, String> paramMap = new HashMap<>();
        paramMap.put(FieldNames.FID, fid);
        Object data = request(ApipApiNames.SN_3, ApipApiNames.VERSION_1, ApipApiNames.GET_AVATAR, RequestMethod.GET, null, paramMap, AuthType.FREE,
                responseBody -> responseBody.getData(), context);
        return (byte[]) data;
    }

    // Construct APIs
    /**
     * 根据ID获取协议信息
     */
    public Map<String, Protocol> protocolByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_4, ApipApiNames.VERSION_1, ApipApiNames.PROTOCOL_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Protocol.class), context);
    }

    /**
     * 根据ID获取协议信息
     */
    public Protocol protocolById(String id, Context context) {
        Map<String, Protocol> map = protocolByIds(RequestMethod.POST, AuthType.FC_SIGN_BODY, context, id);
        if (map == null) return null;
        try {
            return map.get(id);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 搜索协议信息
     */
    public List<Protocol> protocolSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        Object data = request(ApipApiNames.SN_4, ApipApiNames.VERSION_1, ApipApiNames.PROTOCOL_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> responseBody.getData(), context);
        if (data == null) return null;
        return ObjectUtils.objectToList(data, Protocol.class);
    }

    /**
     * 根据ID获取代码信息
     */
    public Map<String, Code> codeByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_5, ApipApiNames.VERSION_1, ApipApiNames.CODE_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Code.class), context);
    }

    /**
     * 根据ID获取代码信息
     */
    public Code codeById(String id, Context context) {
        Map<String, Code> map = codeByIds(RequestMethod.POST, AuthType.FC_SIGN_BODY, context, id);
        if (map == null) return null;
        try {
            return map.get(id);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 搜索代码信息
     */
    public List<Code> codeSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        Object data = request(ApipApiNames.SN_5, ApipApiNames.VERSION_1, ApipApiNames.CODE_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> responseBody.getData(), context);
        if (data == null) return null;
        return ObjectUtils.objectToList(data, Code.class);
    }

    /**
     * 根据ID获取服务信息
     */
    public Map<String, Service> serviceByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_6, ApipApiNames.VERSION_1, ApipApiNames.SERVICE_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Service.class), context);
    }

    /**
     * 搜索服务信息
     */
    public List<Service> serviceSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        Object data = request(ApipApiNames.SN_6, ApipApiNames.VERSION_1, ApipApiNames.SERVICE_SEARCH, requestMethod, fcdsl, null, authType,
                responseBody -> responseBody.getData(), context);
        if (data == null) return null;
        return ObjectUtils.objectToList(data, Service.class);
    }

    /**
     * 根据ID获取应用信息
     */
    public Map<String, App> appByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_7, ApipApiNames.VERSION_1, ApipApiNames.APP_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, App.class), context);
    }

    /**
     * 根据ID获取应用信息
     */
    public App appById(String id, Context context) {
        Map<String, App> map = appByIds(RequestMethod.POST, AuthType.FC_SIGN_BODY, context, id);
        if (map == null) return null;
        try {
            return map.get(id);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 搜索应用信息
     */
    public List<App> appSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_7, ApipApiNames.VERSION_1, ApipApiNames.APP_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), App.class), context);
    }

    // Organize APIs
    /**
     * 根据ID获取群组信息
     */
    public Map<String, Group> groupByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_8, ApipApiNames.VERSION_1, ApipApiNames.GROUP_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Group.class), context);
    }

    /**
     * 搜索群组信息
     */
    public List<Group> groupSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_8, ApipApiNames.VERSION_1, ApipApiNames.GROUP_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Group.class), context);

    }

    /**
     * 根据ID获取团队信息
     */
    public Map<String, Team> teamByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);

        return request(ApipApiNames.SN_9, ApipApiNames.VERSION_1, ApipApiNames.TEAM_BY_IDS, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Team.class), context);
    }

    /**
     * 搜索团队信息
     */
    public List<Team> teamSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return  request(ApipApiNames.SN_9, ApipApiNames.VERSION_1, ApipApiNames.TEAM_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Team.class), context);
    }

    // Personal APIs
    /**
     * 根据ID获取盒子信息
     */
    public Map<String, Box> boxByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        return request(ApipApiNames.SN_10, ApipApiNames.VERSION_1, ApipApiNames.BOX_BY_IDS, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Box.class), context);
    }

    /**
     * 搜索盒子信息
     */
    public List<Box> boxSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_10, ApipApiNames.VERSION_1, ApipApiNames.BOX_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Box.class), context);

    }

    /**
     * 根据ID获取联系人信息
     */
    public Map<String, Contact> contactByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        return request(ApipApiNames.SN_11, ApipApiNames.VERSION_1, ApipApiNames.CONTACT_BY_IDS, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Contact.class), context);
    }

    /**
     * 搜索联系人信息
     */
    public List<Contact> contactSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_11, ApipApiNames.VERSION_1, ApipApiNames.CONTACT_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Contact.class), context);

    }

    /**
     * 根据ID获取邮件信息
     */
    public Map<String, Mail> mailByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        return request(ApipApiNames.SN_13, ApipApiNames.VERSION_1, ApipApiNames.MAIL_BY_IDS, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Mail.class), context);
    }

    /**
     * 搜索邮件信息
     */
    public List<Mail> mailSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_13, ApipApiNames.VERSION_1, ApipApiNames.MAIL_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Mail.class), context);

    }

    /**
     * 根据ID获取证明信息
     */
    public Map<String, Proof> proofByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        return request(ApipApiNames.SN_14, ApipApiNames.VERSION_1, ApipApiNames.PROOF_BY_IDS, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Proof.class), context);
    }

    /**
     * 搜索证明信息
     */
    public List<Proof> proofSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_14, ApipApiNames.VERSION_1, ApipApiNames.PROOF_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Proof.class), context);

    }

    /**
     * 根据ID获取声明信息
     */
    public Map<String, Statement> statementByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        return request(ApipApiNames.SN_15, ApipApiNames.VERSION_1, ApipApiNames.STATEMENT_BY_IDS, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Statement.class), context);
    }

    /**
     * 搜索声明信息
     */
    public List<Statement> statementSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_15, ApipApiNames.VERSION_1, ApipApiNames.STATEMENT_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Statement.class), context);

    }

    /**
     * 根据ID获取代币信息
     */
    public Map<String, Token> tokenByIds(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        return request(ApipApiNames.SN_16, ApipApiNames.VERSION_1, ApipApiNames.TOKEN_BY_IDS, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, Token.class), context);
    }

    /**
     * 搜索代币信息
     */
    public List<Token> tokenSearch(Fcdsl fcdsl, RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_16, ApipApiNames.VERSION_1, ApipApiNames.TOKEN_SEARCH, requestMethod, fcdsl, null, authType, responseBody -> ObjectUtils.objectToList(responseBody.getData(), Token.class), context);

    }

    /**
     * 获取未确认信息
     */
    public List<UnconfirmedInfo> unconfirmed(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.UNCONFIRMED, requestMethod, null, null, authType,
                responseBody -> ObjectUtils.objectToList(responseBody.getData(), UnconfirmedInfo.class), context);
    }

    /**
     * 获取未确认现金
     */
    public Map<String, List<Cash>> unconfirmedCaches(RequestMethod requestMethod, AuthType authType, Context context, String... ids) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addIds(ids);
        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.UNCONFIRMED_CASHES, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMapWithListValues(responseBody.getData(), String.class, Cash.class), context);

    }

    /**
     * 获取手续费率
     */
    public Double feeRate(RequestMethod requestMethod, AuthType authType, Context context) {
        return request(ApipApiNames.SN_18, ApipApiNames.VERSION_1, ApipApiNames.FEE_RATE, requestMethod, null, null, authType,
                responseBody -> (Double)responseBody.getData(), context);

    }

    // Crypto APIs
    /**
     * 获取地址信息
     */
    public Map<String, String> addresses(String addrOrPubkey, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.ADDR_OR_PUB_KEY, addrOrPubkey));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.ADDRESSES, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToMap(responseBody.getData(), String.class, String.class), context);
    }

    /**
     * 加密
     */
    public String encrypt(EncryptType encryptType, String message, String key, String fid, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        EncryptIn encryptIn = new EncryptIn();
        encryptIn.setType(encryptType);
        encryptIn.setMsg(message);
        switch (encryptType) {
            case Symkey -> {
                encryptIn.setSymkey(key);
                encryptIn.setAlg(AlgorithmId.FC_AesCbc256_No1_NrC7);
            }
            case Password -> {
                encryptIn.setPassword(key);
                encryptIn.setAlg(AlgorithmId.FC_AesCbc256_No1_NrC7);
            }
            case AsyOneWay -> {
                if (key != null) {
                    encryptIn.setPubkey(key);
                } else if (fid != null) {
                    encryptIn.setFid(fid);
                }
                encryptIn.setAlg(AlgorithmId.FC_EccK1AesCbc256_No1_NrC7);
            }
            default -> {
                return null;
            }
        }
        fcdsl.addOther(Map.of(FieldNames.ENCRYPT_INPUT, JsonUtils.toJson(encryptIn)));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.ENCRYPT, requestMethod, fcdsl, null, authType,
                responseBody -> ObjectUtils.objectToClass(responseBody.getData(), String.class), context);
    }

    /**
     * 验证签名
     */
    public boolean verify(String signature, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.SIGN, signature));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.VERIFY, requestMethod, fcdsl, null, authType,
                responseBody -> (boolean)responseBody.getData(), context);
    }

    /**
     * SHA256哈希
     */
    public String sha256(String text, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, text));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.SHA_256, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * SHA256双重哈希
     */
    public String sha256x2(String text, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, text));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.SHA_256_X_2, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * SHA256十六进制哈希
     */
    public String sha256Hex(String hex, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, hex));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.SHA_256_HEX, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * SHA256双重十六进制哈希
     */
    public String sha256x2Hex(String hex, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, hex));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.SHA_256_X_2_HEX, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * RIPEMD160十六进制哈希
     */
    public String ripemd160Hex(String hex, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, hex));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.RIPEMD_160_HEX, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * Keccak SHA3十六进制哈希
     */
    public String KeccakSha3Hex(String hex, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, hex));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.KECCAK_SHA_3_HEX, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * 十六进制转Base58
     */
    public String hexToBase58(String hex, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, hex));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.HEX_TO_BASE_58, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * 十六进制校验和
     */
    public String checkSum4Hex(String hex, RequestMethod requestMethod, AuthType authType, Context context) {
        Fcdsl fcdsl = new Fcdsl();
        fcdsl.addOther(Map.of(FieldNames.MESSAGE, hex));
        return request(ApipApiNames.SN_17, ApipApiNames.VERSION_1, ApipApiNames.CHECK_SUM_4_HEX, requestMethod, fcdsl, null, authType,
                responseBody -> (String)responseBody.getData(), context);
    }

    // Endpoint APIs
    /**
     * 获取流通量
     */
    public String circulating(Context context) {
        return request(ApipApiNames.SN_0, ApipApiNames.VERSION_1, ApipApiNames.CIRCULATING, RequestMethod.GET, null, null, AuthType.FREE,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * 获取总供应量
     */
    public String totalSupply(Context context) {
        return request(ApipApiNames.SN_0, ApipApiNames.VERSION_1, ApipApiNames.TOTAL_SUPPLY, RequestMethod.GET, null, null, AuthType.FREE,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * 获取富豪榜
     */
    public String richList(Context context) {
        return request(ApipApiNames.SN_0, ApipApiNames.VERSION_1, ApipApiNames.RICH_LIST, RequestMethod.GET, null, null, AuthType.FREE,
                responseBody -> (String)responseBody.getData(), context);
    }

    /**
     * 获取FreeCash信息
     */
    public String freecashInfo(Context context) {
        return request(ApipApiNames.SN_0, ApipApiNames.VERSION_1, ApipApiNames.FREECASH_INFO, RequestMethod.GET, null, null, AuthType.FREE,
                responseBody -> (String)responseBody.getData(), context);
    }



    public static class ApipApiNames {
        public static List<String> apiList = new ArrayList<>();
        public static String[] apipAPIs;
        public static ArrayList<String> freeApiList = new ArrayList<>();

        public static final String VERSION_1 ="v1";
        public static final String VERSION_2 ="v2";
        public static final String SN_0 = "sn0";
        public static final String SN_1 = "sn1";
        public static final String SN_2 = "sn2";
        public static final String SN_3 = "sn3";
        public static final String SN_4 = "sn4";
        public static final String SN_5 = "sn5";
        public static final String SN_6 = "sn6";
        public static final String SN_7 = "sn7";
        public static final String SN_8 = "sn8";
        public static final String SN_9 = "sn9";
        public static final String SN_10 = "sn10";

        public static final String SN_11 = "sn11";
        public static final String SN_12 = "sn12";
        public static final String SN_13 = "sn13";
        public static final String SN_14 = "sn14";
        public static final String SN_15 = "sn15";
        public static final String SN_16 = "sn16";
        public static final String SN_17 = "sn17";
        public static final String SN_18 = "sn18";
        public static final String SN_19 = "sn19";
        public static final String SN_20 = "sn20";
        public static final String SN_21 = "sn21";
        public static final String Carve = "carve";

        public static String[] openAPIs;
        public static String[] fcdslAPIs;
        public static String[] freeAPIs;
        public static String[] swapHallAPIs;
        public static String[] blockchainAPIs;
        public static String[] identityAPIs;
        public static String[] organizeAPIs;
        public static String[] constructAPIs;
        public static String[] personalAPIs;
        public static String[] publishAPIs;
        public static String[] walletAPIs;
        public static String[] cryptoAPIs;
        public static String[] endpointAPIs;

        public static final String SIGN_IN = "signIn";
        public static final String PING = "ping";
        public static final String SIGN_IN_ECC = "signInEcc";
        public static final String GENERAL = "general";
        public static final String TOTALS = "totals";
        public static final String BLOCK_BY_IDS = "blockByIds";
        public static final String BLOCK_SEARCH = "blockSearch";
        public static final String BEST_BLOCK = "bestBlock";
        public static final String BLOCK_BY_HEIGHTS = "blockByHeights";
        public static final String CASH_BY_IDS = "cashByIds";
        public static final String GET_CASHES = "getCashes";
        public static final String CASH_SEARCH = "cashSearch";
        public static final String BALANCE_BY_IDS = "balanceByIds";

        public static final String TX_BY_IDS = "txByIds";
        public static final String TX_BY_FID = "txByFid";

        public static final String TX_SEARCH = "txSearch";
        public static final String OP_RETURN_BY_IDS = "opReturnByIds";
        public static final String OP_RETURN_SEARCH = "opReturnSearch";
        public static final String UNCONFIRMED = "unconfirmed";
        public static final String UNCONFIRMED_CASHES = "unconfirmedCashes";
        public static final String BLOCK_HAS_BY_IDS = "blockHasByIds";
        public static final String TX_HAS_BY_IDS = "TxHasByIds";
        public static final String CASH_VALID = "cashValid";
        public static final String GET_UTXO = "getUtxo";
        public static final String FID_BY_IDS = "fidByIds";
        public static final String FID_SEARCH = "fidSearch";
        public static final String FID_CID_SEEK = "fidCidSeek";
        public static final String NEW_OP_RETURN_BY_FIDS ="newOpReturnByFids";

        public static final String GET_FID_CID = "getFidCid";
        public static final String CID_BY_IDS = "cidByIds";
        public static final String CID_INFO_BY_IDS = "cidInfoByIds";
        public static final String CID_SEARCH = "cidSearch";
        public static final String CID_HISTORY = "cidHistory";
        public static final String HOMEPAGE_HISTORY = "homepageHistory";
        public static final String NOTICE_FEE_HISTORY = "noticeFeeHistory";
        public static final String REPUTATION_HISTORY = "reputationHistory";
        public static final String NOBODY_SEARCH = "nobodySearch";
        public static final String MULTISIGN_BY_IDS = "multisignByIds";
        public static final String MULTISIGN_SEARCH = "multisignSearch";
        public static final String PROTOCOL_BY_IDS = "protocolByIds";
        public static final String PROTOCOL_SEARCH = "protocolSearch";
        public static final String PROTOCOL_OP_HISTORY = "protocolOpHistory";
        public static final String PROTOCOL_RATE_HISTORY = "protocolRateHistory";
        public static final String CODE_BY_IDS = "codeByIds";
        public static final String CODE_SEARCH = "codeSearch";
        public static final String CODE_OP_HISTORY = "codeOpHistory";
        public static final String CODE_RATE_HISTORY = "codeRateHistory";
        public static final String SERVICE_BY_IDS = "serviceByIds";
        public static final String GET_BEST_BLOCK = "getBestBlock";
        public static final String GET_SERVICES = "getServices";
        public static final String GET_SERVICE = "getService";
        public static final String GET_FREE_SERVICE = "getFreeService";
        public static final String SERVICE_SEARCH = "serviceSearch";
        public static final String SERVICE_OP_HISTORY = "serviceOpHistory";
        public static final String SERVICE_RATE_HISTORY = "serviceRateHistory";
        public static final String APP_BY_IDS = "appByIds";
        public static final String GET_APPS = "getApps";
        public static final String APP_SEARCH = "appSearch";
        public static final String APP_OP_HISTORY = "appOpHistory";
        public static final String APP_RATE_HISTORY = "appRateHistory";
        public static final String GROUP_BY_IDS = "groupByIds";
        public static final String GROUP_SEARCH = "groupSearch";
        public static final String GROUP_MEMBERS = "groupMembers";
        public static final String GROUP_EX_MEMBERS = "groupExMembers";
        public static final String MY_GROUPS = "myGroups";
        public static final String GROUP_OP_HISTORY = "groupOpHistory";
        public static final String TEAM_BY_IDS = "teamByIds";
        public static final String TEAM_SEARCH = "teamSearch";
        public static final String TEAM_MEMBERS = "teamMembers";
        public static final String TEAM_EX_MEMBERS = "teamExMembers";
        public static final String TEAM_OTHER_PERSONS = "teamOtherPersons";
        public static final String MY_TEAMS = "myTeams";
        public static final String TEAM_OP_HISTORY = "teamOpHistory";
        public static final String TEAM_RATE_HISTORY = "teamRateHistory";
        public static final String CONTACT_BY_IDS = "contactByIds";
        public static final String CONTACT_SEARCH = "contactSearch";
        public static final String CONTACTS_DELETED = "contactsDeleted";
        public static final String SECRET_BY_IDS = "secretByIds";
        public static final String SECRET_SEARCH = "secretSearch";
        public static final String SECRETS_DELETED = "secretsDeleted";
        public static final String MAIL_BY_IDS = "mailByIds";
        public static final String MAIL_SEARCH = "mailSearch";
        public static final String MAILS_DELETED = "mailsDeleted";
        public static final String MAIL_THREAD = "mailThread";
        public static final String STATEMENT_BY_IDS = "statementByIds";
        public static final String STATEMENT_SEARCH = "statementSearch";
        public static final String PROOF_BY_IDS = "proofByIds";
        public static final String PROOF_SEARCH = "proofSearch";
        public static final String PROOF_HISTORY = "proofHistory";
        public static final String BOX_BY_IDS = "boxByIds";
        public static final String BOX_SEARCH = "boxSearch";
        public static final String BOX_HISTORY = "boxHistory";
        public static final String AVATARS = "avatars";
        public static final String GET_AVATAR = "getAvatar";
        public static final String DECODE_TX = "decodeTx";
        public static final String BROADCAST_TX = "broadcastTx";
        public static final String FEE_RATE = "feeRate";
        public static final String BROADCAST = "broadcast";
        public static final String GET_TOTALS = "getTotals";
        public static final String GET_PRICES = "getPrices";
        public static final String NID_SEARCH = "nidSearch";
        public static final String ENCRYPT = "encrypt";
        public static final String SHA_256 = "sha256";
        public static final String HEX_TO_BASE_58 = "hexToBase58";
        public static final String CHECK_SUM_4_HEX = "checkSum4Hex";
        public static final String SHA_256_X_2 = "sha256x2";
        public static final String SHA_256_HEX = "sha256Hex";
        public static final String RIPEMD_160_HEX = "ripemd160Hex";
        public static final String KECCAK_SHA_3_HEX = "keccakSha3Hex";
        public static final String SHA_256_X_2_HEX = "sha256x2Hex";
        public static final String VERIFY = "verify";
        public static final String OFF_LINE_TX = "offLineTx";
        public static final String ADDRESSES = "addresses";
        public static final String NEW_CASH_BY_FIDS = "newCashByFids";
        public static final String OP_RETURN_BY_FIDS = "opReturnByFids";
        public static final String NOBODY_BY_IDS ="nobodyByIds";

        public static final String SWAP_REGISTER ="swapRegister";
        public static final String SWAP_UPDATE ="swapUpdate";
        public static final String SWAP_STATE ="swapState";
        public static final String SWAP_LP ="swapLp";
        public static final String SWAP_PENDING ="swapPending";
        public static final String SWAP_FINISHED ="swapFinished";
        public static final String SWAP_PRICE ="swapPrice";
        public static final String SWAP_INFO ="swapInfo";

        public static final String MY_TOKENS = "myTokens";
        public static final String TOKEN_BY_IDS = "tokenByIds";
        public static final String TOKEN_HISTORY = "tokenHistory";
        public static final String TOKEN_HOLDERS_BY_IDS = "tokenHoldersByIds";
        public static final String TOKEN_HOLDER_SEARCH = "tokenHolderSearch";
        public static final String TOKEN_SEARCH = "tokenSearch";

        public static final String CIRCULATING = "circulating";
        public static final String RICH_LIST = "richList";
        public static final String FREECASH_INFO = "freecashInfo";
        public static final String TOTAL_SUPPLY = "totalSupply";

        public static final String CHAIN_INFO ="chainInfo";
        public static final String DIFFICULTY_HISTORY ="difficultyHistory";
        public static final String HASH_RATE_HISTORY ="hashRateHistory";
        public static final String BLOCK_TIME_HISTORY ="blockTimeHistory";
        public static final String NEW_ORDER = "newOrder";


        static {
            openAPIs = new String[]{
                    PING, GET_SERVICE, SIGN_IN, SIGN_IN_ECC, TOTALS
            };
            fcdslAPIs = new String[]{GENERAL};

            blockchainAPIs = new String[]{
                    BLOCK_SEARCH, BEST_BLOCK, BLOCK_BY_IDS, BLOCK_BY_HEIGHTS,
                    CASH_SEARCH, CASH_BY_IDS,
                    FID_SEARCH, FID_BY_IDS,
                    OP_RETURN_SEARCH, OP_RETURN_BY_IDS,
                    MULTISIGN_SEARCH, MULTISIGN_BY_IDS,
                    TX_SEARCH, TX_BY_IDS, TX_BY_FID,
                    CHAIN_INFO, BLOCK_TIME_HISTORY, DIFFICULTY_HISTORY, HASH_RATE_HISTORY
            };

            identityAPIs = new String[]{
                    CID_SEARCH, CID_BY_IDS, CID_HISTORY,
                    FID_CID_SEEK, GET_FID_CID,
                    NOBODY_SEARCH, NOBODY_BY_IDS,
                    HOMEPAGE_HISTORY, NOTICE_FEE_HISTORY, REPUTATION_HISTORY,
                    GET_AVATAR, AVATARS
            };

            organizeAPIs = new String[]{
                    GROUP_SEARCH, GROUP_BY_IDS, GROUP_MEMBERS, GROUP_OP_HISTORY, MY_GROUPS,
                    TEAM_SEARCH, TEAM_BY_IDS, TEAM_MEMBERS, TEAM_EX_MEMBERS,
                    TEAM_OP_HISTORY, TEAM_RATE_HISTORY, TEAM_OTHER_PERSONS, MY_TEAMS
            };

            constructAPIs = new String[]{
                    PROTOCOL_SEARCH, PROTOCOL_BY_IDS, PROTOCOL_OP_HISTORY, PROTOCOL_RATE_HISTORY,
                    CODE_SEARCH, CODE_BY_IDS, CODE_OP_HISTORY, CODE_RATE_HISTORY,
                    SERVICE_SEARCH, SERVICE_BY_IDS, SERVICE_OP_HISTORY, SERVICE_RATE_HISTORY,
                    APP_SEARCH, APP_BY_IDS, APP_OP_HISTORY, APP_RATE_HISTORY
            };

            personalAPIs = new String[]{
                    BOX_SEARCH, BOX_BY_IDS, BOX_HISTORY,
                    CONTACT_SEARCH, CONTACT_BY_IDS, CONTACTS_DELETED,
                    SECRET_SEARCH, SECRET_BY_IDS, SECRETS_DELETED,
                    MAIL_SEARCH, MAIL_BY_IDS, MAILS_DELETED, MAIL_THREAD
            };

            publishAPIs = new String[]{
                    PROOF_SEARCH, PROOF_BY_IDS, PROOF_HISTORY,
                    STATEMENT_SEARCH, STATEMENT_BY_IDS, NID_SEARCH,
                    TOKEN_SEARCH, TOKEN_BY_IDS, TOKEN_HISTORY,
                    TOKEN_HOLDER_SEARCH, TOKEN_HOLDERS_BY_IDS, MY_TOKENS
            };

            walletAPIs = new String[]{
                    BROADCAST_TX, DECODE_TX,
                    CASH_VALID, BALANCE_BY_IDS,
                    UNCONFIRMED, UNCONFIRMED_CASHES, FEE_RATE,
                    OFF_LINE_TX
            };

            cryptoAPIs = new String[]{
                    ADDRESSES,
                    ENCRYPT, VERIFY,
                    SHA_256, SHA_256_X_2, SHA_256_HEX, SHA_256_X_2_HEX,
                    RIPEMD_160_HEX, KECCAK_SHA_3_HEX,
                    CHECK_SUM_4_HEX, HEX_TO_BASE_58
            };
            endpointAPIs = new String[]{
                    NEW_CASH_BY_FIDS,
                    NEW_OP_RETURN_BY_FIDS
            };

            apiList.addAll(List.of(openAPIs));
            apiList.addAll(List.of(blockchainAPIs));
            apiList.addAll(List.of(identityAPIs));
            apiList.addAll(List.of(organizeAPIs));
            apiList.addAll(List.of(constructAPIs));
            apiList.addAll(List.of(personalAPIs));
            apiList.addAll(List.of(publishAPIs));
            apiList.addAll(List.of(walletAPIs));
            apiList.addAll(List.of(cryptoAPIs));
            apiList.addAll(List.of(endpointAPIs));

            apipAPIs = apiList.toArray(new String[0]);


            endpointAPIs = new String[]{
                    TOTAL_SUPPLY, CIRCULATING, RICH_LIST, FREECASH_INFO
            };

            freeAPIs = new String[]{
                    PING, CHAIN_INFO, GET_SERVICE, FID_CID_SEEK, GET_FID_CID, GET_AVATAR, CASH_VALID, BROADCAST
            };

            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_BEST_BLOCK);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_FREE_SERVICE);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_AVATAR);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_TOTALS);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_PRICES);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_APPS);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_CASHES);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_FID_CID);
            freeApiList.add(com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_SERVICES);



            swapHallAPIs = new String[]{
                    SWAP_REGISTER, SWAP_UPDATE, SWAP_STATE,
                    SWAP_LP, SWAP_PENDING, SWAP_FINISHED,
                    SWAP_PRICE, SWAP_INFO, SWAP_INFO
            };

        }
    }
}