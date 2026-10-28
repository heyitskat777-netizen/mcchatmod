# Refine scene qualification, review queues and home search

Short one-actor fragments no longer appear as new shared scenes. Automatic creation
and new split/combine results require 10 RP messages and 2 distinct resolved actors.
Clear groups default to Confirmed(auto); low-confidence groups remain Suggested,
listed first/newest with a Suggested-only filter. Explicit reviews and old manual
small scenes survive. Character schema 5 upgrades after a verified paired backup;
archive 9/parser 4 stay unchanged. Neutral internal windows preserve persona evidence
without exposing shared confidence or qualification.

Identity review now advances through normalized shared-name/alias candidates after
successful Confirm/Deny, with rooted denial exclusions, matching-name hints,
completion/error separation and resize-safe pending actions. Manual selectors
remain. Home search shows match feedback, opens filtered Characters on Enter and
preserves long queries across resize. Loading hides stale scene rows/narration;
rejected undersized splits explain the rule and keep the editor reusable.

Validation: 352 core tests, 15 runner contract tests; architecture, Fabric build and
six packaged runtime dependencies verified. Actual development writer/read and
packaged-JAR writer/read suites pass, each preceded by graphics preflight, with
28/29 assertion stages. Includes controlled pending-Deny resize, search/Enter,
confidence/filter ordering, rejected split, exact 10+10 split/combine, source/privacy
preservation, restart/backups and strict clean shutdown. See docs/STATUS.md and
verification/core-results.json for commands, failed-run diagnosis and the mandatory
three-run reassessment before final passes. No criteria/budgets weakened.

User acceptance: follow USER_TEST_ALPHA13.md with actual logs/modpack, especially
scene minimums/confidence, Suggested+Skipped filters, queue progression, search and
backup restoration. Large archive/queue latency and independent global tag-change
versioning remain documented limitations.

Install/test build: [alpha.13 journal-jars](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40649605532/artifacts/12799111435).

CI: [40649605532](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40649605532)
passed both verify and full client-smoke jobs on the verified implementation commit.
