# Storage Doctor: Google Play declaration and submission checklist

Reviewed **2026-11-23**. Package: `com.natadej.storagedoctor`. This is preparation for review,
not a claim of Google approval. The developer must publish an accurate listing/privacy policy,
record the actual release build and complete the Console forms. Do not select antivirus,
backup/restore, or another category this app does not implement.

## Current target requirement and compatibility

Google's [target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
says: **“Starting August 31, 2026: New apps and app updates must target Android 16 (API level 36)
or higher”** for ordinary phone/tablet apps. The extension ended November 1, 2026; it is no
longer available on this review date. The API 35 requirement for *existing, unchanged* apps'
discoverability is not sufficient for a new submission/update.

- `compileSdk` and `targetSdk` are 36; `minSdk` stays 26.
- [Google's minimum-tools table](https://developer.android.com/build/releases/about-agp#api-level-support)
  lists AGP **8.9.1** for API 36. This branch raises only AGP 8.7.3 → 8.9.1; the existing
  Gradle **8.11.1**, JDK **17**, Kotlin **2.0.21**, Compose and other dependencies remain sufficient.
  Version is `20261123.01` / `2026112301`, retaining the existing monotonically increasing
  date-based scheme. Its intentional high value is locally lint-suppressed, not lowered below
  already distributed builds. Play's maximum is 2100000000; future uploads still need unique codes.
- [Android 16 target changes](https://developer.android.com/about/versions/16/behavior-changes-16):
  edge-to-edge cannot be disabled. The Activity already calls `enableEdgeToEdge`; Main uses
  Material 3 Scaffold/TopAppBar insets. The permission/disclosure page now uses safe-drawing
  insets and scrolls, including on short/landscape/large-font displays. Dialogs scroll too.
  No orientation/resizability lock is declared; UI uses flexible layouts and scrollable lists.
  Back navigation already uses Compose `BackHandler`, not obsolete `onBackPressed` interception.
- [Android 16 all-app changes](https://developer.android.com/about/versions/16/behavior-changes-all):
  JobScheduler quotas affect WorkManager. The opt-in six-hour worker only reads total/free space
  and posts a local notification; it does not run a file scan, transfer data or request expedited
  work. Scheduling is approximate. Manual scans run in the ViewModel; there are no app-started
  foreground services, exact alarms or long-running foreground workers to migrate.
- Android's scoped-storage restrictions remain: All files access does not open other apps'
  private directories or `Android/data`/`Android/obb`. Those paths are skipped; the app does
  not promise access to them. It scans the primary shared-storage root, not every private or
  removable volume. Media trash/deletion uses Android's confirmation APIs on Android 11+.
- Before release, test grant, denial and revocation on Android 11 and 16, edge-to-edge and
  navigation in gesture/three-button modes, rotation, tablet resizing and large text. Unit tests,
  lint and a debug build do not replace these device checks or Play's own review.

## Permission inventory and eligibility

| Permission | Why it is present / how requested |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Android 11+: shared-file/folder analysis and maintenance, including non-media. Only the explicit disclosure-screen Agree button opens special-access Settings. No automatic Settings launch or scan without a grant. |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` | Capped at API **29**, only for Android 8–10 legacy shared-storage scan/deletion. Requested together only after the disclosure's Agree button on those OS versions. `requestLegacyExternalStorage` is for Android 10 only. |
| `PACKAGE_USAGE_STATS` | Optional, separate user action opens Usage access Settings. Describes visible-app storage sizes and local history. File scanning works without it. This does not read browsing history or app interactions. |
| `POST_NOTIFICATIONS` | Optional runtime prompt on Android 13+ when the user enables storage alerts. Denial does not block scanning or storage totals. |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VISUAL_USER_SELECTED` | **Not requested.** No extra granular-media grant is stacked on All files access. |
| `QUERY_ALL_PACKAGES` | **Removed.** Optional per-app sizes use Android's filtered package visibility; the app no longer promises a full installed-app inventory. |
| Internet, contacts, location, camera, microphone, accessibility, install/delete packages | **Not requested.** No analytics, advertising, backend or account SDK. |
| WorkManager merged permissions | The dependency adds `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE` and `FOREGROUND_SERVICE`, plus an internal dynamic-receiver permission. These are not extra storage grants. The app uses ordinary periodic work, no network constraints, no `setForeground`/long-running worker and no app-started foreground service. `ACCESS_NETWORK_STATE` is not Internet access. |

Official [All files access policy](https://support.google.com/googleplay/android-developer/answer/10467955)
permits **File management**, whose core purpose includes “access, editing, and management
(including maintenance) of files and folders outside of its app-specific storage space.”
Storage Doctor's folder exploration, cross-folder analysis and user-confirmed file maintenance
are the basis for that declaration. “Storage cleaner” is not a blanket exception or automatic
approval: Google must accept that these are the prominently advertised core functions.
Do not market the app only as a storage meter, performance booster or media picker.

The [Android All-files guide](https://developer.android.com/training/data-storage/manage-all-files)
requires using narrower APIs when they can provide the core experience. **MediaStore could cover
a media-only cleaner; SAF could cover user-selected files/folders.** Those would be viable
reduced-scope products. They do not cover this app's current primary-shared-storage-wide
non-media directory traversal, folder-size/growth snapshots, generated-file detection and
cross-folder duplicate/cleanup plan. Android 11+ SAF also restricts grants to storage roots and
the Download root. This is not an assertion that SAF can never scan a selected subtree.
If Google rejects this justification, do not re-label or hide the permission: narrow the actual
feature set and remove All-files access in a separately reviewed implementation.

## Exact All files access declaration text

Use the **File management** permitted-use option in the form. Paste the following into the
corresponding free-text fields; where the form has one explanation field, combine these paragraphs.
Do not paste headings or claim a video has been recorded until it has.

### Core purpose / feature description

> Storage Doctor is an on-device shared-file and folder analysis and maintenance app. Its core features are a folder-size explorer, identification of large and duplicate files, non-media files such as downloads/documents/AI models, folders generating files, growth between scans, and a user-reviewed cleanup plan. Users can inspect supported files in a chosen viewer and explicitly confirm removal. This is file management/maintenance, not antivirus, backup or a photo picker.

### Why All files access is necessary / why narrower APIs are insufficient

> The core scan traverses the primary shared-storage directory tree, including non-media files outside our app-specific directory. It needs paths, sizes and modification dates for folder totals and growth, and reads file contents for duplicate comparison. MediaStore alone cannot supply the complete non-media hierarchy. SAF can support a selected-folder workflow, but cannot grant unrestricted traversal of the storage/Download roots on Android 11+ and would limit cross-folder totals, growth, duplicate detection and the unified cleanup plan to selected subtrees. Without All files access these core file-management functions are disabled; a limited storage-total/alert mode remains. We do not access other apps' private directories or Android/data/obb, and do not claim to bypass Android's restrictions.

### How permission/data are used and safeguarded

> Before opening Android's All files access Settings, an in-app disclosure explains the shared-file data read, duplicate content comparison, local history, file-opening disclosure and user-confirmed deletion. Users must tap Agree or can choose Not now to use totals/optional alerts. Scans do not start without the Android grant. Access can be revoked; scanning is cancelled and file results/selections are cleared. Only user-confirmed supported files are removed, with Android media-trash confirmation where available. Processing and history stay on-device; the app has no network permission, ads, analytics or accounts. The opt-in background worker reads disk totals only, not file contents. Optional Usage access is separate and limited to visible-app sizes.

### Video URL

Paste the actual publicly accessible/unlisted demonstration URL after recording the script below.
Do not use a placeholder, a login-required link, screenshots or a video of another build.

## Reviewer demo video script (about 3–4 minutes)

Use a clean test phone/emulator with Android 16 and the **release candidate** installed. Disable
All files access before recording, using Android Settings, not an ADB grant during the video.
Preload only synthetic files: two identical non-media `.bin` files over 1 MB in different shared
subfolders, a disposable `.gguf` over 20 MB for a cleanup finding, and generated-name files in a
test folder if demonstrating that feature. Do not use real photos, chats, documents or app inventory.
Record the actual UI without editing away the consent or system-permission steps.

1. **0:00–0:40, disclosure and denial.** Launch Storage Doctor. Slowly scroll the prominent
   “File access for storage maintenance” disclosure, including local processing, deletion,
   background totals and the decline option. Tap **Not now — use storage totals**. Show that
   Overview has total/used/free storage, target control and limited-mode message. Open another
   tab to show scans/cleanup are unavailable, not silently operating or crashing.
2. **0:40–1:15, explicit grant.** Tap **File access**, show the disclosure again, then tap
   **Agree and open file access settings**. Show Android's app-specific All files access page
   and manually enable the switch. Return to Storage Doctor. No Usage or notification grant
   is needed for the core scan; leave them disabled for this demonstration.
3. **1:15–2:00, core non-media management.** Show the scan and Overview category totals.
   Open **Explore**, navigate the synthetic shared folders and show folder totals. Open
   **AI & runaway** / **Cleanup** to show the disposable non-media model/other findings and
   the plan. Explain that this is a cross-folder analysis, not a request to pick a photo.
4. **2:00–2:45, inspect and maintain.** In **Duplicates**, run duplicate comparison and show
   the synthetic pair from different folders. Use **Open** on a supported test file if a safe
   viewer is installed. Select only a disposable candidate, tap **Clean up**, show the explicit
   permanent-deletion warning, first cancel, then confirm. Show that no deletion occurs before
   confirmation. If showing media trash as well, use synthetic media and include Android's
   own confirmation; never claim the app can silently delete every protected file.
5. **2:45–3:30, revoke and privacy.** Turn All files access off in Android Settings, return to
   the app and choose limited mode if the disclosure is displayed. Show file operations are off
   and totals still work. Open **Privacy policy** from Overview. Explain that alerts are opt-in,
   local totals only, and that no server/account/login is required.

Upload unlisted to YouTube or a publicly viewable video host. Check the link in a signed-out
browser and ensure its resolution makes the disclosure and switches readable. Do not submit
mock UI footage. The video has **not** been recorded as part of the source/build checks.

## Data safety answers (this Android build only)

Audit basis: `AndroidManifest.xml`, Gradle dependencies, `Store`, `Scanner`, `Duplicates`,
`Device`, `Cleaner`, `Opener` and `MonitorWorker`. No HTTP/socket clients, analytics, crash-upload,
advertising or cloud SDKs are used. The app has no Internet permission. WorkManager is for a local
periodic job, not telemetry. `allowBackup=false`, legacy full backup is disabled, and Android 12+
data-extraction rules exclude cloud backup and device transfer for every app-data domain.
Recheck the merged release manifest/dependencies
before submission, especially if adding SDKs later. The Mac Python app is a separate product.

According to Google's [Data safety definitions](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en),
processing only on-device is not collection; a user-initiated expected file transfer to a chosen
viewer is exempt from the sharing declaration. The app must still explain that transfer, and does.

| Form question / section | Answer for this build |
|---|---|
| Does your app collect or share any required user data types? | **No.** No off-device transmission or automatic sharing. Do not label local-only file inspection as server collection. |
| Collected/shared data types (files/docs, photos/video/audio, app activity, app inventory, identifiers, etc.) | **None collected or shared** under these definitions. The form normally skips these subquestions after No. |
| Encryption in transit | No app data is transmitted. Leave inapplicable follow-up questions unselected; do not claim an implemented TLS backend or independent security certification. |
| Account creation / login methods | No account creation or login; select **My app does not allow users to create an account**, if shown. |
| Account-deletion URL | Not applicable: no accounts or developer-held server data. Do not invent a deletion-service URL. |
| User data deletion | Local settings/history can be removed through Android Settings → Apps → Storage Doctor → Storage & cache → Clear storage, or uninstall. Shared files are separate and are not deleted by uninstalling. |
| Independent security review | **No**, unless the owner separately obtains one. |
| Ads | **No** (also answer No in App content → Ads). |

Local processing still includes sensitive information: paths/names, sizes/dates, duplicate-file
contents, and optionally visible-app package names/sizes. Up to 20 scan snapshots (folder paths/
sizes, visible-app package names/sizes and timestamps) and preferences are stored privately;
no cloud backup/upload is implemented. FileProvider grants read access only when the user selects
**Open** and a viewer. That viewer's own behavior is outside this app's control. Do not promise
that another app will never upload a file the user chooses to open there.

## Public privacy policy and listing

The app now has a readable in-app **Privacy policy** in both the disclosure screen and Overview.
Before submission, publish the **same policy text** from `ui/Privacy.kt` at a public, non-geoblocked,
non-editable HTTPS page identifying Storage Doctor and developer natadej with the contact mechanism.
Use the developer's actual legal/contact details if different, and update both copies together.
It must cover local processing, retention/removal, no network/ads/accounts, optional permissions,
and user-initiated opening in another app. A source-file URL or this internal checklist is not
a substitute for a clearly titled public privacy policy. Hosting that page and providing its URL
are owner actions; no public policy URL is invented here.

Suggested accurate store-listing paragraph (use alongside screenshots of these features):

> Storage Doctor is an offline shared-file and folder analysis and maintenance tool. Explore folder sizes, find large and duplicate files, identify downloaded AI models and generated-file growth, and review a cleanup plan before choosing what to remove. All files access enables cross-folder analysis of shared storage, including non-media files; Android private directories remain protected. You can decline access and still use storage totals and optional local alerts. Optional Usage access shows sizes for apps Android makes visible to Storage Doctor. No accounts, ads or data uploads.

Do not advertise access to all app-private caches, “one tap safe clean everything”, full installed-app
inventory, antivirus protection, RAM/CPU boosting, or guaranteed Play approval.

## Exact Play Console actions for Minn

Labels may move between Console revisions; use the named sections, not a guessed external form URL.
Start at [Play Console](https://play.google.com/console/).

1. **All apps → Create app** (if not already created): choose Storage Doctor, default language,
   App, the real free/paid setting, and required declarations → **Create app**. For an existing
   record, open it and verify the package is `com.natadej.storagedoctor`.
2. **Test and release → Testing → Internal testing → Create new release**. Complete Play App
   Signing setup if prompted. Upload the publisher's **signed release AAB** using the registered
   upload key and a fresh versionCode (not this debug APK, and not the source's debug-key-signed
   release build). Wait for processing; verify target API **36** in the artifact details.
   Save as a draft; there is no requirement to start a production rollout for this step.
3. **Policy and programs → App content → Sensitive app permissions → All files access → Start/Manage**.
   It may appear as **All files access permission** directly on App content. If absent, first
   upload/process an AAB declaring the permission and refresh. Select **File management**,
   paste the field texts above, attach the actual demo URL, answer the grant/alternative questions
   truthfully and **Save**. Do not select unrelated use cases. There should be no broad package
   visibility declaration for this artifact after `QUERY_ALL_PACKAGES` removal; retire older
   draft artifacts requesting it if Console still flags them.
4. **App content → Privacy policy → Start/Manage**: paste the published policy's real HTTPS URL
   → **Save**. **App content → Data safety → Start/Manage → Next**: answer No collection/sharing
   and the applicable answers above, review the preview, certify accuracy → **Save**.
5. **App content → Ads → Start/Manage → No → Save**. Complete **App access** with no account/login
   restrictions; give instructions to grant optional device permissions and explain limited mode
   if there is an instructions field. Complete **Content rating** and **Target audience and content**
   based on the actual general-purpose utility and intended audience, not fabricated answers.
   Complete any Console-specific permission questions for the actual merged artifact.
6. **Grow users → Store presence → Main store listing**: accurately describe file/folder maintenance,
   add icon, screenshots and contact details, and save. Record any required developer verification,
   testing eligibility or app-availability steps shown on the dashboard.
7. Review **Publishing overview / Policy status** and fix any warnings. Only the owner should choose
   when to send the declarations/release for review and start any testing/rollout. Google may ask
   for a clearer demo or reject All-files eligibility. Code/build success cannot guarantee review.

No Console settings, declarations, signing keys, policy page, video or live release are created
or submitted by this branch. Those remaining owner actions must be completed before review.
