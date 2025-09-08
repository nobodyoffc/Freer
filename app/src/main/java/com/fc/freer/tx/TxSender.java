package com.fc.freer.tx;

import android.content.Context;
import android.widget.Toast;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisign;
import com.fc.fc_ajdk.data.fchData.SendTo;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.fc_ajdk.utils.http.AuthType;
import com.fc.fc_ajdk.utils.http.RequestMethod;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.FcReplyBody;
import com.fc.freer.network.ApipClient;
import com.fc.freer.utils.QRCodeGenerator;
import com.fc.freer.network.NetworkUtils;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.params.MainNetParams;

import java.util.ArrayList;
import java.util.List;

/**
 * General utility class for creating, signing, and broadcasting transactions
 */
public class TxSender {
    
    public interface TxCallback {
        void onSuccess(String txId);
        void onError(String errorMessage);
        void onUnsignedTx(RawTxInfo rawTxInfo);
    }

    public static void carveFeipWithRecipient(
            Context context,
            String sender,
            String recipient,
            Double amount,
            String feipJson,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            ApipClient apipClient,
            TxCallback callback){
        List<SendTo> sendToList = new ArrayList<>();
        if(amount==null)amount=SendTo.MIN_AMOUNT;
        SendTo sendTo = new SendTo(recipient,amount);
        sendToList.add(sendTo);
        sendTx(context, sender, sendToList, feipJson, prikey, Feip.REQUIRED_CD, TxHandler.DEFAULT_FEE_RATE, null,ApipClient.ApipApiNames.VERSION_2, cashManager, txHandler, apipClient,callback);
    }

    public static void carveSimpleFeip(
                Context context,
                String sender,
                String feipJson,
                byte[] prikey,
                CashManager cashManager,
                TxHandler txHandler,
                ApipClient apipClient,
                TxCallback callback){
        sendTx(context, sender, null, feipJson, prikey, Feip.REQUIRED_CD, TxHandler.DEFAULT_FEE_RATE, null,ApipClient.ApipApiNames.VERSION_2, cashManager, txHandler, apipClient,callback);
    }
    
    /**
     * Creates, signs (if possible), and broadcasts a transaction
     *
     * @param context     The context
     * @param sender      The sender FID
     * @param outputs     List of SendTo objects
     * @param opReturn    OP_RETURN message
     * @param prikey      Private key cipher (null for unsigned tx)
     * @param cd          CD value
     * @param feeRate     Fee rate
     * @param multisign   Multisign object (can be null)
     * @param ver         Transaction version
     * @param cashManager CashManager instance
     * @param txHandler   TxHandler instance
     * @param apipClient  ApipClient instance
     * @param callback    Callback for results
     */
    public static void sendTx(
            Context context,
            String sender,
            List<SendTo> outputs,
            String opReturn,
            byte[] prikey, Long cd,
            Double feeRate,
            Multisign multisign,
            String ver,
            CashManager cashManager,
            TxHandler txHandler,
            ApipClient apipClient,
            TxCallback callback) {
        try{
            if (sender == null || sender.isEmpty()) {
                if (callback != null) {
                    callback.onError("No sender FID available");
                }
                return;
            }

            if(!sender.equals(cashManager.getMainFid())){
                if (callback != null) {
                    callback.onError("The sender do not match the cash manager.");
                }
                return;
            }

            new Thread(() -> {
                try {
                    sendTxInternal(context, sender, outputs, opReturn, cd, feeRate,
                        multisign, ver, prikey, cashManager, txHandler, apipClient, callback, false);
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onError("Transaction creation failed: " + e.getMessage());
                    }
                }
            }).start();
        } catch (Exception e) {
            if (callback != null) {
                callback.onError("Failed to create FEIP transaction: " + e.getMessage());
            }
        }
    }
    
    private static void sendTxInternal(
            Context context,
            String sender,
            List<SendTo> outputs,
            String opReturn,
            Long cd,
            Double feeRate,
            Multisign multisign,
            String ver,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            ApipClient apipClient,
            TxCallback callback,
            boolean isRetry) {
        
        try {
            // Step 1: Load valid cash list
            double payValue = 0.0;
            if (outputs != null) {
                for (SendTo sendTo : outputs) {
                    payValue += sendTo.getAmount();
                }
            }
            
            int outputSize = (outputs != null ? outputs.size() : 0);
            int msgSize = (opReturn != null ? opReturn.getBytes().length : 0);
            
            List<Cash> validCashList = cashManager.getValidCashes(payValue, cd, outputSize, msgSize, 
                feeRate != null ? feeRate :TxHandler.DEFAULT_FEE_RATE, multisign);
            
            if (validCashList == null || validCashList.isEmpty()) {
                if (callback != null) {
                    callback.onError("No valid cash available for transaction");
                }
                return;
            }
            
            // Step 2: Create RawTxInfo
            RawTxInfo rawTxInfo = new RawTxInfo(sender, validCashList, outputs, opReturn, cd, 
                feeRate, multisign, ver);
            
            // Step 3: Create transaction
            Transaction tx = txHandler.createTx(rawTxInfo, MainNetParams.get());
            if (tx == null) {
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
                    if (callback != null) {
                        callback.onError("Failed to sign transaction");
                    }
                    return;
                }
                
                // Check network connectivity before broadcasting
                if (NetworkUtils.isNetworkAvailable(context)) {
                    // Network is available, proceed with broadcasting
                    String result = apipClient.broadcastTx(signedTxHex, RequestMethod.POST, AuthType.FC_SIGN_BODY, context);
                    FcReplyBody response = apipClient.getApiEvent().getResponseBody();
                    String message = response.getMessage();
                    if (Hex.isHex32(result)) {
                        if(!updateFromTx(signedTxHex, sender, context, cashManager))
                            Toast.makeText(context,
                                    R.string.failed_to_update_cash_db,
                                    Toast.LENGTH_LONG).show();
                        if (callback != null) {
                            callback.onSuccess(tx.getTxId().toString());
                        }
                    } else {
                        // Handle specific error cases
                        if (message != null && message.contains("-25")) {
                            // Refresh cash DB and retry
                            if (!isRetry) {
                                int count = cashManager.freshValidCashDB(context,AuthType.FC_SIGN_BODY,RequestMethod.POST);
                                if(count>0)
                                    Toast.makeText(context, context.getString(R.string.cash_refreshed_and_saved, count), Toast.LENGTH_SHORT).show();
                                sendTxInternal(context, sender, outputs, opReturn, cd,
                                        feeRate, multisign, ver, prikey, cashManager, txHandler,
                                        apipClient, callback, true);
                            }
                        } else if (message != null && message.contains("-27")) {
                            // Already confirmed on chain
                            if(!updateFromTx(signedTxHex, sender, context, cashManager))
                                Toast.makeText(context,
                                        R.string.failed_to_update_cash_db,
                                        Toast.LENGTH_LONG).show();
                            if (callback != null) {
                                callback.onSuccess(tx.getTxId().toString());
                            }
                        }else{
                            if (callback != null) {
                                callback.onError("Transaction broadcast failed: " + message);
                            }
                        }
                    }
                } else {
                    // Network is not available, show signed transaction as QR code
                    Toast.makeText(context, "Network is not available. Please broadcast manually using the QR code.", Toast.LENGTH_LONG).show();
                    if (callback != null) {
                        callback.onError("Network is not available. Showing signed transaction as QR code.");
                    }
                    // Show the signed transaction as QR code
                    showSignedTxAsQR(context, signedTxHex);
                }
                
            } else {
                // Show unsigned transaction as QR codes
                if (callback != null) {
                    callback.onUnsignedTx(rawTxInfo);
                }
            }
            
        } catch (Exception e) {
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
    public static void showUnsignedTxAsQR(Context context, RawTxInfo rawTxInfo) {
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
    public static void showSignedTxAsQR(Context context, String signedTxHex) {
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
     * @param apipClient  ApipClient instance
     * @param callback    Callback for results
     */
    public static void sendTx(
            Context context,
            RawTxInfo rawTxInfo,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            ApipClient apipClient,
            TxCallback callback) {
        
        if (rawTxInfo == null) {
            if (callback != null) {
                callback.onError("RawTxInfo is null");
            }
            return;
        }

        String sender = rawTxInfo.getSender();
        if (sender == null || sender.isEmpty()) {
            if (callback != null) {
                callback.onError("No sender FID available");
            }
            return;
        }

        if (!sender.equals(cashManager.getMainFid())) {
            if (callback != null) {
                callback.onError("The sender does not match the cash manager.");
            }
            return;
        }

        new Thread(() -> {
            try {
                sendRawTxInternal(context, rawTxInfo, prikey, cashManager, 
                    txHandler, apipClient, callback, false);
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError("Transaction sending failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private static void sendRawTxInternal(
            Context context,
            RawTxInfo rawTxInfo,
            byte[] prikey,
            CashManager cashManager,
            TxHandler txHandler,
            ApipClient apipClient,
            TxCallback callback,
            boolean isRetry) {
        
        try {
            String sender = rawTxInfo.getSender();
            
            // Create transaction
            Transaction tx = txHandler.createTx(rawTxInfo, MainNetParams.get());
            if (tx == null) {
                if (callback != null) {
                    callback.onError("Failed to create transaction");
                }
                return;
            }
            
            // Check if we have private key to sign
            if (prikey != null && !BytesUtils.isFilledKey(prikey)) {
                // Sign the transaction
                String signedTxHex = txHandler.signTx(rawTxInfo, prikey);
                if (signedTxHex == null || signedTxHex.isEmpty()) {
                    if (callback != null) {
                        callback.onError("Failed to sign transaction");
                    }
                    return;
                }
                
                // Check network connectivity before broadcasting
                if (NetworkUtils.isNetworkAvailable(context)) {
                    // Network is available, proceed with broadcasting
                    String result = apipClient.broadcastTx(signedTxHex, RequestMethod.POST, AuthType.FC_SIGN_BODY, context);
                    FcReplyBody response = apipClient.getApiEvent().getResponseBody();
                    String message = response.getMessage();
                    
                    if (Hex.isHex32(result)) {
                        // Transaction broadcast successful
                        if (!updateFromTx(signedTxHex, sender, context, cashManager)) {
                            Toast.makeText(context,
                                    R.string.failed_to_update_cash_db,
                                    Toast.LENGTH_LONG).show();
                        }
                        if (callback != null) {
                            callback.onSuccess(result);
                        }
                    } else {
                        // Handle specific error cases
                        if (message != null && message.contains("-25")) {
                            // Refresh cash DB and retry
                            if (!isRetry) {
                                int count = cashManager.freshValidCashDB(context, AuthType.FC_SIGN_BODY, RequestMethod.POST);
                                if (count > 0) {
                                    Toast.makeText(context, context.getString(R.string.cash_refreshed_and_saved, count), Toast.LENGTH_SHORT).show();
                                }
                                sendRawTxInternal(context, rawTxInfo, prikey, cashManager, 
                                    txHandler, apipClient, callback, true);
                                return;
                            }
                        } else if (message != null && message.contains("-27")) {
                            // Already confirmed on chain
                            if (!updateFromTx(signedTxHex, sender, context, cashManager)) {
                                Toast.makeText(context,
                                        R.string.failed_to_update_cash_db,
                                        Toast.LENGTH_LONG).show();
                            }
                            if (callback != null) {
                                callback.onSuccess(tx.getTxId().toString());
                            }
                        } else {
                            if (callback != null) {
                                callback.onError("Transaction broadcast failed: " + message);
                            }
                        }
                    }
                } else {
                    // Network is not available, show signed transaction as QR code
                    Toast.makeText(context, "Network is not available. Please broadcast manually using the QR code.", Toast.LENGTH_LONG).show();
                    if (callback != null) {
                        callback.onError("Network is not available. Showing signed transaction as QR code.");
                    }
                    // Show the signed transaction as QR code
                    showSignedTxAsQR(context, signedTxHex);
                }
                
            } else {
                // No private key available, return unsigned transaction
                if (callback != null) {
                    callback.onUnsignedTx(rawTxInfo);
                }
            }
            
        } catch (Exception e) {
            if (callback != null) {
                callback.onError("Transaction processing failed: " + e.getMessage());
            }
        }
    }

    /**
     * Updates cash database from a signed transaction.
     * Removes consumed inputs and adds new outputs for the sender.
     * 
     * @param signedTx The signed transaction hex
     * @param sender The sender FID
     * @param context The context
     * @param cashManager The cash manager instance
     * @return true if successful, false otherwise
     */
    public static boolean updateFromTx(String signedTx, String sender, Context context, CashManager cashManager) {
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
                Long balance = liveKeyInfo.getBalance();
                balance -= totalValue;
                liveKeyInfo.setBalance(balance);
                liveKeyInfo.setCash(liveKeyInfo.getCash() - consumedCashList.size());
                TimberLogger.d("TxSender", "Removed %d consumed cash items", consumedCashList.size());
            }

            totalValue = 0;
            // Add new outputs for the sender
            List<TransactionOutput> outputs = transaction.getOutputs();
            for (int i = 0; i < outputs.size(); i++) {
                TransactionOutput output = outputs.get(i);

                try {
                    Address address = output.getScriptPubKey().getToAddress(com.fc.fc_ajdk.core.fch.FchMainNetwork.MAINNETWORK);

                    if (sender.equals(address.toString())) {
                        Cash cash = new Cash();
                        cash.setBirthTxId(txId);
                        cash.setBirthIndex(i);
                        cash.setOwner(sender);
                        cash.setValue(output.getValue().getValue());
                        cash.setValid(true);
                        cash.makeId();
                        newCashList.add(cash);
                        totalValue += cash.getValue();
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
            int added = cashManager.updateValidCashList(newCashList, true);
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