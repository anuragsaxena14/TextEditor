# Simple Editor (Android) v2: with encrypted files

A plain-text editor that can also keep files encrypted. No internet permission,
no third-party libraries, no accounts.

## Using encryption
- **Encrypt an existing file:** Open it → ⋮ → *Encrypt this file…* → set a password →
  choose where to save the `.sev` file → the app then offers to delete the unencrypted original.
- **New encrypted file:** ⋮ → *New encrypted file…*
- **Open an encrypted file:** Open… (or "Open with" from a file manager) → enter password.
- **Lock:** tap the lock icon, leave the app, turn the screen off, or wait 3 minutes.
  Unsaved changes are saved (encrypted) before locking.
- **Change password:** ⋮ → *Change password…* (asks for the current one first).

## How it protects your data
- AES-256-GCM encryption; key derived from your password with PBKDF2-HMAC-SHA256
  (600,000 iterations, random salt). File header is authenticated, so tampering is detected.
- Decrypted text exists only in the app's memory. It is never written to disk,
  never put in Android's saved state, and the key is wiped from memory on lock.
- Every save is verified: the app checks the encrypted data decrypts correctly
  before writing, then reads the file back to confirm it was written intact.
- Screenshots, screen recording and the recent-apps preview are blocked.
- Keyboards are asked not to learn what you type (incognito flag).
- Copied text is cleared from the clipboard after 30 seconds and marked
  sensitive (Android 13+). Share/Translate/Search are removed from the
  selection menu in encrypted files.
- No INTERNET permission, Android backup disabled, release (non-debuggable) build.

## What it cannot protect against (be honest with yourself)
- **Forgotten password = lost data.** There is no recovery. Keep a copy of the
  `.sev` file on a pen drive or computer; it stays encrypted there.
- **A weak password.** Anyone with the file can try guesses offline.
  Use a long passphrase (4–5 random words or more).
- **A compromised phone:** malware, a rooted device, or an app with
  Accessibility permission can read the screen while a file is open.
- **Keyboard clipboard history** (Gboard/Samsung) may keep a copy of copied text
  despite the 30-second clear. Turn that feature off if you copy passwords.
- **Deleting the original** removes the file, but flash storage doesn't guarantee
  the old bytes are erased immediately. Android's own storage encryption makes this
  low-risk on a locked, up-to-date phone.

## Building (GitHub)
Upload as before; the workflow now builds `app-release.apk`
(artifact **SimpleEditor-apk**).
**Uninstall the previous version before installing a new build.** Each build is signed
with a different key, so Android refuses to update over it. Your files are not
affected, since they live outside the app.
Requires Android 8.0 or newer.
