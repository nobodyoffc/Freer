package com.fc.freer.contact;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.fc.freer.R;
import com.fc.freer.manager.ContactManager;

/**
 * The label the user gave a FID in Contacts, shown next to its CID/FID.
 *
 * The label never replaces the CID/FID: a CID is chosen by its owner, so an
 * impostor could pick one that reads like a friend's. Showing both tells the
 * user who the FID claims to be and who they know it as.
 *
 * Three forms:
 * <ul>
 *   <li>{@link #bindLine} — its own muted line under the name (list rows, toolbars)</li>
 *   <li>{@link #inline} — appended, smaller and muted (message senders, tight rows)</li>
 *   <li>{@link #paren} — plain "name (label)" for sentences and notifications</li>
 * </ul>
 * Combine with {@code NobodyUi.setName(view, fid, ContactLabel.inline(...))};
 * never read a decorated TextView's text back as a CID or FID.
 */
public final class ContactLabel {

    private static final String SEPARATOR = "  ";

    private ContactLabel() {}

    /** The contact label of this FID, or null when it has none. */
    @Nullable
    public static String of(@Nullable String fid) {
        ContactManager manager = ContactManager.getInstance();
        if (manager == null || fid == null) return null;
        try {
            return manager.getLabel(fid);
        } catch (Exception e) {
            return null;
        }
    }

    /** Show the label in {@code view}, or hide the view when the FID has none. */
    public static void bindLine(@Nullable TextView view, @Nullable String fid) {
        if (view == null) return;
        String label = of(fid);
        if (label == null) {
            view.setText(null);
            view.setVisibility(View.GONE);
        } else {
            view.setText(label);
            view.setVisibility(View.VISIBLE);
        }
    }

    /** {@code name} followed by the label, smaller and muted; {@code name} unchanged without one. */
    public static CharSequence inline(@NonNull Context context, @Nullable String fid,
                                      @Nullable CharSequence name) {
        String label = of(fid);
        if (label == null) return name;
        SpannableStringBuilder builder = new SpannableStringBuilder();
        if (name != null) builder.append(name);
        int start = builder.length();
        builder.append(SEPARATOR).append(label);
        builder.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.hint)),
                start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new RelativeSizeSpan(0.85f),
                start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    /** "name (label)", or {@code name} without one. */
    public static String paren(@Nullable String fid, @Nullable String name) {
        String label = of(fid);
        if (label == null) return name;
        return (name == null ? "" : name) + " (" + label + ")";
    }

    /**
     * Keep {@code labelView} showing the label of whatever FID is typed into
     * {@code input}, e.g. under a recipient field.
     */
    public static void watch(@Nullable android.widget.EditText input, @Nullable TextView labelView) {
        if (input == null || labelView == null) return;
        Runnable update = () -> bindLine(labelView,
                input.getText() == null ? null : input.getText().toString().trim());
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { update.run(); }
        });
        update.run();
    }

    /**
     * Run {@code onChange} on the main thread whenever a contact is added,
     * edited or removed, until the owner is destroyed.
     */
    public static void observe(@NonNull LifecycleOwner owner, @NonNull Runnable onChange) {
        Runnable listener = () -> {
            if (owner.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.CREATED)) {
                onChange.run();
            }
        };
        ContactManager.addLabelListener(listener);
        owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(@NonNull LifecycleOwner source) {
                ContactManager.removeLabelListener(listener);
            }
        });
    }
}
