# Alpha.15 — live recording during database contention

Minecraft 1.21.11 / Fabric / Java 21. Install only
`lotc-character-journal-0.1.0-alpha.15.jar`, replacing the prior journal JAR. Keep
your usual paired backup; schemas and parser policy are unchanged from alpha.14.

1. During normal recording, edit an existing character's aliases or refresh a
   larger scene archive. Continue ordinary RP. If the database is temporarily busy,
   Pending may rise; Saved should advance after the operation finishes. A temporary
   writer reservation should not immediately stop capture after five seconds.
2. Check the received dialogue afterward, including genuinely repeated emotes.
   Restart and verify they stay present once per received event. PM/OOC/noise
   filtering must still apply before the mod's journal files or database.
3. Leave and join a different world/server session while work is pending. Check
   later dialogue receives a new session/source rather than the old connection's
   provenance. Do not stress-test the live LoTC server; use a disposable local
   world/profile for deliberate queue or lock scenarios.
4. Close Minecraft normally and reopen. Check readable logs, exact dialogue,
   character/persona separation and capture pause/resume. If the queue overflows,
   a lifecycle request is rejected, or the retry window expires, expect an explicit
   error. Restart recovery protects forced journal records; queued/unforced data
   is not guaranteed after a crash or forced shutdown.

Record the approximate archive size, operation, observed Pending/Saved counts and
any error code. Artificial-lock automated tests and packaged-client tests are
separate from your real installation's acceptance.
