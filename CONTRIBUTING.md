# Contributing

Keep changes focused and preserve encrypted storage, source authorization,
and the behavior of existing libraries. Include the relevant unit test for
a behavior change and describe what you checked.

Start with the [build guide](docs/build.md). After preparing the Telegram
engine, run:

~~~powershell
./build.ps1 -Gradle gradle -TestsOnly
python tools/check_publication.py
~~~

For Android changes, run release Lint for both modules. For engine changes,
run `go mod verify`, `go test ./...`, and `go build ./...` from
`telegram/engine`. Use synthetic files and a clean test device or emulator
for UI checks. Unit tests do not establish live account behavior.

Do not submit credentials, API hashes, session data, private media, database
files, signing keys, or build artifacts. Report problems with the app and
Android versions, reproducible steps, and synthetic examples. Remove account
identifiers and secrets from any diagnostic material.

Keep third-party license texts and notices unchanged. Add a source and license
reference for new dependencies or assets. Tato contributions must stay within
the [documented local interface](connectors/tato/README.md); do not add a client,
extracted implementation, patch, repackager, or bypass instructions.

The user interface is currently Spanish. Public project documentation is
maintained in English. Preserve existing Git history and make new commits
with their real dates.
