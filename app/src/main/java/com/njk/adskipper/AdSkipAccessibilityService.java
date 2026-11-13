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
 * Scoped to the YouTube app only.
 */
@SuppressLint("AccessibilityPolicy")
public class AdSkipAccessibilityService extends AccessibilityService {

    private static final String TAG = "AdSkipService";

    // YouTube View IDs to check first (skip click and ad presence)
    private static final String[] SKIP_VIEW_IDS = {
            "com.google.android.youtube:id/skip_ad_button",
            "com.google.android.youtube:id/skip_ad_button_container"
    };

    // View IDs that only exist while an ad is on screen / that give the player bounds.
    private static final String[] AD_PRESENCE_IDS = {
            "com.google.android.youtube:id/ad_progress_text",
            "com.google.android.youtube:id/ad_badge",
            "com.google.android.youtube:id/ad_attribution"
    };
    private static final String[] PLAYER_VIEW_IDS = {
            "com.google.android.youtube:id/watch_player",
            "com.google.android.youtube:id/player_view"
    };

    // Text fallbacks are matched EXACTLY (never as a substring) so a video titled
    // "How to skip ads" can never be mistaken for the skip button.
    private static final java.util.regex.Pattern SKIP_TEXT = java.util.regex.Pattern.compile(
            "^(skip( ad| ads)?|skip ad[s]? ›|skip ad[s]? >)$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern VISIT_SITE_TEXT = java.util.regex.Pattern.compile(
            "^visit site$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern AD_TEXT = java.util.regex.Pattern.compile(
            "^(visit advertiser|ad( · .*| [0-9]+ of [0-9]+.*)?|sponsored)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    // Throttles & cooldowns
    private static final long CLICK_COOLDOWN_MS = 800;
    private long lastClickTimeMs = 0L;

    private static final long SCAN_THROTTLE_MS = 250;
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

    // Counter keys
    public static final String KEY_DIAG = "diag_text";
    private String lastDiag = "";

    private void setDiag(String d) {
        if (d.equals(lastDiag)) return;
        lastDiag = d;
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DIAG, d).apply();
    }

    public static final String KEY_SAVED_VOLUME = "saved_volume_while_muted";

    public static final String KEY_SKIPPED_TODAY = "skipped_today";
    public static final String KEY_SKIPPED_TOTAL = "skipped_total";
    public static final String KEY_SKIPPED_DAY_EPOCH = "skipped_day_epoch";

    // YouTube Shield overlay
    private WindowManager windowManager;
    private FrameLayout shieldView;
    private boolean isShieldShowing = false;
    private int consecutiveScansNoAd = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final long WATCHDOG_DELAY_MS = 4000L;

    private final Runnable shieldWatchdogRunnable = () -> {
        Log.d(TAG, "Watchdog: no ad signal for 4s, hiding shield and restoring volume");
        hideShield();
        restoreVolume();
    };

    private AccessibilityButtonController.AccessibilityButtonCallback accessibilityButtonCallback;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            handleEvent(event);
        } catch (Throwable t) {
            Log.e(TAG, "event handling failed", t);
            setDiag("ERROR: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return;
        }

        CharSequence pkgName = event.getPackageName();
        String currentPackage = pkgName != null ? pkgName.toString() : "";
        if (!"com.google.android.youtube".equals(currentPackage)) return;

        // Cheapest checks first: throttle before touching prefs or the node tree.
        long now = SystemClock.elapsedRealtime();
        if (now - lastScanTimeMs < SCAN_THROTTLE_MS) return;
        lastScanTimeMs = now;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY_PAUSED, false)) {
            restoreVolume();
            hideShield();
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // The active window may belong to another app (PiP, split screen).
            CharSequence rootPkg = root.getPackageName();
            if (rootPkg == null || !currentPackage.contentEquals(rootPkg)) return;

            boolean skipEnabled = prefs.getBoolean(KEY_SKIP_ENABLED, true);
            boolean muteEnabled = prefs.getBoolean(KEY_MUTE_ENABLED, true);
            boolean shieldEnabled = prefs.getBoolean(KEY_SHIELD_ENABLED, true);

            boolean adShowing = isAdCurrentlyShowing(root);
            setDiag("YouTube scan: ad=" + adShowing + " (" + adReason + ")");

            if (adShowing) {
                consecutiveScansNoAd = 0;
                mainHandler.removeCallbacks(shieldWatchdogRunnable);
                mainHandler.postDelayed(shieldWatchdogRunnable, WATCHDOG_DELAY_MS);

                if (shieldEnabled) {
                    showOrUpdateShield(computeShieldBounds(root));
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

            if (skipEnabled && adShowing && now - lastClickTimeMs >= CLICK_COOLDOWN_MS) {
                findAndClickSkipButton(root, now);
            }
        } finally {
            root.recycle();
        }
    }

    // ── YOUTUBE AD LOGIC ───────────────────────────────────────────────────────

    private boolean hasAnyViewId(AccessibilityNodeInfo root, String[] ids) {
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) {
                recycleNodeList(nodes);
                return true;
            }
        }
        return false;
    }

    /** Returns the first node whose own text/description exactly matches the pattern. */
    private AccessibilityNodeInfo findExactText(AccessibilityNodeInfo root, String query,
                                                java.util.regex.Pattern exact) {
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(query);
        if (matches == null) return null;
        AccessibilityNodeInfo found = null;
        for (AccessibilityNodeInfo m : matches) {
            if (m == null) continue;
            if (found == null) {
                CharSequence t = m.getText();
                CharSequence d = m.getContentDescription();
                if ((t != null && exact.matcher(t.toString().trim()).matches())
                        || (d != null && exact.matcher(d.toString().trim()).matches())) {
                    found = m;
                    continue;
                }
            }
            m.recycle();
        }
        return found;
    }

    private String adReason = "none";

    private boolean isAdCurrentlyShowing(AccessibilityNodeInfo root) {
        adReason = "none";
        if (root == null) return false;
        if (hasAnyViewId(root, SKIP_VIEW_IDS)) { adReason = "skip-id"; return true; }
        if (hasAnyViewId(root, AD_PRESENCE_IDS)) { adReason = "ad-id"; return true; }

        AccessibilityNodeInfo n = findExactText(root, "Visit advertiser", AD_TEXT);
        if (n != null) { n.recycle(); adReason = "visit-advertiser"; return true; }
        n = findExactText(root, "Skip ad", SKIP_TEXT);
        if (n != null) { n.recycle(); adReason = "skip-text"; return true; }

        // Sponsored sheet under the player: needs BOTH labels so feed ads don't trigger it.
        AccessibilityNodeInfo sp = findExactText(root, "Sponsored", AD_TEXT);
        if (sp != null) {
            AccessibilityNodeInfo vs = findExactText(root, "Visit site", VISIT_SITE_TEXT);
            sp.recycle();
            if (vs != null) { vs.recycle(); adReason = "sponsored+visit-site"; return true; }
        }
        return false;
    }

    /** Player bounds, extended down over the sponsored sheet (the card under the video) if present. */
    private Rect computeShieldBounds(AccessibilityNodeInfo root) {
        Rect player = findPlayerBounds(root);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int playerBottom = player != null ? player.bottom
                : getStatusBarHeight() + (int) (dm.widthPixels * 9f / 16f);

        // The video also carries a small "Sponsored" tag; the sheet's label sits below the video.
        boolean sheet = false;
        List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText("Sponsored");
        if (matches != null) {
            Rect r = new Rect();
            for (AccessibilityNodeInfo m : matches) {
                if (m == null) continue;
                CharSequence t = m.getText();
                CharSequence d = m.getContentDescription();
                boolean exact = (t != null && AD_TEXT.matcher(t.toString().trim()).matches())
                        || (d != null && AD_TEXT.matcher(d.toString().trim()).matches());
                if (exact) {
                    m.getBoundsInScreen(r);
                    if (r.top >= playerBottom - 4) sheet = true;
                }
            }
            recycleNodeList(matches);
        }
        if (!sheet) return player;

        Rect window = new Rect();
        root.getBoundsInScreen(window);
        int top = player != null ? player.top : 0;
        return new Rect(window.left, top, window.right, window.bottom);
    }

    private Rect findPlayerBounds(AccessibilityNodeInfo root) {
        for (String id : PLAYER_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null || nodes.isEmpty()) continue;
            Rect r = new Rect();
            nodes.get(0).getBoundsInScreen(r);
            recycleNodeList(nodes);
            if (r.width() > 0 && r.height() > 0) return r;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node, long eventTimeMs, String how) {
        AccessibilityNodeInfo clickable = findClickableSelfOrParent(node);
        if (clickable == null) return false;
        boolean ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (ok) {
            Log.d(TAG, "Skip clicked via " + how);
            lastClickTimeMs = eventTimeMs;
            incrementSkipCounters();
        }
        return ok;
    }

    private void findAndClickSkipButton(AccessibilityNodeInfo root, long eventTimeMs) {
        if (root == null) return;

        for (String id : SKIP_VIEW_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes == null || nodes.isEmpty()) continue;
            boolean done = false;
            for (AccessibilityNodeInfo node : nodes) {
                if (clickNode(node, eventTimeMs, "view id " + id)) {
                    done = true;
                    break;
                }
            }
            recycleNodeList(nodes);
            if (done) return;
        }

        AccessibilityNodeInfo match = findExactText(root, "Skip", SKIP_TEXT);
        if (match != null) {
            clickNode(match, eventTimeMs, "exact text");
            match.recycle();
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
            // Persist first so a crash or kill can never leave the phone silent.
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_SAVED_VOLUME, savedVolume).commit();
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            mutedByUs = true;
        } else if (!adShowing && mutedByUs) {
            restoreVolume();
        }
    }

    private void restoreVolume() {
        if (audioManager != null && mutedByUs && savedVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);
        }
        if (mutedByUs) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove(KEY_SAVED_VOLUME).apply();
        }
        mutedByUs = false;
        savedVolume = -1;
    }

    /** If the service died while muted, give the volume back on next start. */
    private void recoverVolumeAfterCrash() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int saved = sp.getInt(KEY_SAVED_VOLUME, -1);
        if (saved >= 0 && audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
            sp.edit().remove(KEY_SAVED_VOLUME).apply();
        }
    }

    // ── YOUTUBE SHIELD OVERLAY ─────────────────────────────────────────────────

    private void showOrUpdateShield(Rect playerBounds) {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) return;

        WindowManager.LayoutParams lp = createShieldLayoutParams(playerBounds);

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

    private WindowManager.LayoutParams createShieldLayoutParams(Rect playerBounds) {
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
        if (playerBounds != null) {
            // Cover exactly the player, whatever the mode (portrait, fullscreen, tablet).
            lp.x = playerBounds.left;
            lp.y = playerBounds.top;
            lp.width = playerBounds.width();
            lp.height = playerBounds.height();
        } else {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = height;
        }

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
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        recoverVolumeAfterCrash();
        registerAccessibilityButtonCallback();
        Log.d(TAG, "Hush service connected");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterAccessibilityButtonCallback();
        restoreVolume();
        hideShield();
    }
}
