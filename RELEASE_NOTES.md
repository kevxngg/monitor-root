## What's new
- Diablo Mode gives smoother frame rates: the big CPU cores are now left to the game, and the other apps and services running in the foreground use the little cores while you play. Measured in Free Fire on a Helio G99: from about 57 to 80 FPS on average, and frames that took 25 ms or more dropped from about 30% to 6%.
- Diablo Mode no longer turns off FPSGO, MediaTek's frame pacing. With it on, the frame rate is higher and more even.
- New "Screen refresh rate" option in game mode. If a game can't reach the screen's frame rate (for example 80 FPS on a 120 Hz screen), frames alternate between one and two refreshes and it feels choppy; at 60 or 90 Hz every frame lasts the same. Measured in Free Fire on a Helio G99: at 60 Hz, 99% of frames were even. The original rate comes back when you leave the game.
- Floating monitor: FPS were no longer shown on Android 14 and later, or after a single failed read. They are now measured again and recover on their own.
- Floating monitor: the GPU usage on MediaTek is now averaged over several quick readings; before it often showed 0% while playing.
- Everything goes back to normal when you leave the game.
