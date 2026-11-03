#!/system/bin/sh
# Servicio privilegiado de Monitor para custom ROMs y ports (sin root).
# Lo inicia init (monitord.rc). Usa la versión de Monitor instalada; si no la
# encuentra, la que viene en la ROM.
APK=$(pm path com.kevxngg.monitorroot 2>/dev/null | head -n 1 | cut -d: -f2)
if [ -z "$APK" ]; then
  APK=$(ls /product/app/Monitor/*.apk /product/priv-app/Monitor/*.apk 2>/dev/null | head -n 1)
fi
[ -z "$APK" ] && exit 1
export CLASSPATH="$APK"
exec /system/bin/app_process /system/bin com.kevxngg.monitorroot.helper.HelperMain
