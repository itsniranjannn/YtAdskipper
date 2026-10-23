# Hush
> *Ads, dismissed.*

[![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)](https://android.com)
[![Language](https://img.shields.io/badge/language-Java-007396?logo=java&logoColor=white)](https://oracle.com/java)
[![Min SDK](https://img.shields.io/badge/minSdk-24-blue)](https://android.com)
[![Target SDK](https://img.shields.io/badge/targetSdk-36-blue)](https://android.com)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

An Android accessibility and privacy utility designed to tap YouTube's "Skip ad" button, mute media audio during promotional segments, draw a tap-through blackout shield over unskippable ads, and filter trackers and ads across all apps via an on-device local DNS loopback. Features an experimental blackout shield for Facebook sponsored posts.

---

## Brand & Design System

Hush follows a high-contrast dark theme utilizing a solid Violet hero card in the ACTIVE state, contrasting Blush cards for interactive setup guidance, and Lime sparks for live indicators.

### Color Tokens & Contrast Matrix

| Token | Hex | Role | Contrast Ratio | WCAG Compliance |
| :--- | :--- | :--- | :--- | :--- |
| **Midnight** | `#03012A` | Page canvas, window background, status/nav bar, shield overlay | Canvas base | — |
| **Ultramarine** | `#01004D` | Offline & card surfaces | Card base | — |
| **Violet** | `#AF80FA` | Primary active hero surface, buttons, active tiles | **7.00:1** vs Midnight | **AAA** |
| **Blush** | `#F4E7F1` | Light setup surface, primary text on dark cards | **16.85:1** vs Midnight / **15.83:1** vs Ultramarine | **AAA** |
| **Blush 70%** | `#B8ADBA` | Secondary text, captions, descriptions | **9.34:1** vs Midnight / **8.77:1** vs Ultramarine | **AAA** |
| **Lime** | `#95C34D` | Active spark dot, pulsing status ring, live counter highlight | **9.78:1** vs Midnight / **9.19:1** vs Ultramarine | **AAA** |
| **Hairline** | `#1A1966` | 1dp card borders & hairline dividers | Structural | — |
| **PAUSED** | `#F2B84B` | Paused state indicator | **11.26:1** vs Midnight | **AAA** |
| **NOT ENABLED** | `#FF7A6B` | Offline status indicator | **7.92:1** vs Midnight | **AAA** |

### Typography Scale

- **Display** (48sp / ExtraBold): Bricolage Grotesque (`statusLabel`)
- **Wordmark** (24sp / ExtraBold): Bricolage Grotesque (`titleText`)
- **Title** (22sp / Bold): DM Sans
- **Body** (16sp / Regular & Medium): DM Sans
- **Caption** (13sp / Regular): DM Sans
- **Label** (11sp / Bold, Uppercase, Tracking +0.08em): DM Sans
- **Tabular Numbers** (28sp / Bold, `fontFeatureSettings="tnum"`): DM Sans

---

## Key Features

1. **Auto-skip**: Scans YouTube layouts (`com.google.android.youtube`) every 200ms and programmatically taps "Skip ad" when available.
2. **Media Mute**: Silences media volume while an ad plays and restores previous volume immediately when the ad finishes.
3. **Touch-Through Ad Shield**: Overlays an opaque touch-through blackout overlay (`TYPE_ACCESSIBILITY_OVERLAY`) over unskippable ads.
4. **On-Device DNS Filter (All Apps)**:
   - Built on `VpnService` with local loopback DNS resolution (`127.0.0.1:53` -> fallback `9.9.9.9`).
   - Runs with foreground notification (`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`) to survive 30+ minutes screen off.
   - Clean shutdown and preference sync if revoked by another VPN app.
   - Live filtered counter.
5. **Facebook Sponsored Post Hider (Experimental)**: Covers sponsored feed stories in the Facebook app (`com.facebook.katana`) with an interactive confirmation prompt.
6. **Self-Checking Setup Stepper**: Clear step-by-step guidance for Android 13+ "Restricted settings" with direct link to App Info.
7. **Stat Counters**: Live tabular count of skipped ads today and all time.

---

## Build & APK Details

The release APK is signed and ready for installation:

- **Path**: `app/build/outputs/apk/release/app-release.apk`
- **Application ID**: `com.njk.adskipper`
- **Min SDK**: 24 (Android 7.0 Nougat)
- **Target / Compile SDK**: 36 (Android 16 preview)
- **Signature Scheme**: APK Signature Scheme v2 (Verified via `apksigner`)
- **Debug Build**: `app/build/outputs/apk/debug/app-debug.apk`
