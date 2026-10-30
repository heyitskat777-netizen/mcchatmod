## Problem and resulting behavior

An archive writer reservation lasting over five seconds permanently stopped live
capture, even after its filtered event had been forced to the recovery journal.
Alpha.15 retries temporary BUSY/LOCKED live operations with the same event/session
IDs; requests remain Pending and become Saved only after SQL acknowledgement.

Live operations use 250ms SQLite waits, a 180s retry window and interruptible 50ms
backoff. Normal storage keeps 5000ms waits. Retries cover lifecycle/pause/replay,
never repeat journal force/sequence allocation, and exclude permanent failures.
Queue limits and the existing 10s close budget remain. Rejected lifecycle controls
fail closed; the adapter waits for real start success before draining messages.
Healthy state transitions cannot overwrite concurrent terminal control failures.
Worker-owned finalization handles shutdown interruption and settles futures exactly
once. Schemas, parser policy and dependency pins are unchanged.

## Verification

- Reproduced alpha.14: BUSY failure after 5031ms, ERROR/Saved 0, one forced event;
  restart replay recovered exactly one observation.
- 365 core tests, including 11 new real-lock/retry/lifecycle/rollback/recovery cases: PASS.
- 15 runner tests, full Fabric build/preparation, architecture/dependency checks: PASS.
- Final actual Minecraft graphics, development write/read and packaged-JAR write/read: all PASS, writer 28/reader 29 assertion stages and acknowledged shutdown.

Full commands, failures/repairs and actual outcomes are in docs/STATUS.md and
`docs/verification/live-contention-results.json`. The original tests/limits are
preserved. No in-game result is inferred from compilation.

## Limits and user checks

Successful fresh-actor indexing and uninterruptible file I/O are outside the
contention-wait guarantee. Queue overflow remains explicit; only forced journal
records receive the replay guarantee. Test ordinary capture while editing aliases
or refreshing scenes, subsequent exact repeated messages, connection separation,
restart and pause/resume using docs/USER_TEST_ALPHA15.md. Real-modpack acceptance
remains separate.
