# Media Gallery for Android

An Android gallery for browsing photos and videos without downloading an entire
chat library first. It supports on-demand playback, encrypted offline downloads,
local file imports, search, and organization across sources.

Start with **Local files** if you only want to use media on your phone. Add the
Telegram connector when you want to browse selected chats. The app interface is
currently in Spanish; this documentation uses English and includes the labels
you will see on screen.

## Get started

Gallery **2.18** and Telegram connector **1.1** require **Android 12 or later
(API 31)**. The release APKs target **ARM64 (`arm64-v8a`)** phones and tablets.

1. Open [Releases](https://github.com/Jhoan-JS/media-gallery-android/releases).
   Use the two APKs attached to the same release and check them against its
   `SHA256SUMS.txt` file.
2. Install `MediaGallery-2.18-arm64-v8a.apk`. Install
   `TelegramConnector-1.1-arm64-v8a.apk` as well if you need Telegram.
   Keep both apps in the same Android user or profile.
3. Open the gallery and set a PIN.
4. Choose **Archivos propios** (Local files), then **Importar** (Import), to
   copy photos and videos you select into encrypted app storage.
5. For Telegram, follow the [connector setup guide](telegram/README.md).

**Existing lab installations:** the public APKs use a new production signing
certificate. Android cannot install them as updates over older lab-signed apps.
Keep your existing installation and export an encrypted backup before planning
a migration. Replacing an installation removes its app data; a backup and its
passphrase must be available first. The public release has not been tested as
a migration from the older lab installation.

If no APKs are attached to a release, build from source using the
[build guide](docs/build.md). Debug builds are for local testing and use a
different certificate from the public release.

The current gallery allows Android user IDs 0, 14, and 15. User 0 is the usual
primary user; other secondary user IDs are not supported by this version.

## What works without an account

Local file imports, local playback, favorites, Watch later, albums, tags,
bookmarks, recent history, and encrypted backups work without signing in to
Tato or Telegram. Imported originals stay in their original location; the
gallery keeps its own encrypted copy.

Telegram browsing requires a Telegram account, your own API ID and API hash,
and permission for the chats you select. The connector keeps a separate
session from the official Telegram app.

Tato requires a separately authorized compatible connector. This repository
contains the gallery's consumer and the [local interface documentation](connectors/tato/README.md),
but does not distribute a Tato client, connector APK, patch, or repackager.

## Browsing and organization

- Browse a media grid, filter by date and type, and move quickly through dates.
- Stream videos on demand, seek, resume playback, and move between files in the viewer.
- Keep selected files offline and manage storage and download progress.
- Organize media with favorites, Watch later, albums, tags, bookmarks, and archives.
- Group chats into folders and pin chats.
- Keep organization, chosen covers, chat folders, and pinned chats in encrypted backups.

Optional visual search runs on the phone using pinned public models. It needs
those models at build time and analyzes media already available to the app.
Visual analysis is disabled for the Telegram source in this version.

## Build and contribute

The build scripts use Windows PowerShell, JDK 21, Gradle 9.1.0, Android SDK 36,
and Build Tools 36.0.0. The Telegram engine also needs Go 1.27.4 and Android NDK
28.2.13676358. The app's minimum Android version remains API 31.

~~~powershell
./build.ps1 -Gradle gradle
~~~

See the [build guide](docs/build.md) for tool setup, optional models, ABI
selection, output paths, and signing. See [Contributing](CONTRIBUTING.md) for
checks to run before submitting a change.

## Privacy and limits

The gallery has no `INTERNET` permission. The Telegram connector connects to
Telegram to authenticate and fetch selected content. Catalogs, cached media,
and connector sessions stay in private app storage, using SQLCipher, AES-GCM,
and Android Keystore. The apps require matching signatures and explicit
authorization for their local connection.

The PIN and optional biometrics protect the interface. The storage key remains
available to resume background tasks after the process restarts; it is not
derived from the PIN. Root access, a compromised OS, or an authorized app signed
with the same key can weaken these boundaries.

[Privacy details](docs/privacy.md) · [Validation and known limits](VALIDATION.md) ·
[Version 2.18 notes](docs/releases/2.18.md) · [Repository scope](docs/publication-scope.md)

## License

Project-owned code is [MIT licensed](LICENSE), copyright Jhoan Marte.
Dependencies, model assets, and their notices keep their own licenses.
See [License scope](LICENSE_STATUS.md) and [Third-party notices](THIRD_PARTY_NOTICES.md).

Tato and Telegram are names used to identify integrations. This project is not
affiliated with either service.
