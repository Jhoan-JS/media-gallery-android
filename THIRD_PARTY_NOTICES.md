# Third-party components

Each dependency or model keeps its own license. [LICENSE_STATUS.md](LICENSE_STATUS.md)
limits the repository's MIT license to Jhoan Marte's project-owned code.
The supplied third-party license texts and copyright notices are retained unchanged.

| Component | Version or revision | Declared license and reference |
| --- | --- | --- |
| SQLCipher Android | 4.19.1 Community | BSD; vendor notice and Maven metadata |
| AndroidX SQLite | 2.2.0 | Apache-2.0; Maven metadata |
| AndroidX Media3 | 1.11.1 | Apache-2.0; included in app/src/main/assets/licenses/media3.txt |
| ONNX Runtime Android | 1.28.0 | MIT; LICENSE-RUNTIME.txt included with model assets |
| gotd/td and Go modules | go.mod and go.sum | Complete notices in telegram/engine/THIRD_PARTY_NOTICES.txt |
| Kotlin standard library and JetBrains annotations | 2.2.10 / 13.0 | Apache-2.0; resolved Maven metadata in the runtime inventory |
| Guava and its support artifacts | 33.3.1-android / 1.0.2 | Apache-2.0; resolved Maven metadata in the runtime inventory |
| LibTomCrypt, bundled by SQLCipher | 476a9579ae94f32b9ea9e2747bfb04b302370259 | Public-domain option of the supplied dual license, retained in licenses/LibTomCrypt.txt |
| Multilingual text model | 58edf8cada9e398793dca955574a48cbb7f18be2 | Apache-2.0 according to the model card |
| Xenova CLIP vision model | 6b7c523703ae26e39be4b03774966f8571c7f523 | MIT according to the model card |

SQLCipher, AndroidX, and ONNX notices are also kept under `licenses`.
The APKs package the applicable license texts under their `assets/licenses`
directories. The Telegram APK includes the Go dependency and runtime notices.
The [Android runtime inventory](licenses/Android-runtime-dependencies.txt)
records resolved versions and their Maven license declarations.
ONNX Runtime's complete third-party notice is retained from Microsoft's
official 1.28.0 NuGet distribution, alongside the runtime's MIT license.
The model manifest records download URLs, revisions, sizes, and hashes.
Model weights and generated binary vectors are prepared locally and are
excluded from Git. The text vocabulary is included with its license and revision.

The Java and Go sources in this repository implement the project's application
layer. XML resources use system fonts. Tato clients, their libraries and assets,
personal media, and components with unclear provenance are excluded.
Dependency and model authors retain their rights.

## References

- [SQLCipher licenses and notices](https://www.zetetic.net/sqlcipher/license/)
- [Media3 1.11.1 license](https://github.com/androidx/media/blob/1.11.1/LICENSE)
- [ONNX Runtime Android 1.28.0 Maven metadata](https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.pom)
- [gotd/td license](https://github.com/gotd/td/blob/v0.162.0/LICENSE)
- [Pinned text model](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/tree/58edf8cada9e398793dca955574a48cbb7f18be2)
- [Pinned vision model](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/6b7c523703ae26e39be4b03774966f8571c7f523)
- [Microsoft ONNX Runtime Managed 1.28.0 package](https://api.nuget.org/v3-flatcontainer/microsoft.ml.onnxruntime.managed/1.28.0/microsoft.ml.onnxruntime.managed.1.28.0.nupkg)
- [Pinned LibTomCrypt license](https://github.com/sqlcipher/libtomcrypt/blob/476a9579ae94f32b9ea9e2747bfb04b302370259/LICENSE)
