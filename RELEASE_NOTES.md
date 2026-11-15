## What's new
- Diablo Mode now works while the phone is charging. Before, it silently fell back to the Performance profile when plugged in, so the GPU was not pinned. Use the original charger so the phone gets all the power it needs; playing while charging heats the battery more, and the temperature cutoff (battery 46 °C, CPU 95 °C) still protects the phone.
- Diablo Mode keeps the GPU pinned: if the manufacturer's power service undoes the fixed frequency, it is set again within a few seconds.
- GPU: the maximum frequency shown is now the real one the chip can run at (on MediaTek it showed a higher value from a table that is not in use).
- Disk: "Active time" was labelled "Up time" in English and other languages.
- Game mode now detects a game that was already on screen when it started (for example right after the app updates itself in the middle of a session).
- Diablo Mode on Xiaomi/HyperOS: the CPU now really reaches its maximum frequency. The manufacturer's thermal service left its frequency cap in place even after being stopped, so the CPU could stay pinned below the maximum (for example 1.6 GHz instead of 2.0 GHz).
