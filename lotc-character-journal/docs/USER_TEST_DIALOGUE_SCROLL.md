# Alpha.4 dialogue and scrolling check

Kat accepted the alpha.3 smoke tests. This checklist covers the requested refinement.
Use a copied instance/database and finished logs. Keep alpha.3 and its database
backup available; a schema-4 database cannot be opened by alpha.3 directly.

Before upgrading, finish incomplete alpha.3 imports. The new parser policy refuses
incompatible old partial resumes. To finish one later, use alpha.3 with the
pre-upgrade database backup, complete it there, then upgrade that copy again.

1. Open an existing import after installing alpha.4. Logging prefixes, thread/INFO,
   `[System] [CHAT]`, recognized leading IC channel tags such as `[Quiet]`, embedded
   log clocks and color codes should not appear in the dialogue. Speaker names,
   emotes, punctuation and quoted markers inside actual speech must remain.
2. Search a familiar conversation. Scroll through several messages with the wheel,
   drag the bar, try arrows/Page Up/Page Down/Home/End, and cross a 50-message page
   boundary. Read the end of a long message. Resize the window: controls should stay
   visible and scrolling should remain anchored near the same message.
3. Toggle Details to inspect source line/clock and uncertainty, then hide it again.
   Search, channel/source filters and UNKNOWN review should still work. Restart and
   re-import an identical source: cleaned records should persist without duplication.
4. Check the previous batch reports/undo on disposable data. Migration may reduce
   imported/added counts when moving known system/empty messages to ignored, or
   newly recognized private content to excluded. Processed positions and original
   files stay unchanged. The pre-schema4 backup preserves the old database.

The current transcript table stores one cleaned dialogue payload and separate
provenance metadata; it has no raw logger-line column. Old backups, SQLite history
and source files can retain previous bytes. This is not secure erasure. Unsupported
formats stay uncertain rather than being guessed; do not post private dialogue in
bug reports. Live capture/persona/AI features remain outside this refinement.
