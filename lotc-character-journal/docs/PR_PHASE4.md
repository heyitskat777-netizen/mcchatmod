# Add reversible linked history and reviewable scenes

Confirmed identity links now combine shared related dialogue once per immutable
source coordinate, and undo immediately separates the views. Persona memories
retain their explicit assignments and identities.

Adds source/session-local RP scene suggestions, a main-screen browser and
post-import review entry points. Users can correct participants/title/location,
confirm or skip, search/scroll scene dialogue, and split/combine saved scenes.
Review decisions survive regrouping, imports, restart and paired backup/restore.
Direct/overheard requires an explicit persona perspective. Assigned-only scene
views hide shared metadata and do not inherit global tag/link identity knowledge.

Characters schema 4 upgrades after a verified paired backup; archive schema 9 and
parser policy 4 stay unchanged. Source undo trims only owned scene membership in
the same paired transaction. Revision tombstones and compare-and-save protect
stale edits. Summary paging avoids loading every scene's dialogue.

Validation: 333 core tests; 14 smoke-runner contract tests; architecture, Fabric
compilation/build and packaged dependency checks. The actual Linux/Mesa client
suite passes graphics preflight, development write/read and packaged-JAR write/read,
including scene edits, resize, individual split/combine, skip/restart, persona
projection, backups and clean worker shutdown. Exact commands/runs/results are in
[docs/STATUS.md](https://github.com/heyitskat777-netizen/mcchatmod/blob/feature/scenes/lotc-character-journal/docs/STATUS.md) and [verification/core-results.json](https://github.com/heyitskat777-netizen/mcchatmod/blob/feature/scenes/lotc-character-journal/docs/verification/core-results.json).

User testing: follow [USER_TEST_SCENES.md](https://github.com/heyitskat777-netizen/mcchatmod/blob/feature/scenes/lotc-character-journal/docs/USER_TEST_SCENES.md), especially quiet,
crowded and interrupted conversations, manual participant/location corrections,
restart/skip, link undo, persona isolation and restoring a backup copy. Locations
without evidence remain unknown; a split inherits reviewed interpretations and
requires reviewing both halves. Real-log accuracy, large detailed-scene performance
and modpack acceptance remain distinct from synthetic automated verification.
