# Two-file storage and character discovery — alpha.9

Target: Minecraft 1.21.11 / Fabric / Java 21. Keep your old JAR and make an offline
copy of the entire `config/lotc-journal` folder before testing an upgrade. Do not
submit private databases/logs or screenshots containing private chat to GitHub.

1. Upgrade and open the journal. Check existing imports, live dialogue, individual
   favorites, character notes/aliases, persona choices and assigned memories.
   `archive.sqlite` and `characters.sqlite` should now exist; native
   `journal.sqlite` is retained unchanged as a recovery source. New installations
   create only the new pair. Do not edit/swap a database while Minecraft runs.
2. Import a small known `.log` or `.log.gz` with an identifiable RP speaker and
   actions. **Characters** should list that observed name once. Repeat the import
   and restart: it must not duplicate the character or dialogue. Check live chat
   creates the same observed character when its trusted name matches.
3. Open that character and **Related dialogue**. Cycle **Evidence: All**, SPEAKER,
   ACTION and MENTION. Speech/actions must belong to the header's actor; a name
   quoted inside someone else's speech should be a mention. Search and favorites
   should work inside this view. This is the shared archive, not persona memory.
4. Save a new character whose name appears in older dialogue, then add an alias.
   Related dialogue should find those earlier occurrences. Remove an alias and
   check obsolete derived matches disappear without deleting dialogue, favorites
   or manual persona assignments. Duplicate names/shared aliases should remain
   unresolved rather than silently merging. Renaming an automatically observed
   character retains its first observed name to prevent duplicate rediscovery.
5. Check ordinary plugin notices, PM and OOC do not create characters. UNKNOWN
   dialogue can match mentions, but must not acquire confident speaker/action
   tags or become persona/AI knowledge. Automatic records leave account text blank
   and never select/create a persona or assign its memories.
6. **Create backup**, then close Minecraft and keep the complete backup trio:
   `journal-<time>.sqlite`, `journal-<time>.sqlite.characters.sqlite`, and
   `journal-<time>.sqlite.pair.json`. Restore into a separate test profile by
   copying the first to `archive.sqlite`, the second to `characters.sqlite`, and
   the manifest to `archive.sqlite.pair.json`. Reopen and check records, favorites,
   personas and assignments. Restore both files from the same snapshot; one missing
   member should produce a storage error, not an empty replacement. Preserve the
   originals so a failed restore is reversible.

Recognized discovery forms include `Name: dialogue`, `Name says: dialogue` and
anchored common actions such as `Name waves.` or `*Name nods.*`. Unrecognized
wording remains searchable mentions/unresolved evidence. Quoted names and shared
aliases do not prove identity. This is a deterministic observation index; scene
recognition, account ownership and AI interpretation remain separate work.

Large archives are not performance-certified: new observed names and saved alias
changes currently scan the existing archive on a background worker, within a
storage transaction. Record long saves/imports or live-queue errors before using
this build on your only archive. The automated process-death test covers an
interruption before commit; it does not emulate power loss during SQLite commit.
