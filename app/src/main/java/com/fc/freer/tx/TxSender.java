package com.fc.freer.tx;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.fapi.message.FapiResponse;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.fc_ajdk.data.fchData.P2SH;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.network.NetworkUtils;
import com.fc.freer.multisig.SignMultisigTxActivity;
import com.fc.freer.ui.WaitingDialog;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;

import java.util.ArrayList;
import java.util.List;

/**
 * General utility class for creating, signing, and broadcasting transactions
 */
public class TxSender {

    private static TxSender currentInstance = null;
    private TxCallback pendingCallback = null;
    private WaitingDialog waitingDialog = null;

    private void showWaitingDialog(Context context) {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        waitingDialog = new WaitingDialog(activity, activity.getString(R.string.loading));
                        waitingDialog.show();
                    }
                });
            }
        }
    }

    private void dismissWaitingDialog(Context context) {
        if (context instanceof Activity) {
            Activity activity = (Activity) context;
            activity.runOnUiThread(() -> {
                if (waitingDialog != null && waitingDialog.isShowing()) {
                    waitingDialog.dismiss();
                    waitingDialog = null;
                }
            });
        }
    }

    public interface TxCallback {
        void onSuccess(String txId);
        void onError(String errorMessage);
        void onUnsignedTx(RawTxInfo rawTxInfo);
        void onUnbroadcasted(String signedTxHex);
        default void onCancelled() {}
    }

    /**
     * Call this method when SendTxActivity finishes successfully
     * @param txId The transaction ID returned from the activity
     */
    public void onActivityResult(String txId) {
        if (pendingCallback != null && txId != null) {
            pendingCallback.onSuccess(txId);
            pendingCallback = null; // Clear the callback after use
        }
    }

    /**
     * Call this method when SendTxActivity finishes with an error
     * @param errorMessage The error message
     */
    public void onActivityError(String errorMessage) {
        if (pendingCallback != null) {
            pendingCallback.onError(errorMessage);
            pendingCallback = null; // Clear the callback after use
        }
    }

    /**
     * Gets the current active TxSender instance (for use by activities)
     * @return The current instance, or null if none
     */
    public static TxSender getCurrentInstance() {
        return currentInstance;
    }

    /**
     * Gets the pending callback (for use by activities)
     * @return The pending callback, or null if none
     */
    public TxCallback getPendingCallback() {
        return pendingCallback;
    }

    /**
     * Clears the pending callback and current instance, notifying the callback if still pending.
     */
    public void clearPendingCallback() {
        if (pendingCallback != null) {
            TxCallback cb = pendingCallback;
            pendingCallback = null;
            currentInstance = null;
            cb.onCancelled();
        } else {
            currentInstance = null;
        }
    }

    /**
     * Resolves the Multisig info of a sender FID.
     * A multisig (P2SH) FID owns no private key of its own, so its transactions
     * have to be built with the Multisig info and signed by the cosigners.
     *
     * @param sender The sender FID
     * @return The Multisig of the sender, or null if the sender is not multisig
     *         or its Multisig info is unknown locally
     */
    public static Multisig getMultisigOfSender(String sender) {
        if (sender == null || !sender.startsWith("3")) return null;
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null) return null;
        KeyInfo keyInfo = fidManager.getKeyInfoByFid(sender);
        return keyInfo == null ? null : keyInfo.getMultisign();
    }

    public void carveFeipWithRecipient(
            Context context,
            String sender,
            String recipient,
            Double amount,
            String feipJson,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback){
        List<Cash> cashList = new ArrayList<>();
        if(amount==null)amount= Cash.MIN_AMOUNT;
        Cash cash = new Cash(recipient,amount);
        cashList.add(cash);
        sendTx(context, sender, cashList, feipJson, prikey, Feip.CD_REQUIRED, TxHandler.DEFAULT_FEE_RATE, null, RawTxInfo.VERSION_2, null, cashManager, txHandler, fapiClient,callback,true);
    }

    public void carveSimpleFeip(
            Context context,
            String sender,
            String feipJson,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback){
        sendTx(context, sender, null, feipJson, prikey, Feip.CD_REQUIRED, TxHandler.DEFAULT_FEE_RATE, null, RawTxInfo.VERSION_2, null, cashManager, txHandler, fapiClient,callback,true);
    }
    
    /**
     * Creates, signs (if possible), and broadcasts a transaction
     *
     * @param context     The context
     * @param sender      The sender FID
     * @param outputs     List of Cash objects
     * @param opReturn    OP_RETURN message
     * @param prikey      Private key cipher (null for unsigned tx)
     * @param cd          CD value
     * @param feeRate     Fee rate
     * @param multisig   Multisig object (can be null)
     * @param ver         Transaction version
     * @param cashManager CashManager instance
     * @param txHandler   TxHandler instance
     * @param fapiClient  FapiClient instance
     * @param callback    Callback for results
     */
    public void sendTx(
            Context context,
            String sender,
            List<Cash> outputs,
            String opReturn,
            byte[] prikey, Long cd,
            Double feeRate,
            Multisig multisig,
            String ver,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback) {
        sendTx(context, sender, outputs, opReturn, prikey, cd, feeRate, multisig, ver,
                null, cashManager, txHandler, fapiClient, callback, false);
    }

    /**
     * Creates, signs (if possible), and broadcasts a transaction with confirmation option
     *
     * @param context     The context
     * @param sender      The sender FID
     * @param outputs     List of Cash objects
     * @param opReturn    OP_RETURN message
     * @param prikey      Private key cipher (null for unsigned tx)
     * @param requiredCd  CD value
     * @param feeRate     Fee rate
     * @param multisig   Multisig object (can be null)
     * @param ver         Transaction version
     * @param lockTime
     * @param cashManager CashManager instance
     * @param txHandler   TxHandler instance
     * @param fapiClient  FapiClient instance
     * @param callback    Callback for results
     * @param withConfirm Whether to show confirmation UI before sending
     */
    public void sendTx(
            Context context,
            String sender,
            List<Cash> outputs,
            String opReturn,
            byte[] prikey,
            Long requiredCd,
            Double feeRate,
            Multisig multisig,
            String ver,
            Long lockTime,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback,
            boolean withConfirm) {
        showWaitingDialog(context);
        try{
            if (sender == null || sender.isEmpty()) {
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onError("No sender FID");
                }
                return;
            }

            if(!sender.equals(cashManager.getLiveFid())){
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onError("The sender do not match the cash manager.");
                }
                return;
            }

            Long requestedCd = requiredCd;

            // A multisig FID has no prikey of its own; resolve its Multisig info
            // so the tx is built for the cosigners instead of failing to sign.
            Multisig senderMultisig = (multisig != null) ? multisig : getMultisigOfSender(sender);
            if (senderMultisig == null && sender.startsWith("3")) {
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onError(context.getString(R.string.failed_to_get_multisign_info_for_fid) + ": " + sender);
                }
                return;
            }

            new Thread(() -> {
                try {
                    // Resolve the effective CD on the worker thread: getBestHeight()
                    // is a network call and returns null when invoked on the main
                    // thread, which used to skip the below-CDD_CHECK_HEIGHT waiver
                    // and fail fresh FIDs with "No enough CD".
                    Long bestHeight = (fapiClient != null) ? fapiClient.getBestHeight() : null;
                    Long finalRequiredCd = Feip.getRequiredCd(bestHeight, requestedCd);
                    sendTxInternal(context, sender, outputs, opReturn, finalRequiredCd, feeRate,
                            senderMultisig, ver, lockTime, prikey, cashManager, txHandler, fapiClient, callback, false, withConfirm);
                } catch (Exception e) {
                    dismissWaitingDialog(context);
                    if (callback != null) {
                        callback.onError("Transaction creation failed: " + e.getMessage());
                    }
                }
            }).start();
        } catch (Exception e) {
            dismissWaitingDialog(context);
            if (callback != null) {
                callback.onError("Failed to create FEIP transaction: " + e.getMessage());
            }
        }
    }
    
    private void sendTxInternal(
            Context context,
            String sender,
            List<Cash> outputs,
            String opReturn,
            Long cdRequired,
            Double feeRate,
            Multisig multisig,
            String ver,
            Long lockTime,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback,
            boolean isRetry,
            boolean withConfirm) {
        
        try {
            // Step 1: Load valid cash list
            double payValue = 0.0;
            if (outputs != null) {
                for (Cash cash : outputs) {
                    payValue += cash.getAmount();
                }
            }
            
            int outputSize = (outputs != null ? outputs.size() : 0);
            int msgSize = (opReturn != null ? opReturn.getBytes().length : 0);

            // A FID whose first coins have only just arrived has an empty cash DB: nothing
            // fills it but the Cash page, so every carve here failed with "no valid cash"
            // while the coins sat on the chain. Fill it from the index instead of sending
            // the user to another screen to do it. The first load also applies the mempool
            // overlay, which is what picks up a first FCH that no block has confirmed yet.
            if (!isRetry && !cashManager.hasValidCashes()) {
                cashManager.refreshCashFromAPI(context);
                cashManager.commit();
            }

            List<Cash> validCashList = cashManager.getValidCashes(payValue, cdRequired, outputSize, msgSize,
                feeRate != null ? feeRate :TxHandler.DEFAULT_FEE_RATE, multisig,context);
            
            if (validCashList == null || validCashList.isEmpty()) {
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onError(context.getString(R.string.no_valid_cash_available_for_transaction));
                }
                return;
            }
            
            // Step 2: Create RawTxInfo
            RawTxInfo rawTxInfo = new RawTxInfo(sender, validCashList, outputs, opReturn, cdRequired,
                feeRate, multisig, ver);

            // Step 2.5: Show confirmation UI if requested. A multisig tx always
            // goes through the cosigner UI: no single prikey can sign it here.
            if (withConfirm || multisig != null) {
                // Store the callback for later use by the activity
                pendingCallback = callback;
                currentInstance = this;

                Intent intent;
                String txInfoJson;
                if (multisig != null) {
                    // Use SignMultisigTxActivity for multisig transactions.
                    // toNiceJson()/toJson() drop senderInfo, which carries the
                    // Multisig, so keep it here for the cosigner UI.
                    intent = new Intent(context, SignMultisigTxActivity.class);
                    txInfoJson = rawTxInfo.toJsonWithSenderInfo();
                } else {
                    // Use SendTxActivity for regular transactions
                    intent = new Intent(context, SendTxActivity.class);
                    txInfoJson = rawTxInfo.toNiceJson();
                }
                intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, txInfoJson);
                dismissWaitingDialog(context);
                context.startActivity(intent);
                return;
            }

            // Step 3: Create transaction
            Transaction tx = txHandler.createTx(rawTxInfo, MainNetParams.get());
            if (tx == null) {
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onError("Failed to create transaction");
                }
                return;
            }

            // Step 4: Check if we should sign and broadcast or show as QR
            if (prikey != null && !BytesUtils.isFilledKey(prikey)) {

                // Sign the transaction
                String signedTxHex = txHandler.signTx(rawTxInfo, prikey);
                if (signedTxHex == null || signedTxHex.isEmpty()) {
                    dismissWaitingDialog(context);
                    if (callback != null) {
                        callback.onError("Failed to sign transaction");
                    }
                    return;
                }

                // Check if transaction has lockTime in the future
                if (rawTxInfo.getLockTime() != null && rawTxInfo.getLockTime() > 0) {
                    Long bestHeight = fapiClient.getBestHeight();
                    if (bestHeight != null && rawTxInfo.getLockTime() > bestHeight) {
                        long blocksToWait = rawTxInfo.getLockTime() - bestHeight;
                        ToastUtils.makeText(context,
                            "Transaction is time-locked until block " + rawTxInfo.getLockTime() +
                            " (current: " + bestHeight + ", " + blocksToWait + " blocks to wait). " +
                            "Transaction signed but not broadcast.");
                        dismissWaitingDialog(context);
                        if (callback != null) {
                            callback.onUnbroadcasted(signedTxHex);
                        }
                        return;
                    }
                }

                // Check network connectivity before broadcasting
                if (NetworkUtils.isNetworkAvailable(context)) {
                    // Network is available, proceed with broadcasting
                    //TODO
                    TimberLogger.d("TxSender","TX:"+signedTxHex);

                    String result = fapiClient.broadcastTx(signedTxHex);
                    FapiResponse response = fapiClient.getLastResponse();
                    String message = response.getMessage();
                    if (Hex.isHex32(result)) {
                        if(!updateCashOfTx(signedTxHex, sender, context, cashManager))
                            ToastUtils.makeText(context,
                                    R.string.failed_to_update_cash_db
                            );
                        ToastUtils.makeText(context, R.string.tx_sent);
                        dismissWaitingDialog(context);
                        if (callback != null) {
                            callback.onSuccess(result);
                        }
                    } else {
                        // Handle specific error cases
                        if (message != null && (message.contains("-25") || message.contains("-26"))) {
                            // -25: Missing inputs (spent or never existed)
                            // -26: Mempool conflict (inputs already used in another mempool tx)
                            // Refresh cash DB and retry
                            if (!isRetry) {
                                String errorType = message.contains("-25") ? "missing inputs" : "mempool conflict";
                                TimberLogger.w("TxSender", "Transaction failed due to " + errorType + ", reconciling cash DB and retrying");

                                // The conflicting spend sits in the mempool right now, so the
                                // RPC-backed overlay sees it immediately, while the ES-backed
                                // full refresh lags by seconds and can re-introduce the very
                                // cash that just conflicted. Fall back to the full refresh
                                // (which re-applies the overlay) for -25, where the input may
                                // be confirmed-spent or unknown to the node, or when the
                                // overlay call itself failed. (Already on background thread.)
                                int changed = cashManager.applyMempoolOverlay(context);
                                if (message.contains("-25") || changed < 0) {
                                    cashManager.freshValidCashDB(context);
                                }

                                ToastUtils.makeText(context, context.getString(R.string.toast_cash_db_refreshed));

                                sendTxInternal(context, sender, outputs, opReturn, cdRequired,
                                        feeRate, multisig, ver, lockTime, prikey, cashManager, txHandler,
                                        fapiClient, callback, true, false);
                            } else {
                                // Already retried once, don't retry again
                                dismissWaitingDialog(context);
                                if (callback != null) {
                                    callback.onError("Transaction broadcast failed after retry: " + message);
                                }
                            }
                        } else if (message != null && message.contains("-27")) {
                            // Already confirmed on chain
                            if(!updateCashOfTx(signedTxHex, sender, context, cashManager))
                                ToastUtils.makeText(context,
                                        R.string.failed_to_update_cash_db
                                );
                            ToastUtils.makeText(context, R.string.tx_sent);
                            dismissWaitingDialog(context);
                            if (callback != null) {
                                callback.onSuccess(result);
                            }
                        }else{
                            dismissWaitingDialog(context);
                            if (callback != null) {
                                callback.onError("Transaction broadcast failed: " + message);
                            }
                        }
                    }
                } else {
                    // Network is not available, call onUnbroadcasted with signed transaction
                    ToastUtils.makeText(context, context.getString(R.string.toast_network_unavailable_broadcast_manual));
                    dismissWaitingDialog(context);
                    if (callback != null) {
                        callback.onUnbroadcasted(signedTxHex);
                    }
                }

            } else {
                // Show unsigned transaction as QR codes
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onUnsignedTx(rawTxInfo);
                }
            }

        } catch (Exception e) {
            dismissWaitingDialog(context);
            if (callback != null) {
                callback.onError("Transaction processing failed: " + e.getMessage());
            }
        }
    }

    /**
     * Shows an unsigned transaction as QR codes
     * 
     * @param context The context
     * @param rawTxInfo The unsigned transaction info
     */
    public void showUnsignedTxAsQR(Context context, RawTxInfo rawTxInfo) {
        if (context != null && rawTxInfo != null) {
            String json = rawTxInfo.toNiceJson();
            QRCodeGenerator.generateAndShowQRCode(context, json, "Unsigned TX");
        }
    }

    /**
     * Shows a signed transaction as QR code for offline signing
     * 
     * @param context The context
     * @param signedTxHex The signed transaction hex string
     */
    public void showSignedTxAsQR(Context context, String signedTxHex) {
        if (context != null && signedTxHex != null) {
            // Create a simple JSON structure for the signed transaction
            QRCodeGenerator.generateAndShowQRCode(context, signedTxHex, "Signed TX");
        }
    }

    /**
     * Signs and sends a transaction from RawTxInfo
     *
     * @param context     The context
     * @param rawTxInfo   The raw transaction info containing all transaction details
     * @param prikey      Private key for signing
     * @param cashManager CashManager instance
     * @param txHandler   TxHandler instance
     * @param fapiClient  FapiClient instance
     * @param callback    Callback for results
     */
    public void sendTx(
            Context context,
            RawTxInfo rawTxInfo,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback) {
        sendTx(context, rawTxInfo, prikey, cashManager, txHandler, fapiClient, callback, false);
    }

    /**
     * Signs and sends a transaction from RawTxInfo with confirmation option
     *
     * @param context     The context
     * @param rawTxInfo   The raw transaction info containing all transaction details
     * @param prikey      Private key for signing
     * @param cashManager CashManager instance
     * @param txHandler   TxHandler instance
     * @param fapiClient  FapiClient instance
     * @param callback    Callback for results
     * @param withConfirm Whether to show confirmation UI before sending
     */
    public void sendTx(
            Context context,
            RawTxInfo rawTxInfo,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback,
            boolean withConfirm) {
        sendTx(context, rawTxInfo, prikey, cashManager, txHandler, fapiClient, callback, withConfirm, false);
    }

    /**
     * Signs and sends a transaction from RawTxInfo.
     *
     * @param keepInputs  Sign only rawTxInfo's own inputs. On a spent-input error the cash DB is
     *                    still refreshed, but the send fails instead of re-selecting inputs: the
     *                    user picked these and must review any replacement.
     */
    public void sendTx(
            Context context,
            RawTxInfo rawTxInfo,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback,
            boolean withConfirm,
            boolean keepInputs) {
        showWaitingDialog(context);

        if (rawTxInfo == null) {
            dismissWaitingDialog(context);
            if (callback != null) {
                callback.onError("RawTxInfo is null");
            }
            return;
        }

        String sender = rawTxInfo.getSender();
        if (sender == null || sender.isEmpty()) {
            dismissWaitingDialog(context);
            if (callback != null) {
                callback.onError("No sender FID available");
            }
            return;
        }

        if (!sender.equals(cashManager.getLiveFid())) {
            dismissWaitingDialog(context);
            if (callback != null) {
                callback.onError("The sender does not match the cash manager.");
            }
            return;
        }

        new Thread(() -> {
            try {
                sendRawTxInternal(context, rawTxInfo, prikey, cashManager,
                    txHandler, fapiClient, callback, false, withConfirm, keepInputs);
            } catch (Exception e) {
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onError("Transaction sending failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void sendRawTxInternal(
            Context context,
            RawTxInfo rawTxInfo,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            FapiClient fapiClient,
            TxCallback callback,
            boolean isRetry,
            boolean withConfirm,
            boolean keepInputs) {
        
        try {
            String sender = rawTxInfo.getSender();

            // Show confirmation UI if requested
            if (withConfirm) {
                // Store the callback for later use by the activity
                pendingCallback = callback;
                currentInstance = this;

                Intent intent;
                if (rawTxInfo.getSenderMultisig() != null) {
                    // Use SignMultisigTxActivity for multisig transactions
                    intent = new Intent(context, SignMultisigTxActivity.class);
                } else {
                    // Use SendTxActivity for regular transactions
                    intent = new Intent(context, SendTxActivity.class);
                }
                intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, rawTxInfo.toNiceJson());
                dismissWaitingDialog(context);
                context.startActivity(intent);
                return;
            }

            // Create transaction
            Transaction tx = txHandler.createTx(rawTxInfo, MainNetParams.get());
            if (tx == null) {
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onError("Failed to create transaction");
                }
                return;
            }

            // Check if we have private key to sign
            if (prikey != null && !BytesUtils.isFilledKey(prikey)) {
                // Sign the transaction with P2SH/CLTV support
                String signedTxHex = txHandler.signTx(prikey, tx, rawTxInfo.getInputs());
                if (signedTxHex == null || signedTxHex.isEmpty()) {
                    dismissWaitingDialog(context);
                    if (callback != null) {
                        callback.onError("Failed to sign transaction");
                    }
                    return;
                }

                // Check if transaction has lockTime in the future
                if (rawTxInfo.getLockTime() != null && rawTxInfo.getLockTime() > 0) {
                    Long bestHeight = fapiClient.getBestHeight();
                    if (bestHeight != null && rawTxInfo.getLockTime() > bestHeight) {
                        long blocksToWait = rawTxInfo.getLockTime() - bestHeight;
                        ToastUtils.makeText(context,
                            context.getString(R.string.tx_is_time_locked_until_block_d_current_d_d_blocks_to_wait_transaction_signed_but_not_broadcast,rawTxInfo.getLockTime() ,bestHeight,blocksToWait));
                        dismissWaitingDialog(context);
                        if (callback != null) {
                            callback.onUnbroadcasted(signedTxHex);
                        }
                        return;
                    }
                }

                // Check network connectivity before broadcasting
                if (NetworkUtils.isNetworkAvailable(context)) {
                    // Network is available, proceed with broadcasting
                    String result = fapiClient.broadcastTx(signedTxHex);
                    FapiResponse response = fapiClient.getLastResponse();
                    String message = response.getMessage();

                    if (Hex.isHex32(result)) {
                        // Transaction broadcast successful
                        if (!updateCashOfTx(signedTxHex, sender, context, cashManager)) {
                            ToastUtils.makeText(context,
                                    R.string.failed_to_update_cash_db
                            );
                        }
                        ToastUtils.makeText(context, R.string.tx_sent);
                        dismissWaitingDialog(context);
                        if (callback != null) {
                            callback.onSuccess(result);
                        }
                    } else {
                        // Handle specific error cases
                        if (message != null && (message.contains("-25") || message.contains("-26"))) {
                            // -25: Missing inputs (spent or never existed)
                            // -26: Mempool conflict (inputs already used in another mempool tx)
                            // Refresh cash DB and retry
                            if (!isRetry) {
                                String errorType = message.contains("-25") ? "missing inputs" : "mempool conflict";
                                TimberLogger.w("TxSender", "Transaction failed due to " + errorType + ", reconciling cash DB and retrying");

                                // See sendTxInternal: overlay first (RPC mempool, no ES lag);
                                // full refresh only for -25 or if the overlay call failed.
                                // (Already on background thread.)
                                int changed = cashManager.applyMempoolOverlay(context);
                                if (message.contains("-25") || changed < 0) {
                                    cashManager.freshValidCashDB(context);
                                }

                                ToastUtils.makeText(context, context.getString(R.string.toast_cash_db_refreshed));
                                if (keepInputs) {
                                    // The user picked these inputs; swapping in others here would
                                    // sign a TX they never reviewed. The cash DB is fresh now, so
                                    // they can reselect.
                                    dismissWaitingDialog(context);
                                    if (callback != null) {
                                        callback.onError(context.getString(R.string.selected_inputs_spent_reselect));
                                    }
                                    return;
                                }
                                // The original rawTxInfo has the conflicting inputs baked in; re-selecting
                                // them from the just-refreshed (mempool-aware) DB is required, otherwise the
                                // retry rebroadcasts the identical TX and hits the same -26. Mirror the input
                                // selection done in sendTxInternal.
                                List<Cash> retryOutputs = rawTxInfo.getOutputs();
                                double retryPayValue = 0.0;
                                if (retryOutputs != null) {
                                    for (Cash out : retryOutputs) retryPayValue += out.getAmount();
                                }
                                int retryOutputSize = retryOutputs != null ? retryOutputs.size() : 0;
                                String retryOpReturn = rawTxInfo.getOpReturn();
                                int retryMsgSize = retryOpReturn != null ? retryOpReturn.getBytes().length : 0;
                                Multisig retryMultisig = rawTxInfo.getSenderMultisig();
                                double retryFeeRate = rawTxInfo.getFeeRate() != null
                                        ? rawTxInfo.getFeeRate() : TxHandler.DEFAULT_FEE_RATE;
                                List<Cash> retryInputs = cashManager.getValidCashes(retryPayValue,
                                        rawTxInfo.getCd(), retryOutputSize, retryMsgSize, retryFeeRate,
                                        retryMultisig, context);
                                if (retryInputs == null || retryInputs.isEmpty()) {
                                    dismissWaitingDialog(context);
                                    if (callback != null) {
                                        callback.onError("Transaction broadcast failed after retry: " + message);
                                    }
                                    return;
                                }
                                RawTxInfo retryTxInfo = new RawTxInfo(rawTxInfo.getSender(), retryInputs,
                                        retryOutputs, retryOpReturn, rawTxInfo.getCd(), rawTxInfo.getFeeRate(),
                                        retryMultisig, rawTxInfo.getVer());
                                retryTxInfo.setLockTime(rawTxInfo.getLockTime());
                                sendRawTxInternal(context, retryTxInfo, prikey, cashManager,
                                    txHandler, fapiClient, callback, true, false, false);
                                return;
                            } else {
                                // Already retried once, don't retry again
                                dismissWaitingDialog(context);
                                if (callback != null) {
                                    callback.onError("Transaction broadcast failed after retry: " + message);
                                }
                            }
                        } else if (message != null && message.contains("-27")) {
                            // Already confirmed on chain
                            if (!updateCashOfTx(signedTxHex, sender, context, cashManager)) {
                                ToastUtils.makeText(context,
                                        R.string.failed_to_update_cash_db
                                );
                            }
                            ToastUtils.makeText(context, R.string.tx_sent);
                            dismissWaitingDialog(context);
                            if (callback != null) {
                                callback.onSuccess(tx.getTxId().toString());
                            }
                        } else {
                            dismissWaitingDialog(context);
                            if (callback != null) {
                                callback.onError("Transaction broadcast failed: " + message);
                            }
                        }
                    }
                } else {
                    // Network is not available, call onUnbroadcasted with signed transaction
                    ToastUtils.makeText(context, context.getString(R.string.toast_network_unavailable_broadcast_qr));
                    dismissWaitingDialog(context);
                    if (callback != null) {
                        callback.onUnbroadcasted(signedTxHex);
                    }
                }

            } else {
                // No private key available, return unsigned transaction
                dismissWaitingDialog(context);
                if (callback != null) {
                    callback.onUnsignedTx(rawTxInfo);
                }
            }

        } catch (Exception e) {
            dismissWaitingDialog(context);
            if (callback != null) {
                callback.onError("Transaction processing failed: " + e.getMessage());
            }
        }
    }

    /**
     * Parse P2SH map from OP_RETURN output
     * @param transaction The transaction to parse
     * @return Map of hash160Hex to redeemScriptHex if found, null otherwise
     */
    private java.util.Map<String, String> parseP2shMapFromOpReturn(Transaction transaction) {
        return P2SH.parseP2SHMapFromOpReturn(transaction);
    }

    /**
     * Updates cash database from a signed transaction.
     * Removes consumed inputs and adds new outputs for the sender.
     *
     * @param signedTx    The signed transaction hex
     * @param sender      The sender FID
     * @param context     The context
     * @param cashManager The cash manager instance
     * @return true if successful, false otherwise
     */
    public boolean updateCashOfTx(String signedTx, String sender, Context context, CashManager cashManager) {
        if (signedTx == null || sender == null || cashManager == null) {
            return false;
        }
        
        FidManager fidManager = FidManager.getInstance();
        KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();

        List<Cash> newCashList = new ArrayList<>();

        try {
            Transaction transaction = new Transaction(com.fc.fc_ajdk.core.fch.FchMainNetwork.MAINNETWORK, Hex.fromHex(signedTx));
            String txId = transaction.getTxId().toString();
            
            // Remove consumed inputs from valid cash database
            List<Cash> consumedCashList = new ArrayList<>();
            long totalValue = 0;
            for (int i = 0; i < transaction.getInputs().size(); i++) {
                String inputTxId = transaction.getInputs().get(i).getOutpoint().getHash().toString();
                int inputIndex = (int) transaction.getInputs().get(i).getOutpoint().getIndex();
                
                // Calculate cash ID using the Cash class method
                String consumedCashId = Cash.makeCashId(inputTxId, inputIndex);
                
                // Get consumed cash from database and add to removal list
                if (consumedCashId != null) {
                    Cash consumedCash = cashManager.getCashById(consumedCashId);
                    if (consumedCash != null) {
                        consumedCashList.add(consumedCash);
                        totalValue += consumedCash.getValue();
                    }
                }
            }
            
            // Remove all consumed cash in batch
            if (!consumedCashList.isEmpty()) {
                cashManager.removeCashList(consumedCashList);

                cashManager.setTotalCashOnChain(cashManager.getTotalCashOnChain()-consumedCashList.size());

                Long balance = liveKeyInfo.getBalance();
                balance -= totalValue;
                liveKeyInfo.setBalance(balance);
                liveKeyInfo.setCash(liveKeyInfo.getCash() - consumedCashList.size());
                TimberLogger.d("TxSender", "Removed %d consumed cash items", consumedCashList.size());
            }

            totalValue = 0;
            // Add new outputs for the sender

            // Parse P2SH map from OP_RETURN (hash160Hex -> redeemScriptHex)
            java.util.Map<String, String> p2shMap = parseP2shMapFromOpReturn(transaction);

            List<TransactionOutput> outputs = transaction.getOutputs();
            for (int i = 0; i < outputs.size(); i++) {
                TransactionOutput output = outputs.get(i);
                org.bitcoinj.script.Script script = output.getScriptPubKey();

                try {
                    Address address = script.getToAddress(com.fc.fc_ajdk.core.fch.FchMainNetwork.MAINNETWORK);
                    String addressStr = address.toString();

                    // Check if output belongs to sender (standard P2PKH)
                    if (sender.equals(addressStr)) {
                        Cash cash = new Cash();
                        cash.setBirthTxId(txId);
                        cash.setBirthIndex(i);
                        cash.setOwner(sender);
                        cash.setValue(output.getValue().getValue());
                        cash.setValid(true);
                        cash.makeId();
                        cash.setNew(Boolean.TRUE);

                        newCashList.add(cash);
                        totalValue += cash.getValue();
                        TimberLogger.d("TxSender", "Added standard output " + i + " for sender");

                    } else if (script.getScriptType() == Script.ScriptType.P2SH && p2shMap != null && !p2shMap.isEmpty()) {
                        // Check for P2SH outputs (CLTV and MULTISIG_CLTV)
                        try {
                            // Calculate hash160 of this P2SH output's redeemScript
                            byte[] p2shScriptHash = P2SH.extractP2SHScriptHash(output.getScriptBytes());
                            if (p2shScriptHash != null) {
                                String hash160Hex = Hex.toHex(p2shScriptHash);

                                // Look up redeemScript in the map using hash160
                                String redeemScriptHex = p2shMap.get(hash160Hex);
                                if (redeemScriptHex != null && !redeemScriptHex.isEmpty()) {
                                    // Parse redeemScript to extract owner and type
                                    Cash cash = new Cash();
                                    boolean valid = P2SH.addCLTVInfoToCashWithValidation(cash, redeemScriptHex);

                                    if(!valid)
                                        continue;

                                    String cashType = cash.getType();

                                    // Check if this P2SH belongs to sender (CLTV or MULTISIG_CLTV types)
                                    if (sender.equals(cash.getOwner()) &&
                                            (Cash.CashType.P2SH_CLTV.getValue().equals(cashType) ||
                                                    Cash.CashType.P2SH_MULTISIG_CLTV.getValue().equals(cashType))) {

                                        // This is a P2SH CLTV or MULTISIG_CLTV output for the sender
                                        cash.setBirthTxId(txId);
                                        cash.setBirthIndex(i);
                                        cash.setValue(output.getValue().getValue());
                                        cash.setValid(true);
                                        cash.setRedeemScript(redeemScriptHex);
                                        // Extract lockTime from redeemScript
                                        Long lockTime = P2SH.extractLockTimeFromRedeemScript(redeemScriptHex);
                                        cash.setLockTime(lockTime);
                                        cash.makeId();
                                        cash.setNew(Boolean.TRUE);

                                        newCashList.add(cash);
                                        totalValue += cash.getValue();

                                        TimberLogger.d("TxSender", "Added " + cashType + " output " + i +
                                                " with lockTime " + cash.getLockTime());
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            TimberLogger.d("TxSender", "Error processing P2SH output " + i + ": " + ex.getMessage());
                        }
                    }

                } catch (Exception e) {
                    // Skip outputs that can't be converted to addresses (e.g., OP_RETURN)
                    TimberLogger.d("TxSender", "Skipping output " + i + " - cannot convert to address: " + e.getMessage());
                }
            }
            
            if (!newCashList.isEmpty()) {
                liveKeyInfo.setBalance(totalValue);
                liveKeyInfo.setCash(liveKeyInfo.getCash() + newCashList.size());
            }
            
            // Add new cash to database and commit changes
            int added = cashManager.updateValidToDB(newCashList, true);
            if(added>0)
                cashManager.setTotalCashOnChain( cashManager.getTotalCashOnChain()+added);
            cashManager.commit();
            TimberLogger.i("TxSender", "Updated cash from transaction: removed %d inputs, added %d outputs",
                consumedCashList.size(), added);
            fidManager.updateKeyInfo(context, liveKeyInfo.getId(), liveKeyInfo);
            return true;

        } catch (Exception e) {
            TimberLogger.e("TxSender", "Failed to parse signed transaction: " + e.getMessage());
            return false;
        }
    }
}