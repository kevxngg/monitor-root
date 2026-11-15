package com.kevxngg.monitorroot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Translations: the Spanish text is the key. Translations live in
 * assets/i18n.tsv (one row per text, one column per language).
 */
object I18n {
    /** Code, native name and whether it is written right to left. */
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

    /** Empty [pref] = system language (or English if unavailable). */
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

/** Translates a Spanish text into the chosen language. */
fun tr(es: String): String = I18n.get(es)

/** Translates and fills in the %s / %d of the text. */
fun tr(es: String, vararg args: Any?): String = try {
    String.format(I18n.locale(), I18n.get(es), *args)
} catch (_: Exception) {
    String.format(es, *args)
}
