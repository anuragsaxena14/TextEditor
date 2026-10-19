# Simple Editor (Android)

A small plain-text editor: open, edit, save, and search any text file.

## Features
- Open any file via the system file picker, or from a file manager's "Open with…"
- Save / Save as (works with local storage, SD card, Google Drive, etc.)
- Find bar: live highlighting of all matches, next/previous, match counter, case-sensitive toggle (Aa)
- Unsaved-changes prompt, dark mode, monospace font
- Ctrl+F / Ctrl+S / Ctrl+O with a hardware keyboard
- No third-party libraries; files up to 2 MB, saved as UTF-8

## Option A: build on GitHub (no installs needed)
1. Create a new GitHub repository and upload the contents of this folder
   (including the hidden `.github` folder).
2. Open the repo's **Actions** tab. The "Build APK" workflow runs automatically
   on every push (or click "Run workflow").
3. When it finishes (~3–5 min), open the run and download the
   **SimpleEditor-apk** artifact. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone, open it, and allow "Install unknown apps"
   when prompted.

## Option B: Android Studio
1. File → Open → select this folder. Let it sync (accept the Gradle wrapper prompt if shown).
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
