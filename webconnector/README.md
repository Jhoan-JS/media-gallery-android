# Optional Web Connector

Media Gallery 2.19 can browse Rule34.xxx from its source picker. Install both
APK files in the same Android user with the same signing certificate. Android 12
or later is required. Telegram Connector 1.1 and the local Tato interface use
their existing protocols.

1. Unlock the gallery and choose **Fuentes web** in the source picker.
2. Tap **Configurar**. For an authorized adult test account, enter the account
   ID and its single API key on the connector screen, then tap **Habilitar y
   autorizar**. The gallery cannot read the key.
3. Enter one to eight tags and tap **Buscar**. Use **Todo**, **Imágenes** or
   **Videos** to filter the loaded pages. **Cargar más** reads the next page.
4. Tap a tile to open the existing viewer. **Etiquetas del sitio** starts a
   search for the selected tag. Use the source picker to leave and return.

The web source is off by default and only shows posts the service labels safe.
A site label does not independently verify what an image contains. Searches go
to the site and media requests go to its approved CDN hosts. Searches, position,
favorites, history, metadata and bounded temporary cache are stored separately
from chat libraries in the gallery's encrypted storage. **Revocar y apagar**
removes the API key and stops web requests. The last catalog and local marks
are retained for the same account. This version supports one account per
installation and does not export the web library or save permanent web downloads.

Playback uses the existing Media3 player. A successful seek requires a
compatible codec, correct HTTP content ranges, and a stable media version
validator. A video server that ignores byte ranges is shown as unsupported.
Small images can use a bounded full response when byte ranges are unavailable.

[Rule34 API documentation](https://api.rule34.xxx/) ·
[Rule34 terms](https://rule34.xxx/index.php?page=tos)

## Testing

Run :app:testDebugUnitTest, :telegram:testDebugUnitTest and
:webconnector:testDebugUnitTest. A debug-only fixture transport can substitute
synthetic responses for all HTTP calls when assembled with **-PwebFixture**.
Generate a PNG and a seekable MP4 named **fixture.png** and **fixture.mp4** in
**.qa/web-fixture**. Use synthetic credentials and the tag **fixture_gallery**
in an isolated emulator. The fixture assets and transport are excluded from
release APKs.

The app-side integration and release-package checks do not establish that the
real API response schema, quotas and CDN validators match the fixture. Confirm
those with an approved non-explicit image and MP4 before distributing support
for the live site.
