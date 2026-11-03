#!/system/bin/sh
# Arranca el servicio de Monitor en el dominio monitord, como lo haría la ROM.
MODDIR=${0%/*}
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 3
(
  while true; do
    runcon u:r:monitord:s0 /system/bin/sh "$MODDIR/monitord.sh" > "$MODDIR/monitord.log" 2>&1
    sleep 5
  done
) &
