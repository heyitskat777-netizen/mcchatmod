# Alpha.14 — shared-scene search performance

Use Minecraft 1.21.11 / Fabric / Java 21. Replace the existing journal mod with
`lotc-character-journal-0.1.0-alpha.14.jar`; do not install two versions together.
Keep your usual paired backup. No new schema upgrade is required from alpha.13.

1. In Scenes, search for dialogue text, a saved participant name, and a missing
   name. Try both early and later pages in your larger archive; record any long
   wait and approximate archive/scene counts. The fixed synthetic test is not a
   performance promise for your computer.
2. Confirm participant-name search reflects your saved tag corrections and manual
   participants. A rejected automatic speaker must not keep matching through its
   old tag; restoring automatic tags should restore the appropriate match.
3. Open a saved character, change its notes/account observation, Save, and reopen.
   The fields should persist without an archive-wide name-tag rebuild. Check its
   existing related dialogue and persona assignments are unchanged. Changing names
   or aliases still performs the necessary historical backfill.
4. Switch to a persona-scoped scene view. It should search only that persona's
   explicitly assigned actors/text and must not expose shared identity links,
   manual titles, locations or silent attendees. Reopen/restart and repeat.

If alpha.13 has not been reviewed yet, also complete its focused scene minimum,
confidence/filter, identity-queue, home-search and persistence checks in
[USER_TEST_ALPHA13.md](USER_TEST_ALPHA13.md). Synthetic storage tests and automated
Minecraft client tests remain separate from your real-log/modpack acceptance.
