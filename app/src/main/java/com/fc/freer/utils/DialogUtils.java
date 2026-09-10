package com.fc.freer.utils;

import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

/**
 * Dialog helpers.
 * <p>
 * The main job here is making dialog text selectable. Dialogs carry the things a user most often
 * needs to get out of the app verbatim — an error, a service id, a document hash — and by default
 * an {@link AlertDialog}'s title and message are plain, unselectable labels, so that text can only
 * be retyped by hand.
 */
public final class DialogUtils {

    private DialogUtils() {}

    /**
     * Show a dialog with its title and message selectable, so both can be long-pressed and copied.
     *
     * <p>Use in place of {@code builder.show()}.</p>
     *
     * @return the shown dialog
     */
    public static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        // The title and message views only exist once the dialog has inflated its layout.
        dialog.setOnShowListener(d -> makeTextSelectable(dialog));
        dialog.show();
        return dialog;
    }

    /**
     * Platform-dialog counterpart of {@link #show(AlertDialog.Builder)}, for the screens still
     * building {@code android.app.AlertDialog}.
     */
    public static android.app.AlertDialog show(android.app.AlertDialog.Builder builder) {
        android.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> makeTextSelectable(dialog));
        dialog.show();
        return dialog;
    }

    /**
     * Show an already-created dialog with its title and message selectable, for the call sites
     * that build with {@code create()} rather than handing over the builder.
     *
     * @return the shown dialog
     */
    public static <T extends Dialog> T show(T dialog) {
        dialog.setOnShowListener(d -> makeTextSelectable(dialog));
        dialog.show();
        return dialog;
    }

    /**
     * Make a shown dialog's text selectable: the standard title and message, plus any text in a
     * custom content view. Safe to call on any dialog — views it does not have are skipped.
     * <p>
     * Interactive views are deliberately left alone. {@code setTextIsSelectable} turns a view
     * focusable and long-clickable and swaps in a movement method, which would swallow taps —
     * so buttons, checkboxes, editable fields and anything already clickable keep their
     * behaviour. Copying text is not worth breaking a dialog's controls for.
     */
    public static void makeTextSelectable(Dialog dialog) {
        if (dialog == null) return;
        try {
            Window window = dialog.getWindow();
            if (window != null) {
                selectAll(window.getDecorView());
            }
        } catch (Exception ignored) {
            // A dialog that is not laid out the way we expect simply stays unselectable.
        }
    }

    private static void selectAll(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                selectAll(group.getChildAt(i));
            }
            return;
        }
        if (!(view instanceof TextView)) return;
        if (view instanceof Button || view instanceof CompoundButton || view instanceof EditText) return;
        if (view.isClickable() || view.isLongClickable()) return;

        TextView text = (TextView) view;
        if (text.length() == 0) return;
        text.setTextIsSelectable(true);
    }
}
