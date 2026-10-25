# Historical import test — Phase 2a

This build adds historical imports and transcript inspection. Live capture,
character identities/personas, previous-database import, and AI remain future work.
Use Minecraft 1.21.11 / Fabric / Java 21 and the `0.1.0-alpha.2` development JAR.

1. Open the journal with its configured key and click **Log import**.
2. Enter a finished `.log` or `.log.gz` path. Relative paths start at the Minecraft
   instance directory, for example `logs/2026-10-24-1.log.gz`. Absolute paths also
   work; enter them without surrounding quotes. The importer reads only that file.
3. Leave **Reviewed names** blank for the first test. Unrecognized names/channels
   remain UNKNOWN, with no identity or persona assignment. This is expected.
   Optionally provide names you have actually observed, comma separated, before
   first import; those are observations, not character links.
4. Click **Import log**. Check processed, stored, excluded, and ignored counts.
   Open **View transcripts**. Inspect source label/fingerprint, original line
   number, log clock, channel, and original text. Use Previous/Next, text search,
   and mouse-wheel scrolling for long records.
5. Compare a short known passage with the source. Distinct identical messages
   on different lines should remain distinct. Check supported PM/OOC markers are
   excluded. Unknown formats go to local review records; do not assume an unknown
   record is roleplay. Unknown channels are never eligible for analysis.
6. Import the exact same file again. Counts must remain unchanged. A renamed or
   gzip-compressed copy with exactly the same decompressed bytes also reuses the
   source. LF versus CRLF bytes are different sources; modified headers, subsets,
   overlapping files, and historical/live overlap are not automatically reconciled.
7. Restart Minecraft, reopen the transcript browser, and check the imported data
   persists. For a larger finished source, cancel during import and retry with the
   same reviewed names: committed batches resume without duplicate rows.
8. Create a normal journal backup. Migration from a Phase 1 database also creates
   a separate pre-schema-2 backup under `config/lotc-journal/backups/` before adding
   import tables. Existing layout settings must remain selected.

Limits: UTF-8, complete LF or CRLF lines, at most 64 MiB decompressed and 64 KiB
per line. Gzip compressed input is additionally bounded to 65 MiB. Corrupt gzip,
malformed UTF-8, bare CR, or an unfinished final line fail validation before any
source row or imported records are written. `latest.log` and symlink aliases to it
are refused. Other selected files that change while being read are also refused.
Only finished historical snapshots are supported; automatic rotation/tailing is
part of later live-capture work.

Exclusion coverage is conservative and currently verified using synthetic samples:
recognized PM/OOC payloads never reach journal storage; an unsupported private/OOC
format can remain UNKNOWN in the local review path. No provider or AI is connected.
Do not paste private source text into GitHub issues, test fixtures, or screenshots
for distribution. Report counts, generic format descriptions, and local source/line
references; keep any real sample review private.

The first classification configuration is saved as a versioned fingerprint.
Completed imports retain the original classifications on repeat; a partial import
rejects changed reviewed names rather than mixing interpretations. Retrying with
the original names resumes it. Reclassification/editing is future work.

This completes neither live capture nor Phase 2's real-log acceptance gate.
Record the exact JAR/source version, launcher, mod list, observed counts, and any
unexplained missing/duplicated lines. Performance budgets and a manually checked
real-log gold set still need user review.
