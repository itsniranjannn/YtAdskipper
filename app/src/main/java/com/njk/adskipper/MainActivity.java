package com.njk.adskipper;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
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
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
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

    // Redesign & Stepper Elements
    private View statusRingSecondary;
    private View heroCard;
    private View heroGlow;
    private TextView heroServiceLabel;
    private View setupHelpCard;
    private TextView stepperBadge;
    private TextView stepperAndroidNotice;
    private TextView stepperInstruction;
    private Button helpAppInfoButton;
    private TextView troubleHeader;
    private TextView troubleAdbDetails;

    // Switch Cards & Tiles
    private FrameLayout tileAutoSkip;
    private FrameLayout tileMute;
    private FrameLayout tileShield;
    private FrameLayout tileDns;
    private FrameLayout tileFbShield;

    private ImageView iconAutoSkip;
    private ImageView iconMute;
    private ImageView iconShield;
    private ImageView iconDns;
    private ImageView iconFbShield;

    private View cardAutoSkip;
    private View cardMute;
    private View cardShield;
    private View cardDns;
    private View cardFbShield;

    private SwitchMaterial switchDns;
    private SwitchMaterial switchFbShield;
    private TextView captionDnsStatus;

    // Stats
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

    private final ActivityResultLauncher<String> postNotificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                // Post-notification result received; proceed to prepare/start VPN
                launchVpnConsentOrStart();
            });

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                boolean granted = result.getResultCode() == RESULT_OK;
                if (granted) {
                    HushVpnService.start(this);
                    if (switchDns != null) switchDns.setChecked(true);
                    updateTileAppearance(tileDns, iconDns, true, true);
                    syncDnsCaption(true);
                } else {
                    if (switchDns != null) switchDns.setChecked(false);
                    updateTileAppearance(tileDns, iconDns, false, true);
                    syncDnsCaption(false);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);

        splashScreen.setOnExitAnimationListener(splashScreenView -> {
            if (isAnimationDisabled()) {
                splashScreenView.remove();
                return;
            }
            final View iconView = splashScreenView.getIconView();
            if (iconView != null) {
                iconView.setAlpha(1.0f);
                iconView.animate()
                        .scaleX(1.25f)
                        .scaleY(1.25f)
                        .alpha(0f)
                        .setDuration(280)
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

        View scrollView = findViewById(R.id.scrollView);
        if (scrollView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(scrollView, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), insets.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }

        prefs = getSharedPreferences(AdSkipAccessibilityService.PREFS_NAME, MODE_PRIVATE);

        // Required IDs
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

        // Redesign & Stepper Elements
        statusRingSecondary = findViewById(R.id.statusRingSecondary);
        heroCard = findViewById(R.id.heroCard);
        heroGlow = findViewById(R.id.heroGlow);
        heroServiceLabel = findViewById(R.id.heroServiceLabel);
        setupHelpCard = findViewById(R.id.setupHelpCard);
        stepperBadge = findViewById(R.id.stepperBadge);
        stepperAndroidNotice = findViewById(R.id.stepperAndroidNotice);
        stepperInstruction = findViewById(R.id.stepperInstruction);
        helpAppInfoButton = findViewById(R.id.helpAppInfoButton);
        troubleHeader = findViewById(R.id.troubleHeader);
        troubleAdbDetails = findViewById(R.id.troubleAdbDetails);

        // Switch Cards
        tileAutoSkip = findViewById(R.id.tileAutoSkip);
        tileMute = findViewById(R.id.tileMute);
        tileShield = findViewById(R.id.tileShield);
        tileDns = findViewById(R.id.tileDns);
        tileFbShield = findViewById(R.id.tileFbShield);

        iconAutoSkip = findViewById(R.id.iconAutoSkip);
        iconMute = findViewById(R.id.iconMute);
        iconShield = findViewById(R.id.iconShield);
        iconDns = findViewById(R.id.iconDns);
        iconFbShield = findViewById(R.id.iconFbShield);

        cardAutoSkip = findViewById(R.id.cardAutoSkip);
        cardMute = findViewById(R.id.cardMute);
        cardShield = findViewById(R.id.cardShield);
        cardDns = findViewById(R.id.cardDns);
        cardFbShield = findViewById(R.id.cardFbShield);

        switchDns = findViewById(R.id.switchDns);
        switchFbShield = findViewById(R.id.switchFbShield);
        captionDnsStatus = findViewById(R.id.captionDnsStatus);

        statsTodayNumber = findViewById(R.id.statsTodayNumber);
        statsTotalNumber = findViewById(R.id.statsTotalNumber);

        setupSwitches();
        setupDnsSwitch();
        setupActionButtons();
        setupTroubleExpander();
        updateStatsView(false);

        // Resilient Entrance Motion
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
            boolean serviceEnabled = isAccessibilityServiceEnabled();
            if (!serviceEnabled) {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            } else {
                boolean currentlyPaused = prefs.getBoolean(AdSkipAccessibilityService.KEY_PAUSED, false);
                prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_PAUSED, !currentlyPaused).apply();
                updateStatus();
            }
        });

        if (helpAppInfoButton != null) {
            helpAppInfoButton.setOnClickListener(v -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            });
        }
    }

    private void setupTroubleExpander() {
        if (troubleHeader == null || troubleAdbDetails == null) return;
        troubleHeader.setOnClickListener(v -> {
            int targetVis = troubleAdbDetails.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
            troubleAdbDetails.setVisibility(targetVis);
        });
    }

    private void setupSwitches() {
        switchAutoSkip.setChecked(prefs.getBoolean(AdSkipAccessibilityService.KEY_SKIP_ENABLED, true));
        switchMute.setChecked(prefs.getBoolean(AdSkipAccessibilityService.KEY_MUTE_ENABLED, true));
        switchShield.setChecked(prefs.getBoolean(AdSkipAccessibilityService.KEY_SHIELD_ENABLED, true));

        updateTileAppearance(tileAutoSkip, iconAutoSkip, switchAutoSkip.isChecked(), false);
        updateTileAppearance(tileMute, iconMute, switchMute.isChecked(), false);
        updateTileAppearance(tileShield, iconShield, switchShield.isChecked(), false);

        switchAutoSkip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_SKIP_ENABLED, isChecked).apply();
            onSwitchToggled(switchAutoSkip, tileAutoSkip, iconAutoSkip, cardAutoSkip, isChecked);
        });

        switchMute.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_MUTE_ENABLED, isChecked).apply();
            onSwitchToggled(switchMute, tileMute, iconMute, cardMute, isChecked);
        });

        switchShield.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_SHIELD_ENABLED, isChecked).apply();
            onSwitchToggled(switchShield, tileShield, iconShield, cardShield, isChecked);
        });

        // Facebook Switch
        if (switchFbShield != null) {
            boolean fbEnabled = prefs.getBoolean(AdSkipAccessibilityService.KEY_FB_SHIELD_ENABLED, false);
            switchFbShield.setChecked(fbEnabled);
            updateTileAppearance(tileFbShield, iconFbShield, fbEnabled, false);

            switchFbShield.setOnClickListener(v -> {
                boolean targetChecked = switchFbShield.isChecked();
                if (targetChecked) {
                    switchFbShield.setChecked(false);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.fb_dialog_title)
                            .setMessage(R.string.fb_dialog_message)
                            .setPositiveButton(R.string.fb_dialog_enable, (dialog, which) -> {
                                switchFbShield.setChecked(true);
                                prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_FB_SHIELD_ENABLED, true).apply();
                                onSwitchToggled(switchFbShield, tileFbShield, iconFbShield, cardFbShield, true);
                            })
                            .setNegativeButton(R.string.fb_dialog_cancel, null)
                            .show();
                } else {
                    prefs.edit().putBoolean(AdSkipAccessibilityService.KEY_FB_SHIELD_ENABLED, false).apply();
                    onSwitchToggled(switchFbShield, tileFbShield, iconFbShield, cardFbShield, false);
                }
            });
        }
    }

    private void setupDnsSwitch() {
        if (switchDns == null) return;
        syncDnsSwitch();
        switchDns.setOnClickListener(v -> {
            boolean wantOn = switchDns.isChecked();
            if (wantOn) {
                switchDns.setChecked(false); // Revert until dialog accepted
                new AlertDialog.Builder(this)
                        .setTitle(R.string.vpn_dialog_title)
                        .setMessage(R.string.vpn_dialog_message)
                        .setPositiveButton(R.string.vpn_dialog_enable, (dialog, which) -> checkNotificationPermissionAndStartVpn())
                        .setNegativeButton(R.string.vpn_dialog_cancel, null)
                        .show();
            } else {
                HushVpnService.stop(this);
                onSwitchToggled(switchDns, tileDns, iconDns, cardDns, false);
                syncDnsCaption(false);
            }
        });
    }

    private void checkNotificationPermissionAndStartVpn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            }
        }
        launchVpnConsentOrStart();
    }

    private void launchVpnConsentOrStart() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            vpnPermissionLauncher.launch(consent);
        } else {
            HushVpnService.start(this);
            if (switchDns != null) switchDns.setChecked(true);
            onSwitchToggled(switchDns, tileDns, iconDns, cardDns, true);
            syncDnsCaption(true);
        }
    }

    private void syncDnsSwitch() {
        if (switchDns == null) return;
        boolean on = HushVpnService.running
                && prefs.getBoolean(HushVpnService.KEY_DNS_ENABLED, false);
        switchDns.setChecked(on);
        updateTileAppearance(tileDns, iconDns, on, false);
        syncDnsCaption(on);
    }

    private void syncDnsCaption(boolean on) {
        if (captionDnsStatus == null) return;
        if (on) {
            int blocked = prefs.getInt(HushVpnService.KEY_DNS_BLOCKED_TOTAL, 0);
            if (blocked > 0) {
                captionDnsStatus.setText(getString(R.string.caption_dns_on) + " • " + blocked + " filtered");
            } else {
                captionDnsStatus.setText(R.string.caption_dns_on);
            }
            captionDnsStatus.setTextColor(ContextCompat.getColor(this, R.color.color_lime));
        } else {
            captionDnsStatus.setText(R.string.caption_dns_off);
            captionDnsStatus.setTextColor(ContextCompat.getColor(this, R.color.color_blush_70));
        }
    }

    private void onSwitchToggled(View switchView, View tileView, ImageView iconView, View cardView, boolean isChecked) {
        switchView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        updateTileAppearance(tileView, iconView, isChecked, true);

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

    private void updateTileAppearance(View tile, ImageView icon, boolean isChecked, boolean animate) {
        if (tile == null) return;
        int targetBg = isChecked ? R.drawable.bg_tile_active : R.drawable.bg_tile_outline;
        int iconColor = ContextCompat.getColor(this, isChecked ? R.color.color_midnight : R.color.color_blush);

        if (!animate || isAnimationDisabled()) {
            tile.setBackgroundResource(targetBg);
            if (icon != null) icon.setColorFilter(iconColor);
        } else {
            tile.animate().cancel();
            tile.setAlpha(0.4f);
            tile.setScaleX(0.92f);
            tile.setScaleY(0.92f);
            tile.setBackgroundResource(targetBg);
            if (icon != null) icon.setColorFilter(iconColor);
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
        syncDnsSwitch();
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
            } else if (HushVpnService.KEY_DNS_BLOCKED_TOTAL.equals(key)
                    || HushVpnService.KEY_DNS_ENABLED.equals(key)) {
                syncDnsSwitch();
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

        updateHeroAppearance(stateKey);
        updateStepperVisibility(!serviceEnabled);

        if (heroCard != null) {
            heroCard.setAlpha(1f);
            heroCard.setTranslationY(0f);
        }

        if (!stateChanged || isAnimationDisabled()) {
            statusLabel.setText(labelRes);
            statusLabel.setAlpha(1f);
            statusLabel.setTranslationY(0f);
            statusText.setText(descRes);
            statusDot.setBackgroundTintList(ColorStateList.valueOf(targetColor));
        } else {
            animateHeroStateTransition(labelRes, descRes, targetColor);
        }

        if ("active".equals(stateKey)) {
            startStatusPulse();
        } else {
            stopStatusPulse();
        }
    }

    private void updateHeroAppearance(String stateKey) {
        if (heroCard == null) return;
        boolean isActive = "active".equals(stateKey);
        boolean isPaused = "paused".equals(stateKey);

        if (isActive) {
            heroCard.setBackgroundResource(R.drawable.bg_hero_active);
            if (heroGlow != null) heroGlow.setVisibility(View.VISIBLE);
            statusLabel.setTextColor(ContextCompat.getColor(this, R.color.color_midnight));
            statusText.setTextColor(ContextCompat.getColor(this, R.color.color_midnight));
            if (heroServiceLabel != null) {
                heroServiceLabel.setTextColor(ContextCompat.getColor(this, R.color.color_midnight));
            }
            enableButton.setText(R.string.status_active);
            enableButton.setBackgroundResource(R.drawable.bg_button_midnight);
            enableButton.setTextColor(ContextCompat.getColor(this, R.color.color_violet));
        } else if (isPaused) {
            heroCard.setBackgroundResource(R.drawable.bg_hero_paused);
            if (heroGlow != null) heroGlow.setVisibility(View.GONE);
            statusLabel.setTextColor(ContextCompat.getColor(this, R.color.color_blush));
            statusText.setTextColor(ContextCompat.getColor(this, R.color.color_blush_70));
            if (heroServiceLabel != null) {
                heroServiceLabel.setTextColor(ContextCompat.getColor(this, R.color.color_blush));
            }
            enableButton.setText(R.string.action_resume_protection);
            enableButton.setBackgroundResource(R.drawable.bg_button);
            enableButton.setTextColor(ContextCompat.getColor(this, R.color.color_midnight));
        } else {
            heroCard.setBackgroundResource(R.drawable.bg_hero_offline);
            if (heroGlow != null) heroGlow.setVisibility(View.VISIBLE);
            statusLabel.setTextColor(ContextCompat.getColor(this, R.color.color_blush));
            statusText.setTextColor(ContextCompat.getColor(this, R.color.color_blush_70));
            if (heroServiceLabel != null) {
                heroServiceLabel.setTextColor(ContextCompat.getColor(this, R.color.color_blush));
            }
            enableButton.setText(R.string.action_open_settings);
            enableButton.setBackgroundResource(R.drawable.bg_button);
            enableButton.setTextColor(ContextCompat.getColor(this, R.color.color_midnight));
        }
    }

    private void updateStepperVisibility(boolean show) {
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
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationCancel(Animator animation) {
                                setupHelpCard.setAlpha(1f);
                                setupHelpCard.setTranslationY(0f);
                            }
                        })
                        .start();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (helpAppInfoButton != null) helpAppInfoButton.setVisibility(View.VISIBLE);
                if (stepperBadge != null) stepperBadge.setText("STEP 1 & 2 OF 3");
                if (stepperAndroidNotice != null) stepperAndroidNotice.setVisibility(View.VISIBLE);
                if (stepperInstruction != null) {
                    stepperInstruction.setText("1. Open accessibility settings, tap Hush Service, and press OK on the prompt.\n2. Open App info -> tap 3 dots (⋮) -> Allow restricted settings (confirm with PIN/fingerprint).\n3. Turn Hush Service ON.");
                }
            } else {
                if (helpAppInfoButton != null) helpAppInfoButton.setVisibility(View.GONE);
                if (stepperBadge != null) stepperBadge.setText("STEP 1 OF 2");
                if (stepperAndroidNotice != null) stepperAndroidNotice.setVisibility(View.GONE);
                if (stepperInstruction != null) {
                    stepperInstruction.setText("1. Open accessibility settings above.\n2. Select Hush Service and turn it ON to activate protection.");
                }
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
        float slidePx = 12f * getResources().getDisplayMetrics().density;

        statusLabel.animate().cancel();
        statusLabel.animate()
                .alpha(0f)
                .translationY(-slidePx)
                .setDuration(150)
                .setInterpolator(fastOutSlowIn)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationCancel(Animator animation) {
                        statusLabel.setText(labelRes);
                        statusLabel.setAlpha(1f);
                        statusLabel.setTranslationY(0f);
                    }
                })
                .withEndAction(() -> {
                    statusLabel.setText(labelRes);
                    statusLabel.setTranslationY(slidePx);
                    statusLabel.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(150)
                            .setInterpolator(fastOutSlowIn)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationCancel(Animator animation) {
                                    statusLabel.setAlpha(1f);
                                    statusLabel.setTranslationY(0f);
                                }
                            })
                            .start();
                })
                .start();

        statusText.setText(descRes);

        if (colorTransitionAnimator != null) {
            colorTransitionAnimator.cancel();
        }
        int startColor = ContextCompat.getColor(this, R.color.color_blush);
        colorTransitionAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, targetColor);
        colorTransitionAnimator.setDuration(300);
        colorTransitionAnimator.setInterpolator(fastOutSlowIn);
        colorTransitionAnimator.addUpdateListener(anim -> {
            int c = (int) anim.getAnimatedValue();
            statusDot.setBackgroundTintList(ColorStateList.valueOf(c));
        });
        colorTransitionAnimator.start();

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
        View header = findViewById(R.id.headerContainer);
        View hero = findViewById(R.id.heroCard);
        View stats = findViewById(R.id.statsContainer);
        View help = findViewById(R.id.setupHelpCard);
        View card1 = findViewById(R.id.cardAutoSkip);
        View card2 = findViewById(R.id.cardMute);
        View card3 = findViewById(R.id.cardShield);
        View card4 = findViewById(R.id.cardDns);
        View cardFb = findViewById(R.id.cardFbShield);
        View footer = findViewById(R.id.footerContainer);

        View[] views = new View[]{header, hero, stats, help, card1, card2, card3, card4, cardFb, footer};

        if (entrancePlayed || isAnimationDisabled() || rootContent == null) {
            for (View v : views) {
                if (v != null) {
                    v.setAlpha(1f);
                    v.setTranslationY(0f);
                }
            }
            return;
        }
        entrancePlayed = true;

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
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationCancel(Animator animation) {
                            v.setAlpha(1f);
                            v.setTranslationY(0f);
                        }
                    })
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
