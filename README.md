# Aurora Store

Aurora Store enables you to search and download apps from the official Google Play store. You can check app descriptions, screenshots, updates, reviews, and download the APK directly from Google Play to your device. 

To use Aurora Store, log in using Google Play account, when you first open and configure Aurora Store.

Unlike a traditional app store, Aurora Store does not own, license or distribute any apps. All apps, app descriptions, screenshots and other content in Aurora Store are directly accessed, downloaded and/or displayed from Google Play. 

Aurora Store works exactly like a door or a browser, allowing you to log in to your Google Play account and find the apps from Google Play. 

*_Please note that Aurora Store does not have any approval, sponsorship or authorization from Google, Google Play, any apps downloaded through Aurora Store or any app developers; neither does Aurora Store have any affiliation, cooperation or connection with them._*

[<img src="https://img.shields.io/github/v/release/dwicao/AuroraStore?label=Download%20APK&color=brightgreen" alt="Download APK from GitHub Releases">](https://github.com/dwicao/AuroraStore/releases)

## Screenshots

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot-01.png" height="400"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot-03.png" height="400"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot-07.png" height="400"> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot-08.png" height="400">

## Features

- FOSS: Has GPLv3 licence
- Beautiful design: Built upon latest Material 3 guidelines
- Account login: You can login with either personal or an anonymous account
- Device & Locale spoofing: Change your device and/or locale to access geo locked apps
- [Exodus Privacy](https://exodus-privacy.eu.org/) integration: Instantly see trackers in app
- [Plexus](https://plexus.techlore.tech/) integration: Instantly see app compatibility without Google Play Services or with microG
- Updates blacklisting: Ignore updates for specific apps
- Download manager
- Manual downloads: allows you to download older version of apps, provided
  - The APKs are available with Google
  - You know the version codes for older versions 

## Limitations

- The underlying API used is reversed engineered from the Google Play Store, changes on side may break it.
- Provides only base minimum features
  - Can not download or update paid apps.
  - Can not update apps/games with [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery)
- Multiple in-app features are not available if logged in as Anonymous.
  - Library
  - Purchase History
  - Editor's choice
  - Beta Programs
  - Review Add/Update
- Token dispenser server is not super reliable, downtimes are expected.  

## Fork differences (vs upstream AuroraOSS)

This fork ships changes on top of upstream, mostly around reliability, downloads
and security. Upstream behavior is unchanged everywhere else.

- **Splash/auth hardening.** Session validation and re-mint are bounded by
  timeouts and cancellable, the splash screen gains a watchdog with an explicit
  Retry button, and offline startup shows "No network" instead of spinning
  forever. This fixes the "stuck loading after not opening the app for a while"
  case (expired session + slow/dead network with no way to recover).
- **Shizuku as the default installer.** On every app start Aurora checks whether
  the Shizuku permission is held and installs via Shizuku when it is. On a fresh
  install it shows the Shizuku grant popup once (only when onboarding is done and
  the installer is still the Session default); denying it disables further
  auto-prompts, and picking Shizuku manually from the Installer screen always
  works. After a clear-data the onboarding default is Shizuku too when the
  permission is already held, so no re-configuration is needed.
- **Play API boundary (`PlayGateway`).** All Play API calls go through a single
  choke point mapping failures onto a small `PlayError` taxonomy, emitting
  session-expired redirects uniformly and counting unexpected failures so an
  upstream Google change shows up in logcat before it fully breaks. Previously
  uncaught call sites (unarchive receiver, search suggestions, installed-list
  details, capability probe) no longer crash on session rejection. Background
  workers (auth refresh, update check, per-account sessions) and the Accounts
  screen operations are time-bounded the same way.
- **Dispenser health tracking.** Anonymous logins pick the healthiest dispenser
  (recent failures are skipped with exponential backoff) instead of pure random,
  the Dispenser screen shows per-URL backoff status, and a missing dispenser
  configuration points at Settings instead of a generic internal error.
  Dispenser URLs must be HTTPS at every layer (dialog, ViewModel
  normalization, network call) so tokens are never minted over cleartext.
- **Auth material isolated from backups.** Sign-in state, account type, e-mail,
  AAS/AUTH tokens, the saved session and the microG flag live in a dedicated
  `auth_prefs.xml`, excluded from cloud and device-transfer backups in both
  rules files (existing installs migrate transparently on next write). A logging
  audit confirmed no tokens are printed or surfaced anywhere on the auth paths.
  Note: the Room account rows still mirror these secrets; full separation would
  require moving the account table to its own database, which was deliberately
  deferred to avoid a data migration.
- **Startup account reconciliation.** On cold start the Room account DB (source
  of truth) is reconciled against the legacy SharedPreferences mirror, and the
  stale sibling token is cleared on login — fixing drift from backup restores or
  crashes mid-write.
- **Download hardening.** Range-rejected (416) partials restart from zero once
  instead of looping, transient 5xx responses are retried with backoff, and the
  Exodus Privacy integration retries transient failures instead of failing
  silently on the first attempt.
- **Pause/resume downloads.** Any active download can be paused per app (row,
  bottom sheet, or app details screen) and resumed later from its partial files;
  "pause all / resume all" exists in the Downloads menu and in each Updates
  section header. Paused rows leave the serial download slot, are never
  silently resumed by re-enqueues or retries, survive CacheWorker cleanups,
  the resume button seeds the displayed progress from bytes already on disk,
  and a stuck-queue watchdog re-triggers QUEUED rows left behind by a dropped
  expedited request. The app-details screen shows Pause (+Cancel) while
  downloading and Resume (+Cancel) while paused, and goes back to Open when
  done.
- **Spoof hardening.** A failed native property read no longer poisons the
  process (falls back to an empty profile, degrading to the default profile via
  the existing validation), and a corrupt stored spoof disables itself with a
  toast instead of throwing out of every Play call.
- **Auth material encrypted at rest.** `auth_prefs.xml` is an
  `EncryptedSharedPreferences` backed by an Android Keystore key (plain private
  file as fallback, migrated once), on top of the backup exclusion below.
- **Self-update from this fork's releases.** Release builds read
  `updates.json` at the repo root (served raw by GitHub) instead of upstream's
  feed — whose APKs could never install over this fork's signature. The feed
  follows the `SelfUpdate` schema (`version_code` as string); the version code
  in `app/build.gradle.kts` must keep increasing with each fork release.
- **CI.** `.github/workflows/release.yml` builds a signed release APK and
  attaches it to the GitHub Release on `v*` tags;
  `.github/workflows/nightly-health.yml` runs daily dispenser/Play probes, a
  gplayapi freshness check, and the JVM unit tests as an early warning for
  upstream breakage.

Per-release ritual for the maintainer: bump `versionCode`, tag, let CI publish
the release, then fill `updates.json` (`version_code`, `download_url` pointing
at the release asset, `size`, `sha256` of the APK, changelog) and commit it —
the next update check on installed forks picks the fork update up automatically.

Releases from this fork are signed with a different key than upstream:

- SHA1: `E0:B5:7C:53:D3:4F:F1:1E:E6:00:06:13:55:C7:B3:33:C6:6A:BD:37`
- SHA256: `44:9E:09:99:C0:AF:C2:A3:1F:BF:9D:CA:95:A0:80:07:CD:A3:84:B5:15:54:9F:66:BE:6B:CF:85:21:7F:75:3F`

## AI Usage

Aurora Store is developed with the help of AI tooling. Here is what that means.

**In this project**

- AI assistance is used in day to day development: writing and refactoring code,
  reviewing diffs, and drafting documentation.
- Nothing gets merged that a maintainer has not read, understood and tested.
  Generated code is a starting point, not a finished result.
- Accountability does not change. Whatever ships in Aurora Store is the
  maintainers' responsibility, no matter how it was written.

**In contributions**

- You are welcome to use AI tooling on your contributions. Just mention it in the
  merge request description.
- You are still responsible for what you send. Be ready to explain how your change
  works, and to show that you built and tested it yourself.
- Merge requests and issue reports that are plainly unreviewed model output (calls
  to APIs that do not exist, unrelated changes, made up bug reports) will be closed
  without review.

## Downloads

This is a fork — get the signed APKs from this repo's releases:

- [GitHub Releases](https://github.com/dwicao/AuroraStore/releases) (signed with the [fork key](#fork-differences-vs-upstream-auroraoss))

The upstream channels below carry upstream builds signed with upstream's key,
which will not install over this fork (and vice versa):

- [Official website](https://auroraoss.com/)
- [GitLab Releases](https://gitlab.com/AuroraOSS/AuroraStore/-/releases)
- [IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/com.aurora.store) (reproducible)
- [F-Droid](https://f-droid.org/packages/com.aurora.store/) (signed by F-Droid, [more details](https://f-droid.org/docs/Signing_Process/))
- [App Gallery](https://appgallery.huawei.com/app/C110907863) (limited to certain countries)

## Certificate Fingerprints

- SHA1: 94:42:75:D7:59:8B:C0:3E:48:85:06:06:42:25:A7:19:90:A2:22:02
- SHA256: 4C:62:61:57:AD:02:BD:A3:40:1A:72:63:55:5F:68:A7:96:63:FC:3E:13:A4:D4:36:9A:12:57:09:41:AA:28:0F

## Support

Aurora Store v4 is still in on-going development! Bugs are to be expected! Any bug reports are appreciated.
Please visit [Aurora Wiki](https://gitlab.com/AuroraOSS/AuroraStore/-/wikis/home) for FAQs.

- [Telegram](https://t.me/AuroraSupport)
- [XDA Developers](https://forum.xda-developers.com/t/app-5-0-aurora-store-open-source-google-play-client.3739733/)

## Permissions

- `android.permission.INTERNET` to download and install/update apps from the Google Play servers
- `android.permission.ACCESS_NETWORK_STATE` to check internet availability
- `android.permission.FOREGROUND_SERVICE` to download apps without interruption
- `android.permission.FOREGROUND_SERVICE_DATA_SYNC` to download apps without interruption
- `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` to auto-update apps without interruption (optional)
- `android.permission.MANAGE_EXTERNAL_STORAGE` to access the OBB directory to download APK expansion files for games or large apps
- `android.permission.READ_EXTERNAL_STORAGE` to access the OBB directory to download APK expansion files for games or large apps
- `android.permission.WRITE_EXTERNAL_STORAGE` to access the OBB directory to download APK expansion files for games or large apps
- `android.permission.QUERY_ALL_PACKAGES` to check updates for all installed apps
- `android.permission.REQUEST_INSTALL_PACKAGES` to install and update apps
- `android.permission.REQUEST_DELETE_PACKAGES` to uninstall apps
- `android.permission.ENFORCE_UPDATE_OWNERSHIP` to silently update apps
- `android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION` to silently update apps
- `android.permission.POST_NOTIFICATIONS` to notify user about ongoing downloads, available updates, and errors (optional)
- `android.permission.USE_CREDENTIALS` to allow users to sign into their personal Google account via microG

## Translations

Don't see your preferred language? Click on the widget below to help translate Aurora Store!

<a href="https://hosted.weblate.org/engage/aurora-store/">
  <img src="https://hosted.weblate.org/widgets/aurora-store/-/287x66-grey.png" alt="Translation status" />
</a>

## Donations

You can support Aurora Store's development financially via options below. For more options, checkout the **About** page within the Aurora Store.

[![Liberapay](https://liberapay.com/assets/widgets/donate.svg)](https://liberapay.com/whyorean)
<a href="https://www.paypal.com/paypalme/AuroraDev">
  <img src="https://www.paypalobjects.com/webstatic/mktg/logo/AM_mc_vs_dc_ae.jpg" height="45" alt="PayPal">
</a>

## Project references

Aurora Store is based on these projects

- [YalpStore](https://github.com/yeriomin/YalpStore)
- [AppCrawler](https://github.com/Akdeniz/google-play-crawler)
- [Raccoon](https://github.com/onyxbits/raccoon4)
- [SAI](https://github.com/Aefyr/SAI)
