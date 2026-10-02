package com.fc.freer.onboarding;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.contact.CreateContactActivity;
import com.fc.freer.home.SetCidActivity;
import com.fc.freer.im.ChannelSetupDialog;
import com.fc.freer.im.ImManager;
import com.fc.freer.im.JoinSquareActivity;
import com.fc.freer.im.handler.SquareHandler;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.ui.BackupPrikeyDialog;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.TopupPromptDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;

import java.util.EnumSet;
import java.util.Set;

/**
 * The getting-started checklist at the top of Home.
 * <p>
 * It replaces prompts that each knew one thing (a backup dialog on every launch, a top-up
 * dialog, a CID screen and a server-setup dialog that opened by themselves) with one list of
 * what an identity needs: back up the prikey, get a first FCH, register a CID, set a DOCK and
 * DISK, add the guide, join a square. The decision is {@link Onboarding}; this class only gathers
 * the facts and draws them.
 * <p>
 * Only the step being worked on is expanded; tapping another row opens that one instead.
 */
public final class GettingStartedCard {
    private static final String TAG = "GettingStartedCard";

    /** How often a visible card may ask for the live FID's record again. */
    private static final long CHAIN_REFRESH_INTERVAL_MS = 60_000L;

    private final Activity activity;
    private final View card;
    private final TextView progressText;
    private final ProgressBar progress;
    private final View scroll;
    private final LinearLayout rows;
    private final Runnable requestChainRefresh;

    /** A row the user opened by hand. Null means "the current step". */
    private OnboardingStep expanded;
    private long lastChainRefreshAt;
    /** Bumped per refresh, so a slow gather cannot draw over a newer one. */
    private int generation;
    /** Whether the host is on screen; only then does a pending carve get re-checked by itself. */
    private boolean active;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = this::refresh;

    /**
     * @param root                the included {@code layout_getting_started_card}
     * @param requestChainRefresh asks the host to re-read the live FID's record; the host calls
     *                            {@link #refresh()} again when it lands
     */
    public GettingStartedCard(Activity activity, View root, Runnable requestChainRefresh) {
        this.activity = activity;
        this.card = root;
        this.progressText = root.findViewById(R.id.gettingStartedProgressText);
        this.progress = root.findViewById(R.id.gettingStartedProgress);
        this.scroll = root.findViewById(R.id.gettingStartedScroll);
        this.rows = root.findViewById(R.id.gettingStartedRows);
        this.requestChainRefresh = requestChainRefresh;
    }

    /** What the checklist is drawn from, read off the UI thread. */
    private static final class Snapshot {
        boolean canShow;
        String liveFid;
        OnboardingFacts facts;
        boolean started;
    }

    /**
     * Call from the host's onResume (true) and onPause (false). While active, a card with a carve
     * waiting for the chain re-checks every {@link #CHAIN_REFRESH_INTERVAL_MS}, so the step ticks
     * without leaving the screen.
     */
    public void setActive(boolean active) {
        this.active = active;
        if (!active) handler.removeCallbacks(tick);
    }

    /** Re-read everything and redraw. Safe to call often. */
    public void refresh() {
        final int gen = ++generation;
        new Thread(() -> {
            Snapshot snapshot;
            try {
                snapshot = gather();
            } catch (Exception e) {
                TimberLogger.e(TAG, "Could not gather checklist facts: %s", e.getMessage());
                return;
            }
            activity.runOnUiThread(() -> {
                if (gen != generation || activity.isFinishing() || activity.isDestroyed()) return;
                render(snapshot);
            });
        }).start();
    }

    private Snapshot gather() {
        Snapshot snapshot = new Snapshot();
        FidManager fidManager = FidManager.getInstance();
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (fidManager == null || setting == null) return snapshot;

        String liveFid = fidManager.getLiveFid();
        KeyInfo liveKeyInfo = fidManager.getLiveKeyInfo();
        // The flags live in the main identity's Setting, and a watch-only or multisig FID cannot
        // back up, carve or sign anything, so every step would be a button that fails.
        boolean canSign = liveFid != null && liveFid.equals(fidManager.getMainFid())
                && !fidManager.isLiveFidMultisig()
                && liveKeyInfo != null && liveKeyInfo.getPrikeyCipher() != null
                && !liveKeyInfo.getPrikeyCipher().isEmpty();
        // Finished stays finished, and none of the lookups below are worth making for it.
        if (!canSign || setting.isOnboardingCompleted()) return snapshot;

        snapshot.canShow = true;
        snapshot.liveFid = liveFid;
        snapshot.started = setting.isOnboardingStarted();

        LiveFidRecord record = LiveFidRecord.confirmed(liveFid);
        OnboardingFacts facts = new OnboardingFacts(setting.isPrikeyBackedUp(), record);
        facts.skipped.addAll(setting.getOnboardingSkipped());

        String guide = record != null ? record.guide : null;
        if (guide != null && !guide.isEmpty()) {
            ContactManager contacts = fidManager.getContactManager();
            try {
                facts.guideIsContact = contacts != null && contacts.getContactByFid(guide) != null;
            } catch (Exception e) {
                TimberLogger.w(TAG, "Contact lookup failed: %s", e.getMessage());
            }
        }

        long now = System.currentTimeMillis();
        Long askedAt = FirstFchAsks.askedAt(activity, liveFid);
        if (askedAt != null) {
            facts.pending.put(OnboardingStep.FIRST_FCH,
                    new OnboardingFacts.Pending(null, now - askedAt >= PendingIdentityCarve.OVERDUE_MS));
        }
        PendingIdentityCarves carves = PendingIdentityCarves.of(activity);
        PendingIdentityCarve cid = carves.get(liveFid, PendingIdentityCarve.Kind.CID);
        if (cid != null) {
            facts.pending.put(OnboardingStep.REGISTER_CID, new OnboardingFacts.Pending(cid.txid, cid.isOverdue(now)));
        }
        PendingIdentityCarve home = carves.get(liveFid, PendingIdentityCarve.Kind.HOME);
        if (home != null) {
            facts.pending.put(OnboardingStep.SET_HOME, new OnboardingFacts.Pending(home.txid, home.isOverdue(now)));
        }
        // A master carve is a backup on its way: the step says so while it confirms, and ticks
        // itself off the chain record once the block lands.
        PendingIdentityCarve master = carves.get(liveFid, PendingIdentityCarve.Kind.MASTER);
        if (master != null) {
            facts.pending.put(OnboardingStep.BACKUP_PRIKEY,
                    new OnboardingFacts.Pending(master.txid, master.isOverdue(now)));
        }

        ImManager im = setting.getImManager();
        SquareHandler squares = im != null ? im.getSquareHandler() : null;
        if (squares != null) {
            facts.joinedSquare = squares.hasJoinedOnChain(liveFid);
            // Creating a square makes you its first member, so a create counts as much as a
            // join. A fresh one outranks a stalled one: that is the attempt still in flight.
            Square chosen = null;
            for (Square square : squares.getPendingSquares()) {
                boolean fresh = now - SquareHandler.pendingBroadcastAt(square) < PendingIdentityCarve.OVERDUE_MS;
                if (chosen == null || (fresh && now - SquareHandler.pendingBroadcastAt(chosen)
                        >= PendingIdentityCarve.OVERDUE_MS)) {
                    chosen = square;
                }
            }
            if (chosen != null) {
                boolean overdue = now - SquareHandler.pendingBroadcastAt(chosen) >= PendingIdentityCarve.OVERDUE_MS;
                facts.pending.put(OnboardingStep.JOIN_SQUARE,
                        new OnboardingFacts.Pending(SquareHandler.pendingTxid(chosen), overdue));
            }
        }
        snapshot.facts = facts;
        return snapshot;
    }

    private void render(Snapshot snapshot) {
        if (!snapshot.canShow) {
            handler.removeCallbacks(tick);
            card.setVisibility(View.GONE);
            return;
        }
        Onboarding ob = new Onboarding(snapshot.facts);
        // Every step settled, which needs the chain to have answered: launch's unknowns never get
        // here. The next refresh stops at gather().
        if (ob.isComplete()) markCompleted();
        boolean started = snapshot.started;
        if (ob.hasRequiredStepOpen() && !started) {
            markStarted();
            started = true;
        }
        boolean visible = ob.shouldShow(started);
        maybeRefreshSources(ob, snapshot.facts.chain, visible, snapshot.liveFid);
        scheduleTick(ob, visible);
        if (!visible) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);

        int total = ob.getItems().size();
        int settled = ob.settledCount();
        progressText.setText(activity.getString(R.string.getting_started_progress, settled, total));
        progress.setMax(Math.max(total, 1));
        progress.setProgress(settled);

        Onboarding.Item current = ob.current();
        OnboardingStep open = expanded != null && ob.statusOf(expanded) != null ? expanded
                : current != null ? current.step : null;

        rows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (Onboarding.Item item : ob.getItems()) {
            rows.addView(buildRow(inflater, item, item.step == open, ob, snapshot.liveFid));
        }
        capHeight();
    }

    /**
     * Re-read what the ticks come from, at most once per {@link #CHAIN_REFRESH_INTERVAL_MS}.
     * <p>
     * The live FID's record is asked for while the chain has not answered, or while the card is
     * up with any step past the backup still to do: coins, a CID or a home can arrive from
     * anywhere, not only from a carve made here. The stored squares only change on login or in
     * the Square screen, so they are refreshed the same way while the square step is open.
     */
    private void maybeRefreshSources(Onboarding ob, LiveFidRecord chain, boolean visible, String liveFid) {
        boolean readChain = chain == null;
        boolean readSquares = false;
        if (visible) {
            for (Onboarding.Item item : ob.getItems()) {
                if (item.status.isSettled() || item.status.kind == OnboardingStatus.Kind.UNKNOWN) continue;
                // The backup is the one step the chain cannot usually answer — unless a
                // master carve is on its way, which is exactly what the record will show.
                if (item.step != OnboardingStep.BACKUP_PRIKEY
                        || item.status.kind == OnboardingStatus.Kind.PENDING
                        || item.status.kind == OnboardingStatus.Kind.STALLED) readChain = true;
                if (item.step == OnboardingStep.JOIN_SQUARE) readSquares = true;
            }
        }
        long now = System.currentTimeMillis();
        if ((!readChain && !readSquares) || now - lastChainRefreshAt < CHAIN_REFRESH_INTERVAL_MS) return;
        lastChainRefreshAt = now;
        if (readChain && requestChainRefresh != null) requestChainRefresh.run();
        if (readSquares) refreshSquares(liveFid);
    }

    /** The same membership refresh messaging runs at login, then a redraw. */
    private void refreshSquares(String liveFid) {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        ImManager im = setting != null ? setting.getImManager() : null;
        SquareHandler squares = im != null ? im.getSquareHandler() : null;
        FapiClient client = im != null ? im.getFapiClient() : null;
        if (squares == null || client == null || liveFid == null) return;
        new Thread(() -> {
            try {
                long height = setting.getLastGroupUpdateHeight();
                long newHeight = squares.refreshUpdatedSquares(client, liveFid, height);
                if (newHeight > height) setting.setLastGroupUpdateHeight(newHeight);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Square refresh failed: %s", e.getMessage());
                return;
            }
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) refresh();
            });
        }).start();
    }

    /** While on screen with a carve pending, come back in a minute to see whether it landed. */
    private void scheduleTick(Onboarding ob, boolean visible) {
        handler.removeCallbacks(tick);
        if (!active || !visible) return;
        for (Onboarding.Item item : ob.getItems()) {
            if (item.status.kind == OnboardingStatus.Kind.PENDING) {
                handler.postDelayed(tick, CHAIN_REFRESH_INTERVAL_MS);
                return;
            }
        }
    }

    /** Keep the icon grid usable on short screens: the rows scroll past 40% of the window. */
    private void capHeight() {
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        scroll.setLayoutParams(lp);
        scroll.post(() -> {
            int max = (int) (activity.getWindow().getDecorView().getHeight() * 0.4f);
            if (max > 0 && scroll.getHeight() > max) {
                ViewGroup.LayoutParams capped = scroll.getLayoutParams();
                capped.height = max;
                scroll.setLayoutParams(capped);
            }
        });
    }

    private View buildRow(LayoutInflater inflater, Onboarding.Item item, boolean isOpen,
                          Onboarding ob, String liveFid) {
        View row = inflater.inflate(R.layout.item_getting_started_step, rows, false);
        TextView icon = row.findViewById(R.id.stepIcon);
        TextView title = row.findViewById(R.id.stepTitle);
        TextView statusView = row.findViewById(R.id.stepStatus);
        View detail = row.findViewById(R.id.stepDetail);
        OnboardingStatus status = item.status;

        setIcon(icon, status);
        title.setText(titleOf(item.step));
        title.setTypeface(null, isOpen ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        title.setTextColor(activity.getColor(status.isSettled() ? R.color.hint : R.color.text));
        if (status.kind == OnboardingStatus.Kind.SKIPPED) {
            title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        }
        String line = statusLine(item, ob);
        statusView.setText(!isOpen && line != null ? line : "");

        row.findViewById(R.id.stepHeader).setOnClickListener(v -> {
            expanded = isOpen ? null : item.step;
            refresh();
        });

        if (!isOpen) return row;
        detail.setVisibility(View.VISIBLE);
        ((TextView) row.findViewById(R.id.stepExplanation)).setText(explanationOf(item.step));

        TextView note = row.findViewById(R.id.stepNote);
        TextView copyable = row.findViewById(R.id.stepCopyable);
        switch (status.kind) {
            case WAITING_STEP:
            case WAITING_COIN_DAYS:
                note.setVisibility(View.VISIBLE);
                note.setText(statusLine(status));
                break;
            case PENDING:
                note.setVisibility(View.VISIBLE);
                note.setTextColor(activity.getColor(R.color.hint));
                if (status.txid == null) {
                    note.setText(R.string.gs_note_fch_asked);
                } else {
                    note.setText(item.step == OnboardingStep.BACKUP_PRIKEY
                            ? R.string.gs_note_master_pending : R.string.gs_note_pending);
                    showCopyable(copyable, activity.getString(R.string.gs_txid, shortId(status.txid, 8, 8)), status.txid);
                }
                break;
            case STALLED:
                note.setVisibility(View.VISIBLE);
                note.setText(R.string.gs_note_stalled);
                showCopyable(copyable, activity.getString(R.string.gs_txid, shortId(status.txid, 8, 8)), status.txid);
                break;
            default:
                break;
        }

        // A master ticked this step, so say whose key opens that copy — the FID itself,
        // copyable, because "recoverable" means nothing without knowing by whom.
        if (item.step == OnboardingStep.BACKUP_PRIKEY && ob.getBackupMaster() != null
                && status.kind == OnboardingStatus.Kind.DONE) {
            note.setVisibility(View.VISIBLE);
            note.setTextColor(activity.getColor(R.color.hint));
            note.setText(R.string.gs_note_backed_up_to_master);
            showCopyable(copyable, activity.getString(R.string.gs_master_fid,
                    shortId(ob.getBackupMaster(), 4, 4)), ob.getBackupMaster());
        }

        // Asking again is free, so an ask waiting on coins keeps its way back to the FID and
        // board; and a copy of your own is free too, so a master carve still confirming must
        // not take the backup button away — it is the one thing that works if that carve fails.
        if (status.isActionable() || item.step == OnboardingStep.FIRST_FCH
                || (item.step == OnboardingStep.BACKUP_PRIKEY && !status.isSettled())) {
            addActions(inflater, row.findViewById(R.id.stepActions), copyable, item, ob, liveFid);
        }
        return row;
    }

    private void addActions(LayoutInflater inflater, LinearLayout actions, TextView copyable,
                            Onboarding.Item item, Onboarding ob, String liveFid) {
        boolean waiting = item.status.isWaiting();
        switch (item.step) {
            case BACKUP_PRIKEY:
                addButton(inflater, actions, R.string.gs_action_backup, this::openBackup);
                break;
            case FIRST_FCH:
                showCopyable(copyable, activity.getString(R.string.gs_your_fid, shortId(liveFid, 4, 4)), liveFid,
                        () -> onFirstFchAsked(liveFid));
                addButton(inflater, actions, R.string.gs_action_get_fch, () -> new TopupPromptDialog(activity, liveFid, null)
                        .setOnAskedListener(() -> onFirstFchAsked(liveFid))
                        .show());
                break;
            case REGISTER_CID:
                // While the coins are still aging the note says why; a button into a form that
                // cannot carve would not.
                if (!waiting) {
                    addButton(inflater, actions, R.string.gs_action_register_cid,
                            () -> activity.startActivity(new Intent(activity, SetCidActivity.class)));
                }
                break;
            case SET_HOME:
                if (!waiting) {
                    addButton(inflater, actions, R.string.gs_action_set_home,
                            () -> ChannelSetupDialog.show(activity, null, this::refresh));
                }
                break;
            case ADD_GUIDE: {
                String guide = ob.getGuide();
                if (guide != null) {
                    showCopyable(copyable, activity.getString(R.string.gs_guide_fid, shortId(guide, 4, 4)), guide);
                    addButton(inflater, actions, R.string.gs_action_add_contact, () -> {
                        Intent intent = new Intent(activity, CreateContactActivity.class);
                        intent.putExtra(CreateContactActivity.EXTRA_FID, guide);
                        activity.startActivity(intent);
                    });
                    addButton(inflater, actions, R.string.gs_action_guide_details, () -> openFidDetails(guide));
                }
                break;
            }
            case JOIN_SQUARE:
                // Browsing is free; only the join itself is a carve, and TxSender checks its CD.
                addButton(inflater, actions, R.string.gs_action_browse_squares,
                        () -> activity.startActivity(new Intent(activity, JoinSquareActivity.class)));
                break;
        }
        if (item.step.isSkippable()) {
            Button skip = (Button) inflater.inflate(R.layout.item_getting_started_skip, actions, false);
            skip.setOnClickListener(v -> skip(item.step));
            actions.addView(skip);
        }
    }

    private void addButton(LayoutInflater inflater, LinearLayout actions, int textRes, Runnable onClick) {
        Button button = (Button) inflater.inflate(R.layout.item_getting_started_action, actions, false);
        button.setText(textRes);
        button.setOnClickListener(v -> onClick.run());
        actions.addView(button);
    }

    private void showCopyable(TextView view, String display, String value) {
        showCopyable(view, display, value, null);
    }

    private void showCopyable(TextView view, String display, String value, Runnable afterCopy) {
        view.setVisibility(View.VISIBLE);
        view.setText(display);
        view.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("id", value));
            ToastUtils.makeText(activity, R.string.copied);
            if (afterCopy != null) afterCopy.run();
        });
    }

    /** Asked on the board or copied the FID to hand out: the step waits for coins from here. */
    private void onFirstFchAsked(String liveFid) {
        FirstFchAsks.record(activity, liveFid);
        if (!activity.isFinishing() && !activity.isDestroyed()) refresh();
    }

    // ---- actions ----

    private void openBackup() {
        new BackupPrikeyDialog(activity, new BackupPrikeyDialog.BackupPrikeyListener() {
            @Override
            public void onDone() {
                Setting setting = SettingManager.getInstance().getCurrentSetting();
                if (setting != null) {
                    setting.setPrikeyBackedUp(true);
                    SettingManager.getInstance().saveSettings(activity, setting);
                    ToastUtils.makeText(activity, R.string.prikey_backup_completed);
                }
                refresh();
            }

            @Override
            public void onLater() {
            }
        }).show();
    }

    private void openFidDetails(String fid) {
        FapiClient client = (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7);
        new Thread(() -> {
            Freer freer = null;
            try {
                if (client != null) freer = client.getFreer(fid);
            } catch (Exception e) {
                TimberLogger.w(TAG, "Could not fetch %s: %s", fid, e.getMessage());
            }
            final Freer found = freer;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (found == null) {
                    ToastUtils.makeText(activity, R.string.fid_not_found);
                    return;
                }
                Intent intent = new Intent(activity, DetailActivity.class);
                intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, found.toJson());
                intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Freer.class.getName());
                activity.startActivity(intent);
            });
        }).start();
    }

    private void skip(OnboardingStep step) {
        if (!step.isSkippable()) return;
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null) return;
        Set<OnboardingStep> skipped = EnumSet.noneOf(OnboardingStep.class);
        skipped.addAll(setting.getOnboardingSkipped());
        if (!skipped.add(step)) return;
        setting.setOnboardingSkipped(skipped);
        SettingManager.getInstance().saveSettings(activity, setting);
        if (expanded == step) expanded = null;
        refresh();
    }

    private void markCompleted() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.isOnboardingCompleted()) return;
        setting.setOnboardingCompleted(true);
        SettingManager.getInstance().saveSettings(activity, setting);
    }

    private void markStarted() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.isOnboardingStarted()) return;
        setting.setOnboardingStarted(true);
        SettingManager.getInstance().saveSettings(activity, setting);
    }

    // ---- words ----

    private void setIcon(TextView icon, OnboardingStatus status) {
        String glyph;
        int color;
        switch (status.kind) {
            case DONE: glyph = "✓"; color = R.color.accent; break;
            case SKIPPED: glyph = "–"; color = R.color.hint; break;
            case OPEN: glyph = "○"; color = R.color.accent; break;
            case WAITING_STEP:
            case WAITING_COIN_DAYS: glyph = "⧗"; color = R.color.warning; break;
            case STALLED: glyph = "!"; color = R.color.warning; break;
            case PENDING:
            case UNKNOWN:
            default: glyph = "…"; color = R.color.hint; break;
        }
        icon.setText(glyph);
        icon.setTextColor(activity.getColor(color));
    }

    /**
     * The trailing line on a collapsed row. A backup ticked by a master says so: a bare tick
     * would claim the user has a copy, when what exists is a copy only the master can open.
     */
    private String statusLine(Onboarding.Item item, Onboarding ob) {
        if (item.step == OnboardingStep.BACKUP_PRIKEY && ob.getBackupMaster() != null
                && item.status.kind == OnboardingStatus.Kind.DONE) {
            return activity.getString(R.string.gs_status_backed_up_to_master,
                    shortId(ob.getBackupMaster(), 4, 4));
        }
        return statusLine(item.status);
    }

    private String statusLine(OnboardingStatus status) {
        switch (status.kind) {
            case SKIPPED: return activity.getString(R.string.gs_status_skipped);
            case UNKNOWN: return activity.getString(R.string.gs_status_checking);
            case PENDING:
                return activity.getString(status.txid == null ? R.string.gs_status_fch_asked : R.string.gs_status_pending);
            case STALLED: return activity.getString(R.string.gs_status_stalled);
            case WAITING_STEP:
                return activity.getString(R.string.gs_status_after, activity.getString(titleOf(status.waitStep)));
            case WAITING_COIN_DAYS:
                if (status.days == null) {
                    return activity.getString(R.string.gs_status_coin_days, status.have, status.need);
                }
                if (status.days == 1) {
                    return activity.getString(R.string.gs_status_coin_days_one_day, status.have, status.need);
                }
                return activity.getString(R.string.gs_status_coin_days_days, status.have, status.need, status.days);
            default:
                return null;
        }
    }

    static int titleOf(OnboardingStep step) {
        switch (step) {
            case BACKUP_PRIKEY: return R.string.gs_step_backup_prikey;
            case FIRST_FCH: return R.string.gs_step_first_fch;
            case REGISTER_CID: return R.string.gs_step_register_cid;
            case SET_HOME: return R.string.gs_step_set_home;
            case ADD_GUIDE: return R.string.gs_step_add_guide;
            case JOIN_SQUARE:
            default: return R.string.gs_step_join_square;
        }
    }

    static int explanationOf(OnboardingStep step) {
        switch (step) {
            case BACKUP_PRIKEY: return R.string.gs_explain_backup_prikey;
            case FIRST_FCH: return R.string.gs_explain_first_fch;
            case REGISTER_CID: return R.string.gs_explain_register_cid;
            case SET_HOME: return R.string.gs_explain_set_home;
            case ADD_GUIDE: return R.string.gs_explain_add_guide;
            case JOIN_SQUARE:
            default: return R.string.gs_explain_join_square;
        }
    }

    /** A shortened ID that keeps head and tail, e.g. {@code FEk4…vkUV}. */
    static String shortId(String id, int head, int tail) {
        if (id == null) return "";
        if (id.length() <= head + tail + 1) return id;
        return id.substring(0, head) + "…" + id.substring(id.length() - tail);
    }
}
