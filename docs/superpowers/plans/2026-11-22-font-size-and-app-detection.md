# Font size setting and app detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A persistent "Text size" setting (Normal / Large / Extra large) for the whole app, and correct attribution of every running app process to its package on Android 16 (issue #2).

**Architecture:** The text size multiplies Android's font scale once, through `applyOverrideConfiguration` in `MainActivity.attachBaseContext`, so every screen, dialog and popup follows it. Processes are attributed to packages by their Linux user (uid) through `PackageManager.getPackagesForUid`, with the process name as tie-breaker and fallback. Pure logic lives in two small Kotlin objects covered by JVM tests.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.09.03), minSdk 26, targetSdk 34, JUnit 4. No local Gradle: tests and build run in GitHub Actions on a push to the `pruebas` branch (`gradle testDebugUnitTest assembleRelease`).

**Spec:** `docs/superpowers/specs/2026-11-22-font-size-and-app-detection-design.md`

## Global Constraints

- Text levels: Normal ×1.0, Large ×1.15, Extra large ×1.3, applied on top of Android's font scale, once.
- Preference key `fontSize` (Int 0..2, default 0) in the existing `prefs` SharedPreferences, through `Prefs`.
- Floating monitor (`PanelView`, `Overlay`) and the widget are not scaled.
- No new permissions, no privileged APIs; never list apps that are not running.
- Every user-visible string goes through `tr("<Spanish key>")` with a row in `app/src/main/assets/i18n.tsv` (11 columns: es en fr pt de it ru ar zh ja ko).
- Release notes in English and universal (no device or chip names). Do not publish to `main` without the owner's explicit OK.

## Review Focus

- Android font size already large (system scale 2.0) + Extra large: effective scale must be capped (×2.6 max) so layouts stay usable → `FontScaleTest.effectiveIsCapped`.
- Corrupt or out-of-range stored level (e.g. 7 or -1): must fall back to Normal, not crash at start-up → `FontScaleTest.invalidLevelIsNormal`.
- Rotating the phone or switching dark mode while on Large: the size must stay (configChanges keeps handling them) → checked on device in Task 4.
- Work profile / second space processes (`u10_a…`, `u999_a…`): must map to the right uid → `ProcessMappingTest.otherUsers`.
- Shared-uid packages (several packages on one uid): pick the one matching the process name, else the first → `ProcessMappingTest.sharedUid`.

---

### Task 1: Text size levels (pure logic + preference)

**Files:**
- Create: `app/src/main/java/com/kevxngg/monitorroot/FontScale.kt`
- Modify: `app/src/main/java/com/kevxngg/monitorroot/Common.kt` (Prefs: field, `init`, `save`)
- Test: `app/src/test/java/com/kevxngg/monitorroot/FontScaleTest.kt`

**Interfaces:**
- Produces: `object FontScale { val LEVELS: List<Float>; fun level(index: Int): Float; fun effective(system: Float, index: Int): Float; const val KEY = "fontSize" }`; `Prefs.fontSize: Int` (mutableIntStateOf, saved under `FontScale.KEY`).

- [ ] **Step 1: Write the failing tests**

```kotlin
class FontScaleTest {
    @Test fun levels() { assertEquals(listOf(1.0f, 1.15f, 1.3f), FontScale.LEVELS) }
    @Test fun appliedOnceOverSystem() { assertEquals(1.3f * 1.15f, FontScale.effective(1.3f, 1), 0.0001f) }
    @Test fun normalKeepsSystem() { assertEquals(0.85f, FontScale.effective(0.85f, 0), 0.0001f) }
    @Test fun invalidLevelIsNormal() {
        assertEquals(1.0f, FontScale.level(7), 0f); assertEquals(1.0f, FontScale.level(-1), 0f)
    }
    @Test fun effectiveIsCapped() { assertEquals(2.6f, FontScale.effective(2.0f, 2), 0.0001f) }
}
```

- [ ] **Step 2: Push to `pruebas`, confirm CI fails** — `gh run watch <id> --exit-status`; expected: FAIL, unresolved reference `FontScale`.
- [ ] **Step 3: Implement `FontScale`** (cap at 2.6f; out-of-range index → 1.0f) and `Prefs.fontSize` load/save.
- [ ] **Step 4: Push, confirm CI passes** — expected: `success`.
- [ ] **Step 5: Commit** — `feat: text size levels and preference`.

### Task 2: Apply the text size to the whole app + Settings control

**Files:**
- Modify: `app/src/main/java/com/kevxngg/monitorroot/MainActivity.kt:66` (add `attachBaseContext`)
- Modify: `app/src/main/AndroidManifest.xml` (remove `fontScale` from MainActivity `configChanges`)
- Modify: `app/src/main/java/com/kevxngg/monitorroot/SettingsPage.kt` (General section, after "Tema de la aplicación")
- Modify: `app/src/main/assets/i18n.tsv`

**Interfaces:**
- Consumes: `FontScale.effective`, `FontScale.KEY`, `Prefs.fontSize` (Task 1).

- [ ] **Step 1: `override fun attachBaseContext(newBase: Context)`** — call super, then `applyOverrideConfiguration(Configuration().apply { fontScale = FontScale.effective(newBase.resources.configuration.fontScale, level) })`, with `level` read from `newBase.getSharedPreferences("prefs", MODE_PRIVATE).getInt(FontScale.KEY, 0)` (Prefs is not initialised yet). Skip the override when the level is 0.
- [ ] **Step 2: Manifest** — `configChanges` keeps everything except `fontScale`, so a change of Android's font size recreates the activity and is picked up.
- [ ] **Step 3: Settings row** — `SettingCard(Icons.Outlined.FormatSize, tr("Tamaño del texto"), tr("Agranda los textos de la app"))` with `WinDropdown(listOf(tr("Normal"), tr("Grande"), tr("Muy grande")), Prefs.fontSize)`; on a different choice: save, then `(ctx as? Activity)?.recreate()`.
- [ ] **Step 4: i18n rows** for the 5 new keys (reuse existing keys if present: check `grep -P '^Normal\t'` first).
- [ ] **Step 5: Push, CI build passes; install on the test phone, choose each level, confirm the text grows in pages, a dialog (Run new task) and a dropdown menu, and that the choice survives closing the app.**
- [ ] **Step 6: Commit** — `feat: text size setting`.

### Task 3: Containers grow with the text

**Files:**
- Modify: `app/src/main/java/com/kevxngg/monitorroot/MainActivity.kt:411` (title bar 48.dp), `:423` (search 34.dp), `:483` (nav item 40.dp)
- Modify: `app/src/main/java/com/kevxngg/monitorroot/MacShell.kt:130` (search 30.dp)
- Modify: `app/src/main/java/com/kevxngg/monitorroot/PerformancePage.kt:141` (side item 68.dp)
- Modify: `app/src/main/java/com/kevxngg/monitorroot/CpuPower.kt:382-386` (power mode buttons 52.dp and divider)
- Modify: `app/src/main/java/com/kevxngg/monitorroot/Ui.kt:288` (table rows `height(height)`)

- [ ] **Step 1:** each `.height(N.dp)` above → `.heightIn(min = N.dp)`. For the power mode row, give the Row `height(IntrinsicSize.Min)` and make the divider `fillMaxHeight()`.
- [ ] **Step 2: Push, CI passes; on the phone at Extra large take screenshots of Processes, Performance (CPU, Memory, GPU, Battery), App history, Startup apps, Users, Details, Services, Games, Settings, macOS style, a dialog and a dropdown. Expected: no cut or overlapping text; at Normal the screens look as before.**
- [ ] **Step 3: Commit** — `fix: containers grow with larger text`.

### Task 4: Attribute processes to packages by uid

**Files:**
- Create: `app/src/main/java/com/kevxngg/monitorroot/ProcessMapping.kt`
- Modify: `app/src/main/java/com/kevxngg/monitorroot/Common.kt` (`Apps.packagesForUid`, failed-lookup retry in `Apps.meta`)
- Modify: `app/src/main/java/com/kevxngg/monitorroot/SystemReader.kt:319-321` (package choice)
- Test: `app/src/test/java/com/kevxngg/monitorroot/ProcessMappingTest.kt`

**Interfaces:**
- Produces: `object ProcessMapping { fun uidOf(user: String): Int?; fun pick(processName: String, candidates: List<String>): String?; fun retryDue(failedAtMs: Long, nowMs: Long): Boolean }`; `Apps.packagesForUid(uid: Int): List<String>` (cached per uid).

- [ ] **Step 1: Write the failing tests**

```kotlin
class ProcessMappingTest {
    @Test fun appUser() { assertEquals(10153, ProcessMapping.uidOf("u0_a153")) }
    @Test fun otherUsers() {
        assertEquals(1010005, ProcessMapping.uidOf("u10_a5")); assertEquals(99910020, ProcessMapping.uidOf("u999_a20"))
    }
    @Test fun notAnApp() {
        assertNull(ProcessMapping.uidOf("u0_i12")); assertNull(ProcessMapping.uidOf("system")); assertNull(ProcessMapping.uidOf("root"))
    }
    @Test fun sharedUid() {
        assertEquals("com.b", ProcessMapping.pick("com.b:remote", listOf("com.a", "com.b")))
        assertEquals("com.a", ProcessMapping.pick("android.process.media", listOf("com.a", "com.b")))
        assertNull(ProcessMapping.pick("x", emptyList()))
    }
    @Test fun failedLookupRetriedAfterAMinute() {
        assertFalse(ProcessMapping.retryDue(0L, 59_999L)); assertTrue(ProcessMapping.retryDue(0L, 60_000L))
    }
}
```

- [ ] **Step 2: Push, confirm CI fails** (unresolved `ProcessMapping`).
- [ ] **Step 3: Implement** `ProcessMapping` (uid = user × 100000 + 10000 + n for `u<user>_a<n>`; null otherwise), `Apps.packagesForUid` (via `pm.getPackagesForUid`, cached), and in `Apps.meta` store the failure time and retry when `retryDue`. In `SystemReader`, package = `ProcessMapping.uidOf(user)?.let { pick(rawName, Apps.packagesForUid(it)) }` → if null, the current name rule.
- [ ] **Step 4: Push, CI passes.**
- [ ] **Step 5: On the phone: every `u*_a*` process from `ps -A -o USER,NAME` appears in Monitor under its app (including `android.process.media`, `com.google.android.gms.ui`); counts match `ps`. Install an app while Monitor is open: it appears with name and icon within a minute.**
- [ ] **Step 6: Commit** — `fix: attribute processes to apps by uid (issue #2)`.

### Task 5: Release prep and review

**Files:**
- Modify: `VERSION` (`4.0`), `RELEASE_NOTES.md`

- [ ] **Step 1:** release notes (English, universal): text size setting; running apps recognised by their user; failed app lookups retried; limitation line: without root or Shizuku Android doesn't allow listing other apps' processes, and only running apps are shown.
- [ ] **Step 2:** whole-branch code review (superpowers:requesting-code-review), fix findings.
- [ ] **Step 3:** CI green on `pruebas`; final device check (Tasks 2–4 checks) → ask the owner before pushing to `main`.
