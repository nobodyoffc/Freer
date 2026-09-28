package com.fc.freer.call;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.utils.Hex;

import java.util.Arrays;
import java.util.List;

/**
 * A meeting's call secret from the entity's symkey (VOICE_SPEC §4.2). A
 * member may hold two keys at one version (FIMP2 §7.1): the right one is the
 * one whose admission key matches the {@code authPub} the host posted, which
 * says which without sending the key or its hash.
 */
final class MeetingKeys {

    private MeetingKeys() {}

    /**
     * @param symkeys the keys held at {@code version}
     * @return the call secret whose authPub is {@code authPubHex}, or null if
     *         none of them gives it: this member lacks that key version
     */
    static byte[] secret(List<byte[]> symkeys, byte[] nonce, String entityId, long version, String meetingId,
                         String authPubHex) {
        if (symkeys == null || authPubHex == null) return null;
        byte[] want = Hex.fromHex(authPubHex);
        for (byte[] symkey : symkeys) {
            byte[] secret = CallKeys.meetingSecret(symkey, nonce, entityId, version, meetingId);
            if (Arrays.equals(CallKeys.authPub(CallKeys.authPriv(secret)), want)) return secret;
            Arrays.fill(secret, (byte) 0);
        }
        return null;
    }
}
