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
    /** Hottest CPU reading (sessions saved before 4.2 stored the hottest of CPU and battery here). */
    val cpuTempMax: Float?,
    val battStart: Int?,
    val battEnd: Int?,
    val battTempMax: Float? = null,
    /** The phone was charging at some point: the battery used is not meaningful. */
    val charging: Boolean = false,
    /** Profile (0-4, -1 unknown) and refresh rate (0 = unchanged) used. */
    val profile: Int = -1,
    val hz: Int = 0,
    /** Why it ended: closed, other, away, screen, off, manual ("" unknown). */
    val endReason: String = "",
    /** Seconds during which Android reported the phone as hot enough to slow down. */
    val throttleSec: Int = 0,
    /** Seconds the game was on screen (the rest it was open in the background). */
    val onScreenSec: Int = 0,
) {
    private val valid get() = fps.filter { it > 0 }
    val avgFps: Int? get() = valid.takeIf { it.isNotEmpty() }?.average()?.toInt()
    /** Low FPS: 5th percentile, to ignore isolated stutters while loading. */
    val lowFps: Int? get() = valid.sorted().takeIf { it.isNotEmpty() }?.let { it[(it.size * 0.05).toInt()] }
    /** 1 % low: the worst moments of the session. */
    val low1Fps: Int? get() = valid.sorted().takeIf { it.size >= 20 }?.let { it[(it.size * 0.01).toInt()] }
    val maxFps: Int? get() = valid.maxOrNull()
    /** Share of the time (%) the FPS stayed within 10 % of the average: how even the game ran. */
    val stability: Int? get() {
        val avg = avgFps ?: return null
        if (valid.size < 10) return null
        return valid.count { it >= avg * 0.9 } * 100 / valid.size
    }
    val minutes: Int get() = ((end - start) / 60_000).toInt()
    /** Battery used, only when it means something (not while charging). */
    val battUsed: Int? get() = if (battStart != null && battEnd != null && !charging && battStart >= battEnd) battStart - battEnd else null
    /** Battery per hour of play, from sessions of 5 minutes or more. */
    val drainPerHour: Int? get() = battUsed?.takeIf { minutes >= 5 }?.let { it * 60 / minutes }

    fun toJson(): JSONObject = JSONObject()
        .put("pkg", pkg).put("label", label).put("start", start).put("end", end)
        .put("fps", JSONArray(fps)).put("temp", cpuTempMax?.toDouble() ?: JSONObject.NULL)
        .put("b0", battStart ?: JSONObject.NULL).put("b1", battEnd ?: JSONObject.NULL)
        .put("bt", battTempMax?.toDouble() ?: JSONObject.NULL).put("chg", charging)
        .put("prof", profile).put("hz", hz).put("why", endReason).put("thr", throttleSec).put("scr", onScreenSec)

    companion object {
        fun fromJson(j: JSONObject): GameSession {
            val arr = j.optJSONArray("fps") ?: JSONArray()
            return GameSession(
                j.getString("pkg"), j.optString("label"), j.getLong("start"), j.getLong("end"),
                (0 until arr.length()).map { arr.getInt(it) },
                if (j.isNull("temp")) null else j.getDouble("temp").toFloat(),
                if (j.isNull("b0")) null else j.getInt("b0"),
                if (j.isNull("b1")) null else j.getInt("b1"),
                battTempMax = if (j.has("bt") && !j.isNull("bt")) j.getDouble("bt").toFloat() else null,
                charging = j.optBoolean("chg", false),
                profile = j.optInt("prof", -1),
                hz = j.optInt("hz", 0),
                endReason = j.optString("why", ""),
                throttleSec = j.optInt("thr", 0),
                onScreenSec = j.optInt("scr", 0),
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

    /** Deletes one session (identified by its start time). */
    @Synchronized
    fun remove(ctx: Context, start: Long) {
        val all = read(ctx).filter { it.start != start }
        File(ctx.filesDir, FILE).writeText(JSONArray(all.map { it.toJson() }).toString())
    }
}

/**
 * Short log of game mode (when a game starts and ends, and why), kept in the app's files so a
 * session that ends on its own can be explained. Only the last 200 lines are kept.
 */
object GameLog {
    private const val FILE = "game_log.txt"

    @Synchronized
    fun add(ctx: Context, msg: String) {
        try {
            val f = File(ctx.filesDir, FILE)
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val lines = (if (f.exists()) f.readLines() else emptyList()) + "$time $msg"
            f.writeText(lines.takeLast(200).joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }
}

/** What game mode is doing right now (shown on the Games page and in its notification). */
object GameModeState {
    @Volatile var playing: String? = null
    /** When the current session started (System.currentTimeMillis, 0 = none). */
    @Volatile var sessionStart = 0L
    /** What was actually applied to this game (the profile may not be available on every phone). */
    @Volatile var profile = -1
    @Volatile var hz = 0
    @Volatile var dnd = false
    @Volatile var appsClosed = 0
    @Volatile var overlay = false
    /** Diablo Mode could not be turned on, or was turned off by the safety cutoff. */
    @Volatile var diabloFailed = false
    @Volatile var diabloCut = false
    /** Last readings while playing (null = not read yet). */
    @Volatile var fps: Int? = null
    @Volatile var cpuTemp: Float? = null
    @Volatile var battTemp: Float? = null
    /** Android says the phone is hot enough to slow down. */
    @Volatile var throttling = false
    /** Session to open on the Games page (from the summary notification), 0 = none. */
    @Volatile var openSession = 0L

    fun reset() {
        playing = null; sessionStart = 0L; profile = -1; hz = 0; dnd = false; appsClosed = 0; overlay = false
        diabloFailed = false; diabloCut = false; fps = null; cpuTemp = null; battTemp = null; throttling = false
    }
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
        if (s.throttleSec >= 60) add(tr("%d min con calor", s.throttleSec / 60))
    }.joinToString(", ")

    /** Usage access lets game mode see which app is on screen (granted automatically with root). */
    fun hasUsageAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        true
    }

    /** Readable end reason of a session. */
    fun endReasonText(code: String): String? = when (code) {
        "closed" -> tr("Cerraste el juego")
        "other" -> tr("Abriste otro de tus juegos")
        "away" -> tr("El juego quedó demasiado tiempo en segundo plano")
        "screen" -> tr("Se apagó o bloqueó la pantalla")
        "off" -> tr("Desactivaste el modo juego")
        "manual" -> tr("Terminaste la partida desde la notificación")
        else -> null
    }
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
    private var battTempMax: Float? = null
    private var battStart: Int? = null
    private var chargingSeen = false
    private var throttleSec = 0
    private var onScreenSec = 0
    private var lastRecordAt = 0L
    private var warnedHot = false
    private var savedCpu: CpuPower.Snapshot? = null
    private var startedOverlay = false
    private var changedDnd = false
    private var appliedKill = false
    private var lastFg: String? = null
    private var appliedPower = -1   // profile applied to the current game (-1 = none)
    private var awaySince = 0L      // since when the game is open but not on screen (0 = it is on screen)
    private var offSince = 0L       // since when the screen is off or locked during a game (0 = it is on)
    private var blindTicks = 0      // consecutive readings without any temperature during Diablo Mode
    private var lastNotifAt = 0L
    /** A game whose session was ended from the notification: not started again until it leaves the screen. */
    private var ended: String? = null
    @Volatile private var pendingEnd = false
    @Volatile private var pendingOverlay = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.gameMode = false
                Prefs.save()
                stopSelf()
                return START_NOT_STICKY
            }
            // Handled by the worker thread, which owns the game state
            ACTION_END -> pendingEnd = true
            ACTION_OVERLAY -> pendingOverlay = true
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(applicationContext)
        I18n.init(applicationContext)
        Diag.init(applicationContext)
        ServiceCompat.startForeground(
            this, NOTIF_ID, waitingNotification(null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        alive = true
        Thread {
            // Permission to know which app is on screen (granted with root)
            if (RootShell.open()) RootShell.run("appops set $packageName GET_USAGE_STATS allow")
            if (!GameMode.hasUsageAccess(applicationContext)) Diag.log("game", "no usage access: games cannot be detected")
            DiabloMode.recoverIfNeeded(applicationContext)
            recoverCpuIfNeeded()
            if (RootShell.open()) {   // the app was killed in the middle of a game
                restoreHz()
                GameApps.release(applicationContext)
                restoreDnd()
            }
            while (alive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Diag.log("game", "tick error: ${e.javaClass.simpleName}: ${e.message}")
                }
                // Short naps so that turning game mode off is noticed quickly
                val until = SystemClock.elapsedRealtime() + if (screenActive()) 2000 else 5000
                while (alive && !pendingEnd && !pendingOverlay && SystemClock.elapsedRealtime() < until) SystemClock.sleep(250)
            }
            // The service was stopped: undo everything here, on the thread that applied it
            try {
                current?.let { exitGame(it, notify = false, reason = "game mode turned off", code = "off") }
            } catch (_: Exception) {
            }
            GameModeState.reset()
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
        // The first time (service just started, maybe with the game already on screen) a long window
        // is read: otherwise a game opened before the service started would never be detected
        val window = if (lastFg == null) 12 * 3600_000L else 15_000L
        val events = usm.queryEvents(now - window, now)
        val e = UsageEvents.Event()
        var fg = lastFg
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) fg = e.packageName
        }
        lastFg = fg
        return fg
    }

    private var lastTopCheck = 0L
    private val TOP = Regex("""topResumedActivity=ActivityRecord\{\S+ u\d+ ([\w.]+)/""")

    /** The app really on top according to the system (needs root or Shizuku; null if unknown). */
    private fun topResumed(): String? =
        if (!RootShell.open()) null
        else RootShell.run("dumpsys activity activities | grep -m 1 topResumedActivity").firstOrNull()?.let { TOP.find(it)?.groupValues?.get(1) }

    /** Screen on and unlocked (otherwise the user is not playing). */
    private fun screenActive(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        return (pm?.isInteractive ?: true) && !(km?.isKeyguardLocked ?: false)
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        // With the screen off or locked, game mode is exited so the performance governor, Do Not
        // Disturb, etc. do not stay active in a pocket. A short lock (checking a message, the screen
        // timing out on a loading screen) keeps the session; with Diablo Mode it ends right away.
        if (!screenActive()) {
            current?.let {
                if (offSince == 0L) offSince = now
                if (DiabloMode.active || now - offSince > SCREEN_GRACE_MS) {
                    exitGame(it, reason = "screen off or locked", code = "screen")
                }
            }
            return
        }
        offSince = 0L
        // Diablo Mode that could not be undone (root was lost): restored first, before any profile is
        // applied, so a pinned CPU is never saved as the state to go back to
        if (!DiabloMode.active) DiabloMode.recoverIfNeeded(applicationContext)
        // "End session" from the notification
        if (pendingEnd) {
            pendingEnd = false
            current?.let {
                ended = it
                exitGame(it, reason = "ended from the notification", code = "manual")
            }
        }
        // "Floating monitor" from the notification
        if (pendingOverlay) {
            pendingOverlay = false
            toggleOverlay()
        }
        val cur = current
        var fg = foreground()
        // Usage events miss some changes (for example closing a floating window, or returning to a game
        // that never paused): the app really on top is checked with the system when it matters
        val top = when {
            cur != null && fg != cur -> topResumed()
            cur == null && (fg == null || fg !in Prefs.games) && now - lastTopCheck > 30_000 -> {
                lastTopCheck = now
                topResumed()?.takeIf { it in Prefs.games }
            }
            else -> null
        }
        if (top != null) {
            fg = top
            lastFg = top
        }
        // A game ended by hand starts a new session only after it leaves the screen
        if (ended != null && fg != ended) ended = null
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
            // Looking at Monitor itself (to check the game's stats) does not count as leaving the game
            if (fg == cur || fg == packageName) awaySince = 0L else if (awaySince == 0L) awaySince = now
            val away = awaySince != 0L && now - awaySince > (if (DiabloMode.active) 60_000L else 180_000L)
            if (closed || otherGame || away) {
                exitGame(cur, reason = when {
                    otherGame -> "another game opened: $fg"
                    away -> "game in the background for too long (on screen: $fg)"
                    running == false -> "game process ended"
                    else -> "another app on screen: $fg"
                }, code = when {
                    otherGame -> "other"
                    away -> "away"
                    else -> "closed"
                })
                if (otherGame) enterGame(fg!!)
            }
        } else if (fg != null && fg in Prefs.games && fg != ended) {
            enterGame(fg)
        }
        // Options changed on the Games page while playing: applied (or undone) right away
        current?.let { pkg ->
            if (appliedPower != Prefs.gamePower && RootShell.open()) {
                revertProfile()
                applyProfile()
                updateNotification(pkg)
            }
            if (appliedHz != Prefs.gameHz && RootShell.open()) {
                restoreHz()
                applyHz()
            }
            // Background apps: closed when the option is turned on mid-game, closed again if something
            // started them, let go if the option is turned off
            if (Prefs.gameKill && RootShell.open() && !RootShell.viaShizuku) {
                if (!appliedKill) {
                    GameApps.hold(applicationContext, pkg)
                    appliedKill = true
                } else {
                    GameApps.keepClosed(fg)
                }
            } else if (appliedKill) {
                GameApps.release(applicationContext)
                appliedKill = false
            }
            GameModeState.appsClosed = if (appliedKill) GameApps.heldCount() else 0
            if (Prefs.gameDnd && !changedDnd && RootShell.open()) applyDnd()
            else if (!Prefs.gameDnd && changedDnd && RootShell.open()) {
                restoreDnd()
                changedDnd = false
            }
            GameModeState.dnd = changedDnd
            if (Prefs.gameOverlay && !OverlayState.running && !startedOverlay) startOverlay()
            else if (!Prefs.gameOverlay && startedOverlay) {
                OverlayControl.stop(applicationContext)
                startedOverlay = false
            }
            GameModeState.overlay = OverlayState.running
        }
        if (current != null) record(inGame = fg == current)
        // Keep Diablo Mode: if something changed the frequencies or re-enabled thermal control, it is corrected
        if (DiabloMode.active) DiabloMode.enforce()
    }

    /** true if the game process is still alive; null if it cannot be checked (no root or Shizuku). */
    private fun isRunning(pkg: String): Boolean? =
        if (!RootShell.open()) null else RootShell.run("pidof ${Actions.q(pkg)}").any { it.isNotBlank() }

    private fun batteryIntent() = registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))

    private fun battery(): Int? {
        val i = batteryIntent() ?: return null
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (l >= 0 && sc > 0) l * 100 / sc else null
    }

    private fun pluggedIn(): Boolean = (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    private fun enterGame(pkg: String) {
        GameLog.add(applicationContext, "start $pkg (profile ${Prefs.gamePower}, ${Prefs.gameHz} Hz)")
        current = pkg
        GameModeState.reset()
        GameModeState.playing = pkg
        awaySince = 0L
        offSince = 0L
        blindTicks = 0
        sessionStart = System.currentTimeMillis()
        GameModeState.sessionStart = sessionStart
        fps.clear()
        tempMax = null
        battTempMax = null
        battStart = battery()
        chargingSeen = pluggedIn()
        throttleSec = 0
        onScreenSec = 0
        lastRecordAt = 0L
        warnedHot = false
        lastNotifAt = 0L
        if (!RootShell.open()) {
            // Nothing can be changed without root or Shizuku: the session is still recorded
            GameLog.add(applicationContext, "no root or Shizuku: profile, refresh rate and other options not applied")
            Diag.log("game", "game started without root or Shizuku: options not applied")
            updateNotification(pkg)
            return
        }
        applyProfile()
        applyHz()
        // Close the installed apps and keep them closed until the game ends
        if (Prefs.gameKill && !RootShell.viaShizuku) {
            GameApps.hold(applicationContext, pkg)
            appliedKill = true
        }
        if (Prefs.gameDnd) applyDnd()
        GameModeState.dnd = changedDnd
        if (Prefs.gameOverlay && !OverlayState.running) startOverlay()
        GameModeState.overlay = OverlayState.running || startedOverlay
        updateNotification(pkg)
    }

    /** Do Not Disturb, only if it was off. */
    private fun applyDnd() {
        val zen = RootShell.run("settings get global zen_mode").firstOrNull()?.trim()
        if (zen == "0") {
            // Remembered on disk first: if the app is killed mid-game it is turned off on the next start
            gamePrefs().edit().putBoolean("dnd", true).commit()
            RootShell.run("cmd notification set_dnd priority")
            changedDnd = true
        }
    }

    private fun startOverlay() {
        startedOverlay = try {
            OverlayControl.start(applicationContext)
        } catch (_: Exception) {
            RootShell.run("am start-foreground-service -n $packageName/.OverlayService")
            true
        }
        if (!startedOverlay) {
            GameLog.add(applicationContext, "floating monitor not shown: no permission to draw over other apps")
            Diag.log("overlay", "no permission to draw over other apps")
        }
    }

    /** Shows or hides the floating monitor from the notification. */
    private fun toggleOverlay() {
        if (OverlayState.running) {
            OverlayControl.stop(applicationContext)
            startedOverlay = false
        } else {
            startOverlay()
        }
        GameModeState.overlay = OverlayState.running || startedOverlay
        current?.let { updateNotification(it) }
    }

    /** Applies the chosen performance profile to the game that is open. */
    private fun applyProfile() {
        val power = Prefs.gamePower
        appliedPower = power
        GameModeState.profile = 0
        // With Shizuku the CPU cannot be changed (the profile selector is disabled): nothing to apply
        if (RootShell.viaShizuku) return
        // Diablo profile: everything at maximum. If not possible, Mejor rendimiento (Best performance) is used
        val diablo = power == 4 && DiabloMode.enable(applicationContext)
        if (power == 4 && !diablo) {
            GameModeState.diabloFailed = true
            GameLog.add(applicationContext, "Diablo Mode could not be turned on: Performance used instead")
            Diag.log("game", "Diablo Mode could not be turned on on this phone")
        }
        // Power mode (the full CPU state is saved to restore it on exit)
        val mode = if (diablo) null else when (power) {
            1, 4 -> CpuPower.Mode.PERFORMANCE
            2 -> CpuPower.Mode.BALANCED
            3 -> CpuPower.Mode.SAVER
            else -> null
        }
        GameModeState.profile = if (diablo) 4 else if (mode != null) power.takeIf { it != 4 } ?: 1 else 0
        if (mode != null) {
            val before = CpuPower.snapshot()
            if (CpuPower.isAvailable(mode, before.clusters)) {
                savedCpu = before
                // Remembered on disk: if Android kills the app mid-game, the CPU is reset on the next start
                gamePrefs().edit().putBoolean("cpuApplied", true).apply()
                CpuPower.apply(mode, before.clusters)
            } else {
                GameModeState.profile = 0
                Diag.log("game", "power mode $mode not available on this phone (${before.clusters.size} CPU clusters)")
            }
        }
    }

    private fun gamePrefs() = getSharedPreferences("game", Context.MODE_PRIVATE)

    // ---- Screen refresh rate while playing ----
    // A game that can't keep up with the screen (e.g. 80 FPS on a 120 Hz screen) alternates frames of
    // one and two refreshes, which feels like stutter. At a rate it can sustain, every frame lasts the same.
    private var appliedHz = 0

    /** Settings (namespace:key) that decide the refresh rate; only the ones the phone has are changed. */
    private val HZ_KEYS = listOf(
        "system:min_refresh_rate", "system:peak_refresh_rate",
        "system:user_refresh_rate", "system:miui_refresh_rate", "secure:miui_refresh_rate",
    )

    /** Turns Do Not Disturb off if game mode turned it on (also after the app was killed). */
    private fun restoreDnd() {
        val sp = gamePrefs()
        if (!sp.getBoolean("dnd", false)) return
        // Still the "priority" mode game mode set (1): otherwise the user changed it and it is left alone
        if (RootShell.run("settings get global zen_mode").firstOrNull()?.trim() == "1") {
            RootShell.run("cmd notification set_dnd off")
        }
        sp.edit().remove("dnd").apply()
    }

    private fun applyHz() {
        // Original rate of an earlier game still pending (it could not be restored): restored first,
        // so the game's rate is never saved as the "original"
        if (gamePrefs().contains("hz")) restoreHz()
        if (gamePrefs().contains("hz")) return
        val hz = Prefs.gameHz
        appliedHz = hz
        GameModeState.hz = 0
        if (hz <= 0) return
        val saved = HZ_KEYS.mapNotNull { k ->
            val ns = k.substringBefore(':'); val key = k.substringAfter(':')
            val v = RootShell.run("settings get $ns $key").firstOrNull()?.trim()
            if (v.isNullOrEmpty() || v == "null") null else "$k=$v"
        }
        if (saved.isEmpty()) {
            Diag.log("game", "refresh rate: none of the known settings exists on this phone")
            return
        }
        // Remembered on disk, so the original rate comes back even if the app is killed mid-game
        gamePrefs().edit().putString("hz", saved.joinToString(";")).apply()
        saved.forEach { e ->
            val k = e.substringBefore('=')
            val ns = k.substringBefore(':'); val key = k.substringAfter(':')
            val value = if (key == "min_refresh_rate" || key == "peak_refresh_rate") "$hz.0" else "$hz"
            RootShell.run("settings put $ns $key $value")
        }
        GameModeState.hz = hz
    }

    private fun restoreHz() {
        val sp = gamePrefs()
        val saved = sp.getString("hz", null)
        appliedHz = 0
        GameModeState.hz = 0
        if (saved.isNullOrEmpty()) return
        saved.split(';').filter { it.contains('=') }.forEach { e ->
            val k = e.substringBefore('=')
            val ns = k.substringBefore(':'); val key = k.substringAfter(':')
            RootShell.run("settings put $ns $key ${Actions.q(e.substringAfter('='))}")
        }
        sp.edit().remove("hz").apply()
    }

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

    private fun exitGame(pkg: String, notify: Boolean = true, reason: String = "", code: String = "") {
        GameLog.add(applicationContext, "end $pkg: $reason")
        current = null
        val profile = GameModeState.profile
        val hz = GameModeState.hz
        GameModeState.reset()
        revertProfile()
        if (RootShell.open()) {
            restoreHz()
            GameApps.release(applicationContext)
        }
        appliedKill = false
        if (changedDnd && RootShell.open()) {
            restoreDnd()
            changedDnd = false
        }
        // SurfaceFlinger keeps counting frames while TimeStats is on: off when nobody needs it
        if (!OverlayState.running) try { QuickStats.stopTimeStats() } catch (_: Exception) {}
        if (startedOverlay) {
            OverlayControl.stop(applicationContext)
            startedOverlay = false
        }
        // Session summary (only if it lasted at least one minute)
        val end = System.currentTimeMillis()
        if (Prefs.gameRecord && end - sessionStart >= 60_000) {
            val label = Apps.meta(pkg)?.label ?: pkg
            val s = GameSession(
                pkg, label, sessionStart, end, fps.toList(), tempMax, battStart, battery(),
                battTempMax = battTempMax, charging = chargingSeen || pluggedIn(), profile = profile, hz = hz,
                endReason = code, throttleSec = throttleSec, onScreenSec = onScreenSec,
            )
            GameSessions.add(applicationContext, s)
            notifySummary(s)
        }
        if (notify) updateNotification(null)
    }

    private fun record(inGame: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastRecordAt == 0L) 0 else ((now - lastRecordAt) / 1000).toInt().coerceIn(0, 10)
        lastRecordAt = now
        if (inGame) onScreenSec += dt
        if (pluggedIn()) chargingSeen = true
        // Android's own thermal status (all chip makers, Android 10+): "moderate" or worse means it is slowing down
        val hotSystem = if (Build.VERSION.SDK_INT >= 29) {
            (getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: 0) >= android.os.PowerManager.THERMAL_STATUS_MODERATE
        } else false
        if (hotSystem && inGame) throttleSec += dt
        GameModeState.throttling = hotSystem
        // FPS only when they are needed (session summary), the temperature always: it protects the phone
        val d = QuickStats.read(fps = Prefs.gameRecord, ctx = applicationContext)
        GameModeState.fps = d.fps
        GameModeState.cpuTemp = d.cpuTemp
        GameModeState.battTemp = d.battTemp
        // Session stats only count while the game is on screen
        if (inGame) {
            if (Prefs.gameRecord) d.fps?.let { fps.add(it) }
            d.cpuTemp?.let { tempMax = maxOf(tempMax ?: it, it) }
            d.battTemp?.let { battTempMax = maxOf(battTempMax ?: it, it) }
        }
        // Too hot while playing (not in Diablo Mode, which has its own cutoff): one warning per session
        if (!DiabloMode.active && !warnedHot && inGame &&
            ((d.battTemp ?: 0f) >= HOT_BATT || (d.cpuTemp ?: 0f) >= HOT_CPU)
        ) {
            warnedHot = true
            GameLog.add(applicationContext, "hot warning (CPU ${d.cpuTemp}, battery ${d.battTemp})")
            notifyHot(d)
        }
        // Diablo Mode safety cutoff: too hot, or the temperature cannot be read
        if (DiabloMode.active) {
            val hot = DiabloMode.overheated(d.cpuTemp, d.battTemp)
            // Without any temperature reading the cutoff cannot protect the phone: the mode is turned off
            blindTicks = if (d.cpuTemp == null && d.battTemp == null) blindTicks + 1 else 0
            if (hot || blindTicks >= 3) {
                DiabloMode.disable(applicationContext)
                GameModeState.diabloCut = true
                GameModeState.profile = 0
                GameLog.add(applicationContext, "Diablo Mode off: " + if (hot) "too hot (CPU ${d.cpuTemp}, battery ${d.battTemp})" else "no temperature reading")
                if (!hot) Diag.log("game", "Diablo Mode turned off: no temperature reading on this phone")
                notifyDiabloOff(if (hot) d else null)
                current?.let { updateNotification(it) }   // the notification no longer says "Diablo Mode"
            }
        }
        // The ongoing notification shows the time played, FPS and temperature (every 10 s)
        if (now - lastNotifAt >= 10_000) {
            lastNotifAt = now
            current?.let { updateNotification(it) }
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

    private fun notifyHot(d: QuickStats.Data) {
        channel()
        val temps = listOfNotNull(d.battTemp?.let { tr("batería %d °C", it.toInt()) }, d.cpuTemp?.let { tr("CPU %d °C", it.toInt()) }).joinToString(", ")
        val text = tr("El teléfono está muy caliente (%s). Si baja el rendimiento, prueba el perfil Eficiencia o descansa unos minutos.", temps)
        val n = NotificationCompat.Builder(this, SUMMARY_CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(tr("Temperatura alta"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(this).notify(3201, n) } catch (_: SecurityException) {}
    }

    // ---- Notifications ----
    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Modo juego"), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(SUMMARY_CHANNEL, tr("Resumen de partidas"), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openApp(session: Long = 0L) = PendingIntent.getActivity(
        this, if (session == 0L) 30 else 40 + (session / 1000 % 90).toInt(),
        Intent(this, MainActivity::class.java).putExtra("page", "games").apply { if (session != 0L) putExtra("session", session) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(code: Int, action: String) = PendingIntent.getService(
        this, code, Intent(this, GameModeService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun waitingNotification(pkg: String?): android.app.Notification {
        channel()
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fg)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (pkg == null) {
            return b.setContentTitle(tr("Modo juego activo"))
                .setContentText(tr("Esperando a que abras uno de tus juegos."))
                .addAction(0, tr("Desactivar"), action(31, ACTION_STOP))
                .build()
        }
        val s = GameModeState
        val title = tr("Jugando: %s", Apps.meta(pkg)?.label ?: pkg) + if (DiabloMode.active) " · " + tr("Modo Diablo") else ""
        val min = if (s.sessionStart > 0) ((System.currentTimeMillis() - s.sessionStart) / 60_000).toInt() else 0
        val text = listOfNotNull(
            tr("%d min", min),
            s.fps?.let { tr("%d FPS", it) },
            s.cpuTemp?.let { "CPU %.0f °C".format(it) },
            s.battTemp?.let { tr("batería %d °C", it.toInt()) },
        ).joinToString(" · ")
        return b.setContentTitle(title)
            .setContentText(text)
            .addAction(0, tr("Terminar partida"), action(32, ACTION_END))
            .addAction(0, if (OverlayState.running) tr("Ocultar monitor") else tr("Mostrar monitor"), action(33, ACTION_OVERLAY))
            .addAction(0, tr("Desactivar"), action(31, ACTION_STOP))
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
            // Opens this session's details
            .setContentIntent(openApp(s.start))
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
        /** A lock shorter than this keeps the session (outside Diablo Mode). */
        private const val SCREEN_GRACE_MS = 30_000L
        /** One warning per session at these temperatures (outside Diablo Mode). */
        private const val HOT_BATT = 45f
        private const val HOT_CPU = 90f
        const val ACTION_STOP = "com.kevxngg.monitorroot.STOP_GAME_MODE"
        const val ACTION_END = "com.kevxngg.monitorroot.END_GAME_SESSION"
        const val ACTION_OVERLAY = "com.kevxngg.monitorroot.TOGGLE_GAME_OVERLAY"
    }
}
