# Community packages: design

Per-game dual-screen layouts, settings, device profiles and mods shared by the community, browsable per game in the
app (like upstream's Community Configs), hosted entirely on GitHub with no server of our own.

Repo: [`nono5822/GameNativeDuo-community`](https://github.com/nono5822/GameNativeDuo-community). Schemas, the
validator and the index builder live there; this file is the app-side view.

## Hosting

```
contributor ──PR──▶ GameNativeDuo-community (main)
                      │  CI on PR: validate (schema, hashes, sizes, paths, licence)
                      │  CI on merge: compile + sign the index, publish to GitHub Pages
                      ▼
           https://nono5822.github.io/GameNativeDuo-community/v1/...   ◀── app (ETag, cached)
           GitHub Releases (large mod binaries, sha256-pinned)          ◀── app (on install only)
```

- The app never calls the GitHub API (60 requests/h per IP unauthenticated). It reads static files from Pages (CDN).
- Every published JSON file has a detached ed25519 signature (`<file>.sig`, base64). The public key is pinned in the
  app (`VDgt6QeCefGECccRBbtFePnNoPLAmUhPlN0jyswkpoo=`, also in the community repo's `keys/index-public-key.txt`); a
  mirror or CDN cannot alter content. The private key is the community repo's `DUO_INDEX_SIGNING_KEY` secret; the
  maintainer keeps a copy offline. Rotating it needs an app update. Every payload file is checked against its sha256
  before use.
- Payloads are content-addressed (`v1/blobs/<sha256>`): immutable, cacheable forever, deduplicated.

## Formats

Authoring (in the repo): `games/<store>-<appId>/<package-id>/manifest.json` plus its files. Schema:
`schema/package.schema.json`. The `ui-layout.json` inside a layout package is exactly the file the layout editor saves
(`gamenative-duo/ui-layout`, see `engine-adapter-contract.md`).

Published (by CI), sharded so the app downloads only what it shows:

| File | Used for | Size |
|---|---|---|
| `v1/catalog.json` | Dual screen tab: every game with packages, counts, kinds | grows with games |
| `v1/games/<store>-<appId>.json` | A game's page: its packages with file URLs and hashes | a few KB |
| `v1/blobs/<sha256>` | Package files | as uploaded |
| `v1/public-key.txt` | Key check (the app pins its own copy) | 44 bytes |

Game keys: `steam-433340` = container id `STEAM_433340` lower-cased with `-`.

## Package kinds and trust

| Kind | Content | In the app |
|---|---|---|
| `ui-layout` | `ui-layout.json` | Data: one tap, may auto-apply |
| `duo-settings` | `duo-settings.json` (split mode, native touch, screen size) | Data: one tap |
| `device-profile` | `device-profile.json` (screens, calibration) | Data: one tap |
| `game-profile` | `profile.json` (configures the shared Unity adapter) | Data: one tap |
| `mod` | source + build description + an install map | Code: built from source by CI, signed, reviewed, explicit consent |

Wine runs as the app's own Android user, so code in a mod can read the app's data (including store logins: see
`upstream-keystore-credentials.md` for the hardening offered upstream). Data kinds may only contain `.json` and
images; code is reviewed by maintainers, hash-pinned, and installed only after the user sees what it writes.

### Code trust (devkit item 4)

- **Built from source.** A mod package holds source (`src/`, or a public repository at a full commit) and a `build`
  section; `tools/build_mods.py` builds it in a CI job with no secrets (pinned .NET SDK, Deterministic + PathMap,
  locked NuGet restore), and the signed index lists only those outputs (`builtFrom: "repo@commit"`). Validate builds
  every mod twice and compares. The only binaries a package may contain are pinned loader files (`loaders.json`:
  BepInEx 5.4.23.5, Lovely 0.10.0). Game assemblies come from reference sets (`refs.json`): stubs made by
  `duo stubs` (method bodies `throw null`, resources and static data zeroed; metadata kept), pinned by sha256. The
  Hollow Knight plugin builds byte-identical against stubs and against the real game files.
- **Review.** `review.yml` comments hints on every package PR (`tools/review_flags.py`: diff since the approved
  version, flagged lines for network, processes, native code, files outside the game, sign-ins, run-time loading,
  obfuscation, plus manifest changes that widen access), and an AI-assisted reading when the repository has an
  `ANTHROPIC_API_KEY` secret (`tools/ai_review.py`). A maintainer approves every code PR (CODEOWNERS).
- **Author keys.** `author.key` (SSH ed25519) signs `manifest.json` into `manifest.json.sig` (`duo pack --sign`,
  `ssh-keygen -Y`); `author.history` hands a package from an old key to a new one. CI checks the signature, the chain
  and, against the approved state, that a changed key was handed over. The index carries `authorKey`/`authorKeys`; the
  app pins the key at install (`AuthorKeyPins`) and shows "This mod changed owner" when an update comes from another.
- **Permissions.** `bridge` declares the features a mod uses (capabilities by name, `haptic`, commands); for built mods
  the app masks HELLO capabilities, drops other packets and refuses other commands (`DuoBridge.permissions`). Install
  targets: prefix files only under the user's AppData/Documents (never GameNative Duo's folder), DLL overrides only for
  DLLs installed at the game root; checked by CI and again by the installer.
- **Revocation.** `revoked.json` (maintainers) is published signed as `v1/revoked.json`; the app fetches it with an
  ETag, removes a withdrawn mod through its undo record and shows the reason once (`CommunityRevocations`).

## App side

Built (`duo/community/`): `CommunityPackages` (fetch with ETag, ed25519 + sha256 verification, disk cache in
`files/duo/community/`, apply/remove, applied versions in `applied/<containerId>.json`), `Ed25519` (verify-only, RFC 8032,
unit-tested against the RFC vectors and a published catalog), and the mod explorer (`duo/mods/ModExplorer.kt`,
`ModHub`, `ModHubState`), opened by the chevron of the Dual screen control next to Play. It lists bundled mods and
community packages together: code mods (or, for games without one, settings packages) are what the switch applies,
one at a time, switched through `DuoModInstaller`'s undo record; layouts and settings are add-ons on top. Layouts
and settings apply in one tap; a layout the user made is only replaced after asking; code mods need consent once
per version and author key (the consent screen shows the author key, whether the code was built from source, the
bridge features, and "This mod changed owner" when that applies); updates show when the index has a newer
`packageVersion`. Withdrawn versions never install. Ratings: `duo/docs/community-ratings.md`.

Still to build:

1. `CommunityPackageService`: fetch `games/<key>.json` (ETag, disk cache, signature check), download blobs (sha256).
2. Game page: "Dual-screen mods" sheet: filtered by device profile, adapter version and game build; sorted by rating
   or newest; cards with top/bottom screenshots, kind badge, author, version.
3. Apply: layouts through `UiLayoutStore` (user edits become their own copy; Reset returns to the package), settings
   through `DuoSettingsStore`, mods through a generalised `DuoModInstaller` (bundled mods become packages that ship in
   the APK: one code path, still works offline).
4. Dual screen tab from `catalog.json` instead of `DuoModCatalog`.
5. "Share layout" in the editor: pre-filled GitHub issue form, converted to a PR by a bot.
6. Ratings: one GitHub Discussion per package; CI folds reactions into the index nightly.

## Rules

- Element ids can change when a game updates; a package lists the game builds it was made for and the app warns on a
  mismatch. Rules that match nothing do nothing.
- A licence is required. Only mods whose licence allows redistribution; never game files; nothing touching DRM.
