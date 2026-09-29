package com.fc.freer.call;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.im.dock.DockCheckLevel;
import com.fc.freer.manager.FidManager;
import com.fc.freer.utils.ToolbarUtils;

/** Call settings (VOICE_SPEC §10): Available for calls, Message checking, Always relay, and in debug builds a test relay. */
public class CallSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call_settings);
        ToolbarUtils.setupToolbar(this, getString(R.string.call_settings));
        CallManager calls = CallManager.getInstance(this);

        CheckBox available = findViewById(R.id.cbAvailableForCalls);
        available.setChecked(calls.availableForCalls());
        available.setOnCheckedChangeListener((b, on) -> {
            calls.setAvailableForCalls(on);
            if (on) askToIgnoreBatteryOptimisation();
        });

        // How often the DOCKs are checked, and so how soon calls, meetings and messages arrive (§6.3).
        RadioGroup checking = findViewById(R.id.rgDockChecking);
        checking.check(switch (DockCheckLevel.get(this)) {
            case FAST -> R.id.rbDockFast;
            case BATTERY_SAVER -> R.id.rbDockSaver;
            default -> R.id.rbDockNormal;
        });
        checking.setOnCheckedChangeListener((g, id) -> {
            DockCheckLevel.set(this, id == R.id.rbDockFast ? DockCheckLevel.FAST
                    : id == R.id.rbDockSaver ? DockCheckLevel.BATTERY_SAVER : DockCheckLevel.NORMAL);
            ImManager im = FidManager.getInstance().getImManager();
            if (im != null) im.applyDockIntervals();
        });

        CheckBox ownAec = findViewById(R.id.cbOwnEchoCanceller);
        ownAec.setChecked(calls.ownEchoCanceller());
        ownAec.setOnCheckedChangeListener((b, on) -> calls.setOwnEchoCanceller(on));

        CheckBox alwaysRelay = findViewById(R.id.cbAlwaysRelay);
        alwaysRelay.setChecked(calls.alwaysRelay());
        alwaysRelay.setOnCheckedChangeListener((b, on) -> calls.setAlwaysRelay(on));

        // Testing only: a release build always calls through the callee's home.CALL (§6.2).
        findViewById(R.id.callRelayGroup).setVisibility(
                com.fc.freer.BuildConfig.DEBUG ? android.view.View.VISIBLE : android.view.View.GONE);
        EditText relay = findViewById(R.id.etCallRelay);
        relay.setText(calls.relayOverride());
        relay.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                calls.setRelayOverride(s.toString());
            }
        });
    }

    /** Doze would otherwise stop the keepalive that lets a call reach this phone (§6.3). */
    private void askToIgnoreBatteryOptimisation() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }
}
