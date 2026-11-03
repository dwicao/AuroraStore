# AGENTS.md

Aurora Store — a FOSS Android client that searches/downloads apps from Google
Play via the reverse-engineered `gplayapi` library. This repo is **dwicao's
fork** of upstream AuroraOSS (`upstream` remote: `whyorean/AuroraStore`); it
adds reliability/security hardening on top (see README "Fork differences").
Single Gradle module: `:app`. Kotlin + Jetpack Compose, Hilt, Room, WorkManager.

## Requirements

- **JDK 21** (toolchain pinned in `app/build.gradle.kts`)
- Android SDK (compileSdk/targetSdk 37, minSdk 23)
- Gradle 9.6.0 via wrapper (hash-pinned in `gradle-wrapper.properties`)

## Commands

All CI jobs use these exact invocations:

```bash
./gradlew assembleVanillaDebug          # debug build (GitLab CI build stage)
./gradlew ktlintCheck                   # lint (GitLab CI lint stage)
./gradlew testVanillaDebugUnitTest      # JVM unit tests (GitHub nightly-health)
./gradlew assembleVanillaRelease        # release APK (needs signing.properties, else unsigned)
```

- Flavor-aware names: `assemble<Flavor><BuildType>` where flavor ∈ `Vanilla`
  (default), `Huawei`, `Preload`; buildType ∈ `Debug`, `Nightly`, `Release`.
- After **any dependency change** you MUST regenerate
  `gradle/verification-metadata.xml` or the build fails checksum verification:

  ```bash
  ./gradlew --write-verification-metadata sha256 \
      assembleVanillaRelease testVanillaDebugUnitTest
  ```

  (or `gh workflow run bootstrap-verification.yml` — it commits the result).
  Coverage is vanilla-only; new huawei/preload-only artifacts will fail
  verification until regenerated.
- Android lint config: `app/lint.xml` (task `lint...` exists but is not in CI).
- `ktlintFormat` (from the jlleitschuh ktlint plugin) auto-fixes style issues;
  config lives in `.editorconfig` (`android_studio` style).

## Build variants

- Flavor dimension `device`: `vanilla` (default, anonymous login shown),
  `huawei` (HMS, no anonymous login, `-hw` suffix), `preload` (system-app
  variant, `-preload` suffix).
- Build types: `debug` (`.debug` suffix, AOSP testkey signing),
  `release` (minified; signed **only if `signing.properties` exists**),
  `nightly` (`.nightly` suffix + commit hash; **vanilla only** — huawei/preload
  × nightly variants are disabled in `androidComponents.beforeVariants`).
- `missingDimensionStrategy("device","vanilla")` so tests default to vanilla.
- **versionName comes from the latest git tag `v*`** (`git describe`), falling
  back to hardcoded `"4.8.4"` when tags are unavailable — shallow clones
  without tags produce a wrong version name. `versionCode` is hardcoded in
  `app/build.gradle.kts` and **must keep increasing** per fork release (the
  self-update feed compares against it).
- Flavor-specific code lives in `app/src/<flavor>/java` — notably each flavor
  provides its own `FlavouredUtil` object implementing
  `main/.../util/IFlavouredUtil` (same object name, per-flavor bodies). New
  flavor-dependent behavior goes behind that interface, not `if (flavor)`.
- `signing.properties` must be at the **repo root** (checked via `File(...)`
  against the JVM working dir) with `STORE_FILE` relative to `app/`; it is
  gitignored (also `*.keystore`).

## Architecture

Single-activity Compose app (`ComposeActivity`, the LAUNCHER; plus
`DeepLinkConfirmActivity` as a gate for external `market://`/`play.google.com`
links, `InstallActivity`, `MicroGInstallerActivity`).

**Flow:** Compose screen → `hiltViewModel()` → `@HiltViewModel` ViewModel →
data layer → gplayapi / Room / WorkManager.

- **DI:** Hilt. Modules in `app/src/main/java/com/aurora/store/module/`
  (`CommonModule`, `PlayClientModule`, qualifier `@NoAuth`). Application class
  `AuroraApp` (`@HiltAndroidApp`) initializes helpers, receivers, Coil.
- **Play API:** one process-wide `PlayClient` (+ `WebPlayClient`, +
  `@NoAuth` anonymous client) provided by `PlayClientModule`; device/locale
  spoofing is passed as *suppliers* so runtime spoof changes apply per call.
  **All new Play API call sites must go through `PlayGateway.call(label) { }`**
  (`data/play/PlayGateway.kt`) — it maps failures onto the `PlayError`
  taxonomy, emits `AuthEvent.SessionExpired`, and counts unexpected failures
  (`PlayErrorMonitor`) as an early-warning signal for upstream Google changes.
  Don't catch `PlayException.Unauthorized` ad hoc.
- **Navigation (Navigation3):** two parallel sealed types in
  `compose/navigation/`:
  - `Screen` — `@Parcelize @Serializable` `NavKey`s; the actual backstack
    entries (also passed via Intent extras, `Screen.PARCEL_KEY`).
  - `Destination` — what screens emit through a single
    `onNavigateTo: (Destination) -> Unit` callback.
  `NavDisplay.kt` maps `Destination` → `Screen` and hosts `entryProvider`.
  **Adding a screen = edit all three files** (`Screen.kt`, `Destination.kt`,
  `NavDisplay.kt` mapping + entry). Gotcha: `Destination.AppUpdate` maps to
  `Unit` (no-op) — update navigation is handled inside `MainScreen` state.
- **Events:** global `AuroraApp.events` (`EventFlow`, typed SharedFlows:
  `BusEvent`, `InstallerEvent`, `AuthEvent`) plus `AuroraApp.scope`
  (global `MainScope`). Session-expired redirects flow through here and land
  on `Screen.Splash`.
- **Persistence:** Room (`data/room/`, DB `aurora_database`, **version 15**,
  schemas exported to `app/schemas/` and committed). Entity/DAO changes need:
  bump `version`, add `MIGRATION_n_n+1` in `MigrationHelper`, register it in
  `RoomModule`, commit the regenerated schema JSON.
- **Background:** WorkManager with `@HiltWorker`/`@AssistedInject` workers in
  `data/work/` (auth refresh, downloads, update check, cache, Exodus tracker
  sync, export). `AuroraApp` implements `Configuration.Provider` with
  `HiltWorkerFactory`.
- **Installers:** strategy objects in `data/installer/`
  (`SessionInstaller`, `ShizukuInstaller`, `RootInstaller`, `NativeInstaller`,
  `ServiceInstaller`, AM/Huawei, microG) behind `IInstaller`, selected via
  `AppInstaller` (Shizuku is the fork's default; auto-prompts on startup).
- **Preferences:** two stores — regular `util/Preferences` (plain
  SharedPreferences) and `util/AuthPrefs` (**encrypted** `auth_prefs.xml`,
  auth material only: tokens, account type, email; backup-excluded). Never
  move secrets into plain prefs.

### ViewModel/screen conventions

- ViewModels: `@HiltViewModel` + constructor `@Inject`, expose
  `MutableStateFlow`/`asStateFlow()` pairs (private `_x` + public `x`), events
  via `MutableSharedFlow`. Grouped by feature under `viewmodel/`.
- Screens are plain `@Composable` functions taking `onNavigateTo`; obtain VMs
  with `hiltViewModel(key = ...)` when scoped per item (e.g. per packageName).
- Compose previews: use the providers in `compose/preview/` (`AppPreviewProvider`
  etc.) instead of ad-hoc fake data.
- Experimental Compose/Coil/uuid APIs are opted in **globally** in
  `app/build.gradle.kts` `kotlin.compilerOptions.optIn` — no per-file `@OptIn`
  needed for those listed there.

## Conventions

- **Every source file starts with an SPDX header**
  (`SPDX-FileCopyrightText: ...` / `SPDX-License-Identifier: GPL-3.0-or-later`);
  the repo is REUSE-compliant (`REUSE.toml`, `LICENSES/`). Non-commentable
  files are annotated in `REUSE.toml`.
- ktlint (`android_studio` style): composable functions are exempt from
  function-naming rules; `standard:class-signature` disabled (see
  `.editorconfig`).
- Log with `Log.<level>(TAG, ...)` where `TAG` comes from
  `com.aurora.extensions.TAG`.
- Commit messages (observed style): prefixed scope — `CI:`, `README:`,
  `Bump versionCode N -> M`, or a direct imperative sentence. Don't hand-edit
  `updates.json` or `gradle/verification-metadata.xml` — CI regenerates them
  (`Feed vX.Y.Z (auto-updated from release)`, `CI: refresh Gradle dependency
  verification metadata`).

## Testing

- **JVM unit tests** (`app/src/test/`): JUnit4 + Google Truth; pure-logic
  seams only (Play error mapping, dispenser health policy, DEX tracker
  scanner, bsdiff, APK frosting). Run: `./gradlew testVanillaDebugUnitTest`.
  - `FrostingVerifierTest` needs a real Play APK via
    `-Pfrosted.apk=/path/to/base.apk`; without it those cases are skipped
    (`assumeTrue`), not failed.
- **Instrumented tests** (`app/src/androidTest/`): Compose UI tests extending
  `IsolatedTest` (provides `createComposeRule` + `AuroraTheme` content), Hilt
  via `HiltInstrumentationTestRunner` (`disableAnalytics=true` argument).
  gplayapi-subtype error branches need a live session and are deliberately
  *not* unit-tested — covered by the nightly workflow's live probes instead.

## CI

- **GitLab** (`.gitlab-ci.yml`): `assembleVanillaDebug` → `ktlintCheck` →
  upload debug APK to the package registry (default branch only).
- **GitHub** (`.github/workflows/`):
  - `release.yml` — on `v*` tag: build signed `assembleVanillaRelease`,
    attach to the GitHub Release, then **refresh `updates.json`** (self-update
    feed) and commit to master. Release flow: bump `versionCode`, tag `vX.Y.Z`,
    push the tag. Secrets: `ANDROID_KEYSTORE_BASE64`, `KEY_ALIAS`,
    `KEY_PASSWORD` (builds unsigned without them).
  - `nightly-health.yml` — daily probes (default dispenser, Play
    reachability), gplayapi-freshness check (warning only), unit tests.
  - `bootstrap-verification.yml` — regenerates dependency verification
    metadata (manual dispatch).

## Gotchas

- The Play API is reverse-engineered; upstream Google changes break it without
  warning. The nightly workflow is the early-warning system — treat
  `PlayError.ProtocolChanged`/`Unknown` spikes (via `PlayErrorMonitor` logs)
  as the first signal, not noise to suppress.
- Network is **HTTPS-only everywhere** (`usesCleartextTraffic=false`, dispenser
  URLs enforced HTTPS at dialog/VM/network layers). Don't add cleartext paths.
- The bundled Exodus API token is revoked upstream (degrades tracker reports
  only); override with `-PexodusApiKey=`, `EXODUS_API_KEY` env, or the CI
  secret. Per-app reports fall back to on-device DEX scanning.
- Self-update reads root `updates.json` (GitHub raw), **not** upstream's feed —
  fork APKs can't install over upstream's signature (and vice versa).
- R8 full mode is explicitly disabled (`gradle.properties`), and
  `android.usesSdkInManifest.disallowed=false` exists because AGP 9 disallows
  SDK-in-manifest which Shizuku support relies on — don't "clean up" these.
- Configuration cache is enabled; avoid code in build scripts that breaks it.
- Debug LeakCanary is wired via `debugImplementation`.
