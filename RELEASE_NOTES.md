## Novedades
- El proyecto incorpora la licencia MIT, con atribución a kevxngg.
- Diagnóstico de actualizaciones: distingue problemas de DNS, tiempo de espera, conexión segura, servidor y datos inválidos. Si falla la conexión y hay DNS privado activo, muestra una sugerencia para revisarlo.
- Las comprobaciones en segundo plano reintentan si falla la consulta. Las alarmas delegan la conexión a WorkManager para evitar exceder el tiempo permitido por Android.
- Actualización obligatoria al acumular tres builds de atraso: por ejemplo, con la build 10 y la 13 publicada. La pantalla permite actualizar o descargar el APK en el navegador.
- La última versión comprobada se conserva para mantener el límite conocido incluso sin conexión. Sin una versión comprobada no se bloquea la app por un fallo de red.
- Los controles de actualización vuelven a estar disponibles si la instalación no termina y la app sigue abierta.
