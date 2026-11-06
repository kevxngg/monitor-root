## Mejoras de seguridad
- Antes de instalar una actualización con root, la app comprueba que la APK sea Monitor, esté firmada con la misma clave, sea más nueva y llegue completa e intacta (huella SHA-256 publicada en cada versión). Si algo no coincide, no se instala.

## Batería
- La revisión en segundo plano ya no despierta al teléfono cada 5 minutos, y las actualizaciones se buscan como máximo una vez por hora (con la app abierta, como siempre).
- Los ajustes de la CPU, Servicios y Juegos dejan de actualizarse cuando la app está minimizada.

## Correcciones
- Si un comando del sistema se quedaba colgado, todas las funciones con root podían bloquearse hasta cerrar la app. Ahora cada comando tiene un límite de 20 segundos y la conexión root se recupera sola.
- Un valor de velocidad inválido podía congelar la app al dibujar las gráficas de disco y red.
