package com.fc.freer.model;

import android.content.Context;

import com.fc.fc_ajdk.data.fcData.FcObject;
import com.fc.fc_ajdk.utils.Base32;
import com.fc.fc_ajdk.utils.BytesUtils;
import com.fc.freer.R;
import com.fc.freer.secret.ExportSecretActivity;

public class BackupKey extends FcObject {
    private String password;
    private String symkey;
    private String time;
    private String hint;

    /** @return a 128-bit random password, 26 Base32 characters. */
    public static String makeRandomPassword() {
        return Base32.toBase32(BytesUtils.getRandomBytes(16));
    }

    public static BackupKey makeBackupKey(BackupHeader backupHeader, String inputSymKeyStr, String randomPassword, Context context) {
        BackupKey backupKey = new BackupKey();
        backupKey.setTime(backupHeader.getTime());
        if (randomPassword != null) {
            // The random password is handed over apart from the export, never inside it.
            backupKey.setHint(context.getString(R.string.random_password_kept_apart));
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

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }
}
