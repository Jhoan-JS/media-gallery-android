# Contrato local de Tato

La implementación privada se encuentra en el proyecto de origen bajo bridge/src.
Depende de ejecutarse dentro de un cliente Tato Android modificado.
La preparación de publicación incluye la descripción del contrato y el consumidor
de la galería en app/src/main/java/local/ttlab/gallery/Source.java.

El cliente Tato, las clases extraídas, los DEX, las claves de firma y los scripts
de reempaquetado quedan fuera de esta copia. Un conector instalable necesita una
implementación del proveedor por el titular del cliente o una distribución autorizada.
Esta preparación carece de ese APK y no acredita un derecho a redistribuirlo.

## Permisos y autorización

La autoridad usada por el consumidor es local.ttlab.bridge. El proveedor exige
el permiso de firma local.ttlab.permission.GALLERY y valida el paquete de la
galería, el mismo usuario Android y el consentimiento para la cuenta activa.
La autorización se solicita a una actividad del conector mediante
startActivityForResult. La selección y revocación corresponden al conector.

El valor account identifica de forma opaca la cuenta autorizada. Cada operación
debe verificar que sigue siendo la cuenta vinculada. Las revisiones de los medios
impiden mezclar fragmentos de archivos que cambiaron durante una lectura.

## Consultas y medios

| Ruta relativa | Resultado esperado |
| --- | --- |
| identity | Cursor con account |
| capabilities | protocol, max_range y rpc_block |
| channels | Identificador y título, con peer_key opcional |
| media/{channel}?cursor={cursor}&limit=50 | Página de medios y extras next/newest |
| thumb/{channel}/{message} | Miniatura disponible |
| file/{channel}/{message} | Fragmento de archivo |
| stream/{channel}/{message} | Secuencia acotada de fragmentos |

Salvo identity, las operaciones incluyen account. Las lecturas de medios
incluyen revision. El cursor de medios contiene id, channel, name, mime, size,
date, width, height, duration, revision y content_key. El siguiente cursor
se comunica en los extras. Un cursor positivo debe avanzar hacia historial anterior.

Las lecturas usan ParcelFileDescriptor y modo r. Las rutas file y stream
reciben offset y length. El consumidor alinea offsets a 131072 bytes,
negocia capabilities y acepta hasta 1048576 bytes por rango en el protocolo 2.
Valida longitud, límite del archivo, cancelación e identidad de origen.
Los nombres, metadatos y miniaturas retornados son datos de entrada del proveedor.

El contrato ofrece lecturas. No incluye operaciones para enviar mensajes,
editar contenido, obtener credenciales ni exportar la sesión de Tato.
