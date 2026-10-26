# Current status — October 26, 2026

## Alpha.8 — visible character browser and native database scope

Branch `feature/character-browser`, based on `209a31d` (alpha.7). Kat reports live
recording seems OK, but saved characters cannot be reviewed/listed and Use as
persona stays gray, blocking message-assignment testing. This records limited
live-recording user acceptance; persona user acceptance remains pending this fix.

Root cause: IdentityScreen hid saved rows behind a selector also named New
character, reset its index on every load/reopen/resize, and could race two save
refresh callbacks. The alpha.7 create→immediate persona smoke did not exercise
reopening existing characters. Alpha.8 replaces it with a visible searchable,
paged saved-character list and a separate detail editor keyed by immutable UUID.
Saved rows open labeled name/alias/account/notes fields with persona activation
available. New/dirty drafts explain the required explicit Save. Drafts survive
resize; one tokened list refresh preserves previous results on errors and exposes
Refresh / retry. Selecting/saving never relies on a changing filtered row index.

Review also found valid 200×96 aliases could be truncated by the old 4096-character
field, and literal semicolons/backslashes were not round-tripped. The bounded
40,000-character lossless escaped form preserves those native domain values and
rejects malformed edits without persisting partial data.

Kat **withdraws the unrelated previous-database importer requirement**. No format
clarification is pending; prior alpha.7 references below are historical and
superseded. AGENTS/DECISIONS/MILESTONES/README/checklists now require one mod-created
`config/lotc-journal/journal.sqlite`. This already contains imported historical
and live observations, characters, aliases, personas, favorites and assignments.
Schema **8** and parser policy **4** stay unchanged: no reset, replacement database
or new schema migration is needed, and existing native records are preserved.
Historical .log/.log.gz imports populate the same archive; identity confirmation
remains explicit. Native schema upgrade tests/backups stay; the obsolete external
legacy discovery document is removed.

Native agents were available: build changed only the version to alpha.8 and
confirmed same-DB/schema/package preservation; verification added four meaningful
application alias-integrity cases and proposed real reopen/selection regressions;
review independently inspected the actual browser/editor/alias changes and found
no blocking issue. Coordinator owns UI/client assertions/docs and integrated runs.
No agent ran shared Gradle checks or committed files.

Coordinator verification:

```sh
./gradlew check :fabric:build --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --run-id character-browser-1 --timeout 300
```

Integrated check/build **passed in 1m18s**, 30 tasks (18 executed/12 up-to-date).
Fresh XML: **257 tests, 0 failures/errors/skips** — domain 4, application 16,
parsing 116, ingestion 56, storage 65. All **14 Python runner tests passed** in
1.142s. Full-size alias sets, delimiter/backslash/Unicode and whitespace preservation,
invalid encodings and explicit replacement/clearing are covered by the new cases.
Toolchain/metadata and all six packaged runtime JAR checks passed. No failed
compile or repair cycle is claimed at this point.

Local **`character-browser-1` passed all five phases** within the existing
300-second process limit, all exit codes zero, no timeouts/report errors:

| Local phase | Elapsed | Result |
| --- | --- | --- |
| Graphics preflight | 92.9s | Passed |
| Development writer | 160.0s | Passed |
| Development reader | 118.3s | Passed |
| Packaged writer | 228.9s | Passed |
| Packaged reader | 123.6s | Passed |

Writers passed **21 assertion stages**, readers **22**, including successful body
completion and worker/shutdown witnesses. No runtime repair, reduced assertion or
extended timeout was needed. Evidence: `fabric/build/client-smoke/evidence/character-browser-1/`
(JSON, XML, selected screenshots, logs, launch/shutdown witnesses). Normal-list and 640x480 detail screenshots were inspected: saved rows/ID
suffixes, labeled fields and explicit save/persona guidance are readable and the
controls remain onscreen.

GitHub Actions **passed verify and the complete five-JVM client suite** on
functional commit `5a49648`:
[run 40406809302](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40406809302).

| CI phase | Elapsed | Result |
| --- | --- | --- |
| Graphics preflight | 34.5s | Passed |
| Development writer | 36.0s | Passed |
| Development reader | 34.6s | Passed |
| Packaged writer | 40.3s | Passed |
| Packaged reader | 34.0s | Passed |

[PR #9](https://github.com/heyitskat777-netizen/mcchatmod/pull/9) targets PR #8's
feature/live-capture branch and is ready for review; main stays unchanged. The
final documentation-only commit receives normal CI separately; the successful
functional commit/run above identifies the tested code. Alpha.8
[installable CI artifact ZIP](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40406809302/artifacts/12691079070).
Install the non-`-sources` JAR. Local artifact:
`fabric/build/libs/lotc-character-journal-0.1.0-alpha.8.jar`, 14,646,256 bytes;
SHA-256 `25eaf1f7bffc8a1b01f25eec58f30544f19f5251e75109cecc970d2b02d61142`.
User/modpack acceptance of the new browser remains pending.

Mandatory `character-browser` creates/saves without immediately using a persona,
returns to visible rows, filters by shared alias and empty query, opens the second
saved UUID, resizes an edited draft at 640x480, saves/activates that exact persona,
reopens/filters the first character and checks repeated activation creates no extra
identities/personas. Reader JVMs reuse saved records, followed by unchanged actual
Assign/memory-scope assertions. Existing live capture/privacy/journal-reimport/
favorites/import/undo/watch/keybind/backup/shutdown gates remain mandatory.
Logs: `/tmp/lotc-browser-integrated-1.log`, `/tmp/lotc-browser-runner-tests.log`,
`/tmp/lotc-browser-client-1.log`. New user checklist:
[USER_TEST_CHARACTER_BROWSER.md](USER_TEST_CHARACTER_BROWSER.md).

## Alpha.7 — individual selection, live capture and manual identities/personas

Branch `feature/live-capture`, based on `f906858` (alpha.6). Kat reports the favorite
selection looks like the whole log and authorizes continuing Phases 2b and 3.
Main remains the reviewed baseline. Alpha.6 acceptance is **qualified by this UI
finding**, not recorded as an unqualified user pass.

Native delegation: build agent implemented new live domain/ingestion and initial
schema 7, then handed SqliteJournalStore to the coordinator for additive schema 8.
Verification agent supplied 32 new tests, including real child-process death and
restart. Review agent independently checked row coordinates, durability, privacy,
threading, imports and identity isolation without edits. Verification/review agents
then reached usage limits; coordinator owns remaining fixes, final integration,
client assertions and verification. No completed independent final approval of all
coordinator follow-ups is claimed.

Implemented scope:

- Individual-message row highlighting, neutral pane border, selected-message status
  and no implicit first-row selection. Static inspection found coordinate inversion
  correct; the old test only clicked the first row, so it could not establish
  arbitrary-row behavior. New real-input regression clicks a wrapped third message
  after scrolling and checks exact selected coordinate and favorite SQL state.
- Automatic received CHAT and non-overlay GAME capture on world connection, with
  persisted pause/resume, bounded startup/worker queues and visible pending/saved/
  error/overflow state. No outbound commands, action bars or canceled packets are
  claimed captured. Filtering precedes any payload persistence.
- Stable session/sequence observations in the same transcript/favorites database,
  explicitly separate from immutable historical content-hash sources. Dated
  `<gameDir>/lotc-journal/live/<date>/<session>-<segment>.lotc-chat.jsonl` authority
  files and readable `.chat.txt` views. Force precedes SQLite commit and SAVED
  acknowledgment. Restart replays forced events idempotently; incomplete owned
  tails are repaired and incomplete headers quarantined, never unrelated files.
- Date/size rotation, strict UTF-8/versioned records, concurrent-root ownership,
  per-message disk/queue failures, derived-view rebuild, and read-only explicit
  import of a complete closed **single-segment** journal through Log import.
  Rotated multi-segment sessions recover automatically from their owned root;
  selecting one arbitrary rotated segment is not a supported standalone import.
- Character CRUD (name/aliases/notes/manual account observation), explicit owning
  persona creation/selection, message identity/reference vs speaker confirmation,
  unassigned/skip, clearing/reassigning, persistent audit coordinates, and reversible
  merge overlays with persisted decision review/undo. Shared archive administration
  is visibly separate from persona memories. Memory views require exact persona
  assignments and preserve source dialogue/favorites. Capture does not silently
  attribute all dialogue to the selected persona; new memories remain explicitly
  assigned. AI, scenes and extraction remain disabled/unimplemented.
- A screenshot exposed comma-title persona notices missing from the exact template.
  Policy **4** recognizes those unattributed replies and preserves quoted/proven RP.
  Schema **8** verifies/backups older schemas, cleans uncertain older notices with
  favorite cascade and source/ownership accounting, and adds live/identity tables.
  Existing ROLEPLAY rows are deliberately not forcibly deleted from wording alone.
  The screenshot alone does not prove their original stored classification.

Upgrade: finish alpha.5/alpha.6 **partial policy-3** jobs with their matching old mod
before installing alpha.7, or use its matching pre-upgrade backup first. Completed
source identity/policy/undo remains stable. Older backups and originals retain
prior bytes. Backups are SQLite snapshots: retaining newer authoritative live
journals while restoring an older database replays newer forced chat events. Copy
both owned journal and database directories for a full profile recovery plan.

Milestone limits: Phase 2b's live recording/replay is implemented; ambiguous overlap
with ordinary historical `.log` files remains distinct provenance, not a claimed
exact cross-format deduplication. Native event IDs reconcile native journal repeats;
text/time equality never collapses repeated emotes. Private-source accuracy and
measured real-play performance remain Phase 2c. Phase 3's manual identity/persona
slice is implemented; the **previous-database importer is pending its actual
schema/file**. A clarification is pending; no prior format or automatic merge is
invented. Future exports/statistics/AI must use the same persona boundary when
implemented; no absent analysis feature is claimed tested.

Coordinator commands:

```sh
./gradlew -PcoreOnly check --no-daemon --console=plain
./gradlew check :fabric:build --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --run-id live-persona-smoke-1 --timeout 300
```

The first core attempt failed compilation because a new child-JVM test referenced
SQLite's runtime-only driver at compile time. It was corrected to discover the
runtime class location with Class.forName; no production dependency or assertion
was weakened. Integrated pass 2 succeeded in **2m18s**. Following narrow titled-name
and invalid-user-import refinements, integrated pass 3 succeeded in **1m59s**.
Fresh XML: **253 tests, 0 failures/errors/skips** — domain 4, application 12,
parsing 116, ingestion 56, storage 65. All **14 Python runner tests passed** in
1.282s. New cases include two real forcibly killed/halted JVM/restart scenarios,
force-before-SQL replay, acknowledged receipt persistence, repeated events, Unicode,
partial tails/headers, view rebuild, concurrent ownership, rollover/queue/disk
failure, privacy before disk, genuine old-schema backup/cleanup and strict persona
isolation/merge reversal/assignment rollback. These prove process-crash behavior;
power-loss or drive-controller guarantees are not inferred.

The final integrated check/build passed in **1m40s** (30 tasks: 8 executed, 22
up-to-date). The initial five-JVM `live-persona-smoke-1` attempt is recorded below, followed by
the complete passing repaired run. Mandatory stages now include arbitrary-row selection, live startup,
native CHAT/GAME + overlay/privacy exclusions, paused restart, closed-format UI
reimport, character/persona UI, explicit two-message assignments and persona memory
views, while retaining prior import/undo/watch/scroll/backup/keybind/world/shutdown
assertions. Identity and live worker termination are included in shutdown witnesses.
First runtime attempt **failed** in development reader (128.5s), reproduced both
locally and in GitHub Actions run 40381310444. Preflight (145.4s) and development
writer (150.3s, 20 assertion stages) passed; packaged phases were not attempted
after the reader failure. The old SQL RP assertion counted all stored sources,
including the writer's three valid live RP events, while still expecting the 52
historical fixture rows. The visible historical query was already correct.
Repair cycle 1 scopes the SQL counts to that exact historical source, retains
52 RP / 10 UNKNOWN expectations, and leaves the separate exact-three-live-event
assertions intact. This is a test-scope repair, not an ignored application failure
or reduced criterion. Failed evidence remains under `live-persona-smoke-1`.
Repair check/build passed in **38s** (30 tasks: 3 executed, 27 up-to-date).
Fresh local **`live-persona-smoke-2` passed all five phases**, exit 0, no timeouts
or report errors, with body-completion and successful worker/shutdown witnesses:

| Local phase | Elapsed | Result |
| --- | --- | --- |
| Graphics preflight | 152.3s | Passed |
| Development writer | 175.6s | Passed |
| Development reader | 158.6s | Passed |
| Packaged writer | 149.3s | Passed |
| Packaged reader | 129.6s | Passed |

Writers passed **20 assertion stages**, readers **21**. Evidence is preserved in
`fabric/build/client-smoke/evidence/live-persona-smoke-2/` (JSON/XML/screenshots,
safe logs, launch and shutdown witnesses). Existing software-renderer/audio/Realms
warnings were nonfatal only because every mandatory assertion, witness and exit
passed. No timeout or assertion was relaxed. Actual row-selection and normal main-screen screenshots
were inspected: only the intended third wrapped message is highlighted/starred,
with adjacent rows and fixed controls separate. User/modpack acceptance is pending.

GitHub Actions **passed verify and the complete five-JVM client suite** on repaired
functional commit `cd7ffc5`:
[run 40382340353](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40382340353).

| CI phase | Elapsed | Result |
| --- | --- | --- |
| Graphics preflight | 40.9s | Passed |
| Development writer | 45.2s | Passed |
| Development reader | 42.3s | Passed |
| Packaged writer | 51.9s | Passed |
| Packaged reader | 41.6s | Passed |

No assertion/timeout was reduced. First failed run is preserved; one runtime
repair cycle addressed source scoping. Native CHAT/GAME capture, privacy/overlay
exclusions before both file formats, exact event-ID journal reimport, explicit
character/persona UI and restricted memory views all passed the runtime gates.
These are isolated local-world tests, not unattended LoTC server or real-modpack
accuracy evidence.

[PR #8](https://github.com/heyitskat777-netizen/mcchatmod/pull/8) stacks on favorites
PR #7 and is ready for review; main remains unchanged. A documentation-only final
commit receives its normal CI rerun; the successful functional commit/run above
is identified precisely. Alpha.7
[CI installable artifact ZIP](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40382340353/artifacts/12679930033).
Install the non-`-sources` JAR. Local artifact:
`fabric/build/libs/lotc-character-journal-0.1.0-alpha.7.jar`, 14,637,604 bytes;
SHA-256 `9bfea22c60a93dde7048a959394cfa2e00e20dd607ab1d75cb957503bf37133e`.
CI evidence includes screenshots/logs/JSON/XML. Direct cloud artifact retrieval was
blocked by the blob host (403); CI's phase logs and preserved local evidence were
available for verification. Logs: `/tmp/lotc-live-core-1.log`,
`/tmp/lotc-live-integrated-2.log`, `/tmp/lotc-live-integrated-3.log`,
`/tmp/lotc-live-integrated-final.log`, `/tmp/lotc-live-runner-tests.log`,
`/tmp/lotc-live-client-1.log`, `/tmp/lotc-live-client-2.log`. User checklist:
[USER_TEST_LIVE_PERSONAS.md](USER_TEST_LIVE_PERSONAS.md).

## Alpha.6 — favorites, transcript access and accepted next milestones

Branch `feature/message-favorites`, based on alpha.5 verification commit `2351a72`.
Kat reports all tests passed so far; this accepts the preceding smoke work, not
unimplemented live capture or measured private-source accuracy. Main is unchanged.
Native agents are available: build investigated exact Fabric hooks and public
Chat Logs source/package impacts read-only; verification added only new storage
and domain tests; review independently checked data integrity/threading/privacy
without edits. Coordinator owns production/UI/client assertions/docs/integration.

Implemented: individual coordinate-keyed favorites, direct star / selected-message
button / focused-pane F controls, intersecting Favorites filter, and main-screen
View transcripts using background source loading. Selected offscreen messages
cannot be toggled accidentally; empty views disable mutation. SQL desired-state
writes are idempotent and reject missing rows, callbacks retain exact keys and
ignore stale requests, and count/page/favorite state share a read snapshot.
Bookmarks do not infer identities or change AI eligibility. Schema 6 is additive
from v5 with verified pre-schema6 backup, preserves parser policy 3, and cascades
bookmarks only when their observation is deleted. Older upgrades retain backed-up
noise cleanup. Existing migration fixtures now reconstruct the exact old shape
without the new additive table; privacy/reference expectations were preserved.

Accepted and scheduled, **not enabled**: Phase 2b automatic dated crash-recoverable
live journals/readable logs (next slice), Phase 3 explicit reversible identity
assignment with confirmation/unassigned, Phase 4 location/participant scene view
and import-time review with persistent skip/override, Phase 6 evidence-backed
favorite item/name/task proposals. See MILESTONES.md and DECISIONS.md. The public
Chat Logs review and exact 1.21.11 capture/durability criteria are in
[LIVE_CHAT_LOG_DESIGN.md](LIVE_CHAT_LOG_DESIGN.md); no third-party dependency added.

Coordinator verification:

```sh
./gradlew check :fabric:build --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --run-id favorites-smoke-1 --timeout 300
```

Integrated check/build passed in **28s** (30 tasks, 7 executed/23 up-to-date).
Fresh XML totals **217 tests, 0 failures/errors/skips**: domain 4, application 9,
parsing 115, ingestion 39, storage 50. Ten new tests cover exact-coordinate identity,
restart/idempotence, missing/removed-row rejection, literal query/channel/source/
favorites intersections and paging, backup restoration, owned undo/cascade failure
rollback and genuine v5 additive migration. All **14 Python contract tests passed**
in 1.157s. Two earlier integration passes also succeeded; no failed repair cycles.
Independent review caught null-selection and offscreen-F hazards before runtime;
both guards are present. Package verification checks MessageReference in the
existing domain JAR and all six bundled runtime dependencies.

Actual five-JVM **`favorites-smoke-1` passed all phases** (300-second bound per
process, all exit codes zero, no timeouts or report errors):

| Phase | Elapsed | Result |
| --- | --- | --- |
| Graphics preflight | 59.9s | Passed |
| Development writer | 73.8s | Passed |
| Development reader | 66.0s | Passed |
| Packaged writer | 96.7s | Passed |
| Packaged reader | 68.8s | Passed |

Writers passed 16 assertion stages and readers 17, with body-completion and clean
shutdown witnesses. Normal and 640x480 screenshots were inspected: star controls,
scrollbar and fixed footer are readable/reachable. Nonfatal graphics/audio/Realms
warnings did not replace any assertion; no runtime repair cycle was needed.
Mandatory `message-favorites` assertions use the real main entry point and star
mouse control, preserve one of two identical-looking observations independently,
empty the last-favorite view safely, and verify exact SQL state before reader
mutation. Previous roleplay/privacy/scroll/batch/undo/watch/keybind/world/backup/
shutdown assertions remain mandatory. No user testing of alpha.6 is inferred.
Logs: `/tmp/lotc-favorites-integrated-final.log`,
`/tmp/lotc-favorites-runner-tests.log`, `/tmp/lotc-favorites-client-1.log`.
Evidence: `fabric/build/client-smoke/evidence/favorites-smoke-1/` contains JSON,
JUnit XML, screenshots, safe logs and launch/shutdown witnesses. Legacy migration,
restored favorite backups and transactional undo failures are proven by separate
synthetic SQLite tests; fresh client profiles do not substitute for upgrade tests.

GitHub Actions passed both verify and the full client-smoke job on functional
commit `b1960bb`:
[run 40267722195](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40267722195).
[PR #7](https://github.com/heyitskat777-netizen/mcchatmod/pull/7) targets the unmerged
roleplay-filtering PR #6 branch and is ready for review; main remains unchanged.
This evidence identifies the functional commit; a documentation-only follow-up
receives the normal CI rerun separately.

Installable local artifact: `fabric/build/libs/lotc-character-journal-0.1.0-alpha.6.jar`,
14,552,317 bytes; SHA-256
`f11e5aace13836faabec36dbf11326d58121e8701395bdd6e00e6b5638ab5df1`.
[Alpha.6 CI artifact ZIP](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40267722195/artifacts/12627436071).
Install the non-`-sources` JAR. User testing of alpha.6 remains pending:
[USER_TEST_FAVORITES.md](USER_TEST_FAVORITES.md).

## Alpha.5 — roleplay classification and system-noise cleanup

Branch: `feature/roleplay-filtering`, based on `b8220c4`. Kat supplied screenshots
of command/plugin output retained as UNKNOWN and requested RP-only chat/emotes.
No private screenshot names, accounts or dialogue are included in fixtures.

Scope: whole-message deterministic filters for persona switch/size/search/results,
JourneyMap hints, friend requests, news headers/link bullets, Soulstone notices,
lock and build/region replies; original IC-channel/sender evidence for new ROLEPLAY
observations; direct Roleplay only control plus existing All/UNKNOWN review.
Unmarked ambiguous text stays reviewable. Roleplay channel classification does not
infer speaker identity or confirm persona/account links. Older cleaned UNKNOWN
rows lack original IC wrappers and are not automatically promoted from wording.

Schema 5 performs backed-up cleanup of old schema-4 notices, preserves source IDs,
processed positions, classifications, ownership/undo and original files, and adjusts
ignored/imported contributions atomically. Legacy-v4 structural validation must not
apply the new notice policy before backup/migration; current-v5 canonical validation
remains strict. Parser policy 3 requires original-compatible partial jobs; complete
alpha.4 imports before upgrading or finish using alpha.4 and the pre-upgrade backup.

Native agents are available for this refinement: build owns schema/storage tests
and package/version checks; verification owns parser/ingestion policy and tests;
review is read-only. The coordinator owns UI, client assertions, docs and integrated
checks. Implementation is complete. Independent review found no remaining blocker after
narrowing region/persona/news matches to preserve extended narration and star/hyphen
emotes, and replacing recursive long-list matching with constant-stack parsing.
No user acceptance is inferred from these checks. Alpha.4 results below are historical.

Coordinator commands (Java/proxy/cache activation documented below):

```sh
./gradlew -PcoreOnly :parsing:test :ingestion:test :storage:test --no-daemon --console=plain
./gradlew check :fabric:build --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --run-id roleplay-smoke-1 --timeout 300
```

The initial narrow check passed in **41s**. After conservative pattern refinements
and a reproduced 30KB region-list StackOverflowError, the affected integrated
check/build passed in **1m13s** (30 tasks: 11 executed, 19 up-to-date).
Fresh XML: **207 tests, 0 failures/errors/skips** — domain 1, application 9,
parsing 115, ingestion 39, storage 43. The new long-list regression covers 10,000
regions and 2,000 accounts below the existing 64KiB limit, valid/malformed tails
and actual parser execution under a two-second budget, without reducing limits.
All **14 Python runner contract tests passed**. Package checks require the bundled
notice classifier and normalizer as well as the six runtime JARs.

The five-JVM `roleplay-smoke-1` suite **passed all phases**: graphics preflight
90.1s, development writer 119.9s / reader 88.8s, packaged writer 119.3s / reader
97.9s. All processes exited zero, with no timeouts or report errors. Writers
passed 15 assertion stages and readers 16.
Its mandatory `roleplay-filter` stage asserts 52 explicit-RP and 10 unmarked UNKNOWN
observations, 13 ignored notice families, no inferred speaker identities, actual
Roleplay only / UNKNOWN / All controls and persistence. The fixture totals 78 source
lines = 62 imported + 2 excluded + 14 ignored. Existing scroll/resize/batch/retry/
undo/watch/backup/world/keybind/shutdown assertions stay in place. Legacy schema-4
cleanup, no-raw-column backups, rollback/counters/ownership and UNKNOWN preservation
are separately covered with synthetic databases.

Logs: `/tmp/lotc-roleplay-core.log`, `/tmp/lotc-roleplay-integrated-verified.log`,
`/tmp/lotc-roleplay-runner-tests.log`, `/tmp/lotc-roleplay-client.log`.
GitHub Actions also passed verify and client-smoke on functional commit `8bde8d7`: [run 40223901866](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40223901866).
[PR #6](https://github.com/heyitskat777-netizen/mcchatmod/pull/6) targets
`feature/client-smoke-tests`; main remains unchanged. [Alpha.5 installable artifact ZIP](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40223901866/artifacts/12610327295).
Kat reports all tests passed so far in the subsequent favorites/scene request.
This records user smoke acceptance; measured private-source parser accuracy and
live capture remain unfinished. Checklist: [USER_TEST_ROLEPLAY_FILTER.md](USER_TEST_ROLEPLAY_FILTER.md).

## Alpha.4 refinement — dialogue-only persistence and scrolling

Branch: `feature/dialogue-transcript`, based on `82884d4`. Kat reports **all alpha.3
smoke tests OK**. The supplied screenshot identified the need for continuous
scrolling and removal of logger wrappers from persistent dialogue. This accepts
the previous smoke checklist; it does not establish measured real-log parser
accuracy or accept the new alpha.4 refinement before user testing.

Implemented: native conversation scroll pane (wheel, draggable bar, arrows,
Page Up/Down, Home/End), bounded 50-message pages with wheel/keyboard transitions,
responsive fixed controls and optional Details. The active transcript schema has
one cleaned dialogue payload, with no raw logger-line column. Known logging/RP
wrappers and formatting are stripped; quoted text and unknown bracketed actions
are preserved. Known system notices/empty chat are ignored and PM/OOC remains
excluded before/after cleanup. Provenance remains separate and never implies a
reconstructed date, timezone, character identity or persona.

Schema 4 backs up and validates schema 1/2/3 before migrating. Existing text is
cleaned with the same injected parser normalizer. Removed noise/privacy records
adjust imported/excluded/ignored source and owned-file counters atomically;
processed positions, source IDs, original files and undo ownership stay intact.
Runtime cleanup failures roll back earlier writes/schema/accounting. Old backups,
SQLite history and original files may retain raw bytes; no secure-erasure claim.
Policy 2 blocks incompatible policy-1 partial resumes; finish alpha.3 partial jobs
before upgrading, or finish them using alpha.3 and its pre-upgrade backup first.
Completed source IDs/classifications and explicit undo decisions remain stable.

Native tools were available and three agents supplied initial API/design hazards,
then **all three hit their usage limit before implementation/review completed**.
The coordinator took over production, tests, migration/UI review and integration;
no completed independent final review is claimed for this refinement.

Verification so far:

```sh
./gradlew -PcoreOnly check --no-daemon --console=plain
./gradlew check :fabric:build --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --run-id dialogue-smoke-1 --timeout 300
```

Core check passed in **32s** and integrated Fabric/package build passed in **56s**
(30 tasks: 12 executed, 18 up-to-date). Fresh XML: **142 tests, 0 failures/errors/skips**
(domain 1, application 9, parsing 56, ingestion 36, storage 40). All **14 runner
contract tests passed**. Two initial compile errors from the API change were fixed
(a package-private helper call and a stale raw-field test reference); no assertions
were weakened. Tests now assert the requested clean-payload behavior instead of
retaining the explicitly removed raw copy. Existing privacy expectations, including
ambiguous Whisper as private, remain intact.

The actual five-JVM **`dialogue-smoke-1` passed every phase**:

| Phase | Elapsed | Result |
| --- | --- | --- |
| Graphics-only preflight | 83.9s | Passed |
| Development writer | 98.3s | Passed |
| Development reader | 90.5s | Passed |
| Packaged writer | 116.4s | Passed |
| Packaged reader | 85.6s | Passed |

Each writer has 14 passing assertion stages and each reader 15, including required
`dialogue-scroll`: 62 synthetic observations, duplicate-looking dialogue at distinct
coordinates, actual wheel scrolling, End/Home keys, page transitions both ways,
the final wrapped line of a long message, narrower-window reflow and onscreen fixed
controls. Source details remain inspectable explicitly. SQL asserts no raw column,
clean payloads, stable counts and privacy exclusions before/after restart; existing
batch/retry/undo/watch/backup/keybind/world/shutdown assertions also pass. Screenshots
were inspected at 854x480 and 640x480, with multiple dialogue messages visible,
readable clean text, a real scrollbar and reachable footer controls. No runtime
repair or assertion relaxation was needed. Graphics/audio/Realms warnings remained
nonfatal only because the required reports, assertions, shutdown and exits passed.

Evidence: `fabric/build/client-smoke/evidence/dialogue-smoke-1/` (JSON, JUnit XML,
selected logs, PNGs, launch/shutdown witnesses). Logs:
`/tmp/lotc-dialogue-core-3.log`, `/tmp/lotc-dialogue-integrated-1.log`,
`/tmp/lotc-dialogue-runner-tests.log`, `/tmp/lotc-dialogue-client-1.log`.
Actual legacy migration is covered separately by synthetic SQLite tests, including
multi-chunk cleanup, owned/unowned/undone observations, backup contents, injected
failure rollback and post-migration undo/restart; fresh client profiles do not
substitute for those upgrade tests.

GitHub Actions passed both **verify** and the complete **client-smoke** job on
functional commit `e2f1bfe`: [PR run 40214971685](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40214971685).
[PR #5](https://github.com/heyitskat777-netizen/mcchatmod/pull/5) builds on merged
import-workflow PR #4, targets `feature/client-smoke-tests`, and is ready for review.
Main remains unchanged. A documentation-only follow-up receives its normal CI
rerun; the successful functional run above identifies its tested commit precisely.

Installable artifact: `fabric/build/libs/lotc-character-journal-0.1.0-alpha.4.jar`,
14542781 bytes; SHA-256 `8d6be81128ef4a34b19f442344ac032d457da1852a8926d03dce55cdd60695a2`.
[CI JAR artifact ZIP](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40214971685/artifacts/12606823924).
Install the non-`-sources` JAR. The new alpha.4 user check is still pending:
[USER_TEST_DIALOGUE_SCROLL.md](USER_TEST_DIALOGUE_SCROLL.md). Kat's acceptance of
alpha.3 smoke tests is recorded above and does not imply alpha.4 user acceptance.

Live capture/reconciliation and measured private real-source acceptance remain
unfinished Phase 2 work. The following sections are historical evidence.

## Phase 2a import workflow — automated checks passed; user test pending

Branch: `feature/import-workflow`, based on `56fbd97`. Kat accepted all proposed
Phase 2 additions: folder preview and Import all, durable reports/retry, cross-source
channel filters/UNKNOWN review, batch-owned undo, and opt-in stable finished-folder
watch. They are now requirements, implemented on this branch. Preview does no writes; approved hashes are rechecked
before source creation. Durable schema-3 reports preserve policy/names for retry,
owned observation writes and removal accounting are transactional, and confirmed
watch opt-in uses two stable scans with durable unchanged-attempt suppression.
Cross-source/channel filters include an UNKNOWN review view. Phase 2b live capture
and Phase 2c private real-source acceptance remain unfinished.

Coordinator verification (Java/proxy/cache activation recorded below):

```sh
./gradlew check :fabric:build --rerun-tasks --no-daemon --console=plain
./gradlew :ingestion:test --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --preflight-only --run-id batch-graphics-1 --timeout 300
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --run-id batch-smoke-1 --timeout 300
```

The integrated build passed in **46s**, all **30 tasks executed**. Fresh XML records
**119 tests, 0 failures/errors/skips**: domain 1, application 9, parsing 39,
ingestion 34, storage 36. The affected ingestion rerun after adding batch PM/OOC
sentinel assertions passed in **30s** (9 tasks, 2 executed). All **14 Python runner
contract tests passed**. Package checks verify metadata and six runtime JARs,
including both new ingestion workflow services. Actual graphics-only preflight
passed in **74.8s**, exit 0, with fresh report/shutdown/screenshot evidence under
`fabric/build/client-smoke/evidence/batch-graphics-1/`.

Actual five-JVM suite **`batch-smoke-1` passed all phases**, with a 300-second bound
per JVM. This is executed client evidence, distinct from compilation:

| Phase | Elapsed | Result |
| --- | --- | --- |
| Graphics-only preflight | 74.5s | Passed |
| Development writer | 93.1s | Passed |
| Development reader | 71.0s | Passed |
| Packaged writer | 88.6s | Passed |
| Packaged reader | 65.3s | Passed |

Fresh evidence (JSON, JUnit XML, screenshots, selected logs, launch/shutdown
witnesses) is under `fabric/build/client-smoke/evidence/batch-smoke-1/`.
No automatic runtime repair attempt was needed; all assertions and timeouts stayed
in place. Existing nonfatal software-graphics/audio/Realms diagnostics did not mask
failures: all required body, report, shutdown and process-exit assertions passed.
Writers have 13 assertion stages; readers have 14. The new mandatory batch stage
asserts preview zero writes, known-source duplication without ownership, corrupt-file
isolation with PARTIAL status, failed-file retry without duplicate successful rows,
explicit undo confirmation, older-record preservation, payload-free removed-coordinate
accounting, original-file preservation, and persisted watch opt-in/disable through
actual widgets. A separate reader JVM asserts reports/undo/settings after restart.
Cross-source/channel and UNKNOWN controls are exercised too. All journal workers,
including batch/watch/clock workers, must stop. Screenshots of preview, transcript
and both confirmation warnings were visually inspected and readable at the tested
854x480 / normal GUI size. Screenshots alone are not pass criteria.

GitHub Actions passed both **verify** and **client-smoke**, including all five JVMs
and selected synthetic evidence uploads, on functional commit `b97a51a`:
[PR run 40169848197](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40169848197).
[PR #4](https://github.com/heyitskat777-netizen/mcchatmod/pull/4) stacks on
`feature/client-smoke-tests`, where historical-import PR #3 has been merged.
Main remains unchanged. Later documentation commits receive their normal CI rerun;
the successful run above identifies its tested functional commit precisely.
Logs: `/tmp/lotc-batch-integrated-2.log`, `/tmp/lotc-batch-privacy-tests.log`,
`/tmp/lotc-batch-runner-tests.log`, `/tmp/lotc-batch-graphics.log`,
`/tmp/lotc-batch-client.log`. New user/modpack testing is still pending; see
[USER_TEST_BATCH_IMPORT.md](USER_TEST_BATCH_IMPORT.md).

Independent read-only review found no remaining blocker after fixes for preview
UI state, fail-closed ownership adapters, watcher configuration recovery, exclusive
admission, durable failed-attempt suppression and compact confirmation layouts.
Undo is logical removal, not secure erasure; source checkpoints/backups retain
historical accounting. A watch stop between recording an attempt and dispatch may
conservatively suppress an unchanged file without creating a batch; explicit
Preview folder / Import all is the documented recovery path.

Native subagents are available: build owns domain/storage/schema/build changes,
verification owns batch/watch ingestion services and meaningful tests, and review
inspects integrity/threading/privacy without edits. The coordinator owns existing
importer hooks, client UI, documentation and integrated verification. Main remains
the reviewed baseline. Live capture/reconciliation and real-source acceptance
remain unfinished Phase 2 work.

Current local installable artifact: `fabric/build/libs/lotc-character-journal-0.1.0-alpha.3.jar`,
14529015 bytes; SHA-256 `ef0073f9cf361753cbacf1c921e245711c4dfaa6770ba51a1565548881155444`.
Use the non-`-sources` JAR in a copied instance. User testing of this new workflow
is pending; the earlier accepted Phase 1 report does not cover these additions.

Next unfinished Phase 2 work: live events/tailing, rotation and partial-line handling,
historical/live overlap reconciliation without text-only deduplication, private
manually checked real-log samples and measured performance budgets. No persona,
character identity, encounter or AI functionality is claimed. The remainder is a
historical verification record and does not override the current branch/results.

## Historical verification — Phase 1 accepted; original single-file importer

The user reports the requested tests completed with no issues: resizing, rebinding,
layout, backup, and return to normal play all work. This closes the Phase 1 user
smoke-test gate. The exact tested JAR hash/launcher/mod list was not supplied; do
not infer those details or general hardware/modpack compatibility beyond the report.

Work continues on `feature/log-import`, based on the smoke-suite branch. The first
Phase 2 slice is historical `.log`/`.log.gz` import plus transcript review. It adds
a bounded background importer, content identities, recoverable atomic checkpoints,
recognized-channel exclusions, literal text search, and schema-1 migration with a
verified backup. The planned development artifact is `0.1.0-alpha.2`.

Phase 2 remains incomplete: live capture, rotation/tailing, historical/live overlap
reconciliation, and a manually checked real-log acceptance test remain outstanding.
No character records/identity merges/persona memories/AI are claimed. See
[USER_TEST_IMPORT.md](USER_TEST_IMPORT.md) for the new user checklist and limits.

Native build/contracts/storage, parser/test, and read-only review agents worked
with explicit non-overlapping ownership. Independent review prompted fixes for
classification-policy changes on partial resume and bounded/cancellable raw gzip
headers (including skips). Canonical latest.log aliases are also refused. No
remaining production blocker was reported by independent review.

Coordinator verification:

```sh
./gradlew check :fabric:build --rerun-tasks --no-daemon --console=plain
python3 -m unittest discover -s scripts -p 'test_*.py' -v
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted --run-id import-smoke-1 --timeout 300
```

Use the Java/proxy/cache activation from the earlier record below. The integrated
build passed in **52s**, with **all 30 tasks executed**. Fresh JUnit XML confirms
**93 tests, 0 failures/errors/skips**: domain 1, application 9, parsing 39,
ingestion 20, storage 24. The **14 Python runner contract tests** also passed.
The original 18 parser expectations were preserved. New tests cover recognized
privacy formats, midnight/clock provenance, full validation before writes,
truncated gzip/UTF-8/partial-line/size bounds, header cancellation, source aliases,
atomic batch recovery/concurrency, policy conflicts, cancellation/queue shutdown,
backed-up migration, imported-data backup restoration, search and paging.

The coordinator's actual five-JVM client run **passed all phases**:

| Phase | Elapsed | Result |
| --- | --- | --- |
| Graphics-only preflight | 74.2s | Passed |
| Development writer | 74.5s | Passed |
| Development reader | 89.5s | Passed |
| Packaged writer | 75.9s | Passed |
| Packaged reader | 156.5s | Passed |

The writer exercises the real keybind and Log import controls, imports a synthetic
source, repeats it with unchanged counts, checks 2 distinct UNKNOWN records at
lines 1/2 with identical text, 2 recognized PM/OOC exclusions and 1 ignored line,
then tests transcript paging, no-match/matching text search, source/clock/raw
provenance and screenshots. The separate reader verifies the original source
bytes and imported database records before using the UI again. Both original
journal and import workers must stop. Development and packaged runtimes both
completed their existing layout/backup/key-rebind/world/shutdown assertions too.
The writer has 12 required assertion stages; the reader has 13. Screenshots were
visually inspected and are readable at the tested normal GUI size.

The packaged run loads ingestion and parsing from their nested JARs as well as
storage/domain/application/SQLite. Package verification now checks all six nested
runtime JARs. No real RP logs or user database was used in automated tests. Actual
Phase 1 schema migration is covered with synthetic schema-1 databases and verified
pre-migration snapshots; the fresh-profile client test does not itself test a real
user's database migration.

Successful local evidence is under
`fabric/build/client-smoke/evidence/import-smoke-1/` (JSON, JUnit XML, phase and
shutdown/launch witnesses, selected logs and PNGs). Core logs:
`/tmp/lotc-phase2-integrated.log`, `/tmp/lotc-phase2-verification.log`,
`/tmp/lotc-phase2-runner-tests.log`, `/tmp/lotc-phase2-client.log`.
Outputs/private data remain ignored and uncommitted. A final wording-only
clarification labels character search as future identity work; its affected
Fabric build is checked separately from the full runtime run above.

GitHub Actions also passed both jobs on functional commit `bebd523`:
[PR run 40151619378](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40151619378).
This includes actual development/packaged JVMs and evidence uploads, not just
compilation. Any subsequent documentation/wording commit receives its normal CI
rerun; this statement identifies the verified functional commit precisely.

Limitations and next work: finish the private real-log checklist in
[USER_TEST_IMPORT.md](USER_TEST_IMPORT.md), then implement live capture and
historical/live reconciliation (Phase 2b). Identical decompressed bytes reuse a
source even across plain/gzip/renames; different newline bytes, overlapping subsets,
or changed files remain distinct sources. Completed classifications stay frozen;
partial resumes require the original reviewed-name/policy fingerprint. Recognized
PM/OOC payloads never reach journal storage; unsupported formats can remain UNKNOWN
review records, never eligible for analysis. Dates/timezones remain unresolved.
No live LoTC testing, persona isolation, character identities or AI is claimed.

Current local artifact: `fabric/build/libs/lotc-character-journal-0.1.0-alpha.2.jar`,
14461590 bytes; SHA-256 `8eacdbb4131a10427dc71c9a07666dd5b4db1f6a9a9b5d17e3d7cff262afa9c4`.
The final wording-only Fabric build passed in 23s (19 tasks: 6 executed, 13 up-to-date).

The remainder is a historical record of the earlier foundation/smoke-suite work.
Its pending Phase 1 user-test notes are superseded by the user's acceptance above.

## Client smoke suite — local and GitHub Actions checks passed

Branch: `feature/client-smoke-tests`, stacked on foundation PR #1.
[Smoke PR #2](https://github.com/heyitskat777-netizen/mcchatmod/pull/2).
Target remains Fabric / Minecraft **1.21.11** / **Java 21**. Production feature
logic was unchanged; this expands the test harness, launcher, and CI evidence.

First, before edits, the coordinator re-established cloud graphics using the
existing real-client test under Xvfb/Mesa: **passed in 1m28s**, with fresh
screenshot and exit 0. Evidence: `/workspace/smoke-baseline-evidence`.

New coverage uses actual registered J input, synthetic local-world entry, typed
search text, layout/backup widgets, backup contents/independence, Done, J-to-K
rebinding, world exit, and worker shutdown. Writer/reader phases use separate
Minecraft JVMs sharing only their isolated profile, asserting a nondefault saved
setting in the reader. No direct journal/service construction substitutes for
those interactions. No live LoTC server or personal profile was used.

### Coordinator results

With the Java activation below, Xvfb :99, `LIBGL_ALWAYS_SOFTWARE=1`,
`ALSOFT_DRIVERS=null`, and writable XDG cache/config directories:

```sh
python3 scripts/client_smoke.py --eula-accepted --prepared --run-id cloud-smoke-5 --mode packaged --timeout 300
python3 scripts/client_smoke.py --eula-accepted --prepared --run-id cloud-smoke-7 --mode development --timeout 300
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

| Run | Graphics preflight | Writer JVM | Reader JVM | Result |
| --- | --- | --- | --- | --- |
| Packaged, cloud-smoke-5 | 95.6s | 123.6s, PID 30496 | 67.0s, PID 30833 | All passed |
| Development, cloud-smoke-7 | 75.7s | 65.0s, PID 32838 | 69.0s, PID 33175 | All passed |

Each application report has 11 passing assertion stages, successful body and
CLIENT_STOPPING witnesses, matching run/mode/phase/profile/PID, and a fresh PNG.
The actual packaged mod loaded from `fabric/build/libs/`; SQLite loaded from its
nested processed JAR. The supported Loom production launcher excludes all named
project outputs and standalone SQLite/core JARs. Launch manifests are validated,
so development dependencies cannot mask a missing packaged runtime dependency.
Screenshots were inspected; screenshots alone do not establish success.

**14 Python contract tests passed**, including missing/stale/zero/incomplete
reports, malformed evidence, failed assertions despite graphics warnings,
classpath masking, screenshot validity, and a real detached-child timeout. Linux
subreaper cleanup tracks owned descendants and PID start identity; it does not
kill arbitrary Java processes. Each JVM has a hard 300-second bound plus bounded
cleanup. Explicit tick waits and post-action stage duration budgets also apply.

Preparation/compilation passed with
`./gradlew :fabric:compileGametestJava :fabric:prepareClientSmoke --no-daemon --console=plain`
(39s, 17 tasks, 4 executed). The coordinator also ran the required integrated
`./gradlew check :fabric:build --rerun-tasks --no-daemon --console=plain`; it **passed in 56s**, with all **25 tasks executed** and **40 core tests,
0 failures/errors/skips** confirmed from fresh JUnit XML. Compilation and
runtime checks are separate evidence.

### Failure history and corrections

- Applied-script Loom imports failed before Java compilation. Resolving public
  task classes through the installed plugin classloader fixed it.
- The first production preflight rendered Minecraft but timed out with no test
  report. Fabric API's production aggregator omits its test modules. Explicitly
  installing the matching client/server gametest modules fixed harness loading;
  launch validation now requires those exact additional test-module categories.
- The next run executed successfully but failed the original mod-count witness;
  its launch contract was corrected to include the required test modules while
  still excluding standalone journal dependencies. This was not counted as pass.
- Development world entry timed out. The runner's small render profile was being
  reset by Fabric, so the harness now applies it after reset. A thread dump then
  captured test/server waits in Fabric scheduling. Its documented packet-sync
  disable option resolved the observed world-entry stall. Real world, key/input,
  database, and shutdown assertions and deadlines remain intact.
- Development reader then correctly failed nondefault persistence: Loom's
  `deleteGameTestRunDir` erased the writer's database. That cleanup is disabled
  only for runner-managed profiles. Run IDs still create fresh isolated roots;
  the corrected writer/reader pair passed without changing expectations.

Failed and blocked/skipped phase reports remain under the ignored evidence roots
`fabric/build/client-smoke/evidence/cloud-smoke-{1,2,3,4,6}/`; they were not
replaced with passing outcomes. This used diagnosed corrections, not unchanged
retry loops. No reference expectations or application assertions were weakened.

### CI, artifacts, and remaining scope

GitHub Actions now has an actual `client-smoke` job with Xvfb/Mesa, graphics-only
preflight excluding the journal, development write/read, and production-JAR
write/read. Job/step/process deadlines are bounded. It always uploads selected
JSON, JUnit XML, logs, and PNGs from evidence directories; profiles/worlds,
databases, Minecraft binaries, credentials, and arbitrary files are excluded.
The first two workflow versions failed the smoke job while core/build/runner
checks passed. The corrected complete workflow **passed** on functional commit
`9ffc3114060754387c6a9f85af62d0a8016d3bb6`: [PR run 40124599154](https://github.com/heyitskat777-netizen/mcchatmod/actions/runs/40124599154).
Both verify and client-smoke jobs succeeded, including the default five-JVM
plan and selected synthetic evidence upload. This is executed CI runtime evidence,
not just a workflow syntax check. Detailed
log download was blocked by the environment proxy; a draft domain addition for
`results-receiver.actions.githubusercontent.com` was saved, not asserted applied.

Local successful evidence: `fabric/build/client-smoke/evidence/cloud-smoke-5/`
and `cloud-smoke-7/` (including `summary.json`, `junit.xml`, phase reports, shutdown
and launch witnesses, logs, screenshots). See [CLIENT_SMOKE_TESTS.md](CLIENT_SMOKE_TESTS.md).

The runner is Linux-specific for scoped process cleanup; the mod's platform
support is unchanged. Actual launcher/modpack compatibility, hardware audio/GPU,
and a real user judging GUI scales/usability remain user tests. AI/capture/persona
features remain absent. EULA acceptance was supplied explicitly for this runtime
and represented by the runner flag/CI invocation.

A six-hour review/continuation cadence was requested. **No scheduling tool was
available and no recurring task was created.** The reusable prompt is saved in
[CONTINUE_PROJECT.md](CONTINUE_PROJECT.md); saving a prompt does not schedule it.

The remainder records the earlier foundation verification, before this expanded
world/keybind/restart/packaged coverage.

## Phase 1 foundation — automated checks passed; user testing pending

Target remains **Minecraft 1.21.11 / Fabric / Java 21**. Development is on
`feature/foundation-build`; `main` remains the reviewed baseline. No Phase 2
capture/import work has been started. Phase 0 has a compatibility target and
explicit defaults; real-log reference samples, actual launcher/modpack details,
and performance budgets still need user evidence. Subscription support is deferred.

| Phase | State |
| --- | --- |
| 0 — Discovery | Architecture and initial defaults established; real samples and modpack details pending. |
| 1 — Foundation | Core, packaging, and automated development-client shell tests passed. Actual modpack/keybind/process-restart smoke test pending. |
| 2 — Capture | Conservative parser prototype with synthetic tests only; no import pipeline or live capture. |
| 3–7 | Planned; not implemented. |

## Changes in this milestone

- Recoverable backup/save failures retain READY state and the last committed
  settings, allowing retries. Initialization failures remain FAILED.
- Forced/interrupted shutdown resolves accepted futures instead of abandoning
  queued requests. Closing rejects new work; queued writes are **not guaranteed
  to flush** when the journal closes. The shutdown wait remains bounded.
- Schema ownership/shape and saved values are validated before switching an
  existing database to WAL. Foreign databases claiming schema version 1 are
  rejected without changing their bytes.
- Backup paths are reserved atomically; concurrent attempts cannot replace the
  winner's snapshot. Incomplete output owned by a failed attempt is removed.
- The shell displays the actual mod version and storage/AI/capture diagnostics at
  the tested normal GUI size. Controls start with correct readiness and stay
  disabled during operations; failure messages permit retry.
- Architecture checks cover Fabric client sources and production project
  dependencies. `check` compiles client tests and verifies metadata, client
  classes, bundled runtime JARs, and absence of test/database payloads in the mod.
- The Gradle 9.7.1 wrapper distribution has its official SHA-256 pinned.
- AGENTS.md preserves the coordinator/three-native-subagent workflow and ownership.

## Delegation and review

Native subagent tools were available and used. Build agent owned build/config
files; verification agent owned Java tests/synthetic fixtures; review agent
independently inspected modularity, integrity, threading, and privacy without
edits. Coordinator owned production fixes/docs/integration and ran the integrated
checks. Shared Gradle runs were sequenced. Independent final review reported no
remaining merge-blocking defect. No tests or checked expectations were weakened.

## Reproduce the cloud checks

Working directory: `/workspace/mcchatmod/lotc-character-journal`.
Current tested tools: Temurin **21.0.13+9**, Gradle **9.7.1**, Loom **1.14.10**,
Fabric Loader **0.19.5**, Fabric API **0.141.6+1.21.11**, SQLite JDBC **3.51.3.0**.
Local machines only need JDK 21 and the wrapper; cloud-specific activation is:

```sh
export JAVA_HOME=/workspace/toolchains/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/workspace/.gradle
export JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
./gradlew check :fabric:build --rerun-tasks --no-daemon --console=plain
```

Coordinator result: **passed in 55 seconds, all 25 tasks executed**. JUnit XML
from this run reports **40 tests, 0 failures, 0 errors, 0 skipped**:
application 9, storage 12, parsing 18, domain 1. Eleven new regression tests cover
worker isolation, pre-start recovery, failed-save/backup retry, interrupted close,
foreign/malformed schemas, concurrent backup reservation, existing-empty target
preservation, and incomplete-backup cleanup. Existing parser/privacy tests remain.
No baseline red-to-green test run was claimed: production fixes and tests were
integrated before the new regressions were first executed.

Agent checks (before coordinator integration):

```sh
./gradlew verifyArchitecture :fabric:verifyPackagedMod --no-daemon --console=plain
./gradlew :application:test :storage:test :fabric:compileGametestJava --no-daemon --console=plain
```

Both passed. Build agent's two isolated synthetic negative fixtures correctly
rejected a forbidden Fabric client import and Fabric-to-parsing project dependency.
`git diff --check` passed. The ordinary Fabric unit-test task has `NO-SOURCE`;
that task alone is not a client-test pass.

## Executed automated Minecraft client test

The user explicitly accepted the Minecraft EULA for this development test runtime
in this session. A Debian-signed Xvfb package was downloaded and extracted under
`/workspace/runtime/xvfb` without system installation or disabling verification.
Mesa software rendering and the existing X/GL libraries provided the display.
Start the retained Xvfb binary in a separate process/session before the check:

```sh
/workspace/runtime/xvfb/usr/bin/Xvfb :99 -screen 0 1280x720x24 -nolisten tcp -ac -noreset
```

With the Java activation above, the coordinator ran:

```sh
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 timeout --signal=TERM --kill-after=15s 600s ./gradlew :fabric:runClientGameTest --no-daemon --console=plain
```

**Passed**, exit 0, 4m18s including initial asset downloads. The registered
`JournalClientGameTest` actually executed: its fresh screenshot was produced at
`fabric/build/run/clientGameTest/screenshots/0000_lotc-journal-foundation.png`.
The test exercises the real shell, search field presence, non-pausing behavior,
layout/backup buttons, SQLite snapshot contents/independence, Done, and service +
store reconstruction followed by reopening the persisted layout. The runner's
completion semantics were checked against Fabric's source; no test-failure
exception occurred. The screenshot was visually inspected and is readable at the
tested 854×480 image size. It contains only the synthetic/empty journal shell.

This is an **executed development-client test**, not just compilation. It does
not test the real keybind in a world, the remapped JAR installed in a separate
launcher, a second Minecraft process reopening the same database, or the user's
actual modpack. Those remain in [USER_TEST_PHASE1.md](USER_TEST_PHASE1.md).
No live LoTC server was contacted. No AI/provider integration or captured dialogue
exists, so capture accuracy and persona isolation remain future acceptance gates.

Non-fatal runtime diagnostics: no physical audio device (sound disabled), blocked
optional Mojang public-key/profile lookups for the development player, unavailable
X11 cursor shape, and unwritable default Mesa cache. These did not prevent test
execution. No real authentication or private data was used. Audio, cursor behavior,
and hardware rendering are not validated by this software-rendered run.

## Artifact and evidence

Development JAR: `fabric/build/libs/lotc-character-journal-0.1.0-alpha.1.jar`.
Size: **14,420,066 bytes**.
SHA-256: `4ebd5dcabb16df3c4923dbbcf715ebb9aa7b0571f3e7be27d09b37499610ddf4`.
Package verification checks expanded metadata and nested domain/application/storage/
SQLite JDBC classes. It does not substitute for installing that JAR in a launcher.
Build warnings remain: Gradle deprecations and SQLite's four-part version is not
semver. Dependencies were not upgraded to suppress warnings.

Machine-local logs: `/tmp/lotc-integrated-checks.log`,
`/tmp/lotc-client-gametest.log`, `/tmp/lotc-verification-final.log`. Current JUnit
XML is under each core module's `build/test-results/test/`. Generated outputs,
logs, screenshots, game files, and databases are ignored and are not committed.
The earlier source-upload verification record is in Git history; its socket and
Fabric-host network blockers were resolved in this environment.

## Remaining work and environment state

1. Review this foundation change through a feature-branch pull request.
2. Run the concise Phase 1 user checklist in the actual modpack: keybind/rebind,
   GUI-scale/resizing, complete restart/persistence, backup, close/join/leave.
3. Record launcher/mod list and findings; fix blockers before declaring Phase 1
   complete or starting Phase 2. Real sample review/performance targets remain open.

The environment draft retains Java/build instructions and required Fabric/Mojang
network destinations, and now includes reusable Xvfb installation and process
startup instructions. Saving this draft does not publish the prepared environment
or preserve a running display process. GitHub API access initially returned proxy CONNECT 403;
adding `api.github.com` to the custom allowlist resolved it, and repository API
access was verified. No new credential requirement was needed. Publication and
fresh-task snapshot restoration have not been asserted or independently tested.
