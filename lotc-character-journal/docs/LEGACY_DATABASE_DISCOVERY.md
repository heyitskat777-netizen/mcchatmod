# Previous database discovery — October 24, 2026

Read-only inspection of the current version 11 master succeeded.
The source database was not modified and is not part of this project.

| Item | Observed value |
| --- | --- |
| SQLite quick_check | ok |
| Sources | 2,241 |
| Messages | 397,045 |
| Characters | 2,175 |
| Alias review decisions | 259 |
| Identity dispositions | 1 |
| Recorded clock coverage | 2024-09-01 14:20:06 through 2026-10-10 22:45:35 |

Channel counts: unknown 151,244; roleplay 123,803; system 46,551; OOC 38,659;
private_message 18,991; roleplay_unattributed 15,340; aviary 2,457.

Existing messages preserve source ID/line, local clock, occurred_at, log thread,
log level, original tags, speaker observations, recipient, body, raw payload, and
original raw line. Tables also include aliases, accounts, scenes, relationships,
identity evidence, and review decisions.

The legacy characters table enforces unique normalized names. The new journal
must not inherit that assumption: two distinct characters can share a name.
A shared account must not imply a shared character. Legacy relationship scores
measure interaction signals, not affection.

Representative log shapes were inspected. They include a log prefix, optional
[System], [CHAT], a second displayed clock, and optional formatting codes.
Use synthetic fixtures reflecting those shapes. The first parser prototype is
conservative and incomplete; it does not establish full corpus support.

Phase 3 must inspect the current live schema again, produce a dry-run import
report, preserve external IDs/provenance, and stage unresolved attributions.
A previous extraction decision is evidence to preserve, not automatically
knowledge available to all of Kat's personas.

No private dialogue is reproduced in this report.

