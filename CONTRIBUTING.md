# Contributing to Photos for Proton

Photos for Proton is an independent, unofficial Android client for Proton Drive Photos. It is owned and maintained by akoos.eu and released under the GNU General Public License v3.0 (GPL-3.0).

- Website: https://www.photosforproton.eu
- Author: https://akoos.eu
- Source: https://github.com/gitakoos/proton-photos

By contributing you agree that your contributions are licensed under GPL-3.0.

## Tech stack

- Kotlin, single Gradle module (`app/`)
- Jetpack Compose for UI
- AndroidX Media3 (ExoPlayer) for video
- Namespace and applicationId: `eu.akoos.photos`
- Minimum supported Android: 8.0 (API 26)

## Getting started

1. Clone the repository.
2. Open it in a recent stable Android Studio.
3. Let Gradle sync. The JDK bundled with Android Studio (JBR) is enough; no separate JDK is required.
4. Build a debug APK:
   ```
   ./gradlew :app:assembleDebug
   ```
5. Run on an emulator or a physical device on Android 8.0 (API 26) or newer.

## Before you open a pull request

Run these and make sure they pass:

```
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

- Keep the change focused on one feature or fix.
- `lintDebug` fails a release build on a missing translation, so it must be clean (see Localization).

## Localization

Every user-facing string lives in `app/src/main/res/values/strings.xml` and must be translated in all ten locale folders, or the release build fails:

`values-cs`, `values-de`, `values-es`, `values-fr`, `values-hu`, `values-it`, `values-nl`, `values-pl`, `values-sk`, `values-sl`

- Add the string to the base `values/strings.xml` first, then to all ten locales.
- Never hardcode a user-facing string in Kotlin; always reference `R.string.*`.
- Escape an apostrophe inside a value as `\'`.

## Local-only (guest) mode

The app is fully usable with no Proton account: a guest works on device data only, and a later sign-in upgrades the screens in place.

Rule: every new user-facing feature must be available to a guest by default. A feature may be account-only only when it genuinely needs the cloud or the account key (upload and backup, cloud albums, public links, sharing with people, cloud trash, storage quota, cross-device sync, and anything encrypted to the account key).

For an on-device feature:

- Read the session with `getPrimaryUserId()`; do not add a second no-account flag.
- Branch a null user to the local path instead of fabricating a user id.
- Key on-device data by `userId?.id ?: "local"` so switching from guest to signed-in just re-reads.
- Hide cloud-only controls for a guest rather than showing an inert button.

## Coding style

- Match the style of the code around your change.
- User-facing text is English in the base strings; the locale files carry the rest.
- In user-facing strings, avoid first-person plural.
- Keep comments minimal and about the code. Do not leave TODO or FIXME in committed code.

## License header

Every new source file starts with the GPL-3.0 header:

```
/*
 * Photos for Proton
 * Copyright (C) 2026 Akoos <https://akoos.eu>
 *
 * Source:  https://github.com/gitakoos/proton-photos
 * Website: https://www.photosforproton.eu
 *
 * This file is part of Photos for Proton.
 *
 * Photos for Proton is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
```

## Dependencies

The repository pins a SHA-256 for every dependency in `gradle/verification-metadata.xml`, and the build verifies it. If you add or change a dependency (a version in `gradle/libs.versions.toml`, a new library, or an AGP, Kotlin, Gradle, or plugin version), regenerate and commit that file with the change:

```
./gradlew --write-verification-metadata sha256 help assembleDebug assembleRelease testDebugUnitTest lintDebug
```

Review the diff so only the changed dependency's hashes move. A verification failure you did not cause by a deliberate dependency change means an artifact's bytes differ from what was pinned; investigate it instead of regenerating to force it through.

## Commits and pull requests

- Write commit messages in the imperative and describe the user-visible change, for example "Fix crash when opening a shared album".
- Open a pull request with a short description of what changed and how you tested it.
