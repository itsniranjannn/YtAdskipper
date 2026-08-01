<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=6C5CE7&height=180&section=header&text=Ad%20Skipper%20YT&fontSize=50&fontColor=ffffff&animation=fadeIn&fontAlignY=35" />

<a href="https://github.com">
  <img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=20&pause=1000&color=9AA0AE&center=true&vCenter=true&width=600&lines=Skips+YouTube+ads+automatically%2C+on-device;No+root.+No+APK+mods.+No+network+interception.;Detects+the+native+Skip+Ad+button+and+taps+it." alt="Typing SVG" />
</a>

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Java-007396?logo=java&logoColor=white)
![Min SDK](https://img.shields.io/badge/minSdk-24-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)
![No Network](https://img.shields.io/badge/network%20access-none-success)

</div>

---

<div align="center">

### 📺 See it in action

<!--
  Drop a screen recording here as ad_skipper_demo.gif in your repo root.
  Quick way to record on the emulator:
    Extended Controls (⋮) → Record and Playback → Start recording
  Then convert the .webm/.mp4 to a .gif (ezgif.com, or `ffmpeg -i in.mp4 -vf "fps=12,scale=320:-1" demo.gif`)
-->
![Ad Skipper YT demo](ad_skipper_demo.gif)

*Tap the accessibility button to pause/resume anytime — no need to dig back into Settings.*

</div>

---

## 📱 Screenshots

| Home screen                              | Enabled state                                 |
|------------------------------------------|-----------------------------------------------|
| ![Home screen](home.png) | ![Enabled state](enabled.png) |

---

## ⚙️ How it works

1. An `AccessibilityService` watches **only** the YouTube app's screen — scoped via `android:packageNames` in the service config, so no other app is ever touched.
2. When the view tree changes, it scans (throttled, so it's light on the device) for a node matching known "Skip Ad" label variants.
3. It walks up to the nearest clickable ancestor — the text node itself usually isn't the tappable target — and performs `ACTION_CLICK`.
4. Media volume is muted the moment ad-related UI appears (including the pre-skip countdown), and restored the instant it's gone — so the mute always lands **before** the click, not after.
5. A cooldown after each successful click prevents duplicate taps during the button's own dismiss animation.

No network traffic is read or modified. No data ever leaves the device.

---

## ✨ Features

<table>
<tr>
<td width="50%" valign="top">

**Core**
- 🎯 One-tap setup — deep links straight to Android's Accessibility Settings
- 🔇 Auto-mute before skip, auto-restore after
- ⏱️ Debounced click + scan logic — smooth, no system-wide lag
- 🧵 No network access, no data collection, ever

</td>
<td width="50%" valign="top">

**Interface**
- 🟢 Live three-state status: **Not Enabled → Active → Paused**
- ⏸️ Pause/resume anytime via the system Accessibility Button — no Settings round-trip needed
- 🌊 Animated entrance (staggered slide + fade), breathing logo, color-crossfading status pill
- 🌑 Dark, minimal UI throughout

</td>
</tr>
</table>

---

## 🛠️ Tech stack

- Java
- Android `AccessibilityService` API
- Android Studio / Gradle

---

## 🚀 Setup

1. Clone the repo
2. Open in Android Studio
3. Run on a device or emulator with the **Play Store image** (needed for real YouTube)
4. Open the app → tap **Open Accessibility Settings** → enable **Ad Skipper YT Service**
5. Open YouTube and play any video with a skippable ad

<details>
<summary><strong>⚠️ Android 13+ note — toggle greyed out?</strong></summary>

<br>

Sideloaded apps are hidden behind Android's "restricted settings" protection by default. Fix it once:

**Settings → Apps → Ad Skipper YT → ⋮ (3-dot menu) → Allow restricted settings**

Then go back into Accessibility settings and the toggle will work.

</details>

<details>
<summary><strong>⏸️ Using the pause/resume shortcut</strong></summary>

<br>

Once the service is enabled, Android shows a floating **Accessibility Button** on screen. Tap it:
- **1st tap** → pauses (skip-clicking and muting stop, status turns amber)
- **2nd tap** → resumes (back to green/active)

It keeps toggling on every tap. If the button doesn't appear, check that the "shortcut" option is switched on inside the service's own Settings page — it's the same page reached via "Open Accessibility Settings."

</details>

---

## 🧱 Limitations

- Only works on ads with a visible skip button — cannot remove unskippable ads
- Relies on matching the button's text label, so a YouTube UI change could require an update
- Mutes the whole media stream while an ad shows, not just YouTube's audio — Android doesn't allow an accessibility service to scope volume to a single app's session
- Not distributed via Play Store; using it may be inconsistent with YouTube's Terms of Service, even though the technique itself only uses Android's public Accessibility API
- iOS is not supported and can't be — this whole approach depends on Android's `AccessibilityService`, which has no iOS equivalent

---

## 🔒 Privacy & security

- Scoped to `com.google.android.youtube` only — no other app's screen is ever read
- Zero network permissions in the manifest — not just a promise, enforced by the OS
- Pause state stored locally in private `SharedPreferences` — never transmitted anywhere
- Service is protected by `BIND_ACCESSIBILITY_SERVICE`, so no other app can bind to or impersonate it

---

## 📄 License

MIT — do whatever you want with it, just don't blame me if YouTube changes its UI and it stops working.

<div align="center">

---

### Built by **Niranjan.K**

<img src="https://capsule-render.vercel.app/api?type=waving&color=6C5CE7&height=100&section=footer&animation=fadeIn" />

</div>
