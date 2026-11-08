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
- [x] K1 Source packages: `build` in the package schema (folder or pinned repo + commit, project, engine); `loaders.json`
      (BepInEx 5.4.23.5, Lovely 0.10.0, per-file sha256); validator refuses other binaries in mod packages; tests
- [x] K2 `duo stubs` (duo tool, System.Reflection.Metadata): reference-only assemblies; `refs.json` format; HK set generated locally
- [x] K3 CI builder `tools/build_mods.py`: deterministic dotnet build (pinned SDK, PathMap), refs fetched and
      checked by sha256, outputs hashed; publish builds and lists only built outputs; validate builds twice and compares
- [x] K4 Review flags `tools/review_flags.py` (network, process, P/Invoke, file access, reflection, obfuscation) as a PR
      comment; optional AI review step (secret-gated)
- [x] K5 Author keys: `manifest.json.sig` (SSHSIG), key chain with rotation endorsements, checked against main's chain;
      index publishes `authorKey`/`authorKeys`; `duo keygen`, `duo pack --sign`
- [x] K6 Permissions: `bridge` declaration (capabilities, commands) in the schema; install target path rules
- [x] K7 Revocation: `revoked.json` (maintainer) -> signed `v1/revoked.json`; verify covers it
- [x] K8 Hollow Knight plugin as a source package; built with local stubs; site built and verified locally

### App
- [x] A1 Package model: author keys, review status, bridge permissions, built-from-source; parser tests
- [x] A2 Author key pinning at install; an update signed by another key needs consent again ("changed owner"); tests
- [ ] A3 Installer path hardening; bridge masks undeclared capabilities and drops their packets for community mods; tests
- [ ] A4 Consent screen: author and key, review status, what it writes, bridge features; changed-owner variant
- [ ] A5 Revocation: fetch and verify `revoked.json` (ETag) with the catalog; revoked mod turned off through the undo
      record, reason in the status; tests
- [ ] A6 Docs (`community-packages.md`, devkit-platform status, CLAUDE.md), Keystore upstream proposal

## Log
- K1 (community `32f5388`): `build` in the package schema (engine `dotnet` | `scripts`; source `src/` or a repo at a full
  commit; `refs`, `properties` with `{refs:<id>}`, `outputs` -> package paths). `loaders.json` pins BepInEx 5.4.23.5
  (14 files) and Lovely 0.10.0 per file, hashed from the copies the app bundles (official releases, unmodified).
  Binaries (extension or MZ/ELF/Mach-O/wasm/zip magic) are refused in files, release assets and `src/` unless pinned.
  NuGet needs `packages.lock.json`. `tools/test_code_trust.py` (12 tests) runs in validate.yml.
- K2: `duo stubs DIR --out SET.zip [--only ...]` patches assemblies in place with System.Reflection.Metadata (no new
  dependency, AOT-safe): every method body -> `ldnull; throw`, embedded resources and static data blobs zeroed, names
  and signatures kept (private too: publicized builds and Harmony targets need them). Deterministic zip (same sha256
  twice). Hollow Knight's whole Managed folder (134 assemblies, 36 MB -> 8.3 MB zip) and BepInEx (BepInEx.dll,
  0Harmony.dll) stubbed into the scratchpad: the Hollow Knight plugin built against the stubs is **byte-identical** to
  the build against the real game files (both DLLs). Stubs are not committed anywhere.
- K3 (community `0944622`): `tools/build_mods.py` (src/ or `git fetch --depth 1` of the named commit, checked; reference
  sets by sha256 from refs.json or `--refs-dir`; `dotnet build` with global.json pinning SDK 10.0.112 (rollForward
  disable), Deterministic, ContinuousIntegrationBuild, PathMap of the work folder, RestoreLockedMode). publish.yml: a
  `build-mods` job with a read-only token and no secrets hands `built.json` + files over as an artifact; the signing job
  only hashes them (`duo_index.py build --built`, which refuses a mod not built for its version and adds `builtFrom`).
  validate.yml builds every mod twice in different folders and compares. Tests: a scripts mod, a dotnet mod built
  reproducibly in two folders, a missing output.
- K4 (community `6a279ec`): `tools/review_flags.py` (base and head trees as data; per changed mod: diff since the
  approved version, src/ or repo old...new commit with a compare link; flagged added lines in 7 categories; manifest
  changes that widen access; Duo SDK copies exempt from the network flag) posted by `review.yml` on
  pull_request_target, which never runs pull request code. `tools/ai_review.py` (anthropic SDK, claude-opus-5-5,
  adaptive thinking, effort high, streaming, `fallbacks: "default"`; injection-aware system prompt; oversize diffs get
  a note, never truncation) runs only with the ANTHROPIC_API_KEY secret. **ai_review.py is syntax-checked only**: the
  anthropic package is not installed here. Tests: flag categories (incl. no false positive on `"Format:\n"`), a
  changed package, SDK copies.
- K5 (community `2ae85b0`, app this commit): `author` in the package schema (`key`: `ssh-ed25519 AAAAC3...`, `history`
  of `{key, handover}`), `manifest.json.sig` (SSHSIG, namespace `gamenative-duo-package`), handovers in namespace
  `gamenative-duo-key-handover` over `<next key>\n`. Validator: code mods need a key; signature; chain; with
  `--base-ref` (PR base, or the previous commit on main) a changed key must be handed over by the approved one, history
  only extends, a key cannot be removed. Index: `authorKey`, `authorKeys` (no signatures). `duo_index.py sign |
  handover`; `duo keygen`, `duo pack --sign [--key]` (pack also verifies). 6 validator tests with real ssh-keygen keys
  (incl. a forged handover); duo test; duo-signed packages verify in the validator and the other way round.
- K6 (community `752f925`): `bridge` (capabilities by name incl. `haptic`; commands with `.*` prefixes) in the schema,
  required with `build` (as is `author`). `check_install`: prefixFiles only under `users/xuser/AppData/` or
  `.../Documents/`, never `AppData/Roaming/GameNativeDuo/`; gameFiles may not write the app's own files; a DLL override
  must name a DLL the package installs at the game root (or `winhttp` via shared `bepinex`). Targets resolve like
  `CommunityModInstaller.plan`. 3 tests.
- K7 (community `aab63b6`): `revoked.json` (CODEOWNERS; `{id, versions | ["*"], reason, date}`) checked by validate,
  published signed as `v1/revoked.json`; index entries of revoked versions carry `revoked: <reason>`. 2 tests.
- K8 (community `7eb82f8`; app `cc6ea25fe` adds NuGet lock files to the two
  Unity plugin projects): `gamenative-duo.hollow-knight-dual-screen` 0.4.0 builds the HK plugin from
  `nono5822/GameNativeDuo@cc6ea25fe` against `hollow-knight-1.5.12620` and `bepinex-5.4.23.5` (refs.json: sha256 only,
  **no url: stubs not hosted**). `build_mods.py --repo-mirror URL=PATH` (unpushed commits); repository sources build
  in their checkout. End to end in a throwaway clone signed with a throwaway key: validate OK, built twice
  reproducibly (`754102d7...`), index built and verified, entry has `builtFrom`, `authorKey(s)`, `bridge`, the built
  DLL as a blob; publishing without built outputs is refused.
  **Before merging the community branch**: (1) sign the HK package with the maintainer's author key (`duo keygen`, then
  `duo pack --sign games/steam-367520/gamenative-duo.hollow-knight-dual-screen`; validation fails until then);
  (2) decide on hosting the two stub sets (a release asset, `url` in refs.json), or CI cannot build it; (3) push
  `cc6ea25fe` (or the merged equivalent) to GitHub, or point the manifest at the commit that lands.
- A1: `CommunityPackage` gains `authorKey`, `authorKeys` (ed25519 only), `builtFrom`, `bridge` (`BridgePermissions`:
  names -> HELLO capability mask, `haptic`, command patterns), `revoked`. `CommunityModInstaller.plan` refuses a revoked
  version. Tests in `CommunityTrustTest`.
- A2: `AuthorKeyPins` (`files/duo/community/author-keys.json`, per device): FIRST / SAME / HANDED_OVER (pinned key
  earlier in the CI-checked chain) / CHANGED_OWNER (another key or none); pinned after a successful install. The
  consent key now includes the author key's fingerprint, so a version republished under another key asks again;
  `ModHubState.Consent` carries the owner check for the dialog (A4).
