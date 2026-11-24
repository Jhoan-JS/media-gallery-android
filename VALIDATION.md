# Verificación local de la preparación

Fecha de verificación 24 de noviembre de 2026.

La base histórica se verificó mediante el APK 2.18 y sus metadatos de
compilación, el versionCode 20 del código y el SHA-256 registrado en
SOURCE_MANIFEST.json. Conector TG declara versión 1.1 y versionCode 2.
La entrega Conector Tato 2.5 se identificó en el proyecto original.

## Resultados

| Comprobación | Resultado |
| --- | --- |
| Pruebas unitarias de galería | 93 pruebas en 25 suites, cero fallos, errores u omisiones |
| Pruebas unitarias Java de Conector TG | 5 pruebas en una suite, cero fallos, errores u omisiones |
| Motor Go | go mod verify, go test ./... y go build ./... completados |
| Biblioteca Android de Telegram | Compilada desde estas fuentes para arm64-v8a y x86_64 |
| Alineación del motor | Segmentos ELF LOAD de ambas ABI con alineación de 16 KiB o mayor |
| Galería Android | assembleDebug y assembleRelease completados |
| Conector TG Android | assembleDebug y assembleRelease completados |
| Firmas de desarrollo | Ambos APK verificados y con el mismo certificado desechable |
| Manifiesto final de galería | INTERNET ausente, allowBackup=false, usesCleartextTraffic=false |
| Lint release de galería | Cero errores y 91 advertencias |
| Lint release de Conector TG | Cero errores y 12 advertencias |

La mayoría de advertencias de Lint corresponden a textos construidos en código
y recursos sin usar. También hay advertencias de accesibilidad de controles,
compatibilidad de ABI, configuración de extracción de datos y dependencias.
La preparación conserva el código funcional existente y registra esas advertencias.

Las pruebas usan valores sintéticos, almacenes en memoria y dobles de las
interfaces. La compilación resuelve dependencias públicas y utiliza una nueva
clave de desarrollo propia de esta preparación.

## Alcance pendiente

La validación de esta copia cubre compilación, pruebas unitarias, Lint,
firma de desarrollo y comprobación estática de la distribución de fuentes.
La copia se entrega sin los pesos de reconocimiento visual y sin el cliente Tato.
Su descarga y generación se documentan en README.md.

Quedan pendientes la aceptación en un dispositivo con cuentas de prueba,
el rendimiento de reproducción en el hardware final y la inferencia con los
modelos completos. La migración de una instalación ya firmada requiere
conservar la identidad de firma privada del proyecto y sus respaldos cifrados.
La elección de la licencia del código propio también sigue pendiente.

Los artefactos y los registros de las pruebas se conservan en la carpeta
de trabajo de preparación. La copia final de fuentes se obtiene del
historial local y excluye compilados, modelos binarios, claves y cachés.
