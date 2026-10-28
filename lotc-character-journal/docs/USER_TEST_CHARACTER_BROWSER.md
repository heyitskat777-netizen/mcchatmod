# Alpha.8 — character list and persona activation

Minecraft 1.21.11 / Fabric / Java 21. Existing native journal.sqlite records stay in
place; schema remains 8 and parser policy remains 4. No database reset or unrelated
old-database import is required. Follow prior partial-import advice if upgrading
from alpha.6 or earlier; alpha.7 partial policy-4 imports remain compatible.

1. Journal → Characters: saved names should be visible as individual rows with ID
   suffixes. Search by name, alias or account observation. Clear search restores
   the list; Previous/Next browse pages. An empty search explains how to create.
2. Open an existing non-first row. Review the labeled fields and confirm Use as
   persona is enabled. Resize and return to the same character: its ID and fields
   must stay unchanged. Select it as persona, close/reopen Characters and repeat.
3. New character opens a separate draft. Use as persona explains that Save character
   is required. Save, then use it. It must appear in the list after Done, and still
   be selectable after restarting Minecraft. Repeated Use as persona reuses the
   same saved character/persona rather than creating duplicates.
4. Edit notes/name/aliases, resize before saving, and confirm the draft survives.
   Save explicitly before persona activation. Aliases are semicolon-separated;
   use \; for a literal semicolon and \\ for a backslash. Existing large alias
   sets must not be truncated by opening/saving the editor.
5. Open Transcripts, select one message, Assign, and choose the saved identity and
   owning persona. Check Persona memories for each persona separately. Starring or
   importing a message alone does not confirm an identity or persona assignment.
6. Import a known finished .log/.log.gz: its dialogue appears in the same native
   database alongside saved characters, notes, favorites and persona memories.
   Restart and repeat import; existing dialogue/identities/assignments stay intact.

Kat reports alpha.7 live recording seems OK. This release still needs this saved-
character browsing/persona user check in the normal modpack. Report source/identity
IDs and expected behavior without publishing private dialogue or account details.
