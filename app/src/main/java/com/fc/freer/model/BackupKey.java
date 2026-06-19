package com.fc.freer.model;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.freer.R;
import com.fc.freer.secret.ExportSecretActivity;

public class BackupKey extends FcObject {
    private String password;
    private String symkey;
    private String time;
    private String keyName;
    private String hint;

    public static BackupKey makeBackupKey(BackupHeader backupHeader, String inputSymKeyStr, String randomPassword, Context context) {
        BackupKey backupKey = new BackupKey();
        backupKey.setTime(backupHeader.getTime());
        backupKey.setKeyName(backupHeader.getKeyName());
        if (randomPassword != null) {
            backupKey.setPassword(randomPassword);
        } else if (inputSymKeyStr != null) {
            backupKey.setSymkey(inputSymKeyStr);

        } else {
            backupKey.setHint(context.getString(R.string.app_password_can_not_be_shown_please_keep_it_carefully));
        }
        return backupKey;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSymkey() {
        return symkey;
    }

    public void setSymkey(String symkey) {
        this.symkey = symkey;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getKeyName() {
        return keyName;
    }

    public void setKeyName(String keyName) {
        this.keyName = keyName;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }
}
