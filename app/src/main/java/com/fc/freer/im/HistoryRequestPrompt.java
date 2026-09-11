package com.fc.freer.im;

import android.app.Activity;
import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.freer.R;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The user-facing half of an incoming history request: what is asked for, how much it would hand
 * over, and a confirmation that says plainly what sharing means before anything is sent. Used by
 * the chat the request concerns and by the pending-issue screens.
 */
public final class HistoryRequestPrompt {

    private HistoryRequestPrompt() {}

    /**
     * Count the messages off the main thread, then ask: Share… / Decline / Later.
     * {@code onDecided} runs after a successful share or a decline; Later leaves the request open.
     */
    public static void show(Activity activity, ImManager imManager, PendingIssue issue,
                            Runnable onDecided) {
        PendingIssue.HistoryRequestData data = dataOf(issue);
        if (activity == null || imManager == null || data == null) return;

        new Thread(() -> {
            int count = imManager.countHistoryRequestMessages(issue.getId());
            activity.runOnUiThread(() -> {
                if (count < 0 || activity.isFinishing() || activity.isDestroyed()) return;
                AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                        .setTitle(R.string.request_history)
                        .setMessage(title(activity, imManager.getLiveFid(), issue) + "\n\n"
                                + detail(activity, data, count))
                        .setNegativeButton(R.string.decline, (d, w) -> {
                            imManager.declineHistoryRequest(issue.getId());
                            ToastUtils.showInfo(activity, R.string.history_request_declined);
                            if (onDecided != null) onDecided.run();
                        })
                        .setNeutralButton(R.string.later, null);
                // Nothing to hand over: sharing would upload an empty file, so only decline or wait.
                if (count > 0) {
                    builder.setPositiveButton(R.string.history_share_action,
                            (d, w) -> confirmShare(activity, imManager, issue, count, onDecided));
                }
                DialogUtils.show(builder);
            });
        }).start();
    }

    /**
     * Say what sharing hands over — the part people miss is that a group transcript is mostly
     * other people's words — and share only once the user agrees.
     */
    public static void confirmShare(Activity activity, ImManager imManager, PendingIssue issue,
                                    int count, Runnable onDecided) {
        PendingIssue.HistoryRequestData data = dataOf(issue);
        if (activity == null || imManager == null || data == null) return;

        int messageRes = data.imType == ImType.P2P
                ? R.string.history_share_confirm_p2p
                : R.string.history_share_confirm_group;
        String message = activity.getString(messageRes, count,
                formatRange(data.since, data.before), data.requesterFid);

        DialogUtils.show(new AlertDialog.Builder(activity)
                .setTitle(R.string.history_share_title)
                .setMessage(message)
                .setPositiveButton(R.string.history_share_confirm_action, (d, w) -> {
                    Context app = activity.getApplicationContext();
                    ToastUtils.showInfo(app, R.string.history_sharing);
                    imManager.approveHistoryRequest(issue.getId(), (shared, error) -> {
                        if (error != null) {
                            ToastUtils.showWarning(app, error);
                            return;
                        }
                        ToastUtils.showInfo(app,
                                app.getString(R.string.history_shared, shared, data.requesterFid));
                        if (onDecided != null) onDecided.run();
                    });
                })
                .setNegativeButton(R.string.cancel, null));
    }

    /**
     * History shared with us could not be downloaded: Retry / Dismiss / Later.
     * {@code onDecided} runs after a successful retry or a dismiss.
     */
    public static void showImportFailure(Activity activity, ImManager imManager, PendingIssue issue,
                                         Runnable onDecided) {
        PendingIssue.HistoryImportFailedData data = importFailureOf(issue);
        if (activity == null || imManager == null || data == null) return;

        DialogUtils.show(new AlertDialog.Builder(activity)
                .setTitle(R.string.history_import_failed_issue)
                .setMessage(importFailureMessage(activity, data))
                .setPositiveButton(R.string.retry, (d, w) -> retryImport(activity, imManager, issue, onDecided))
                .setNegativeButton(R.string.dismiss, (d, w) -> dismissImport(activity, imManager, issue, onDecided))
                .setNeutralButton(R.string.later, null));
    }

    public static void retryImport(Activity activity, ImManager imManager, PendingIssue issue,
                                   Runnable onDecided) {
        PendingIssue.HistoryImportFailedData data = importFailureOf(issue);
        if (activity == null || imManager == null || data == null) return;
        Context app = activity.getApplicationContext();
        ToastUtils.showInfo(app, R.string.history_import_retrying);
        imManager.retryHistoryImport(data.nonce, (count, error) -> {
            if (error != null) {
                ToastUtils.showWarning(app, error);
                return;
            }
            ToastUtils.showInfo(app, app.getString(R.string.history_imported, count, data.senderFid));
            if (onDecided != null) onDecided.run();
        });
    }

    public static void dismissImport(Activity activity, ImManager imManager, PendingIssue issue,
                                     Runnable onDecided) {
        PendingIssue.HistoryImportFailedData data = importFailureOf(issue);
        if (activity == null || imManager == null || data == null) return;
        imManager.dismissHistoryImport(data.nonce);
        ToastUtils.showInfo(activity, R.string.history_import_dismissed);
        if (onDecided != null) onDecided.run();
    }

    /** "History from Alice (2024-01-01 – 2024-02-01) could not be added: <reason>". */
    public static String importFailureMessage(Context context, PendingIssue.HistoryImportFailedData data) {
        return context.getString(R.string.history_import_failed_message, data.senderFid,
                formatRange(data.since, data.before),
                data.lastError != null ? data.lastError : "");
    }

    public static PendingIssue.HistoryImportFailedData importFailureOf(PendingIssue issue) {
        if (issue == null || issue.getIssueType() != PendingIssue.IssueType.HISTORY_IMPORT_FAILED) return null;
        PendingIssue.HistoryImportFailedData data =
                issue.getDataAs(PendingIssue.HistoryImportFailedData.class);
        return data != null && data.nonce != null && data.senderFid != null ? data : null;
    }

    /** "Alice asks for your conversation with them", or the group/other-device equivalent. */
    public static String title(Context context, String liveFid, PendingIssue issue) {
        PendingIssue.HistoryRequestData data = dataOf(issue);
        if (data == null) return "";
        if (data.imType == ImType.P2P) {
            return data.requesterFid.equals(liveFid)
                    ? context.getString(R.string.history_ask_own_device, data.targetId)
                    : context.getString(R.string.history_ask_p2p, data.requesterFid);
        }
        String thread = issue.getNote() != null && !issue.getNote().isEmpty()
                ? issue.getNote() : data.targetId;
        return context.getString(R.string.history_ask_group, data.requesterFid, thread);
    }

    /** The range, and how much of it this device holds. */
    public static String detail(Context context, PendingIssue.HistoryRequestData data, int count) {
        String range = formatRange(data.since, data.before);
        return count == 0
                ? context.getString(R.string.history_ask_detail_none, range)
                : context.getString(R.string.history_ask_detail, range, count);
    }

    /** Day range, inclusive at both ends as the user picked it ({@code before} is exclusive). */
    public static String formatRange(long since, long before) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String start = since <= 0 ? "…" : format.format(new Date(since));
        String end = before == Long.MAX_VALUE ? "…" : format.format(new Date(before - 1));
        return start + " – " + end;
    }

    private static PendingIssue.HistoryRequestData dataOf(PendingIssue issue) {
        if (issue == null || issue.getIssueType() != PendingIssue.IssueType.HISTORY_REQUEST) return null;
        PendingIssue.HistoryRequestData data = issue.getDataAs(PendingIssue.HistoryRequestData.class);
        return data != null && data.imType != null && data.requesterFid != null
                && data.targetId != null ? data : null;
    }
}
