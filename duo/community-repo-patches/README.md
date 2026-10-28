# Community repo changes for trustworthy ratings (to apply by hand)

Written by agent task 3-mod-hub (2026-10-28). The session could read `../GameNativeDuo-community` but could not
write to it or run Python, so these files are **untested**: run `python tools/test_rating_rules.py` and
`python tools/duo_index.py validate` after copying them in. The app side (`app.gamenative.duo.mods.ModExplorer`)
already reads every field below and works without them (it falls back to the discussion link and raw counts, and
does not claim the one-vote rules until `rules >= 2`).

## Files to copy

| File | What |
|---|---|
| `tools/rating_rules.py` | Pure rules: one vote per account (👍 and 👎 from one account cancel), authors and bots excluded, compat reports one per account+device per package version (newest wins). |
| `tools/test_rating_rules.py` | Unit tests for the rules. Add `python tools/test_rating_rules.py` to `validate.yml`. |
| `.github/ISSUE_TEMPLATE/compat-report.yml` | Report form. Field ids match what the app pre-fills (`package`, `version`, `device`, `result`, `game`). Inputs, not dropdowns: GitHub only pre-fills inputs from the URL. |

## Edits to existing files

### `tools/ratings.py`

1. In the discussions query, replace `reactionGroups { ... }` with the reacting users:
   ```graphql
   up: reactions(first: 100, content: THUMBS_UP) { nodes { user { login __typename } } }
   down: reactions(first: 100, content: THUMBS_DOWN) { nodes { user { login __typename } } }
   ```
   (paginate when `totalCount > 100`; one query per discussion is fine at our size).
2. Read compat reports once: `repository.issues(labels: ["compat-report"], first: 100, states: [OPEN, CLOSED])
   { nodes { author { login __typename } createdAt body } }` (paginated), then `rating_rules.report_from_issue`.
3. Per package:
   ```python
   authors = manifest.get("githubLogins", [])
   up, down = rating_rules.aggregate_votes(up_users, down_users, authors)
   ratings[pid] = {
       "up": up, "down": down, "comments": discussion["comments"]["totalCount"], "url": discussion["url"],
       "rules": rating_rules.RULES_VERSION,
       "reportUrl": f"https://github.com/{owner}/{name}/issues/new?template=compat-report.yml",
       "compat": rating_rules.aggregate_compat(reports, pid, manifest["packageVersion"]),
   }
   ```
4. The `create()` body text: "React 👍 or 👎 (one vote per account; authors' own votes are not counted). To say how it
   runs on your device, use the report form: <reportUrl>".
5. Permissions in `publish.yml`: add `issues: read`.

### `schema/package.schema.json`

- `githubLogins`: optional array of GitHub logins (`^[A-Za-z0-9-]{1,39}$`), the package's authors on GitHub, excluded
  from its votes. The share bot (`issue_to_package.py`) should fill it with the issue author's login.
- `changelog`: optional array of `{version, date, notes}` (notes ≤ 500 chars); the app shows the newest five.

### `tools/duo_index.py`

- Official packages: a maintainer-owned `OFFICIAL` file (one package id per line; protect it with CODEOWNERS).
  `build()` writes `"official": [ids of this game's packages listed there]` at the top level of each
  `games/<key>.json` (never copied from a manifest; the schema's `additionalProperties: false` already stops a
  manifest from carrying `official` or `rating`). The app trusts it because the file is signed.
- `validate()`: a package id starting with `gamenative-duo.` must be listed in `OFFICIAL` (the namespace is
  reserved), so nobody can look official by name alone.

Already official in the app without this (pinned in `CommunityIndex.OFFICIAL_PACKAGES`): crosshair-on-top,
recommended-settings, ayn-thor, dead-cells-minimap-below, hollow-knight-dual-screen. Missing from the pin but in the
repo under our namespace: `gamenative-duo.isaac-minimap-below`, `gamenative-duo.silksong-dual-screen`,
`gamenative-duo.ror2-dual-screen` (they will show as Community until `OFFICIAL` exists or the pin is extended).
