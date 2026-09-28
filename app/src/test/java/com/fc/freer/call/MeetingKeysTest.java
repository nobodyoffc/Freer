package com.fc.freer.call;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.fc.fc_ajdk.call.CallKeys;
import com.fc.fc_ajdk.utils.Hex;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.List;

public class MeetingKeysTest {

    static final String TEAM = "FTeam";
    static final String ID = "mtg_00112233445566778899aabb";

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    @Test
    public void theKeyWhoseAuthPubMatchesIsTheOne() {
        byte[] ours = random(32), other = random(32), nonce = random(32);
        byte[] secret = CallKeys.meetingSecret(ours, nonce, TEAM, 7, ID);
        String authPub = Hex.toHex(CallKeys.authPub(CallKeys.authPriv(secret)));
        // Two keys at version 7 (FIMP2 §7.1): the host's is the second.
        assertArrayEquals(secret, MeetingKeys.secret(List.of(other, ours), nonce, TEAM, 7, ID, authPub));
        assertNull("without the host's key, no secret", MeetingKeys.secret(List.of(other), nonce, TEAM, 7, ID, authPub));
        assertNull("another version's derivation does not match",
                MeetingKeys.secret(List.of(ours), nonce, TEAM, 8, ID, authPub));
        assertNull(MeetingKeys.secret(null, nonce, TEAM, 7, ID, authPub));
    }
}
