package com.njk.adskipper;

import android.accessibilityservice.AccessibilityService;
import android.annotation.SuppressLint;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * AdSkipAccessibilityService
 * --------------------------
 * Watches the YouTube app's screen (and only YouTube's, see
 * accessibility_service_config.xml -> android:packageNames) for a node
 * whose text/content-description matches known "Skip Ad" labels, and
 * performs a click on it. It also mutes device media volume for as long
 * as ad-related UI is on screen, and restores it afterwards.
 *
 * This does NOT:
 *  - read or modify YouTube's app code
 *  - intercept or modify network traffic
 *  - remove/block ads that have no skip button (unskippable ads)
 *  - collect or transmit any data off-device
 *
 * It only automates something a human could already do by tapping the
 * screen (or the volume buttons) themselves, once the button becomes
 * visible.
 */
@SuppressLint("AccessibilityPolicy")
public class AdSkipAccessibilityService extends AccessibilityService {

    private static final String TAG = "AdSkipService";

    // YouTube changes exact wording/casing across versions & languages,
    // so we match on several known variants rather than one exact string.
    private static final String[] SKIP_LABELS = {
            "Skip Ad",
            "Skip ad",
            "Skip Ads",
            "Skip ads",
            "skip"
    };

    // Broader set used only to decide "an ad is currently on screen", for
    // muting — covers the pre-skip countdown window too, not just the
    // moment the Skip button itself appears. Best-effort: YouTube's exact
    // ad-badge wording varies by version/region, so this may miss some
    // unskippable ads or occasionally mute a beat early/late.
    private static final String[] AD_PRESENCE_LABELS = {
            "Skip Ad",
            "Skip ad",
            "Skip Ads",
            "Skip ads",
            "skip",
            "Visit advertiser",
            "Ad ·",
            "Sponsored"
    };

    // Debounce: the view tree fires TYPE_WINDOW_CONTENT_CHANGED repeatedly
    // during the skip-button's own tap/dismiss animation (multiple layout
    // passes in <500ms), so without this the same click gets attempted
    // 5-8 times in a row. We remember when we last successfully clicked
    // and ignore further attempts for a short cooldown window.
    private static final long CLICK_COOLDOWN_MS = 800;
    private long lastClickTimeMs = 0L;

    private AudioManager audioManager;
    private boolean mutedByUs = false;
    private int savedVolume = -1;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            // Mute tracking runs on every event, independent of the click
            // cooldown below — otherwise volume would stay muted/unmuted
            // stale during the cooldown window.
            updateMuteState(isAdCurrentlyShowing(root));

            long now = SystemClock.elapsedRealtime();
            if (now - lastClickTimeMs < CLICK_COOLDOWN_MS) {
                return; // still inside cooldown from the last successful click
            }
            findAndClickSkipButton(root, now);
        } finally {
            root.recycle();
        }
    }

    /**
     * Returns true if any ad-presence indicator (skip button, pre-skip
     * countdown badge, "Visit advertiser", etc.) is currently on screen.
     */
    private boolean isAdCurrentlyShowing(AccessibilityNodeInfo root) {
        for (String label : AD_PRESENCE_LABELS) {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            if (matches != null && !matches.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Mutes the device's media volume while an ad is showing and restores
     * the user's previous volume once it's gone (skipped or ended).
     *
     * Note: this mutes the whole media stream, not just YouTube — Android
     * doesn't allow a normal accessibility service to scope volume changes
     * to a single app's audio session. It's restored the moment the ad
     * indicator disappears.
     */
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

    /**
     * Recursively searches the current view-tree for a node that looks
     * like a skip button, then performs ACTION_CLICK on it (or its
     * clickable parent, since the text node itself often isn't the
     * clickable target). Stops after the first successful click this
     * pass, since one click is all a single ad ever needs.
     */
    private void findAndClickSkipButton(AccessibilityNodeInfo node, long eventTimeMs) {
        if (node == null) return;

        for (String label : SKIP_LABELS) {
            List<AccessibilityNodeInfo> matches =
                    node.findAccessibilityNodeInfosByText(label);

            if (matches == null) continue;

            for (AccessibilityNodeInfo match : matches) {
                AccessibilityNodeInfo clickable = findClickableSelfOrParent(match);
                if (clickable == null) continue;

                boolean success = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.d(TAG, "Attempted skip click, success=" + success);

                if (success) {
                    lastClickTimeMs = eventTimeMs;
                    return; // done for this pass, cooldown handles the rest
                }
            }
        }
    }

    /**
     * findAccessibilityNodeInfosByText() often returns the TextView
     * showing "Skip Ad", but the actual clickable target is one of its
     * ancestors (a Button/FrameLayout wrapping it). Walk up until we
     * find something clickable.
     */
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

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
        restoreVolume(); // never leave the device stuck muted
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        Log.d(TAG, "AdSkipAccessibilityService connected");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        restoreVolume(); // service turned off (or app killed) mid-ad — don't leave it muted
    }
}