package com.fc.freer.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.Decryptor;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.Hex;
import com.fc.freer.model.BackupHeader;
import com.fc.freer.model.BackupKey;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * The FTSP31 entity backup vectors (Protocols/FTSP/vectors/backup.json, copied into FC-AJDK's
 * test resources by Freeverse/tools/sync-ftsp-vectors.sh), read the way the importer reads them:
 * BackupUtils.readBackup, then each key sealed for this device. They are the lists Safe's
 * ExportKeysActivity and ExportSecretActivity write in each of their three modes.
 */
public class SafeBackupCompatTest {
    private static final byte[] SYMKEY = Hex.fromHex("dc1e7c03e162397b355b6f1c895dfdf3790d98c10b920c55e91272b8eecada2a");

    @Test
    public void ftsp31Vectors() throws Exception {
        Path file = Paths.get("../FC-AJDK/src/test/resources/ftsp-vectors/backup.json");
        JsonArray vectors = JsonParser.parseString(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("vectors");
        assertTrue(vectors.size() > 0);
        for (JsonElement e : vectors) {
            JsonObject v = e.getAsJsonObject();
            String id = v.get("id").getAsString();
            JsonArray items;
            try {
                items = read(v.get("backupText").getAsString(), v.get("tClass").getAsString(),
                        v.has("password") ? v.get("password").getAsString() : null);
            } catch (IllegalStateException refused) {
                assertEquals(id + " was refused: " + refused.getMessage(), "reject", v.get("expect").getAsString());
                continue;
            }
            if (!"import".equals(v.get("expect").getAsString())) fail(id + " must be refused");
            assertEquals(id, v.getAsJsonArray("items"), items);
        }
    }

    private static JsonArray read(String text, String tClass, String typedPassword) throws Exception {
        Class<?> c = "KeyInfo".equals(tClass) ? KeyInfo.class : Secret.class;
        List<Object> objects = BackupUtils.readBackup(text.getBytes(StandardCharsets.UTF_8), c);
        BackupKey backupKey = null;
        BackupHeader header = null;
        List<Object> lines = new ArrayList<>();
        for (Object o : objects) {
            if (o instanceof BackupKey k) backupKey = k;
            else if (o instanceof BackupHeader h) header = h;
            else lines.add(o);
        }
        // FcEntityImporter.importEntity refuses a list whose two key names disagree.
        if (backupKey != null && header != null && header.getKeyName() != null
                && !header.getKeyName().equals(backupKey.getKeyName()))
            throw new IllegalStateException("key names differ");
        String password = backupKey != null && backupKey.getPassword() != null ? backupKey.getPassword() : typedPassword;

        JsonArray out = new JsonArray();
        for (Object line : lines) {
            if (line instanceof KeyInfo key) {
                boolean ok = key.getPrikeyCipher() != null
                        ? password != null && FcEntityImporter.openPasswordPrikey(key,
                                CryptoDataByte.fromBase64(key.getPrikeyCipher()), password, SYMKEY)
                        : key.sealPlainPrikey(SYMKEY);
                if (!ok) throw new IllegalStateException(key.getId() + " not opened");
                JsonObject o = new JsonObject();
                o.addProperty("fid", key.getId());
                o.addProperty("label", key.getLabel());
                o.addProperty("prikeyHex", Hex.toHex(key.decryptPrikey(SYMKEY)));
                o.addProperty("pubkeyHex", key.getPubkey());
                out.add(o);
            } else {
                Secret secret;
                if (line instanceof CryptoDataByte cipher) {
                    if (password == null) throw new IllegalStateException("password needed");
                    Decryptor.decryptByPassword(cipher, password.toCharArray());
                    if (cipher.getCode() == null || cipher.getCode() != 0) throw new IllegalStateException("wrong password");
                    secret = Secret.fromJson(new String(cipher.getData(), StandardCharsets.UTF_8), Secret.class);
                } else {
                    secret = (Secret) line;
                }
                JsonObject o = new JsonObject();
                o.addProperty("type", secret.getType());
                o.addProperty("title", secret.getTitle());
                o.addProperty("content", secret.getContent());
                if (secret.getMemo() != null) o.addProperty("memo", secret.getMemo());
                out.add(o);
            }
        }
        return out;
    }
}
