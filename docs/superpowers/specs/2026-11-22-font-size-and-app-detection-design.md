# Font size setting and app detection on Android 16 — design

Source: issue #2 (drawinggh). Approved approaches: A (font) and A (detection), 2026-11-22.

## Intent
- Users find the text too small: give them a text size setting.
- A user on Android 16 sees fewer running apps than in other task managers: make Monitor recognise every running app process correctly, without showing apps that are not running.

## Goals
- Settings > General: "Text size" with Normal / Large / Extra large, saved in `Prefs` and kept across restarts.
- Applies to every screen of the app, including dialogs and dropdown menus.
- Android's own font size is respected and applied once: effective scale = system font scale × app level.
- Nothing cut off or overlapping at Extra large; same look as today at Normal.
- Every app process (`u<user>_a<n>`) is attributed to its package, even when its process name is not a package name.
- A package lookup that failed is retried later instead of being remembered forever.

## Non-goals / anti-goals
- No scaling of the floating monitor or the home screen widget (the floating monitor has its own size option).
- No listing of apps that are only "recent" (UsageStats) and not running: that would be false data.
- No new permissions; no privileged or restriction-bypassing mechanisms.
- No rewrite of the 185 hard-coded `fontSize` values.

## Approaches
### Font size
- **A (chosen):** in `MainActivity.attachBaseContext`, `applyOverrideConfiguration` with `fontScale = systemFontScale × level`. Covers dialogs and popups (they inherit the activity context). Changing the level recreates the activity (as the interface style already does). `fontScale` is removed from the manifest's `configChanges` so a change of Android's font size recreates the activity and is picked up.
- B: override `LocalDensity` inside Compose only. Rejected: dialogs and popups get their own density and would stay small.
- C: central typography replacing 185 sizes. Rejected: same result, much larger change.

### Detection
- **A (chosen):** attribute each process to a package by its Linux user (`u0_a153` → uid 10153 → `PackageManager.getPackagesForUid`). With several packages for one uid (shared uid), prefer the one matching the process name; the process name stays as a fallback (isolated `u0_i*` processes, native processes).
- B: a list of known special process names. Rejected: fragile, vendor-specific.

## Design
- `FontScale` (pure Kotlin): levels `[1.0, 1.15, 1.3]`, `effective(system, level)` clamped; `Prefs.fontSize` (Int, key `fontSize`, default 0).
- `MainActivity`: `attachBaseContext` reads the level from the `prefs` SharedPreferences directly (Prefs may not be initialised yet) and applies the override.
- Settings: a `SettingCard` with a `WinDropdown` (Normal / Large / Extra large); on change save and `recreate()`.
- Fixed `height(N.dp)` on containers with text become `heightIn(min = N.dp)`: header row, search box, Mac title bar, Performance side items, power mode buttons, table rows.
- `ProcessMapping` (pure Kotlin): `uidOf("u0_a153") = 10153`, `uidOf("u10_a5") = 1010005`, isolated/other → null; `pick(processName, candidates)` chooses the package.
- `SystemReader`: package = mapping by uid (via `Apps.packagesForUid`, cached) → fallback to the current name rule.
- `Apps.meta`: a failed lookup is cached for 60 s, then retried.

## Verification
- Unit tests (JVM) for `FontScale` and `ProcessMapping` and the retry rule, written before the code.
- CI build (`gradle testDebugUnitTest assembleRelease`).
- On the test phone (Android 16): screenshots of every page and dialog at Extra large; process list compared with `ps` (every `u*_a*` process attributed to a package).

## Limitations to document
- Without root or Shizuku Android does not let any app list other apps' processes.
- Monitor shows running processes only; "recent" apps that are not running are not shown on purpose.
- The original reporter's exact case is not reproduced yet (details requested in #2).
