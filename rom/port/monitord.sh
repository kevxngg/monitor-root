#!/system/bin/sh
# Monitor's privileged service for custom ROMs and ports (no root).
# Started by init (monitord.rc). Uses the installed Monitor version; if it is
# not found, the one bundled in the ROM.
APK=$(pm path com.kevxngg.monitorroot 2>/dev/null | head -n 1 | cut -d: -f2)
if [ -z "$APK" ]; then
  APK=$(ls /product/app/Monitor/*.apk /product/priv-app/Monitor/*.apk 2>/dev/null | head -n 1)
fi
[ -z "$APK" ] && exit 1
export CLASSPATH="$APK"
exec /system/bin/app_process /system/bin com.kevxngg.monitorroot.helper.HelperMain
