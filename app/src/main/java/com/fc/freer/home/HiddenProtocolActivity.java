package com.fc.freer.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.data.feipData.Protocol;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.ProtocolManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.ProtocolCardContainer;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HiddenProtocolActivity extends BaseCryptoActivity {
    private static final String TAG = "HiddenProtocolActivity";

    private ProtocolCardContainer protocolCardContainer;
    private LinearLayout protocolListContainer;
    private ProtocolManager protocolManager;

    private Button revealButton;
    private ImageButton backButton;
    private CheckBox selectAllCheckBox;
    private ScrollView protocolScrollView;
    private TextView protocolStatisticsTextView;

    private List<Protocol> protocolList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_hidden_protocol;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.hidden_protocols);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ProtocolManager
        try {
            FidManager fidManager = FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;

            if (fidManager != null && liveFid != null) {
                protocolManager = ProtocolManager.getInstance(this, liveFid);
            } else {
                TimberLogger.e(TAG, "liveFid is null, cannot initialize ProtocolManager");
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error initializing ProtocolManager with liveFid: " + e.getMessage());
        }

        if (protocolManager == null) {
            ToastUtils.makeText(this, getString(R.string.protocol_not_ready_try_later));
            finish();
            return;
        }

        // Load hidden protocols from local deleted list
        protocolList = protocolManager.getLocalHiddenProtocols();

        if (protocolList == null || protocolList.isEmpty()) {
            ToastUtils.makeText(this, getString(R.string.no_hidden_protocols_found));
            finish();
            return;
        }

        // Setup toolbar
        ToolbarUtils.setupToolbar(this, getActivityTitle());

        // Replace deprecated onBackPressed() with OnBackPressedCallback
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        setupButtons();
        loadProtocolCardList();
    }

    @Override
    protected void initializeViews() {
        revealButton = findViewById(R.id.reveal_button);
        backButton = findViewById(R.id.back_button);
        selectAllCheckBox = findViewById(R.id.select_all_checkbox);
        protocolScrollView = findViewById(R.id.protocol_scroll_view);
        protocolStatisticsTextView = findViewById(R.id.protocol_statistics);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Not used in this activity
    }

    private void loadProtocolCardList() {
        protocolListContainer = findViewById(R.id.fragment_container);

        protocolCardContainer = new ProtocolCardContainer(this, protocolListContainer, ChooseMode.CHOOSE_MULTI);
        protocolCardContainer.setHideEditButton(true);

        protocolCardContainer.setOnProtocolListChangedListener(updatedProtocolList -> {
            updateUI();
        });

        Map<String, String> cidMap = protocolCardContainer.getCidMap(protocolList, this);
        for (Protocol protocol : protocolList) {
            protocolCardContainer.addProtocolCard(protocol, cidMap);
        }

        setupSelectAllCheckBox();
        updateUI();
    }

    private void setupSelectAllCheckBox() {
        if (selectAllCheckBox != null && protocolCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (buttonView.isPressed()) {
                    protocolCardContainer.selectAll(isChecked);
                }
            });
        }
    }

    private void updateSelectAllCheckBoxState() {
        if (selectAllCheckBox != null && protocolCardContainer != null) {
            selectAllCheckBox.setOnCheckedChangeListener(null);

            if (protocolCardContainer.areAllSelected()) {
                selectAllCheckBox.setChecked(true);
            } else if (protocolCardContainer.areNoneSelected()) {
                selectAllCheckBox.setChecked(false);
            } else {
                selectAllCheckBox.setChecked(false);
            }

            setupSelectAllCheckBox();
        }
    }

    private void updateUI() {
        updateButtonStates();
        updateSelectAllCheckBoxState();
        updateStatistics();
    }

    private void updateButtonStates() {
        boolean hasSelected = protocolCardContainer != null && !protocolCardContainer.getSelectedProtocols().isEmpty();
        revealButton.setEnabled(hasSelected);
        revealButton.setAlpha(hasSelected ? 1.0f : 0.5f);
    }

    private void updateStatistics() {
        if (protocolStatisticsTextView == null) {
            return;
        }

        int containerSize = protocolCardContainer != null ? protocolCardContainer.getProtocolList().size() : 0;
        protocolStatisticsTextView.setText(getString(R.string.total_count, containerSize));
        protocolStatisticsTextView.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setupButtons() {
        revealButton.setOnClickListener(v -> {
            hideKeyboard();
            if (protocolCardContainer == null) return;
            List<Protocol> selectedProtocols = protocolCardContainer.getSelectedProtocols();
            if (selectedProtocols.isEmpty()) {
                ToastUtils.makeText(this, getString(R.string.no_protocols_selected));
                return;
            }
            performRevealOperation(selectedProtocols);
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void performRevealOperation(List<Protocol> protocolsToReveal) {
        // Remove from local deleted list and add back to main database
        for (Protocol protocol : protocolsToReveal) {
            protocolManager.removeFromLocalDeletedList(protocol);
            protocolManager.addProtocol(protocol);
        }
        protocolManager.commit();

        // Remove revealed protocols from the card container
        for (Protocol protocol : protocolsToReveal) {
            protocolCardContainer.removeProtocolById(protocol.getId());
        }

        // Update the main list
        protocolList.removeAll(protocolsToReveal);

        ToastUtils.makeText(this, getString(R.string.protocols_revealed_successfully, protocolsToReveal.size()));

        if (protocolList.isEmpty()) {
            setResult(Activity.RESULT_OK);
            finish();
        } else {
            updateUI();
            setResult(Activity.RESULT_OK);
        }
    }
}

