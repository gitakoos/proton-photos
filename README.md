<p align="center">
  <img src="docs/logo.png" alt="Photos for Proton" width="96" />
</p>

<h1 align="center">Photos for Proton</h1>

<p align="center">
  End-to-end encrypted photo backup and browsing for your Proton Drive Photos library.<br />
  <strong>Unofficial</strong> open-source Android client built against the publicly documented Drive API by
  <a href="https://akoos.eu">Akoos</a>. Not affiliated with Proton AG.
</p>

<p align="center">
  <a href="https://github.com/gitakoos/proton-photos/releases/latest"><img src="https://img.shields.io/badge/release-v2.3.5-blue" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-green" alt="License" /></a>
  <img src="https://img.shields.io/badge/minSdk-26-orange" alt="Min SDK" />
  <a href="https://photos.akoos.eu"><img src="https://img.shields.io/badge/website-photos.akoos.eu-8B7CFF" alt="Website" /></a>
</p>

<p align="center">
  <a href="https://www.buymeacoffee.com/akoos"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-100f0f?logo=buymeacoffee&logoColor=FFDD00" alt="Buy me a coffee" /></a>
</p>

## Features

- End-to-end encryption via ProtonCore + GoOpenPGP.
- First-launch onboarding wizard: guides through backup mode, folder selection, metadata privacy, app lock, appearance, language, and permissions in a single step-through flow.
- Background sync: per-folder selection or "back up everything" with a per-folder exclude list, Wi-Fi-only toggle, continuous backup (uploads start within seconds of capture and resume after device reboot), three-photo parallel uploads.
- Reinstall pairing: previously backed-up photos rejoin Synced state automatically after a clean install.
- Delete after backup: optional toggle that removes the device copy as soon as the cloud upload succeeds, with a system trash consent notification that drains on unlock.
- Albums: every album is cloud-native and end-to-end encrypted: create, rename, add / remove photos, automatic cover that you can override with a long-press on any photo.
- Collection: a memories page on the Albums tab: "On this day" milestones (a year, two, five and ten years back) and seasonal sets like "Summer 2023", each grouping opening its own grid.
- Album sharing: invite people by email with viewer or editor roles and an optional message, manage members and pending invites, revoke access or stop sharing entirely. Albums shared with you appear in a dedicated tab and open instantly from cache, with large shared albums loading fast; "Save to my library" copies their photos into your own Photos.
- Mirror folders to Drive albums: pick device folders and the app keeps a matching encrypted album on Drive in sync as new photos land.
- Device folders: browse the folders on your phone, each on its own page; back up a whole folder (optionally mirrored as a Drive album) or hand-pick individual photos with live progress; delete device photos with the same options as the main grid.
- Built-in photo editor: eight adjustments (brightness, exposure, contrast, highlights, shadows, saturation, tone, temperature), filter, redact, rotate, free-form crop, undo / redo.
- Built-in video editor: trim, crop, rotate, music overlay with audio trim. Works on both device and cloud-hosted videos. Strips embedded GPS and other location metadata from the exported clip.
- Open-with support: hand a photo or video to the app from any file manager or gallery to view it full-screen, or to jump straight into the editor.
- Photo viewer: slideshow with video support (waits for clips to finish), pinch-zoom, "On this day" memories card.
- Pinch-to-zoom on the photos grid groups by day, month or year as you zoom in / out.
- Type badges on the timeline: device photos are tagged at a glance as video, motion / live photo, panorama or RAW.
- RAW support: DNG and other RAW photos get a thumbnail and preview at upload, so they show correctly across the app and on the web.
- Calendar view: a single continuous calendar that snaps one month per page, with a hero photo per day, an editable place + description, and the full grid of that day's photos and videos. A type-to-jump search overlay finds any month or year instantly.
- Photo map: open the map from Search to see your located photos plotted on a world map, on-device and cloud alike, with thumbnail pins. Tap a place to open a drawer of every photo taken there and save it as an album; search any city to jump to it. Dark map tiles in dark theme, and place names are resolved fully offline.
- Places: every location opens its own page: a hero, the photo + video count, and the full grid of everything taken there, with the same multi-select and save-as-album actions as the rest of the app.
- Search: filename, media type, sync state, year and month filters, with accent-insensitive matching so an unaccented query still finds accented names; the empty-state shows recent photos, an "On this day" carousel and a jump-to-month grid.
- Timeline scrubber on the photos grid for fast year-jump navigation.
- Multi-select: long-press a photo, or drag across the grid to sweep a range, in the timeline, albums and device folders. Bulk actions: back up to Drive, download, add to album, delete, hide, strip metadata; adding a local photo to a cloud album uploads it first, back up also works on a single photo from the viewer, and uploads keep running in the background after you leave. Mixed device + cloud selection is guarded so nothing is silently dropped.
- Share to other apps: send photos and videos to any other app from the gallery, an album, or a device folder; cloud-only photos download first, then share.
- Timeline filter: choose which device folders and album photos appear on the Photos timeline; display-only, so everything stays backed up and browsable.
- Cloud trash, in-app: browse deleted cloud photos, restore them, or empty the trash for good without leaving the app.
- Hidden vault behind biometric / PIN. Heavy blur overlay on cells and viewer.
- Per-field metadata stripping (GPS, camera, timestamps, software) on upload or in bulk.
- Mirror to local (opt-in): apply the same upload-time metadata strip and rename to the on-device original, so your local and cloud copies stay byte-for-byte identical and pair by content hash. Off by default, with its own onboarding step.
- Offline browsing: cached photos and videos work without a network connection.
- Background thumbnail cache: gallery populates instantly with visible cells resolved first, and a size-bounded background warm-up decrypts the rest of the library ahead of time so scrolling stays smooth.
- One-tap bulk free-up of already-backed-up device copies.
- Configurable app lock with timeout.
- In-app updater: checks the releases page for a newer build, then downloads and installs the APK from within the app.
- Home-screen widget: four modes including a Cloud Photos mode that pulls thumbnails from the encrypted on-disk cache, so the source bytes never enter the device's photo index or any other app's view.
- Sandbox hardening: TLS cert pinning on every Proton call (API + CDN), network allowlist (only proton.me, quad9.net, cloudflare-dns.com reachable; cleartext blocked), `allowBackup="false"` + empty `dataExtractionRules` (no state migrates via Google Drive auto-backup or device transfer), all `Log.*` stripped in release builds, StrictMode cleartext detector in debug.
- 6 languages (en, hu, de, fr, es, it), light / dark / system theme, 6 colour palettes (Default, Forest, Sunset, Sea, Sepia, Mono).

## Install

Download the latest APK from the [releases page](https://github.com/gitakoos/proton-photos/releases/latest) and tap to install.

## Architecture

Clean architecture in a single module, with strict one-way layer dependencies:

| Layer | Holds | Depends on |
|---|---|---|
| **Presentation** | Jetpack Compose screens, ViewModels, navigation, theme | Domain |
| **Domain** | Entities, repository interfaces, use cases, pure Kotlin, no Android imports | none |
| **Data** | Drive API client, crypto wrapper, Room database, DataStore, repository implementations, WorkManager workers | Domain |

Hilt binds the Data implementations to the Domain interfaces; every Proton Drive feature lives under `data/repository/drive/` (detailed below).

## Project structure

```
app/src/main/kotlin/eu/akoos/photos/
├── presentation/     UI: Composables + ViewModels
│   ├── auth/         Sign-in
│   ├── onboarding/   First-launch wizard (about, backup mode, folder pick, privacy, metadata mirror, lock, appearance, permissions)
│   ├── gallery/      Photos tab + multi-select
│   ├── albums/       Albums tab + detail + sharing
│   ├── shared/       Shared-with-me albums tab
│   ├── calendar/     Calendar view + per-day metadata editor
│   ├── map/          Photo map (osmdroid thumbnail pins, place drawer, city search)
│   ├── location/     Per-place detail page
│   ├── viewer/       Full-screen photo / video viewer
│   ├── editor/       Photo + video editor
│   ├── search/       Search with filename + content filters
│   ├── hidden/       Hidden vault (PIN / biometric)
│   ├── settings/     Settings, About, Privacy, Language, Theme, Palette, Trash, folder-mirror
│   ├── lock/         App lock
│   ├── updater/      In-app update orchestration
│   ├── common/       Shared composables (ErrorPopup, ConfirmDialog, EmptyState, ThemedSnackbar)
│   ├── util/         Presentation-layer helpers (formatters, focus helpers)
│   └── theme/        Colour tokens + palette factories
├── domain/           Pure domain layer
│   ├── entity/
│   ├── repository/   Interfaces
│   └── usecase/
├── data/             Implementations + IO
│   ├── api/          Retrofit Drive API + DTOs
│   ├── crypto/       DriveCryptoHelper (ProtonCore wrapper)
│   ├── db/           Room DAOs / entities / migrations
│   ├── repository/   incl. GpsBackfillScheduler + CloudGpsBackfillScheduler
│   │   │             (geotag extraction for on-device + cloud photos)
│   │   └── drive/    Drive backend split per concern (Upload, Download,
│   │                 Stream, Album, AlbumSharing, AlbumCryptoChain,
│   │                 CloudTrash, ThumbnailDecryptScheduler, …)
│   ├── preferences/  DataStore
│   ├── updater/      APK download + install
│   └── hidden/       Hidden-vault storage
├── di/               Hilt modules (Core, Network, Database, Repository,
│                     WorkManager, Updater, Stub)
├── navigation/       Single NavGraph
├── util/             Cross-cutting helpers (Exif, NetworkObserver, ErrorMessageSanitizer,
│                     OfflineGeocoder — offline reverse-geocoding from a bundled GeoNames dataset)
├── worker/           WorkManager workers
├── service/          Foreground service + boot receiver for continuous backup
├── widget/           Home-screen widget (4 modes incl. Cloud Photos from encrypted cache)
└── App.kt + MainActivity.kt
```

## Tech stack

- Kotlin · Jetpack Compose · Material 3
- Hilt (DI) · Room (DB) · Coil (images) · OkHttp + Retrofit (network) · Media3/ExoPlayer (video) · [osmdroid](https://github.com/osmdroid/osmdroid) (map tiles)
- Offline place names from the [GeoNames](https://www.geonames.org/) cities15000 dataset (CC BY 4.0), bundled under `app/src/main/assets`
- [ProtonCore Android](https://github.com/ProtonMail/protoncore_android) (GPL-3.0) + [GoOpenPGP](https://github.com/ProtonMail/gopenpgp) for auth, keys, crypto
- Drive API reference: [ProtonDriveApps/android-drive](https://github.com/ProtonDriveApps/android-drive)
- Min SDK 26 (Android 8) · Target SDK 35

## What's reused vs. what's built here

This project **uses the published `me.proton.core:*` libraries as-is** (no forks, no modifications). All Drive feature code is written from scratch against the publicly documented Drive API. Requests carry a client-specific app-version / User-Agent so Proton's servers see this independent client for what it is, rather than impersonating the official app.

**ProtonCore modules consumed** (all `me.proton.core:*` v36.6.0, GPL-3.0, via Maven Central; see `gradle/libs.versions.toml` for the exact list):

- Auth & account: `auth`, `account`, `account-manager`, `user`, `human-verification`
- Crypto & keys: `crypto`, `crypto-android`, `key`
- Network: `network`, `network-data`
- UI plumbing: `presentation`, `presentation-compose`, `account-manager-presentation-compose`, `human-verification-presentation`, `payment-presentation`
- Features & misc: `plan`, `feature-flag`, `data-room`, `util-*`, country/push/challenge/notification helpers, `*-dagger` bind modules for Hilt
- Recovery: `account-recovery`, `user-recovery`

Telemetry / observability modules are pulled in (transitively required by some dagger modules) but **the app does not emit any telemetry events**; there is no analytics call site anywhere in the codebase.

**What this app implements on top** (`app/src/main/kotlin/eu/akoos/photos/data/repository/drive/`):

| File | Responsibility |
|---|---|
| `PhotoUploadService` | Per-file streaming upload pipeline: 4 MB blocks → encrypt + sign → CDN PUT in parallel (bounded). Manifest = sorted block-hash concat + detached signature. Block spill files in `cacheDir`. |
| `PhotoDownloadService` | Cloud → device. Stream + decrypt + apply `DATE_TAKEN` from `captureTime + zone offset`. Optional album subfolder. |
| `PhotoStreamService` | Incremental cloud-state sync (mutation journal). Owns `createOrGetPhotosVolume` lazy bootstrap. |
| `AlbumService` | Album CRUD: create / rename / set-cover, add/remove photos (batched). Mirrors selected device folders into matching Drive albums. |
| `AlbumCryptoChain` | Single source of truth for album-share key selection: album NodeKey decrypt, photo parent-key resolution, and the share-context bundle that downstream code reuses. |
| `AlbumSharingService` | Public link mint, email invite (PKESK encrypt to invitee + sign) with viewer / editor roles, member + invitation management (revoke / remove), leave or stop sharing, shared-with-me (primary + v2 backup endpoints), accept / decline, and server-side "Save to my library" copy. The multi-step share popup (email chips, role picker, optional message) is wired through this service. |
| `PhotosShareService` | Per-user key cache (volumeId, shareId, rootLinkId, rootLinkKeyBytes, rootNodeHashKey) + shared API semaphore. Wiped on sign-out. |
| `PhotosVolumeBootstrap` | First-run Photos volume + share + root-link creation. Handles `ALREADY_EXISTS` race via `getVolumes()` fallback. |
| `CloudTrashService` | Cloud trash listing, restore (`moveTrashLinks`), permanent delete; surfaced as the in-app Trash screen. |
| `ThumbnailDecryptScheduler` | On-demand + background thumbnail decryption: resolves visible cells first (priority-ordered with prefetch), warms the rest of the library in the background, and keeps the decrypted-thumbnail cache within a size budget by evicting the least-recently-used. |
| `RecentUploadsTracker` | Short-TTL (90 s) recently-uploaded-linkIds map; stops stale rows from blocking cloud-delete propagation. |
| `LinkDetailHelpers`, `ThumbnailHelpers`, `PhotoEntityBuilder` | Shared helpers (batch link metadata, JPEG thumbnail ≤512 px, DTO → Room entity mapping). |

Plus the layers around them:

- `data/api/`: Retrofit interface + DTOs for `/drive/photos/*`, `/drive/v2/*`, block uploads, sharing endpoints.
- `data/crypto/DriveCryptoHelper.kt`: thin wrapper over `me.proton.core:crypto*` (no custom crypto primitives; PGP delegated entirely to upstream).
- `data/db/`: Room schema for the local mutation journal (`PhotoListingEntity`, `SyncStateEntity`) with exported schemas + migrations.
- `domain/usecase/`: `UploadPendingUseCase`, `ReconcileSyncStateUseCase`, `DownloadPhotosUseCase`, `FreeUpSpaceUseCase`, `DeletePhotoUseCase`, `GetGalleryItemsUseCase`, `CategorizeItem`.
- `worker/`: `SyncWorker`, `AlbumDownloadWorker`, `FreeUpSpaceWorker`, `CachePruneWorker` (WorkManager + foreground service notifications, battery-aware constraints).
- `presentation/`: Jetpack Compose UI (gallery, viewer, editor, albums, settings, …) and ViewModels.

No private endpoints, no undocumented APIs, no obfuscation (`-dontobfuscate` in `proguard-rules.pro` so the released APK can be byte-compared to a fresh build from this source).

## Disclaimer

Not affiliated with Proton AG. Proton, Proton Drive, and Proton Photos are trademarks of Proton AG. Built against the publicly documented Drive API with no proprietary code from Proton.

## Author

[**Akoos**](https://akoos.eu): creator and maintainer.

## License

[GPL-3.0](LICENSE): matches the upstream `me.proton.core:*` libraries this app links against.
