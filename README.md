# Tato Galería para Android

Borrador local de publicación de la galería 2.18 y Conector TG 1.1.
La galería permite explorar catálogos paginados, reproducir medios bajo demanda,
guardar contenido cifrado sin conexión y organizarlo mediante favoritos,
Ver después, historial, álbumes, etiquetas, marcadores y archivos.
Incluye carpetas y chats fijados, portadas de video y respaldo cifrado de esa organización.

Esta copia procede de un snapshot del proyecto funcional. Los commits documentan
su preparación actual. La elección de la licencia del código propio queda pendiente.
Consulta [el alcance](docs/publication-scope.md) y [los avisos de terceros](THIRD_PARTY_NOTICES.md).

## Componentes

| Directorio | Contenido |
| --- | --- |
| app | Galería, almacenamiento cifrado, reproductor y pruebas unitarias |
| telegram | Conector Android y motor Go construido desde fuentes |
| connectors/tato | Contrato local del conector Tato y límites de esta distribución |
| tools | Preparación opcional de modelos públicos y comprobación del contenido publicable |

El cliente Tato modificado y sus empaquetadores requieren una revisión de
redistribución independiente. Esta copia permite usar archivos propios y el
conector Telegram compilado. El código consumidor de la interfaz local de Tato
se conserva en la galería. [Contrato Tato](connectors/tato/README.md)

## Requisitos

- Android 12 o posterior, API 31. La configuración por defecto genera ARM64.
- JDK 21, Android SDK 36 y Build Tools 36.0.0.
- Gradle 9.1.0 y Android Gradle Plugin 9.0.1.
- Para compilar Telegram, Android NDK 28.2.13676358 y Go 1.27.4.
- PowerShell para los scripts de preparación. Los módulos Java y Go también
  pueden compilarse con sus herramientas habituales en otros sistemas.

Configura JAVA_HOME y ANDROID_HOME localmente. El script de Telegram descarga
el SDK Go fijado y verifica su SHA-256, o utiliza TATO_GO_HOME si ya está instalado.
Las dependencias de compilación se descargan de sus repositorios públicos.

## Compilación local

En PowerShell, desde la raíz de esta copia, ejecuta lo siguiente.

```powershell
./build.ps1 -Gradle gradle
```

El proceso construye el motor Telegram desde fuentes, ejecuta las pruebas
unitarias y Lint y genera los APK de desarrollo de ambos módulos.
Usa -Gradle con la ruta de gradle.bat si Gradle no está en PATH.
La compilación de desarrollo emplea una clave desechable creada en .qa.
Ambas aplicaciones necesitan la misma firma para su permiso de comunicación local.
Los APK release quedan sin firmar. La firma de producción se configura fuera
de esta copia y debe conservar una identidad estable para futuras actualizaciones.

Los artefactos se generan bajo app/build y telegram/build.
Los pesos de reconocimiento visual se preparan por separado.

```powershell
python tools/prepare_semantic.py
python -m pip install numpy onnxruntime tokenizers
python tools/prepare_semantic.py --topics
```

El script obtiene modelos públicos con revisiones y hashes fijados.
Los vectores de temas se generan usando frases incluidas en el propio script.
La aplicación verifica los modelos y ejecuta la inferencia en el teléfono.
Esta instantánea de fuentes excluye los pesos ONNX y los vectores binarios.
Las funciones visuales requieren completar su preparación antes de compilar.

## Instalación y primer uso

1. Prueba los APK debug en un usuario Android separado o en un dispositivo de laboratorio.
2. Instala la galería y Conector TG generados en la misma compilación y usuario.
3. Crea el PIN de la galería. Archivos propios permite importar contenido que elijas.
4. Para Telegram, introduce tus datos de aplicación de Telegram dentro del conector
   y completa allí el inicio de sesión. Elige los chats que autorizas.
5. En la galería, selecciona Telegram y autoriza la conexión. Después actualiza la biblioteca.

Los APK debug tienen una firma distinta de la entrega instalada con la clave
privada del proyecto. Android exige la misma firma para actualizar esa instalación.
Conserva esa clave fuera del repositorio y realiza un respaldo cifrado antes
de cualquier migración. La copia de publicación carece de las claves originales.

## Privacidad y seguridad

La galería carece del permiso INTERNET. El conector Telegram se comunica
con Telegram para autenticar la cuenta y obtener el contenido autorizado.
El catálogo, la sesión del conector y la caché quedan en el almacenamiento
privado de cada aplicación. Se utilizan SQLCipher y AES-GCM con Android Keystore.
El transporte entre aplicaciones exige firma, paquete, usuario y consentimiento.

Las pantallas privadas emplean FLAG_SECURE. La huella es opcional y se conserva
el desbloqueo con PIN. La clave del almacenamiento permite reanudar tareas tras
reiniciar el proceso. El PIN controla el acceso a la interfaz y su verificador
es independiente de la clave de cifrado. Un dispositivo comprometido, un sistema
con acceso privilegiado o una aplicación autorizada con la misma firma amplían
el acceso que puede proteger esta arquitectura.

El reconocimiento visual usa contenido disponible y procesamiento local.
Los respaldos elegidos por el usuario se cifran con su frase. Los archivos
originales o copias que el usuario exporte a otro destino quedan sujetos a la
protección de ese destino. [Formato del respaldo](docs/privacy.md)

## Pruebas y estado de publicación

Las pruebas de esta preparación se ejecutan con datos sintéticos y sin cuentas.
Los resultados y las limitaciones están en [VALIDATION.md](VALIDATION.md).
La instantánea conserva el comportamiento de la 2.18 en su código de aplicación.
Los cambios de preparación afectan a compilación, firma de desarrollo y documentación.

Antes de publicar hay que elegir la licencia del código propio y confirmar
la atribución de sus recursos. Las dependencias mantienen sus licencias y autores.
Esta carpeta contiene una preparación local de fuentes.
