package com.njk.adskipper;

import android.animation.ArgbEvaluator;
import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
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
    private View statusPill;
    private ViewGroup rootContent;

    private Animator pulseAnimator;
    private ValueAnimator pillColorAnimator;
    private ObjectAnimator logoFloatAnimator;
    private ObjectAnimator logoBreatheAnimatorX;
    private ObjectAnimator logoBreatheAnimatorY;
    private ImageView logoImage;
    private boolean introPlayed = false;

    private static final int COLOR_NOT_ENABLED = 0x66FF5A5A; // visible red tint
    private static final int COLOR_PAUSED = 0x66FFC24B;      // visible amber tint
    private static final int COLOR_ACTIVE = 0x6650E3A0;      // visible green tint

    // Watches Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES directly so the
    // UI updates the instant the service is toggled — via Settings, the
    // system accessibility shortcut button, a quick-settings tile, anywhere —
    // not just when this Activity happens to resume.
    private ContentObserver accessibilitySettingsObserver;

    // Watches the pause flag the accessibility service writes when its
    // system Accessibility Button is tapped, so pausing/resuming updates
    // this screen live too, same as enabling/disabling.
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private String lastKnownStateKey = null; // "not_enabled" | "paused" | "active"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(
                AdSkipAccessibilityService.PREFS_NAME, MODE_PRIVATE);

        statusText = findViewById(R.id.statusText);
        statusLabel = findViewById(R.id.statusLabel);
        statusDot = findViewById(R.id.statusDot);
        statusRing = findViewById(R.id.statusRing);
        statusPill = findViewById(R.id.statusPill);
        rootContent = findViewById(R.id.rootContent);
        logoImage = findViewById(R.id.logoImage);
        Button enableButton = findViewById(R.id.enableButton);

        enableButton.setOnClickListener(v -> {
            // Deep-links to the system Accessibility settings screen.
            // Android does not allow apps to silently self-enable an
            // AccessibilityService, by design (it's a sensitive
            // permission) — the user must manually toggle it on. The
            // shortcut on/off switch lives on this same native settings
            // page (inside the service's own detail screen), so this one
            // button is the single entry point for both.
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        playLogoIntro(logoImage, rootContent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerAccessibilitySettingsObserver();
        registerPausePrefsListener();
        updateStatus();
        startLogoFloat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLogoFloat();
        stopStatusPulse();
        unregisterAccessibilitySettingsObserver();
        unregisterPausePrefsListener();
    }

    private void registerPausePrefsListener() {
        if (prefsListener != null) return;
        prefsListener = (sharedPreferences, key) -> {
            if (AdSkipAccessibilityService.KEY_PAUSED.equals(key)) {
                updateStatus();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void unregisterPausePrefsListener() {
        if (prefsListener == null) return;
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        prefsListener = null;
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
        boolean serviceEnabled = isAccessibilityServiceEnabled();
        boolean paused = prefs.getBoolean(AdSkipAccessibilityService.KEY_PAUSED, false);

        String stateKey;
        int dotRes;
        String label;
        String text;

        if (!serviceEnabled) {
            stateKey = "not_enabled";
            dotRes = R.drawable.dot_inactive;
            label = "NOT ENABLED";
            text = "Ad Skipper is not enabled yet. Tap below to turn it on.";
        } else if (paused) {
            stateKey = "paused";
            dotRes = R.drawable.dot_paused;
            label = "PAUSED";
            text = "Ad Skipper is paused. Tap the accessibility button again to resume.";
        } else {
            stateKey = "active";
            dotRes = R.drawable.dot_active;
            label = "ACTIVE";
            text = "Ad Skipper is active. Open YouTube and ads will be skipped automatically.";
        }

        boolean stateChanged = lastKnownStateKey == null || !lastKnownStateKey.equals(stateKey);
        lastKnownStateKey = stateKey;

        statusDot.setBackgroundResource(dotRes);

        TransitionManager.beginDelayedTransition(rootContent, new AutoTransition());
        statusLabel.setText(label);
        statusText.setText(text);

        int targetColor = stateKey.equals("active") ? COLOR_ACTIVE
                : stateKey.equals("paused") ? COLOR_PAUSED
                  : COLOR_NOT_ENABLED;
        animatePillColor(targetColor);

        if (stateKey.equals("active")) {
            startStatusPulse();
        } else {
            stopStatusPulse();
        }

        // Small confirming "pop" so a state flip is unmistakable even if you
        // caught it out of the corner of your eye (e.g. after using the
        // system accessibility button instead of this screen).
        if (stateChanged) {
            popStatusDot();
        }
    }

    /**
     * Smoothly crossfades the status pill's tint to match the current
     * state (red/amber/green), instead of the color cutting instantly.
     * Any in-flight color animation is cancelled first so rapid state
     * flips (e.g. quick pause/resume taps) don't queue up and stutter.
     */
    private void animatePillColor(int targetColor) {
        if (statusPill == null) return;
        if (pillColorAnimator != null) {
            pillColorAnimator.cancel();
        }
        Object currentTag = statusPill.getTag();
        int startColor = (currentTag instanceof Integer) ? (Integer) currentTag : targetColor;

        pillColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, targetColor);
        pillColorAnimator.setDuration(320);
        pillColorAnimator.setInterpolator(new DecelerateInterpolator());
        pillColorAnimator.addUpdateListener(anim ->
                statusPill.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf((int) anim.getAnimatedValue())));
        statusPill.setTag(targetColor);
        pillColorAnimator.start();
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

    // ── Idle "breathing" float on the logo — a small, slow up/down drift
    // that only runs while the screen is actually visible (started in
    // onResume, stopped in onPause), so it costs nothing while the app is
    // backgrounded. Small amplitude on purpose: this is ambient texture,
    // not something meant to draw the eye away from the status card.
    // ── Idle logo motion — a clearly visible float + breathing scale, so
    // the page doesn't feel static. Runs only while the screen is visible
    // (started in onResume, stopped in onPause), so it costs nothing while
    // the app is backgrounded.
    private void startLogoFloat() {
        if (logoFloatAnimator != null || logoImage == null) return;

        float amplitudePx = 14 * getResources().getDisplayMetrics().density;
        logoFloatAnimator = ObjectAnimator.ofFloat(logoImage, View.TRANSLATION_Y, 0f, -amplitudePx);
        logoFloatAnimator.setDuration(1400);
        logoFloatAnimator.setRepeatMode(ValueAnimator.REVERSE);
        logoFloatAnimator.setRepeatCount(ValueAnimator.INFINITE);
        logoFloatAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        logoFloatAnimator.start();

        logoBreatheAnimatorX = ObjectAnimator.ofFloat(logoImage, View.SCALE_X, 1f, 1.12f);
        logoBreatheAnimatorX.setDuration(1400);
        logoBreatheAnimatorX.setStartDelay(700); // let the entrance scale animation settle first
        logoBreatheAnimatorX.setRepeatMode(ValueAnimator.REVERSE);
        logoBreatheAnimatorX.setRepeatCount(ValueAnimator.INFINITE);
        logoBreatheAnimatorX.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        logoBreatheAnimatorX.start();

        logoBreatheAnimatorY = ObjectAnimator.ofFloat(logoImage, View.SCALE_Y, 1f, 1.12f);
        logoBreatheAnimatorY.setDuration(1400);
        logoBreatheAnimatorY.setStartDelay(700);
        logoBreatheAnimatorY.setRepeatMode(ValueAnimator.REVERSE);
        logoBreatheAnimatorY.setRepeatCount(ValueAnimator.INFINITE);
        logoBreatheAnimatorY.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        logoBreatheAnimatorY.start();
    }

    private void stopLogoFloat() {
        if (logoFloatAnimator != null) {
            logoFloatAnimator.cancel();
            logoFloatAnimator = null;
        }
        if (logoBreatheAnimatorX != null) {
            logoBreatheAnimatorX.cancel();
            logoBreatheAnimatorX = null;
        }
        if (logoBreatheAnimatorY != null) {
            logoBreatheAnimatorY.cancel();
            logoBreatheAnimatorY = null;
        }
        if (logoImage != null) {
            logoImage.setTranslationY(0f);
            logoImage.setScaleX(1f);
            logoImage.setScaleY(1f);
        }
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
        // (which would double-fade the logo itself). Each child also
        // starts slightly below its resting position for a soft slide-up,
        // staggered per child so the screen builds in rather than
        // appearing all at once.
        ViewGroup group = (ViewGroup) content;
        int slideOffsetPx = (int) (24 * getResources().getDisplayMetrics().density);
        int staggerStep = 90;
        int delay = 150; // let the logo lead by a beat before the rest follows

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == logo) continue;
            child.setAlpha(0f);
            child.setTranslationY(slideOffsetPx);
        }

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, 1f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.6f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.6f, 1f);

        AnimatorSet logoIn = new AnimatorSet();
        logoIn.playTogether(fadeIn, scaleX, scaleY);
        logoIn.setDuration(650);
        logoIn.setInterpolator(new OvershootInterpolator(1.4f));
        logoIn.start();

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == logo) continue;
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(420)
                    .setStartDelay(delay)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            delay += staggerStep;
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