# Repository scope

The source tree contains the Gallery, the Telegram and Web companions, and the
optional isolated source worker. See the [build guide](build.md) for the available
configurations and [Releases](https://github.com/Jhoan-JS/media-gallery-android/releases)
for published APKs.

| Path | Contents |
| --- | --- |
| `app` | Gallery, encrypted storage, media viewer, imports, organization, and unit tests |
| `telegram` | Android connector and the Go engine sources |
| `webconnector` | Web connector and shared web transport |
| `isolated` | Local Android interfaces for the isolated source worker |
| `connectors/tato` | Documentation of the local provider interface |
| `tools` | Public model preparation and source-content checks |
| `licenses` | Retained third-party notices |

The gallery and Telegram connector can be built from this repository.
The gallery can import local files without a service account.

## Tato boundary

The gallery includes a consumer of the existing Tato provider interface.
An installable Tato connector requires an implementation supplied by the
client owner or an otherwise authorized distribution.

This repository does not include the Tato client, extracted client classes,
DEX files, native client libraries, connector implementation, patches,
repackagers, session material, or instructions to bypass protections.
See the [interface contract](../connectors/tato/README.md).

## Build inputs and release files

Model weights and binary vectors are fetched or generated from pinned public
inputs at build time. They are not committed to Git. Their origins and licenses
are recorded in the [model manifest](../app/src/main/assets/ml/semantic/PROVENANCE.json).

Compiled APKs belong in a GitHub Release, not the source tree. Release assets
must be signed with the persistent production identity and accompanied by
SHA-256 checksums. Debug APKs, unsigned APKs, private signing material,
credentials, sessions, databases, logs, backups, and personal media are excluded.

The source manifest records the original file hashes for provenance.
Build tooling, documentation, and legal notices have separate maintained files.
[VALIDATION.md](../VALIDATION.md) describes what was actually checked.
