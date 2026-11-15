# Monitor on custom ROMs and ports (no root, SELinux Enforcing)

**English** | [Español](#español)

For ports and ROMs built by **repacking partitions** (not compiled from AOSP). The app is the **regular APK**: it installs and updates like any other app and does not need the ROM's platform key. A small service that Android starts at boot does what root does on a regular phone.

**SELinux stays Enforcing** (`getenforce` → `Enforcing`). Only the Monitor service gets its own domain, just like Magisk's or KernelSU's `su`. The service also checks with the kernel which app is connecting and **only answers to Monitor**.

## Files

| Repository file | Where it goes in the ROM | Mode |
|---|---|---|
| `MonitorRoot.apk` (from Releases) | `/product/app/Monitor/Monitor.apk` | 644 |
| `rom/port/monitord.sh` | `/product/bin/monitord.sh` | 755 |
| `rom/port/monitord.rc` | `/product/etc/init/monitord.rc` | 644 |
| `rom/port/monitord.cil` | **appended** to the end of `/product/etc/selinux/product_sepolicy.cil` | — |

All with the normal `/product` context (`u:object_r:system_file:s0`).

## Important

- **Back up** `product_sepolicy.cil` before editing it. A typo in that file prevents the phone from booting. Paste the lines exactly as they are.
- When `product_sepolicy.cil` changes, Android compiles the policy at boot instead of using the precompiled one. This is normal; nothing else is needed.
- Put the APK in `/product/app`, not `priv-app`: that way it needs no extra permission files.

## Check that it works

```sh
getenforce                      # Enforcing
ps -AZ | grep monitord          # u:r:monitord:s0 ... app_process
```
In Monitor, **Settings → About → Root manager** should say **"ROM service (no root)"**.

---

## Español

Para ports y ROMs que se hacen con **repack de particiones** (sin compilar desde AOSP). La app es la **APK normal**: se instala y se actualiza como cualquier app, y no necesita la clave de plataforma de la ROM. Un pequeño servicio que inicia Android al arrancar hace lo que en un teléfono normal hace el root. **SELinux sigue en Enforcing**: solo el servicio de Monitor tiene su propio dominio, y solo responde a Monitor (lo comprueba con el kernel).

| Archivo del repositorio | Dónde va en la ROM | Permisos |
|---|---|---|
| `MonitorRoot.apk` (de Releases) | `/product/app/Monitor/Monitor.apk` | 644 |
| `rom/port/monitord.sh` | `/product/bin/monitord.sh` | 755 |
| `rom/port/monitord.rc` | `/product/etc/init/monitord.rc` | 644 |
| `rom/port/monitord.cil` | se **añade al final** de `/product/etc/selinux/product_sepolicy.cil` | — |

Todos con el contexto normal de `/product` (`u:object_r:system_file:s0`). **Haz una copia** de `product_sepolicy.cil` antes de editarlo: un error de escritura impide que el teléfono arranque. Pon la APK en `/product/app`, no en `priv-app`. Para comprobarlo: `getenforce` (Enforcing) y `ps -AZ | grep monitord` (`u:r:monitord:s0`). En Monitor, **Configuración → Acerca de → Gestor de root** debe decir **"Servicio de la ROM (sin root)"**.
