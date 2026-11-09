# Task 07: widgets on the top screen; battery-aware bottom screen

Owner: Opus · Thor needed: yes · Depends on: —

Read RULES.md and `duo/ui/WidgetLayer.kt`. 1) Widgets (FPS, CPU, GPU, temperature, battery, clock, + new: frame
time, session time) can be placed on the top screen too, never covering the game's own HUD by default, at 0 cost
when none are placed. 2) Power: per game, bottom screen 60/120 Hz, dim, and blank-when-idle (no change for N s,
wake on touch or change), plus a global "battery saver" preset. All options in the layout editor and in the game's
GameNative Duo settings tab. Measure battery draw (`dumpsys battery` over 10 min) before/after and report.
