package com.fc.freer.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.fc_ajdk.utils.IdNameUtils;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.model.BackupHeader;
import com.fc.freer.model.BackupKey;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * The lists Safe's ExportKeysActivity and ExportSecretActivity write, built line for line as
 * Safe builds them, read back by Freer's backup reader.
 */
public class SafeBackupCompatTest {
    private static final String PASSWORD = "safe-password";

    private static byte[] random32() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** Safe's BackupKeysActivity.addKeyInfoJson: the pubkey is dropped, the prikey kept or sealed. */
    private static String safeKeyLine(byte[] prikey, String password) {
        KeyInfo keyInfo = new KeyInfo();
        keyInfo.setId(KeyTools.prikeyToFid(prikey.clone()));
        keyInfo.setLabel("from safe");
        keyInfo.setSaveTime("2026-10-02");
        if (password == null) {
            keyInfo.setPrikey(Hex.toHex(prikey));
        } else {
            keyInfo.setPrikeyCipher(new Encryptor().encryptByPassword(prikey.clone(), password.toCharArray()).toBase64());
        }
        return keyInfo.toNiceJson();
    }

    private static List<String> safeHeaderLines(String tClass, int items, String randomPassword) {
        BackupHeader header = new BackupHeader();
        header.settClass(tClass);
        header.setTime(System.currentTimeMillis());
        header.setItems(items);
        header.setAlg(AlgorithmId.FC_AesGcm256_No1_NrC7.getDisplayName());
        String password = randomPassword != null ? randomPassword : PASSWORD;
        header.setKeyName(IdNameUtils.makeKeyName(password.getBytes()));
        BackupKey backupKey = new BackupKey();
        backupKey.setTime(header.getTime());
        backupKey.setKeyName(header.getKeyName());
        if (randomPassword != null) backupKey.setPassword(randomPassword);
        else backupKey.setHint("The app password can not be shown.");
        List<String> lines = new ArrayList<>();
        lines.add(JsonUtils.toNiceJson(backupKey));
        lines.add(header.toNiceJson());
        return lines;
    }

    private static List<Object> read(List<String> lines, Class<?> tClass) throws Exception {
        String text = JsonUtils.makeJsonListString(lines);
        return BackupUtils.readBackup(text.getBytes(StandardCharsets.UTF_8), tClass);
    }

    private static <T> List<T> only(List<Object> objects, Class<T> c) {
        List<T> list = new ArrayList<>();
        for (Object o : objects) if (c.isInstance(o)) list.add(c.cast(o));
        return list;
    }

    @Test
    public void keysWithoutEncryption() throws Exception {
        byte[] p1 = random32(), p2 = random32();
        List<String> lines = new ArrayList<>();
        lines.add(safeKeyLine(p1, null));
        lines.add(safeKeyLine(p2, null));

        List<KeyInfo> keys = only(read(lines, KeyInfo.class), KeyInfo.class);
        assertEquals(2, keys.size());
        byte[] symkey = random32();
        assertTrue(keys.get(0).sealPlainPrikey(symkey));
        assertNull(keys.get(0).getPrikey());
        assertNotNull(keys.get(0).getPubkey());
        assertArrayEquals(p1, keys.get(0).decryptPrikey(symkey));
    }

    @Test
    public void keysByRandomPassword() throws Exception {
        String random = "RANDOMPASSWORD";
        byte[] p1 = random32();
        List<String> lines = safeHeaderLines("KeyInfo", 1, random);
        lines.add(safeKeyLine(p1, random));

        List<Object> objects = read(lines, KeyInfo.class);
        assertEquals(random, only(objects, BackupKey.class).get(0).getPassword());
        assertEquals(1, only(objects, BackupHeader.class).size());
        KeyInfo key = only(objects, KeyInfo.class).get(0);
        CryptoDataByte cipher = CryptoDataByte.fromBase64(key.getPrikeyCipher());
        Decryptor.decryptByPassword(cipher, random.toCharArray());
        assertEquals(Integer.valueOf(0), cipher.getCode());
        assertArrayEquals(p1, cipher.getData());
        assertNull(key.getPubkey());
    }

    @Test
    public void keysByCurrentPassword() throws Exception {
        List<String> lines = safeHeaderLines("KeyInfo", 1, null);
        lines.add(safeKeyLine(random32(), PASSWORD));

        List<Object> objects = read(lines, KeyInfo.class);
        BackupKey backupKey = only(objects, BackupKey.class).get(0);
        assertNull(backupKey.getPassword());
        assertEquals(1, only(objects, KeyInfo.class).size());
    }

    private static Secret secret(String title) {
        Secret secret = new Secret();
        secret.setTitle(title);
        secret.setContent("content of " + title);
        return secret;
    }

    @Test
    public void secretsInAllThreeModes() throws Exception {
        // Don't encrypt: Safe still writes the header, no BackupKey.
        List<String> plain = new ArrayList<>();
        BackupHeader header = new BackupHeader();
        header.settClass("Secret");
        header.setTime(System.currentTimeMillis());
        header.setItems(1);
        plain.add(header.toNiceJson());
        plain.add(JsonUtils.toNiceJson(secret("a")));
        List<Secret> plainSecrets = only(read(plain, Secret.class), Secret.class);
        assertEquals(1, plainSecrets.size());
        assertEquals("content of a", plainSecrets.get(0).getContent());

        // Random password: each secret is one password cipher of its whole JSON.
        String random = "RANDOMPASSWORD";
        List<String> enc = safeHeaderLines("Secret", 1, random);
        enc.add(new Encryptor().encryptByPassword(secret("b").toBytes(), random.toCharArray()).toNiceJson());
        List<Object> objects = read(enc, Secret.class);
        List<CryptoDataByte> ciphers = only(objects, CryptoDataByte.class);
        assertEquals(1, ciphers.size());
        Decryptor.decryptByPassword(ciphers.get(0), random.toCharArray());
        assertEquals(Integer.valueOf(0), ciphers.get(0).getCode());
        Secret opened = Secret.fromJson(new String(ciphers.get(0).getData()), Secret.class);
        assertEquals("content of b", opened.getContent());
    }
}
