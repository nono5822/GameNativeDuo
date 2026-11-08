// GameNative Duo for VS Code: a thin skin over the duo program (duo/docs/devkit-platform.md, "VS Code extension").
// Everything real happens in duo; this file only starts it, shows its UI and turns `duo check` into problems.
"use strict";
const vscode = require("vscode");
const cp = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

let output, problems, simTerminal;

function config() {
  return vscode.workspace.getConfiguration("gamenativeDuo");
}

/** The server to talk to: the device from the settings, else the last `duo sim` (its session file). */
function target() {
  const url = config().get("deviceUrl"), code = config().get("deviceCode");
  if (url && code) return { url, code };
  try {
    const s = JSON.parse(fs.readFileSync(path.join(os.tmpdir(), "gamenative-duo-sim.json"), "utf8"));
    return { url: s.url, code: s.code };
  } catch (e) {
    return null;
  }
}

function env() {
  const t = target();
  return t ? { ...process.env, DUO_URL: t.url, DUO_CODE: t.code } : process.env;
}

function folder() {
  const doc = vscode.window.activeTextEditor && vscode.window.activeTextEditor.document;
  if (doc && doc.uri.scheme === "file") return path.dirname(doc.uri.fsPath);
  const ws = vscode.workspace.workspaceFolders;
  return ws && ws.length ? ws[0].uri.fsPath : process.cwd();
}

/** Runs duo with args, output in the GameNative Duo channel; resolves with exit code and stdout. */
function duo(args, cwd) {
  return new Promise((resolve) => {
    output.appendLine("$ duo " + args.join(" "));
    const p = cp.spawn(config().get("duoPath"), args, { cwd: cwd || folder(), env: env() });
    let out = "";
    p.stdout.on("data", (d) => { out += d; output.append(String(d)); });
    p.stderr.on("data", (d) => output.append(String(d)));
    p.on("error", (e) => { output.appendLine("duo: " + e.message + " (set gamenativeDuo.duoPath)"); resolve({ code: -1, out }); });
    p.on("close", (code) => resolve({ code, out }));
  });
}

function openEditor() {
  const t = target();
  if (!t) {
    vscode.window.showWarningMessage("No simulator running and no device set. Start one with 'GameNative Duo: Start the simulator'.");
    return;
  }
  const panel = vscode.window.createWebviewPanel("gamenativeDuo", "GameNative Duo", vscode.ViewColumn.Beside, { enableScripts: true });
  const src = `${t.url}/?k=${encodeURIComponent(t.code)}`;
  // The page is the same one the browser shows, served by duo or the device; the webview only frames it.
  panel.webview.html = `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src ${t.url}; style-src 'unsafe-inline';">
<style>html,body,iframe{margin:0;padding:0;border:0;width:100%;height:100%;background:#0E1120}</style></head>
<body><iframe src="${src}"></iframe></body></html>`;
}

function startSim() {
  if (simTerminal && simTerminal.exitStatus === undefined) { simTerminal.show(); return; }
  simTerminal = vscode.window.createTerminal({ name: "duo sim", cwd: folder() });
  simTerminal.sendText(`${config().get("duoPath")} sim`);
  simTerminal.show();
}

/** `duo check --json`: failures become problems on the element's id in the open manifest/profile (or the folder). */
async function check() {
  const { code, out } = await duo(["check", "--json"]);
  problems.clear();
  let report;
  try { report = JSON.parse(out); } catch (e) { vscode.window.showErrorMessage("duo check did not give a report; see the GameNative Duo output."); return; }
  const doc = vscode.window.activeTextEditor && vscode.window.activeTextEditor.document;
  const uri = doc ? doc.uri : vscode.Uri.file(path.join(folder(), "manifest.json"));
  const text = doc ? doc.getText() : "";
  const diags = (report.results || []).filter((r) => !r.passed).map((r) => {
    const at = text.indexOf(`"${r.id}"`);
    const pos = at >= 0 && doc ? doc.positionAt(at) : new vscode.Position(0, 0);
    const d = new vscode.Diagnostic(new vscode.Range(pos, pos.translate(0, r.id.length + 2)),
      `${r.name}: ${r.feature} does not work as declared (${r.detail || "failed"})`, vscode.DiagnosticSeverity.Error);
    d.source = "duo check";
    return d;
  });
  problems.set(uri, diags);
  vscode.window.showInformationMessage(code === 0 ? "duo check: every declared feature works." : `duo check: ${diags.length} problem(s).`);
}

function activate(context) {
  output = vscode.window.createOutputChannel("GameNative Duo");
  problems = vscode.languages.createDiagnosticCollection("gamenative-duo");
  const reg = (name, fn) => context.subscriptions.push(vscode.commands.registerCommand(name, fn));
  reg("gamenativeDuo.openEditor", openEditor);
  reg("gamenativeDuo.startSim", startSim);
  reg("gamenativeDuo.build", () => duo(["build", "."]));
  reg("gamenativeDuo.check", check);
  reg("gamenativeDuo.pack", () => duo(["pack", "."]));
  reg("gamenativeDuo.test", () => {
    const doc = vscode.window.activeTextEditor && vscode.window.activeTextEditor.document;
    if (doc) duo(["test", doc.uri.fsPath]);
  });
  context.subscriptions.push(vscode.tasks.registerTaskProvider("duo", {
    provideTasks: () => ["build .", "check", "pack ."].map((c) =>
      new vscode.Task({ type: "duo", command: c }, vscode.TaskScope.Workspace, "duo " + c, "duo",
        new vscode.ShellExecution(`${config().get("duoPath")} ${c}`, { env: env() }))),
    resolveTask: (task) => new vscode.Task(task.definition, vscode.TaskScope.Workspace, "duo " + task.definition.command, "duo",
      new vscode.ShellExecution(`${config().get("duoPath")} ${task.definition.command}`, { env: env() })),
  }));
  context.subscriptions.push(output, problems);
}

function deactivate() {}

module.exports = { activate, deactivate };
