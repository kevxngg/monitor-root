## Novedades
- Cada clúster de la CPU muestra a qué frecuencia va en este momento, para comprobar que el modo elegido funciona.

## Correcciones
- Con el modo Mejor eficiencia (powersave), la CPU podía irse a la frecuencia máxima en procesadores MediaTek: el servicio de rendimiento del sistema sube la frecuencia mínima al tocar la pantalla y powersave la seguía. Ahora powersave también pone la frecuencia máxima en la mínima, así nada puede subirla. Al cambiar a otro gobernador, ese tope se quita solo.
