# Devkit item 4: code trust

User asked (2026-11-08) for item 4 of `duo/docs/devkit-platform.md` ("Code trust"), the Thor being unavailable
(charging): everything that can be built and tested on the PC. Every checklist box is one commit; a new session reads
this file and starts at the first unchecked box.

- App: worktree `~/dev/GameNativeDuo-uniform`, branch `duo/code-trust` (from `duo/devkit-sdk`, unmerged). Nothing pushed.
- Community repo: worktree `~/dev/GameNativeDuo-community-profiles`, branch `devkit/code-trust` (from
  `devkit/game-profiles`). Nothing pushed. That repo's main checkout has someone's uncommitted work: untouched.
- The main checkout (`~/dev/GameNativeDuo`) holds uncommitted hotkeys work: never commit, reset or copy it.

## Decisions

- **Author keys are SSH ed25519 keys**, signed and verified with `ssh-keygen -Y sign/verify` (namespace
  `gamenative-duo-package`). Authors can reuse a key they have (GitHub lists them at `github.com/<user>.keys`); no
  crypto code of ours. The validator verifies in CI; the app only compares key strings from the signed index (the
  index key stays the root of trust).
- **Source from a folder or a pinned commit**: a code mod package holds `src/` or names a git repo and a full commit
  hash; CI builds it, and the index lists only CI-built output hashes. Binaries in a package are refused unless they
  are a pinned loader file (`loaders.json`).
- **Game references are stubs** made by `duo stubs` (Mono.Cecil, from the BepInEx we bundle): public API, no method
  bodies, no resources. `refs.json` pins a stub archive by sha256. Publishing stubs derived from a game's DLLs is the
  maintainer's call: the tool and the format are built, Hollow Knight's set is generated locally, **nothing is
  committed or uploaded**.
- **Keystore hardening** of store credentials touches upstream code: written as an upstream proposal only.

## Checklist

### Community repo
- [ ] K1 Source packages: `build` in the package schema (folder or pinned repo + commit, project, engine); `loaders.json`
      (BepInEx 5.4.23.5, Lovely 0.10.0, per-file sha256); validator refuses other binaries in mod packages; tests
- [ ] K2 `duo stubs` (duo tool, Cecil): reference-only assemblies; `refs.json` format; HK set generated locally
- [ ] K3 CI builder `tools/build_mods.py`: deterministic dotnet build (pinned SDK, PathMap), refs fetched and
      checked by sha256, outputs hashed; publish builds and lists only built outputs; validate builds twice and compares
- [ ] K4 Review flags `tools/review_flags.py` (network, process, P/Invoke, file access, reflection, obfuscation) as a PR
      comment; optional AI review step (secret-gated)
- [ ] K5 Author keys: `manifest.json.sig` (SSHSIG), key chain with rotation endorsements, checked against main's chain;
      index publishes `authorKey`/`authorKeys`; `duo keygen`, `duo pack --sign`
- [ ] K6 Permissions: `bridge` declaration (capabilities, commands) in the schema; install target path rules
- [ ] K7 Revocation: `revoked.json` (maintainer) -> signed `v1/revoked.json`; verify covers it
- [ ] K8 Hollow Knight plugin as a source package; built with local stubs; site built and verified locally

### App
- [ ] A1 Package model: author keys, review status, bridge permissions, built-from-source; parser tests
- [ ] A2 Author key pinning at install; an update signed by another key needs consent again ("changed owner"); tests
- [ ] A3 Installer path hardening; bridge masks undeclared capabilities and drops their packets for community mods; tests
- [ ] A4 Consent screen: author and key, review status, what it writes, bridge features; changed-owner variant
- [ ] A5 Revocation: fetch and verify `revoked.json` (ETag) with the catalog; revoked mod turned off through the undo
      record, reason in the status; tests
- [ ] A6 Docs (`community-packages.md`, devkit-platform status, CLAUDE.md), Keystore upstream proposal

## Log
