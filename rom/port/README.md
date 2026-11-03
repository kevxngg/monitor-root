# Monitor en custom ROMs y ports (sin root, SELinux en Enforcing)

**Español** | [English](#english)

Para ports y ROMs que se hacen con **repack de particiones** (sin compilar desde AOSP). La app es la **APK normal**: se instala y se actualiza como cualquier app, y no necesita la clave de plataforma de la ROM. Un pequeño servicio que inicia Android al arrancar hace lo que en un teléfono normal hace el root.

**SELinux sigue en Enforcing** (`getenforce` → `Enforcing`). Solo el servicio de Monitor tiene su propio dominio, igual que el `su` de Magisk o KernelSU. Además, el servicio comprueba con el kernel qué app se conecta y **solo responde a Monitor**.

## Archivos

| Archivo del repositorio | Dónde va en la ROM | Permisos |
|---|---|---|
| `MonitorRoot.apk` (de Releases) | `/product/app/Monitor/Monitor.apk` | 644 |
| `rom/port/monitord.sh` | `/product/bin/monitord.sh` | 755 |
| `rom/port/monitord.rc` | `/product/etc/init/monitord.rc` | 644 |
| `rom/port/monitord.cil` | se **añade al final** de `/product/etc/selinux/product_sepolicy.cil` | — |

Todos con el contexto normal de `/product` (`u:object_r:system_file:s0`).

## Importante

- **Haz una copia** de `product_sepolicy.cil` antes de editarlo. Un error de escritura en ese archivo impide que el teléfono arranque. Pega las líneas tal cual.
- Al cambiar `product_sepolicy.cil`, Android compila la política al arrancar en lugar de usar la precompilada. Es normal y no hay que hacer nada más.
- Pon la APK en `/product/app`, no en `priv-app`: así no necesita archivos de permisos extra.

## Comprobar que funciona

```sh
getenforce                      # Enforcing
ps -AZ | grep monitord          # u:r:monitord:s0 ... app_process
```
En Monitor, **Configuración → Acerca de → Gestor de root** debe decir **"Servicio de la ROM (sin root)"**.

---

## English

For ports and ROMs made by **repacking partitions**. The app is the **regular APK** (installs and updates like any app, no platform key needed). A small service started by init does what root does on a regular phone. **SELinux stays Enforcing**: only the Monitor service gets its own domain, and it only answers to Monitor (checked with kernel peer credentials).

| Repo file | Goes to | Mode |
|---|---|---|
| `MonitorRoot.apk` | `/product/app/Monitor/Monitor.apk` | 644 |
| `rom/port/monitord.sh` | `/product/bin/monitord.sh` | 755 |
| `rom/port/monitord.rc` | `/product/etc/init/monitord.rc` | 644 |
| `rom/port/monitord.cil` | **append** to `/product/etc/selinux/product_sepolicy.cil` | — |

Back up `product_sepolicy.cil` first: a typo there prevents booting. Check with `getenforce` (Enforcing) and `ps -AZ | grep monitord` (`u:r:monitord:s0`). In Monitor, Settings → About → Root manager shows "ROM service (no root)".
