# Módulo de prueba (KernelSU / Magisk)

Sirve para probar el servicio de Monitor en un teléfono con root antes de integrarlo en una ROM.
No es necesario para usar Monitor.

1. Instala la APK de Monitor de la rama de pruebas.
2. Instala este módulo desde KernelSU o Magisk y reinicia.
3. En KernelSU, **quita el permiso de superusuario a Monitor** (para comprobar que funciona sin root).
4. Abre Monitor: en Configuración → Acerca de → Gestor de root debe decir "Servicio de la ROM (sin root)".

Comprobaciones por terminal: `getenforce` (Enforcing) y `ps -AZ | grep monitord` (u:r:monitord:s0).
