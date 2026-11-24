# Componentes de terceros

Las licencias de dependencias y modelos se aplican a sus componentes respectivos.
El archivo LICENSE_STATUS.md registra la decisión pendiente del código propio.

| Componente | Versión o revisión | Licencia declarada y evidencia |
| --- | --- | --- |
| SQLCipher Android | 4.19.1 Community | BSD, aviso del proveedor y metadatos Maven |
| AndroidX SQLite | 2.2.0 | Apache-2.0, metadatos Maven |
| AndroidX Media3 | 1.11.1 | Apache-2.0, licencia incluida en app/src/main/assets/licenses/media3.txt |
| ONNX Runtime Android | 1.28.0 | MIT, LICENSE-RUNTIME.txt incluido con las fuentes de assets |
| gotd/td y módulos Go | go.mod y go.sum | Avisos y textos completos en telegram/engine/THIRD_PARTY_NOTICES.txt |
| Modelo de texto multilingüe | 58edf8cada9e398793dca955574a48cbb7f18be2 | Apache-2.0 según la ficha del modelo |
| Modelo visual CLIP de Xenova | 6b7c523703ae26e39be4b03774966f8571c7f523 | MIT según la ficha del modelo |

Los avisos SQLCipher, AndroidX y ONNX también se conservan bajo licenses.
El manifiesto de modelos incluye hashes y orígenes. Los pesos y vectores binarios
se preparan localmente y están excluidos del historial.
El vocabulario de texto se conserva con su licencia y revisión.

Las fuentes Java y Go importadas pertenecen a la capa de aplicación del proyecto.
Los recursos XML usan fuentes del sistema. Quedaron excluidos los clientes Tato,
sus bibliotecas y recursos, las muestras multimedia y los componentes sin una
procedencia clara. Esta revisión es una preparación de distribución y requiere
que el titular confirme la licencia y atribución final.

Fuentes consultadas para los avisos.

- [SQLCipher](https://www.zetetic.net/sqlcipher/license/)
- [Media3 1.11.1](https://github.com/androidx/media/blob/1.11.1/LICENSE)
- [ONNX Runtime 1.28.0](https://github.com/microsoft/onnxruntime/blob/v1.28.0/LICENSE)
- [gotd/td](https://github.com/gotd/td/blob/v0.162.0/LICENSE)
- [Modelo de texto fijado](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/tree/58edf8cada9e398793dca955574a48cbb7f18be2)
- [Modelo visual fijado](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/6b7c523703ae26e39be4b03774966f8571c7f523)
