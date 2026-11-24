# Alcance de la preparación

La base verificada es la galería 2.18, versionCode 20, y Conector TG 1.1,
versionCode 2. El SHA-256 del APK histórico 2.18 es
3746dc4286bfefab2e68e7194ec2864a6ae1f2565961fa47edf36bc4fe3064ec.
La entrega histórica Conector Tato 2.5 se conserva en el proyecto original.

SOURCE_MANIFEST.json registra las rutas, tamaños y hashes de los archivos
seleccionados del origen. La copia se creó por lista de inclusión.
Las fuentes Java de la aplicación y del conector Telegram, sus recursos XML,
pruebas unitarias y motor Go se conservaron. Las configuraciones de compilación
se adaptaron para usar herramientas configurables y una firma debug desechable.

Quedaron excluidos los APK y paquetes de distribución, las compilaciones,
.qa del origen, Windows y Linux de escritorio, proyectos de ingeniería inversa,
registros, evidencias de dispositivo, bases de datos, sesiones, copias de seguridad,
credenciales y material de firma. También se excluyeron los pesos binarios,
las muestras multimedia y los scripts que instalan o manipulan perfiles del teléfono.

Los tests instrumentados originales dependen en parte del cliente de laboratorio,
su exportador y recursos externos. Esta copia incluye las pruebas unitarias
independientes y deja la aceptación de dispositivo como verificación pendiente.

La implementación Tato por reflexión, el exportador y el reempaquetado permanecen
en el origen. El contrato público del conector describe la interfaz local existente.

El origen y sus directorios padre carecen de un repositorio Git aplicable.
Los repositorios históricos presentes en los experimentos de terceros no
pertenecen a la galería Android. Se creó una historia actual de importación por
componentes, sin reconstruir fechas ni atribuir a la persona usuaria código ajeno.
