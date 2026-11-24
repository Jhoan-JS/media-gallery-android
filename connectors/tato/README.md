# Tato local provider interface

The gallery consumes an Android content provider implemented by a compatible
Tato connector. The consumer is in
[Source.java](../../app/src/main/java/local/ttlab/gallery/Source.java).

This repository documents that interface only. It does not include a Tato
client, connector APK, extracted classes, DEX files, signing keys, patches,
or repackaging scripts. A usable connector must come from the client owner
or an otherwise authorized distribution. There is no public Tato installation
package in this release.

## Permissions and authorization

The consumer uses the `local.ttlab.bridge` authority. The provider requires
the signature permission `local.ttlab.permission.GALLERY` and validates the
gallery package, the same Android user, and consent for the active account.

The gallery requests authorization through a connector activity using
`startActivityForResult`. Selection and revocation belong to the connector.

The `account` field is an opaque identity for the authorized account.
Every operation must confirm it still matches the linked account. Media
revisions prevent mixing chunks of a file that changed during a read.

## Queries and media

| Relative path | Expected result |
| --- | --- |
| `identity` | Cursor containing `account` |
| `capabilities` | `protocol`, `max_range`, and `rpc_block` |
| `channels` | ID and title, with optional `peer_key` |
| `media/{channel}?cursor={cursor}&limit=50` | Media page and `next`/`newest` extras |
| `thumb/{channel}/{message}` | Available thumbnail |
| `file/{channel}/{message}` | File range |
| `stream/{channel}/{message}` | Bounded sequence of ranges |

Operations other than `identity` include `account`. Media reads also include
`revision`. Media cursor fields are `id`, `channel`, `name`, `mime`, `size`,
`date`, `width`, `height`, `duration`, `revision`, and `content_key`.
The next cursor is returned in cursor extras. A positive cursor must advance
toward older history.

Reads use `ParcelFileDescriptor` in `r` mode. The `file` and `stream` paths
take `offset` and `length`. The consumer aligns offsets to 131,072 bytes,
negotiates capabilities, and accepts up to 1,048,576 bytes per range in
protocol 2. It validates lengths, file boundaries, cancellation, and source
identity. Returned names, metadata, and thumbnails remain untrusted provider input.

The contract exposes reads. It does not provide message sending, editing,
credential retrieval, or Tato session export.
