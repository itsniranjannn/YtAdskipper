package com.njk.adskipper;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class MainActivity extends AppCompatActivity {

    // Required View IDs (Hard Constraints)
    private ViewGroup rootContent;
    private ImageView logoImage;
    private View statusDot;
    private View statusRing;
    private TextView statusLabel;
    private TextView statusText;
    private Button enableButton;
    private SwitchMaterial switchAutoSkip;
    private SwitchMaterial switchMute;
    private SwitchMaterial switchShield;

    // Redesign Views
    private View statusRingSecondary;
    private View heroCard;
    private View setupHelpCard;
    private Button helpAppInfoButton;
    private FrameLayout tileAutoSkip;
    private FrameLayout tileMute;
    private FrameLayout tileShield;
    private View cardAutoSkip;
    private View cardMute;
    private View cardShield;
    private TextView statsTodayNumber;
    private TextView statsTotalNumber;

    // Animators
    private AnimatorSet pulseAnimatorSet;
    private ValueAnimator colorTransitionAnimator;
    private boolean entrancePlayed = false;

    // State & Preference Observation
    private ContentObserver accessibilitySettingsObserver;
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private String lastKnownStateKey = null;

    private final Interpolator fastOutSlowIn = new FastOutSlowInInterpolator();
    private final DecelerateInterpolator decelerate = new DecelerateInterpolator();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Initialize Core SplashScreen API
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        // Edge-to-Edge handling
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        // Splash exit animation: 250ms scale + fade into app
        splashScreen.setOnExitAnimationListener(splashScreenView -> {
            if (isAnimationDisabled()) {
                splashScreenView.remove();
                return;
            }
            final View iconView = splashScreenView.getIconView();
            if (iconView != null) {
                iconView.animate()
                        .scaleX(1.2f)
                        .scaleY(1.2f)
                        .alpha(0f)
                        .setDuration(250)
                        .setInterpolator(fastOutSlowIn)
                        .withEndAction(splashScreenView::remove)
                        .start();
            } else {
                splashScreenView.getView().animate()
                        .alpha(0f)
                        .setDuration(250)
                        .setInterpolator(fastOutSlowIn)
                        .withEndAction(splashScreenView::remove)
                        .start();
            }
        });

        // Window Insets Padding
        View scrollView = findViewById(R.id.scrollView);
        if (scrollView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }

        prefs = getSharedPreferences(AdSkipAccessibilityService.PREFS_NAME, MODE_PRIVATE);

        // Bind Required IDs
        rootContent = findViewById(R.id.rootContent);
        logoImage = findViewById(R.id.logoImage);
        statusDot = findViewById(R.id.statusDot);
        statusRing = findViewById(R.id.statusRing);
        statusLabel = findViewById(R.id.statusLabel);
        statusText = findViewById(R.id.statusText);
        enableButton = findViewById(R.id.enableButton);
        switchAutoSkip = findViewById(R.id.switchAutoSkip);
        switchMute = findViewById(R.id.switchMute);
        switchShield = findViewById(R.id.switchShield);

        // Bind Redesign Elements
        statusRingSecondary = findViewById(R.id.statusRingSecondary);
        heroCard = findViewById(R.id.heroCard);
        setupHelpCard = findViewById(R.id.setupHelpCard);
        helpAppInfoButton = findViewById(R.id.helpAppInfoButton);
        tileAutoSkip = findViewById(R.id.tileAutoSkip);
        tileMute = findViewById(R.id.tileMute);
        tileShield = findViewById(R.id.tileShield);
        cardAutoSkip = findViewById(R.id.cardAutoSkip);
        cardMute = findViewById(R.id.cardMute);
        cardShield = findViewById(R.id.cardShield);
        statsTodayNumber = findViewById(R.id.statsTodayNumber);
        statsTotalNumber = findViewById(R.id.statsTotalNumber);

        setupSwitches();
        setupActionButtons();
        updateStatsView(false);

        playEntranceMotion();
    }

    private boolean isAnimationDisabled() {
        float scale = Settings.Global.getFloat(
                getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f);
        return scale == 0f;
    }

    private void setupActionButtons() {
        enableButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        if (helpAppInfoButton != null) {
            helpAppInfoButton.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            });
        }
    }

    private void setupSwitches() {
        switchAutoSkip.setChecked(prefs.getBoolean(AdSkipAccessibilityService.KEY_SKIP_ENABLED, true));
        switchMute.setChecked(prefs.getBoolean(AdSkipAccessibilityService.KEY_MUTE_ENABLED, true));
        switchShield.setChecked(prefs.getBoolean(AdSkipAccessibilityService.KEY_SHIELD_ENABLED, true));

        updateTileAppearance(tileAutoSkip, switchAutoSkip.isChecked(), false);
        updateTileAppearance(tileMute, switchMute.isChecked(), false);
        updateTileAppearance(tileShield, switchShield.isChecked(), false);

        switchAutoSkip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_SKIP_ENABLED, isChecked).apply();
            onSwitchToggled(switchAutoSkip, tileAutoSkip, cardAutoSkip, isChecked);
        });

        switchMute.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_MUTE_ENABLED, isChecked).apply();
            onSwitchToggled(switchMute, tileMute, cardMute, isChecked);
        });

        switchShield.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_SHIELD_ENABLED, isChecked).apply();
            onSwitchToggled(switchShield, tileShield, cardShield, isChecked);
        });
    }

    private void onSwitchToggled(View switchView, View tileView, View cardView, boolean isChecked) {
        // Haptic Feedback
        switchView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);

        // Animate Tile Fill
        updateTileAppearance(tileView, isChecked, true);

        // 200ms row tint flash
        if (cardView != null && !isAnimationDisabled()) {
            cardView.animate().cancel();
            cardView.setAlpha(0.7f);
            cardView.animate()
                    .alpha(1.0f)
                    .setDuration(200)
                    .setInterpolator(fastOutSlowIn)
                    .start();
        }
    }

    private void updateTileAppearance(View tile, boolean isChecked, boolean animate) {
        if (tile == null) return;
        int targetBg = isChecked ? R.drawable.bg_tile_active : R.drawable.bg_tile_outline;
        if (!animate || isAnimationDisabled()) {
            tile.setBackgroundResource(targetBg);
        } else {
            tile.animate().cancel();
            tile.setAlpha(0.4f);
            tile.setScaleX(0.92f);
            tile.setScaleY(0.92f);
            tile.setBackgroundResource(targetBg);
            tile.animate()
                    .alpha(1.0f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .setInterpolator(fastOutSlowIn)
                    .start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerAccessibilitySettingsObserver();
        registerPausePrefsListener();
        updateStatus();
        updateStatsView(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopStatusPulse();
        unregisterAccessibilitySettingsObserver();
        unregisterPausePrefsListener();
    }

    private void registerPausePrefsListener() {
        if (prefsListener != null) return;
        prefsListener = (sharedPreferences, key) -> {
            if (AdSkipAccessibilityService.KEY_PAUSED.equals(key)) {
                updateStatus();
            } else if (AdSkipAccessibilityService.KEY_SKIPPED_TODAY.equals(key)
                    || AdSkipAccessibilityService.KEY_SKIPPED_TOTAL.equals(key)) {
                updateStatsView(true);
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
        int targetColor;
        int labelRes;
        int descRes;

        if (!serviceEnabled) {
            stateKey = "not_enabled";
            dotRes = R.drawable.dot_inactive;
            targetColor = ContextCompat.getColor(this, R.color.status_inactive);
            labelRes = R.string.status_offline;
            descRes = R.string.status_desc_offline;
        } else if (paused) {
            stateKey = "paused";
            dotRes = R.drawable.dot_paused;
            targetColor = ContextCompat.getColor(this, R.color.status_paused);
            labelRes = R.string.status_paused;
            descRes = R.string.status_desc_paused;
        } else {
            stateKey = "active";
            dotRes = R.drawable.dot_active;
            targetColor = ContextCompat.getColor(this, R.color.status_active);
            labelRes = R.string.status_active;
            descRes = R.string.status_desc_active;
        }

        boolean stateChanged = lastKnownStateKey == null || !lastKnownStateKey.equals(stateKey);
        lastKnownStateKey = stateKey;

        statusDot.setBackgroundResource(dotRes);
        if (statusRing != null) statusRing.setBackgroundResource(dotRes);
        if (statusRingSecondary != null) statusRingSecondary.setBackgroundResource(dotRes);

        // Toggle Setup Help Card with smooth 250ms collapse
        toggleSetupHelpCard(!serviceEnabled);

        if (!stateChanged || isAnimationDisabled()) {
            statusLabel.setText(labelRes);
            statusText.setText(descRes);
            statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(targetColor));
        } else {
            animateHeroStateTransition(labelRes, descRes, targetColor);
        }

        if ("active".equals(stateKey)) {
            startStatusPulse();
        } else {
            stopStatusPulse();
        }
    }

    private void toggleSetupHelpCard(boolean show) {
        if (setupHelpCard == null) return;
        if (show) {
            if (setupHelpCard.getVisibility() != View.VISIBLE) {
                setupHelpCard.setVisibility(View.VISIBLE);
                setupHelpCard.setAlpha(0f);
                setupHelpCard.setTranslationY(-16f * getResources().getDisplayMetrics().density);
                setupHelpCard.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(250)
                        .setInterpolator(fastOutSlowIn)
                        .start();
            }
        } else {
            if (setupHelpCard.getVisibility() == View.VISIBLE) {
                setupHelpCard.animate()
                        .alpha(0f)
                        .translationY(-16f * getResources().getDisplayMetrics().density)
                        .setDuration(250)
                        .setInterpolator(fastOutSlowIn)
                        .withEndAction(() -> setupHelpCard.setVisibility(View.GONE))
                        .start();
            }
        }
    }

    private void animateHeroStateTransition(int labelRes, int descRes, int targetColor) {
        // State word: 150ms out / 150ms in crossfade with 12dp vertical slide
        float slidePx = 12f * getResources().getDisplayMetrics().density;

        statusLabel.animate().cancel();
        statusLabel.animate()
                .alpha(0f)
                .translationY(-slidePx)
                .setDuration(150)
                .setInterpolator(fastOutSlowIn)
                .withEndAction(() -> {
                    statusLabel.setText(labelRes);
                    statusLabel.setTranslationY(slidePx);
                    statusLabel.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(150)
                            .setInterpolator(fastOutSlowIn)
                            .start();
                })
                .start();

        // Status text update
        statusText.setText(descRes);

        // Dot & Ring color tween over 300ms (ArgbEvaluator)
        if (colorTransitionAnimator != null) {
            colorTransitionAnimator.cancel();
        }
        int startColor = ContextCompat.getColor(this, R.color.brand_sand);
        colorTransitionAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, targetColor);
        colorTransitionAnimator.setDuration(300);
        colorTransitionAnimator.setInterpolator(fastOutSlowIn);
        colorTransitionAnimator.addUpdateListener(anim -> {
            int c = (int) anim.getAnimatedValue();
            statusDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(c));
        });
        colorTransitionAnimator.start();

        // Hero Panel: subtle 1.00 -> 1.02 -> 1.00 scale settle
        if (heroCard != null) {
            heroCard.animate().cancel();
            heroCard.animate()
                    .scaleX(1.02f)
                    .scaleY(1.02f)
                    .setDuration(150)
                    .setInterpolator(fastOutSlowIn)
                    .withEndAction(() -> heroCard.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(150)
                            .setInterpolator(fastOutSlowIn)
                            .start())
                    .start();
        }
    }

    private void startStatusPulse() {
        if (pulseAnimatorSet != null || isAnimationDisabled() || statusRing == null) return;

        // Ring 1: scale 1 to 2.6, alpha 0.5 to 0, 1600ms
        ObjectAnimator ring1ScaleX = ObjectAnimator.ofFloat(statusRing, View.SCALE_X, 1.0f, 2.6f);
        ObjectAnimator ring1ScaleY = ObjectAnimator.ofFloat(statusRing, View.SCALE_Y, 1.0f, 2.6f);
        ObjectAnimator ring1Alpha = ObjectAnimator.ofFloat(statusRing, View.ALPHA, 0.5f, 0.0f);

        ring1ScaleX.setRepeatCount(ValueAnimator.INFINITE);
        ring1ScaleY.setRepeatCount(ValueAnimator.INFINITE);
        ring1Alpha.setRepeatCount(ValueAnimator.INFINITE);

        ring1ScaleX.setDuration(1600);
        ring1ScaleY.setDuration(1600);
        ring1Alpha.setDuration(1600);

        AnimatorSet ring1Set = new AnimatorSet();
        ring1Set.playTogether(ring1ScaleX, ring1ScaleY, ring1Alpha);

        // Ring 2: offset by 800ms
        AnimatorSet ring2Set = null;
        if (statusRingSecondary != null) {
            ObjectAnimator ring2ScaleX = ObjectAnimator.ofFloat(statusRingSecondary, View.SCALE_X, 1.0f, 2.6f);
            ObjectAnimator ring2ScaleY = ObjectAnimator.ofFloat(statusRingSecondary, View.SCALE_Y, 1.0f, 2.6f);
            ObjectAnimator ring2Alpha = ObjectAnimator.ofFloat(statusRingSecondary, View.ALPHA, 0.5f, 0.0f);

            ring2ScaleX.setRepeatCount(ValueAnimator.INFINITE);
            ring2ScaleY.setRepeatCount(ValueAnimator.INFINITE);
            ring2Alpha.setRepeatCount(ValueAnimator.INFINITE);

            ring2ScaleX.setDuration(1600);
            ring2ScaleY.setDuration(1600);
            ring2Alpha.setDuration(1600);
            ring2ScaleX.setStartDelay(800);
            ring2ScaleY.setStartDelay(800);
            ring2Alpha.setStartDelay(800);

            ring2Set = new AnimatorSet();
            ring2Set.playTogether(ring2ScaleX, ring2ScaleY, ring2Alpha);
        }

        pulseAnimatorSet = new AnimatorSet();
        if (ring2Set != null) {
            pulseAnimatorSet.playTogether(ring1Set, ring2Set);
        } else {
            pulseAnimatorSet.play(ring1Set);
        }
        pulseAnimatorSet.start();
    }

    private void stopStatusPulse() {
        if (pulseAnimatorSet != null) {
            pulseAnimatorSet.cancel();
            pulseAnimatorSet = null;
        }
        if (statusRing != null) {
            statusRing.setScaleX(1f);
            statusRing.setScaleY(1f);
            statusRing.setAlpha(0f);
        }
        if (statusRingSecondary != null) {
            statusRingSecondary.setScaleX(1f);
            statusRingSecondary.setScaleY(1f);
            statusRingSecondary.setAlpha(0f);
        }
    }

    private void playEntranceMotion() {
        if (entrancePlayed || isAnimationDisabled() || rootContent == null) return;
        entrancePlayed = true;

        View header = findViewById(R.id.headerContainer);
        View hero = findViewById(R.id.heroCard);
        View stats = findViewById(R.id.statsContainer);
        View help = findViewById(R.id.setupHelpCard);
        View card1 = findViewById(R.id.cardAutoSkip);
        View card2 = findViewById(R.id.cardMute);
        View card3 = findViewById(R.id.cardShield);
        View footer = findViewById(R.id.footerContainer);

        View[] views = new View[]{header, hero, stats, help, card1, card2, card3, footer};
        float travelDistance = 24f * getResources().getDisplayMetrics().density;
        int delay = 0;

        for (View v : views) {
            if (v == null) continue;
            v.setAlpha(0f);
            v.setTranslationY(travelDistance);
            v.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(380)
                    .setStartDelay(delay)
                    .setInterpolator(fastOutSlowIn)
                    .start();
            delay += 60;
        }
    }

    private void updateStatsView(boolean animate) {
        if (statsTodayNumber == null || statsTotalNumber == null) return;

        int today = prefs.getInt(AdSkipAccessibilityService.KEY_SKIPPED_TODAY, 0);
        int total = prefs.getInt(AdSkipAccessibilityService.KEY_SKIPPED_TOTAL, 0);

        if (!animate || isAnimationDisabled()) {
            statsTodayNumber.setText(String.valueOf(today));
            statsTotalNumber.setText(String.valueOf(total));
            return;
        }

        // Count-up over 600ms
        int currentToday = parseSafeInt(statsTodayNumber.getText().toString());
        int currentTotal = parseSafeInt(statsTotalNumber.getText().toString());

        ValueAnimator animToday = ValueAnimator.ofInt(currentToday, today);
        animToday.setDuration(600);
        animToday.setInterpolator(fastOutSlowIn);
        animToday.addUpdateListener(anim -> statsTodayNumber.setText(String.valueOf(anim.getAnimatedValue())));
        animToday.start();

        ValueAnimator animTotal = ValueAnimator.ofInt(currentTotal, total);
        animTotal.setDuration(600);
        animTotal.setInterpolator(fastOutSlowIn);
        animTotal.addUpdateListener(anim -> statsTotalNumber.setText(String.valueOf(anim.getAnimatedValue())));
        animTotal.start();
    }

    private int parseSafeInt(String val) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 0;
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
