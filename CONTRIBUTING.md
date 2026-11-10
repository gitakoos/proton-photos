# Contributing to Photos for Proton

Photos for Proton is an independent, unofficial Android client for Proton Drive Photos. It is owned and maintained by akoos.eu and released under the GNU General Public License v3.0 (GPL-3.0).

- Website: https://www.photosforproton.eu
- Author: https://akoos.eu
- Source: https://github.com/gitakoos/proton-photos

By contributing you agree that your contributions are licensed under GPL-3.0.

## Branches

| Branch | What it holds |
|---|---|
| `beta` | The development branch. All work lands here first, commit by commit. **Open pull requests against `beta`.** |
| `main` | Stable releases only, one commit per release, tagged `vX.Y.Z`. Nothing is merged into it directly. |
| `preview` | The source of each test build, one commit per build, tagged `vX.Y.Z-testN`. |

`beta` is never rewritten: no force pushes, no rebases of published commits. A release puts the exact `beta` tree onto `main`, so everything merged into `beta` reaches the next release.

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

Every user-facing string lives in `app/src/main/res/values/strings.xml` and must be translated in all eleven locale folders, or the release build fails:

`values-cs`, `values-de`, `values-es`, `values-fr`, `values-hu`, `values-it`, `values-nl`, `values-pl`, `values-sk`, `values-sl`, `values-tr`

- Add the string to the base `values/strings.xml` first, then to all eleven locales.
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

## Commit messages

Every commit on `beta` is public and stays in the history for good.

- English, imperative mood, describing the change: "Fix crash when opening a shared album", not "fixed stuff" or "WIP".
- A short subject line (about 72 characters at most), then optionally a blank line and a body explaining what changed and why.
- No personal data (other people's names, email addresses, local file paths, device serials) and no tokens, keys or passwords.
- No `fixup!`, `squash!` or `wip` commits; clean them up before opening the pull request.

## Pull requests

1. Open the pull request against `beta`, with a short description of what changed, why, and how you tested it (device, Android version, guest or signed in). Link the issue it fixes.
2. Run the three Gradle checks above; CI runs them again on the pull request.
3. Changes to build scripts, the Gradle wrapper, `gradle/verification-metadata.xml`, GitHub workflows, the manifest (permissions, exported components) or ProGuard rules are reviewed line by line before anything is built. Explain each such change in the description. New dependencies need a reason and a release at least two weeks old.
4. Only the maintainer can merge. Each pull request is fetched, checked against the rules on this page, reviewed against the current `beta`, built, tested, and tried on a real device when it changes something visible.
5. The outcome is one of: merged as it is; merged with a follow-up commit from the maintainer; changes requested; part of it taken; or declined with a reason.
6. When it is merged, your commits keep your name. If the history needs cleaning up (work-in-progress commits, or a message containing something that must not be public), the pull request is squashed into one commit, still authored by you. If only part of it is taken, that part is committed under your name. Every contribution is credited in the notes of the build it first ships in.

Do not post links to builds or downloads in pull requests or issues; only the official releases on this repository are trusted.
