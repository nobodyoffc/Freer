package com.fc.freer.nobody;

import android.app.Activity;
import android.text.SpannableStringBuilder;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.DialogUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The confirmation before any action that involves a nobody.
 *
 * Never a hard block: the user may mean it (funding the public request board,
 * encrypting something they intend to publish). But the consequence is said
 * plainly, and nothing proceeds until the user chooses to.
 */
public final class NobodyGuard {

    private static final String TAG = "NobodyGuard";

    private NobodyGuard() {}

    public static void confirm(Activity activity, Collection<String> fids,
                               @StringRes int consequence, Runnable proceed) {
        confirm(activity, fids, consequence, proceed, null);
    }

    /**
     * Run {@code proceed} directly when none of the FIDs is a nobody; otherwise ask
     * first. Unknown FIDs are checked on the network; if that fails, the decision
     * rests on what is already known.
     *
     * @param onCancel run when the user declines; may be null
     */
    public static void confirm(Activity activity, Collection<String> fids,
                               @StringRes int consequence, Runnable proceed,
                               @Nullable Runnable onCancel) {
        List<String> candidates = NobodyUi.nonNull(fids);
        if (candidates.isEmpty()) {
            proceed.run();
            return;
        }
        NobodyRegistry registry = NobodyRegistry.get();
        if (registry.unknownOf(candidates).isEmpty()) {
            decide(activity, candidates, consequence, proceed, onCancel);
            return;
        }
        WaitingDialog waiting = new WaitingDialog(activity, activity.getString(R.string.nobody_checking));
        DialogUtils.show(waiting);
        NobodyUi.resolveAsync(candidates, true, ok -> {
            if (waiting.isShowing()) {
                try {
                    waiting.dismiss();
                } catch (Exception ignored) {
                }
            }
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (!ok) TimberLogger.w(TAG, "Nobody check incomplete; deciding from known nobodies");
            decide(activity, candidates, consequence, proceed, onCancel);
        });
    }

    /** Same as {@link #confirm} for pubkeys; anything that isn't a pubkey is ignored. */
    public static void confirmPubkeys(Activity activity, Collection<String> pubkeys,
                                      @StringRes int consequence, Runnable proceed,
                                      @Nullable Runnable onCancel) {
        List<String> fids = new ArrayList<>();
        if (pubkeys != null) {
            for (String pubkey : pubkeys) {
                String fid = NobodyRegistry.fidOfPubkey(pubkey);
                if (fid != null) fids.add(fid);
            }
        }
        confirm(activity, fids, consequence, proceed, onCancel);
    }

    private static void decide(Activity activity, List<String> candidates,
                               @StringRes int consequence, Runnable proceed,
                               @Nullable Runnable onCancel) {
        List<String> nobodies = NobodyRegistry.get().nobodiesOf(candidates);
        if (nobodies.isEmpty()) {
            proceed.run();
            return;
        }
        final boolean[] proceeded = {false};
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(R.string.nobody_warning_title)
                .setMessage(message(activity, nobodies, consequence))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.proceed_anyway, (dialog, which) -> {
                    proceeded[0] = true;
                    proceed.run();
                })
                .setOnDismissListener(dialog -> {
                    if (!proceeded[0] && onCancel != null) onCancel.run();
                });
        DialogUtils.show(builder);
    }

    /**
     * Tell the user, once per FID, that one of their own keys is a nobody. Safe to
     * call from any thread; it does nothing once the alert has been shown.
     */
    public static void alertOwnKeyIfNeeded(@Nullable Activity activity, String fid) {
        if (activity == null || fid == null || !NobodyRegistry.get().isNobody(fid)) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (!NobodyRegistry.get().claimOwnKeyAlert(fid)) return;
            DialogUtils.show(new AlertDialog.Builder(activity)
                    .setTitle(R.string.nobody_own_key_alert_title)
                    .setMessage(activity.getString(R.string.nobody_own_key_alert_message, fid))
                    .setPositiveButton(R.string.ok, null));
        });
    }

    private static CharSequence message(Activity activity, List<String> nobodies, @StringRes int consequence) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        text.append(activity.getString(R.string.nobody_list_intro)).append('\n');
        CidFidManager cidFidManager = CidFidManager.getInstance();
        for (String fid : nobodies) {
            String cid = cidFidManager != null ? cidFidManager.getCidByFid(fid) : null;
            text.append("• ");
            if (cid != null && !cid.isEmpty()) text.append(cid).append("  ");
            text.append(NobodyUi.shortFid(fid)).append('\n');
        }
        text.append('\n').append(activity.getString(consequence));
        return text;
    }
}
