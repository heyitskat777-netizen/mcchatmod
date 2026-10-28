# Automated client smoke tests

Target: Minecraft **1.21.11**, Fabric, **JDK 21**. This suite uses synthetic local
worlds and generated test profiles. It never connects to LoTC or loads a personal
Minecraft directory. It does not establish compatibility with the user's modpack.

## Run

Build and core checks remain:

```sh
./gradlew check :fabric:build
python3 -m unittest discover -s scripts -p 'test_*.py'
```

The automated runner currently supports Linux for its scoped process cleanup;
this does not change the mod's platform support. Provide an X display with OpenGL support. GitHub Actions installs Xvfb
and Mesa, starts a virtual display, and runs the suite with software rendering.
For an existing display, activate JDK 21 and run:

```sh
LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted
```

Pass `--eula-accepted` only after accepting the [Minecraft EULA](https://www.minecraft.net/eula).
Kat accepted it for this development test runtime in the setup session; the
explicit flag documents that acceptance for a run. `--preflight-only` establishes
the graphics-only launch before application testing. Use `--mode development` or `--mode packaged` to repeat only the affected mode
(with a fresh graphics preflight). `--timeout 300` sets the
bounded per-launch timeout; preparation is bounded separately. Do not retry a
failure without reading its report and diagnosing it.

The runner prepares its required Gradle outputs itself. `--prepared` is for an
already completed `./gradlew :fabric:prepareClientSmoke` preparation. Do not use
it with stale outputs. The Gradle client tasks are implementation steps and need
the runner's isolation/report properties; use the Python entrypoint for complete
validation rather than treating a standalone task exit as a suite pass.

## Coverage and isolation

1. A production-namespace graphics preflight launches the test harness with
   Fabric API but without the journal. Failure here cannot be attributed to the
   journal. It must establish a working Minecraft title screen/rendering context.
2. The development writer enters a generated local world, uses the registered
   journal keybind, interacts with widgets, saves settings, creates/checks a
   backup, closes the screen, leaves the world, and shuts down.
3. A second development JVM opens the same test profile and verifies the saved
   settings before exercising the journal again.
4. The packaged writer/reader repeat those checks through Loom's supported
   production-client task, using the remapped mod and its nested dependencies.
   Production runs exclude development application/domain/storage outputs and
   external SQLite JDBC so those cannot hide missing bundled classes.

Both development and packaged runs also assert the physical archive/character
SQLite split, historical/live character discovery, filtered related dialogue,
paired backups, persistent reversible link denials, durable message-tag reviews and explicit persona
isolation. The tag-review stage includes multitag drafts, resize, save/reject/restore,
a simulated newer review revision, stale-save rejection and explicit reload. The scene-review stage adds saved metadata/participants, resize, exact-message
split, confirmed combine, durable skip, restart and paired-backup checks. Writers
require 26 stages; readers require 27 including restart evidence. Worker liveness at shutdown remains a strict
assertion, separate from Gradle process exit.

Each suite run has a unique run ID. Graphics, development, and packaged game
directories live separately under `fabric/build/client-smoke/profiles/<runId>/`.
The writer and reader within a mode intentionally share a profile; different
modes/runs do not. Do not point these tasks at an existing personal profile.

## Evidence and failures

Selected logs, assertion reports, JUnit XML, and screenshots go under
`fabric/build/client-smoke/evidence/<runId>/`. GitHub Actions uploads the evidence
even on failure. It does not upload game directories, world saves, databases,
Minecraft binaries, account credentials, or arbitrary user files.

A screenshot alone is not a pass. The runner checks process exit, report identity,
required phases/stages, explicit assertions, and clean-shutdown evidence. Missing,
stale, incomplete, and timed-out outcomes fail the job. Graphics/preparation
failures are reported separately from application assertion failures; optional
audio/profile lookup/cursor warnings do not excuse an application failure.

The client has a dummy audio device in CI, and Mesa uses software rendering.
The pinned Fabric test packet synchronizer is disabled using its documented
`fabric.client.gametest.disableNetworkSynchronizer` option: initial local-world
loads stalled with it enabled. Real world-entry, key/input, database, and shutdown
assertions remain active; their state waits do not rely on that synchronizer.
Stage durations are post-action budgets; explicit tick waits and the hard process
deadline enforce active bounds.

These checks do not validate hardware audio, GPU-driver compatibility, other mods,
real-server capture or modpack behavior, real-persona knowledge accuracy, or AI features. See [STATUS.md](STATUS.md) for
actual commands/outcomes and [USER_TEST_PHASE1.md](USER_TEST_PHASE1.md) for the
remaining user test.

## Phase 2 batch workflow extension

The mandatory `batch-workflow` stage exercises real folder-path widgets, preview
without writes, confirmation, corrupt-file isolation, duplicate recognition,
retry, two-step undo and watch opt-in. The reader JVM checks durable undone
status, removed-coordinate accounting, older observations and saved watch intent,
then disables the watcher through the UI. Cross-source/channel controls and the
UNKNOWN review queue are also asserted. Screenshots capture preview and both
confirmation warnings. All `lotc-journal-*`, `lotc-identity-*` and `lotc-scene-*` workers must stop on shutdown.
Unit tests separately exercise stable-file watch polling, bounded/cancelled work,
content changes, retries and transactional storage invariants. These are not a
substitute for the executed client stage or private real-log acceptance.

## Dialogue scroll and schema-4 refinement

The mandatory `dialogue-scroll` stage uses 62 synthetic dialogue observations,
including identical lines and a long final message. It asserts multiple messages
on screen, actual mouse-wheel movement, End/Home keys, crossing the 50-message page
boundary in both directions, reaching the final wrapped line, a narrower window,
fixed controls, and optional source details. SQL asserts cleaned dialogue with no
raw column, excluded private payloads and stable source coordinates/counts on repeat
import/restart. These explicit requirements replace the old one-record UI navigation
expectations because the user requested a scrollable conversation. Privacy gates
and runner exit/report/shutdown requirements are preserved. Schema-1/2/3 migration,
cleanup failure rollback and ownership/undo accounting are tested separately using
synthetic SQLite fixtures; a fresh client profile is not a legacy migration test.

## Roleplay classification and noise regression

The mandatory `roleplay-filter` stage imports 62 synthetic eligible observations
(52 with positive IC evidence, 10 unmarked UNKNOWN), 13 representative system
notice templates, two private/OOC lines and one non-chat line. It asserts 78 source
lines = 62 imported + 2 excluded + 14 ignored, clean exact payloads, no inferred
speaker identities, Roleplay only / UNKNOWN review / All UI counts, repeat import,
and restart persistence in development and packaged JVMs. Existing scroll, backup,
batch, undo, watch, world, keybind and shutdown criteria remain. Unit tests separately
check every notice family against quoted/attributed/near-miss dialogue, privacy
precedence, schema-4 cleanup with no raw column, rollback and legacy uncertainty.

## Alpha.6 favorites assertions

Mandatory `message-favorites` exercises main-screen View transcripts, an exact
first-coordinate favorite among repeated-looking dialogue, Favorites: Only,
actual star mouse input, removing the last favorite into a safe empty view,
restoring the star and returning directly to the main journal. Reader JVMs assert
the saved exact key before mutation. This runs in both development and packaged
profiles. Synthetic fixtures and existing privacy/channel/scroll/undo checks remain.
Schema-5 upgrades, restored backups, owned favorite cascade/rollback and filter
intersections are separately verified by SQLite tests, not inferred from fresh
client profiles. This build does not capture live chat or create scenes/identities.

## Alpha.7 live/persona stages

Required `message-row-selection` clicks a wrapped third message after real scrolling
and checks its exact bookmark coordinate. `live-startup` proves automatic start and
persisted explicit pause. `live-capture` invokes actual Fabric CHAT and non-overlay
GAME listeners in an isolated world, tests repeated RP, PM/OOC/notices/overlay gates,
checks both dated file formats, and reimports a closed journal through UI without
changing original bytes or duplicating event IDs. `identity-personas` creates two
characters/personas, explicitly assigns two individual messages, and browses the
restricted memory screen. Reader JVMs verify persisted decisions before mutation.
The shutdown witness checks live and identity workers as well as prior journal I/O.
Actual subprocess kill/halt recovery, queue/disk/rotation faults and persona SQL
isolation are core tests; screenshots or successful compilation do not replace them.

## Alpha.8 character browsing regression

`character-browser` is mandatory in writers/readers for development and installed
JARs. It creates without immediately choosing a persona, returns to visible saved
rows, searches alias/no-result/clear states, opens the non-first saved UUID, keeps
an edited draft through 640x480 resize, saves and activates that exact persona,
then reopens/filters/selects another character and checks no duplicate identities
or personas were created. Reader JVMs reuse persisted records. Existing Assign and
persona memory assertions follow. Lossless full-size/escaped alias form handling
is separately covered by application tests. Native schema and parser policy stay 8/4.
