# Privacy and data protection

## Storage and unlocking

The catalog uses SQLCipher. Media is stored in AES-GCM encrypted chunks,
with device keys held by Android Keystore. Local grants are bound to the
calling signature, package, Android user, and an opaque account identity.

Automatic task recovery requires the process to access its device key after
a restart. The PIN and optional biometrics guard the interface. The PIN is not
used to derive the catalog encryption key. This design depends on a trusted
Android OS and its screen lock.

Private screens use `FLAG_SECURE`, and Android app backups are disabled.
Private storage, permissions, and screenshot restrictions reduce exposure to
other apps; they do not protect against a compromised OS, root access,
privileged accessibility access, or possession of an authorized signing key.

## Network and connectors

The gallery has no `INTERNET` permission. Source companions use the network
to authenticate and request selected Telegram content or query enabled websites.
The Telegram session is separate from the official app's session.

Standalone companions store encrypted settings and session data in their own
app storage. In the [isolated configuration](build.md#isolated-sources), Gallery
stores them and the isolated worker processes network traffic. The companion's
socket service limits outbound destinations and opens connections for that
worker. Enabled websites receive the searches sent to them and the requests
needed to retrieve media.

Selecting chats and authorizing the gallery are separate steps. Updating the
selection requires authorization again. Intentionally opening media can report
reads and views to Telegram. Secret, ephemeral, and protected content is excluded.

The Tato interface requires a compatible, separately authorized provider.
Sources Connector 3.2 bundles that runtime as a release asset. The Git source
tree only documents the Tato provider interface and the Gallery-side integration.

## Visual processing

Dependencies and public models are downloaded on the build machine.
Images and descriptions are processed on the phone with ONNX Runtime.
The gallery does not send images or metadata to an external AI service.
Analysis uses available media and does not require downloading the whole library.
The Telegram source does not run visual analysis in this version.

## Encrypted backups and migration

Backups use PBKDF2-HMAC-SHA256 with 210,000 iterations and a random salt,
followed by authenticated AES-GCM encryption. They include organization,
chosen covers, chat folders, and pinned chats.

Restore validates the structure and encrypts data with keys on the destination
device. Non-exportable Android Keystore keys are recreated there rather than
extracted. Connector authorization must be renewed after migration.

Keep the backup passphrase separately from the backup. Original files and
unencrypted copies you deliberately export depend on the protection of their
destination.

The public release uses a different certificate from the older lab builds.
Android will reject an in-place update between those signers. Export and
retain an encrypted backup before considering removal of an old installation.
Cross-signer migration has not been validated in this release.
