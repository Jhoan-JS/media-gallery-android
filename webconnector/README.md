# Optional Web Connector

Rule34.xxx and Gelbooru are optional sources in Gallery. Install Gallery and
Sources Connector 2.0 (**Conector de fuentes**) in the same Android user with
the same signing certificate. Android 12 or later is required. The standalone
Web Connector 1.6 can coexist with Sources Connector 2.0.

Gallery 2.30's [isolated worker configuration](../docs/build.md#isolated-sources)
uses the same site support. Setup is inside Gallery under **Fuentes**, and the
encrypted API credentials are stored by Gallery.

If an existing standalone connector already has access, Gallery can use it
while the corresponding source is unconfigured in Sources Connector. To move
the source, enter its credentials in Gallery's connection flow using the same
site account ID. The connectors keep separate Android Keystore entries and do
not transfer credentials. Gallery selects the new connection at the next
library entry or after setup and keeps the chosen connector while the viewer
is open. Revoking a source in Sources Connector stops its use in Gallery. To
remove the old API key as well, use the old Web Connector's revoke action.

1. Unlock the gallery and choose **Gelbooru** or **Rule34.xxx** in the source picker.
2. Open the source connection settings. Enter the selected site's account ID and API key on the
   connector screen, then tap **Habilitar y autorizar**. Gelbooru lists these
   values under **API Access Credentials** in its account options.
   The gallery cannot read the key.
3. Gelbooru loads recent posts on entry. Set **Etiquetas de inicio** under
   settings to load recent posts that match all your chosen tags. Typing in
   the search field searches after a short pause. Images and videos appear
   together, and the next page loads as you scroll. Open **Filtros** next to
   search to narrow the feed by type, orientation or watched status. Choose
   **Tamaño de miniaturas** under settings for a larger or denser grid.
   **Encuadre de miniaturas** shows the complete preview inside the tile.
   Tap the star to save or reopen named searches for the selected site.
4. Tap a tile to open the viewer. The gallery restores your place when you return.
   Swipe forward at the end of the loaded feed to continue into the next page.
   Searches can browse up to 40 pages of 30 posts each, subject to site limits.
   **Etiquetas del sitio** in the viewer adds a tag to the current search,
   excludes it, or starts a search for that tag alone.

Each web source is off by default. Gelbooru includes every rating returned
by its API. Gelbooru controls what the API returns for the account. Rule34.xxx
shows posts rated **Safe**. Searches go to the selected site and media requests
go to its accepted CDN hosts. Each
site has its own settings and account. Gelbooru handles its responses with a
temporary in-memory library and media buffers, including when the site sends
`Cache-Control: no-store`. Its recent posts, search results and viewer marks
are cleared when that session ends. Startup tags, saved searches and credentials remain
encrypted. Saving a search stores its name and terms locally for that site. Rule34.xxx uses its existing encrypted library and cache.
**Revocar y apagar** removes the selected site's API key and stops its requests.
Web libraries have no export or permanent downloads.

Gelbooru reads the XML or JSON catalogue and dereferences individual post IDs
through the JSON API. Media requests use links supplied by Gelbooru and
send the origin of that API as the referrer. Credentials and search terms
stay with the API request. The viewer loads images and compatible videos
inside the gallery. Larger previews are reduced in memory for the grid.
The viewer reports an error if the CDN declines a request.

Playback uses the existing Media3 player. Gelbooru uses conditional byte
ranges with a strong ETag or a last-modified timestamp that can identify the
representation. If the server supplies neither or ignores byte ranges, the
connector can load a video up to 24 MiB from a single response into memory.
Larger videos in that case show an error. Rule34.xxx still requires a strong
ETag and byte ranges for video.

[Rule34 API documentation](https://api.rule34.xxx/) ·
[Rule34 terms](https://rule34.xxx/index.php?page=tos)

[Gelbooru API documentation](https://gelbooru.com/index.php?id=18780&page=wiki&s=view) ·
[Gelbooru terms](https://gelbooru.com/tos.php)

## Testing

Run :app:testDebugUnitTest, :telegram:testDebugUnitTest and
:webconnector:testDebugUnitTest. Build the common debug connector with
`-PwebFixture` to exercise synthetic responses without real network requests.
Generate a PNG and a seekable MP4 named **fixture.png** and **fixture.mp4** in
**.qa/web-fixture**, or pass the directory using `-PwebFixtureAssets`. The debug
build takes only those two assets. Use synthetic credentials in an isolated emulator.
Use **fixture_gallery** for Rule34.xxx or **fixture_gelbooru** with the
key **gel_fixture_key** for Gelbooru. The fixture assets and transport are excluded from
release APKs.

The app-side integration and release-package checks do not establish that the
real API response schema, quotas and CDN validators match the fixture. Confirm
those with an approved non-explicit image and MP4 before distributing support
for the live site.
