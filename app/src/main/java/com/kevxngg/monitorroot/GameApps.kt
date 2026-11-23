package com.kevxngg.monitorroot

import android.content.Context
import android.os.SystemClock

/**
 * "Close background apps" in game mode (root): the installed apps are closed and kept from
 * starting again until you leave the game.
 *
 * For each app: background running is blocked (appops RUN_ANY_IN_BACKGROUND), it goes to the
 * "restricted" standby bucket (no jobs, alarms or syncs) and its processes are killed. It is not
 * force-stopped, so when the game ends everything goes back exactly as it was and its
 * notifications keep working.
 *
 * Never touched: system apps, the game, Monitor, the keyboard, the launcher, the phone and SMS apps,
 * apps with an active VPN or accessibility service, apps playing media, and the root and Shizuku managers.
 */
object GameApps {
    private const val KEY = "held"

    /** Apps the system or the user needs while playing, even if they were installed as user apps. */
    private val SYSTEM_PREFIXES = listOf(
        "android", "com.android.", "com.google.android.", "com.miui.", "com.xiaomi.", "com.mi.",
        "com.mediatek.", "com.qualcomm.", "com.qti.", "com.samsung.", "com.sec.", "com.oneplus.",
        "com.oplus.", "com.coloros.", "com.huawei.", "com.vivo.", "com.motorola.",
    )
    private val ALWAYS_KEEP = setOf(
        "me.weishu.kernelsu", "com.topjohnwu.magisk", "me.bmax.apatch", "moe.shizuku.privileged.api",
        "com.rifsxd.ksunext", "io.github.vvb2060.magisk",
    )
    private val SPECIAL = Regex("""permission=android\.permission\.BIND_(VPN_SERVICE|ACCESSIBILITY_SERVICE|INPUT_METHOD|DEVICE_ADMIN|COMPANION_DEVICE_SERVICE)""")
    private val BUCKETS = mapOf(10 to "active", 20 to "working_set", 30 to "frequent", 40 to "rare", 45 to "restricted")

    @Volatile private var held: Set<String> = emptySet()
    /** Phone apps with an icon (camera, calendar, gallery...): only closed, their settings are not changed. */
    @Volatile private var closed: Set<String> = emptySet()
    /** How many times each phone app came back after being closed. */
    private val comebacks = HashMap<String, Int>()
    /** Phone apps among [held]; the ones the system keeps restarting stop being closed (they stay blocked). */
    @Volatile private var phoneHeld: Set<String> = emptySet()
    @Volatile private var giveUp: Set<String> = emptySet()
    /** Phone apps that must never be closed even if they have an icon (the system security and game tools, calls). */
    // (Closing the clock does not cancel alarms: they stay registered with the system.)
    private val SYSTEM_KEEP = listOf("securitycenter", "incallui", "systemui")
    private var lastCheck = 0L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("game", Context.MODE_PRIVATE)

    /** Packages that must keep running right now (besides the system and [game]). */
    private fun protectedNow(ctx: Context, game: String): Set<String> {
        val keep = HashSet(ALWAYS_KEEP)
        keep += ctx.packageName
        keep += game
        fun pkgOf(component: String?) = component?.trim()?.substringBefore('/')?.takeIf { it.isNotEmpty() && it != "null" }
        RootShell.run("settings get secure default_input_method").firstOrNull().let(::pkgOf)?.let { keep += it }
        RootShell.run("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lastOrNull().let(::pkgOf)?.let { keep += it }
        RootShell.run("settings get secure enabled_accessibility_services").firstOrNull()?.split(':')
            ?.mapNotNull(::pkgOf)?.let { keep += it }
        RootShell.run("settings get secure always_on_vpn_app").firstOrNull().let(::pkgOf)?.let { keep += it }
        for (role in listOf("DIALER", "SMS", "ASSISTANT", "HOME")) {
            RootShell.run("cmd role get-role-holders android.app.role.$role").forEach { l -> pkgOf(l)?.let { keep += it } }
        }
        // Running services the phone depends on (VPN, accessibility, keyboard, device admin, watches)
        var last: String? = null
        RootShell.run("dumpsys activity services").forEach { l ->
            val t = l.trim()
            if (t.startsWith("packageName=")) last = t.substringAfter('=')
            else if (SPECIAL.containsMatchIn(t)) last?.let { keep += it }
        }
        // Apps playing media (music while you play keeps working)
        last = null
        RootShell.run("dumpsys media_session").forEach { l ->
            val t = l.trim()
            if (t.startsWith("package=")) last = t.substringAfter('=')
            else if (t.startsWith("state=PlaybackState {state=3")) last?.let { keep += it }
        }
        return keep
    }

    /** Closes the installed apps and keeps them closed until [release]. */
    @Synchronized
    fun hold(ctx: Context, game: String) {
        if (held.isNotEmpty() || !RootShell.open() || RootShell.viaShizuku) return
        // Apps of an earlier game still blocked (could not be released): released first, so their
        // blocked state is never saved as the "original"
        if (prefs(ctx).contains(KEY)) release(ctx)
        if (prefs(ctx).contains(KEY)) return
        val keep = protectedNow(ctx, game)
        val installed = RootShell.run("pm list packages -3").mapNotNull { it.trim().removePrefix("package:").takeIf { p -> p.isNotEmpty() } }
        val userApps = installed.filter { p -> p !in keep && SYSTEM_PREFIXES.none { p == it || p.startsWith(it) } }
        // The phone's own apps with an icon (camera, calendar, gallery, weather, Play Store...).
        // Many are restarted by the system right away, so they are held like your apps (blocked in the
        // background and put back as they were afterwards). Never the ones that share the system's
        // identity (Settings and similar: blocking them would block the system itself), nor the clock
        // (its alarms must ring): those are only closed.
        val uids = RootShell.run("cmd package list packages -U").mapNotNull { l ->
            val p = l.substringAfter("package:").substringBefore(' ').trim()
            val uid = l.substringAfter("uid:", "").trim().substringBefore(',').toIntOrNull() ?: return@mapNotNull null
            p to uid
        }.toMap()
        val phoneApps = RootShell.run("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER")
            .map { it.trim() }.filter { it.contains('/') && !it.contains('=') && !it.contains(' ') }
            .map { it.substringBefore('/') }.toSet()
            .filter { p -> p !in keep && p !in userApps && SYSTEM_KEEP.none { p.contains(it, ignoreCase = true) } }
        val onlyClose = phoneApps.filter { p -> (uids[p] ?: 0) < 10_000 || p.contains("clock", true) || p.contains("alarm", true) }
        closed = onlyClose.toSet()
        val pkgs = userApps + (phoneApps - onlyClose.toSet())
        phoneHeld = (phoneApps - onlyClose.toSet()).toSet()
        giveUp = emptySet()
        if (closed.isNotEmpty()) RootShell.run(closed.joinToString("\n") { "am kill ${Actions.q(it)}" })
        if (pkgs.isEmpty()) return
        // Current state of each app, to put it back exactly as it was
        val state = RootShell.run(pkgs.joinToString("\n") { p ->
            val q = Actions.q(p)
            "echo \"$p|${'$'}(cmd activity get-standby-bucket $q 2>/dev/null)|${'$'}(cmd appops get $q RUN_ANY_IN_BACKGROUND 2>/dev/null | head -1)\""
        }).mapNotNull { l ->
            val f = l.split('|')
            if (f.size < 3 || f[0] !in pkgs) return@mapNotNull null
            val bucket = f[1].trim().toIntOrNull() ?: 10
            val mode = Regex("""RUN_ANY_IN_BACKGROUND: (\w+)""").find(f[2])?.groupValues?.get(1) ?: "default"
            "${f[0]}|$bucket|$mode"
        }
        if (state.isEmpty()) return
        // Saved before changing anything: if the app is killed mid-game, it is undone on the next start
        prefs(ctx).edit().putString(KEY, state.joinToString(";")).apply()
        held = state.map { it.substringBefore('|') }.toSet()
        RootShell.run(held.joinToString("\n") { p ->
            val q = Actions.q(p)
            "cmd appops set $q RUN_ANY_IN_BACKGROUND ignore; cmd activity set-standby-bucket $q restricted; am kill $q"
        })
        // A first check a few seconds later ends whatever survived "am kill"
        lastCheck = SystemClock.elapsedRealtime() - 7_000
    }

    /**
     * Every 10 s while playing: an app the system started anyway (a push message, for example)
     * is closed again. The app on screen [onScreen] is never touched (you may have opened it on purpose).
     */
    fun keepClosed(onScreen: String?) {
        val pkgs = held + closed
        if (pkgs.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheck < 10_000) return
        lastCheck = now
        // pid -> app (sub-processes such as "app:service" count as the app)
        val procs = RootShell.run("ps -A -o PID,NAME").mapNotNull { l ->
            val f = l.trim().split(Regex("\\s+"), limit = 2)
            val pid = f.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            pid to (f.getOrNull(1)?.substringBefore(':') ?: return@mapNotNull null)
        }
        val running = procs.map { it.second }.toSet()
        // A phone app that keeps coming back is being restarted by the system on purpose (for example
        // the camera kept ready to open fast): closing it over and over would only waste CPU
        (closed + (phoneHeld - giveUp)).filter { it in running }.forEach { p ->
            val n = (comebacks[p] ?: 0) + 1
            comebacks[p] = n
            if (n >= 3) {
                closed = closed - p
                giveUp = giveUp + p
            }
        }
        // Phone apps: closed the normal way. Your apps: "am kill" skips a process that is handling a
        // message or broadcast, and some stayed alive for minutes, so their processes are ended directly
        // (as Android does when it needs memory); they are already restricted and won't come back.
        val script = closed.filter { it in running && it != onScreen }.map { "am kill ${Actions.q(it)}" } +
            procs.filter { it.second in held && it.second !in giveUp && it.second != onScreen }.map { "kill -9 ${it.first}" }
        if (script.isNotEmpty()) RootShell.run(script.joinToString("\n"))
    }

    /** Puts every app back as it was before the game. */
    @Synchronized
    fun release(ctx: Context) {
        val saved = prefs(ctx).getString(KEY, null)
        // Without root nothing can be released: everything is kept to try again later
        if (!saved.isNullOrEmpty() && !RootShell.open()) return
        held = emptySet()
        closed = emptySet()
        comebacks.clear()
        phoneHeld = emptySet()
        giveUp = emptySet()
        if (saved.isNullOrEmpty()) return
        val script = saved.split(';').mapNotNull { e ->
            val f = e.split('|')
            if (f.size < 3) return@mapNotNull null
            val q = Actions.q(f[0])
            val mode = f[2].takeIf { it in setOf("allow", "ignore", "deny", "default", "foreground") } ?: "default"
            // Phone apps that the system keeps "exempt" have no bucket to put back
            val bucket = BUCKETS[f[1].toIntOrNull()]?.let { "; cmd activity set-standby-bucket $q $it" } ?: ""
            "cmd appops set $q RUN_ANY_IN_BACKGROUND $mode$bucket"
        }
        if (script.isNotEmpty()) RootShell.run(script.joinToString("\n"))
        prefs(ctx).edit().remove(KEY).apply()
    }
}
