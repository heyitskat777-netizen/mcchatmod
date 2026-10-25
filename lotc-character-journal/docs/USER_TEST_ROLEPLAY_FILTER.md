# Alpha.5 roleplay/noise check

Finish incomplete alpha.4 imports before upgrading; parser policy 3 cannot safely
resume older policies. Upgrade a copied instance/database and keep its backup.

1. Open an older transcript. Recognized persona commands/lookups, map hints, friend
   requests, news, Soulstone, lock and build/region replies should disappear from
   UNKNOWN. Dialogue quoting those messages must remain. Source positions stay fixed;
   imported/ignored counters change to account for removed system notices.
2. Import a finished log not already imported. Recognized IC channels with dialogue
   or emote structure should classify as ROLEPLAY, even when the speaker is not a
   reviewed name. No character/persona/account link should be created from this.
3. Click **Roleplay only**. Compare the visible RP with the original file privately.
   Try **UNKNOWN review** and **Channel: ALL**. Bare unmarked text remains UNKNOWN;
   Minecraft's generic `[CHAT]` does not prove RP. Old cleaned records lost their
   IC tags, so older RP may still need the All/UNKNOWN view. Migration does not guess.
4. Repeat-import and restart: counts and filters should agree with the saved records.
   Confirm actual RP, quotations and unknown bracketed actions survived; recognized
   PM/OOC must still be absent. Report safe source IDs/counts and the exact template
   family if something is missed, without sharing private names or dialogue.

Persona lookup replies are operational metadata, not dialogue or confirmed identity
merges. Original source files are untouched, so later identity work can inspect them
separately. All/UNKNOWN remain available to avoid silently hiding ambiguous history.
The new rules intentionally prefer leaving an ambiguous message reviewable over
removing possible RP. No claim of perfect semantic roleplay detection is made.
