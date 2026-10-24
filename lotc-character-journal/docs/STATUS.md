# Current status — October 24, 2026

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
