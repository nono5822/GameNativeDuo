# GameNative Duo for VS Code

A thin skin over `duo` (duo/tools/duo): the same editor and developer tabs as in the browser, in a VS Code panel.

- **GameNative Duo: Start the simulator**: `duo sim` in a terminal.
- **Open editor and dev tools**: the simulator's (or a device's) page: Layout, Inspector, Bridge, Logs, Console, Check.
- **Build / Check / Test / Pack**: `duo build`, `duo check` (failures become problems on the element's id in the open
  file), `duo test <open script>`, `duo pack`. Tasks of type `duo` for `tasks.json`.
- JSON validation for `ui-layout.json`, community `manifest.json` and `profile.json` from the community repo's schemas.

Settings: `gamenativeDuo.duoPath`, and `gamenativeDuo.deviceUrl` + `deviceCode` for a device in developer mode.
Install from source: `npx @vscode/vsce package` here, then "Install from VSIX".
