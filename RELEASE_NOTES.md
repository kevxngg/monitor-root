## Improvements
- Game mode shows what is really applied while you play (profile, refresh rate, Do Not Disturb, apps closed, floating monitor), with time played, FPS and temperature.
- The game mode notification shows time, FPS and temperature, and can end the session or show/hide the floating monitor.
- Richer session summaries: 1 % low FPS, stability, CPU and battery max temperature, time the phone slowed down because of heat, battery per hour, profile used and how the session ended. Tap the summary notification to open its details; long-press a session to delete it.
- Floating monitor: FPS graph that adapts to your screen's refresh rate, the lowest recent FPS, numbers that no longer jump around, network graph that follows your connection, temperature in red when it is really hot, and tidier layouts in all four styles.
- One warning per session when the phone gets very hot.
- New diagnostics report (Settings and Games): records which features do not work on your phone. It stays on the phone until you share it or attach it to a GitHub issue.

## Fixes
- More accurate CPU temperature: sensors that only look like temperatures (battery charge, power limits) are no longer mixed in, and a single faulty sensor is ignored.
- Options changed while playing (close background apps, Do Not Disturb, floating monitor) now apply right away.
- Briefly locking the screen no longer ends the game session.
- Battery used is no longer shown as negative when playing while charging.
- Uninstalled games are no longer counted in your list.
- Game mode now says when something is missing (root, usage access, overlay permission, notifications) and when Diablo Mode could not be used or was turned off to protect the phone.
- The floating monitor shows "Connecting…" instead of looking live when no readings arrive.
