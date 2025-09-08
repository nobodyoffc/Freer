package com.fc.freer.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.fc.fc_ajdk.clients.ApiUrl;
import com.fc.fc_ajdk.constants.CodeMessage;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.apipData.SignInMode;
import com.fc.fc_ajdk.data.fcData.FcSession;
import com.fc.fc_ajdk.data.fchData.Multisign;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.serviceParams.ApipParams;
import com.fc.fc_ajdk.data.feipData.serviceParams.Params;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.ObjectUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.RequestMethod;
import com.fc.freer.R;
import com.fc.freer.initiate.ConfigureManager;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.model.ApiAccount;
import com.fc.freer.model.ApiEvent;
import com.fc.freer.model.ApiProvider;
import com.fc.freer.model.Configure;
import com.fc.freer.model.FcReplyBody;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxHandler;
import com.fc.freer.ui.UserConfirmDialog;
import com.fc.freer.utils.SecurePrikeyManager;
import com.google.gson.Gson;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.GET_SERVICE;
import static com.fc.fc_ajdk.clients.ApipClient.ApipApiNames.VERSION_1;

// 新增导入
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

// 新增导入 - 用于buyApi方法
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.SendTo;
import com.fc.fc_ajdk.handlers.AccountHandler;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.http.AuthType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.fc.fc_ajdk.constants.Constants.COIN_TO_SATOSHI;

public abstract class FcClient {

    private static final String TAG = "FcClient";
    public static final int TIMEOUT = 30;

    protected final FcHttpRequester fcHttpRequester;
    protected ApiProvider apiProvider;
    protected ApiAccount apiAccount;
    protected String urlHead;
    protected ApiEvent apiEvent;
    protected Service service;
    protected ExecutorService executorService;
    protected Handler mainHandler;

    private Boolean isFree;
    protected Boolean isConnected;
    protected boolean isServiceActive = false;
    private ApipClient defaultApipClient; // 新增：默认ApipClient

    public FcClient(Context context) {
        this(context, null, null);
    }

    public FcClient(Context context,String urlHead) {
        this.fcHttpRequester = new FcHttpRequester(context,urlHead);
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.urlHead = urlHead;
    }

    public FcClient(Context context,  ApiProvider apiProvider, ApiAccount apiAccount) {
        this.fcHttpRequester = new FcHttpRequester(context, apiProvider, apiAccount);
        this.apiProvider = apiProvider;
        this.apiAccount = apiAccount;
        if (apiAccount != null && apiAccount.getApiUrl() != null) {
            this.urlHead = apiAccount.getApiUrl();
        } else if (apiProvider != null && apiProvider.getApiUrl() != null) {
            this.urlHead = apiProvider.getApiUrl();
        }
        this.executorService = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }


    /**
     * Generic async method for all API operations
     *
     * Usage examples:
     *
     * // Ping a service
     * client.doAsync(() -> client.ping(RequestMethod.GET), (success, event) -> {
     *     if (success) {
     *         Log.d(TAG, "Ping successful");
     *     } else {
     *         Log.e(TAG, "Ping failed: " + event.getMessage());
     *     }
     * });
     *
     * // Sign in
     * client.doAsync(() -> client.signIn(privateKey, RequestBody.SignInMode.NORMAL), (success, event) -> {
     *     if (success) {
     *         Log.d(TAG, "Sign in successful");
     *     }
     * });
     *
     * // Fetch service info
     * client.doAsync(() -> client.fetchService(), (service, event) -> {
     *     if (service != null) {
     *         Log.d(TAG, "Service: " + service.getStdName());
     *     }
     * });
     *
     * // Buy API service
     * client.doAsync(() -> client.buyApi(context), (paidAmount, event) -> {
     *     if (paidAmount != null && paidAmount > 0) {
     *         Log.d(TAG, "Paid: " + paidAmount + " satoshis");
     *     }
     * });
     *
     * @param operation The synchronous operation to execute
     * @param callback The callback to handle the result
     */
    public <T> void doAsync(Supplier<T> operation, AsyncCallback<T> callback) {
        executorService.execute(() -> {
            T result = operation.get();
            mainHandler.post(() -> {
                this.apiEvent = apiEvent; // Update instance apiEvent with latest event
                callback.onResult(result, apiEvent);
            });
        });
    }

    /**
     * 检查服务并更新服务信息
     */
    public boolean updateService(Context context) {
        if (isServiceActive) {
            return true;
        }
        try {
            if (fcHttpRequester == null) {
                TimberLogger.e(TAG, "FcHttpRequester is null, cannot update service");
                return false;
            }
            
            TimberLogger.d(TAG, "Trying to connect to API service: %s", urlHead);
            if(fcHttpRequester.getApiUrl()==null && urlHead!=null){
                ApiUrl apiUrl = new ApiUrl();
                apiUrl.setUrlHead(urlHead);
                fcHttpRequester.setApiUrl(apiUrl);
            }

            //  执行服务发现
            service = fetchService(context);
            this.apiEvent = getApiEvent();

            if (service == null) {
                TimberLogger.e(TAG, "Failed to extract service from response");
                return false;
            }

            isServiceActive = true;

            ApipParams apipParams = Params.getParamsFromService(service,ApipParams.class);
            service.setParams(apipParams);

            TimberLogger.d(TAG, "Found service: %s", service.getStdName());

            // 4. 更新ApiProvider
            if (apiProvider == null) {
                apiProvider = new ApiProvider();
            }
            if (!apiProvider.updateWithService(service, Service.ServiceType.APIP)) {
                TimberLogger.e(TAG, "Failed to update ApiProvider from service: %s", service.getStdName());
                return false;
            }
            fcHttpRequester.setApiProvider(apiProvider);

            // 5. 更新ApiAccount
            if (apiAccount == null) {
                apiAccount = createApiAccountForService(apiProvider, service);
                if (apiAccount == null) {
                    TimberLogger.e(TAG, "Failed to create ApiAccount for service: %s", service.getStdName());
                    return false;
                }
            } else {
                if (!updateApiAccountWithService(apiAccount, apiProvider, service)) {
                    TimberLogger.e(TAG, "Failed to update ApiAccount for service: %s", service.getStdName());
                    return false;
                }
            }
            
            // 6. 将更新后的apiProvider和apiAccount保存到configure中并永久化
            boolean saved = ConfigureManager.saveApiProviderAndAccount(context, apiProvider, apiAccount);
            if (!saved) {
                TimberLogger.w(TAG, "Failed to save apiProvider and apiAccount to configure");
            }
            
            TimberLogger.d(TAG, "Successfully created ApipClient for service: %s", service.getStdName());
            return true;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in connectClient: %s", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 用户登录方法 - 使用私钥对请求体签名并发送POST请求
     *
     * @param context Application context for operations
     * @param priKey 用户私钥
     * @param signInMode   登录模式（NORMAL或REFRESH）
     * @return 登录是否成功
     */
    public boolean signIn(Context context, byte[] priKey, @Nullable SignInMode signInMode) {
        try {

            if (fcHttpRequester.getApiUrl() == null || fcHttpRequester.getApiUrl().getUrlHead() == null) {
                TimberLogger.w(TAG, "No API URL available for sign in");
                apiEvent = new ApiEvent(context);
                apiEvent.setCodeMessage(com.fc.fc_ajdk.constants.CodeMessage.Code3004RequestUrlIsAbsent);
                return false;
            }

            // 构建登录URL
            String signInUrlTail = buildUrlTail(null, ApipClient.ApipApiNames.VERSION_1, ApipClient.ApipApiNames.SIGN_IN,null);
            
            // 发送POST请求（使用自定义签名请求头）
            if (priKey == null || BytesUtils.isFilledKey(priKey)) {
                priKey = SecurePrikeyManager.fetchPrikeySilent(apiAccount.getUserPrikeyCipher());
            }
            apiEvent = fcHttpRequester.post(signInUrlTail, null, TIMEOUT, true, signInMode, AuthType.FC_SIGN_BODY, null,priKey);


            // 处理响应
            if (apiEvent.isSuccess() && apiEvent.getResponseBody() != null) {
                try {
                    FcReplyBody fcReplyBody = apiEvent.getResponseBody();
                    if (fcReplyBody.getCode() == 0) {
                        TimberLogger.d(TAG, "Sign in successful");
                        return true;
                    } else {
                        TimberLogger.w(TAG, "Sign in failed with code: %d, message: %s", 
                            fcReplyBody.getCode(), fcReplyBody.getMessage());
                        apiEvent.setCode(fcReplyBody.getCode());
                        apiEvent.setMessage(fcReplyBody.getMessage());
                        return false;
                    }
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error parsing sign in response: %s", e.getMessage());
                    apiEvent.setCode(com.fc.fc_ajdk.constants.CodeMessage.Code1030FailedToParseData);
                    apiEvent.setMessage("Error parsing sign in response: " + e.getMessage());
                    return false;
                }
            } else {
                if(apiEvent.getCode().equals(CodeMessage.Code1004InsufficientBalance)) {
                    this.apiAccount.setBalance(0L);
                    TimberLogger.w(TAG, "Sign in failed: insufficient balance");
                }
                TimberLogger.w(TAG, "Sign in request failed: %s", apiEvent.getMessage());
                return false;
            }
            
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in sign in: %s", e.getMessage(), e);
            apiEvent = new ApiEvent(context);
            apiEvent.setFullUrl(fcHttpRequester.getApiUrl() != null ? fcHttpRequester.getApiUrl().getUrlHead() : "N/A");
            apiEvent.setRequestMethod("POST");
            apiEvent.setCode(com.fc.fc_ajdk.constants.CodeMessage.Code1020OtherError);
            apiEvent.setMessage("Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * 发送ping请求验证服务连接（返回ApiEvent对象）
     */
    public boolean ping(Context context, RequestMethod method) {
        if (fcHttpRequester == null) {
            TimberLogger.w(TAG, "FcHttpRequester is null, cannot ping");
            apiEvent = new ApiEvent(context);
            apiEvent.setCodeMessage(com.fc.fc_ajdk.constants.CodeMessage.Code1020OtherError);
            return false;
        }
        
        AuthType authType = (method == RequestMethod.POST) ? AuthType.FC_SIGN_BODY : AuthType.FREE;
        
        Boolean result = request(
            null,           // sn
                ApipClient.ApipApiNames.VERSION_1,           // ver
                ApipClient.ApipApiNames.PING,         // apiName
                method,         // requestMethod
                null,           // fcdsl
                null,           // paramsMap
                authType,       // authType
                responseBody -> {
                    try {
                        return responseBody!=null && responseBody.getCode()!=null && responseBody.getCode()==CodeMessage.Code0Success;
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Error parsing ping data field: %s", e.getMessage());
                        return false;
                    }
                },
                context);
        
        return Boolean.TRUE.equals(result);
    }


    /**
     * 获取服务信息（返回Service对象）
     * 复用通用GET方法，专门用于服务发现
     */
    public Service fetchService(Context context) {
        try {
            Service service = null;
            if (fcHttpRequester.getApiUrl() == null || fcHttpRequester.getApiUrl().getUrlHead() == null) {
                TimberLogger.w(TAG, "No API URL available for service discovery");
                apiEvent = new ApiEvent(context);
                apiEvent.setCodeMessage(com.fc.fc_ajdk.constants.CodeMessage.Code3004RequestUrlIsAbsent);
                return null;
            }

            // 构建服务发现的URL后缀
            String serviceUrlTail = "/" + VERSION_1 + "/" + GET_SERVICE;

            TimberLogger.d(TAG, "Sending service discovery request with URL tail: %s", serviceUrlTail);

            // 复用通用GET方法，设置10秒超时，不更新账户信息
            apiEvent = fcHttpRequester.get(serviceUrlTail, 10);

            // 如果请求成功，需要特殊处理Service对象的解析
            if (apiEvent.isSuccess() && apiEvent.getResponseBody() != null) {
                try {
                    Gson gson = new Gson();
                    FcReplyBody fcReplyBody = apiEvent.getResponseBody();
                    if (fcReplyBody.getData() != null) {
                        // 解析Service对象
                        String serviceJson = ObjectUtils.objectToString(fcReplyBody.getData());
                        service = gson.fromJson(serviceJson, Service.class);

                        if (service != null) {
                            apiEvent.setCodeMessage(com.fc.fc_ajdk.constants.CodeMessage.Code0Success);
                            TimberLogger.d(TAG, "Service discovery successful: %s", service.getStdName());
                        } else {
                            apiEvent.setCodeMessage(com.fc.fc_ajdk.constants.CodeMessage.Code1030FailedToParseData);
                        }
                    } else {
                        apiEvent.setCodeMessage(com.fc.fc_ajdk.constants.CodeMessage.Code3005ResponseDataIsNull);
                    }
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error parsing service data: %s", e.getMessage());
                    apiEvent.setCode(com.fc.fc_ajdk.constants.CodeMessage.Code1030FailedToParseData);
                    apiEvent.setMessage("Error parsing service data: " + e.getMessage());
                }
            }

            return service;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in service discovery: %s", e.getMessage(), e);
            apiEvent = new ApiEvent(context);
            apiEvent.setFullUrl(fcHttpRequester.getApiUrl() != null ? fcHttpRequester.getApiUrl().getUrlHead() : "N/A");
            apiEvent.setRequestMethod("GET");
            apiEvent.setCode(com.fc.fc_ajdk.constants.CodeMessage.Code1020OtherError);
            apiEvent.setMessage("Exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * 构建URL后缀的辅助方法
     */
    protected String buildUrlTail(String sn, String ver, String apiName, String paramString) {
        StringBuilder urlTailBuilder = new StringBuilder();
        urlTailBuilder.append("/");
        if (sn != null && !sn.isEmpty()) urlTailBuilder.append(sn).append("/");
        if (ver != null && !ver.isEmpty()) urlTailBuilder.append(ver).append("/");
        urlTailBuilder.append(apiName);
        if (paramString != null && !paramString.isEmpty()) urlTailBuilder.append("?").append(paramString);
        return urlTailBuilder.toString();
    }


    /**
     * 为服务创建ApiAccount
     */
    public ApiAccount createApiAccountForService(ApiProvider apiProvider, Service service) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        try {
            ApiAccount apiAccount = new ApiAccount();

            // 设置基本信息
            apiAccount.setProviderId(apiProvider.getId());
            apiAccount.setApiUrl(apiProvider.getApiUrl());
            apiAccount.setUserPubkey(setting.getPubkey());
            apiAccount.setUserPrikeyCipher(setting.getPrikeyCipher());
            apiAccount.setVia(com.fc.freer.FreerApplication.FREER_APP_DEALER);

            // 生成账户ID
            String accountId = apiAccount.makeApiAccountId(apiProvider.getId(), setting.getFid());
            apiAccount.setId(accountId);

            // 设置用户信息
            String currentFid = setting.getFid();
            if (currentFid != null) {
                apiAccount.setUserId(currentFid);
                apiAccount.setUserName(currentFid);
            } else {
                TimberLogger.w(TAG, "No current user FID found");
                return null;
            }

            // 设置服务信息
            apiAccount.setService(service);
            Gson gson = new Gson();
            ApipParams params = gson.fromJson(gson.toJson(service.getParams()), ApipParams.class);
            apiAccount.setApipParams(params);

            // 设置超时时间
            apiAccount.setTimeout(30000); // 30秒

            return apiAccount;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error creating ApiAccount: %s", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 更新现有ApiAccount的服务信息
     */
    public boolean updateApiAccountWithService(ApiAccount apiAccount, ApiProvider apiProvider, Service service) {
        if (apiProvider == null || service == null) {
            return false;
        }
        if(apiAccount == null )apiAccount= new ApiAccount();
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        try {
            // 更新基本信息（只更新有新值的字段）
            if (apiProvider.getId() != null) {
                apiAccount.setProviderId(apiProvider.getId());
            }
            if (apiProvider.getApiUrl() != null) {
                apiAccount.setApiUrl(apiProvider.getApiUrl());
            }
            if (setting.getPubkey() != null) {
                apiAccount.setUserPubkey(setting.getPubkey());
            }
            if (setting.getPrikeyCipher() != null) {
                apiAccount.setUserPrikeyCipher(setting.getPrikeyCipher());
            }

            // 更新用户信息
            String currentFid = setting.getFid();
            if (currentFid != null) {
                apiAccount.setUserId(currentFid);
                apiAccount.setUserName(currentFid);
                
                // 重新生成账户ID
                String accountId = apiAccount.makeApiAccountId(apiProvider.getId(), currentFid);
                apiAccount.setId(accountId);
            }

            // 更新服务信息
            apiAccount.setService(service);
            Gson gson = new Gson();
            ApipParams params = gson.fromJson(gson.toJson(service.getParams()), ApipParams.class);
            apiAccount.setApipParams(params);

            return true;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating ApiAccount: %s", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 购买API服务 - 向服务提供商支付费用
     * 注意：此方法应该在后台线程中调用，因为它包含网络请求和长时间等待
     * @param activityContext Activity context for showing user dialogs
     * @return 支付的金额（以satoshi为单位），如果失败返回null
     */
    public Long buyApi(Context activityContext) {
        try {
            if (apiAccount == null) {
                TimberLogger.e(TAG, "ApiAccount is null, cannot buy API");
                return null;
            }

            if (apiAccount.getUserPrikeyCipher() == null) {
                TimberLogger.e(TAG, "User private key cipher is null");
                return null;
            }

            if (service == null) {
                TimberLogger.e(TAG, "Service is null, cannot buy API");
                return null;
            }

            if (apiAccount.getServiceParams() == null) {
                apiAccount.setServiceParams((Params) service.getParams());
            }

            // 使用用户的FID作为sender，而不是apiAccount.getId()
            String sender = apiAccount.getUserId();
            if (sender == null || sender.isEmpty()) {
                TimberLogger.e(TAG, "User ID is null or empty, cannot buy API");
                return null;
            }
            
            boolean isMultisign = sender.startsWith("3");
            Multisign multisign = getMultisignInfo(sender, isMultisign);

            // Use the provided activity context
            TimberLogger.d(TAG, "buyApi using context: %s", activityContext != null ? activityContext.getClass().getSimpleName() : "null");

            // 获取最小支付金额
            Long minPay = FchUtils.coinStrToSatoshi(apiAccount.getServiceParams().getMinPayment());
            if (minPay == null) {
                minPay = AccountHandler.DEFAULT_MIN_PAYMENT;
            }

            // 获取服务价格
            Long price = FchUtils.coinStrToSatoshi(apiAccount.getServiceParams().getPricePerKBytes());
            if (price == null) {
                TimberLogger.w(TAG, "The price of APIP service is 0.");
                price = 0L;
            }

            // 计算支付金额
            double payValue = (double) Math.max(price * ApiAccount.orderRequestTimes, minPay) / COIN_TO_SATOSHI;

            // 创建支付目标列表
            List<SendTo> sendToList = new ArrayList<>();
            SendTo sendTo = new SendTo();
            sendTo.setFid(apiAccount.getServiceParams().getDealer());
            sendTo.setAmount(payValue);
            sendToList.add(sendTo);

            // 获取有效的现金列表
            List<Cash> cashList = getValidCashList(apiAccount.getUserId(), payValue, sendToList, isMultisign, multisign,activityContext);

            if (cashList == null || cashList.isEmpty()) {
                if (activityContext != null) {
                    mainHandler.post(() -> Toast.makeText(activityContext, R.string.no_valid_cash_found_for_payment, Toast.LENGTH_LONG).show());
                }
                apiEvent.setCodeMessage(CodeMessage.Code2007CashNoFound);
                return null;
            }

            List<Cash> payingCashList;
            try {
                payingCashList = Cash.makeCashListForPay(cashList);
                if (payingCashList.isEmpty()) {
                    TimberLogger.e(TAG, "Failed to create paying cash list from valid cash list");
                    return null;
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error creating paying cash list: %s", e.getMessage(), e);
                return null;
            }

            RawTxInfo txInfo;
            try {
                txInfo = new RawTxInfo(sender, payingCashList, sendToList, null, null, null, multisign, null);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error creating transaction info: %s", e.getMessage(), e);
                return null;
            }

            // Check user setting for transaction confirmation
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            
            if (setting != null && setting.getUserConfirmTx()) {
                // Show confirmation dialog to user
                return showConfirmationDialogAndProceed(txInfo, activityContext, payValue, cashList);
            } else {
                // No confirmation needed, proceed directly
                return processDirectApiPurchase(txInfo, activityContext, payValue, cashList);
            }

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in buyApi: %s", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 显示确认对话框并处理API购买
     */
    private Long showConfirmationDialogAndProceed(RawTxInfo txInfo, Context contextForDialog, double payValue, List<Cash> cashList) {
        final Object lock = new Object();
        final boolean[] userConfirmed = {false};
        final boolean[] dialogAnswered = {false};
        
        mainHandler.post(() -> {
            try {
                String title = contextForDialog.getString(R.string.confirm_api_payment);
                String message = contextForDialog.getString(R.string.confirm_api_payment_message, 
                    payValue, apiAccount.getServiceParams().getDealer());
                
                UserConfirmDialog dialog = new UserConfirmDialog(contextForDialog, title, message, choice -> {
                    synchronized (lock) {
                        userConfirmed[0] = (choice == UserConfirmDialog.Choice.YES);
                        dialogAnswered[0] = true;
                        lock.notify();
                    }
                });
                
                dialog.show();
                
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error showing confirmation dialog: %s", e.getMessage());
                synchronized (lock) {
                    userConfirmed[0] = false;
                    dialogAnswered[0] = true;
                    lock.notify();
                }
            }
        });
        
        // Wait for user confirmation
        synchronized (lock) {
            while (!dialogAnswered[0]) {
                try {
                    lock.wait(30000); // 30 second timeout
                    if (!dialogAnswered[0]) {
                        TimberLogger.w(TAG, "User confirmation dialog timeout");
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    TimberLogger.w(TAG, "Confirmation dialog wait interrupted");
                    return null;
                }
            }
        }
        
        if (!userConfirmed[0]) {
            TimberLogger.i(TAG, "User cancelled API purchase");
            return null;
        }
        
        // User confirmed, proceed with purchase
        return processDirectApiPurchase(txInfo, contextForDialog, payValue, cashList);
    }

    /**
     * 处理直接API购买（无需用户确认）
     */
    private Long processDirectApiPurchase(RawTxInfo txInfo, Context contextForDialog, double payValue, List<Cash> cashList) {
        try {
            boolean isMultisign = txInfo.getSender().startsWith("3");
            
            String signedTx = null;
            if (isMultisign) {
                // For multisign, show transaction info to user (not implemented here)
                TimberLogger.i(TAG, "Multisign transaction requires external signing");
                return null;
            } else {
                // Use SecurePrikeyManager to get private key with user confirmation
                byte[] prikey = SecurePrikeyManager.requestPrikeySync(contextForDialog,
                        "To buy API service from "+apiAccount.getApiUrl(), apiAccount.getUserPrikeyCipher());

                if (prikey == null) {
                    TimberLogger.w(TAG, "User denied private key access for API purchase or failed to get private key");
                    return null;
                }
                TxHandler txHandler = new TxHandler();
                signedTx = txHandler.signTx(txInfo, prikey);
                SecurePrikeyManager.erasePrikey(prikey);
            }

            if (signedTx == null) {
                TimberLogger.e(TAG, "Failed to create signed transaction");
                return null;
            }

            // 广播交易
            ApipClient broadcastClient = defaultApipClient;
            if (broadcastClient == null && this instanceof ApipClient) {
                broadcastClient = (ApipClient) this;
            }
            
            if (broadcastClient == null) {
                TimberLogger.e(TAG, "No APIP client available to broadcast transaction");
                return null;
            }
            
            String result = broadcastClient.broadcastTx(signedTx, RequestMethod.GET, AuthType.FREE,contextForDialog);
            if (!Hex.isHex32(result)) {
                TimberLogger.e(TAG, "Failed to buy APIP service. Failed to broadcast TX: %s", result);
                return null;
            }

            TimberLogger.d(TAG, "TxId: %s", result);
            TimberLogger.i(TAG, "Paid for APIP service: %f f to %s by %s", payValue, apiAccount.getServiceParams().getDealer(), result);

            // 等待交易确认
            Boolean confirmed = waitConfirmation(cashList.get(0).getId(), broadcastClient, contextForDialog);
            if (confirmed == null || !confirmed) {
                return null;
            }

            // 记录支付信息
            if (apiAccount.getPayments() == null) {
                apiAccount.setPayments(new HashMap<>());
            }
            apiAccount.getPayments().put(result, payValue);

            // 支付成功后尝试重新登录，最多重试20次，每次间隔30秒
            if (retrySignInAfterPayment(contextForDialog)) {
                TimberLogger.i(TAG, "Successfully signed in after API purchase");
                // 返回支付的金额（以satoshi为单位）
                return FchUtils.coinToSatoshi(payValue);
            } else {
                TimberLogger.w(TAG, "Failed to sign in after API purchase, but payment was successful");
                // 即使登录失败，支付已成功，仍返回支付金额
                return FchUtils.coinToSatoshi(payValue);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in processDirectApiPurchase: %s", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 支付成功后重试登录，最多20次，每次间隔30秒
     * 使用静默方式获取私钥，避免重复弹窗
     */
    private boolean retrySignInAfterPayment(Context context) {
        final int MAX_RETRY_ATTEMPTS = 20;
        final long RETRY_INTERVAL_MS = 30 * 1000; // 30 seconds
        
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                TimberLogger.d(TAG, "Attempting sign in after payment, attempt %d/%d", attempt, MAX_RETRY_ATTEMPTS);
                
                // Get private key silently for each attempt (no user dialog)
                byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(apiAccount.getUserPrikeyCipher());
                
                if (prikey == null) {
                    TimberLogger.e(TAG, "Failed to fetch private key silently for sign in attempt %d", attempt);
                    return false;
                }
                
                boolean signInResult = signIn(context, prikey, SignInMode.NORMAL);
                
                if (signInResult) {
                    // Update session after successful sign-in
                    boolean sessionUpdated = updateSessionAfterSignIn(prikey, context);
                    if (sessionUpdated) {
                        TimberLogger.i(TAG, "Sign in and session update successful after payment on attempt %d", attempt);
                        return true;
                    } else {
                        TimberLogger.w(TAG, "Sign in successful but session update failed on attempt %d", attempt);
                        // Continue to retry since session update failed
                    }
                } else {
                    TimberLogger.w(TAG, "Sign in failed on attempt %d/%d", attempt, MAX_RETRY_ATTEMPTS);
                    
                    // If not the last attempt, wait before retrying
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        TimberLogger.d(TAG, "Waiting %d seconds before next sign in attempt", RETRY_INTERVAL_MS / 1000);
                        Thread.sleep(RETRY_INTERVAL_MS);
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                TimberLogger.w(TAG, "Sign in retry interrupted");
                return false;
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error during sign in attempt %d: %s", attempt, e.getMessage());
                
                // If not the last attempt, wait before retrying
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        
        TimberLogger.e(TAG, "Failed to sign in after %d attempts following successful payment", MAX_RETRY_ATTEMPTS);
        return false;
    }


    /**
     * General FCDSL request method with flexible parameters
     *
     * @param sn              Service number (e.g., "sn18")
     * @param ver             API version (e.g., "v1")
     * @param apiName         API endpoint name
     * @param requestMethod   HTTP method (GET or POST)
     * @param fcdsl           FCDSL query object
     * @param paramsMap       Additional parameters map
     * @param authType        Authentication type
     * @param extractor       Function to extract data from response
     * @return Extracted data on success, null on failure
     */
    protected <T> T request(String sn, String ver, String apiName,
                            RequestMethod requestMethod, Fcdsl fcdsl,
                            Map<String, String> paramsMap,
                            AuthType authType, Function<FcReplyBody, T> extractor, Context context) {

        apiEvent = new ApiEvent(context);

        if (requestMethod == RequestMethod.GET) {
            // Convert FCDSL to URL parameters for GET requests
            if (fcdsl != null) {
                String urlParamsStr = com.fc.fc_ajdk.data.apipData.Fcdsl.fcdslToUrlParams(fcdsl);
                Map<String, String> fcdslParams = com.fc.fc_ajdk.data.apipData.Fcdsl.urlParamsStrToMap(urlParamsStr);
                if (paramsMap == null) {
                    paramsMap = fcdslParams;
                } else if (fcdslParams != null) {
                    paramsMap.putAll(fcdslParams);
                }
            }
            String paramString = NetworkUtils.buildQueryString(paramsMap);
            String urlTail = buildUrlTail(sn, ver, apiName, paramString);

            return executeRequestWithHandling(
                    () -> fcHttpRequester.get(urlTail, TIMEOUT, true, authType),
                    extractor,
                    apiName,context
            );
        } else {
            // POST request with FCDSL
            final com.fc.fc_ajdk.data.apipData.Fcdsl finalFcdsl;
            if (paramsMap != null && !paramsMap.isEmpty()) {
                if (fcdsl == null) {
                    finalFcdsl = new com.fc.fc_ajdk.data.apipData.Fcdsl();
                } else {
                    finalFcdsl = fcdsl;
                }
                finalFcdsl.addOther(paramsMap);
            } else {
                finalFcdsl = fcdsl;
            }

            String urlTail = buildUrlTail(sn, ver, apiName, null);

            return executeRequestWithHandling(
                    () -> fcHttpRequester.post(urlTail, finalFcdsl, TIMEOUT, true, authType),
                    extractor,
                    apiName,context
            );
        }
    }

    /**
     * Check the result of an API event and handle special response codes
     *
     * @param originalApiEvent The API event to check
     * @param originalRequest Original request callback to retry if needed
     * @param context Activity context for showing dialogs (if null, uses stored context)
     * @return CheckResultAction indicating the action taken
     */
    public CheckResultAction checkResult(ApiEvent originalApiEvent, Runnable originalRequest, Context context) {
        if (originalApiEvent == null) {
            TimberLogger.w(TAG, "ApiEvent or responseBody is null");
            return CheckResultAction.FAILED;
        }

        int code = originalApiEvent.getCode();

        // 1. If the responseBody.code is CodeMessage.Code0Success, extract balance and return SUCCESS
        if (code == CodeMessage.Code0Success) {

            TimberLogger.d(TAG, "API request successful");
            updateApiAccountFromResponse(originalApiEvent.getResponseBody());

            // Check if remaining access count is below minimum threshold
            if (apiAccount != null && apiAccount.getRemainRequestCount() != null &&
                    apiAccount.getRemainRequestCount() < com.fc.freer.utils.ApiCenter.MIN_REST_REQUEST_KB) {
                TimberLogger.w(TAG, "Remaining access count (%d) is below minimum threshold (%d), buying API service",
                        apiAccount.getRemainRequestCount(), com.fc.freer.utils.ApiCenter.MIN_REST_REQUEST_KB);

                // Buy API service synchronously since we're already in background thread
                try {
                    Long paidAmount = buyApi(context);
                    if (paidAmount != null && paidAmount > 0) {
                        TimberLogger.i(TAG, "Successfully bought API service for %d satoshis due to low remaining count", paidAmount);
                    } else {
                        TimberLogger.w(TAG, "Failed to buy API service for low remaining count");
                    }
                } catch (Exception e) {
                    TimberLogger.e(TAG, "Error buying API service for low remaining count: %s", e.getMessage());
                }
            }
            isConnected=true;
            return CheckResultAction.SUCCESS;
        }

        // 2. If the responseBody.code is Code1009SessionTimeExpired, call signIn to request new FcSession
        if (code == CodeMessage.Code1009SessionTimeExpired || code == CodeMessage.Code1023MissSessionKey) {
            TimberLogger.w(TAG, CodeMessage.getMsg(code));

            try {
                
                // Get private key synchronously since we're in background thread
                byte[] prikey = SecurePrikeyManager.requestPrikeySync(context,
                        "To sign in the API service of "+apiAccount.getApiUrl(), apiAccount.getUserPrikeyCipher());
                
                if (prikey == null) {
                    TimberLogger.w(TAG, "User denied session renewal request");
                    return CheckResultAction.FAILED;
                }

                boolean signInResult = signIn(context, prikey, SignInMode.NORMAL);
                if (signInResult) {
                    TimberLogger.d(TAG, "Sign in successful, updating session");

                    // Update session after successful sign-in
                    boolean sessionUpdated = updateSessionAfterSignIn(prikey, context);
                    if (sessionUpdated) {
                        // Retry the original request if provided
                        if (originalRequest != null) {
                            TimberLogger.d(TAG, "Retrying original request after successful sign in");
                            originalRequest.run();
                        }
                        
                        SecurePrikeyManager.erasePrikey(prikey);
                        
                        return CheckResultAction.SESSION_RENEWED;
                    } else {
                        TimberLogger.e(TAG, "Failed to update session after sign in");
                        SecurePrikeyManager.erasePrikey(prikey);

                        return CheckResultAction.FAILED;
                    }
                } else {
                    // Check if sign in failed due to insufficient balance
                    if (this.apiEvent.getCode() == CodeMessage.Code1004InsufficientBalance) {
                        TimberLogger.w(TAG, "Sign in failed due to insufficient balance during session renewal, attempting to buy API");
                        SecurePrikeyManager.erasePrikey(prikey);
                        
                        // Buy API service and retry
                        Long paid = buyApi(context);
                        if (paid != null && paid > 0) {
                            TimberLogger.i(TAG, "API purchased successfully during session renewal, retrying original request");
                            // buyApi already handles sign-in and session update, just retry the original request
                            if (originalRequest != null) {
                                originalRequest.run();
                            }
                            return CheckResultAction.API_PURCHASED;
                        } else {
                            TimberLogger.e(TAG, "Failed to buy API during session renewal");
                            return CheckResultAction.FAILED;
                        }
                    } else {
                        SecurePrikeyManager.erasePrikey(prikey);
                        return CheckResultAction.FAILED;
                    }
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error during session renewal: %s", e.getMessage());
                return CheckResultAction.FAILED;
            }
        }

        // 3. If the responseBody.code is Code1004InsufficientBalance, buy API and sign in again
        if (code == CodeMessage.Code1004InsufficientBalance) {
            TimberLogger.w(TAG, "Insufficient balance, attempting to buy API service");
            Long paid = buyApi(context);
            if (paid != null && paid > 0) {
                // buyApi already handles sign-in, just retry the original request
                if (originalRequest != null) {
                    originalRequest.run();
                }
                return CheckResultAction.API_PURCHASED;
            }
            return CheckResultAction.FAILED;
        }

        // For other error codes, log the error and return FAILED
        TimberLogger.w(TAG, "API request failed with code: %d, message: %s", code, originalApiEvent.getMessage());
        return CheckResultAction.FAILED;
    }

//
//    /**
//     * 处理购买成功后的登录流程
//     */
//    private void handleSignInAfterPurchase(Runnable originalRequest, Context context) {
//        try {
//            // Get private key for session renewal after purchase
//            byte[] prikey = SecurePrikeyManager.requestPrikeySync(context,
//                    "To sign in the API service of "+apiAccount.getApiUrl());
//
//            if (prikey == null) {
//                TimberLogger.w(TAG, "User denied session renewal after API purchase");
//                return;
//            }
//
//            // Sign in again after purchase
//            boolean signInResult = signIn(context,prikey, SignInMode.NORMAL);
//            if (signInResult) {
//                TimberLogger.d(TAG, "Sign in successful after API purchase");
//
//                // Update FcSession in ApiAccount
//                FcSession fcSession = extractSessionFromSignInResult(prikey, context);
//                if (fcSession != null && apiAccount != null) {
//                    apiAccount.setSession(fcSession);
//                    apiAccount.setSessionKey(fcSession.getKeyBytes());
//
//                    // Save updated ApiAccount
//                    ConfigureManager.saveApiAccount(context, apiAccount);
//
//                    // Retry the original request if provided
//                    if (originalRequest != null) {
//                        TimberLogger.d(TAG, "Retrying original request after successful API purchase and sign in");
//                        originalRequest.run();
//                    }
//
//                    SecurePrikeyManager.erasePrikey(prikey);
//                    TimberLogger.i(TAG, "API purchase and sign in completed successfully");
//                } else {
//                    TimberLogger.e(TAG, "Failed to extract session after API purchase");
//                    SecurePrikeyManager.erasePrikey(prikey);
//                }
//            } else {
//                TimberLogger.e(TAG, "Sign in failed after API purchase");
//                SecurePrikeyManager.erasePrikey(prikey);
//            }
//
//        } catch (Exception e) {
//            TimberLogger.e(TAG, "Error during API purchase sign in: %s", e.getMessage());
//        }
//    }

    /**
     * Check result action enum
     */
    public enum CheckResultAction {
        SUCCESS,            // Request was successful
        SESSION_RENEWED,    // Session was renewed successfully
        API_PURCHASED,      // API was purchased successfully
        RETRIED,           // Original request was retried after recovery
        FAILED            // Recovery failed or other error
    }

    /**
     * Helper method to execute HTTP requests with automatic checkResult handling
     * Reduces code duplication across API methods
     *
     * @param requestSupplier Function to execute the HTTP request
     * @param responseExtractor Function to extract data from successful response
     * @param operationName Name of the operation for logging
     * @param activityContext Activity context for showing user dialogs
     * @return Extracted data on success, null on failure
     */
    protected <T> T executeRequestWithHandling(
            Supplier<ApiEvent> requestSupplier,
            Function<FcReplyBody, T> responseExtractor,
            String operationName,
            Context activityContext) {
        try {
            ApiEvent apiEvent = requestSupplier.get();
            this.apiEvent = apiEvent;  // Update instance apiEvent

            CheckResultAction action = checkResult(apiEvent, () -> {
                ApiEvent retryEvent = requestSupplier.get();
                if (retryEvent != null && retryEvent.isSuccess()) {
                    TimberLogger.d(TAG, "%s retry successful", operationName);
                    // Update the instance apiEvent with the successful retry result
                    this.apiEvent = retryEvent;
                }
            }, activityContext);

            if (isSuccessfulAction(action)) {
                // Use instance apiEvent which may have been updated by retry
                FcReplyBody responseBody = this.apiEvent.getResponseBody();
                if (responseBody != null ) {
                    return responseExtractor.apply(responseBody);
                }
            }

            TimberLogger.w(TAG, "%s failed with action: %s", operationName, action);
            return null;

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error in %s: %s", operationName, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Check if the given action represents a successful result
     */
    private boolean isSuccessfulAction(CheckResultAction action) {
        return action == CheckResultAction.SUCCESS ||
                action == CheckResultAction.SESSION_RENEWED ||
                action == CheckResultAction.API_PURCHASED ||
                action == CheckResultAction.RETRIED;
    }

    /**
     * Update ApiAccount with balance and remaining request count from response
     */
    private void updateApiAccountFromResponse(FcReplyBody responseBody) {
        try {
            if (responseBody == null || responseBody.getBalance() == null || apiAccount == null) {
                return;
            }

            Long balance = responseBody.getBalance();
            TimberLogger.d(TAG, "Current balance: %d", balance);

            // Update balance in ApiAccount
            apiAccount.setBalance(balance);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating ApiAccount from response: %s", e.getMessage(), e);
        }
    }

    /**
     * Update session information in ApiAccount after successful sign-in
     * @param prikey Private key used for session decryption
     * @param context Application context
     * @return true if session was successfully updated, false otherwise
     */
    private boolean updateSessionAfterSignIn(byte[] prikey, Context context) {
        try {
            FcSession fcSession = extractSessionFromSignInResult(prikey, context);
            if (fcSession != null && apiAccount != null) {
                apiAccount.setSessionKey(fcSession.makeKeyBytes());
                fcSession.setKey(null);
                apiAccount.setSession(fcSession);
                apiAccount.setClient(this);

                // Save updated ApiAccount
                ConfigureManager.saveApiAccount(context, apiAccount);
                TimberLogger.d(TAG, "Session updated successfully after sign-in");
                return true;
            } else {
                TimberLogger.e(TAG, "Failed to extract or update session after sign-in");
                return false;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error updating session after sign-in: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Extract FcSession from sign in result with private key for session processing
     */
    private FcSession extractSessionFromSignInResult(byte[] prikey, Context context) {
        if (apiEvent == null || apiEvent.getResponseBody() == null || apiEvent.getResponseBody().getData() == null) {
            return null;
        }

        try {
            Object data = apiEvent.getResponseBody().getData();
            Gson gson = new Gson();
            FcSession serverSession = gson.fromJson(gson.toJson(data), FcSession.class);

            // Process the session key encryption (similar to ApipClient implementation)
            byte[] symkey = ConfigureManager.getInstance().getSymkey();
            if (symkey != null && prikey != null && apiAccount != null) {
                return processSessionFromSignIn(serverSession, prikey, symkey,context);
            }
            return serverSession;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error extracting session from sign in result: %s", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Process session from sign in result (similar to ApipClient.makeSessionFromSignInEccResult)
     */
    private FcSession processSessionFromSignIn(FcSession fcSession, byte[] prikey, byte[] symkey, Context context) {
        try {
            String sessionKeyCipher = fcSession.getKeyCipher();

            if(BytesUtils.isFilledKey(prikey))prikey = SecurePrikeyManager.fetchPrikeySilent(apiAccount.getUserPrikeyCipher());
            String fid = com.fc.fc_ajdk.core.crypto.KeyTools.prikeyToFid(prikey);

            com.fc.fc_ajdk.core.crypto.Decryptor decryptor = new com.fc.fc_ajdk.core.crypto.Decryptor();
            CryptoDataByte cryptoDataByte = decryptor.decryptJsonByAsyOneWay(sessionKeyCipher, prikey);
            if (cryptoDataByte.getCode() != 0) {
                return null;
            }
            byte[] sessionKey = cryptoDataByte.getData();

            com.fc.fc_ajdk.core.crypto.Encryptor encryptor = new com.fc.fc_ajdk.core.crypto.Encryptor(com.fc.fc_ajdk.data.fcData.AlgorithmId.FC_AesCbc256_No1_NrC7);
            CryptoDataByte newCryptoDataByte = encryptor.encryptBySymkey(sessionKey, symkey);
            if (newCryptoDataByte.getCode() != 0) {
                return null;
            }
            String newCipher = newCryptoDataByte.toJson();
            fcSession.setKeyCipher(newCipher);

            String sessionName = FcSession.makeSessionName(sessionKey);
            fcSession.setKey(com.fc.fc_ajdk.utils.Hex.toHex(sessionKey));
            fcSession.setId(sessionName);
            fcSession.setUserId(fid);

            return fcSession;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error processing session from sign in: %s", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Helper method to get multisign info for address
     */
    private Multisign getMultisignInfo(String sender, boolean isMultisign) {
        if (!isMultisign) return null;
        
        try {
            Configure configure = ConfigureManager.getInstance().getConfigure();
            if (configure != null && configure.getMainCidInfoMap() != null) {
                return configure.getMainCidInfoMap().get(sender).getMultisign();
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to get multisign info: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Helper method to get valid cash list for payment
     */
    private List<Cash> getValidCashList(String userId, double payValue, List<SendTo> sendToList, boolean isMultisign, Multisign multisign, Context activityContext) {
        try {
            CashManager cashManager = CashManager.getInstance();
            if (cashManager != null && cashManager.getMainFid() != null && 
                cashManager.getMainFid().equals(userId) && 
                !cashManager.getCashDB().isEmpty()) {
                
                List<Cash> cashList = cashManager.getValidCashes(payValue, 0L, sendToList.size(), 0, TxHandler.DEFAULT_FEE_RATE, multisign);
                TimberLogger.d(TAG, "Got cash list from CashManager: %d items", cashList != null ? cashList.size() : 0);
                return cashList;
            } else if (this instanceof ApipClient apipClient) {
                TimberLogger.d(TAG, "Getting cash list from ApipClient for user: %s, payValue: %f", userId, payValue);
                List<Cash> cashList = apipClient.cashValid(userId, payValue, null, sendToList.size(), null, AuthType.FREE, RequestMethod.GET,activityContext);
                TimberLogger.d(TAG, "Got cash list from ApipClient: %d items", cashList != null ? cashList.size() : 0);
                return cashList;
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error getting cash list: %s", e.getMessage(), e);
        }
        return null;
    }

    /**
     * 等待交易确认
     *
     * @param cashId     现金ID
     * @param apipClient APIP客户端用于查询交易状态
     * @param context
     */
    private Boolean waitConfirmation(String cashId, ApipClient apipClient, Context context) {
        if (apipClient == null) {
            TimberLogger.w(TAG, "No APIP client available for transaction confirmation");
            return null;
        }
        
        int maxAttempts = 20; // 最多等待20次，每次30秒
        int attempt = 0;
        
        while (attempt < maxAttempts) {
            try {
                TimeUnit.SECONDS.sleep(30);
                List<String> idList = new ArrayList<>();
                idList.add(cashId);
                Map<String, Cash> result = apipClient.cashByIds(RequestMethod.GET, AuthType.FREE, idList,context);
                if (result != null && result.get(cashId) != null && Boolean.FALSE.equals(result.get(cashId).isValid()) ) {
                    TimberLogger.i(TAG, "Transaction confirmed!");
                    return true;
                }
                TimberLogger.d(TAG, "Wait confirmation... attempt %d/%d", attempt + 1, maxAttempts);
                attempt++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                TimberLogger.w(TAG, "Waiting for confirmation interrupted");
                break;
            } catch (Exception e) {
                TimberLogger.w(TAG, "Error checking transaction confirmation: %s", e.getMessage());
                attempt++;
            }
        }
        
        if (attempt >= maxAttempts) {
            TimberLogger.w(TAG, "Transaction confirmation timeout after %d minutes", maxAttempts);
        }
        return false;
    }



    /**
     * Generic async callback interface
     */
    public interface AsyncCallback<T> {
        void onResult(T result, ApiEvent apiEvent);
    }




    /**
     * 关闭客户端
     */
    public void shutdown() {
        if (fcHttpRequester != null) {
            fcHttpRequester.shutdown();
        }
        isServiceActive = false;
    }

    public String getUrlHead() {
        return urlHead;
    }

    public void setUrlHead(String urlHead) {
        this.urlHead = urlHead;
    }

    public ApiProvider getApiProvider() {
        return apiProvider;
    }

    public void setApiProvider(ApiProvider apiProvider) {
        this.apiProvider = apiProvider;
    }

    public ApiAccount getApiAccount() {
        return apiAccount;
    }

    public void setApiAccount(ApiAccount apiAccount) {
        this.apiAccount = apiAccount;
    }

    public boolean isFree() {
        return isFree;
    }

    public void setIsFree(boolean isFree) {
        this.isFree = isFree;
    }

    public Boolean getServiceActive() {
        return isServiceActive;
    }

    public void setServiceActive(Boolean serviceActive) {
        isServiceActive = serviceActive;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    public Boolean getFree() {
        return isFree;
    }

    public void setFree(Boolean free) {
        isFree = free;
    }

    public ApiEvent getApiEvent() {
        return apiEvent;
    }

    public void setApiEvent(ApiEvent apiEvent) {
        this.apiEvent = apiEvent;
    }

    public Boolean getConnected() {
        return isConnected;
    }

    public void setConnected(Boolean connected) {
        this.isConnected = connected;
    }

    public ApipClient getDefaultApipClient() {
        return defaultApipClient;
    }

    public void setDefaultApipClient(ApipClient defaultApipClient) {
        this.defaultApipClient = defaultApipClient;
    }
}
