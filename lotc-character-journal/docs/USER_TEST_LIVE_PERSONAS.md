# Alpha.7 — individual favorites, live logs and persona memories

Minecraft 1.21.11 / Fabric / Java 21. Back up before upgrading. Finish incomplete
alpha.5/alpha.6 policy-3 jobs with that old version before moving to policy 4;
completed sources remain stable. The game does not need any AI provider.

1. Open Transcripts and click a specific non-first message, including a wrapped
   one after scrolling. Only that row should highlight; the status identifies its
   message number. Click its star or Favorite selected. Verify the same message
   alone in Favorites: Only; adjacent/duplicate-looking messages remain separate.
2. Join a world: main journal should show Live CAPTURING. During a normal session,
   check RP dialogue/emotes, repeated emotes and a known plugin channel. PM/OOC,
   action bars and recognized command notices should not appear in saved data.
   Ambiguous eligible messages can remain UNKNOWN; report source coordinates.
3. Pause capture. Send some chat, resume and send another message. Only the resumed
   eligible message should be saved. Pause and restart: it should remain paused.
4. Inspect `<instance>/lotc-journal/live/<date>/`: versioned `.lotc-chat.jsonl` files
   are the recovery authority; `.chat.txt` is a readable derived view. Every saved
   message has a timestamp and a stable session/sequence. Never edit authoritative
   files while the game runs. Pending is not a durable saved acknowledgment.
5. In a disposable copied instance, abruptly close Minecraft after Saved has risen,
   then restart. Acknowledged messages should survive once, with repeated text at
   distinct coordinates. Queued/unforced messages may be lost. Automated process-
   death tests do not certify physical drive/power-loss behavior.
6. Use Log import on a complete closed single-segment `.lotc-chat.jsonl` copy, then
   repeat it: counts stay unchanged and the original stays untouched. Arbitrary
   single rotated segments are not standalone imports; the owned root recovers
   complete rotated sessions automatically. Ordinary historical logs may overlap
   without provable shared event identity, so ambiguous overlaps remain separate.
7. Characters: create two distinct characters (even with a shared alias), add notes
   and manual account observations, then Use as persona for each. They must remain
   separate. Search names/aliases and rename one; account text is unverified input.
8. On two individual transcript messages, choose Assign. Confirm different owning
   personas and identities; try Reference vs Speaker and keep an identity unassigned.
   Skip must not guess a link. Clear/reassign to reverse a wrong assignment.
9. Open Persona memories for each persona. Only its explicitly assigned messages
   should appear, including search/channel/favorites filters. Shared archive
   Transcripts intentionally remains an administrative archive view. Capturing or
   starring a message does not automatically assign it to your active persona.
10. Review identity links: confirm a deliberate same-identity link, restart, review
    the saved decision and undo it. Source dialogue, stars and persona memory scope
    must remain intact; duplicate names alone never trigger a merge.
11. Make a backup after edits. A database backup captures that point's decisions;
    retaining newer live journals while restoring older SQL replays newer forced
    chat. Copy both owned journals and the database for profile recovery. Do not
    test a restore against your sole working copy or while Minecraft is running.

The previous character-database importer needs the real format/schema; do not
assume the current module has imported it. Scenes, participant/location grouping,
AI and item/name/task generation remain later milestones. Report build/version,
source/line IDs and expected result without publishing private dialogue.
