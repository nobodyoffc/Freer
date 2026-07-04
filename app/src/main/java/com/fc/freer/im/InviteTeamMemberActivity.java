package com.fc.freer.im;

import android.content.Intent;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.data.feipData.TeamOpData;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.contact.ChooseContactActivity;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class InviteTeamMemberActivity extends BaseCryptoActivity {
    public static final String EXTRA_TEAM_ID = "extra_team_id";
    private static final String TAG = "InviteTeamMember";

    private EditText fidsInput;
    private ImageButton inviteButton;
    private ImageButton fromListButton;
    private ImageButton fromContactsButton;
    private ImageButton searchOnChainButton;
    private ImageButton clearButton;
    private String teamId;

    private ActivityResultLauncher<Intent> chooseContactLauncher;
    private ActivityResultLauncher<Intent> searchOnChainLauncher;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_invite_member;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.invite_members);
    }

    @Override
    protected void initializeViews() {
        fidsInput = findViewById(R.id.fids_input);
        inviteButton = findViewById(R.id.invite_button);
        fromListButton = findViewById(R.id.from_list_button);
        fromContactsButton = findViewById(R.id.from_contacts_button);
        searchOnChainButton = findViewById(R.id.search_on_chain_button);
        clearButton = findViewById(R.id.clear_button);

        teamId = getIntent().getStringExtra(EXTRA_TEAM_ID);
        if (teamId == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            finish();
        }

        chooseContactLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String json = result.getData().getStringExtra(ChooseContactActivity.EXTRA_SELECTED_CONTACTS);
                        if (json != null) {
                            List<Contact> contacts = new Gson().fromJson(json, new TypeToken<List<Contact>>() {}.getType());
                            if (contacts != null) {
                                appendFidsToInput(contacts.stream()
                                        .map(Contact::getFid)
                                        .filter(fid -> fid != null && !fid.isEmpty())
                                        .collect(java.util.stream.Collectors.toList()));
                            }
                        }
                    }
                }
        );

        searchOnChainLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> fids = result.getData().getStringArrayListExtra(
                                SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
                        if (fids != null && !fids.isEmpty()) {
                            appendFidsToInput(fids);
                        }
                    }
                }
        );
    }

    @Override
    protected void setupButtons() {
        inviteButton.setOnClickListener(v -> {
            hideKeyboard();
            inviteMembers();
        });

        fromListButton.setOnClickListener(v -> {
            hideKeyboard();
            addFromFidList();
        });

        fromContactsButton.setOnClickListener(v -> {
            hideKeyboard();
            Intent intent = new Intent(this, ChooseContactActivity.class);
            intent.putExtra(ChooseContactActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
            chooseContactLauncher.launch(intent);
        });

        searchOnChainButton.setOnClickListener(v -> {
            hideKeyboard();
            Intent intent = new Intent(this, SearchFidsOnChainActivity.class);
            intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_MULTI.name());
            intent.putExtra(SearchFidsOnChainActivity.EXTRA_TITLE, getString(R.string.search_fids_on_chain));
            searchOnChainLauncher.launch(intent);
        });

        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            fidsInput.setText("");
        });

        findViewById(R.id.back_button).setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {}

    private void addFromFidList() {
        List<String> globalFidList = FreerApplication.getFidList();
        if (globalFidList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.fid_list_empty));
            return;
        }
        appendFidsToInput(globalFidList);
    }

    private void appendFidsToInput(List<String> fids) {
        if (fids == null || fids.isEmpty()) return;

        String existing = fidsInput.getText() != null ? fidsInput.getText().toString().trim() : "";
        Set<String> existingFids = new LinkedHashSet<>();
        if (!existing.isEmpty()) {
            for (String line : existing.split("\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) existingFids.add(trimmed);
            }
        }

        int addedCount = 0;
        for (String fid : fids) {
            if (existingFids.add(fid)) addedCount++;
        }

        StringBuilder sb = new StringBuilder();
        for (String fid : existingFids) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(fid);
        }
        fidsInput.setText(sb.toString());
        fidsInput.setSelection(sb.length());

        ToastUtils.makeText(this, getString(R.string.added_n_fids, addedCount));
    }

    // Called from background thread (inside TxSender callback).
    // The consensus document is published at team creation (uploaded to the team's DISK and
    // committed on-chain via Team.consensusId + Team.home.DISK), so an invitee discovers and
    // downloads it entirely from the confirmed on-chain team. We therefore only send a
    // lightweight "you've been invited" notification here — no off-chain HAT, which would
    // otherwise race ahead of the invite TX's on-chain confirmation.
    private void sendTeamInviteNotifications(List<String> fids) {
        try {
            Setting setting = SettingManager.getInstance().getCurrentSetting();
            if (setting == null) return;
            ImManager imManager = setting.getImManager();
            if (imManager == null) return;

            String teamName = null;
            if (imManager.getTeamHandler() != null) {
                Team team = imManager.getTeam(teamId);
                if (team != null) teamName = team.getStdName();
            }
            String displayName = teamName != null ? teamName : teamId;

            for (String fid : fids) {
                imManager.sendTeamInviteNotification(fid, teamId, displayName);
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to send invite notifications: %s", e.getMessage());
        }
    }

    private void inviteMembers() {
        String text = fidsInput.getText() != null ? fidsInput.getText().toString().trim() : "";
        if (text.isEmpty()) {
            ToastUtils.makeText(this, R.string.fids_required);
            return;
        }

        String[] lines = text.split("\\n");
        List<String> fidList = new ArrayList<>();
        for (String line : lines) {
            String fid = line.trim();
            if (!fid.isEmpty()) fidList.add(fid);
        }

        if (fidList.isEmpty()) {
            ToastUtils.makeText(this, R.string.fids_required);
            return;
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        TeamOpData opData = TeamOpData.makeInvite(teamId, fidList.toArray(new String[0]));
        Feip feip = Feip.fromName("Team");
        feip.setData(opData);
        String feipJson = feip.toJson();

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey == null) {
            ToastUtils.makeText(this, getString(R.string.failed));
            return;
        }

        inviteButton.setEnabled(false);

        new Thread(() -> {
            CashManager cashManager = CashManager.getInstance();
            TxSender txSender = new TxSender();
            txSender.carveSimpleFeip(this, liveKeyInfo.getId(), feipJson, prikey, cashManager,
                    new TxHandler(), (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7),
                    new TxSender.TxCallback() {
                        @Override
                        public void onSuccess(String txId) {
                            sendTeamInviteNotifications(fidList);
                            runOnUiThread(() -> {
                                finish();
                            });
                        }

                        @Override
                        public void onError(String errorMessage) {
                            runOnUiThread(() -> {
                                inviteButton.setEnabled(true);
                                ToastUtils.makeText(InviteTeamMemberActivity.this,
                                        getString(R.string.failed_to_send_invitation) + ": " + errorMessage);
                            });
                        }

                        @Override
                        public void onUnsignedTx(com.fc.fc_ajdk.core.fch.RawTxInfo rawTxInfo) {
                            runOnUiThread(() -> inviteButton.setEnabled(true));
                        }

                        @Override
                        public void onUnbroadcasted(String signedTxHex) {
                            runOnUiThread(() -> inviteButton.setEnabled(true));
                        }

                        @Override
                        public void onCancelled() {
                            runOnUiThread(() -> inviteButton.setEnabled(true));
                        }
                    });
        }).start();
    }
}
