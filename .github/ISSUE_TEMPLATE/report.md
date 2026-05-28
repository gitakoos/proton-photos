---
name: Report — bug, crash, or feature request
about: One form for everything. Pick the type below and fill in what applies.
title: ""
labels: ["triage"]
assignees: []
---

> Proton Photos is built by one person in spare time. The more concrete info you provide here, the faster I can act. Anything in *italics* is a hint — feel free to delete it.

## What kind of report is this?

- [ ] **Crash** — the app force-closed, froze, or shows a black/white screen
- [ ] **Bug** — something doesn't work the way it should (wrong UI, sync stuck, missing photos, broken button…)
- [ ] **Feature request** — an idea or improvement

## Device

| | |
|---|---|
| Model | *e.g. Pixel 9 Pro, Samsung Galaxy S24, Fairphone 5* |
| Android version | *e.g. Android 15* |
| Build number | *Settings → About phone → Build number — e.g. `AP31.240617.009`* |
| OS flavor | Stock / GrapheneOS / LineageOS / CalyxOS / other (please specify) |
| App version | *find inside the app under Settings → About — e.g. `1.3.0-beta`* |

## What happened?

*One or two sentences describing what you observed.*

## Steps to reproduce (if a bug or crash)

1. 
2. 
3. 

## What did you expect to happen?

*If it's a feature request, write what you'd like to see and why.*

## Logcat (for crashes — strongly recommended)

Without a logcat we usually have to guess. Capturing one needs a PC with `adb`. **No third-party app required.**

1. Enable USB debugging: Settings → About phone → tap Build number 7 times → back → System → Developer options → toggle **USB debugging**.
2. Install Android platform-tools on your computer:
   - **Windows**: https://developer.android.com/tools/releases/platform-tools (download the ZIP, unzip)
   - **macOS**: `brew install android-platform-tools`
   - **Linux**: `sudo apt install adb` (or your distro's equivalent)
3. Plug the phone into the computer, accept the "Allow USB debugging?" prompt on the phone.
4. Open a terminal in the unzipped folder (Windows: PowerShell or cmd; macOS/Linux: any terminal).
5. Clear the existing log buffer:
   ```
   adb logcat -c
   ```
6. Reproduce the crash on the phone (open the app, do the steps that crash it).
7. Save the log immediately after the crash:
   ```
   adb logcat -d > crash.txt
   ```
8. Open `crash.txt`, search for `FATAL EXCEPTION` or `AndroidRuntime`. Paste the surrounding ~50 lines below — or just drag-and-drop the whole `crash.txt` file into this issue (GitHub accepts file uploads).

**Privacy note:** logcat may contain your email address, file names, or short-lived tokens. Open the file in a text editor and search-replace your email with `REDACTED` before pasting. If unsure, paste only the `FATAL EXCEPTION` block.

```
paste logcat here
```

## Screenshots or screen recording (very helpful for UI bugs)

*Drag-and-drop into the box. If a screenshot shows your email or filenames you'd rather keep private, blur/crop first.*

## Anything else?

*Related issues, things you tried that worked around it, mockups for feature requests — all welcome.*
