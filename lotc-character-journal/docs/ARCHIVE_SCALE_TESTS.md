# Synthetic archive scale verification

This is an opt-in Phase 4 storage measurement, separate from Fabric compilation,
automated Minecraft client tests, and real-log/modpack acceptance. It uses a new
isolated pair of SQLite files and synthetic dialogue only. It never reads your
Minecraft profile, talks to a server, or requires the Minecraft EULA.

Run with Java 21 from `lotc-character-journal`:

```sh
./gradlew -PcoreOnly :storage:scaleTest --no-daemon --console=plain
```

Add `--offline` when the pinned dependencies are already cached and the environment
cannot reach their repositories. Ordinary `check` excludes the `scale` JUnit tag.
The dedicated task always reruns the workload; cached results cannot establish a
new measurement. The separate `Synthetic archive scale` GitHub Actions workflow
runs for relevant source/build changes and supports manual dispatch.

## Fixed workload and acceptance criteria

The initial reference workload is 100,000 historical dialogue observations in
100 sources, 2,000 saved character identities, 1,000 qualifying scenes, and 100
independent shared-alias candidate pairs. Fixture setup and imports go through the
production repository APIs. All actors are saved before the reference import;
these timings measure an archive with known characters. They do not measure the
repeated discovery/backfill cost of a first import containing new actors in each
batch. Appends obey the production 500-coordinate batch bound.
Alias backfill, transcript paging/search, scene refresh/search/detail, identity
queue construction, persona-scoped reads, and paired backup/reopen are measured
separately. Exact expected counts come from the synthetic fixture contract rather
than a preceding read of the implementation's result.

Checks preserve repeated messages at different coordinates, original source
payloads/cursors, idempotent refresh/reimport behavior, explicit persona ownership,
and paired backup integrity. Search inputs include literal pattern characters;
unassigned evidence must not become persona knowledge. Queue output must stay
bounded and contain distinct eligible pairs.

This is a storage-port workload: it supplies cleaned ROLEPLAY rows and exclusion
counters directly, rather than running a `.log` file through the parser. It does
not establish end-to-end import throughput or scale coverage for UNKNOWN
classification. Parser/privacy/UNKNOWN behavior retains the separate core
regression tests; synthetic exclusion counters alone do not prove filtering.

Budgets were selected before the reference run: 300 seconds each for ingestion
and full alias reindex (including the notes-only save baseline), 180 seconds per
scene refresh and original/backup reopen, 120 seconds for setup, and 60 seconds
per remaining measured operation.
The JUnit suite has a 15-minute hard deadline; Gradle and CI have longer enclosing
timeouts so normal failures can write their evidence. These generous limits detect
hangs and severe regressions. They are not interactive latency targets or a promise
about a user's hardware. A failed assertion remains a failure even when timing is
within budget. Stage durations include their correctness assertions and validation
overhead; they are not isolated microbenchmark timings. Screenshots are not used
as acceptance evidence here.

## Evidence and interpretation

`storage/build/reports/scale/archive-scale.json` records workload counts, Java/OS
metadata, operation timings and pass/fail status. It is written as work progresses
so a normal failure retains completed measurements. A force-killed process may
leave only the last completed checkpoint. JUnit XML and HTML are under
`storage/build/test-results/scaleTest/` and
`storage/build/reports/tests/scaleTest/`; GitHub uploads these selected reports as
`storage-scale-reports`. No SQLite files or transcript payloads are uploaded.

Actual results and unresolved limits belong in [STATUS.md](STATUS.md). A synthetic
single-writer pass does not establish real conversation grouping accuracy, Windows
modpack performance, or live-writer health during long transactions. The existing
isolated Minecraft suite remains the source for actual launch/keybind/widget and
packaged-JAR results. The baseline found a shared-scene search performance defect repaired in alpha.14.
See [USER_TEST_ALPHA14.md](USER_TEST_ALPHA14.md) and the broader
[USER_TEST_ALPHA13.md](USER_TEST_ALPHA13.md) for real-profile acceptance.
