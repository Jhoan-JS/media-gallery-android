# Build from source

## Toolchain

The supplied scripts target Windows PowerShell. Use:

- JDK 21 with `JAVA_HOME` set.
- Android SDK 36, Build Tools 36.0.0, and `ANDROID_HOME` set.
- Gradle 9.1.0 on `PATH`, or pass the path to `gradle.bat`.
- Android Gradle Plugin 9.0.1, declared by the project.
- Android NDK 28.2.13676358 for the Telegram engine.
- Go 1.27.4. The engine script downloads the pinned Windows SDK and verifies
  its SHA-256, or uses `TATO_GO_HOME` when set to an existing installation.
- Python 3 for optional model preparation and the publication-content check.
  The model script uses `hashlib.file_digest`, which requires Python 3.11 or later.

The scripts resolve public build dependencies. They do not need a Telegram
account, API credentials, a Tato session, or an Android phone.

## Optional visual models

Prepare the models **before** assembling an APK if you want visual search:

~~~powershell
python tools/prepare_semantic.py
python -m pip install numpy onnxruntime tokenizers
python tools/prepare_semantic.py --topics
~~~

The script downloads pinned public weights and checks their hashes. It creates
topic vectors from phrases in the script. Model weights, projections, and
vectors stay out of Git. Android loads these assets locally; it does not
download the models at runtime.

A build without these files still has local browsing, playback, organization,
and connectors. The model-based features need the complete asset set.
Visual analysis is disabled for Telegram in this version.

## Build and check both apps

From the repository root:

~~~powershell
./build.ps1 -Gradle gradle
~~~

This builds the Telegram engine from Go source for ARM64 and x86_64, runs the
Java unit tests and release Lint, and assembles debug and unsigned release APKs.
The engine uses Go's `-trimpath` flag to omit local build paths from its binaries.
It also omits runtime module metadata because gomobile records absolute local
replacement paths there. Dependency versions, checksums, and licenses remain in
`go.mod`, `go.sum`, and the packaged notices.

The default APK ABI is `arm64-v8a`. For an x86_64 test emulator, after preparing
the engine, run:

~~~powershell
gradle :app:assembleDebug :telegram:assembleDebug -PuiQaAbi=x86_64
~~~

Both modules need the same ABI setting. ARMv7 and 32-bit x86 are not supported
by this configuration. The runtime minimum is Android 12 (API 31), even though
the project compiles and targets API 36.

Outputs:

| Component | Debug APK | Unsigned release APK |
| --- | --- | --- |
| Gallery | `app/build/outputs/apk/debug/app-debug.apk` | `app/build/outputs/apk/release/app-release-unsigned.apk` |
| Telegram | `telegram/build/outputs/apk/debug/telegram-debug.apk` | `telegram/build/outputs/apk/release/telegram-release-unsigned.apk` |

The root script generates a disposable key under `.qa` for local debug builds.
It is shared by both apps so their signature permissions work. These debug
APKs do not update the public production-signed release or old lab installations.

## Isolated sources

Gallery 2.31 can use the project's isolated source worker for Telegram and Web.
The worker handles network protocol traffic and Gallery keeps the encrypted
session and settings. Existing sessions and web credentials remain in their
original packages. Set up the same accounts from **Fuentes** when moving to
this configuration.

Build the open source worker and Gallery for an ARM64 test device with a
single signing identity:

~~~powershell
./telegram/build-engine.ps1 -IsolatedWorker
gradle :telegram:assembleRelease -PisolatedWorker -PisolatedEngineAar=.qa/tg-tools/isolated-tgengine-arm64.aar
gradle :app:assembleRelease -PisolatedClient -PqaIsolatedHost=local.ttlab.telegram
~~~

The worker build uses the `local.ttlab.telegram` package. Use a dedicated test
device or emulator so the worker cannot replace an existing standalone connector
signed with the same key. The worker handles Telegram and Web. See the
[Tato interface](../connectors/tato/README.md) for authorized implementations.

## Release signing

Release builds are unsigned by default. Sign both APKs with the same persistent
project identity outside the repository. Keep the keystore and passwords out
of Git, logs, command-line password arguments, and release assets.

Use Android's `zipalign` before `apksigner sign`, then check both outputs:

~~~powershell
apksigner verify --verbose --print-certs MediaGallery-2.18-arm64-v8a.apk
apksigner verify --verbose --print-certs TelegramConnector-1.1-arm64-v8a.apk
Get-FileHash -Algorithm SHA256 MediaGallery-2.18-arm64-v8a.apk
Get-FileHash -Algorithm SHA256 TelegramConnector-1.1-arm64-v8a.apk
~~~

The public release uses a dedicated production key. Its certificate differs
from the old lab signer. Android requires the same signing identity for an
in-place update; replacing an old installation requires a separate migration
plan and a verified encrypted backup. The two public apps must always retain
their matching production signer.

## Checks

After the Telegram engine has been prepared:

~~~powershell
./build.ps1 -Gradle gradle -TestsOnly
python tools/check_publication.py
~~~

The Go checks can also run from `telegram/engine` with Go 1.27.4:

~~~powershell
go mod verify
go test ./...
go build ./...
~~~

See [VALIDATION.md](../VALIDATION.md) for recorded results and remaining checks.
