# Hush
> *Ads, dismissed.*

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://android.com)
[![Language](https://img.shields.io/badge/language-Java-007396?logo=java&logoColor=white)](https://oracle.com/java)
[![Min SDK](https://img.shields.io/badge/minSdk-24-blue)](https://android.com)
[![Target SDK](https://img.shields.io/badge/targetSdk-36-blue)](https://android.com)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)
[![Network Access](https://img.shields.io/badge/network%20access-none-success)](README.md)

An Android accessibility engine designed to tap YouTube's "Skip ad" button, mute media audio during promotional segments, and draw an opaque touch-through blackout shield over unskippable ads. Also features an experimental tap-through blackout shield for Facebook sponsored posts. No root, no patched APKs, no network proxies, and zero background analytics.

---

## Brand & Colour Palette

Hush is styled in an expressive midnight-and-violet dark palette with crisp blush typography and a lime accent spark.

| Token | Hex | Role | Contrast Ratio |
| :--- | :--- | :--- | :--- |
| **Midnight** | `#03012A` | Page root canvas, window background, status/nav bar | — |
| **Ultramarine** | `#01004D` | Card surfaces & hero panel | — |
| **Violet** | `#AF80FA` | Primary action buttons, switch on-state, active tiles | **7.00:1** (on Midnight) / **6.58:1** (on Ultramarine) |
| **Blush** | `#F4E7F1` | Primary text on dark surfaces, headers | **16.85:1** (on Midnight) / **15.83:1** (on Ultramarine) |
| **Blush 70%** | `#B8ADBA` | Secondary text, descriptions, captions | **9.34:1** (on Midnight) / **8.77:1** (on Ultramarine) |
| **Hairline** | `#1A1966` | 1dp card borders, dividers | — |
| **Lime** | `#95C34D` | ACTIVE state pulse rings, spark dot | **9.78:1** (on Midnight) / **9.19:1** (on Ultramarine) |
| **PAUSED** | `#F2B84B` | Paused state word, amber indicator | **11.26:1** (on Midnight) / **10.59:1** (on Ultramarine) |
| **NOT ENABLED** | `#FF7A6B` | Offline status word, red indicator | **7.92:1** (on Midnight) / **7.44:1** (on Ultramarine) |

*Button pairing: Violet background (`#AF80FA`) with Midnight text (`#03012A`) achieves a **7.00:1** contrast ratio (WCAG AAA compliant).*

---

## Features

- **Auto-skip**: Discovers `com.google.android.youtube:id/skip_ad_button` or "Skip ad" labels and dispatches clicks within 200ms.
- **Media Mute**: Attenuates device media audio during advertisement segments and restores previous volume upon completion.
- **Touch-Through Ad Shield**: Overlays an opaque blackout window (`#03012A`) over player bounds for unskippable ads (`TYPE_ACCESSIBILITY_OVERLAY`, pass-through taps).
- **Facebook Sponsored Post Hider (Experimental)**: Optional switch that overlays a touch-through blackout shield over sponsored stories in the Facebook app (`com.facebook.katana`).
- **Independent Control Gating**: Each feature is independently toggleable via SharedPreferences (`skip_enabled`, `mute_enabled`, `shield_enabled`, `fb_shield_enabled`).
- **Live State Observation**: Automatically transitions between **ACTIVE**, **PAUSED**, and **OFFLINE** via `ContentObserver` and system shortcut callbacks without polling.
- **Android 13+ Setup Help**: Built-in guidance for sideloaded apps to enable "Restricted settings" seamlessly.
- **Skip Metrics Counter**: Tracks ads dismissed today and all-time with count-up animations.
- **Strictly Local**: Scoped exclusively to targeted apps. No internet permissions requested in `AndroidManifest.xml`.

---

## Setup & Installation

1. Build or install `app-debug.apk`.
2. Launch **Hush**.
3. Tap **Open accessibility settings** and turn on **Hush Service**.
4. *(Android 13+ only)* If restricted, tap **Open App info** in the setup card, tap the three dots (top right), choose **Allow restricted settings**, then enable the service.
5. Open YouTube and enjoy uninterrupted playback.
