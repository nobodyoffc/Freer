package com.fc.freer.utils;

import static com.fc.fc_ajdk.utils.JsonUtils.readOneJsonFromInputStream;

import com.fc.fc_ajdk.core.crypto.CryptoDataByte;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.model.BackupHeader;
import com.fc.freer.model.BackupKey;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BackupUtils {

    public static <T> List<Object> readBackup(File file, Class<T> tClass) {
        try(FileInputStream fis = new FileInputStream(file)){
            return readBackup(fis,tClass);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read backup data from "+file.getAbsolutePath(),e);
        }
    }

    public static <T> List<Object> readBackup(byte[] bytes, Class<T> tClass) {
        if(bytes==null || bytes.length==0)return null;
        try(InputStream is = new ByteArrayInputStream(bytes)){
            return readBackup(is,tClass);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> List<Object> readBackup(InputStream is, Class<T> tClass) throws Exception {
        List<Object> objectList = new ArrayList<>();
        while(true) {
            byte[] jsonBytes = readOneJsonFromInputStream(is);
            if (jsonBytes == null) return objectList;
            String json = new String(jsonBytes, StandardCharsets.UTF_8);

            try {
                CryptoDataByte cryptoDataByte = CryptoDataByte.fromJson(json);
                if (cryptoDataByte.getCipher() != null && cryptoDataByte.getIv() != null) {
                    objectList.add(cryptoDataByte);
                    continue;
                }
            }catch (Exception ignore){
                continue;
            }

            try {

                BackupKey backupKey = BackupKey.fromJson (json, BackupKey.class);
                // A backup encrypted with the app password carries only a hint, and is still the key line
                if (backupKey != null && (backupKey.getPassword()!=null || backupKey.getSymkey()!=null
                        || (backupKey.getHint()!=null && backupKey.getKeyName()!=null))){
                    objectList.add(backupKey);
                    continue;
                }
            }catch (Exception ignore){
                continue;
            }

            try {

                BackupHeader backupHeader = JsonUtils.fromJson(json, BackupHeader.class);
                if (backupHeader != null && backupHeader.getItems()!=null && backupHeader.getTime()!=null) {
                    objectList.add(backupHeader);
                    continue;
                }
            }catch (Exception ignore){
                continue;
            }
            try {
                T t = JsonUtils.fromJson(json, tClass);
                if (t != null) {
                    if(t instanceof Secret secret){
                        if(secret.getContent()==null && secret.getContentCipher()==null)continue;
                    }

                    if(t instanceof KeyInfo keyInfo){
                        // getId() makes a hash id when none was given, so demand a real FID
                        if(!KeyTools.isGoodFid(keyInfo.getId()))continue;
                    }

                    objectList.add(t);
                }
            }catch (Exception ignore){}
        }
    }
}
