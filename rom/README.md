# Monitor para ROMs (sin root)

**Español** | [English](#english)

La **edición de sistema** de Monitor funciona **sin root** cuando viene integrada en una ROM como app del sistema. Usa el usuario del sistema (`android.uid.system`), así que tiene que ir **firmada con la clave de la plataforma de tu ROM**.

## Integración

1. Descarga `MonitorRoot-system.apk` de la [última versión](https://github.com/kevxngg/monitor-root/releases/latest).
2. Copia esta carpeta `rom/` en tu árbol, por ejemplo en `packages/apps/Monitor`, y pon el APK dentro.
3. Añade a tu `device.mk` o `product.mk`:
   ```make
   PRODUCT_PACKAGES += Monitor
   ```
4. Si SELinux bloquea algo (lo verás como `avc: denied` en el logcat), usa `sepolicy/system_app.te` como punto de partida.

El `Android.bp` vuelve a firmar el APK con `certificate: "platform"` y lo instala como app privilegiada en `system_ext`.

## ¿Qué APK uso?

Primero revisa con qué claves está firmada tu ROM:
```sh
adb shell getprop ro.build.tags
```

- **`test-keys`**: usa **`MonitorRoot-system-testkey.apk`**, que ya viene firmada con la clave de plataforma de prueba pública de AOSP. Puedes ponerla directamente en `/system_ext/priv-app/Monitor/Monitor.apk`, sin firmar nada.
- **`release-keys`**: tu ROM usa una clave propia. Usa **`MonitorRoot-system.apk`** y fírmala con tu clave de plataforma, ya sea con el `Android.bp` (`certificate: "platform"`) o a mano:
  ```sh
  apksigner sign --key platform.pk8 --cert platform.x509.pem --out Monitor.apk MonitorRoot-system.apk
  ```

Si la etiqueta dice `test-keys` pero la app no aparece después de reiniciar, la ROM usa otra clave aunque diga test-keys: fírmala con la tuya.

## Diferencias con la edición normal

- No pide root: usa la shell del sistema.
- No se actualiza desde GitHub: se actualiza con cada versión de tu ROM.
- La edición de sistema **no se puede instalar** en un teléfono normal: Android la rechaza porque no está firmada con su clave de plataforma.

## Qué depende de la ROM

Como app del sistema, Monitor puede hacer casi todo lo que hace con root. Lo que puede variar según la ROM es lo que controla SELinux: leer los procesos de otras apps y cambiar los gobernadores de CPU y GPU. Si algo no funciona, revisa los `avc: denied` y ajusta la política.

---

## English

The **system edition** of Monitor works **without root** when it's built into a ROM as a system app. It runs as the system user (`android.uid.system`), so it must be **signed with your ROM's platform key**.

1. Download from the [latest release](https://github.com/kevxngg/monitor-root/releases/latest): `MonitorRoot-system-testkey.apk` if `getprop ro.build.tags` says `test-keys` (already signed with the public AOSP platform test key, just drop it into `/system_ext/priv-app/Monitor/`), or `MonitorRoot-system.apk` to sign with your own platform key.
2. Copy this `rom/` folder into your tree (for example `packages/apps/Monitor`) and put the APK inside.
3. Add `PRODUCT_PACKAGES += Monitor` to your `device.mk` or `product.mk`.
4. If SELinux blocks something (`avc: denied` in logcat), use `sepolicy/system_app.te` as a starting point.

The system edition doesn't ask for root, doesn't update from GitHub (it updates with your ROM), and can't be installed on a regular phone.
