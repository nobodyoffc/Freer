package com.fc.freer.convert;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageButton;

import com.fc.fc_ajdk.utils.DateUtils;
import com.fc.fc_ajdk.utils.FcDate;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.utils.QRCodeGenerator;

public class TimeConvertActivity extends BaseCryptoActivity {
    private static final String TAG = "TimeConvertActivity";
    private static final long FCH_GENESIS_TIMESTAMP = 1577836800000L; // 2020-01-01 00:00:00 UTC

    private EditText timestamp10Input;
    private EditText timestamp13Input;
    private EditText heightInput;
    private EditText fcDateInput;
    private EditText standardTimeInput;


    private boolean isUpdating = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set up text change listeners
        setupTextWatchers();

        // Set up icon click listeners
        setupIconListeners();

        // Set current time as default
        setCurrentTime();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_time_convert;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.time_convert);
    }

    @Override
    protected void initializeViews() {
        // Initialize input fields
        timestamp10Input = findViewById(R.id.timestamp_10_input);
        timestamp13Input = findViewById(R.id.timestamp_13_input);
        heightInput = findViewById(R.id.height_input);
        fcDateInput = findViewById(R.id.fc_date_input);
        standardTimeInput = findViewById(R.id.standard_time_input);

        // Initialize buttons
        clearButton = findViewById(R.id.clearButton);
    }

    @Override
    protected void setupButtons() {
        clearButton.setOnClickListener(v -> {
            hideKeyboard();
            clearAllFields();
        });

    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // No QR scan functionality in this activity
    }

    private void setupTextWatchers() {
        timestamp10Input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdating && s.length() > 0) {
                    convertFromTimestamp10(s.toString());
                }
            }
        });

        timestamp13Input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdating && s.length() > 0) {
                    convertFromTimestamp13(s.toString());
                }
            }
        });

        heightInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdating && s.length() > 0) {
                    convertFromHeight(s.toString());
                }
            }
        });

        fcDateInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdating && s.length() > 0) {
                    convertFromFcDate(s.toString());
                }
            }
        });

        standardTimeInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!isUpdating && s.length() > 0) {
                    convertFromStandardTime(s.toString());
                }
            }
        });
    }

    private void setupIconListeners() {
        // Timestamp 10 icons
        ImageButton timestamp10Copy = findViewById(R.id.timestamp_10_copy);
        ImageButton timestamp10Qr = findViewById(R.id.timestamp_10_qr);
        timestamp10Copy.setOnClickListener(v -> {
            String text = timestamp10Input.getText().toString();
            if (text != null && !text.trim().isEmpty()) {
                copyToClipboard(text, "Timestamp 10");
                showToast(getString(R.string.copied));
            } else {
                showToast(getString(R.string.no_content_to_copy));
            }
        });
        timestamp10Qr.setOnClickListener(v -> showQRCode(timestamp10Input.getText().toString(), "Timestamp 10"));

        // Timestamp 13 icons
        ImageButton timestamp13Copy = findViewById(R.id.timestamp_13_copy);
        ImageButton timestamp13Qr = findViewById(R.id.timestamp_13_qr);
        timestamp13Copy.setOnClickListener(v -> {
            String text = timestamp13Input.getText().toString();
            if (text != null && !text.trim().isEmpty()) {
                copyToClipboard(text, "Timestamp 13");
                showToast(getString(R.string.copied));
            } else {
                showToast(getString(R.string.no_content_to_copy));
            }
        });
        timestamp13Qr.setOnClickListener(v -> showQRCode(timestamp13Input.getText().toString(), "Timestamp 13"));

        // Height icons
        ImageButton heightCopy = findViewById(R.id.height_copy);
        ImageButton heightQr = findViewById(R.id.height_qr);
        heightCopy.setOnClickListener(v -> {
            String text = heightInput.getText().toString();
            if (text != null && !text.trim().isEmpty()) {
                copyToClipboard(text, "Height");
                showToast(getString(R.string.copied));
            } else {
                showToast(getString(R.string.no_content_to_copy));
            }
        });
        heightQr.setOnClickListener(v -> showQRCode(heightInput.getText().toString(), "Height"));

        // FcDate icons
        ImageButton fcDateCopy = findViewById(R.id.fc_date_copy);
        ImageButton fcDateQr = findViewById(R.id.fc_date_qr);
        fcDateCopy.setOnClickListener(v -> {
            String text = fcDateInput.getText().toString();
            if (text != null && !text.trim().isEmpty()) {
                copyToClipboard(text, "FcDate");
                showToast(getString(R.string.copied));
            } else {
                showToast(getString(R.string.no_content_to_copy));
            }
        });
        fcDateQr.setOnClickListener(v -> showQRCode(fcDateInput.getText().toString(), "FcDate"));

        // Standard Time icons
        ImageButton standardTimeCopy = findViewById(R.id.standard_time_copy);
        ImageButton standardTimeQr = findViewById(R.id.standard_time_qr);
        standardTimeCopy.setOnClickListener(v -> {
            String text = standardTimeInput.getText().toString();
            if (text != null && !text.trim().isEmpty()) {
                copyToClipboard(text, "Standard Time");
                showToast(getString(R.string.copied));
            } else {
                showToast(getString(R.string.no_content_to_copy));
            }
        });
        standardTimeQr.setOnClickListener(v -> showQRCode(standardTimeInput.getText().toString(), "Standard Time"));
    }

    private void showQRCode(String text, String title) {
        if (text == null || text.trim().isEmpty()) {
            showToast(getString(R.string.no_content_to_generate_qr_code));
            return;
        }

        QRCodeGenerator.generateAndShowQRCode(this, text, title);
    }

    private void clearAllFields() {
        isUpdating = true;
        try {
            timestamp10Input.setText("");
            timestamp13Input.setText("");
            heightInput.setText("");
            fcDateInput.setText("");
            standardTimeInput.setText("");
        } finally {
            isUpdating = false;
        }
    }

    private void setCurrentTime() {
        isUpdating = true;
        try {
            long currentTimestamp13 = System.currentTimeMillis();
            timestamp13Input.setText(String.valueOf(currentTimestamp13));
            convertFromTimestamp13(String.valueOf(currentTimestamp13));
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error setting current time: " + e.getMessage(), e);
        } finally {
            isUpdating = false;
        }
    }

    private void convertFromTimestamp10(String input) {
        isUpdating = true;
        try {
            long timestamp10 = Long.parseLong(input);
            long timestamp13 = timestamp10 * 1000;

            // Update all other fields
            timestamp13Input.setText(String.valueOf(timestamp13));

            // Calculate height (minutes since genesis)
            long height = (timestamp13 - FCH_GENESIS_TIMESTAMP) / (60 * 1000);
            heightInput.setText(String.valueOf(height));

            // Convert to FcDate
            FcDate fcDate = FcDate.fromHeight(height);
            fcDateInput.setText(fcDate.toString());

            // Convert to standard time
            String standardTime = DateUtils.longShortToTime(timestamp10, DateUtils.TO_SECOND);
            standardTimeInput.setText(standardTime);

        } catch (NumberFormatException e) {
            TimberLogger.d(TAG, "Invalid timestamp10 format: " + input);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting from timestamp10: " + e.getMessage(), e);
        } finally {
            isUpdating = false;
        }
    }

    private void convertFromTimestamp13(String input) {
        isUpdating = true;
        try {
            long timestamp13 = Long.parseLong(input);
            long timestamp10 = timestamp13 / 1000;

            // Update all other fields
            timestamp10Input.setText(String.valueOf(timestamp10));

            // Calculate height (minutes since genesis)
            long height = (timestamp13 - FCH_GENESIS_TIMESTAMP) / (60 * 1000);
            heightInput.setText(String.valueOf(height));

            // Convert to FcDate
            FcDate fcDate = FcDate.fromHeight(height);
            fcDateInput.setText(fcDate.toString());

            // Convert to standard time
            String standardTime = DateUtils.longToTime(timestamp13, DateUtils.TO_SECOND);
            standardTimeInput.setText(standardTime);

        } catch (NumberFormatException e) {
            TimberLogger.d(TAG, "Invalid timestamp13 format: " + input);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting from timestamp13: " + e.getMessage(), e);
        } finally {
            isUpdating = false;
        }
    }

    private void convertFromHeight(String input) {
        isUpdating = true;
        try {
            long height = Long.parseLong(input);

            // Convert to FcDate
            FcDate fcDate = FcDate.fromHeight(height);
            fcDateInput.setText(fcDate.toString());

            // Convert height to timestamp (height is in minutes)
            long timestamp13 = FCH_GENESIS_TIMESTAMP + (height * 60 * 1000);
            long timestamp10 = timestamp13 / 1000;

            // Update all other fields
            timestamp13Input.setText(String.valueOf(timestamp13));
            timestamp10Input.setText(String.valueOf(timestamp10));

            // Convert to standard time
            String standardTime = DateUtils.longToTime(timestamp13, DateUtils.TO_SECOND);
            standardTimeInput.setText(standardTime);

        } catch (NumberFormatException e) {
            TimberLogger.d(TAG, "Invalid height format: " + input);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting from height: " + e.getMessage(), e);
        } finally {
            isUpdating = false;
        }
    }

    private void convertFromFcDate(String input) {
        isUpdating = true;
        try {
            // Parse FcDate string format: year.day.hour.minute
            long height = FcDate.toHeight(input);

            // Update height field
            heightInput.setText(String.valueOf(height));

            // Convert height to timestamp (height is in minutes)
            long timestamp13 = FCH_GENESIS_TIMESTAMP + (height * 60 * 1000);
            long timestamp10 = timestamp13 / 1000;

            // Update all other fields
            timestamp13Input.setText(String.valueOf(timestamp13));
            timestamp10Input.setText(String.valueOf(timestamp10));

            // Convert to standard time
            String standardTime = DateUtils.longToTime(timestamp13, DateUtils.TO_SECOND);
            standardTimeInput.setText(standardTime);

        } catch (IllegalArgumentException e) {
            TimberLogger.d(TAG, "Invalid FcDate format: " + input + " - " + e.getMessage());
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting from FcDate: " + e.getMessage(), e);
        } finally {
            isUpdating = false;
        }
    }

    private void convertFromStandardTime(String input) {
        isUpdating = true;
        try {
            // Parse standard time format: yy-MM-dd HH:mm:ss
            long timestamp13 = DateUtils.dateToLong(input, DateUtils.TO_SECOND);

            if (timestamp13 == -1) {
                return;
            }

            long timestamp10 = timestamp13 / 1000;

            // Calculate height (minutes since genesis)
            long height = (timestamp13 - FCH_GENESIS_TIMESTAMP) / (60 * 1000);

            // Update all other fields
            timestamp13Input.setText(String.valueOf(timestamp13));
            timestamp10Input.setText(String.valueOf(timestamp10));
            heightInput.setText(String.valueOf(height));

            // Convert to FcDate
            FcDate fcDate = FcDate.fromHeight(height);
            fcDateInput.setText(fcDate.toString());

        } catch (Exception e) {
            TimberLogger.e(TAG, "Error converting from standard time: " + e.getMessage(), e);
        } finally {
            isUpdating = false;
        }
    }
}
