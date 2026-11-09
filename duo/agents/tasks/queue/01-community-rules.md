# Task 01: apply the community-repo voting rules (finishes task 3)

Owner: Codex (code-only) · Thor needed: no · Depends on: —

Read `duo/agents/tasks/queue/RULES.md`. Task 3 left `duo/community-repo-patches/` (one vote per account, no self-votes,
report form, maintainer official list) unapplied. Apply them to `../GameNativeDuo-community`, run its
`tools/test_rating_rules.py` and `tools/duo_index.py validate`, keep its CI green, commit there. Then switch the app
from "plain reaction counts" to showing the enforced rules (see `duo/docs/` mod-explorer notes), unit-test it, and
delete `duo/community-repo-patches/`. Then: a cross-review of branches `agent/2026-10-28-1007` and `agent/2026-10-28-1446`
(HK, upstream merge, mod hub) by a different model than wrote them; findings as tasks or fixes.
