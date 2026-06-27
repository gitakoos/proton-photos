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
  <a href="https://github.com/gitakoos/proton-photos/releases/latest"><img src="https://img.shields.io/github/v/release/gitakoos/proton-photos?label=release&color=blue" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-green" alt="License" /></a>
  <img src="https://img.shields.io/badge/minSdk-26-orange" alt="Min SDK" />
  <a href="https://photos.akoos.eu"><img src="https://img.shields.io/badge/website-photos.akoos.eu-8B7CFF" alt="Website" /></a>
</p>

<p align="center">
  <a href="https://www.buymeacoffee.com/akoos"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-100f0f?logo=buymeacoffee&logoColor=FFDD00" alt="Buy me a coffee" /></a>
</p>

## Features

| Area | What you get |
| --- | --- |
| **End-to-end encrypted** | Photos, videos, albums and names encrypted with ProtonCore + GoOpenPGP. |
| **Backup** | Per-folder or back-up-everything (with a per-folder exclude list), Wi-Fi-only, continuous (uploads start seconds after capture and resume after a reboot), parallel uploads. Auto re-pairs your library after a reinstall; optional delete-after-backup and one-tap free-up of already-backed-up copies. |
| **Browse & find** | Pinch-zoom timeline that regroups by day, month or year, a year-jump scrubber, a calendar view (a hero photo and editable place per day, type-to-jump search), a photo map of your located shots with offline place names, per-place pages, search (type, date and sync filters, accent-insensitive), and a memories page (On this day, seasons). |
| **Albums & sharing** | Cloud-native encrypted albums with custom covers; share by email with viewer or editor roles, manage members and invites, and save albums shared with you into your own library; mirror device folders to matching Drive albums. |
| **Organize** | Multi-select by long-press or by dragging across the grid, in the timeline, albums and device folders, with bulk back-up, download, add-to-album, delete, hide and strip; per-folder browsing; a display-only timeline filter; share to any other app. |
| **Edit** | Photo editor (eight adjustments, filter, redact, rotate, free-form crop, undo and redo) and video editor (trim, crop, rotate, music overlay, strips location metadata); RAW and DNG previews; open a photo from any app straight into the viewer or editor. |
| **Privacy & lock** | Hidden vault behind biometric or PIN with a blur overlay, per-field metadata stripping (GPS, camera, timestamp, software) on upload or in bulk, mirror-to-local so your on-device copy matches the cloud, in-app cloud trash, and a configurable app lock with timeout. |
| **Hardened** | TLS cert pinning on every Proton call, a strict host allowlist with cleartext blocked, no app state carried in cloud auto-backup or device transfer, and logs stripped from release builds. |
| **Comfort** | Offline browsing of cached photos, a full-screen viewer with slideshow, a background thumbnail warm-up for smooth scrolling, a home-screen widget (including a Cloud Photos mode served from the encrypted cache), an in-app FAQ, an in-app updater, 11 languages, light, dark and system themes, and 6 colour palettes. |

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
│   ├── folders/      Per-device-folder detail + multi-select
│   ├── memories/     On this day + seasons memories
│   ├── calendar/     Calendar view + per-day metadata editor
│   ├── map/          Photo map (osmdroid thumbnail pins, place drawer, city search)
│   ├── location/     Per-place detail page
│   ├── viewer/       Full-screen photo / video viewer
│   ├── editor/       Photo + video editor
│   ├── search/       Search with filename + content filters
│   ├── hidden/       Hidden vault (PIN / biometric)
│   ├── settings/     Settings, About, Privacy, Language, Theme, Palette, Trash, FAQ, folder-mirror
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
