# License scope

The project-owned code is available under the [MIT license](LICENSE),
copyright Jhoan Marte.

This covers the project's Java and Go code, tests, app-specific XML resources,
build configuration, scripts, and documentation. The Tato connector guide
documents a local interface. Release assets may also contain third-party code,
including the Tato runtime in Sources Connector 3.2. That code retains its
own terms and notices.

The following components retain their third-party licenses and copyright notices:

- `app/src/main/assets/ml/semantic/wordpiece.txt` and the public model files
  prepared for a build follow the licenses recorded in
  [PROVENANCE.json](app/src/main/assets/ml/semantic/PROVENANCE.json).
- Texts under `licenses`, `app/src/main/assets/licenses`,
  `telegram/src/main/assets/licenses`, and the
  `LICENSE-*.txt` files in the semantic model directory retain their notices.
- [Telegram engine notices](telegram/engine/THIRD_PARTY_NOTICES.txt) retain the
  rights and license terms of their respective authors.
- Dependencies declared in Gradle and `go.mod` retain their own licenses.

MIT does not replace those third-party licenses. Tato and Telegram trademarks
belong to their respective owners. This project is not affiliated with them.
