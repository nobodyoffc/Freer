package com.fc.freer.utils;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.ui.IoIconsView;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;

public class TextIconsUtils {
    private static final String TAG = "TextIconsUtils";

    public static void setupTextIcons(Dialog dialog, int textView, int textIcons, int QR_SCAN_TEXT_REQUEST_CODE) {
        TimberLogger.i(TAG, "setupTextIcons for Dialog called with requestCode: " + QR_SCAN_TEXT_REQUEST_CODE);

        // Find the EditText within the container for paste functionality
        View container = dialog.findViewById(textView);
        com.google.android.material.textfield.TextInputEditText editText = null;
        if (container != null) {
            editText = container.findViewById(R.id.textInput);
            if (editText == null) {
                editText = container.findViewById(R.id.keyInput);
            }
        }

        final com.google.android.material.textfield.TextInputEditText finalEditText = editText;

        setupIoIconsView(dialog,
                textView,
                textIcons,
                false,  // showMakeQr
                false,  // showPeople
                true,   // showScan
                true,   // showPaste
                false,  // showFile
                null,
                null,
                () -> {
                    TimberLogger.i(TAG, "Scan icon clicked in Dialog");
                    startQrScan(dialog, QR_SCAN_TEXT_REQUEST_CODE);
                },
                () -> {
                    TimberLogger.i(TAG, "Paste icon clicked in Dialog");
                    if (finalEditText != null) {
                        pasteFromClipboard(dialog, finalEditText);
                    }
                },
                null);
    }


    public static void setupTextIcons(Activity activity, int textView, int textIcons, int QR_SCAN_TEXT_REQUEST_CODE) {
        setupTextIcons(activity, textView, textIcons, QR_SCAN_TEXT_REQUEST_CODE, false, false, null, null);
    }

    /**
     * Sets up the scan and paste icons like {@link #setupTextIcons(Activity, int, int, int)} and,
     * in addition, optionally shows the people icon (for FID fields) and/or the file icon (for DID
     * fields) with the supplied click listeners. The people and file icons stay hidden when their
     * listener is not requested, so a field only exposes what its content actually supports.
     */
    public static void setupTextIcons(Activity activity, int textView, int textIcons, int QR_SCAN_TEXT_REQUEST_CODE,
                                      boolean showPeople, boolean showFile,
                                      IoIconsView.OnPeopleClickListener peopleListener,
                                      IoIconsView.OnFileClickListener fileListener) {
        TimberLogger.i(TAG, "setupTextIcons for Activity called with requestCode: " + QR_SCAN_TEXT_REQUEST_CODE);

        // Find the EditText within the container for paste functionality
        View container = activity.findViewById(textView);
        com.google.android.material.textfield.TextInputEditText editText = null;
        if (container != null) {
            editText = container.findViewById(R.id.textInput);
            if (editText == null) {
                editText = container.findViewById(R.id.keyInput);
            }
        }

        final com.google.android.material.textfield.TextInputEditText finalEditText = editText;

        if (activity instanceof BaseCryptoActivity) {
            setupIoIconsView(activity, textView, textIcons,
                    false,  // showMakeQr
                    showPeople,
                    true,   // showScan
                    true,   // showPaste
                    showFile,
                    null,
                    peopleListener,
                    () -> {
                        TimberLogger.i(TAG, "Scan icon clicked in BaseCryptoActivity");
                        ((BaseCryptoActivity) activity).startQrScan(QR_SCAN_TEXT_REQUEST_CODE);
                    },
                    () -> {
                        TimberLogger.i(TAG, "Paste icon clicked in BaseCryptoActivity");
                        if (finalEditText != null) {
                            ((BaseCryptoActivity) activity).pasteFromClipboard(finalEditText);
                        }
                    },
                    fileListener);
        } else {
            setupIoIconsView(activity, textView, textIcons,
                    false,  // showMakeQr
                    showPeople,
                    true,   // showScan
                    true,   // showPaste
                    showFile,
                    null,
                    peopleListener,
                    () -> {
                        TimberLogger.i(TAG, "Scan icon clicked in Activity");
                        startQrScan(activity, QR_SCAN_TEXT_REQUEST_CODE);
                    },
                    () -> {
                        TimberLogger.i(TAG, "Paste icon clicked in Activity");
                        if (finalEditText != null) {
                            pasteFromClipboard(activity, finalEditText);
                        }
                    },
                    fileListener);
        }
    }

    public static void setupTextWithoutIcons(Dialog dialog, int textView, int textIcons) {
        TimberLogger.i(TAG, "setupTextWithoutIcons for Dialog called");
        View container = dialog.findViewById(textView);
        IoIconsView icons = container.findViewById(textIcons);
        if (icons != null) {
            icons.init(dialog.getContext(), false, false, false, false, false);
        }
    }

    public static void setupTextWithoutIcons(Activity activity, int textView, int textIcons) {
        TimberLogger.i(TAG, "setupTextWithoutIcons for Activity called");
        View container = activity.findViewById(textView);
        IoIconsView icons = container.findViewById(textIcons);
        if (icons != null) {
            icons.init(activity, false, false, false, false, false);
        }
    }

    private static void setupIoIconsView(Dialog dialog, int containerId, int iconId, boolean showMakeQr, boolean showPeople,
                                         boolean showScan, boolean showPaste, boolean showFile,
                                         IoIconsView.OnMakeQrClickListener makeQrListener,
                                         IoIconsView.OnPeopleClickListener peopleListener,
                                         IoIconsView.OnScanClickListener scanListener,
                                         IoIconsView.OnPasteClickListener pasteListener,
                                         IoIconsView.OnFileClickListener fileListener) {
        TimberLogger.i(TAG, "setupIoIconsView for Dialog called with showScan: " + showScan);
        View container = dialog.findViewById(containerId);
        IoIconsView icons = container.findViewById(iconId);
        if (icons != null) {
            TimberLogger.i(TAG, "Found IoIconsView in Dialog, initializing");
            icons.init(dialog.getContext(), showMakeQr, showPeople, showScan, showPaste, showFile);
            if (makeQrListener != null) icons.setOnMakeQrClickListener(makeQrListener);
            if (peopleListener != null) icons.setOnPeopleClickListener(peopleListener);
            if (scanListener != null) {
                TimberLogger.i(TAG, "Setting scan click listener for Dialog");
                icons.setOnScanClickListener(scanListener);
            }
            if (pasteListener != null) icons.setOnPasteClickListener(pasteListener);
            if (fileListener != null) icons.setOnFileClickListener(fileListener);
        } else {
            TimberLogger.e(TAG, "IoIconsView not found in Dialog for id: " + iconId);
        }
    }

    private static void setupIoIconsView(Activity activity, int containerId, int iconId, boolean showMakeQr, boolean showPeople,
                                         boolean showScan, boolean showPaste, boolean showFile,
                                         IoIconsView.OnMakeQrClickListener makeQrListener,
                                         IoIconsView.OnPeopleClickListener peopleListener,
                                         IoIconsView.OnScanClickListener scanListener,
                                         IoIconsView.OnPasteClickListener pasteListener,
                                         IoIconsView.OnFileClickListener fileListener) {
        TimberLogger.i(TAG, "setupIoIconsView for Activity called with showScan: " + showScan);
        View container = activity.findViewById(containerId);
        IoIconsView icons = container.findViewById(iconId);
        if (icons != null) {
            TimberLogger.i(TAG, "Found IoIconsView in Activity, initializing");
            icons.init(activity, showMakeQr, showPeople, showScan, showPaste, showFile);
            if (makeQrListener != null) icons.setOnMakeQrClickListener(makeQrListener);
            if (peopleListener != null) icons.setOnPeopleClickListener(peopleListener);
            if (scanListener != null) {
                TimberLogger.i(TAG, "Setting scan click listener for Activity");
                icons.setOnScanClickListener(scanListener);
            }
            if (pasteListener != null) icons.setOnPasteClickListener(pasteListener);
            if (fileListener != null) icons.setOnFileClickListener(fileListener);
        } else {
            TimberLogger.e(TAG, "IoIconsView not found in Activity for id: " + iconId);
        }
    }

    private static void startQrScan(Dialog dialog, int requestCode) {
        TimberLogger.i(TAG, "startQrScan for Dialog called with requestCode: " + requestCode);
        Activity activity = dialog.getOwnerActivity();
        if (activity != null) {
            if (activity instanceof BaseCryptoActivity) {
                TimberLogger.i(TAG, "Starting QR scan from BaseCryptoActivity");
                ((BaseCryptoActivity) activity).startQrScan(requestCode);
            } else {
                TimberLogger.e(TAG, "Owner activity is not a BaseCryptoActivity");
                ToastUtils.makeText(activity, R.string.qr_scanning_not_supported);
            }
        } else {
            TimberLogger.e(TAG, "Dialog has no owner activity");
        }
    }

    private static void startQrScan(Activity activity, int requestCode) {
        TimberLogger.i(TAG, "startQrScan for Activity called with requestCode: " + requestCode);
        if (activity instanceof BaseCryptoActivity) {
            TimberLogger.i(TAG, "Starting QR scan from BaseCryptoActivity");
            ((BaseCryptoActivity) activity).startQrScan(requestCode);
        } else {
            TimberLogger.e(TAG, "Activity is not a BaseCryptoActivity");
            ToastUtils.makeText(activity, R.string.qr_scanning_not_supported);
        }
    }

    /**
     * Helper method to paste text from clipboard to an EditText in Dialog context
     */
    private static void pasteFromClipboard(Dialog dialog, com.google.android.material.textfield.TextInputEditText editText) {
        android.content.Context context = dialog.getContext();
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                android.content.ClipData.Item item = clipData.getItemAt(0);
                CharSequence text = item.getText();
                if (text != null && text.length() > 0) {
                    editText.setText(text.toString());
                    ToastUtils.makeText(context, R.string.pasted);
                    return;
                }
            }
        }
        ToastUtils.makeText(context, R.string.clipboard_is_empty);
    }

    /**
     * Helper method to paste text from clipboard to an EditText in Activity context
     */
    private static void pasteFromClipboard(android.app.Activity activity, com.google.android.material.textfield.TextInputEditText editText) {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                android.content.ClipData.Item item = clipData.getItemAt(0);
                CharSequence text = item.getText();
                if (text != null && text.length() > 0) {
                    editText.setText(text.toString());
                    ToastUtils.makeText(activity, R.string.pasted);
                    return;
                }
            }
        }
        ToastUtils.makeText(activity, R.string.clipboard_is_empty);
    }
}