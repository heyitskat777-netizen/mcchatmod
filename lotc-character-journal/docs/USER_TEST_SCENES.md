# Alpha.12 — linked history and scenes

Target: Fabric / Minecraft 1.21.11 / Java 21. Replace the previous mod JAR with
`lotc-character-journal-0.1.0-alpha.12.jar` (not the sources JAR). Keep your original
logs. Before updating, keep a backup of **both** `archive.sqlite` and
`characters.sqlite`; native upgrade makes an additional verified paired backup.
Keep its `.pair.json` manifest with the two members. Test restoration on a copy.

## Character history and tag review

1. On individual messages, test alpha.11 **Review tags**: correct/remove one tag,
   reject all, restart/reimport, then restore automatic. Other messages stay intact.
2. Confirm a deliberately chosen pair in Characters → Review links. Related
   dialogue from either member should show the union once per source coordinate.
   Check speech/action/mention, literal search and favorites filters.
3. Undo the link. Related histories separate immediately. A saved Deny link must
   block both direct links and indirect bridges until explicitly cleared.
4. Verify that shared tags/links do not add memories or reveal a new speaker
   identity in either persona's assigned scene view. Unassigned speakers stay
   unresolved; a persona retains its explicitly attributed identity.

## Scenes

1. Open **Scenes** from the main journal, then **Suggest / refresh**. The import
   screens/dashboard also provide **Review scenes / skip** after import. Review is
   outside the import transaction; importing never waits for your answer.
2. Compare a quiet conversation, a crowded event and an interrupted conversation.
   Check message membership and observed participants. A name mentioned in speech
   must not automatically become an attendee. Record false joins and false splits
   separately from missing or duplicate imported messages.
3. Review a scene: correct its title/location, edit participants, remove a wrong
   attendee, and optionally add a silent participant. Direct/overheard requires an
   explicit persona perspective. **Confirm / save scene** persists the exact list;
   edits in the participant screen alone remain an unsaved parent-scene draft.
4. Resize at the smallest GUI scale/window you use. Title/location and participant
   drafts should survive, with every control reachable. Reopen and restart to
   check the saved result. Shared edits must not grant persona memories.
5. Open **Scene dialogue / split**, search and scroll the bounded transcript, select
   a non-first message, then confirm **Split before selected**. Review both halves.
   A split inherits the reviewed location/participants into both halves: verify
   those interpretations in each half. Choose the other half and confirm
   **Combine scenes**. Counts must not duplicate
   dialogue/favorites. Combining uses saved scenes; save drafts beforehand.
6. **Skip for now**, hide/show skipped scenes, restart/reimport and refresh. The
   decision must persist. Reopen a skipped group and Confirm/save to review it.
   Refreshing or changing Gap only regroups unreviewed suggestions, not confirmed
   or skipped membership. New dialogue cannot silently extend a reviewed scene.
7. Cycle **Shared archive** to a persona. Only explicitly assigned RP messages and
   explicitly attributed speaker identities appear. Title/location are generic;
   silent attendees and shared identity corrections cannot expose other knowledge.
   Shared scene editing/regrouping is disabled in this view.
8. Make a paired backup, restore a copy, and verify scenes, participants, skip
   decisions, tags and persona assignments. Undo a disposable import batch: only
   its owned dialogue/scene memberships should disappear; unrelated edits survive.

## Interpreting suggestions and counts

- Default inactivity boundary: **15 minutes**; the Gap button offers 1, 5, 10, 15,
  30, 60 and 120 minutes. Exactly the threshold stays together; greater gaps split.
- Suggestions are source/session-local windows, not proof of one conversation.
  Crowded/interleaved conversations need manual splits and participant review.
  Explicit changes in recorded persona ownership are boundaries too.
- Historical logs do not prove a date/timezone/location. Missing/backward clocks,
  including historical midnight rollover, split conservatively. A location stays
  unknown until you enter one; a place mentioned in speech is not a location fix.
- Statistics count stored observations, not exact emotes. On narrow windows,
  **S**, **A** and **M/R** mean speech, actions and mentions/references. Multipart emotes and
  overlapping ordinary/live sources may remain separate observations.
- Elapsed time requires complete monotonic clocks in a common source/context.
  Active time is an estimate: sum each adjacent positive gap, capped at 60 seconds.
  Missing/mixed chronology is shown as unknown; it is never fabricated.
- Persona interpretation counts use its own speaker/reference assignments and
  owned message syntax, not shared archive tag edits. References do not prove a
  meeting. Groups are capped at 5,000 observations, with bounded transcript pages.
  Saving a reviewed participant list supports up to 64 identity/perspective entries;
  larger automatic lists remain visible for explicit correction, never truncation.
- Real-log matching accuracy, busy-scene usefulness and large-archive performance
  need your actual-play review. Automated synthetic coverage is separate evidence.

AI, relationship/task extraction and nearby recognition are later features.
