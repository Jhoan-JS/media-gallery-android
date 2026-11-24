# Telegram connector 1.1

The connector keeps its own Telegram user session and shares only the chats
you explicitly select and authorize. It is an Android app, not a bot, and does
not need the official Telegram app to stay open.

## Install and connect

Requirements: Android 12 or later (API 31), an ARM64 device for the public
APKs, gallery 2.18, and connector 1.1 from the same release. Both apps must use
the same certificate and run in the same Android user or profile.

1. Install both APKs from [Releases](https://github.com/Jhoan-JS/media-gallery-android/releases).
   See the [gallery setup](../README.md#get-started) and signing compatibility note.
2. Open the gallery and create its PIN. Open the Telegram connector from the
   gallery's settings.
3. Enter your own Telegram **API ID** and **API hash** in the connector.
   Obtain application credentials through [my.telegram.org](https://my.telegram.org).
   Do not put them in source code, a GitHub issue, or a log.
4. Enter your phone number, the code Telegram sends, and your two-step
   verification password if requested. Enter these only on the phone.
5. Open **Elegir chats** (Choose chats). Use **Cargar más chats** (Load more
   chats) to include further pages and archived chats. Select the channels,
   groups, private chats, or Saved Messages you want, then tap
   **Guardar selección** (Save selection).
6. In the gallery, select Telegram and open **Ajustes → Autorizar conexión**
   (Settings → Authorize connection). Confirm the selected chats in the connector.
7. Return to the gallery and tap **Actualizar** (Refresh).

The official Telegram app's existing sign-in does not sign in this connector.
Saving a different selection requires a new authorization. Closing an app does
not deliberately revoke the saved session.

## Behavior and limits

The connector fetches selected history, metadata, thumbnails, and media ranges.
Offline downloads are requested by the gallery. Intentionally opening media
can report reads and views to Telegram. Secret, ephemeral, and protected content
is excluded. Visual analysis is disabled for this source.

Revoking gallery access in the connector stops local sharing while retaining
the connector's Telegram session. This is separate from Telegram's own session
management. Network availability and Telegram's service limits still apply.

Without an account, the connector cannot list or fetch chats. The gallery's
[local-file features](../README.md#what-works-without-an-account) remain available.

## Build

Run the [root build](../docs/build.md) to compile the Go engine and both Android
apps. The connector package is `local.ttlab.telegram`, version name `1.1`,
version code `2`. Its provider authority is `local.ttlab.telegram.bridge`.
It requires the signature permission `local.ttlab.permission.TELEGRAM` and
checks the calling gallery package and explicit account grant.

Go dependency versions and checksums are in `engine/go.mod` and `engine/go.sum`.
Their license texts are retained in [THIRD_PARTY_NOTICES.txt](engine/THIRD_PARTY_NOTICES.txt).
