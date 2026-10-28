# Alpha.13 — scene rules, identity queue and main search

Use Fabric / Minecraft 1.21.11 / Java 21. Replace the prior journal JAR with
`lotc-character-journal-0.1.0-alpha.13.jar`. Keep a paired backup of archive.sqlite,
characters.sqlite and the backup manifest. Upgrade from character schema 4 makes a
verified paired backup before schema 5. Original logs and parser policy stay intact.

1. **Main search:** type a saved name or alias on the journal home screen. The
   match count should update; Enter opens the matching Characters list. Try a
   missing name, a longer query, and resize/reopen. After a completed layout/backup
   action, editing the query should show search feedback again. A fresh operation
   result/error stays visible until you edit the query.
2. **Identity queue:** use a real ambiguous saved name/alias, or a disposable test
   profile. Review links should show a queued pair and the matching name/alias in
   its tooltip. Confirm or Deny should save the decision and advance. Resize during
   a decision; reopen/restart to check processed pairs stay resolved. Undo should
   make eligible pairs reviewable again. Empty queue and unavailable queue must
   have different messages. Manual selectors remain available for unrelated names;
   selecting a pair manually does not itself suggest a match.
3. **Minimum:** refresh/open Scenes after import. New shared automatic scenes need
   at least 10 RP observations and 2 distinct observed speakers/actors. Nine messages,
   one actor, mentions of a second name and two linked aliases for one actor must
   not create a shared automatic scene. Duplicate-looking lines with distinct source
   coordinates still count as distinct observations. Previously reviewed small
   scenes are preserved; neutral internal windows may project your assigned memories
   without appearing as shared scenes.
4. **Confidence:** a clear qualifying conversation should be **CONFIRMED(auto)**.
   A qualifying group with unresolved/multiple actor tags, over 8 actors or a gap
   over 5 minutes stays **SUGGESTED**, with reasons in scene review/tooltips. Missing
   dates/locations remain unknown; automatic confirmation is grouping confidence,
   never an identity link, location assertion or persona knowledge grant.
5. **Filters/order:** Suggested scenes appear before Confirmed, newest first within
   each group. **Suggested: Only** excludes Confirmed; toggle back to All. Check
   search and paging, and combine with Skipped show/hide. Loading must hide stale
   rows/results. Persona-scoped views disable shared-state filters and hide shared
   confidence/origin, titles and locations.
6. **Corrections:** explicit Save or Skip freezes the exact reviewed membership,
   even if saving an otherwise unchanged auto-confirmed scene. Automatic refresh
   may grow/reassess other automatic scenes; it must preserve reviewed/skipped edits.
   Split requires at least 10 messages and 2 actual actors in **each** half. A rejected
   split explains why and lets you select another boundary without losing the scene.
   Combine also enforces the minimum and 5,000-message maximum. Inherited participant
   lists do not prove two actors occurred in a new half.
7. **Persistence/privacy:** restart/reimport, restore a paired backup copy, and undo
   a disposable import. Source dialogue, favorites and persona assignments stay
   intact except exact owned undo. Shared merges/tags cannot reveal a new identity
   or shared confidence in a persona's assigned scene projection.

Record false joins/splits in quiet, crowded and interrupted conversations. The
confidence heuristics are conservative rules, not a measured accuracy score.
Detailed large-archive/queue latency remains unmeasured; stored-scene CAS does not
atomically version an independent writer's global tag interpretation changes.
