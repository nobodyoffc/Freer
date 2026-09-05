package com.fc.freer.im;

import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.SendTxActivity;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Helper-side viewer of the first-FCH request board.
 *
 * Reads the board through {@link NewcomerBoard} — the nobody freer's DOCK
 * inbox, readable by everyone because the nobody's key is public — while the
 * helper stays logged in as their own FID. The helper checks one or more
 * requests (or All), enters an amount (default {@link #DEFAULT_SEND_AMOUNT}),
 * and taps send: one transaction is prefilled with that amount per checked FID
 * as outputs and valid cashes from the CashManager as inputs, then reviewed
 * and signed in SendTxActivity — always sent from the helper's own identity,
 * never as the nobody.
 */
public class NewcomerRequestsActivity extends BaseCryptoActivity {
    private static final String TAG = "NewcomerRequests";

    private static final int MAX_SHOWN_REQUESTS = 30;
    /** Default amount per output cash: enough for a DOCK registration TX plus a little slack. */
    private static final double DEFAULT_SEND_AMOUNT = 1.01;
    /**
     * Default number of separate output cashes per newcomer. Three lets a fresh
     * FID take 2–3 on-chain actions in parallel without waiting for a single
     * UTXO to confirm between them.
     */
    private static final int DEFAULT_SEND_COUNT = 3;
    /**
     * Safety cap on total outputs (count x selected newcomers). A single TX with
     * far more than this becomes unwieldy; we reject rather than let it fail deep
     * in TxHandler.
     */
    private static final int MAX_TOTAL_OUTPUTS = 100;

    private SwipeRefreshLayout swipeRefreshLayout;
    private RecyclerView recyclerView;
    private TextView emptyText;
    private CheckBox checkAllBox;
    private ImageButton sendButton;
    private ImageButton ignoreButton;
    private EditText amountInput;
    private EditText countInput;
    private TextView balanceText;
    private TextView newcomerCountText;
    private TextView totalText;

    private final List<NobodyBoard.Request> requests = new ArrayList<>();
    private final Set<String> selectedFids = new HashSet<>();
    /** Session cache: latest request per FID, keyed by requester FID. */
    private final Map<String, NobodyBoard.Request> cacheByFid = new LinkedHashMap<>();
    /** Session-only dismissals, so an ignored FID never re-enters the cache. */
    private final Set<String> ignoredFids = new HashSet<>();
    private RequestAdapter adapter;
    private volatile boolean loading = false;

    /** Persisted watermark (server createTime): only newer asks are fetched. */
    private long boardCursor;
    private String liveFid;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_newcomer_requests;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.newcomer_requests);
    }

    @Override
    protected void initializeViews() {
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        recyclerView = findViewById(R.id.requests_recycler_view);
        emptyText = findViewById(R.id.empty_text);
        checkAllBox = findViewById(R.id.check_all);
        sendButton = findViewById(R.id.send_button);
        ignoreButton = findViewById(R.id.ignore_button);
        amountInput = findViewById(R.id.amount_input);
        amountInput.setText(String.valueOf(DEFAULT_SEND_AMOUNT));
        countInput = findViewById(R.id.count_input);
        countInput.setText(String.valueOf(DEFAULT_SEND_COUNT));
        balanceText = findViewById(R.id.balance_text);
        newcomerCountText = findViewById(R.id.newcomer_count_text);
        totalText = findViewById(R.id.total_text);

        Setting setting = SettingManager.getInstance().getCurrentSetting();
        liveFid = setting != null ? setting.getMainFid() : null;
        boardCursor = loadCursor();

        // Total consumed = count x amount x selected newcomers; recompute on any
        // input edit (selection changes call refreshTotals directly).
        TextWatcher totalWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshTotals(); }
        };
        amountInput.addTextChangedListener(totalWatcher);
        countInput.addTextChangedListener(totalWatcher);
        showLiveBalance();

        adapter = new RequestAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        swipeRefreshLayout.setOnRefreshListener(this::loadRequests);

        // OnClickListener (not OnCheckedChangeListener) so programmatic
        // setChecked from selection updates never loops back here.
        checkAllBox.setOnClickListener(v -> {
            selectedFids.clear();
            if (checkAllBox.isChecked()) {
                for (NobodyBoard.Request request : requests) {
                    selectedFids.add(request.requesterFid);
                }
            }
            adapter.notifyDataSetChanged();
            refreshTotals();
        });
    }

    @Override
    protected void setupButtons() {
        sendButton.setOnClickListener(v -> sendToChecked());
        ignoreButton.setOnClickListener(v -> ignoreChecked());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used.
    }

    @Override
    protected void onResume() {
        super.onResume();
        showLiveBalance();
        loadRequests();
    }

    private void toggleSelection(NobodyBoard.Request request) {
        if (!selectedFids.remove(request.requesterFid)) {
            selectedFids.add(request.requesterFid);
        }
        updateCheckAllState();
    }

    private void updateCheckAllState() {
        checkAllBox.setChecked(!requests.isEmpty() && selectedFids.size() == requests.size());
        refreshTotals();
    }

    /**
     * Update the summary row: number of selected newcomers and the grand total
     * consumed (count x amount x selected). Total shows a dash while inputs are
     * empty or invalid, so a partial edit never displays a misleading figure.
     */
    private void refreshTotals() {
        newcomerCountText.setText(String.valueOf(selectedFids.size()));

        Integer count = parseCount();
        Double amount = parseAmount();
        if (count == null || amount == null || selectedFids.isEmpty()) {
            totalText.setText("—");
            return;
        }
        double total = count * amount * selectedFids.size();
        totalText.setText(FchUtils.formatSatoshiToCoin(FchUtils.coinToSatoshi(total)));
    }

    /** Show the liveFid's on-chain balance from its cached KeyInfo (no network). */
    private void showLiveBalance() {
        KeyInfo liveKeyInfo = FidManager.getInstance() != null
                ? FidManager.getInstance().getLiveKeyInfo() : null;
        Long balance = liveKeyInfo != null ? liveKeyInfo.getBalance() : null;
        balanceText.setText(balance != null
                ? FchUtils.formatSatoshiToCoin(balance) : "—");
    }

    /** Parsed positive count, or null when blank/invalid. */
    private Integer parseCount() {
        try {
            int count = Integer.parseInt(countInput.getText().toString().trim());
            return count > 0 ? count : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parsed positive amount, or null when blank/invalid. */
    private Double parseAmount() {
        try {
            double amount = Double.parseDouble(amountInput.getText().toString().trim());
            return amount > 0 ? amount : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Sending ────────────────────────────────────────────────────────

    /**
     * Build one TX for every checked request: {@code count} outputs (default
     * DEFAULT_SEND_COUNT) of the entered amount (default DEFAULT_SEND_AMOUNT)
     * per receiver, valid cashes from the CashManager as inputs, then hand it to
     * SendTxActivity for review and signing.
     */
    private void sendToChecked() {
        final List<String> receivers = new ArrayList<>();
        for (NobodyBoard.Request request : requests) {
            if (selectedFids.contains(request.requesterFid)) {
                receivers.add(request.requesterFid);
            }
        }
        if (receivers.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.newcomer_requests_none_selected));
            return;
        }

        final Double amountPerOutput = parseAmount();
        if (amountPerOutput == null) {
            ToastUtils.makeText(this, getString(R.string.amount_must_be_greater_than_zero));
            return;
        }

        final Integer countPerReceiver = parseCount();
        if (countPerReceiver == null) {
            ToastUtils.makeText(this, getString(R.string.invalid_amount));
            return;
        }

        // Each receiver gets `count` output cashes, so the TX carries
        // count x receivers outputs (plus change). Guard the total up front.
        final int totalOutputs = countPerReceiver * receivers.size();
        if (totalOutputs > MAX_TOTAL_OUTPUTS) {
            ToastUtils.makeText(this, getString(R.string.newcomer_requests_too_many_outputs));
            return;
        }

        WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.please_wait));
        waitingDialog.show();

        new Thread(() -> {
            CashManager cashManager = FidManager.getInstance() != null
                    ? FidManager.getInstance().getCashManager() : null;
            if (cashManager == null) cashManager = CashManager.getInstance();

            List<Cash> inputs = null;
            if (cashManager != null) {
                // Small per-output margin on top of the payload: the fee estimate
                // inside getValidCashesForAmount assumes one output, so scale the
                // slack by the real output count.
                double total = amountPerOutput * totalOutputs + 0.001 * totalOutputs;
                inputs = cashManager.getValidCashesForAmount(total);
            }

            final List<Cash> finalInputs = inputs;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                waitingDialog.dismiss();
                if (finalInputs == null || finalInputs.isEmpty()) {
                    ToastUtils.makeText(this, getString(R.string.not_enough_fch));
                    return;
                }

                KeyInfo senderKeyInfo = FidManager.getInstance().getLiveKeyInfo();
                if (senderKeyInfo == null) {
                    ToastUtils.makeText(this, getString(R.string.current_setting_not_available));
                    return;
                }

                // Inputs and outputs are fully specified and the fee/change are
                // computed by TxHandler, so skip CreateTxActivity and go straight
                // to the send page — it still shows sender, receivers, and summary,
                // and nothing is signed until the user confirms there.
                RawTxInfo txInfo = new RawTxInfo();
                txInfo.setSender(senderKeyInfo.getId());
                txInfo.setSenderInfo(senderKeyInfo);
                txInfo.setInputs(finalInputs);
                List<Cash> outputs = new ArrayList<>();
                for (String fid : receivers) {
                    // `count` separate cashes per newcomer, so a fresh FID can act
                    // in parallel without waiting for one UTXO to confirm.
                    for (int i = 0; i < countPerReceiver; i++) {
                        outputs.add(new Cash(fid, amountPerOutput));
                    }
                }
                txInfo.setOutputs(outputs);

                Intent intent = new Intent(this, SendTxActivity.class);
                intent.putExtra(SendTxActivity.EXTRA_TX_INFO_JSON, txInfo.toJson());
                // Launch for result so a successful send closes this board and
                // returns to the caller.
                startActivityForResult(intent, SendTxActivity.RESULT_SIGNED);
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // The gift TX was signed and sent: nothing more to do on the board, so
        // propagate success to the caller and close.
        if (requestCode == SendTxActivity.RESULT_SIGNED
                && resultCode == SendTxActivity.RESULT_SIGNED) {
            setResult(RESULT_OK);
            finish();
        }
    }

    // ── Loading ────────────────────────────────────────────────────────

    private void loadRequests() {
        if (loading) return;
        loading = true;

        new Thread(() -> {
            String error = null;
            NewcomerBoard.FetchResult fetched =
                    new NewcomerBoard.FetchResult(new ArrayList<>(), boardCursor, null);
            try {
                // No cap: everything open goes into the session cache, and the
                // cursor below is only allowed to move past what we now hold.
                fetched = NewcomerBoard.fetch(this, boardCursor, Integer.MAX_VALUE);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to load board requests: %s", e.getMessage());
                error = e.getMessage();
            }

            final String finalError = error;
            final NewcomerBoard.FetchResult finalFetched = fetched;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                swipeRefreshLayout.setRefreshing(false);

                // Merge the newly fetched asks into the session cache (latest
                // per FID, never re-adding something dismissed this session).
                for (NobodyBoard.Request request : finalFetched.requests) {
                    if (ignoredFids.contains(request.requesterFid)) continue;
                    NobodyBoard.Request existing = cacheByFid.get(request.requesterFid);
                    if (existing == null || request.timestamp > existing.timestamp) {
                        cacheByFid.put(request.requesterFid, request);
                    }
                }

                // Advance the watermark past everything just retrieved so the
                // next fetch only pulls genuinely newer asks, then persist it.
                if (finalFetched.maxCreateTime > boardCursor) {
                    boardCursor = finalFetched.maxCreateTime;
                    saveCursor();
                }

                rebuildRequestList();

                String failure = finalError != null ? finalError : finalFetched.error;
                if (failure != null && requests.isEmpty()) {
                    emptyText.setText(failure);
                    emptyText.setVisibility(View.VISIBLE);
                } else if (requests.isEmpty()) {
                    emptyText.setText(R.string.newcomer_requests_empty);
                    emptyText.setVisibility(View.VISIBLE);
                } else {
                    emptyText.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    /**
     * Rebuild the visible list from the session cache, newest first, capped at
     * {@link #MAX_SHOWN_REQUESTS}. Drops selections whose request is gone.
     */
    private void rebuildRequestList() {
        List<NobodyBoard.Request> sorted = new ArrayList<>(cacheByFid.values());
        sorted.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        if (sorted.size() > MAX_SHOWN_REQUESTS) {
            sorted = new ArrayList<>(sorted.subList(0, MAX_SHOWN_REQUESTS));
        }
        requests.clear();
        requests.addAll(sorted);

        Set<String> shownFids = new HashSet<>();
        for (NobodyBoard.Request request : requests) {
            shownFids.add(request.requesterFid);
        }
        selectedFids.retainAll(shownFids);
        updateCheckAllState();
        adapter.notifyDataSetChanged();
    }

    /**
     * Ignore is a session-view declutter, not a persisted block: checked FIDs
     * are dropped from the cache and won't re-enter it this session. Across
     * sessions the cursor already hides them, so nothing else needs persisting.
     */
    private void ignoreChecked() {
        if (selectedFids.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.newcomer_requests_none_selected));
            return;
        }
        ignoredFids.addAll(selectedFids);
        for (String fid : selectedFids) {
            cacheByFid.remove(fid);
        }
        selectedFids.clear();
        rebuildRequestList();
        if (requests.isEmpty()) {
            emptyText.setText(R.string.newcomer_requests_empty);
            emptyText.setVisibility(View.VISIBLE);
        }
    }

    private long loadCursor() {
        return NewcomerBoard.getCursor(this, liveFid);
    }

    private void saveCursor() {
        NewcomerBoard.saveCursor(this, liveFid, boardCursor);
    }

    // ── List ───────────────────────────────────────────────────────────

    private class RequestAdapter extends RecyclerView.Adapter<RequestAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_newcomer_request, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(requests.get(position));
        }

        @Override
        public int getItemCount() {
            return requests.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            private final CheckBox checkBox;
            private final TextView fidText;
            private final TextView noteText;
            private final TextView timeText;
            private final SimpleDateFormat timeFormat =
                    new SimpleDateFormat("MM/dd HH:mm", Locale.getDefault());

            Holder(@NonNull View itemView) {
                super(itemView);
                checkBox = itemView.findViewById(R.id.request_checkbox);
                fidText = itemView.findViewById(R.id.request_fid);
                noteText = itemView.findViewById(R.id.request_note);
                timeText = itemView.findViewById(R.id.request_time);
                // The whole row toggles; the box itself just displays state.
                checkBox.setClickable(false);
                checkBox.setFocusable(false);
            }

            void bind(NobodyBoard.Request request) {
                checkBox.setChecked(selectedFids.contains(request.requesterFid));
                fidText.setText(request.requesterFid);
                noteText.setText(request.note);
                noteText.setVisibility(request.note.isEmpty() ? View.GONE : View.VISIBLE);
                timeText.setText(request.timestamp > 0
                        ? timeFormat.format(new Date(request.timestamp)) : "");

                itemView.setOnClickListener(v -> {
                    toggleSelection(request);
                    checkBox.setChecked(selectedFids.contains(request.requesterFid));
                });

                fidText.setOnLongClickListener(v -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "FID", request.requesterFid));
                    ToastUtils.makeText(NewcomerRequestsActivity.this,
                            getString(R.string.fid_copied_to_clipboard));
                    return true;
                });
            }
        }
    }
}
