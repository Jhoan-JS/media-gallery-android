# Validation

## Source and build checks

The gallery is version 2.18 (`versionCode 20`). The Telegram connector is
version 1.1 (`versionCode 2`).

Checks run on November 24, 2026 produced these results:

| Check | Result |
| --- | --- |
| Gallery unit tests | 93 tests in 25 suites; no failures, errors, or skips |
| Telegram connector Java tests | 5 tests in one suite; no failures, errors, or skips |
| Go engine | `go mod verify`, `go test ./...`, and `go build ./...` passed |
| Android Telegram engine | Built from source for arm64-v8a and x86_64 |
| Engine alignment | Both ABIs have ELF LOAD alignment of at least 16 KiB |
| Android gallery | `assembleDebug` and `assembleRelease` passed |
| Android connector | `assembleDebug` and `assembleRelease` passed |
| Local debug signing | Both APKs verified with the same disposable test certificate |
| Merged gallery manifest | No INTERNET permission; allowBackup=false; usesCleartextTraffic=false |
| Model preparation helper | Synthetic test with mocked network preserved the ONNX Runtime license |
| Gallery release Lint | 0 errors, 91 warnings |
| Connector release Lint | 0 errors, 12 warnings |

Most Lint warnings concern hard-coded text and unused resources. Others concern
control accessibility, ABI support, data extraction configuration, and dependencies.
They remain known issues; a successful build does not resolve those warnings.

Tests use synthetic values, in-memory stores, and interface doubles.
The source checks cover build outputs and source distribution, not a live account.

## Public release checks

The production release candidates were checked separately from the debug builds:

| Check | Result |
| --- | --- |
| Production signer | Both APKs verified with apksigner; matching dedicated RSA-4096 certificate |
| APK configuration | Release builds; not debuggable; original packages and component versions retained |
| Native libraries | All packaged ELF LOAD segments aligned to at least 16 KiB |
| Private build paths | Removed from both Go ABIs; no local user paths found in APK entries |
| Runtime notices | 19 Android dependency declarations checked; full vendor, model, and Go notices retained |
| Clean emulator install | Both signed ARM64 APKs installed on a new API 37 emulator with 16 KiB pages and ARM64 translation |
| Gallery unlock | PIN created, retained across a same-signer update and cold start, and used to unlock again |
| Local imports | One new synthetic PNG and one three-second synthetic MP4 imported without an account |
| Viewer | Image opened; video reached its end; returning to the library kept both items available |
| Telegram startup | Reached API ID/hash setup through the gallery; no credentials entered |

The emulator had no service account and its network was disabled during app
checks. No personal phone profile or account was used. These checks establish
basic release installation and local behavior, not live Telegram authentication
or physical ARM64 playback performance.

The matching GitHub Release records the final source commit, APK hashes, and
certificate fingerprint. An empty draft is not an installable release.

## Remaining limits

Live Telegram authentication, real library performance, and cross-signer
migration from an old lab installation are not established by the unit tests.
There is no redistributed Tato client. Model-based features require the full
prepared asset set. The app interface remains Spanish.

The public release signer differs from the older lab signer. Preserve an
encrypted backup and its passphrase before planning a migration.
