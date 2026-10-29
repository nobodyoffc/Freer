package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.fc.fc_ajdk.data.feipData.ProofHistory;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ProofManager;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ToastUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * On-chain records (issue, sign, transfer, destroy) of one proof, or of the live FID.
 * Reuses the token history layouts.
 */
public class ProofHistoryActivity extends BaseCryptoActivity {
    public static final String EXTRA_PROOF_ID = "extra_proof_id";
    public static final String EXTRA_PROOF_TITLE = "extra_proof_title";
    public static final String EXTRA_FID = "extra_fid";

    private ProofManager proofManager;
    private WaitingDialog waitingDialog;

    private ImageButton clearButton;
    private ImageButton backButton;
    private SwipeRefreshLayout swipeRefreshLayout;
    private LinearLayout listContainer;
    private TextView statisticsTextView;

    private final List<ProofHistory> historyList = new ArrayList<>();

    private String filterProofId;
    private String filterProofTitle;
    private String filterFid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        filterProofId = getIntent().getStringExtra(EXTRA_PROOF_ID);
        filterProofTitle = getIntent().getStringExtra(EXTRA_PROOF_TITLE);
        filterFid = getIntent().getStringExtra(EXTRA_FID);
        super.onCreate(savedInstanceState);

        proofManager = ProofManager.getInstance();
        if (proofManager == null) {
            ToastUtils.makeText(this, getString(R.string.proof_not_ready_try_later));
            finish();
            return;
        }

        if (filterProofId != null && filterProofTitle != null) {
            setTitle(getString(R.string.proof_records) + ": " + filterProofTitle);
        }

        loadData();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_token_history;
    }

    @Override
    protected String getActivityTitle() {
        return getString(filterProofId == null ? R.string.my_proof_records : R.string.proof_records);
    }

    @Override
    protected void initializeViews() {
        clearButton = findViewById(R.id.clear_button);
        backButton = findViewById(R.id.back_button);
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout);
        listContainer = findViewById(R.id.fragment_container);
        statisticsTextView = findViewById(R.id.statistics_text);

        // One page of records; no paging
        Button loadMoreButton = findViewById(R.id.load_more_button);
        if (loadMoreButton != null) loadMoreButton.setVisibility(View.GONE);

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                swipeRefreshLayout.setRefreshing(false);
                loadData();
            });
        }
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used
    }

    @Override
    protected void setupButtons() {
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> {
                historyList.clear();
                listContainer.removeAllViews();
                updateStatistics();
            });
        }
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }
    }

    private void loadData() {
        showWaitingDialog(getString(R.string.loading_proof_records));
        new Thread(() -> {
            List<ProofHistory> records = proofManager.fetchProofHistoryFromAPI(filterProofId, filterFid);
            runOnUiThread(() -> {
                dismissWaitingDialog();
                historyList.clear();
                listContainer.removeAllViews();
                if (records == null || records.isEmpty()) {
                    ToastUtils.makeText(this, getString(R.string.no_proof_records_found));
                } else {
                    historyList.addAll(records);
                    for (ProofHistory history : historyList) {
                        View cardView = LayoutInflater.from(this).inflate(R.layout.item_token_history_card, listContainer, false);
                        setupHistoryCard(cardView, history);
                        listContainer.addView(cardView);
                    }
                }
                updateStatistics();
            });
        }).start();
    }

    private void setupHistoryCard(View cardView, ProofHistory history) {
        TextView opValue = cardView.findViewById(R.id.history_op_value);
        TextView timeValue = cardView.findViewById(R.id.history_time_value);
        TextView titleValue = cardView.findViewById(R.id.history_token_value);
        TextView signerValue = cardView.findViewById(R.id.history_signer_value);
        LinearLayout recipientRow = cardView.findViewById(R.id.recipient_row);
        TextView recipientValue = cardView.findViewById(R.id.history_recipient_value);
        LinearLayout amountRow = cardView.findViewById(R.id.amount_row);

        opValue.setText(opName(history.getOp()));

        if (history.getTime() != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            timeValue.setText(sdf.format(new Date(history.getTime() * 1000L)));
        } else {
            timeValue.setText("");
        }

        // Issue records carry the title; the others only the proof ID
        String subject = history.getTitle();
        if (subject == null) subject = history.getProofId();
        if (subject == null && history.getProofIds() != null) subject = String.join(", ", history.getProofIds());
        titleValue.setText(subject != null ? subject : "");

        signerValue.setText(history.getSigner() != null ? history.getSigner() : "");

        // The new owner: issueTo on issue, transferTo on transfer
        String newOwner = history.getIssueTo() != null ? history.getIssueTo() : history.getTransferTo();
        if (newOwner != null && !newOwner.isEmpty()) {
            recipientRow.setVisibility(View.VISIBLE);
            recipientValue.setText(newOwner);
        } else {
            recipientRow.setVisibility(View.GONE);
        }
        amountRow.setVisibility(View.GONE);

        cardView.setOnClickListener(v -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, history.toJson());
            intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, ProofHistory.class.getName());
            startActivity(intent);
        });
    }

    private String opName(String op) {
        if (op == null) return "";
        switch (op.toLowerCase(Locale.ROOT)) {
            case "issue": return getString(R.string.proof_op_issue);
            case "sign": return getString(R.string.proof_op_sign);
            case "transfer": return getString(R.string.proof_op_transfer);
            case "destroy": return getString(R.string.proof_op_destroy);
            default: return op;
        }
    }

    private void updateStatistics() {
        if (statisticsTextView == null) return;
        statisticsTextView.setText(getString(R.string.total_count, historyList.size()));
        View statisticsContainer = findViewById(R.id.statistics_container);
        if (statisticsContainer != null) statisticsContainer.setVisibility(View.VISIBLE);
    }

    private void showWaitingDialog(String message) {
        if (waitingDialog == null) {
            waitingDialog = new WaitingDialog(this, message);
        } else {
            waitingDialog.setHint(message);
        }
        if (!isFinishing() && !waitingDialog.isShowing()) {
            waitingDialog.show();
        }
    }

    private void dismissWaitingDialog() {
        if (waitingDialog != null && waitingDialog.isShowing() && !isFinishing()) {
            waitingDialog.dismiss();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (waitingDialog != null && waitingDialog.isShowing()) {
            waitingDialog.dismiss();
        }
        waitingDialog = null;
    }
}
