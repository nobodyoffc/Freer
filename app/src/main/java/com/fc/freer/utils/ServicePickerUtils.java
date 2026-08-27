package com.fc.freer.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.EditText;

import com.fc.fc_ajdk.data.fcData.FcEntity;
import com.fc.fc_ajdk.fapi.client.ApiProvider;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.data.SetDiskActivity;

/**
 * Shared helpers for picking an on-chain service (DOCK, DISK, ...) with
 * {@link SetDiskActivity} and applying the selected service's SID to an input field.
 */
public final class ServicePickerUtils {
    private static final String TAG = "ServicePickerUtils";

    private ServicePickerUtils() {}

    /** Intent launching the service picker filtered by the given component (e.g. DOCK/DISK). */
    public static Intent pickerIntent(Context context, String component, String title) {
        Intent intent = new Intent(context, SetDiskActivity.class);
        intent.putExtra(SetDiskActivity.EXTRA_COMPONENT, component);
        intent.putExtra(SetDiskActivity.EXTRA_TITLE, title);
        return intent;
    }

    /** Reads the picked service from the picker result and sets its SID into the target input. */
    public static void applySelectedService(Activity activity, Intent data, EditText target) {
        String json = data.getStringExtra(SetDiskActivity.EXTRA_SELECTED_SERVICE);
        if (json == null || json.isEmpty()) return;
        new Thread(() -> {
            try {
                ApiProvider provider = FcEntity.fromJson(json, ApiProvider.class);
                if (provider == null || provider.getId() == null) return;
                final String sid = provider.getId();
                activity.runOnUiThread(() -> target.setText(sid));
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error reading selected service: %s", e.getMessage());
            }
        }).start();
    }
}
