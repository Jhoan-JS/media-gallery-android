# Media Gallery for Android

An Android gallery for browsing photos and videos without downloading an entire
chat library first. It supports on-demand playback, encrypted offline downloads,
local file imports, search, and organization across sources.

Start with **Local files** if you only want to use media on your phone. Add
**Conector de fuentes** to browse Tato, Telegram, and web sources. The app interface is
currently in Spanish; this documentation uses English and includes the labels
you will see on screen.

## Get started

Gallery **2.31** pairs with **Conector de fuentes 3.2**. The pair targets
**ARM64 (`arm64-v8a`)** phones and tablets running **Android 12L or later**.

1. Open the [2.31 release](https://github.com/Jhoan-JS/media-gallery-android/releases/tag/gallery-v2.31-sources-v3.2)
   and check the files against `SHA256SUMS.txt`.
2. To use online sources, install `ConectorFuentes-3.2-arm64-v8a.apk` first.
   Install `MediaGallery-2.31-arm64-v8a.apk` in the same Android user or profile.
3. Open the gallery and set a PIN.
4. Choose **Archivos propios** (Local files), then **Importar** (Import), to
   copy photos and videos you select into encrypted app storage.
5. To connect sources, open **Fuentes**, choose the source, and use
   **Configurar conexión**. For Telegram or Web, set up the same account again
   if you are moving from the standalone connector.

**Existing lab installations:** the public APKs use a new production signing
certificate. Android cannot install them as updates over older lab-signed apps.
Keep your existing installation and export an encrypted backup before planning
a migration. Replacing an installation removes its app data; a backup and its
passphrase must be available first. The public release has not been tested as
a migration from the older lab installation.

If no APKs are attached to a release, build from source using the
[build guide](docs/build.md). Debug builds are for local testing and use a
different certificate from the public release.

Install both APKs in the active main or secondary Android user. Each user has
its own library and source sessions. For Tato, sign in to your account in
**Conector de fuentes**, then choose **Fuentes**, **Tato**, **Configurar conexión**
and **Autorizar conexión** in the gallery. Review and confirm the account.
Install both updates over the previous production pair to keep your data.

## Optional web sources

Gallery 2.28 supports Gelbooru and Rule34.xxx alongside Telegram in the
optional **Conector de fuentes** 2.0. It updates the Telegram connector package.
Configure sources from **Fuentes** in Gallery. Each site stays disabled until
configured on the device. The existing Web Connector 1.6 remains usable during
the transition.

The gallery returns to the last library after unlock. **Biblioteca**, **Guardado**,
**Descargas**, and **Fuentes** stay in the same places across sources. Filters sit
next to search, and thumbnail size and framing are in **Ajustes**. Use
**Búsquedas guardadas** or the star in a web library to reuse named searches.
The [connector guide](webconnector/README.md) covers setup, privacy and limits.

The optional isolated build of Gallery 2.31 brings Telegram and web setup into
**Fuentes**. It runs source networking through a compatible companion and keeps
its credentials in encrypted Gallery storage. See the [build guide](docs/build.md#isolated-sources)
for the open source components that support this configuration.

## What works without an account

Local file imports, local playback, favorites, Watch later, albums, tags,
bookmarks, recent history, and encrypted backups work without signing in to
Tato or Telegram. Imported originals stay in their original location; the
gallery keeps its own encrypted copy.

Telegram browsing requires a Telegram account, your own API ID and API hash,
and permission for the chats you select. The connector keeps a separate
session from the official Telegram app.

Tato requires a compatible connector and account authorization. The 3.2 release
asset bundles the Tato runtime with the project's source services. Git contains
the gallery's consumer and the [local interface documentation](connectors/tato/README.md).

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

The gallery has no `INTERNET` permission. Source companions connect to Telegram
and enabled websites to fetch selected content. Catalogs, cached media,
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
Dependencies, model assets, and the Tato runtime keep their own licenses and notices.
See [License scope](LICENSE_STATUS.md) and [Third-party notices](THIRD_PARTY_NOTICES.md).

Tato and Telegram are names used to identify integrations. This project is not
affiliated with either service.
