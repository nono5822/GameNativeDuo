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
| `mod` | DLLs/scripts + an install map | Code: explicit consent, reviewed before merge |

Wine runs as the app's own Android user, so code in a mod can read the app's data (including store logins). Data kinds
may only contain `.json` and images; code is reviewed by maintainers, hash-pinned, and installed only after the user
sees what it writes.

## App side

Built (`duo/community/`): `CommunityPackages` (fetch with ETag, ed25519 + sha256 verification, disk cache in
`files/duo/community/`, apply/remove, applied versions in `applied/<containerId>.json`), `Ed25519` (verify-only, RFC 8032,
unit-tested against the RFC vectors and a published catalog), and the mod explorer (`duo/mods/ModExplorer.kt`,
`ModHub`, `ModHubState`), opened by the chevron of the Dual screen control next to Play. It lists bundled mods and
community packages together: code mods (or, for games without one, settings packages) are what the switch applies,
one at a time, switched through `DuoModInstaller`'s undo record; layouts and settings are add-ons on top. Layouts
and settings apply in one tap; a layout the user made is only replaced after asking; code mods need consent once
per version; updates show when the index has a newer `packageVersion`. Ratings: `duo/docs/community-ratings.md`.

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
