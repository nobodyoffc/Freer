package com.fc.freer.utils;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

/**
 * Shows a simple "About &lt;entity&gt;" explanation dialog. Freer entities map to FEIP
 * protocols whose behaviour differs from traditional apps, so each entity list screen
 * offers an "About" item in its more-menu that explains the entity via this dialog.
 */
public final class AboutDialog {
    private AboutDialog() {}

    public static void show(Context context, int titleRes, int messageRes) {
        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
