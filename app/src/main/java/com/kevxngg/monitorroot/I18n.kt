package com.kevxngg.monitorroot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Traducciones: el texto en español es la clave. Las traducciones están en
 * assets/i18n.tsv (una fila por texto, una columna por idioma).
 */
object I18n {
    /** Código, nombre nativo y si se escribe de derecha a izquierda. */
    val LANGS = listOf(
        Triple("es", "Español", false),
        Triple("en", "English", false),
        Triple("fr", "Français", false),
        Triple("pt", "Português", false),
        Triple("de", "Deutsch", false),
        Triple("it", "Italiano", false),
        Triple("ru", "Русский", false),
        Triple("ar", "العربية", true),
        Triple("zh", "中文", false),
        Triple("ja", "日本語", false),
        Triple("ko", "한국어", false),
    )

    private var columns: List<String> = emptyList()
    private var table: Map<String, List<String>> = emptyMap()

    var lang by mutableStateOf("es")
        private set

    val rtl get() = LANGS.firstOrNull { it.first == lang }?.third ?: false

    fun locale(): Locale = Locale.forLanguageTag(lang)

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\t", "\t")

    fun init(ctx: Context) {
        try {
            val lines = ctx.assets.open("i18n.tsv").bufferedReader(Charsets.UTF_8).readLines()
            columns = lines.first().split('\t')
            table = lines.drop(1).filter { it.isNotBlank() }.associate { line ->
                val cells = line.split('\t').map(::unescape)
                cells[0] to cells
            }
        } catch (_: Exception) {
        }
        apply(Prefs.language)
    }

    /** [pref] vacío = idioma del sistema (o inglés si no está disponible). */
    fun apply(pref: String) {
        lang = pref.ifEmpty {
            val sys = Locale.getDefault().language
            if (LANGS.any { it.first == sys }) sys else "en"
        }
    }

    fun get(es: String): String {
        val l = lang
        if (l == "es") return es
        val col = columns.indexOf(l)
        if (col < 0) return es
        return table[es]?.getOrNull(col)?.takeIf { it.isNotBlank() } ?: es
    }
}

/** Traduce un texto en español al idioma elegido. */
fun tr(es: String): String = I18n.get(es)

/** Traduce y rellena los %s / %d del texto. */
fun tr(es: String, vararg args: Any?): String = try {
    String.format(I18n.locale(), I18n.get(es), *args)
} catch (_: Exception) {
    String.format(es, *args)
}
