package com.njk.adskipper;

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/**
 * AdSkipAccessibilityService
 * --------------------------
 * Core background accessibility engine for Hush.
 * Watches the YouTube app's screen for ad signals: taps "Skip ad", mutes
 * media audio during promotion intervals, and overlays an opaque touch-through
 * shield over unskippable ads.
 * Also optionally observes Facebook (com.facebook.katana) for sponsored post suppression.
 */
@SuppressLint("AccessibilityPolicy")
public class AdSkipAccessibilityService extends AccessibilityService {

    private static final String TAG = "AdSkipService";

    // YouTube View IDs to check first (skip click and ad presence)
    private static final String[] SKIP_VIEW_IDS = {
            "com.google.android.youtube:id/skip_ad_button",
            "com.google.android.youtube:id/skip_ad_button_container"
    };

    // Text labels: findAccessibilityNodeInfosByText is a case-insensitive substring match.
    private static final String[] SKIP_LABELS = {
            "Skip ad"
    };

    private static final String[] AD_PRESENCE_LABELS = {
            "Skip ad",
            "Visit advertiser"
    };

    // Throttles & cooldowns
    private static final long CLICK_COOLDOWN_MS = 800;
    private long lastClickTimeMs = 0L;

    private static final long SCAN_THROTTLE_MS = 200;
    private long lastScanTimeMs = 0L;

    private AudioManager audioManager;
    private boolean mutedByUs = false;
    private int savedVolume = -1;

    // Public Preference Constants
    public static final String PREFS_NAME = "ad_skipper_prefs";
    public static final String KEY_PAUSED = "paused";
    public static final String KEY_SKIP_ENABLED = "skip_enabled";
    public static final String KEY_MUTE_ENABLED = "mute_enabled";
    public static final String KEY_SHIELD_ENABLED = "shield_enabled";
    public static final String KEY_FB_SHIELD_ENABLED = "fb_shield_enabled";

    // Counter keys
    public static final String KEY_SKIPPED_TODAY = "skipped_today";
    public static final String KEY_SKIPPED_TOTAL = "skipped_total";
    public static final String KEY_SKIPPED_DAY_EPOCH = "skipped_day_epoch";

    // YouTube Shield overlay
    private WindowManager windowManager;
    private FrameLayout shieldView;
    private boolean isShieldShowing = false;
    private int consecutiveScansNoAd = 0;

    // Facebook Shield overlay
    private FrameLayout fbShieldView;
    private boolean isFbShieldShowing = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long WATCHDOG_DELAY_MS = 4000L;
    private static final long FB_WATCHDOG_DELAY_MS = 1500L;

    private final Runnable shieldWatchdogRunnable = () -> {
        Log.d(TAG, "Shield watchdog timeout reached (4s no ad signal), hiding shield");
        hideShield();
    };

    private final Runnable fbWatchdogRunnable = () -> {
        Log.d(TAG, "FB shield watchdog timeout reached (1.5s no signal), hiding FB shield");
        hideFbShield();
    };

    private AccessibilityButtonController.AccessibilityButtonCallback accessibilityButtonCallback;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }

        CharSequence pkgName = event.getPackageName();
        String currentPackage = pkgName != null ? pkgName.toString() : "";

        if (isPaused()) {
            restoreVolume();
            hideShield();
            hideFbShield();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            long now = SystemClock.elapsedRealtime();

            // Handle Facebook Sponsored Posts (Experimental)
            if ("com.facebook.katana".equals(currentPackage)) {
                // If YouTube shield is showing, clear it
                hideShield();

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                boolean fbShieldEnabled = prefs.getBoolean(KEY_FB_SHIELD_ENABLED, false);

                if (fbShieldEnabled) {
                    handleFacebookScan(root);
                } else {
                    hideFbShield();
                }
                return;
            }

            // Outside Facebook, clear FB shield
            hideFbShield();

            // Handle YouTube Scanning
            if (now - lastScanTimeMs < SCAN_THROTTLE_MS) {
                return;
            }
            lastScanTimeMs = now;

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean skipEnabled = prefs.getBoolean(KEY_SKIP_ENABLED, true);
            boolean muteEnabled = prefs.getBoolean(KEY_MUTE_ENABLED, true);
            boolean shieldEnabled = prefs.getBoolean(KEY_SHIELD_ENABLED, true);

            boolean adShowing = isAdCurrentlyShowing(root);

            if (adShowing) {
                consecutiveScansNoAd = 0;
                mainHandler.removeCallbacks(shieldWatchdogRunnable);
                mainHandler.postDelayed(shieldWatchdogRunnable, WATCHDOG_DELAY_MS);

                if (shieldEnabled) {
                    showOrUpdateShield();
                } else {
                    hideShield();
                }
            } else {
                consecutiveScansNoAd++;
                if (consecutiveScansNoAd >= 2) {
                    hideShield();
                }
            }

            if (muteEnabled) {
                updateMuteState(adShowing);
            } else {
                restoreVolume();
            }

            if (skipEnabled) {
                if (now - lastClickTimeMs >= CLICK_COOLDOWN_MS) {
                    findAndClickSkipButton(root, now);
                }
            }
        } finally {
            root.recycle();
        }
    }

    // ── FACEBOOK SPONSORED POST HIDER ──────────────────────────────────────────

    private void handleFacebookScan(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> sponsoredNodes = root.findAccessibilityNodeInfosByText("Sponsored");
        if (sponsoredNodes == null || sponsoredNodes.isEmpty()) {
            mainHandler.removeCallbacks(fbWatchdogRunnable);
            mainHandler.postDelayed(fbWatchdogRunnable, FB_WATCHDOG_DELAY_MS);
            return;
        }

        try {
            for (AccessibilityNodeInfo node : sponsoredNodes) {
                if (node == null) continue;
                // Confirm text equals or contains "Sponsored"
                CharSequence text = node.getText();
                CharSequence desc = node.getContentDescription();
                boolean matches = (text != null && text.toString().contains("Sponsored"))
                        || (desc != null && desc.toString().contains("Sponsored"));

                if (!matches) continue;

                // Find enclosing feed item container
                AccessibilityNodeInfo feedItem = findFeedContainer(node);
                if (feedItem != null) {
                    Rect bounds = new Rect();
                    feedItem.getBoundsInScreen(bounds);

                    DisplayMetrics dm = getResources().getDisplayMetrics();
                    if (bounds.width() > 0 && bounds.height() > 0 && bounds.bottom > 0 && bounds.top < dm.heightPixels) {
                        mainHandler.removeCallbacks(fbWatchdogRunnable);
                        showOrUpdateFbShield(bounds);
                        feedItem.recycle();
                        return;
                    }
                    feedItem.recycle();
                }
            }
        } finally {
            recycleNodeList(sponsoredNodes);
        }

        mainHandler.removeCallbacks(fbWatchdogRunnable);
        mainHandler.postDelayed(fbWatchdogRunnable, FB_WATCHDOG_DELAY_MS);
    }

    private AccessibilityNodeInfo findFeedContainer(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        AccessibilityNodeInfo bestCandidate = null;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        while (current != null && depth < 8) {
            Rect r = new Rect();
            current.getBoundsInScreen(r);
            // Feed container spans most of the screen width and reasonable post height
            if (r.width() >= (int) (dm.widthPixels * 0.85f) && r.height() >= (int) (120 * dm.density)) {
                bestCandidate = current;
                break;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (current != node && current != bestCandidate) {
                current.recycle();
            }
            current = parent;
            depth++;
        }
        return bestCandidate;
    }

    private void showOrUpdateFbShield(Rect bounds) {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.format = PixelFormat.OPAQUE;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = bounds.left;
        lp.y = bounds.top;
        lp.width = bounds.width();
        lp.height = bounds.height();

        try {
            if (!isFbShieldShowing || fbShieldView == null) {
                if (fbShieldView == null) {
                    fbShieldView = createFbShieldView();
                }
                windowManager.addView(fbShieldView, lp);
                isFbShieldShowing = true;
            } else {
                windowManager.updateViewLayout(fbShieldView, lp);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error updating FB shield view", e);
        }
    }

    private FrameLayout createFbShieldView() {
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.parseColor("#03012A")); // Midnight blackout
        layout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        TextView label = new TextView(this);
        label.setText("SPONSORED POST HIDDEN");
        label.setTextColor(Color.parseColor("#B8ADBA")); // Blush 70%
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setLetterSpacing(0.12f);
        label.setIncludeFontPadding(false);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER;
        layout.addView(label, lp);

        return layout;
    }

    private void hideFbShield() {
        mainHandler.removeCallbacks(fbWatchdogRunnable);
        if (isFbShieldShowing && fbShieldView != null && windowManager != null) {
            try {
                windowManager.removeView(fbShieldView);
            } catch (RuntimeException e) {
                Log.e(TAG, "Error removing FB shield view", e);
            }
        }
        isFbShieldShowing = false;
    }

    // ── YOUTUBE AD LOGIC ───────────────────────────────────────────────────────

    private boolean isAdCurrentlyShowing(AccessibilityNodeInfo root) {
        if (root == null) return false;

        for (String id : SKIP_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                for (AccessibilityNodeInfo n : nodes) {
                    if (n != null) n.recycle();
                }
                return true;
            }
        }

        for (String label : AD_PRESENCE_LABELS) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            if (matches == null) continue;
            boolean found = !matches.isEmpty();
            for (AccessibilityNodeInfo m : matches) {
                if (m != null) m.recycle();
            }
            if (found) return true;
        }
        return false;
    }

    private void findAndClickSkipButton(AccessibilityNodeInfo root, long eventTimeMs) {
        if (root == null) return;

        for (String id : SKIP_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null || nodes.isEmpty()) continue;

            for (AccessibilityNodeInfo node : nodes) {
                AccessibilityNodeInfo clickable = findClickableSelfOrParent(node);
                if (clickable != null) {
                    boolean success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    if (success) {
                        Log.d(TAG, "Skip clicked via view id " + id);
                        lastClickTimeMs = eventTimeMs;
                        incrementSkipCounters();
                        recycleNodeList(nodes);
                        return;
                    }
                }
            }
            recycleNodeList(nodes);
        }

        for (String label : SKIP_LABELS) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            if (matches == null || matches.isEmpty()) continue;

            for (AccessibilityNodeInfo match : matches) {
                AccessibilityNodeInfo clickable = findClickableSelfOrParent(match);
                if (clickable != null) {
                    boolean success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.d(TAG, "Attempted skip click via text '" + label + "', success=" + success);
                    if (success) {
                        lastClickTimeMs = eventTimeMs;
                        incrementSkipCounters();
                        recycleNodeList(matches);
                        return;
                    }
                }
            }
            recycleNodeList(matches);
        }
    }

    private void incrementSkipCounters() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long todayEpoch = getStartOfDayEpoch();
        long storedEpoch = sp.getLong(KEY_SKIPPED_DAY_EPOCH, 0L);

        int todayCount = sp.getInt(KEY_SKIPPED_TODAY, 0);
        int totalCount = sp.getInt(KEY_SKIPPED_TOTAL, 0);

        if (todayEpoch != storedEpoch) {
            todayCount = 1;
        } else {
            todayCount++;
        }
        totalCount++;

        sp.edit()
                .putLong(KEY_SKIPPED_DAY_EPOCH, todayEpoch)
                .putInt(KEY_SKIPPED_TODAY, todayCount)
                .putInt(KEY_SKIPPED_TOTAL, totalCount)
                .apply();
        Log.d(TAG, "Incremented skip stats: today=" + todayCount + ", total=" + totalCount);
    }

    private long getStartOfDayEpoch() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void recycleNodeList(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo info : list) {
            if (info != null) info.recycle();
        }
    }

    private AccessibilityNodeInfo findClickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 6) {
            if (current.isClickable()) {
                return current;
            }
            current = current.getParent();
            depth++;
        }
        return null;
    }

    private void updateMuteState(boolean adShowing) {
        if (audioManager == null) return;

        if (adShowing && !mutedByUs) {
            savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            mutedByUs = true;
            Log.d(TAG, "Ad detected, muted media volume (was " + savedVolume + ")");
        } else if (!adShowing && mutedByUs) {
            restoreVolume();
        }
    }

    private void restoreVolume() {
        if (audioManager != null && mutedByUs && savedVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
            Log.d(TAG, "Ad gone, restored media volume to " + savedVolume);
        }
        mutedByUs = false;
        savedVolume = -1;
    }

    // ── YOUTUBE SHIELD OVERLAY ─────────────────────────────────────────────────

    private void showOrUpdateShield() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        WindowManager.LayoutParams lp = createShieldLayoutParams();

        try {
            if (!isShieldShowing || shieldView == null) {
                if (shieldView == null) {
                    shieldView = createShieldView();
                }
                windowManager.addView(shieldView, lp);
                isShieldShowing = true;
                Log.d(TAG, "Shield view added");
            } else {
                windowManager.updateViewLayout(shieldView, lp);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Error adding or updating shield view", e);
        }
    }

    private void hideShield() {
        mainHandler.removeCallbacks(shieldWatchdogRunnable);
        consecutiveScansNoAd = 0;
        if (isShieldShowing && shieldView != null && windowManager != null) {
            try {
                windowManager.removeView(shieldView);
                Log.d(TAG, "Shield view removed");
            } catch (RuntimeException e) {
                Log.e(TAG, "Error removing shield view", e);
            }
        }
        isShieldShowing = false;
    }

    private FrameLayout createShieldView() {
        FrameLayout layout = new FrameLayout(this);
        // Midnight blackout (#03012A)
        layout.setBackgroundColor(Color.parseColor("#03012A"));
        layout.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        TextView label = new TextView(this);
        label.setText("HUSH");
        // Blush 70% (#B8ADBA)
        label.setTextColor(Color.parseColor("#B8ADBA"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setLetterSpacing(0.14f);
        label.setIncludeFontPadding(false);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER;
        layout.addView(label, lp);

        return layout;
    }

    private WindowManager.LayoutParams createShieldLayoutParams() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int orientation = getResources().getConfiguration().orientation;

        int height;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            height = WindowManager.LayoutParams.MATCH_PARENT;
        } else {
            int statusBarHeight = getStatusBarHeight();
            height = statusBarHeight + (int) (dm.widthPixels * 9f / 16f);
        }

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        lp.format = PixelFormat.OPAQUE;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = height;

        return lp;
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return (int) (24 * getResources().getDisplayMetrics().density);
    }

    // ── ACCESSIBILITY BUTTON CALLBACK & LIFECYCLE ──────────────────────────────

    private void registerAccessibilityButtonCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        accessibilityButtonCallback = new AccessibilityButtonController.AccessibilityButtonCallback() {
            @Override
            public void onClicked(AccessibilityButtonController controller) {
                boolean nowPaused = !isPaused();
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_PAUSED, nowPaused)
                        .apply();

                if (nowPaused) {
                    restoreVolume();
                    hideShield();
                    hideFbShield();
                }
                Log.d(TAG, "Accessibility button tapped, paused=" + nowPaused);
            }

            @Override
            public void onAvailabilityChanged(AccessibilityButtonController controller, boolean available) {
                Log.d(TAG, "Accessibility button available=" + available);
            }
        };

        getAccessibilityButtonController().registerAccessibilityButtonCallback(accessibilityButtonCallback);
    }

    private void unregisterAccessibilityButtonCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || accessibilityButtonCallback == null) {
            return;
        }
        getAccessibilityButtonController().unregisterAccessibilityButtonCallback(accessibilityButtonCallback);
        accessibilityButtonCallback = null;
    }

    private boolean isPaused() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_PAUSED, false);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
        restoreVolume();
        hideShield();
        hideFbShield();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        registerAccessibilityButtonCallback();
        Log.d(TAG, "Hush service connected");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterAccessibilityButtonCallback();
        restoreVolume();
        hideShield();
        hideFbShield();
    }
}
