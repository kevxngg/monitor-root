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

/** A play session: stats saved when the game is closed. */
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
    /** Low FPS: 5th percentile, to ignore isolated stutters while loading. */
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

/** Which game is being played right now (to show it on the Games page). */
object GameModeState {
    @Volatile var playing: String? = null
}

object GameMode {
    /** Apps Android flags as games (to suggest them). */
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
            // Android may not allow starting it from the background; with root it does
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
 * Watches which app is on screen. When one of your games opens, it applies the power mode,
 * shows the floating monitor, etc., and saves the session summary.
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
    private var appliedPower = -1   // profile applied to the current game (-1 = none)
    private var awaySince = 0L      // since when the game is open but not on screen (0 = it is on screen)
    private var blindTicks = 0      // consecutive readings without any temperature during Diablo Mode

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
            // Permission to know which app is on screen (granted with root)
            if (RootShell.open()) RootShell.run("appops set $packageName GET_USAGE_STATS allow")
            DiabloMode.recoverIfNeeded(applicationContext)
            recoverCpuIfNeeded()
            while (alive) {
                try {
                    tick()
                } catch (_: Exception) {
                }
                // Short naps so that turning game mode off is noticed quickly
                val until = SystemClock.elapsedRealtime() + if (screenActive()) 2000 else 5000
                while (alive && SystemClock.elapsedRealtime() < until) SystemClock.sleep(250)
            }
            // The service was stopped: undo everything here, on the thread that applied it
            try {
                current?.let { exitGame(it, notify = false) }
            } catch (_: Exception) {
            }
        }.start()
    }

    override fun onDestroy() {
        alive = false   // the worker thread leaves the game and restores everything
        super.onDestroy()
    }

    /** Foreground app based on usage events from the last few seconds. */
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

    /** Screen on and unlocked (otherwise the user is not playing). */
    private fun screenActive(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        return (pm?.isInteractive ?: true) && !(km?.isKeyguardLocked ?: false)
    }

    private fun tick() {
        // With the screen off or locked, game mode is exited so the performance governor,
        // Do Not Disturb, etc. do not stay active in a pocket.
        if (!screenActive()) {
            current?.let { exitGame(it) }
            return
        }
        val fg = foreground()
        val cur = current
        if (cur != null) {
            // The session continues while the game is open, even if you briefly switch to another
            // app (ads, sign-in, another window). It ends when the game is closed (removed from
            // recents), when another of your games opens, or when the screen locks.
            val running = if (fg == cur) true else isRunning(cur)
            val otherGame = fg != null && fg != cur && fg in Prefs.games
            // If the process cannot be checked, falls back to the previous rule (another app on screen)
            val closed = running == false || (running == null && fg != null && fg != cur && fg != packageName)
            // Game left in the background: after a while the session ends (sooner with Diablo Mode,
            // so the phone is not left at full power without thermal control in a pocket or another app)
            val now = SystemClock.elapsedRealtime()
            if (fg == cur) awaySince = 0L else if (awaySince == 0L) awaySince = now
            val away = awaySince != 0L && now - awaySince > (if (DiabloMode.active) 60_000L else 180_000L)
            if (closed || otherGame || away) {
                exitGame(cur)
                if (otherGame) enterGame(fg!!)
            }
        } else if (fg != null && fg in Prefs.games) {
            enterGame(fg)
        }
        // The profile was changed on the Games page while playing: it is applied right away
        current?.let { pkg ->
            if (appliedPower != Prefs.gamePower && RootShell.open()) {
                revertProfile()
                applyProfile()
                updateNotification(pkg)
            }
        }
        if (current != null && (Prefs.gameRecord || DiabloMode.active)) record(inGame = fg == current)
        // Keep Diablo Mode: if something changed the frequencies or re-enabled thermal control, it is corrected
        if (DiabloMode.active) DiabloMode.enforce()
    }

    /** true if the game process is still alive; null if it cannot be checked (no root or Shizuku). */
    private fun isRunning(pkg: String): Boolean? =
        if (!RootShell.open()) null else RootShell.run("pidof ${Actions.q(pkg)}").any { it.isNotBlank() }

    private fun battery(): Int? {
        val i = registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l >= 0 && sc > 0) l * 100 / sc else null
    }

    private fun enterGame(pkg: String) {
        current = pkg
        GameModeState.playing = pkg
        awaySince = 0L
        blindTicks = 0
        sessionStart = System.currentTimeMillis()
        fps.clear()
        tempMax = null
        battStart = battery()
        if (!RootShell.open()) return
        applyProfile()
        // Close background apps to free memory
        if (Prefs.gameKill) RootShell.run("am kill-all")
        // Do Not Disturb (only if it was off)
        if (Prefs.gameDnd) {
            val zen = RootShell.run("settings get global zen_mode").firstOrNull()?.trim()
            if (zen == "0") {
                RootShell.run("cmd notification set_dnd priority")
                changedDnd = true
            }
        }
        // Floating monitor
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

    /** Applies the chosen performance profile to the game that is open. */
    private fun applyProfile() {
        val power = Prefs.gamePower
        appliedPower = power
        // Diablo profile: everything at maximum. If not possible, Mejor rendimiento (Best performance) is used
        val diablo = power == 4 && DiabloMode.enable(applicationContext)
        // Power mode (the full CPU state is saved to restore it on exit)
        val mode = if (diablo) null else when (power) {
            1, 4 -> CpuPower.Mode.PERFORMANCE
            2 -> CpuPower.Mode.BALANCED
            3 -> CpuPower.Mode.SAVER
            else -> null
        }
        if (mode != null) {
            val before = CpuPower.snapshot()
            if (CpuPower.isAvailable(mode, before.clusters)) {
                savedCpu = before
                // Remembered on disk: if Android kills the app mid-game, the CPU is reset on the next start
                gamePrefs().edit().putBoolean("cpuApplied", true).apply()
                CpuPower.apply(mode, before.clusters)
            }
        }
    }

    private fun gamePrefs() = getSharedPreferences("game", Context.MODE_PRIVATE)

    /** If the app was killed while a game had a power mode applied, the CPU goes back to balanced. */
    private fun recoverCpuIfNeeded() {
        val sp = gamePrefs()
        if (!sp.getBoolean("cpuApplied", false) || !RootShell.open()) return
        val clusters = CpuPower.read()
        if (CpuPower.isAvailable(CpuPower.Mode.BALANCED, clusters)) CpuPower.apply(CpuPower.Mode.BALANCED, clusters)
        sp.edit().putBoolean("cpuApplied", false).apply()
    }

    /** Undoes what [applyProfile] changed (Diablo Mode and the CPU power mode). */
    private fun revertProfile() {
        DiabloMode.disable(applicationContext)
        if (RootShell.open()) {
            savedCpu?.let { CpuPower.restore(it) }
            gamePrefs().edit().putBoolean("cpuApplied", false).apply()
        }
        savedCpu = null
        appliedPower = -1
    }

    private fun exitGame(pkg: String, notify: Boolean = true) {
        current = null
        GameModeState.playing = null
        revertProfile()
        if (changedDnd && RootShell.open()) RootShell.run("cmd notification set_dnd off")
        changedDnd = false
        if (startedOverlay) {
            OverlayControl.stop(applicationContext)
            startedOverlay = false
        }
        // Session summary (only if it lasted at least one minute)
        val end = System.currentTimeMillis()
        if (Prefs.gameRecord && end - sessionStart >= 60_000) {
            val label = Apps.meta(pkg)?.label ?: pkg
            val s = GameSession(pkg, label, sessionStart, end, fps.toList(), tempMax, battStart, battery())
            GameSessions.add(applicationContext, s)
            notifySummary(s)
        }
        if (notify) updateNotification(null)
    }

    private fun record(inGame: Boolean) {
        val d = QuickStats.read(fps = true, ctx = applicationContext)
        // Session stats only count while the game is on screen
        if (Prefs.gameRecord && inGame) {
            d.fps?.let { fps.add(it) }
            val t = listOfNotNull(d.cpuTemp, d.battTemp).maxOrNull()
            if (t != null) tempMax = maxOf(tempMax ?: t, t)
        }
        // Diablo Mode safety cutoff: too hot, or the temperature cannot be read
        if (DiabloMode.active) {
            val hot = DiabloMode.overheated(d.cpuTemp, d.battTemp)
            // Without any temperature reading the cutoff cannot protect the phone: the mode is turned off
            blindTicks = if (d.cpuTemp == null && d.battTemp == null) blindTicks + 1 else 0
            if (hot || blindTicks >= 3) {
                DiabloMode.disable(applicationContext)
                notifyDiabloOff(if (hot) d else null)
                current?.let { updateNotification(it) }   // the notification no longer says "Diablo Mode"
            }
        }
    }

    private fun notifyDiabloOff(d: QuickStats.Data?) {
        channel()
        val text = if (d != null) tr("El teléfono llegó a %s. Se restauró el control térmico para protegerlo.",
            listOfNotNull(d.battTemp?.let { tr("batería %d °C", it.toInt()) }, d.cpuTemp?.let { tr("CPU %d °C", it.toInt()) }).joinToString(", "))
        else tr("No se pudo leer la temperatura. Se restauró el control térmico para proteger el teléfono.")
        val n = NotificationCompat.Builder(this, SUMMARY_CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(tr("Modo Diablo desactivado"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(3200, n) } catch (_: SecurityException) {}
    }

    // ---- Notifications ----
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
        else tr("Jugando: %s", Apps.meta(pkg)?.label ?: pkg) + if (DiabloMode.active) " · " + tr("Modo Diablo") else ""
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
            NotificationManagerCompat.from(this).notify(SUMMARY_ID + (s.start / 1000 % 90).toInt(), n)
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
