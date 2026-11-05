package com.kevxngg.monitorroot

/**
 * Datos del hardware que no cambian mientras el teléfono está encendido.
 * Se leen una sola vez con root y quedan en memoria.
 */
object DeviceInfo {
    class Info(
        /** Por ejemplo "6× Cortex-A55 + 2× Cortex-A76". */
        val coreTypes: String?,
        /** Por ejemplo "L1d" to "32 KB", "L2" to "128 KB · 256 KB". */
        val caches: List<Pair<String, String>>,
        /** Fabricante y modelo del almacenamiento interno. */
        val storageModel: String?,
        /** Segundos que tardó Android en arrancar (hasta mostrar la pantalla). */
        val bootSeconds: Float?,
    )

    @Volatile private var cached: Info? = null

    // Núcleos ARM: (fabricante, número de pieza) → nombre comercial
    private val PARTS = mapOf(
        "0x41:0xd03" to "Cortex-A53", "0x41:0xd04" to "Cortex-A35", "0x41:0xd05" to "Cortex-A55",
        "0x41:0xd07" to "Cortex-A57", "0x41:0xd08" to "Cortex-A72", "0x41:0xd09" to "Cortex-A73",
        "0x41:0xd0a" to "Cortex-A75", "0x41:0xd0b" to "Cortex-A76", "0x41:0xd0d" to "Cortex-A77",
        "0x41:0xd41" to "Cortex-A78", "0x41:0xd44" to "Cortex-X1", "0x41:0xd46" to "Cortex-A510",
        "0x41:0xd47" to "Cortex-A710", "0x41:0xd48" to "Cortex-X2", "0x41:0xd4d" to "Cortex-A715",
        "0x41:0xd4e" to "Cortex-X3", "0x41:0xd80" to "Cortex-A520", "0x41:0xd81" to "Cortex-A720",
        "0x41:0xd82" to "Cortex-X4", "0x41:0xd85" to "Cortex-X925", "0x41:0xd87" to "Cortex-A725",
        // Kryo de Qualcomm (basados en Cortex)
        "0x51:0x800" to "Kryo Gold", "0x51:0x801" to "Kryo Silver", "0x51:0x802" to "Kryo Gold",
        "0x51:0x803" to "Kryo Silver", "0x51:0x804" to "Kryo Gold", "0x51:0x805" to "Kryo Silver",
    )

    fun get(diskName: String?): Info {
        cached?.let { return it }
        val disk = diskName ?: "sda"
        val script = """
            echo @@CPUINFO; cat /proc/cpuinfo
            echo @@CACHE
            for d in /sys/devices/system/cpu/cpu*/cache/index*; do echo "§(cat §d/level)|§(cat §d/type)|§(cat §d/size)"; done
            echo @@DISK; cat /sys/block/$disk/device/vendor /sys/block/$disk/device/model /sys/block/$disk/device/name
            echo @@BOOT; logcat -b events -d | grep -m 1 boot_progress_enable_screen
        """.trimIndent().replace("§", "$")
        val sec = HashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        for (l in RootShell.run(script)) {
            if (l.startsWith("@@")) {
                val list = ArrayList<String>()
                sec[l.removePrefix("@@").trim()] = list
                cur = list
            } else cur?.add(l)
        }
        val info = Info(
            coreTypes = coreTypes(sec["CPUINFO"].orEmpty()),
            caches = caches(sec["CACHE"].orEmpty()),
            storageModel = sec["DISK"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifBlank { null },
            bootSeconds = sec["BOOT"]?.firstOrNull()
                ?.let { Regex("boot_progress_enable_screen:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                ?.let { it / 1000f },
        )
        // Solo se guarda si se pudo leer algo (si root aún no estaba listo, se reintenta)
        if (info.coreTypes != null || info.storageModel != null) cached = info
        return info
    }

    private fun coreTypes(lines: List<String>): String? {
        val names = ArrayList<String>()
        var implementer: String? = null
        for (l in lines) {
            val key = l.substringBefore(':').trim()
            val value = l.substringAfter(':', "").trim().lowercase()
            when (key) {
                "CPU implementer" -> implementer = value
                "CPU part" -> {
                    names.add(PARTS["${implementer ?: "0x41"}:$value"] ?: "CPU $value")
                    implementer = null
                }
            }
        }
        if (names.isEmpty()) return null
        // Se agrupan en orden de aparición: "6× Cortex-A55 + 2× Cortex-A76"
        return names.groupingBy { it }.eachCount().entries.joinToString(" + ") { (name, n) -> "$n× $name" }
    }

    private fun caches(lines: List<String>): List<Pair<String, String>> {
        val byLevel = LinkedHashMap<String, LinkedHashSet<String>>()
        lines.mapNotNull { l ->
            val p = l.split('|')
            if (p.size < 3 || p[0].isBlank() || p[2].isBlank()) null else Triple(p[0].trim(), p[1].trim(), p[2].trim())
        }.sortedBy { it.first }.forEach { (level, type, size) ->
            val name = when (type) {
                "Data" -> "L${level}d"
                "Instruction" -> "L${level}i"
                else -> "L$level"
            }
            byLevel.getOrPut(name) { LinkedHashSet() }.add(size.replace("K", " KB").replace("M", " MB"))
        }
        return byLevel.map { (name, sizes) -> name to sizes.joinToString(" · ") }
    }
}
