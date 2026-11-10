## Novedades
- Nuevo monitor flotante: un panel con medidores de CPU, GPU, RAM y temperatura, gráficas, red, almacenamiento y FPS. Tiene cuatro diseños (completo, compacto, gamer y retro), se ajusta de tamaño arrastrando la esquina inferior derecha, y con la esquina superior derecha se minimiza a una burbuja con el logo de la app que se pega al borde de la pantalla.
- Funciona sin root con Shizuku: al abrir la app eliges "Conectar con root" o "Conectar con Shizuku" (se puede cambiar en Configuración). Con Shizuku, Monitor lee procesos, CPU, memoria, red, almacenamiento, batería, temperaturas y FPS, y puede finalizar apps. Lo que necesita cambiar el sistema aparece marcado como "Solo con root".
- Con Shizuku, la batería, las temperaturas y el almacenamiento se leen por caminos alternativos cuando Android bloquea los archivos del sistema.

## Correcciones
- Si se perdía la conexión con root o Shizuku, la app se quedaba sin datos hasta cerrarla. Ahora se reconecta sola.
- El modo juego seguía activo con la pantalla bloqueada (rendimiento al máximo en el bolsillo). Ahora se desactiva al apagar la pantalla y se reactiva al volver al juego.
- Con Shizuku, las actualizaciones automáticas fallaban; ahora se instalan bien.
- El monitor flotante podía cerrarse al calcular el almacenamiento, la burbuja quedaba fuera de la pantalla al girarla, y los cambios de diseño o transparencia no se aplicaban hasta reabrirlo.
- Las acciones que solo funcionan con root (servicios del sistema, aplicaciones de arranque, prioridad de procesos) ya no fallan en silencio con Shizuku: aparecen marcadas.

## Nota
- La primera vez que abras esta versión verás la pantalla para elegir la conexión. Si usas root, elige "Conectar con root" y listo.
