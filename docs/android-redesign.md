# Android gallery redesign

## What the redesign should accomplish

Unlock into the last library, with its active view restored whenever the source still has that state. The source name, search, filters, and route back from the viewer should be recognizable across Tato, Telegram, web sources, and imported files. Existing playback, gestures, collections, downloads, and privacy controls remain part of the experience.

## Daily flows

| Flow | Intended behavior |
| --- | --- |
| Open the app | Unlock directly into the last library. Restore the chat, filters, search, and position where the source's retention rules allow it. If the library needs attention, explain the issue there and offer the nearest recovery action. |
| Change source | Show the available sources with a quiet connection status. Restore each source's own view. Keep an obvious way to return to the preceding library. |
| Explore and search | Keep search and filters in the same place. Use names or captions for indexed libraries and tags for web sources. Let users reach chat folders, pinned chats, and saved web searches in the context where they apply. |
| Open media | Keep the full-screen viewer, swipe navigation, playback position, and gesture controls. Return to the same row in the grid. Place secondary actions in one viewer menu. |
| Organize | Bring favorites, Watch later, history, albums, tags, and archives together under Saved, scoped to the active source and the features it supports. |
| Track downloads | Show active tasks and errors, then offline media and storage controls. Keep the selected source visible on tasks and saved files. |
| Manage a connection | Open Sources inside Gallery. For each source show the current state, granted access, and the next action, such as connect, choose chats, authorize, or reconnect. Resume the originating library after the connector returns. |
| Manage privacy and appearance | Put PIN, biometrics, encrypted backup, previews, and grid appearance in one settings structure. Keep advanced controls out of everyday navigation. |

Gelbooru keeps its session-only catalog and media. The app remembers the last source, but an ended Gelbooru session opens a fresh feed. The user's deliberately saved searches and startup tags are separate encrypted preferences.

## Navigation options

- **Full-screen gallery.** Small search and source controls at the top. One labeled action at the bottom opens Saved, Downloads, Sources, and Settings on demand. This makes room for thumbnails and adds a step to those destinations.
- **Sources within reach.** The bottom area switches sources directly. The active source's top bar holds search and filters, and a menu gives access to common destinations. This favors frequent switching and gives prominence to source controls.
- **Library with tabs.** Use the same labeled destinations throughout Gallery: Library, Saved, Downloads, and Sources. The current source is selected in the library header. This gives every task a consistent address and uses a strip of the screen for navigation.

## Common Telegram and Web connector

- Gallery keeps its current package ID, signing identity, and absence of the Android Internet permission. Its local data stays in place during the update.
- The proposed companion updates the Telegram package in place, with the same signer and a higher version code, to retain its encrypted session and authorized chats. Verify this using a lab update before treating it as guaranteed.
- Add the Web provider under a distinct authority and a separate signature permission so the update can coexist with the older Web connector. Prefer the common companion when it is available and authorized. Keep the old Web path available during the transition.
- Web credentials are entered in the new companion if required. Do not copy Keystore keys or extract existing sessions. Verify Gelbooru and Rule34 separately, including revocation and isolation between sites. Remove the old connector only after the user has verified the new one and authorized its removal.
- Tato keeps its separately authorized connector. Gallery presents its status and its setup route alongside the other sources.

## Acceptance before release

- Update over the current signed APKs in a lab and verify the PIN, biometric setup where available, encrypted library, downloads, favorites, and profiles.
- Exercise first setup and reconnection from Sources, including expired authorization, a missing companion, one enabled source, and several sources in use.
- Switch sources during a library session, restore the grid position on return from a viewer, and verify that search, media, and preferences remain scoped to the intended source.
- Exercise paged results, galleries with no results, offline failures, resumable downloads, large-video playback and seek, and the existing animated and static media paths.
- Check small and large layouts, font scaling, Android Back, screen-reader labels, and the merged release manifest. Confirm that Gallery still has no Internet permission.
