package com.fc.freer.im;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.utils.ToastUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every symkey this device has given out or taken in for one conversation -- FIMP §9.7, §7 of
 * {@code docs/SYMKEY_IDENTITY_SPEC.md}.
 *
 * <p>The leak radius comes first, then the rows newest first. The radius is deliberately not the
 * membership: a member the owner never managed to push to can read nothing, and a FID removed
 * from the group still holds every version it was given.
 */
public class SymkeyLedgerActivity extends BaseCryptoActivity {
    public static final String EXTRA_ENTITY_ID = "extra_entity_id";

    private LinearLayout listLayout;
    private TextView leakRadius;
    private String entityId;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_symkey_ledger;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.symkey_exchange_title);
    }

    @Override
    protected void initializeViews() {
        listLayout = findViewById(R.id.ledger_list_layout);
        leakRadius = findViewById(R.id.leak_radius);

        entityId = getIntent().getStringExtra(EXTRA_ENTITY_ID);
        if (entityId == null) {
            finish();
            return;
        }
        render();
    }

    @Override
    protected void setupButtons() {}

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void render() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        ImManager imManager = setting != null ? setting.getImManager() : null;
        KeyLedger ledger = imManager != null ? imManager.getKeyLedger() : null;
        if (ledger == null) {
            ToastUtils.makeText(this, getString(R.string.im_manager_init_failed));
            return;
        }

        List<KeyLedger.KeyEvent> events = ledger.forEntity(entityId);

        // The radius is over every version, because a key handed out for any of them opens
        // every message sealed under that one.
        Set<String> people = new HashSet<>();
        Set<Long> versions = new HashSet<>();
        for (KeyLedger.KeyEvent event : events) {
            if (event.getVersion() != null && event.getVersion() != 0) versions.add(event.getVersion());
        }
        for (Long version : versions) {
            people.addAll(ledger.holders(entityId, version));
        }
        leakRadius.setText(people.isEmpty()
                ? getString(R.string.symkey_leak_radius_none)
                : getString(R.string.symkey_leak_radius, people.size()));

        if (events.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.symkey_ledger_empty);
            empty.setTextSize(13);
            empty.setTextColor(getResources().getColor(R.color.hint, null));
            listLayout.addView(empty);
            return;
        }

        for (KeyLedger.KeyEvent event : events) {
            listLayout.addView(rowFor(event, versions));
        }
    }

    private LinearLayout rowFor(KeyLedger.KeyEvent event, Set<Long> among) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(24, 12, 24, 12);
        row.setBackgroundResource(R.drawable.container_outline);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 8);
        row.setLayoutParams(lp);

        TextView who = new TextView(this);
        who.setTextSize(14);
        who.setTextColor(getResources().getColor(R.color.text, null));
        String counterparty = displayName(event.getCounterparty());
        who.setText(event.getDirection() == KeyLedger.Direction.SENT
                ? getString(R.string.symkey_ledger_sent, counterparty)
                : getString(R.string.symkey_ledger_received, counterparty));
        row.addView(who);

        // The version, fixed-width and year-first so a column diffs by eye. A tap copies the
        // raw integer, which is what goes into a log or a message to another member.
        long version = event.getVersion() != null ? event.getVersion() : 0L;
        if (version != 0) {
            TextView versionView = new TextView(this);
            versionView.setTextSize(13);
            versionView.setTypeface(android.graphics.Typeface.MONOSPACE);
            versionView.setTextColor(getResources().getColor(R.color.accent, null));
            String text = SymkeyVersionText.compact(this, version);
            if (SymkeyVersionText.isLegacy(version)) {
                text = text + "  " + getString(R.string.symkey_legacy_chip);
            }
            versionView.setText(text);
            versionView.setOnClickListener(v -> copyVersion(version));
            row.addView(versionView);
        }

        TextView detail = new TextView(this);
        detail.setTextSize(12);
        detail.setTextColor(getResources().getColor(R.color.hint, null));
        StringBuilder sb = new StringBuilder(outcomeText(event.getOutcome()));
        sb.append(" · ").append(getString(event.isSolicited()
                ? R.string.symkey_ledger_solicited : R.string.symkey_ledger_unsolicited));
        long lastAt = event.getLastAt() != null ? event.getLastAt() : 0L;
        if (event.getRepeats() > 1) {
            sb.append(" · ").append(getString(R.string.symkey_ledger_repeats,
                    event.getRepeats(), relative(lastAt)));
        } else if (lastAt > 0) {
            sb.append(" · ").append(relative(lastAt));
        }
        detail.setText(sb.toString());
        row.addView(detail);

        return row;
    }

    private String relative(long atMs) {
        if (atMs <= 0) return "";
        return android.text.format.DateUtils.getRelativeTimeSpanString(
                atMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
                .toString();
    }

    private void copyVersion(long version) {
        String raw = SymkeyVersionText.raw(version);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("symkeyVersion", raw));
        ToastUtils.makeText(this, getString(R.string.symkey_version_copied, raw));
    }

    /** CID when we know one, else the FID elided in the middle. */
    private String displayName(String fid) {
        if (fid == null) return "";
        CidFidManager cidFidManager = CidFidManager.getInstance();
        String cid = cidFidManager != null ? cidFidManager.getCidByFid(fid) : null;
        if (cid != null && !cid.isEmpty()) return cid;
        if (fid.length() <= 14) return fid;
        return fid.substring(0, 6) + "…" + fid.substring(fid.length() - 6);
    }

    private String outcomeText(KeyLedger.Outcome outcome) {
        if (outcome == null) return "";
        return switch (outcome) {
            case SHARED -> getString(R.string.symkey_outcome_shared);
            case STORED -> getString(R.string.symkey_outcome_stored);
            case DUPLICATE -> getString(R.string.symkey_outcome_duplicate);
            case REFUSED -> getString(R.string.symkey_outcome_refused);
            case UNREADABLE -> getString(R.string.symkey_outcome_unreadable);
            case NOT_HELD -> getString(R.string.symkey_outcome_not_held);
            case NOT_A_MEMBER -> getString(R.string.symkey_outcome_not_a_member);
            case NO_PUBKEY -> getString(R.string.symkey_outcome_no_pubkey);
        };
    }
}
