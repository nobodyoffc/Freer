package com.fc.fc_ajdk.fapi.client;

import org.bitcoinj.fch.FchMainNetwork;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.constants.IndicesNames;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.fapi.FapiCode;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * 自动充值管理器
 * 
 * 当客户端余额低于阈值时，自动触发充值流程：
 * 1. 获取 Service 信息（pricePerKB, minPayment, dealer）
 * 2. 计算支付金额 = max(purchaseKb * pricePerKB, minPayment)
 * 3. 获取可用 UTXOs
 * 4. 构造并签名交易
 * 5. 广播交易
 * 
 * 特性：
 * - 冷却时间防止短时间内重复充值
 * - 重试策略处理 UTXO 无效等问题
 * - 异步执行不阻塞业务请求
 * - 充值结果回调通知
 */
public class AutoRechargeManager {
    private static final String TAG = "AutoRechargeManager";
    
    // ==================== 配置键常量 ====================
    public static final String KEY_ENABLED = "autoRechargeEnabled";
    public static final String KEY_THRESHOLD = "autoRechargeThreshold";
    public static final String KEY_PURCHASE_KB = "autoRechargeKb";
    public static final String KEY_COOLDOWN_MS = "autoRechargeCooldownMs";
    public static final String KEY_MAX_RETRIES = "autoRechargeMaxRetries";
    public static final String KEY_RETRY_DELAY_MS = "autoRechargeRetryDelayMs";
    public static final String KEY_MAX_PAYMENT = "autoRechargeMaxPayment";
    
    // ==================== 默认值常量 ====================
    public static final boolean DEFAULT_ENABLED = true;
    public static final long DEFAULT_THRESHOLD = 0L;
    public static final long DEFAULT_PURCHASE_KB = 1000L;
    public static final long DEFAULT_COOLDOWN_MS = 60_000L;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final long DEFAULT_RETRY_DELAY_MS = 2_000L;
    public static final long DEFAULT_MAX_PAYMENT = 100_000_000L; // 1 FCH
    
    private final FapiClient fapiClient;
    private final Map<String, Object> settingMap;
    private final String mainFid;
    private final FapiClient.PrikeyProvider prikeyProvider;
    
    // 配置
    private final boolean enabled;
    private final long threshold;  // 触发阈值（聪）
    private final long purchaseKb;  // 购买量（KB）
    private final long cooldownMs;  // 冷却时间（毫秒）
    private final int maxRetries;  // 最大重试次数
    private final long retryDelayMs;  // 重试基础延迟（毫秒）
    private final long maxPayment;  // 最大支付额（聪），防止价格过高
    
    // 状态
    private final AtomicBoolean recharging = new AtomicBoolean(false);
    private volatile long lastRechargeAttemptMs = 0;
    private volatile long lastSuccessfulRechargeMs = 0;
    private volatile String lastRechargeTxId = null;
    private volatile boolean stoppedDueToPriceLimit = false;  // 是否因价格过高而停止
    private volatile String priceAlertMessage = null;  // 价格告警消息
    
    // via FID for OP_RETURN in recharge transactions
    private volatile String viaFid = null;
    
    // 缓存
    private volatile Service cachedService = null;
    private volatile long serviceLoadedMs = 0;
    private static final long SERVICE_CACHE_TTL_MS = 300_000L;  // Service 缓存 5 分钟
    
    // 回调
    private BiConsumer<RechargeResult, String> rechargeCallback;
    
    public AutoRechargeManager(FapiClient fapiClient, Map<String, Object> settingMap,
                               String mainFid, FapiClient.PrikeyProvider prikeyProvider) {
        this.fapiClient = fapiClient;
        this.settingMap = settingMap;
        this.mainFid = mainFid;
        this.prikeyProvider = prikeyProvider;
        
        this.enabled = getBool(settingMap, KEY_ENABLED, DEFAULT_ENABLED);
        this.threshold = getLong(settingMap, KEY_THRESHOLD, DEFAULT_THRESHOLD);
        this.purchaseKb = getLong(settingMap, KEY_PURCHASE_KB, DEFAULT_PURCHASE_KB);
        this.cooldownMs = getLong(settingMap, KEY_COOLDOWN_MS, DEFAULT_COOLDOWN_MS);
        this.maxRetries = getInt(settingMap, KEY_MAX_RETRIES, DEFAULT_MAX_RETRIES);
        this.retryDelayMs = getLong(settingMap, KEY_RETRY_DELAY_MS, DEFAULT_RETRY_DELAY_MS);
        this.maxPayment = getLong(settingMap, KEY_MAX_PAYMENT, DEFAULT_MAX_PAYMENT);
        
        TimberLogger.i(TAG, "AutoRechargeManager initialized: enabled=%b, threshold=%d, purchaseKb=%d, cooldownMs=%d, maxPayment=%d sat (%.8f FCH)", 
                enabled, threshold, purchaseKb, cooldownMs, maxPayment, FchUtils.satoshiToCoin(maxPayment));
    }
    
    /**
     * 检查是否需要充值并触发
     * 
     * @param currentBalance 当前余额（聪）
     * @return CompletableFuture，如果触发充值返回充值结果，否则返回 null
     */
    public CompletableFuture<RechargeResult> checkAndRechargeIfNeeded(Long currentBalance) {
        if (!enabled) {
            TimberLogger.d(TAG, "Auto-recharge is disabled");
            return CompletableFuture.completedFuture(null);
        }
        
        // 检查是否因价格过高而停止
        if (stoppedDueToPriceLimit) {
            TimberLogger.w(TAG, "Auto-recharge stopped due to price limit. Call resetPriceAlert() to resume after adjusting settings.");
            return CompletableFuture.completedFuture(null);
        }
        
        long effectiveThreshold = computeEffectiveThreshold();
        if (currentBalance == null || currentBalance > effectiveThreshold) {
            return CompletableFuture.completedFuture(null);
        }
        
        // 检查冷却期
        long now = System.currentTimeMillis();
        if (now - lastRechargeAttemptMs < cooldownMs) {
            TimberLogger.d(TAG, "In cooldown period, skipping recharge. Last attempt: {}ms ago", now - lastRechargeAttemptMs);
            return CompletableFuture.completedFuture(null);
        }
        
        // 检查是否已有充值进行中
        if (!recharging.compareAndSet(false, true)) {
            TimberLogger.d(TAG, "Recharge already in progress, skipping");
            return CompletableFuture.completedFuture(null);
        }
        
        lastRechargeAttemptMs = now;
        TimberLogger.i(TAG, "Triggering auto-recharge: balance={}, threshold={}", currentBalance, threshold);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeRechargeWithRetry();
            } finally {
                recharging.set(false);
            }
        });
    }
    
    /**
     * 执行手动充值
     * 
     * @param amountFch 充值金额（FCH），如果为 null 则使用默认计算值
     * @return 充值结果
     */
    public RechargeResult manualRecharge(Double amountFch) {
        if (!recharging.compareAndSet(false, true)) {
            return RechargeResult.failure("Recharge already in progress");
        }
        
        try {
            if (amountFch != null && amountFch > 0) {
                long amountSatoshi = FchUtils.coinToSatoshi(amountFch);
                return executeRechargeWithRetry(amountSatoshi);
            } else {
                return executeRechargeWithRetry();
            }
        } finally {
            recharging.set(false);
        }
    }
    
    /**
     * Compute effective recharge threshold using minCredit from service.
     * If the service publishes minCredit, threshold = -(minCredit_satoshi / 2),
     * meaning recharge triggers when balance drops to half the negative credit limit.
     * Otherwise falls back to the configured threshold (default 0).
     */
    private long computeEffectiveThreshold() {
        Service service = getServiceInfo();
        if (service != null && service.getMinCredit() != null && !service.getMinCredit().isEmpty()) {
            try {
                long minCreditSatoshi = FchUtils.coinToSatoshi(Double.parseDouble(service.getMinCredit()));
                if (minCreditSatoshi > 0) {
                    return -(minCreditSatoshi / 2);
                }
            } catch (NumberFormatException e) {
                TimberLogger.w(TAG, "Invalid minCredit format: %s", service.getMinCredit());
            }
        }
        return threshold;
    }

    /**
     * 带重试的充值执行
     */
    private RechargeResult executeRechargeWithRetry() {
        long paymentAmount = calculatePaymentAmount();
        if (paymentAmount <= 0) {
            return RechargeResult.failure("Failed to calculate payment amount");
        }
        return executeRechargeWithRetry(paymentAmount);
    }
    
    private RechargeResult executeRechargeWithRetry(long paymentSatoshi) {
        // 安全检查：支付额是否超过最大限制
        if (paymentSatoshi > maxPayment) {
            stoppedDueToPriceLimit = true;
            priceAlertMessage = String.format(
                    "ALERT: Payment amount %d satoshi (%.8f FCH) exceeds max limit %d satoshi (%.8f FCH). " +
                    "Service may have set unreasonably high price. Recharge stopped for safety.",
                    paymentSatoshi, FchUtils.satoshiToCoin(paymentSatoshi),
                    maxPayment, FchUtils.satoshiToCoin(maxPayment));
            TimberLogger.e(priceAlertMessage);

            RechargeResult alertResult = RechargeResult.priceAlert(paymentSatoshi, maxPayment, priceAlertMessage);
            notifyCallback(alertResult, priceAlertMessage);
            
            return alertResult;
        }
        
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                // 指数退避
                long delay = retryDelayMs * (1L << (attempt - 1));
                TimberLogger.i(TAG, "Retrying recharge (attempt {}/{}), waiting {}ms...", attempt + 1, maxRetries + 1, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return RechargeResult.failure("Recharge interrupted");
                }
            }
            
            try {
                RechargeResult result = executeSingleRecharge(paymentSatoshi);
                if (result.isSuccess()) {
                    lastSuccessfulRechargeMs = System.currentTimeMillis();
                    lastRechargeTxId = result.getTxId();
                    notifyCallback(result, "Recharge successful");
                    TimberLogger.i(TAG, "Recharge successful: txId={}, amount={} satoshi ({} FCH)", 
                            result.getTxId(), paymentSatoshi, FchUtils.satoshiToCoin(paymentSatoshi));
                    return result;
                }
                
                // 检查是否是可重试的错误
                if (!isRetryableError(result.getMessage())) {
                    TimberLogger.w(TAG, "Non-retryable recharge error: {}", result.getMessage());
                    notifyCallback(result, result.getMessage());
                    return result;
                }
                
                TimberLogger.w(TAG, "Retryable recharge error on attempt {}: {}", attempt + 1, result.getMessage());
                lastException = new RuntimeException(result.getMessage());
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Exception during recharge attempt {}: {}", attempt + 1, e.getMessage());
                lastException = e;
            }
        }
        
        String errorMsg = "Recharge failed after " + (maxRetries + 1) + " attempts: " + 
                (lastException != null ? lastException.getMessage() : "unknown error");
        RechargeResult failResult = RechargeResult.failure(errorMsg);
        notifyCallback(failResult, errorMsg);
        return failResult;
    }
    
    /**
     * 执行单次充值
     */
    private RechargeResult executeSingleRecharge(long paymentSatoshi) {
        // 1. 获取 Service 信息
        Service service = getServiceInfo();
        if (service == null) {
            return RechargeResult.failure("Failed to get service info");
        }
        
        String dealer = service.getDealer();
        if (dealer == null || dealer.isEmpty()) {
            String pubkey = service.getDealerPubkey();
            if (pubkey != null && !pubkey.isEmpty()) {
                dealer = KeyTools.pubkeyToFchAddr(pubkey);
                TimberLogger.d(TAG, "Derived dealer FID from dealerPubkey: %s", dealer);
            }
        }
        if (dealer == null || dealer.isEmpty()) {
            return RechargeResult.failure("Service dealer address not found (no dealer or dealerPubkey)");
        }
        
        // 2. 获取私钥
        if (prikeyProvider == null) {
            return RechargeResult.failure("Private key provider not configured");
        }
        byte[] prikey = prikeyProvider.decryptPrikey();
        if (prikey == null) {
            return RechargeResult.failure("Failed to decrypt private key. Is wallet unlocked?");
        }
        
        String myFid = this.mainFid;
        if (myFid == null || myFid.isEmpty()) {
            return RechargeResult.failure("Main FID not configured");
        }
        
        // 3. 获取可用 UTXOs
        // Prefer server-provided cashes from the PAYMENT_REQUIRED response
        double amountFch = FchUtils.satoshiToCoin(paymentSatoshi);
        List<Cash> cashList = null;

        FapiResponse lastResp = fapiClient.getLastResponse();
        if (lastResp != null && lastResp.getCode() != null
                && lastResp.getCode() == FapiCode.PAYMENT_REQUIRED
                && lastResp.getData() != null) {
            try {
                cashList = ObjectUtils.objectToList(lastResp.getData(), Cash.class);
                TimberLogger.i(TAG, "Using %d server-provided cashes from PAYMENT_REQUIRED response",
                        cashList != null ? cashList.size() : 0);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Failed to parse cashes from PAYMENT_REQUIRED response: %s", e.getMessage());
            }
        }

        // Fallback to API call if no server-provided cashes
        if (cashList == null || cashList.isEmpty()) {
            TimberLogger.d(TAG, "No server-provided cashes, trying cashValid API for %.8f FCH", amountFch);
            cashList = fapiClient.cashValid(myFid, amountFch, null, null, 1, 0);
        }

        if (cashList == null || cashList.isEmpty()) {
            return RechargeResult.failure("No valid UTXOs available. Insufficient balance?");
        }
        
        TimberLogger.d(TAG, "Found {} valid UTXOs for recharge", cashList.size());
        
        // 4. 构造输出
        List<Cash> outputs = new ArrayList<>();
        Cash sendTo = new Cash();
        sendTo.setOwner(dealer);
        sendTo.setAmount(amountFch);
        outputs.add(sendTo);
        
        // 5. 创建并签名交易
        String signedTx;
        try {
            String opReturn = (viaFid != null && !viaFid.isEmpty())
                    ? "{\"via\":\"" + viaFid + "\"}"
                    : null;
            RawTxInfo rawTxInfo = new RawTxInfo(myFid, cashList, outputs, opReturn, 0L, TxHandler.DEFAULT_FEE_RATE, null, RawTxInfo.VERSION_2);
            TxHandler txHandler = new TxHandler(FchMainNetwork.MAINNETWORK);
            signedTx = txHandler.signTx(rawTxInfo, prikey);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to create transaction: {}", e.getMessage());
            return RechargeResult.failure("Failed to create transaction: " + e.getMessage());
        }
        
        if (signedTx == null || signedTx.isEmpty()) {
            return RechargeResult.failure("Failed to create signed transaction");
        }
        
        // 6. 广播交易
        String txId = fapiClient.broadcastTx(signedTx);
        
        if (txId != null && Hex.isHex32(txId)) {
            return RechargeResult.success(txId, paymentSatoshi, dealer);
        } else {
            String errorMsg = "Failed to broadcast transaction";
            if (fapiClient.getLastResponse() != null && fapiClient.getLastResponse().getMessage() != null) {
                errorMsg = fapiClient.getLastResponse().getMessage();
            } else if (fapiClient.getLastError() != null) {
                errorMsg = fapiClient.getLastError();
            }
            return RechargeResult.failure(errorMsg);
        }
    }
    
    /**
     * 计算支付金额
     */
    private long calculatePaymentAmount() {
        Service service = getServiceInfo();
        if (service == null) {
            TimberLogger.e(TAG, "Cannot calculate payment amount: service info not available");
            return -1;
        }
        
        // 解析 pricePerKB
        long pricePerKbSatoshi = 0;
        if (service.getPricePerKB() != null && !service.getPricePerKB().isEmpty()) {
            try {
                double pricePerKbFch = Double.parseDouble(service.getPricePerKB());
                pricePerKbSatoshi = FchUtils.coinToSatoshi(pricePerKbFch);
            } catch (NumberFormatException e) {
                TimberLogger.w(TAG, "Invalid pricePerKB format: {}", service.getPricePerKB());
            }
        }
        
        // 解析 minPayment
        long minPaymentSatoshi = 0;
        if (service.getMinPayment() != null && !service.getMinPayment().isEmpty()) {
            try {
                double minPaymentFch = Double.parseDouble(service.getMinPayment());
                minPaymentSatoshi = FchUtils.coinToSatoshi(minPaymentFch);
            } catch (NumberFormatException e) {
                TimberLogger.w(TAG, "Invalid minPayment format: {}", service.getMinPayment());
            }
        }
        
        // 计算购买金额 = purchaseKb * pricePerKB
        long purchaseAmount = purchaseKb * pricePerKbSatoshi;
        
        // 取较大值
        long paymentAmount = Math.max(purchaseAmount, minPaymentSatoshi);
        
        if (paymentAmount <= 0) {
            TimberLogger.e(TAG, "Calculated payment amount is 0. pricePerKB={}, purchaseKb={}, minPayment={}", 
                    pricePerKbSatoshi, purchaseKb, minPaymentSatoshi);
            return -1;
        }
        
        TimberLogger.d(TAG, "Calculated payment: {} satoshi ({} FCH), purchaseKb={}, pricePerKB={}, minPayment={}", 
                paymentAmount, FchUtils.satoshiToCoin(paymentAmount), purchaseKb, pricePerKbSatoshi, minPaymentSatoshi);
        
        return paymentAmount;
    }
    
    /**
     * 获取 Service 信息（带缓存）
     */
    private Service getServiceInfo() {
        long now = System.currentTimeMillis();
        
        // 检查缓存
        if (cachedService != null && (now - serviceLoadedMs) < SERVICE_CACHE_TTL_MS) {
            return cachedService;
        }
        
        // 从服务端获取
        String sid = fapiClient.getServiceSid();
        if (sid == null || sid.isEmpty()) {
            TimberLogger.e(TAG, "Service SID not configured");
            return null;
        }
        
        try {
            Map<String, Service> serviceMap = fapiClient.entityByIds(
                    IndicesNames.SERVICE, Service.class, List.of(sid));
            
            if (serviceMap != null && serviceMap.containsKey(sid)) {
                cachedService = serviceMap.get(sid);
                serviceLoadedMs = now;
                TimberLogger.d(TAG, "Service info loaded: name={}, pricePerKB={}, minPayment={}, dealer={}", 
                        cachedService.getStdName(), cachedService.getPricePerKB(), 
                        cachedService.getMinPayment(), cachedService.getDealer());
                return cachedService;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get service info: {}", e.getMessage());
        }
        
        return cachedService;  // 返回过期的缓存（如果有）
    }
    
    /**
     * 判断是否是可重试的错误
     */
    private boolean isRetryableError(String errorMessage) {
        if (errorMessage == null) return false;
        String lower = errorMessage.toLowerCase();
        // UTXO 相关错误可重试
        return lower.contains("utxo") || 
               lower.contains("spent") || 
               lower.contains("double spend") ||
               lower.contains("input") ||
               lower.contains("missing") ||
               lower.contains("conflict") ||
               lower.contains("mempool");
    }
    
    /**
     * 通知回调
     */
    private void notifyCallback(RechargeResult result, String message) {
        if (rechargeCallback != null) {
            try {
                rechargeCallback.accept(result, message);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error in recharge callback: {}", e.getMessage());
            }
        }
    }
    
    // ==================== Getters and Setters ====================
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public boolean isRecharging() {
        return recharging.get();
    }
    
    public long getLastRechargeAttemptMs() {
        return lastRechargeAttemptMs;
    }
    
    public long getLastSuccessfulRechargeMs() {
        return lastSuccessfulRechargeMs;
    }
    
    public String getLastRechargeTxId() {
        return lastRechargeTxId;
    }
    
    public long getThreshold() {
        return threshold;
    }
    
    public long getPurchaseKb() {
        return purchaseKb;
    }
    
    public Service getCachedService() {
        return cachedService;
    }
    
    /**
     * Pre-cache service info (e.g. from bootstrap) so recharge can compute
     * payment amounts even when FAPI requests are blocked by credit limit.
     */
    public void setCachedService(Service service) {
        if (service != null) {
            this.cachedService = service;
            this.serviceLoadedMs = System.currentTimeMillis();
            TimberLogger.d(TAG, "Service info pre-cached: name=%s, pricePerKB=%s, minPayment=%s, dealer=%s",
                    service.getStdName(), service.getPricePerKB(), service.getMinPayment(), service.getDealer());
        }
    }
    
    public long getMaxPayment() {
        return maxPayment;
    }
    
    public boolean isStoppedDueToPriceLimit() {
        return stoppedDueToPriceLimit;
    }
    
    public String getPriceAlertMessage() {
        return priceAlertMessage;
    }
    
    /**
     * 重置价格告警状态（用于手动恢复或调整配置后）
     */
    public void resetPriceAlert() {
        stoppedDueToPriceLimit = false;
        priceAlertMessage = null;
        TimberLogger.i(TAG, "Price alert status reset");
    }
    
    public void setRechargeCallback(BiConsumer<RechargeResult, String> callback) {
        this.rechargeCallback = callback;
    }
    
    public void setViaFid(String viaFid) {
        this.viaFid = viaFid;
    }
    
    public String getViaFid() {
        return viaFid;
    }
    
    // ==================== Helper Methods ====================
    
    private static boolean getBool(Map<String, Object> map, String key, boolean defVal) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defVal;
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }
    
    private static long getLong(Map<String, Object> map, String key, long defVal) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defVal;
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (Exception e) {
            return defVal;
        }
    }
    
    private static int getInt(Map<String, Object> map, String key, int defVal) {
        if (map == null || !map.containsKey(key) || map.get(key) == null) return defVal;
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (Exception e) {
            return defVal;
        }
    }
    
    // ==================== Result Class ====================
    
    /**
     * 充值结果类型
     */
    public enum ResultType {
        SUCCESS,      // 充值成功
        FAILURE,      // 充值失败
        PRICE_ALERT   // 价格告警，充值被阻止
    }
    
    /**
     * 充值结果
     */
    public static class RechargeResult {
        private final ResultType type;
        private final boolean success;
        private final String txId;
        private final long amountSatoshi;
        private final long maxPayment;  // 最大支付限额（用于价格告警）
        private final String recipientFid;
        private final String message;
        private final long timestamp;
        
        private RechargeResult(ResultType type, boolean success, String txId, long amountSatoshi, 
                               long maxPayment, String recipientFid, String message) {
            this.type = type;
            this.success = success;
            this.txId = txId;
            this.amountSatoshi = amountSatoshi;
            this.maxPayment = maxPayment;
            this.recipientFid = recipientFid;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
        
        public static RechargeResult success(String txId, long amountSatoshi, String recipientFid) {
            return new RechargeResult(ResultType.SUCCESS, true, txId, amountSatoshi, 0, recipientFid, "Success");
        }
        
        public static RechargeResult failure(String message) {
            return new RechargeResult(ResultType.FAILURE, false, null, 0, 0, null, message);
        }
        
        /**
         * 价格告警：支付额超过最大限制
         * @param requestedAmount 请求的支付额（聪）
         * @param maxPayment 最大支付限额（聪）
         * @param message 告警消息
         */
        public static RechargeResult priceAlert(long requestedAmount, long maxPayment, String message) {
            return new RechargeResult(ResultType.PRICE_ALERT, false, null, requestedAmount, maxPayment, null, message);
        }
        
        public ResultType getType() { return type; }
        public boolean isSuccess() { return success; }
        public boolean isPriceAlert() { return type == ResultType.PRICE_ALERT; }
        public String getTxId() { return txId; }
        public long getAmountSatoshi() { return amountSatoshi; }
        public double getAmountFch() { return FchUtils.satoshiToCoin(amountSatoshi); }
        public long getMaxPayment() { return maxPayment; }
        public double getMaxPaymentFch() { return FchUtils.satoshiToCoin(maxPayment); }
        public String getRecipientFid() { return recipientFid; }
        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return switch (type) {
                case SUCCESS -> String.format("RechargeResult{type=SUCCESS, txId=%s, amount=%.8f FCH, to=%s}", 
                        txId, getAmountFch(), recipientFid);
                case PRICE_ALERT -> String.format("RechargeResult{type=PRICE_ALERT, requestedAmount=%.8f FCH, maxPayment=%.8f FCH}", 
                        getAmountFch(), getMaxPaymentFch());
                case FAILURE -> String.format("RechargeResult{type=FAILURE, message=%s}", message);
            };
        }
    }
}

