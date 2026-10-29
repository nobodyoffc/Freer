package com.fc.freer.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.feipData.Token;
import com.fc.fc_ajdk.data.feipData.TokenHolder;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.TokenManager;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.TokenCardContainer;
import com.fc.freer.utils.TokenHolderCardContainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Closed tokens that concern the live FID: those it holds a balance in, and those it deployed.
 * FEIP20 rejects issue, transfer and destroy on a closed token, so both lists are read-only:
 * a held balance is frozen for good.
 */
public class ClosedTokensActivity extends BaseCryptoActivity {
    private static final String TAG = "ClosedTokensActivity";

    private TokenManager tokenManager;
    private WaitingDialog waitingDialog;

    private TextView heldHeader;
    private LinearLayout heldContainer;
    private TextView deployedHeader;
    private LinearLayout deployedContainer;

    @Override protected int getLayoutId() { return R.layout.activity_closed_tokens; }
    @Override protected String getActivityTitle() { return getString(R.string.closed_tokens); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String liveFid = null;
        try {
            FidManager fidManager = FidManager.getInstance();
            liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (liveFid != null) tokenManager = TokenManager.getInstance(this, liveFid);
        } catch (Exception e) { TimberLogger.e(TAG, "Error initializing TokenManager: " + e.getMessage()); }

        if (tokenManager == null) { ToastUtils.makeText(this, getString(R.string.token_not_ready_try_later)); finish(); return; }

        loadClosedTokens(liveFid);
    }

    @Override protected void initializeViews() {
        heldHeader = findViewById(R.id.held_header);
        heldContainer = findViewById(R.id.held_container);
        deployedHeader = findViewById(R.id.deployed_header);
        deployedContainer = findViewById(R.id.deployed_container);
    }

    @Override protected void handleQrScanResult(int requestCode, String qrContent) {}

    @Override protected void setupButtons() {}

    private void loadClosedTokens(String liveFid) {
        showWaitingDialog(getString(R.string.loading_tokens));

        new Thread(() -> {
            // Holdings come from the local database; their token info says whether each is closed
            List<TokenHolder> holders = tokenManager.getAllTokenHolders();
            List<String> tokenIds = new ArrayList<>();
            for (TokenHolder holder : holders) {
                if (holder.getTokenId() != null && !tokenIds.contains(holder.getTokenId())) tokenIds.add(holder.getTokenId());
            }
            Map<String, Token> tokenInfoMap = new HashMap<>();
            if (!tokenIds.isEmpty()) {
                Map<String, Token> fetched = tokenManager.fetchTokensByIds(tokenIds);
                if (fetched != null) tokenInfoMap.putAll(fetched);
            }
            List<TokenHolder> closedHolders = new ArrayList<>();
            for (TokenHolder holder : holders) {
                Token token = tokenInfoMap.get(holder.getTokenId());
                if (token != null && Boolean.TRUE.equals(token.getClosed())) closedHolders.add(holder);
            }

            List<Token> deployed = tokenManager.fetchClosedTokensDeployedBy(liveFid);

            runOnUiThread(() -> {
                dismissWaitingDialog();
                showLists(closedHolders, tokenInfoMap, deployed != null ? deployed : new ArrayList<>());
            });
        }).start();
    }

    private void showLists(List<TokenHolder> closedHolders, Map<String, Token> tokenInfoMap, List<Token> deployed) {
        if (closedHolders.isEmpty() && deployed.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_closed_tokens_found));
            finish();
            return;
        }

        // No send or burn listener: the holder card leaves those icons out
        TokenHolderCardContainer heldCards = new TokenHolderCardContainer(this, heldContainer, ChooseMode.WITHOUT_CHOOSE);
        heldCards.setTokenInfoMap(tokenInfoMap);
        for (TokenHolder holder : closedHolders) heldCards.addTokenHolderCard(holder);
        heldHeader.setVisibility(closedHolders.isEmpty() ? View.GONE : View.VISIBLE);

        // The token card hides the issue icon on a closed token; history stays available
        TokenCardContainer deployedCards = new TokenCardContainer(this, deployedContainer, ChooseMode.WITHOUT_CHOOSE);
        deployedCards.setOnHistoryIconClickListener(token -> {
            Intent intent = new Intent(this, TokenHistoryActivity.class);
            intent.putExtra(TokenHistoryActivity.EXTRA_TOKEN_ID, token.getId());
            startActivity(intent);
        });
        Map<String, String> cidMap = deployedCards.getCidMap(deployed, this);
        for (Token token : deployed) deployedCards.addTokenCard(token, cidMap);
        deployedHeader.setVisibility(deployed.isEmpty() ? View.GONE : View.VISIBLE);
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
