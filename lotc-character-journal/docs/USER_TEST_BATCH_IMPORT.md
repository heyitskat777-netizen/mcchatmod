# Phase 2 folder import user check

Target: Fabric / Minecraft 1.21.11 / Java 21. Check STATUS.md for the exact
verified build. Use a separate instance/profile and a copy of your log folder;
keep a journal backup before trying migration or undo with valuable data.

1. Press your journal key → Log import → Import all / dashboard. Enter the
   folder path and optional reviewed observed names. Preview first: compare the
   file counts, known sources and byte estimates. Nested folders, links and active
   latest.log are excluded. Preview must not add transcript records.
2. Confirm Import all. Include a finished plain log, its gzip duplicate and a
   deliberately corrupt archive. Inspect the dashboard: the valid file should
   finish, the duplicate should add no records, and the corrupt file should fail
   independently. Retry failed should preserve successful records and counts.
3. Open View transcripts. Cycle All sources / individual sources and channels;
   try UNKNOWN review and literal search. Check cleaned dialogue and source
   line/clock in Details (alpha.4 no longer stores a raw logger copy). Uncertain channels remain review-only; no date, timezone or character
   identity is invented. Check PM/OOC exclusions privately against the source.
4. Restart and inspect saved reports. A cancelled/interrupted batch must wait for
   an explicit retry; it must not silently start scanning. Changed approved files
   require a fresh preview instead of silently substituting different content.
5. Choose a test batch and Undo batch. Check the warning before Confirm undo.
   Only that batch's newly inserted observations should disappear; older imports
   and original files should remain. Removed coordinates stay accounted for,
   and repeating the already completed source does not silently restore them.
   Undo is terminal for that batch; restoring removed records currently requires
   a suitable database backup, not a second click on Import all. Undo is logical
   record removal; existing backups and SQLite history can still contain earlier
   bytes. It is not a secure-erasure operation.
6. Enable folder watch only if wanted, and confirm. It checks the selected folder
   every 30 seconds, including after restart, and waits for two unchanged scans.
   Add a completed log and check its durable report. An actively changing file or
   latest.log must not import. Stability is a size/mtime/file-identity heuristic,
   not a lock proving another process has finished writing; select an archive
   folder rather than a folder of actively written custom logs. Disable watch and
   verify the choice after restart. Unchanged failed/attempted files are suppressed
   across restarts; retry their saved batch explicitly. A stop or configuration
   change between recording an automatic attempt and dispatch can leave no batch;
   use Preview folder / Import all to process that file manually.
7. Resize at normal and smaller GUI scales, close all screens, resume play, and
   leave the world. Report the build/version, expected and actual counts, safe
   error codes and source IDs. Keep private raw dialogue out of public issues.

Watching finished archives is separate from live chat capture/tailing. Persona
memory, character attribution, encounter analysis and AI are later phases.
