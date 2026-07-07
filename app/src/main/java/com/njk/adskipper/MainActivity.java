package com.njk.adskipper;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView statusLabel;
    private View statusDot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusLabel = findViewById(R.id.statusLabel);
        statusDot = findViewById(R.id.statusDot);
        Button enableButton = findViewById(R.id.enableButton);

        enableButton.setOnClickListener(v -> {
            // Deep-links to the system Accessibility settings screen.
            // Android does not allow apps to silently self-enable an
            // AccessibilityService, by design (it's a sensitive
            // permission) — the user must manually toggle it on.
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean enabled = isAccessibilityServiceEnabled();

        statusDot.setBackgroundResource(enabled ? R.drawable.dot_active : R.drawable.dot_inactive);
        statusLabel.setText(enabled ? "ACTIVE" : "NOT ENABLED");
        statusText.setText(enabled
                ? "Ad Skipper is active. Open YouTube and ads will be skipped automatically."
                : "Ad Skipper is not enabled yet. Tap below to turn it on.");
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (TextUtils.isEmpty(enabledServices)) return false;

        String serviceId = getPackageName() + "/" + AdSkipAccessibilityService.class.getName();
        return enabledServices.contains(serviceId);
    }
}
