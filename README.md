# Proton Photos for Android

> **Unofficial** open-source Proton Drive Photos client for Android. Built against the publicly documented Drive API.

[![Release](https://img.shields.io/badge/release-beta-blue)](https://github.com/gitakoos/proton-photos/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-green)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-26-orange)](https://developer.android.com/about/dashboards)

End-to-end encrypted photo backup and browsing for your Proton Drive Photos library.

**Website:** [photos.akoos.eu](https://photos.akoos.eu)

## Features

- End-to-end encryption via ProtonCore + GoOpenPGP.
- Background sync — per-folder selection or "back up everything", Wi-Fi-only toggle, configurable interval.
- Reinstall pairing — previously backed-up photos rejoin Synced state automatically after a clean install.
- Albums — create, rename, add / remove photos, quick-set cover via long-press on any photo.
- Multi-step share dialog — email chips, viewer / editor permissions, optional invite message.
- Built-in photo editor — eight adjustments (brightness, exposure, contrast, highlights, shadows, saturation, tone, temperature), filter, redact, rotate, free-form crop, undo / redo.
- Built-in video editor — trim, crop, rotate, music overlay with audio trim. Works on both device and cloud-hosted videos.
- Photo viewer — slideshow with video support (waits for clips to finish), pinch-zoom, "On this day" memories card.
- Pinch-to-zoom on the photos grid groups by day, month or year as you zoom in / out.
- Calendar view — every day on a calendar, with a hero photo per day, an editable place + description, and the full grid of that day's photos and videos.
- Search — filename, media type, sync state, year and month filters; the empty-state shows recent photos, an "On this day" carousel and a jump-to-month grid.
- Timeline scrubber on the photos grid for fast year-jump navigation.
- Multi-select bulk actions — download, add to album, delete, hide, strip metadata.
- Hidden vault behind biometric / PIN. Heavy blur overlay on cells and viewer.
- Per-field metadata stripping (GPS, camera, timestamps, software) on upload or in bulk.
- Offline browsing — cached photos and videos work without a network connection.
- Lazy thumbnail decryption — gallery populates instantly, thumbnails resolve as cells scroll into view.
- One-tap bulk free-up of already-backed-up device copies.
- Configurable app lock with timeout.
- Home-screen widget.
- 6 languages (en, hu, de, fr, es, it), light / dark / system theme, 6 colour palettes (Default, Forest, Sunset, Sea, Sepia, Mono).

## Install

Download the latest APK from the [releases page](https://github.com/gitakoos/proton-photos/releases/latest) and tap to install.

## Project structure

```
app/src/main/kotlin/eu/akoos/photos/
├── presentation/     UI — Composables + ViewModels
│   ├── auth/         Sign-in
│   ├── gallery/      Photos tab + multi-select
│   ├── albums/       Albums tab + detail + sharing
│   ├── viewer/       Full-screen photo / video viewer
│   ├── editor/       Photo + video editor
│   ├── search/       Search with filename + content filters
│   ├── hidden/       Hidden vault (PIN / biometric)
│   ├── settings/     Settings, About, Privacy, Language, Theme, Palette
│   ├── lock/         App lock
│   └── theme/        Colour tokens + palette factories
├── domain/           Pure domain layer
│   ├── entity/
│   ├── repository/   Interfaces
│   └── usecase/
├── data/             Implementations + IO
│   ├── api/          Retrofit Drive API + DTOs
│   ├── crypto/       DriveCryptoHelper (ProtonCore wrapper)
│   ├── db/           Room DAOs / entities / migrations
│   ├── repository/
│   │   └── drive/    Drive backend split per concern (Upload, Download,
│   │                 Stream, Album, AlbumSharing, CloudTrash, …)
│   ├── preferences/  DataStore
│   └── hidden/       Hidden-vault storage
├── worker/           WorkManager workers
├── widget/           Home-screen widget
└── App.kt + MainActivity.kt
```

## Tech stack

- Kotlin · Jetpack Compose · Material 3
- Hilt (DI) · Room (DB) · Coil (images) · OkHttp + Retrofit (network) · Media3/ExoPlayer (video)
- [ProtonCore Android](https://github.com/ProtonMail/protoncore_android) (GPL-3.0) + [GoOpenPGP](https://github.com/ProtonMail/gopenpgp) for auth, keys, crypto
- Drive API reference: [ProtonDriveApps/android-drive](https://github.com/ProtonDriveApps/android-drive)
- Min SDK 26 (Android 8) · Target SDK 35

## What's reused vs. what's built here

This project **uses the published `me.proton.core:*` libraries as-is** (no forks, no modifications). All Drive feature code is written from scratch against the publicly documented Drive API.

**ProtonCore modules consumed** (all `me.proton.core:*` v36.6.0, GPL-3.0, via Maven Central — see `gradle/libs.versions.toml` for the exact list):

- Auth & account — `auth`, `account`, `account-manager`, `user`, `human-verification`
- Crypto & keys — `crypto`, `crypto-android`, `key`
- Network — `network`, `network-data`
- UI plumbing — `presentation`, `presentation-compose`, `account-manager-presentation-compose`, `human-verification-presentation`, `payment-presentation`
- Features & misc — `plan`, `feature-flag`, `data-room`, `util-*`, country/push/challenge/notification helpers, `*-dagger` bind modules for Hilt
- Recovery — `account-recovery`, `user-recovery`

Telemetry / observability modules are pulled in (transitively required by some dagger modules) but **the app does not emit any telemetry events** — there is no analytics call site anywhere in the codebase.

**What this app implements on top** (`app/src/main/kotlin/eu/akoos/photos/data/repository/drive/`):

| File | Responsibility |
|---|---|
| `PhotoUploadService` | Per-file streaming upload pipeline — 4 MB blocks → encrypt + sign → CDN PUT in parallel (bounded). Manifest = sorted block-hash concat + detached signature. Block spill files in `cacheDir`. |
| `PhotoDownloadService` | Cloud → device. Stream + decrypt + apply `DATE_TAKEN` from `captureTime + zone offset`. Optional album subfolder. |
| `PhotoStreamService` | Incremental cloud-state sync (mutation journal). Owns `createOrGetPhotosVolume` lazy bootstrap. |
| `AlbumService` | Album CRUD: create / rename / set-cover, add/remove photos (batched). |
| `AlbumSharingService` | Public link mint, email invite (PKESK encrypt to invitee + sign), member list / revoke, shared-with-me (primary + v2 backup endpoints), accept/decline. The multi-step share popup (email chips, permission picker, optional message) is wired through this service; the underlying `inviteToAlbum` API currently returns "outdated app" — diagnostic work pending. |
| `PhotosShareService` | Per-user key cache (volumeId, shareId, rootLinkId, rootLinkKeyBytes, rootNodeHashKey) + shared API semaphore. Wiped on sign-out. |
| `PhotosVolumeBootstrap` | First-run Photos volume + share + root-link creation. Handles `ALREADY_EXISTS` race via `getVolumes()` fallback. |
| `CloudTrashService` | Cloud trash listing, restore (`moveTrashLinks`), permanent delete. |
| `RecentUploadsTracker` | Short-TTL (90 s) recently-uploaded-linkIds map — stops stale rows from blocking cloud-delete propagation. |
| `LinkDetailHelpers`, `ThumbnailHelpers`, `PhotoEntityBuilder` | Shared helpers (batch link metadata, JPEG thumbnail ≤512 px, DTO → Room entity mapping). |

Plus the layers around them:

- `data/api/` — Retrofit interface + DTOs for `/drive/photos/*`, `/drive/v2/*`, block uploads, sharing endpoints.
- `data/crypto/DriveCryptoHelper.kt` — thin wrapper over `me.proton.core:crypto*` (no custom crypto primitives; PGP delegated entirely to upstream).
- `data/db/` — Room schema for the local mutation journal (`PhotoListingEntity`, `SyncStateEntity`) with exported schemas + migrations.
- `domain/usecase/` — `UploadPendingUseCase`, `ReconcileSyncStateUseCase`, `DownloadPhotosUseCase`, `FreeUpSpaceUseCase`, `DeletePhotoUseCase`, `GetGalleryItemsUseCase`, `CategorizeItem`.
- `worker/` — `SyncWorker`, `AlbumDownloadWorker`, `FreeUpSpaceWorker` (WorkManager + foreground service notifications).
- `presentation/` — Jetpack Compose UI (gallery, viewer, editor, albums, settings, …) and ViewModels.

No private endpoints, no undocumented APIs, no obfuscation (`-dontobfuscate` in `proguard-rules.pro` so the released APK can be byte-compared to a fresh build from this source).

## Disclaimer

Not affiliated with Proton AG. Proton, Proton Drive, and Proton Photos are trademarks of Proton AG. Built against the publicly documented Drive API with no proprietary code from Proton.

## Author

[**Akoos**](https://akoos.eu) — creator and maintainer.

## License

[GPL-3.0](LICENSE) — matches the upstream `me.proton.core:*` libraries this app links against.
