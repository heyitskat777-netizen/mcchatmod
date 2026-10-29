## Problem and change

Searching a large scene archive repeatedly scanned every character-evidence row
for each nonmatching scene. The new fixed-workload check reproduced a 466.454s
list/search stage against its unchanged 60s ceiling. Alpha.14 restricts shared
actor evidence to scene coordinates before UNION, preserving reviewed tags,
manual participants and persona scopes with existing indexes. Metadata-only
character saves avoid a redundant archive-wide reindex while name/alias edits
retain full backfill under the same write transaction.

Add an opt-in storage
scale suite using 100,000 synthetic dialogue observations, 2,000 pre-saved
characters, 1,000 scenes and 100 independent shared-alias candidate pairs.

The suite uses production storage APIs, checks exact coordinates/counts, alias
backfill, literal search/paging, scene idempotence, identity queues, persona
isolation and paired backup/reopen. Reports include timings, fixed ceilings,
workload/environment metadata and explicit limitations. The dedicated CI workflow
runs on relevant source/build changes and uploads JSON, JUnit and HTML reports.
Ordinary checks exclude the expensive scale tag; timing runs cannot reuse cached
results or an old JSON report. Runtime version is now alpha.14; schemas and parser
policy are unchanged.

## Verification

- Full Fabric build/preparation, architecture and packaged dependencies: PASS.
- 354 core tests and 15 runner tests: PASS.
- Real graphics, development write/read and packaged-JAR write/read: all PASS;
  writers 28/readers 29 explicit stages and acknowledged shutdown.
- Final fixed 100k scale suite: PASS 16 stages in 4m08s, original ceilings unchanged.
  Scene list/search/paging 466.454s→0.729s; notes-only save 108.811s→0.516s. These stage
  timings include validation overhead, not isolated microbenchmarks.
- Payload digest, exact counts, favorites, persona assignments and paired backup/
  reopen integrity preserved. Full evidence and the retained failed baseline are
  recorded in docs/STATUS.md and docs/verification/archive-scale-results.json.

## Limits and user testing

This known-character storage workload does not measure parser throughput, fresh
actor discovery, concurrent live writers or Minecraft/Windows modpack latency.
It cannot establish real-conversation grouping accuracy. Continue alpha.13's
focused home-search, identity-queue, scene-minimum/confidence, correction and
restart/paired-restore checklist if still pending. Alpha.14 adds a focused
participant/text/missing-name scene-search checklist for the real archive.

CI for runtime commit 462cffa passed both Build and check
(run 40820641449, verify+client-smoke) and Synthetic archive scale (run 40820641520).
See STATUS.md for downloadable evidence and installable alpha.14 artifact.
