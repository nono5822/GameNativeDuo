# Community ratings and compatibility reports

How the mod explorer's ratings work, end to end. App: `duo/mods/ModExplorer.kt`, `community/CommunityIndex.kt`
(`CommunityRating`, `CompatReports`). Community repo: `tools/ratings.py` + `tools/rating_rules.py` (unit-tested by
`tools/test_rating_rules.py` in its validate CI), the `compat-report.yml` form and the maintainer `OFFICIAL` file.

## Votes

- A vote is a 👍 or 👎 reaction on the package's GitHub discussion (one per package, created by the publish job).
  The app never calls GitHub: the nightly publish folds the counts into the signed index.
- Rules (`rating_rules.aggregate_votes`, index field `"rules": 2`): one vote per account (both reactions from one
  account count as neither), the package's authors (`githubLogins`) and bots are not counted.
- With `rules >= 2` the detail page states those rules under the counts (`CommunityRating.rulesNote`). An older
  index's raw counts are shown without claiming them, with the warning "counted before the one-vote rules" once
  there are votes.
- The share bot writes the sharing account into `githubLogins` and refuses updates from another account.
- Shown as "👍 92% of 25 votes · 3 comments"; sorted by the Wilson lower bound (`CommunityRating.score`), so one 👍
  does not beat forty 👍 and two 👎.

## Compatibility reports

- A report is an issue from the repo's `compat-report.yml` form, opened pre-filled from the app
  (`ModHub.reportUrl`: package, version, device profile id, result). Without a form URL in the index, the app falls
  back to the discussion.
- Counted per device profile for the package's current version only, newest report per account and device
  (`rating_rules.aggregate_compat`). Index: `rating.compat = {"ayn-thor": {"works", "issues", "broken"}}`.
- Verdict (`CompatReports.verdict`): works only with a clear majority, ties lean to the worse outcome.
- Shown next to the rating: "✓ Works on AYN Thor (5 of 6 reports)", else "Tested on AYN Thor by its author"
  (`testedOn`), else "No reports from AYN Thor yet". "Best on <device>" sorts by that.

## Official

Bundled mods, `CommunityIndex.OFFICIAL_PACKAGES` (pinned in the app), or the game file's top-level `official`
list (written by the index builder from the maintainer's `OFFICIAL` file, covered by the signature). Never from a
package's own fields. Ids starting with `gamenative-duo.` are reserved: the community repo's validation fails unless
they are listed in `OFFICIAL` (CODEOWNERS-protected).

## Voting from the app

Detail page → "Vote or report": opens the discussion (vote) or the pre-filled form (report) in the browser, or
shows the link as a QR code for a device that is signed in to GitHub.
