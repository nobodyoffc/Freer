package com.fc.fc_ajdk.data.fcData;

import com.fc.fc_ajdk.constants.CodeMessage;
import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.utils.TimberLogger;

/**
 * Sealing and opening an {@link ImMessage} body.
 *
 * <p>Seal fails loudly; open fails quietly. The asymmetry is the point.
 * Failing to seal must stop a send -- the alternative is putting the plaintext
 * on the wire. Failing to open must not stop anything: a batch arriving after a
 * key rotation will contain some we cannot read yet, and each of those is a row
 * to show as locked and a key to go and ask for, not an error to abort on.
 *
 * <p><b>What gets sealed is the whole body</b>, both {@code content} and
 * {@code data}, framed together by {@link ImMessage#bodyFraming()}. v1 sealed
 * {@code content} alone and left the binary payload beside it in the clear, so
 * a voice note travelled with its metadata encrypted and its audio readable,
 * and a file share travelled with the key next to the ciphertext it unlocked.
 * Sealing the framing instead makes that state unrepresentable rather than
 * merely discouraged.
 *
 * <p><b>The sealed form is a binary bundle</b>, not a CryptoDataStr JSON
 * string: alg(6) type(1) [pubkeyA(33)] [keyName(6)] iv(12) cipher. 52 bytes of
 * overhead for AsyTwoWay against roughly 200 plus 33% Base64 for the JSON form.
 *
 * <p><b>Algorithms are pinned to GCM.</b> {@code new Encryptor()} defaults to
 * AES-CBC, whose bundle carries a trailing sum and which the Mac client does
 * not read for an IM body. Every path here names its algorithm explicitly.
 */
public final class ImMessageBody {

    private static final String TAG = "ImMessageBody";

    private ImMessageBody() {}

    // ========== symkey (team, room) ==========

    /**
     * Seal the body under a group key, stamping the version so the far end
     * knows which key to reach for.
     *
     * @return true if sealed; false leaves the message untouched and the caller
     *         MUST fail the send rather than transmit it
     */
    public static boolean sealWithSymkey(ImMessage message, byte[] symkey, Long version) {
        if (message == null || symkey == null) return false;
        if (message.getContent() == null && message.getData() == null) {
            TimberLogger.e(TAG, "Nothing to seal");
            return false;
        }
        try {
            CryptoDataByte sealed = new Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
                    .encryptBySymkey(message.bodyFraming(), symkey);
            byte[] bundle = toBundle(sealed);
            if (bundle == null) return false;

            message.setBody(bundle);
            message.setSymkeyVersion(version);
            message.setContent(null);
            message.setData(null);
            return true;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to seal body under symkey: %s", e.getMessage());
            return false;
        }
    }

    /** Recover the body from a symkey-sealed bundle. */
    public static boolean openWithSymkey(ImMessage message, byte[] symkey) {
        if (message == null || symkey == null) return false;
        byte[] body = message.getBody();
        if (body == null || body.length == 0) return false;
        try {
            CryptoDataByte opened = new Decryptor().decryptBundleBySymkey(body, symkey);
            byte[] framing = dataOf(opened, message);
            if (framing == null) return false;
            message.applyBodyFraming(framing);
            return true;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to open message %s: %s", message.getId(), e.getMessage());
            return false;
        }
    }

    // ========== asymmetric (p2p) ==========

    /**
     * Seal the body for a single recipient.
     *
     * <p>A message to ourselves goes AsyOneWay: an AsyTwoWay envelope with the
     * same pubkey in both slots is one the side-selection cannot resolve, so it
     * would be sealed to nobody, ourselves included.
     *
     * <p>Note that an AsyTwoWay <em>bundle</em> records only {@code pubkeyA},
     * unlike the JSON envelope, so it cannot be reopened by its sender. Nothing
     * needs that -- the local transcript keeps our own messages as plaintext.
     *
     * @param selfChat true when sender and target are the same identity
     */
    public static boolean sealForPeer(ImMessage message, byte[] prikey, byte[] recipientPubkey, boolean selfChat) {
        if (message == null || prikey == null || recipientPubkey == null) return false;
        if (message.getContent() == null && message.getData() == null) {
            TimberLogger.e(TAG, "Nothing to seal");
            return false;
        }
        try {
            byte[] framing = message.bodyFraming();
            Encryptor encryptor = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7);
            CryptoDataByte sealed = selfChat
                    ? encryptor.encryptByAsyOneWay(framing, recipientPubkey)
                    : encryptor.encryptByAsyTwoWay(framing, prikey, recipientPubkey);
            byte[] bundle = toBundle(sealed);
            if (bundle == null) return false;

            message.setBody(bundle);
            message.setContent(null);
            message.setData(null);
            return true;
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to seal body for a peer: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Recover the body from an AsyOneWay or AsyTwoWay bundle.
     *
     * <p>Both shapes open the same way -- ECDH against the single recorded
     * {@code pubkeyA} -- which is why there is no side to select and no peer
     * pubkey to pass in.
     */
    public static boolean openWithPrikey(ImMessage message, byte[] prikey) {
        if (message == null || prikey == null) return false;
        byte[] body = message.getBody();
        if (body == null || body.length == 0) return false;
        try {
            CryptoDataByte opened = new Decryptor().decryptBundleByAsyOneWay(body, prikey);
            byte[] framing = dataOf(opened, message);
            if (framing == null) return false;
            message.applyBodyFraming(framing);
            return true;
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to open message %s: %s", message.getId(), e.getMessage());
            return false;
        }
    }

    // ========== helpers ==========

    private static byte[] toBundle(CryptoDataByte sealed) {
        if (sealed == null || (sealed.getCode() != null && sealed.getCode() != CodeMessage.Code0Success)) {
            TimberLogger.e(TAG, "Encryption failed: %s", sealed != null ? sealed.getMessage() : "null result");
            return null;
        }
        byte[] bundle = sealed.toBundle();
        if (bundle == null) {
            // toBundle() returns null rather than throwing when a required
            // piece is missing -- an unsupported algorithm, or a Symkey
            // envelope with no keyName. Never send in that case.
            TimberLogger.e(TAG, "Sealed body could not be encoded as a bundle");
        }
        return bundle;
    }

    private static byte[] dataOf(CryptoDataByte opened, ImMessage message) {
        if (opened == null || (opened.getCode() != null && opened.getCode() != CodeMessage.Code0Success)
                || opened.getData() == null) {
            TimberLogger.w(TAG, "Could not open message %s: %s", message.getId(),
                    opened != null ? opened.getMessage() : "null result");
            return null;
        }
        return opened.getData();
    }
}
