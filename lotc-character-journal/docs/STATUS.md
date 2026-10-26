# Current status — October 25, 2026

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
