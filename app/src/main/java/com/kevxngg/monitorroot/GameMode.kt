package com.kevxngg.monitorroot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una partida: estadísticas guardadas al cerrar el juego. */
class GameSession(
    val pkg: String,
    val label: String,
    val start: Long,
    val end: Long,
    val fps: List<Int>,
    val cpuTempMax: Float?,
    val battStart: Int?,
    val battEnd: Int?,
) {
    private val valid get() = fps.filter { it > 0 }
    val avgFps: Int? get() = valid.takeIf { it.isNotEmpty() }?.average()?.toInt()
    /** FPS bajo: percentil 5, para ignorar tirones aislados al cargar. */
    val lowFps: Int? get() = valid.sorted().takeIf { it.isNotEmpty() }?.let { it[(it.size * 0.05).toInt()] }
    val maxFps: Int? get() = valid.maxOrNull()
    val minutes: Int get() = ((end - start) / 60_000).toInt()
    val battUsed: Int? get() = if (battStart != null && battEnd != null) battStart - battEnd else null

    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("label", label).put("start", start).put("end", end)
        .put("fps", JSONArray(fps)).put("temp", cpuTempMax?.toDouble() ?: JSONObject.NULL)
        .put("b0", battStart ?: JSONObject.NULL).put("b1", battEnd ?: JSONObject.NULL)

    companion object {
        fun fromJson(j: JSONObject): GameSession {
            val arr = j.optJSONArray("fps") ?: JSONArray()
            return GameSession(
                j.getString("pkg"), j.optString("label"), j.getLong("start"), j.getLong("end"),
                (0 until arr.length()).map { arr.getInt(it) },
                if (j.isNull("temp")) null else j.getDouble("temp").toFloat(),
                if (j.isNull("b0")) null else j.getInt("b0"),
                if (j.isNull("b1")) null else j.getInt("b1"),
            )
        }
    }
}

object GameSessions {
    private const val FILE = "game_sessions.json"
    private const val KEEP = 40

    @Synchronized
    fun read(ctx: Context): List<GameSession> = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { GameSession.fromJson(arr.getJSONObject(it)) }
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun add(ctx: Context, s: GameSession) {
        val all = (listOf(s) + read(ctx)).take(KEEP)
        File(ctx.filesDir, FILE).writeText(JSONArray(all.map { it.toJson() }).toString())
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}

object GameMode {
    /** Apps que Android marca como juegos (para sugerirlas). */
    @Suppress("DEPRECATION")
    fun isGame(ai: ApplicationInfo): Boolean {
        val byCategory = Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME
        val byFlag = (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        return byCategory || byFlag
    }

    fun start(ctx: Context) {
        val i = Intent(ctx, GameModeService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Exception) {
            // Android puede no dejar iniciarlo desde segundo plano; con root sí
            RootShell.run("am start-foreground-service -n ${ctx.packageName}/.GameModeService")
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, GameModeService::class.java))
    }

    fun summaryText(s: GameSession): String = buildList {
        add(tr("%d min", s.minutes))
        s.avgFps?.let { add(tr("%d FPS de promedio", it)) }
        s.lowFps?.let { add(tr("mínimo %d", it)) }
        s.cpuTempMax?.let { add(tr("%d °C máx.", it.toInt())) }
        s.battUsed?.takeIf { it > 0 }?.let { add(tr("%d %% de batería", it)) }
    }.joinToString(", ")
}

/**
 * Vigila qué app está en pantalla. Al abrir uno de tus juegos aplica el modo de
 * energía, muestra el monitor flotante, etc., y guarda el resumen de la partida.
 */
class GameModeService : Service() {
    @Volatile private var alive = false
    private var current: String? = null
    private var sessionStart = 0L
    private val fps = ArrayList<Int>()
    private var tempMax: Float? = null
    private var battStart: Int? = null
    private var savedCpu: CpuPower.Snapshot? = null
    private var startedOverlay = false
    private var changedDnd = false
    private var lastFg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.gameMode = false
            Prefs.save()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(applicationContext)
        I18n.init(applicationContext)
        ServiceCompat.startForeground(
            this, NOTIF_ID, waitingNotification(null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        alive = true
        Thread {
            // Permiso para saber qué app está en pantalla (se concede con root)
            if (RootShell.open()) RootShell.run("appops set $packageName GET_USAGE_STATS allow")
            while (alive) {
                try {
                    tick()
                } catch (_: Exception) {
                }
                SystemClock.sleep(if (screenActive()) 2000 else 5000)
            }
        }.start()
    }

    override fun onDestroy() {
        alive = false
        current?.let { pkg -> Thread { exitGame(pkg) }.start() }
        super.onDestroy()
    }

    /** App en primer plano según los eventos de uso de los últimos segundos. */
    private fun foreground(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return lastFg
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 15_000, now)
        val e = UsageEvents.Event()
        var fg = lastFg
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) fg = e.packageName
        }
        lastFg = fg
        return fg
    }

    /** Pantalla encendida y desbloqueada (si no, no se está jugando). */
    private fun screenActive(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        return (pm?.isInteractive ?: true) && !(km?.isKeyguardLocked ?: false)
    }

    private fun tick() {
        // Con la pantalla apagada o bloqueada se sale del modo juego: así el gobernador de
        // rendimiento, No molestar, etc. no siguen activos en el bolsillo.
        if (!screenActive()) {
            current?.let { exitGame(it) }
            return
        }
        val fg = foreground()
        val cur = current
        val isGame = fg != null && fg in Prefs.games
        if (cur == null && isGame) {
            enterGame(fg!!)
        } else if (cur != null && fg != null && fg != cur && fg != packageName) {
            exitGame(cur)
            if (isGame) enterGame(fg!!)
        }
        if (current != null && Prefs.gameRecord) record()
    }

    private fun battery(): Int? {
        val i = registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l >= 0 && sc > 0) l * 100 / sc else null
    }

    private fun enterGame(pkg: String) {
        current = pkg
        sessionStart = System.currentTimeMillis()
        fps.clear()
        tempMax = null
        battStart = battery()
        if (!RootShell.open()) return
        // Modo de energía (se guarda el estado completo de la CPU para devolverlo al salir)
        val mode = when (Prefs.gamePower) {
            1 -> CpuPower.Mode.PERFORMANCE
            2 -> CpuPower.Mode.BALANCED
            3 -> CpuPower.Mode.SAVER
            else -> null
        }
        if (mode != null) {
            val before = CpuPower.snapshot()
            if (CpuPower.isAvailable(mode, before.clusters)) {
                savedCpu = before
                CpuPower.apply(mode, before.clusters)
            }
        }
        // Cerrar apps en segundo plano para liberar memoria
        if (Prefs.gameKill) RootShell.run("am kill-all")
        // No molestar (solo si estaba desactivado)
        if (Prefs.gameDnd) {
            val zen = RootShell.run("settings get global zen_mode").firstOrNull()?.trim()
            if (zen == "0") {
                RootShell.run("cmd notification set_dnd priority")
                changedDnd = true
            }
        }
        // Monitor flotante
        if (Prefs.gameOverlay && !OverlayState.running) {
            startedOverlay = try {
                OverlayControl.start(applicationContext)
            } catch (_: Exception) {
                RootShell.run("am start-foreground-service -n $packageName/.OverlayService")
                true
            }
        }
        updateNotification(pkg)
    }

    private fun exitGame(pkg: String) {
        current = null
        if (RootShell.open()) {
            savedCpu?.let { CpuPower.restore(it) }
            if (changedDnd) RootShell.run("cmd notification set_dnd off")
        }
        savedCpu = null
        changedDnd = false
        if (startedOverlay) {
            OverlayControl.stop(applicationContext)
            startedOverlay = false
        }
        // Resumen de la partida (solo si duró al menos un minuto)
        val end = System.currentTimeMillis()
        if (Prefs.gameRecord && end - sessionStart >= 60_000) {
            val label = Apps.meta(pkg)?.label ?: pkg
            val s = GameSession(pkg, label, sessionStart, end, fps.toList(), tempMax, battStart, battery())
            GameSessions.add(applicationContext, s)
            notifySummary(s)
        }
        updateNotification(null)
    }

    private fun record() {
        val d = QuickStats.read(fps = true, ctx = applicationContext)
        d.fps?.let { fps.add(it) }
        val t = listOfNotNull(d.cpuTemp, d.battTemp).maxOrNull()
        if (t != null) tempMax = maxOf(tempMax ?: t, t)
    }

    // ---- Notificaciones ----
    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Modo juego"), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, tr("Resumen de partidas"), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 30, Intent(this, MainActivity::class.java).putExtra("page", "games"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun waitingNotification(pkg: String?): android.app.Notification {
        channel()
        val stop = PendingIntent.getService(
            this, 31, Intent(this, GameModeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (pkg == null) tr("Esperando a que abras uno de tus juegos.")
        else tr("Jugando: %s", Apps.meta(pkg)?.label ?: pkg)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(tr("Modo juego activo"))
            .setContentText(text)
            .setContentIntent(openApp())
            .addAction(0, tr("Desactivar"), stop)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(pkg: String?) {
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, waitingNotification(pkg))
        } catch (_: SecurityException) {
        }
    }

    private fun notifySummary(s: GameSession) {
        channel()
        val n = NotificationCompat.Builder(this, SUMMARY_CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(tr("Resumen de %s", s.label))
            .setContentText(GameMode.summaryText(s))
            .setStyle(NotificationCompat.BigTextStyle().bigText(GameMode.summaryText(s)))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(SUMMARY_ID + (s.start % 1000).toInt(), n)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val CHANNEL = "game_mode"
        private const val SUMMARY_CHANNEL = "game_summary"
        private const val NOTIF_ID = 3001
        private const val SUMMARY_ID = 3100
        const val ACTION_STOP = "com.kevxngg.monitorroot.STOP_GAME_MODE"
    }
}
