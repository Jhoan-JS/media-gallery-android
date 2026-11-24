# Protección de los datos

El catálogo usa SQLCipher. Los archivos de medios se almacenan en fragmentos
cifrados mediante AES-GCM. Android Keystore mantiene las claves de dispositivo.
Las autorizaciones locales se vinculan a la firma, el paquete, el usuario
Android y la identidad opaca de la cuenta.

La reanudación automática de tareas requiere que el proceso pueda acceder a la
clave de dispositivo. El PIN y la biometría protegen el acceso a la interfaz.
El PIN no se usa para derivar la clave con que se guarda el catálogo.
La instalación necesita un sistema Android confiable y su bloqueo de pantalla.

El formato de respaldo usa PBKDF2-HMAC-SHA256 con 210000 iteraciones y sal
aleatoria, seguido de cifrado autenticado AES-GCM. Los registros del respaldo
incluyen organización, portadas seleccionadas, carpetas y chats fijados.
La restauración valida la estructura y vuelve a cifrar los datos para el
dispositivo de destino. Las claves no exportables de Android Keystore se recrean
en ese dispositivo. El conector se vuelve a autorizar tras la migración.

Las dependencias y modelos se obtienen en la máquina de compilación.
Las imágenes y sus descripciones se procesan dentro del teléfono mediante ONNX Runtime.
La galería no dispone del permiso INTERNET. El conector Telegram usa la red para
las operaciones que el usuario solicita a Telegram.

El almacenamiento interno, los permisos y FLAG_SECURE reducen la exposición
entre aplicaciones y en capturas. El control del sistema, root, accesibilidad
privilegiada o la posesión de una clave de firma autorizada afectan a esos límites.
