package com.kevxngg.monitorroot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ISSUES = "https://github.com/kevxngg/monitor-root/issues/new"

/** Copies the report and opens a new GitHub issue with the phone's data already filled in. */
fun reportOnGitHub(ctx: Context, report: String) {
    try {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Monitor", report))
    } catch (_: Exception) {
    }
    // The link carries only the device summary (URLs have a size limit); the full report is pasted
    val head = report.lineSequence().takeWhile { it.isNotBlank() }.joinToString("\n")
    val body = "**Describe the problem:**\n\n\n**Device:**\n```\n$head\n```\n\n**Diagnostics report** (already copied, paste it here):\n"
    val url = ISSUES + "?title=" + Uri.encode("Problem on " + android.os.Build.MODEL) + "&body=" + Uri.encode(body)
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

/** Shows the diagnostics report, with share, copy, report on GitHub and delete. */
@Composable
fun DiagDialog(onDismiss: () -> Unit) {
    val win = LocalWin.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { text = withContext(Dispatchers.IO) { Diag.report(ctx) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Informe de diagnóstico"), fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("Qué funciones van bien y cuáles fallan en este teléfono. Se guarda solo aquí: no se envía nada a menos que lo compartas."),
                    fontSize = 12.sp, color = win.sub,
                )
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(text ?: tr("Cargando…"), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = win.text)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val t = text ?: return@TextButton
                        try {
                            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Monitor", t))
                            Feedback.show(tr("Informe copiado"))
                        } catch (_: Exception) {
                        }
                    }) { Text(tr("Copiar")) }
                    TextButton(onClick = { confirmClear = true }) { Text(tr("Borrar")) }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { text?.let { reportOnGitHub(ctx, it) } }) { Text(tr("Informar en GitHub")) }
                TextButton(onClick = {
                    val t = text ?: return@TextButton
                    try {
                        ctx.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                    }
                }) { Text(tr("Compartir")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
    if (confirmClear) {
        ConfirmDialog(tr("Informe de diagnóstico"), tr("¿Borrar el informe de diagnóstico?"), tr("Borrar"), onConfirm = {
            scope.launch(Dispatchers.IO) {
                Diag.clear(ctx)
                text = Diag.report(ctx)
            }
        }, onDismiss = { confirmClear = false })
    }
}
