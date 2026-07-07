package com.njk.adskipper;

import android.accessibilityservice.AccessibilityService;
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
 * performs a click on it.
 *
 * This does NOT:
 *  - read or modify YouTube's app code
 *  - intercept or modify network traffic
 *  - remove/block ads that have no skip button (unskippable ads)
 *  - collect or transmit any data off-device
 *
 * It only automates something a human could already do by tapping the
 * screen themselves, once the button becomes visible.
 */
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

    // Debounce: the view tree fires TYPE_WINDOW_CONTENT_CHANGED repeatedly
    // during the skip-button's own tap/dismiss animation (multiple layout
    // passes in <500ms), so without this the same click gets attempted
    // 5-8 times in a row. We remember when we last successfully clicked
    // and ignore further attempts for a short cooldown window.
    private static final long CLICK_COOLDOWN_MS = 800;
    private long lastClickTimeMs = 0L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastClickTimeMs < CLICK_COOLDOWN_MS) {
            return; // still inside cooldown from the last successful click
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            findAndClickSkipButton(root, now);
        } finally {
            root.recycle();
        }
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
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "AdSkipAccessibilityService connected");
    }
}
