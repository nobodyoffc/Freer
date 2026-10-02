package com.fc.fc_ajdk.data.fcData;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.utils.Hex;

import org.junit.Test;

import java.security.SecureRandom;

/**
 * A key list exported elsewhere carries each key as a plain prikey. Imported as it is,
 * the key sat in the clear and, with no cipher, its identity could not sign or connect.
 */
public class KeyInfoSealTest {

    private static byte[] random32() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    @Test
    public void plainHexPrikeyIsSealedAndCleared() {
        byte[] prikey = random32();
        byte[] symkey = random32();
        String fid = KeyTools.prikeyToFid(prikey.clone());

        KeyInfo keyInfo = KeyInfo.fromJson(
                "{\"id\":\"" + fid + "\",\"prikey\":\"" + Hex.toHex(prikey) + "\"}", KeyInfo.class);
        assertTrue(keyInfo.sealPlainPrikey(symkey));

        assertNull(keyInfo.getPrikey());
        assertNotNull(keyInfo.getPrikeyCipher());
        assertNotNull(keyInfo.getPubkey());
        assertEquals(fid, keyInfo.getId());
        assertArrayEquals(prikey, keyInfo.decryptPrikey(symkey));
        assertFalse(keyInfo.toJson().contains("\"prikey\""));
    }

    @Test
    public void prikeyOfAnotherFidIsRefused() {
        byte[] prikey = random32();
        String otherFid = KeyTools.prikeyToFid(random32());

        KeyInfo keyInfo = KeyInfo.fromJson(
                "{\"id\":\"" + otherFid + "\",\"prikey\":\"" + Hex.toHex(prikey) + "\"}", KeyInfo.class);
        assertFalse(keyInfo.sealPlainPrikey(random32()));
        assertNull(keyInfo.getPrikeyCipher());
    }

    @Test
    public void existingCipherIsLeftAlone() {
        byte[] prikey = random32();
        byte[] symkey = random32();
        KeyInfo keyInfo = new KeyInfo(null, prikey, symkey);
        String cipher = keyInfo.getPrikeyCipher();
        keyInfo.setPrikey("published");

        assertTrue(keyInfo.sealPlainPrikey(random32()));
        assertEquals(cipher, keyInfo.getPrikeyCipher());
    }
}
