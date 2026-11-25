# Optional Web Connector

Gallery 2.26 and Web Connector 1.6 support Rule34.xxx and Gelbooru. Install both
APK files in the same Android user with the same signing certificate. Android 12
or later is required. Telegram Connector 1.1 and the local Tato interface use
their existing protocols.

1. Unlock the gallery and choose **Gelbooru** or **Rule34.xxx** in the source picker.
2. Tap **Conectar**. Enter the selected site's account ID and API key on the
   connector screen, then tap **Habilitar y autorizar**. Gelbooru lists these
   values under **API Access Credentials** in its account options.
   The gallery cannot read the key.
3. Gelbooru loads recent posts on entry. Set **Etiquetas de inicio** under
   settings to load recent posts that match all your chosen tags. Typing in
   the search field searches after a short pause. Images and videos appear
   together, and the next page loads as you scroll. Open **Filtros** next to
   search to narrow the feed by type, orientation or watched status. Choose
   **Tamaño de miniaturas** under settings for a larger or denser grid.
4. Tap a tile to open the viewer. The gallery restores your place when you return.
   Searches can browse up to 40 pages of 30 posts each, subject to site limits. **Etiquetas del sitio** starts a
   search for the selected tag. Use the source picker to leave and return.

Each web source is off by default. Gelbooru includes every rating returned
by its API. Gelbooru controls what the API returns for the account. Rule34.xxx
shows posts rated **Safe**. Searches go to the selected site and media requests
go to its accepted CDN hosts. Each
site has its own settings and account. Gelbooru handles its responses with a
temporary in-memory library and media buffers, including when the site sends
`Cache-Control: no-store`. Its recent posts, search results and viewer marks
are cleared when that session ends. Startup tags and credentials remain
encrypted. Rule34.xxx uses its existing encrypted library and cache.
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
:webconnector:testDebugUnitTest. A debug-only fixture transport can substitute
synthetic responses for all HTTP calls when assembled with **-PwebFixture**.
Generate a PNG and a seekable MP4 named **fixture.png** and **fixture.mp4** in
**.qa/web-fixture**. Use synthetic credentials in an isolated emulator.
Use **fixture_gallery** for Rule34.xxx or **fixture_gelbooru** with the
key **gel_fixture_key** for Gelbooru. The fixture assets and transport are excluded from
release APKs.

The app-side integration and release-package checks do not establish that the
real API response schema, quotas and CDN validators match the fixture. Confirm
those with an approved non-explicit image and MP4 before distributing support
for the live site.
