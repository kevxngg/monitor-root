# Test module (KernelSU / Magisk)

Lets you test the Monitor service on a rooted phone before integrating it into a ROM.
It is not needed to use Monitor.

1. Install the Monitor APK from the test branch.
2. Install this module from KernelSU or Magisk and reboot.
3. In KernelSU, **revoke Monitor's superuser permission** (to check that it works without root).
4. Open Monitor: Settings → About → Root manager should say "ROM service (no root)".

Terminal checks: `getenforce` (Enforcing) and `ps -AZ | grep monitord` (u:r:monitord:s0).

---

**Español:** sirve para probar el servicio de Monitor en un teléfono con root antes de integrarlo en una ROM. Instala la APK de la rama de pruebas, instala este módulo desde KernelSU o Magisk y reinicia, quita el permiso de superusuario a Monitor y comprueba en Configuración → Acerca de → Gestor de root que dice "Servicio de la ROM (sin root)".
