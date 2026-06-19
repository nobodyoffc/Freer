package com.fc.freer.data;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;

import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.manager.FidManager;
import com.fc.freer.manager.HatManager;
import com.fc.freer.utils.TextIconsUtils;
import com.fc.freer.utils.ToastUtils;
import com.fc.freer.utils.ToolbarUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class UpdateHatActivity extends BaseCryptoActivity {

    public static final String EXTRA_HAT_JSON = "extra_hat_json";

    private Hat currentHat;
    private TextView hatIdValue;
    private TextInputEditText nameInput;
    private TextInputEditText descInput;
    private TextInputEditText typesInput;
    private TextInputEditText aidsInput;
    private TextInputEditText pidsInput;

    private ImageButton clearButton;
    private ImageButton saveButton;
    private ImageButton backButton;

    // QR scan request codes
    private static final int QR_SCAN_NAME = 1001;
    private static final int QR_SCAN_DESC = 1002;
    private static final int QR_SCAN_TYPES = 1003;
    private static final int QR_SCAN_AIDS = 1004;
    private static final int QR_SCAN_PIDS = 1005;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_update_hat;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.update_hat);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get hat data from intent
        String hatJson = getIntent().getStringExtra(EXTRA_HAT_JSON);
        if (hatJson != null) {
            currentHat = JsonUtils.fromJson(hatJson, Hat.class);
        }

        if (currentHat == null) {
            ToastUtils.makeText(this, R.string.hat_data_not_found);
            finish();
            return;
        }

        // Set up root layout touch listener to hide keyboard
        View rootLayout = findViewById(android.R.id.content);
        rootLayout.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hideKeyboard();
            }
            return false;
        });

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
        populateFields();

        // Setup scan icons for input fields
        TextIconsUtils.setupTextIcons(this, R.id.nameView, R.id.scanIcon, QR_SCAN_NAME);
        TextIconsUtils.setupTextIcons(this, R.id.descView, R.id.scanIcon, QR_SCAN_DESC);
        TextIconsUtils.setupTextIcons(this, R.id.typesView, R.id.scanIcon, QR_SCAN_TYPES);
        TextIconsUtils.setupTextIcons(this, R.id.aidsView, R.id.scanIcon, QR_SCAN_AIDS);
        TextIconsUtils.setupTextIcons(this, R.id.pidsView, R.id.scanIcon, QR_SCAN_PIDS);
    }

    @Override
    protected void initializeViews() {
        hatIdValue = findViewById(R.id.hatIdValue);

        View nameView = findViewById(R.id.nameView);
        View descView = findViewById(R.id.descView);
        View typesView = findViewById(R.id.typesView);
        View aidsView = findViewById(R.id.aidsView);
        View pidsView = findViewById(R.id.pidsView);

        nameInput = nameView.findViewById(R.id.textInput);
        nameInput.setHint(R.string.name);

        descInput = descView.findViewById(R.id.textInput);
        descInput.setHint(R.string.description);

        typesInput = typesView.findViewById(R.id.textInput);
        typesInput.setHint(R.string.types_comma_separated);

        aidsInput = aidsView.findViewById(R.id.textInput);
        aidsInput.setHint(R.string.aids_comma_separated);

        pidsInput = pidsView.findViewById(R.id.textInput);
        pidsInput.setHint(R.string.pids_comma_separated);

        clearButton = findViewById(R.id.clearButton);
        saveButton = findViewById(R.id.saveButton);
        backButton = findViewById(R.id.back_button);
    }

    private void populateFields() {
        if (currentHat == null) return;

        hatIdValue.setText(currentHat.getId());

        if (currentHat.getName() != null) {
            nameInput.setText(currentHat.getName());
        }
        if (currentHat.getDesc() != null) {
            descInput.setText(currentHat.getDesc());
        }
        if (currentHat.getTypes() != null) {
            typesInput.setText(String.join(", ", currentHat.getTypes()));
        }
        if (currentHat.getAids() != null) {
            aidsInput.setText(String.join(", ", currentHat.getAids()));
        }
        if (currentHat.getPids() != null) {
            pidsInput.setText(String.join(", ", currentHat.getPids()));
        }
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearInputs();
        });

        saveButton.setOnClickListener(v -> {
            hideKeyboard();
            saveHat();
        });

        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    private void clearInputs() {
        nameInput.setText("");
        descInput.setText("");
        typesInput.setText("");
        aidsInput.setText("");
        pidsInput.setText("");
    }

    private void saveHat() {
        if (currentHat == null) {
            ToastUtils.makeText(this, R.string.hat_data_not_found);
            return;
        }

        String name = getText(nameInput);
        String desc = getText(descInput);
        String typesStr = getText(typesInput);
        String aidsStr = getText(aidsInput);
        String pidsStr = getText(pidsInput);

        // Parse comma-separated strings to lists
        List<String> types = parseCommaSeparatedToList(typesStr);
        List<String> aids = parseCommaSeparatedToList(aidsStr);
        List<String> pids = parseCommaSeparatedToList(pidsStr);

        // Update the hat fields
        currentHat.setName(name.isEmpty() ? null : name);
        currentHat.setDesc(desc.isEmpty() ? null : desc);
        currentHat.setTypes(types);
        currentHat.setAids(aids);
        currentHat.setPids(pids);
        currentHat.setLast(System.currentTimeMillis());

        // Save to database
        FidManager fidManager = FidManager.getInstance();
        if (fidManager == null || fidManager.getLiveFid() == null) {
            ToastUtils.makeText(this, R.string.no_active_fid);
            return;
        }

        HatManager hatManager = HatManager.getInstance(this, fidManager.getLiveFid());
        if (hatManager != null) {
            hatManager.updateHat(currentHat);
            hatManager.commit();
            ToastUtils.makeText(this, R.string.hat_saved_successfully);
            setResult(RESULT_OK);
            finish();
        } else {
            ToastUtils.makeText(this, R.string.initialization_failed);
        }
    }

    private String getText(TextInputEditText input) {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    private List<String> parseCommaSeparatedToList(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String[] parts = input.split(",");
        java.util.List<String> nonEmpty = new java.util.ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                nonEmpty.add(trimmed);
            }
        }
        return nonEmpty.isEmpty() ? null : nonEmpty;
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        switch (requestCode) {
            case QR_SCAN_NAME:
                nameInput.setText(qrContent);
                break;
            case QR_SCAN_DESC:
                descInput.setText(qrContent);
                break;
            case QR_SCAN_TYPES:
                typesInput.setText(qrContent);
                break;
            case QR_SCAN_AIDS:
                aidsInput.setText(qrContent);
                break;
            case QR_SCAN_PIDS:
                pidsInput.setText(qrContent);
                break;
        }
    }
}
