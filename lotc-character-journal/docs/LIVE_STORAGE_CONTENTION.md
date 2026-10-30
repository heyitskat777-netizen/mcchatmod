# Live recording during database contention

Alpha.15 treats temporary SQLite writer reservations separately from permanent
storage/provenance failures. This continues Phase 4 validation; it does not finish
real-scene/modpack acceptance or add AI functionality.

The alpha.14 reproduction held an archive write reservation before receiving one
synthetic RP message. After 5,031ms, SQL returned BUSY and capture entered ERROR
with Saved 0. The filtered event had already been forced to its JSONL journal;
releasing the reservation and restarting recovered exactly one observation.

## Retry contract

All five LiveRepository operations may report the SQL-free domain
`StorageBusyException`. Only SQLite primary BUSY/LOCKED result codes 5/6 (including
extended codes) receive that classification. Messages/exception text do not select
retries. Missing/mismatched storage members, malformed input, provenance conflicts,
constraints, disk errors and other failures remain permanent.

A live storage connection installs its 250ms busy timeout before opening/configuring
the pair. The normal storage timeout stays 5,000ms; attached rollback journals,
FULL synchronization, pair validation and atomic checkpoints remain intact. Each
failed transaction rolls back and closes before the ingestion worker retries.

The existing bounded live worker retries individual idempotent repository calls,
with the same session/event identities, a 50ms interruptible backoff and a 180-second
retry window. It never repeats parsing, journal append/force, rotation, sequence
advancement or readable-log output. Saved increments only after SQL acknowledges
the event. Requests waiting for SQL remain Pending. Retry exhaustion reports
`CAPTURE_DATABASE_BUSY_TIMEOUT`; forced events remain available for owned replay.

The retry window limits admission of additional attempts, not successful query or
file-I/O execution time. A new live actor can still trigger expensive archive
indexing. This change does not establish a universal shutdown/latency bound during
successful discovery, uninterruptible file I/O or arbitrary provider code.

## Queue, connection and shutdown boundaries

The existing 256-request queue remains bounded. Receiving overflow remains an
explicit queue error; unqueued/unforced messages have no crash-recovery guarantee.
A rejected lifecycle/control request is terminal and fails closed with
`CAPTURE_CONTROL_QUEUE_FULL`, requiring restart. It must not allow a later world's
messages to reuse an older session. The Fabric adapter waits for successful start
completion before accepting its readiness-buffer messages into capture. Healthy
state transitions cannot overwrite a terminal failure from concurrent queue
rejection; a late successful database begin does not license capture afterward.

Closing stops long retries; cleanup gets at most one short database attempt. The
worker retains ownership of journal/profile files until its termination cleanup.
An executor shutdown interrupt is temporarily cleared only during that cleanup,
then restored, so a normal close can force its END marker. The existing ten-second
close budget includes actual worker-thread join; timeout reports an error and
settles discarded request futures/counters without releasing files from the caller.
Failed, forced-only sessions remain replayable and do not claim SAVED or clean SQL
closure. No limit or assertion is increased to accommodate retries.

## Evidence

Core tests use real SQLite reservations and callback/latch witnesses, including a
lock held beyond the former five-second failure point, late character-member
rollback, exact repeated-message receipts, lifecycle/replay retries, permanent
failures, retry exhaustion, shutdown and full-control-queue boundaries. Synthetic
fixtures only. Commands and actual results are recorded in [STATUS.md](STATUS.md).

[USER_TEST_ALPHA15.md](USER_TEST_ALPHA15.md) covers real-profile checks. An
artificial lock is not a measurement of fresh-character import throughput or a
promise that an arbitrarily busy/high-volume archive can never overflow its queue.
