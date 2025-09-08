package com.fc.freer.tx;

import static com.fc.fc_ajdk.constants.Constants.COIN_TO_SATOSHI;
import static com.fc.fc_ajdk.core.crypto.KeyTools.prikeyToFid;

import androidx.annotation.Nullable;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.core.fch.TxCreator;
import com.fc.fc_ajdk.data.fcData.ReplyBody;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.fchData.Multisign;
import com.fc.fc_ajdk.data.fchData.SendTo;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.manager.CashManager;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VarInt;
import org.bitcoinj.crypto.SchnorrSignature;
import org.bitcoinj.fch.FchMainNetwork;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;

/**
 * Android transaction handler for creating and broadcasting general transactions
 * Uses CashManager for cash management
 */
public class TxHandler {
    private static final String TAG = "TxHandler";

    public static final double DEFAULT_FEE_RATE = 0.00001;
    private final MainNetParams mainNetwork;
    private CashManager cashManager;

    static {
        fixKeyLength();
        Security.addProvider(new BouncyCastleProvider());
    }

    public static void fixKeyLength() {
        String errorString = "Failed manually overriding key-length permissions.";
        int newMaxKeyLength;
        try {
            if ((newMaxKeyLength = Cipher.getMaxAllowedKeyLength("AES")) < 256) {
                Class<?> c = Class.forName("javax.crypto.CryptoAllPermissionCollection");
                Constructor<?> con = c.getDeclaredConstructor();
                con.setAccessible(true);
                Object allPermissionCollection = con.newInstance();
                Field f = c.getDeclaredField("all_allowed");
                f.setAccessible(true);
                f.setBoolean(allPermissionCollection, true);

                c = Class.forName("javax.crypto.CryptoPermissions");
                con = c.getDeclaredConstructor();
                con.setAccessible(true);
                Object allPermissions = con.newInstance();
                f = c.getDeclaredField("perms");
                f.setAccessible(true);
                ((Map) f.get(allPermissions)).put("*", allPermissionCollection);

                c = Class.forName("javax.crypto.JceSecurityManager");
                f = c.getDeclaredField("defaultPolicy");
                f.setAccessible(true);
                Field mf = Field.class.getDeclaredField("modifiers");
                mf.setAccessible(true);
                mf.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                f.set(null, allPermissions);

                newMaxKeyLength = Cipher.getMaxAllowedKeyLength("AES");
            }
        } catch (Exception e) {
            throw new RuntimeException(errorString, e);
        }
        if (newMaxKeyLength < 256)
            throw new RuntimeException(errorString); // hack failed
    }
    

    public TxHandler() {
        mainNetwork = com.fc.fc_ajdk.core.fch.FchMainNetwork.MAINNETWORK;
    }

    public TxHandler(MainNetParams mainNetwork) {
        this.mainNetwork = mainNetwork;
    }

//Create TX

    public String createTxHex(RawTxInfo rawTxInfo) {
        Transaction tx = createTx(rawTxInfo, this.mainNetwork);
        if(tx==null)return null;
        byte[] txBytes = tx.bitcoinSerialize();
        return Hex.toHex(txBytes);
    }
    public Transaction createTx(RawTxInfo rawTxInfo, MainNetParams mainNetwork) {
        if (rawTxInfo.getInputs() == null || rawTxInfo.getInputs().isEmpty()) {
            TimberLogger.e("The sender is absent.");
            return null;
        }
        byte[] opReturnBytes;
        if(rawTxInfo.getOpReturn()!=null) {
            opReturnBytes = rawTxInfo.getOpReturn().getBytes();
        }else opReturnBytes= new byte[0];
        int opReturnBytesLen = opReturnBytes.length;

        if(rawTxInfo.getChangeTo()==null) {
            if(rawTxInfo.getSender()!=null)rawTxInfo.setChangeTo(rawTxInfo.getSender());
            else rawTxInfo.setChangeTo(rawTxInfo.getInputs().get(0).getOwner());
            if(rawTxInfo.getChangeTo()==null) return null;
        }
        boolean isMultiSign = false;
        if(rawTxInfo.getMultisign()!=null){
            isMultiSign=true;
            String multisignFid = rawTxInfo.getMultisign().getId();
            if(rawTxInfo.getSender()==null)rawTxInfo.setSender(multisignFid);
            else if(!rawTxInfo.getSender().equals(multisignFid))return null;
        }

        if(rawTxInfo.getFeeRate()==null || rawTxInfo.getFeeRate()==0)
            rawTxInfo.setFeeRate(DEFAULT_FEE_RATE);
        long fee;

        int inputSize = rawTxInfo.getInputs().size();
        int outputSize = rawTxInfo.getOutputs() ==null ? 0 : rawTxInfo.getOutputs().size();

        fee = calcFee(inputSize, outputSize, opReturnBytesLen, rawTxInfo.getFeeRate(), isMultiSign, rawTxInfo.getMultisign());

        Transaction transaction = new Transaction(mainNetwork);

        long totalOutput = 0;

        long totalMoney = addInputToTx(rawTxInfo.getInputs(), transaction);

        if(rawTxInfo.getOutputs() !=null && !rawTxInfo.getOutputs().isEmpty()){
            for (SendTo output : rawTxInfo.getOutputs()) {
                long value = FchUtils.coinToSatoshi(output.getAmount());
                totalOutput += value;
                transaction.addOutput(Coin.valueOf(value), Address.fromBase58(mainNetwork, output.getFid()));
            }
        }
        long changeOutputFee = 34L * FchUtils.coinToSatoshi(rawTxInfo.getFeeRate()/ 1000);

        if(!(totalOutput + fee - changeOutputFee == totalMoney)){

            if ((totalOutput + fee ) > totalMoney) {
                TimberLogger.i("Input is not enough");
                return null;
            }

            long change = totalMoney - totalOutput - fee;
            if (change > Constants.DustInSatoshi) {
                transaction.addOutput(Coin.valueOf(change), Address.fromBase58(mainNetwork, rawTxInfo.getChangeTo()));
            }
        }

        if (rawTxInfo.getOpReturn() != null && opReturnBytesLen >0) {
            try {
                Script opreturnScript = ScriptBuilder.createOpReturnScript(opReturnBytes);
                transaction.addOutput(Coin.ZERO, opreturnScript);
            } catch (Exception e) {
                TimberLogger.e("Failed to create opreturn script: "+e.getMessage());
                return null;
            }
        }

        return transaction;
    }

    private long addInputToTx(List<Cash> valueTxIdIndexCashList, Transaction transaction) {
        long totalMoney=0;

        for (Cash input : valueTxIdIndexCashList) {
            totalMoney += input.getValue();
            TransactionOutPoint outPoint = new TransactionOutPoint(this.mainNetwork, input.getBirthIndex(), Sha256Hash.wrap(input.getBirthTxId()));
            TransactionInput unsignedInput = new TransactionInput(this.mainNetwork, null, new byte[0], outPoint, Coin.valueOf(input.getValue()));
            transaction.addInput(unsignedInput);
        }

        return totalMoney;
    }

//Parse TX
    public RawTxInfo parseTx(Transaction tx){
        return RawTxInfo.fromTransaction(tx);
    }

    public RawTxInfo parseTx(String rawTx){
        Transaction tx = new Transaction(this.mainNetwork,Hex.fromHex(rawTx));
        return RawTxInfo.fromTransaction(tx);
    }

//Calculate Fee

    public long calcFee(int inputSize, int outputSize, int opReturnBytesLen, double feeRate, boolean isMultiSign, Multisign multisignForMultiSign) {
        long fee;
        if(isMultiSign) {
            long feeRateLong = (long) (feeRate / 1000 * COIN_TO_SATOSHI);
            fee = feeRateLong * TxCreator.calcSizeMultiSign(inputSize, outputSize, opReturnBytesLen, multisignForMultiSign.getM(), multisignForMultiSign.getN());
        }else {
            long txSize = calcTxSize(inputSize, outputSize, opReturnBytesLen);
            fee =calcFee(txSize, feeRate);
        }
        return fee;
    }

    public long calcSizeMultiSign(int inputNum, int outputNum, int opReturnBytesLen, int m, int n) {

        /*多签单个Input长度：
            基础字节40（preTxId 32，preIndex 4，sequence 4），
            可变脚本长度：？
            脚本：
                op_0    1
                签名：m * (1+64+1)     // length + pubkeyLength + sigHash ALL
                可变redeemScript 长度：？
                redeem script：
                    op_m    1
                    pubkeys    n * 33
                    op_n    1
                    OP_CHECKMULTISIG    1
         */

        long op_mLen =1;
        long op_nLen =1;
        long pubkeyLen = 33;
        long pubkeyLenLen = 1;
        long op_checkmultisigLen = 1;

        long redeemScriptLength = op_mLen + (n * (pubkeyLenLen + pubkeyLen)) + op_nLen + op_checkmultisigLen; //105 n=3
        long redeemScriptVarInt = VarInt.sizeOf(redeemScriptLength);//1 n=3

        long op_pushDataLen = 1;
        long sigHashLen = 1;
        long signLen=64;
        long signLenLen = 1;
        long zeroByteLen = 1;

        long mSignLen = m * (signLenLen + signLen + sigHashLen); //132 m=2

        long scriptLength = zeroByteLen + mSignLen + op_pushDataLen + redeemScriptVarInt + redeemScriptLength;//236 m=2
        long scriptVarInt = VarInt.sizeOf(scriptLength);

        long preTxIdLen = 32;
        long preIndexLen = 4;
        long sequenceLen = 4;

        long inputLength = preTxIdLen + preIndexLen + sequenceLen + scriptVarInt + scriptLength;//240 n=3,m=2


        long opReturnLen = 0;
        if (opReturnBytesLen != 0)
            opReturnLen = calcOpReturnLen(opReturnBytesLen);

        long outputValueLen=8;
        long unlockScriptLen = 25; //If sending to multiSignAddr, it will be 23.
        long unlockScriptLenLen =1;
        long outPutLen = outputValueLen + unlockScriptLenLen + unlockScriptLen;

        long inputCountLen=1;
        long outputCountLen=1;
        long txVerLen = 4;
        long nLockTimeLen = 4;
        long txFixedLen = inputCountLen + outputCountLen + txVerLen + nLockTimeLen;

        long length;
        length = txFixedLen + inputLength * inputNum + outPutLen * (outputNum + 1) + opReturnLen;

        return length;
    }
    public long calcFee(long txSize, double feeRate) {
        long feeRateLong;
        if (feeRate != 0) {
            feeRateLong = (long) (feeRate / 1000 * COIN_TO_SATOSHI);
        } else feeRateLong = (long) (DEFAULT_FEE_RATE / 1000 * COIN_TO_SATOSHI);
        return feeRateLong * txSize;
    }

    public long calcTxSize(int inputNum, int outputNum, int opReturnBytesLen) {

        long baseLength = 10;
        long inputLength = 141 * (long) inputNum;
        long outputLength = 34 * (long) (outputNum + 1); // Include change output

        int opReturnLen = 0;
        if (opReturnBytesLen != 0)
            opReturnLen = calcOpReturnLen(opReturnBytesLen);

        return baseLength + inputLength + outputLength + opReturnLen;
    }

    private int calcOpReturnLen(int opReturnBytesLen) {
        int dataLen;
        if (opReturnBytesLen < 76) {
            dataLen = opReturnBytesLen + 1;
        } else if (opReturnBytesLen < 256) {
            dataLen = opReturnBytesLen + 2;
        } else dataLen = opReturnBytesLen + 3;
        int scriptLen;
        scriptLen = (dataLen + 1) + VarInt.sizeOf(dataLen + 1);
        int amountLen = 8;
        return scriptLen + amountLen;
    }

    //Sign TX

    public String signTx(RawTxInfo rawTxInfo, byte[] prikey){
        Transaction transaction = createTx(rawTxInfo, com.fc.fc_ajdk.core.fch.FchMainNetwork.MAINNETWORK);
        if(transaction==null)return null;
        return signTx(prikey,transaction);
    }

    public String signTx(byte[] prikey, Transaction transaction) {
        if(prikey==null){
            return null;
        }
        ECKey eckey = ECKey.fromPrivate(prikey);

        List<TransactionInput> inputs = transaction.getInputs();
        for (int i = 0; i < inputs.size(); ++i) {
            TransactionInput input = inputs.get(i);
            Script script = ScriptBuilder.createP2PKHOutputScript(eckey);
            Coin value = input.getValue();
            if(value==null)continue;
            SchnorrSignature signature = transaction.calculateSchnorrSignature(i, eckey, script.getProgram(), Coin.valueOf(value.getValue()), Transaction.SigHash.ALL, false);
            Script schnorr = ScriptBuilder.createSchnorrInputScript(signature, eckey);
            transaction.getInput(i).setScriptSig(schnorr);
        }

        byte[] signResult = transaction.bitcoinSerialize();
        return Hex.toHex(signResult);
    }

//Decode TX
    public String decodeTx(String rawTx) {
        return decodeTx(Hex.fromHex(rawTx), com.fc.fc_ajdk.core.fch.FchMainNetwork.MAINNETWORK);
    }
    public String decodeTx(String rawTx, MainNetParams mainNetParams) {
        byte[] rawTxBytes;
        try{
            if(Hex.isHexString(rawTx))rawTxBytes = Hex.fromHex(rawTx);
            else {
                rawTxBytes = Base64.getDecoder().decode(rawTx);
            }
        }catch (Exception e){
            return null;
        }
        return decodeTx(rawTxBytes,mainNetParams);
    }

    public String decodeTx(byte[] rawTxBytes, MainNetParams mainNetwork) {
        if(rawTxBytes==null) return null;

        Transaction transaction;
        // Handle parsing of combined format with input values
        List<Long> inputValueList = new ArrayList<>();
        byte[] rawTx;
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(rawTxBytes)) {
            int flag = byteArrayInputStream.read();
            transaction = new Transaction(mainNetwork, rawTxBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        // Build JSON structure
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append(String.format("  \"txid\": \"%s\",\n", transaction.getTxId()));
        json.append(String.format("  \"hash\": \"%s\",\n", transaction.getHash()));
        json.append(String.format("  \"version\": %d,\n", transaction.getVersion()));
        json.append(String.format("  \"size\": %d,\n", transaction.getMessageSize()));
        json.append(String.format("  \"locktime\": %d,\n", transaction.getLockTime()));

        // Handle inputs
        json.append("  \"vin\": [\n");
        List<TransactionInput> inputs = transaction.getInputs();
        for (int i = 0; i < inputs.size(); i++) {
            TransactionInput input = inputs.get(i);
            json.append("    {\n");
            json.append(String.format("      \"txid\": \"%s\",\n", input.getOutpoint().getHash()));
            json.append(String.format("      \"vout\": %d,\n", input.getOutpoint().getIndex()));
            json.append("      \"scriptSig\": {\n");
            json.append(String.format("        \"asm\": \"%s\",\n", input.getScriptSig().toString()));
            json.append(String.format("        \"hex\": \"%s\"\n", Hex.toHex(input.getScriptSig().getProgram())));
            json.append("      },\n");
            json.append(String.format("      \"sequence\": %d\n", input.getSequenceNumber()));
            json.append("    }").append(i < inputs.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ],\n");

        // Handle outputs
        json.append("  \"vout\": [\n");
        List<TransactionOutput> outputs = transaction.getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            json.append("    {\n");
            json.append(String.format("      \"value\": %.8f,\n", output.getValue().getValue() / 100000000.0));
            json.append(String.format("      \"n\": %d,\n", i));
            json.append("      \"scriptPubkey\": {\n");
            json.append(String.format("        \"asm\": \"%s\",\n", output.getScriptPubKey().toString()));
            json.append(String.format("        \"hex\": \"%s\",\n", Hex.toHex(output.getScriptPubKey().getProgram())));

            // Determine script type and addresses
            String type = getScriptType(output.getScriptPubKey());
            json.append(String.format("        \"type\": \"%s\"", type));

            if (!type.equals("nulldata")) {
                json.append(",\n        \"addresses\": [\n");
                try {
                    Address address = output.getScriptPubKey().getToAddress(mainNetwork);
                    json.append(String.format("          \"%s\"\n", address.toString()));
                } catch (Exception e) {
                    // Handle non-standard scripts
                }
                json.append("        ]");
            }
            json.append("\n      }\n");
            json.append("    }").append(i < outputs.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ]\n");
        json.append("}");

        return json.toString();
    }

    private String getScriptType(Script script) {
        if (script.isSentToAddress() || script.isSentToRawPubKey())
            return "pubkeyhash";
        else if (script.isPayToScriptHash())
            return "scripthash";
        else if (script.isOpReturn())
            return "nulldata";
        else
            return "nonstandard";
    }

//Multisign

    public Multisign createMultisign(List<byte[]> pubkeyList, int m) {
        List<ECKey> keys = new ArrayList<>();
        for (byte[] bytes : pubkeyList) {
            ECKey ecKey = ECKey.fromPublicOnly(bytes);
            keys.add(ecKey);
        }

        Script multiSigScript = ScriptBuilder.createMultiSigOutputScript(m, keys);

        byte[] redeemScriptBytes = multiSigScript.getProgram();

        Multisign multisign;
        try {
            multisign = Multisign.parseMultisignRedeemScript(Hex.toHex(redeemScriptBytes));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return multisign;
    }

    public RawTxInfo signSchnorrMultiSignTx(RawTxInfo RawMultisignTx, byte[] prikey) {

        byte[] rawTx = RawMultisignTx.makeRawTx();
        byte[] redeemScript = Hex.fromHex(RawMultisignTx.getMultisign().getRedeemScript());
        List<Cash> cashList = RawMultisignTx.getInputs();

        Transaction transaction = new Transaction(this.mainNetwork, rawTx);
        List<TransactionInput> inputs = transaction.getInputs();

        ECKey ecKey = ECKey.fromPrivate(prikey);
        BigInteger prikeyBigInteger = ecKey.getPrivKey();
        List<String> sigList = new ArrayList<>();
        for (int i = 0; i < inputs.size(); ++i) {
            Script script = new Script(redeemScript);
            Sha256Hash hash = transaction.hashForSignatureWitness(i, script, Coin.valueOf(cashList.get(i).getValue()), Transaction.SigHash.ALL, false);
            byte[] sig = SchnorrSignature.schnorr_sign(hash.getBytes(), prikeyBigInteger);
            sigList.add(Hex.toHex(sig));
        }

        String fid = prikeyToFid(prikey);
        if (RawMultisignTx.getFidSigMap() == null) {
            Map<String, List<String>> fidSigListMap = new HashMap<>();
            RawMultisignTx.setFidSigMap(fidSigListMap);
        }
        RawMultisignTx.getFidSigMap().put(fid, sigList);
        return RawMultisignTx;
    }

    public String buildSchnorrMultiSignTx(RawTxInfo rawTxInfo) {
        rawTxInfo.makeRawTx();
        Map<String, List<String>> sigListMap = rawTxInfo.getFidSigMap();
        Multisign multisign = rawTxInfo.getMultisign();
        byte[] rawTx = rawTxInfo.makeRawTx();
        if(rawTx==null){
            String rawTxHex = createTxHex(rawTxInfo);
            if(rawTxHex!=null) rawTx = Hex.fromHex(rawTxHex);
        }
        if (sigListMap.size() > multisign.getM())
            sigListMap = dropRedundantSigs(sigListMap, multisign.getM());

        Transaction transaction = new Transaction(this.mainNetwork, rawTx);

        for (int i = 0; i < transaction.getInputs().size(); i++) {
            List<byte[]> sigListByTx = new ArrayList<>();
            for (String fid : multisign.getFids()) {
                try {
                    String sig = sigListMap.get(fid).get(i);
                    sigListByTx.add(Hex.fromHex(sig));
                } catch (Exception ignore) {
                }
            }

            Script inputScript = createSchnorrMultiSigInputScriptBytes(sigListByTx, Hex.fromHex(multisign.getRedeemScript())); // Include all required signatures
            TransactionInput input = transaction.getInput(i);
            input.setScriptSig(inputScript);
        }

        byte[] signResult = transaction.bitcoinSerialize();
        return Hex.toHex(signResult);
    }

    public String buildSignedMultisignTx(String[] signedData) {
        ReplyBody replyBody = mergeMultisignTxData(signedData);
        if (replyBody == null) return null;
        if(replyBody.getCode()!=0){
            System.out.println(replyBody.getMessage());
            return null;
        }
        RawTxInfo finalRawTxInfo = (RawTxInfo) replyBody.getData();
        if (finalRawTxInfo == null) return null;
        return buildSchnorrMultiSignTx(finalRawTxInfo);
    }

    @Nullable
    public ReplyBody mergeMultisignTxData(String[] signedDatas) {
        Map<String, List<String>> fidSigListMap = new HashMap<>();
        RawTxInfo finalRawTxInfo = null;
        byte[] rawTx = null;
        Multisign multisign = null;
        ReplyBody replyBody;
        for (String dataJson : signedDatas) {
            try {


                RawTxInfo multiSignData = com.fc.fc_ajdk.core.fch.RawTxInfo.fromJson(dataJson, RawTxInfo.class);

                if (multisign == null
                        && multiSignData.getMultisign() != null) {
                    multisign = multiSignData.getMultisign();
                }

                if (rawTx == null
                        && multiSignData.makeRawTx() != null
                        && multiSignData.makeRawTx().length > 0) {
                    rawTx = multiSignData.makeRawTx();
                }

                for(String fid:multiSignData.getFidSigMap().keySet()){
                    List<String> sign = multiSignData.getFidSigMap().get(fid);
                    if(fidSigListMap.get(fid)==null){
                        replyBody = verifySig(fid, multiSignData);
                        if(replyBody.getCode()==0)
                            fidSigListMap.put(fid,sign);
                        else return replyBody;
                    }
                }
                finalRawTxInfo = multiSignData;
            } catch (Exception ignored) {
                replyBody= new ReplyBody();
                replyBody.set1020Other("Failed to parse the signed data.");
                return replyBody;
            }
        }
        if (rawTx == null || multisign == null) return null;

        finalRawTxInfo.setMultisign(multisign);
        finalRawTxInfo.setFidSigMap(fidSigListMap);

        replyBody = new ReplyBody();
        replyBody.set0Success();
        replyBody.setData(finalRawTxInfo);
        return replyBody;
    }

    private ReplyBody verifySig(String fid, RawTxInfo multiSignData) {

        ReplyBody replyBody = new ReplyBody();
        try {
            if (!multiSignData.getMultisign().getFids().contains(fid)){
                replyBody.set1020Other("The FID is not a member of "+multiSignData.getMultisign().getId());
                return replyBody;
            }
            int putKeyIndex = multiSignData.getMultisign().getFids().indexOf(fid);
            String pubkey = multiSignData.getMultisign().getPubkeys().get(putKeyIndex);
            String redeemScript = multiSignData.getMultisign().getRedeemScript();
            for(int i = 0; i<multiSignData.getInputs().size(); i++){
                if(!rawTxSigVerify(multiSignData.makeRawTx(), Hex.fromHex(pubkey), Hex.fromHex(multiSignData.getFidSigMap().get(fid).get(i)), i, multiSignData.getInputs().get(i).getValue(), Hex.fromHex(redeemScript), FchMainNetwork.MAINNETWORK)){
                    replyBody.set1020Other("The signature is invalid");
                    return replyBody;
                }
            }
        }catch (Exception e){
            replyBody.set1020Other("Failed to verify the signature.");
            return replyBody;
        }
        replyBody.set0Success();
        return replyBody;
    }


    public Script createSchnorrMultiSigInputScriptBytes(List<byte[]> signatures, byte[] multisigProgramBytes) {
        if (signatures.size() >= 16) return null;
        ScriptBuilder builder = new ScriptBuilder();
        builder.smallNum(0);
        Iterator<byte[]> var3 = signatures.iterator();
        byte[] sigHashAll = new byte[]{0x41};

        while (var3.hasNext()) {
            byte[] signature = (byte[]) var3.next();
            builder.data(BytesUtils.bytesMerger(signature, sigHashAll));
        }

        if (multisigProgramBytes != null) {
            builder.data(multisigProgramBytes);
        }

        return builder.build();
    }

    private static Map<String, List<String>> dropRedundantSigs(Map<String, List<String>> sigListMap, int m) {
        Map<String, List<String>> newMap = new HashMap<>();
        int i = 0;
        for (String key : sigListMap.keySet()) {
            newMap.put(key, sigListMap.get(key));
            i++;
            if (i == m) return newMap;
        }
        return newMap;
    }

    //Verify TX
    public boolean rawTxSigVerify(byte[] rawTx, byte[] pubkey, byte[] sig, int inputIndex, long inputValue, byte[] redeemScript, MainNetParams mainnetwork) {
        if(mainnetwork==null)mainnetwork=this.mainNetwork;
        Transaction transaction = new Transaction(mainnetwork, rawTx);
        Script script = new Script(redeemScript);
        Sha256Hash hash = transaction.hashForSignatureWitness(inputIndex, script, Coin.valueOf(inputValue), Transaction.SigHash.ALL, false);
        return SchnorrSignature.schnorr_verify(hash.getBytes(), pubkey, sig);
    }

//Untested
    public static String createTimeLockedTransaction(List<Cash> inputs, byte[] prikey, List<SendTo> outputs, long lockUntil, String opReturn, MainNetParams mainnetwork) {

        String changeToFid = inputs.get(0).getOwner();

        long fee;
        if (opReturn != null) {
            fee = TxCreator.calcTxSize(inputs.size(), outputs.size(), opReturn.getBytes().length);
        } else fee = TxCreator.calcTxSize(inputs.size(), outputs.size(), 0);

        Transaction transaction = new Transaction(mainnetwork);
    //        transaction.setLockTime(nLockTime);

        long totalMoney = 0;
        long totalOutput = 0;

        ECKey eckey = ECKey.fromPrivate(prikey);

        for (SendTo output : outputs) {
            long value = FchUtils.coinToSatoshi(output.getAmount());
            byte[] pubkeyHash = KeyTools.addrToHash160(output.getFid());
            totalOutput += value;

            ScriptBuilder builder = new ScriptBuilder();

            builder.number(lockUntil)
                    .op(ScriptOpCodes.OP_CHECKLOCKTIMEVERIFY)
                    .op(ScriptOpCodes.OP_DROP);

            builder.op(ScriptOpCodes.OP_DUP)
                    .op(ScriptOpCodes.OP_HASH160)
                    .data(pubkeyHash)
                    .op(ScriptOpCodes.OP_EQUALVERIFY)
                    .op(ScriptOpCodes.OP_CHECKSIG);

            Script cltvScript = builder.build();

            transaction.addOutput(Coin.valueOf(value), cltvScript);
        }

        if (opReturn != null && !opReturn.isEmpty()) {
            try {
                Script opreturnScript = ScriptBuilder.createOpReturnScript(opReturn.getBytes(StandardCharsets.UTF_8));
                transaction.addOutput(Coin.ZERO, opreturnScript);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        for (Cash input : inputs) {
            totalMoney += input.getValue();

            TransactionOutPoint outPoint = new TransactionOutPoint(mainnetwork, input.getBirthIndex(), Sha256Hash.wrap(input.getBirthTxId()));
            TransactionInput unsignedInput = new TransactionInput(mainnetwork, null, new byte[0], outPoint,Coin.valueOf(input.getValue()));
            transaction.addInput(unsignedInput);
        }

        if ((totalOutput + fee) > totalMoney) {
            throw new RuntimeException("input is not enough");
        }

        long change = totalMoney - totalOutput - fee;
        if (change > Constants.DustInSatoshi) {
            transaction.addOutput(Coin.valueOf(change), Address.fromBase58(mainnetwork, changeToFid));
        }

        for (int i = 0; i < inputs.size(); ++i) {
            Cash input = inputs.get(i);
            Script script = ScriptBuilder.createP2PKHOutputScript(eckey);
            SchnorrSignature signature = transaction.calculateSchnorrSignature(i, eckey, script.getProgram(), Coin.valueOf(input.getValue()), Transaction.SigHash.ALL, false);

            Script schnorr = ScriptBuilder.createSchnorrInputScript(signature, eckey);
            transaction.getInput(i).setScriptSig(schnorr);
        }

        byte[] signResult = transaction.bitcoinSerialize();
        return Hex.toHex(signResult);
    }

}