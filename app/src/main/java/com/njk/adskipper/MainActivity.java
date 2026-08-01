package com.njk.adskipper;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView statusLabel;
    private View statusDot;
    private View statusRing;
    private ViewGroup rootContent;

    private Animator pulseAnimator;
    private boolean introPlayed = false;

    // Watches Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES directly so the
    // UI updates the instant the service is toggled — via Settings, the
    // system accessibility shortcut button, a quick-settings tile, anywhere —
    // not just when this Activity happens to resume.
    private ContentObserver accessibilitySettingsObserver;
    private Boolean lastKnownEnabled = null; // null = not checked yet

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        statusLabel = findViewById(R.id.statusLabel);
        statusDot = findViewById(R.id.statusDot);
        statusRing = findViewById(R.id.statusRing);
        rootContent = findViewById(R.id.rootContent);
        ImageView logoImage = findViewById(R.id.logoImage);
        Button enableButton = findViewById(R.id.enableButton);

        enableButton.setOnClickListener(v -> {
            // Deep-links to the system Accessibility settings screen.
            // Android does not allow apps to silently self-enable an
            // AccessibilityService, by design (it's a sensitive
            // permission) — the user must manually toggle it on.
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        playLogoIntro(logoImage, rootContent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerAccessibilitySettingsObserver();
        updateStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStatusPulse();
        unregisterAccessibilitySettingsObserver();
    }

    private void registerAccessibilitySettingsObserver() {
        if (accessibilitySettingsObserver != null) return;
        accessibilitySettingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                updateStatus();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                accessibilitySettingsObserver);
    }

    private void unregisterAccessibilitySettingsObserver() {
        if (accessibilitySettingsObserver == null) return;
        getContentResolver().unregisterContentObserver(accessibilitySettingsObserver);
        accessibilitySettingsObserver = null;
    }

    private void updateStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        boolean stateChanged = lastKnownEnabled == null || lastKnownEnabled != enabled;
        lastKnownEnabled = enabled;

        statusDot.setBackgroundResource(enabled ? R.drawable.dot_active : R.drawable.dot_inactive);

        TransitionManager.beginDelayedTransition(rootContent, new AutoTransition());
        statusLabel.setText(enabled ? "ACTIVE" : "NOT ENABLED");
        statusText.setText(enabled
                ? "Ad Skipper is active. Open YouTube and ads will be skipped automatically."
                : "Ad Skipper is not enabled yet. Tap below to turn it on.");

        if (enabled) {
            startStatusPulse();
        } else {
            stopStatusPulse();
        }

        // Small confirming "pop" so a state flip is unmistakable even if you
        // caught it out of the corner of your eye (e.g. after using the
        // system accessibility shortcut instead of this screen).
        if (stateChanged) {
            popStatusDot();
        }
    }

    private void popStatusDot() {
        statusDot.animate().cancel();
        statusDot.setScaleX(1f);
        statusDot.setScaleY(1f);
        statusDot.animate()
                .scaleX(1.8f).scaleY(1.8f)
                .setDuration(140)
                .setInterpolator(new OvershootInterpolator())
                .withEndAction(() -> statusDot.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(160)
                        .start())
                .start();
    }

    // ── Pulsing status ring, only runs while the service is active ────────
    private void startStatusPulse() {
        if (pulseAnimator != null) return; // already running
        pulseAnimator = AnimatorInflater.loadAnimator(this, R.animator.pulse_status);
        pulseAnimator.setTarget(statusRing);
        pulseAnimator.start();
    }

    private void stopStatusPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        statusRing.setScaleX(1f);
        statusRing.setScaleY(1f);
        statusRing.setAlpha(0f);
    }

    // ── One-shot logo intro, plays only the first time the activity is created ──
    private void playLogoIntro(ImageView logo, View content) {
        if (introPlayed) return;
        introPlayed = true;

        logo.setAlpha(0f);
        logo.setScaleX(0.6f);
        logo.setScaleY(0.6f);

        // content is the same LinearLayout the logo lives in, so hide its
        // OTHER children individually rather than the container's alpha
        // (which would double-fade the logo itself).
        ViewGroup group = (ViewGroup) content;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != logo) child.setAlpha(0f);
        }

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.6f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.6f, 1f);

        AnimatorSet logoIn = new AnimatorSet();
        logoIn.playTogether(fadeIn, scaleX, scaleY);
        logoIn.setDuration(650);
        logoIn.setInterpolator(new OvershootInterpolator(1.4f));

        AnimatorSet full = new AnimatorSet();
        full.play(logoIn);
        full.setStartDelay(0);
        full.start();

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != logo) {
                child.animate().alpha(1f).setDuration(400).setStartDelay(200).start();
            }
        }
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