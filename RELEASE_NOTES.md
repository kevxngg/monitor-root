## What's new
- New floating monitor: a panel with CPU, GPU, RAM and temperature gauges, graphs, network, storage and FPS. It has four layouts (full, compact, gamer and retro), resizes by dragging the bottom-right corner, and the top-right corner minimizes it to a bubble with the app logo that snaps to the edge of the screen.
- Works without root through Shizuku: when you open the app you choose "Connect with root" or "Connect with Shizuku" (you can change it in Settings). With Shizuku, Monitor reads processes, CPU, memory, network, storage, battery, temperatures and FPS, and can end apps. Anything that needs to change the system is marked "Root only".
- With Shizuku, battery, temperatures and storage are read through alternative paths when Android blocks the system files.

## Fixes
- If the root or Shizuku connection was lost, the app had no data until it was closed. It now reconnects on its own.
- Game mode stayed active with the screen locked (full performance in your pocket). It now turns off when the screen goes off and turns back on when you return to the game.
- With Shizuku, automatic updates failed; they now install correctly.
- The floating monitor could close while calculating storage, the bubble went off-screen when rotating, and layout or transparency changes were not applied until it was reopened.
- Root-only actions (system services, startup apps, process priority) no longer fail silently with Shizuku: they are marked.

## Note
- The first time you open this version you will see the screen to choose the connection. If you use root, choose "Connect with root" and you're done.
