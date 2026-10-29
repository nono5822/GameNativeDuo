# Task 06: choose the device; mod versions per dual-screen device

Owner: Opus · Thor needed: yes (Thor only; others simulated) · Depends on: —

Read RULES.md, `DeviceProfile`, `ScreenCalibration`. 1) First start (and Duo settings): pick the device (AYN Thor,
AYANEO Pocket DS, Retroid Dual Screen, Other/custom) with auto-detection preselected and a preview of which screen
is top/bottom; swap-screens option. 2) Mods, layouts and companions can ship per-device variants (catalog +
community schema field `devices`, fallback to a generic variant with automatic scaling); the explorer shows the
variant for this device and "made for <device>" badges. Only Thor variants need real content now; the feature must
exist and be tested with a simulated second profile (unit tests + one run with a forced profile).
