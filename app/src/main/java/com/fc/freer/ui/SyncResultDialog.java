package com.fc.freer.ui;

import android.app.Dialog;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.fc.freer.R;

/**
 * Dialog showing upload/download sync result: total, uploaded/downloaded, skipped, failed, and total fee.
 */
public class SyncResultDialog extends Dialog {
    public static void show(Context context, boolean isUpload, int total, int successCount,
                            int skippedCount, int failedCount, long totalFee, Runnable onDismiss) {
        SyncResultDialog d = new SyncResultDialog(context, isUpload, total, successCount,
                skippedCount, failedCount, totalFee);
        d.setOnDismissListener(dialog -> {
            if (onDismiss != null) onDismiss.run();
        });
        d.show();
    }

    public SyncResultDialog(Context context, boolean isUpload, int total, int successCount,
                             int skippedCount, int failedCount, long totalFee) {
        super(context);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_sync_result);
        setCancelable(true);
        setCanceledOnTouchOutside(true);

        TextView totalTv = findViewById(R.id.sync_result_total);
        TextView successTv = findViewById(R.id.sync_result_success);
        TextView skippedTv = findViewById(R.id.sync_result_skipped);
        TextView failedTv = findViewById(R.id.sync_result_failed);
        TextView feeTv = findViewById(R.id.sync_result_fee);
        Button okBtn = findViewById(R.id.sync_result_ok);

        totalTv.setText(context.getString(R.string.sync_result_total, total));
        totalTv.setVisibility(total > 0 ? View.VISIBLE : View.GONE);

        successTv.setText(isUpload
                ? context.getString(R.string.sync_result_uploaded, successCount)
                : context.getString(R.string.sync_result_downloaded, successCount));
        successTv.setVisibility(successCount > 0 ? View.VISIBLE : View.GONE);

        skippedTv.setText(context.getString(R.string.sync_result_skipped, skippedCount));
        skippedTv.setVisibility(skippedCount > 0 ? View.VISIBLE : View.GONE);

        failedTv.setText(context.getString(R.string.sync_result_failed, failedCount));
        failedTv.setVisibility(failedCount > 0 ? View.VISIBLE : View.GONE);

        feeTv.setText(context.getString(R.string.sync_result_fee, totalFee));
        feeTv.setVisibility(totalFee > 0 ? View.VISIBLE : View.GONE);

        okBtn.setOnClickListener(v -> dismiss());

        // Set dialog width to 80% of screen
        Window window = getWindow();
        if (window != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            window.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (metrics.widthPixels * 0.8);
            window.setAttributes(params);
        }
    }
}
