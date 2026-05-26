# Proton Photos for Android

> **Unofficial** open-source Proton Drive Photos client for Android. Built against the publicly documented Drive API.

[![Release](https://img.shields.io/badge/release-beta-blue)](https://github.com/gitakoos/proton-photos/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-green)](LICENSE)
[![Min SDK](https://img.shields.io/badge/minSdk-26-orange)](https://developer.android.com/about/dashboards)

End-to-end encrypted photo backup and browsing for your Proton Drive Photos library.

**Website:** [photos.akoos.eu](https://photos.akoos.eu)

## Features

- End-to-end encryption via ProtonCore + GoOpenPGP.
- Background sync — per-folder selection, Wi-Fi-only toggle, configurable interval.
- Albums — create, rename, add / remove photos.
- Hidden vault behind biometric / PIN.
- Per-field metadata stripping (GPS, camera, timestamps, software) on upload.
- Built-in editor — adjust, filter, redact, rotate, crop.
- One-tap bulk free-up of already-backed-up device copies.
- Home-screen widget.
- 6 languages (en, hu, de, fr, es, it), light / dark / system theme.

## Install

Download the latest APK from the [releases page](https://github.com/gitakoos/proton-photos/releases/latest) and tap to install.

## Project structure

```
app/src/main/kotlin/me/proton/photos/
├── presentation/     UI — Composables + ViewModels
│   ├── auth/         Sign-in
│   ├── gallery/      Photos tab + multi-select
│   ├── albums/       Albums tab + detail + sharing
│   ├── viewer/       Full-screen photo / video viewer
│   ├── editor/       Image editor
│   ├── settings/     Settings, About, Privacy, Language, Theme
│   ├── lock/         App lock
│   └── theme/        Colour tokens
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

**What this app implements on top** (`app/src/main/kotlin/me/proton/photos/data/repository/drive/`):

| File | Responsibility |
|---|---|
| `PhotoUploadService` | Per-file streaming upload pipeline — 4 MB blocks → encrypt + sign → CDN PUT in parallel (bounded). Manifest = sorted block-hash concat + detached signature. Block spill files in `cacheDir`. |
| `PhotoDownloadService` | Cloud → device. Stream + decrypt + apply `DATE_TAKEN` from `captureTime + zone offset`. Optional album subfolder. |
| `PhotoStreamService` | Incremental cloud-state sync (mutation journal). Owns `createOrGetPhotosVolume` lazy bootstrap. |
| `AlbumService` | Album CRUD: create / rename / set-cover, add/remove photos (batched). |
| `AlbumSharingService` | Public link mint, email invite (PKESK encrypt to invitee + sign), member list / revoke, shared-with-me (primary + v2 backup endpoints), accept/decline. **Not enabled in v1.0.0-beta — known flakiness on accept-invite + shared-with-me decryption.** |
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
