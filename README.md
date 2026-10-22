# Adieu
> *Ads, dismissed.*

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://android.com)
[![Language](https://img.shields.io/badge/language-Java-007396?logo=java&logoColor=white)](https://oracle.com/java)
[![Min SDK](https://img.shields.io/badge/minSdk-24-blue)](https://android.com)
[![Target SDK](https://img.shields.io/badge/targetSdk-36-blue)](https://android.com)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)
[![Network Access](https://img.shields.io/badge/network%20access-none-success)](README.md)

An Android accessibility engine designed to tap YouTube's "Skip ad" button, mute media audio during promotion intervals, and draw an opaque touch-through blackout shield over unskippable ads. No root, no patched APKs, no network proxies, and zero background analytics.

---

## Brand & Colour Palette

Adieu features a custom dark palette built on warm wine surfaces and sand typography.

| Token | Hex | Role | Contrast Ratio |
| :--- | :--- | :--- | :--- |
| **Brand wine** | `#7F011F` | Hero panels, active switch tracks, icon tiles | — |
| **Brand sand** | `#F5EBD0` | Primary text, primary button fill, logo glyph | **11.8:1** (on Wine 800) / **15.6:1** (on Wine 900) |
| **Wine 900** | `#14030A` | Page root canvas | — |
| **Wine 800** | `#240612` | Card surfaces & switch containers | — |
| **Wine 600** | `#A3052B` | Pressed states, ring highlights | — |
| **Hairline** | `#4A1424` | 1dp dividers and card outlines | — |
| **Sand 400** | `#CDBF9F` | Secondary descriptions, captions | **8.1:1** (on Wine 800) / **10.7:1** (on Wine 900) |
| **ACTIVE** | `#7BD88F` | Active state word, pulsing rings | **9.5:1** (on Wine 800) / **12.6:1** (on Wine 900) |
| **PAUSED** | `#F2B84B` | Paused state word, amber indicator | **9.8:1** (on Wine 800) / **13.0:1** (on Wine 900) |
| **OFFLINE** | `#FF7A6B` | Offline status word, red indicator | **6.4:1** (on Wine 800) / **8.5:1** (on Wine 900) |

---

## Features

- **Auto-skip**: Discovers `com.google.android.youtube:id/skip_ad_button` or "Skip ad" labels and dispatches clicks within 200ms.
- **Media Mute**: Attenuates device media audio during advertisement segments and restores previous volume upon completion.
- **Touch-Through Ad Shield**: Overlays an opaque blackout window (`#0E0206`) over player bounds for unskippable ads (`TYPE_ACCESSIBILITY_OVERLAY`, pass-through taps).
- **Independent Control Gating**: Each feature is independently toggleable via SharedPreferences (`skip_enabled`, `mute_enabled`, `shield_enabled`).
- **Live State Observation**: Automatically transitions between **ACTIVE**, **PAUSED**, and **OFFLINE** via `ContentObserver` and system shortcut callbacks without polling.
- **Android 13+ Setup Help**: Built-in guidance for sideloaded apps to enable "Restricted settings" seamlessly.
- **Skip Metrics Counter**: Tracks ads dismissed today and all-time with count-up animations.
- **Strictly Local**: Scoped exclusively to `com.google.android.youtube`. No internet permissions requested in `AndroidManifest.xml`.

---

## Setup & Installation

1. Build or install `app-release.apk` / `app-debug.apk`.
2. Launch **Adieu**.
3. Tap **Open accessibility settings** and turn on **Adieu Service**.
4. *(Android 13+ only)* If restricted, tap **Open App info** in the setup card, tap the three dots (top right), choose **Allow restricted settings**, then enable the service.
5. Open YouTube and play media normally.
