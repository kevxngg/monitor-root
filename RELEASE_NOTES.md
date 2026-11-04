## Novedades
- Cada gobernador ahora muestra los ajustes que tienen sentido para él: performance y powersave dejan la frecuencia fija (en la máxima o en la mínima), userspace permite elegir una frecuencia exacta y los gobernadores dinámicos (schedutil, walt…) permiten limitar la frecuencia mínima y la máxima.
- Nueva opción de frecuencia mínima por clúster.
- El modo de energía muestra "Personalizado" cuando cambias los ajustes avanzados de la CPU.

## Correcciones
- El modo de energía y los ajustes avanzados de la CPU mostraban datos distintos (por ejemplo, "Mejor eficiencia" arriba y schedutil abajo): el modo solo miraba el primer núcleo y cada sección leía la CPU por su cuenta. Ahora las dos usan el mismo estado de todos los clústeres y se actualizan juntas.
- Con performance o powersave se podía cambiar la frecuencia aunque el gobernador la ignoraba. Ahora la frecuencia queda fija y al elegir performance la CPU va de verdad a su máxima.
- Lo mismo en la GPU: con performance o powersave la frecuencia aparece fija.
- El modo juego solo devolvía el gobernador al salir y dejaba los límites de frecuencia cambiados. Ahora restaura exactamente el estado que tenía cada clúster.
