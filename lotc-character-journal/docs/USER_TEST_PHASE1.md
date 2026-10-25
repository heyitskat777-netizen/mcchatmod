# First user test — foundation

Run this after a successful build, using a local world first. Record the exact
JAR SHA-256 and source commit, Minecraft version, Fabric Loader/API versions,
launcher, and mod list. Automated shell tests do not replace this modpack test.

1. Start a 1.21.11 Fabric instance with the JAR and matching Fabric API.
2. Enter a local world; press J. Confirm the journal opens.
3. Rebind the journal key in Controls. Confirm the new binding works.
4. Try small and large GUI scales; resize the window. Confirm the actual mod
   version, storage status, and disabled AI/capture status stay readable. Verify
   settings/backup controls are unavailable while an operation is pending.
5. Type in the search field. It must explain search is not connected yet.
6. Toggle the saved layout preference. Close/reopen and restart Minecraft.
   Confirm the preference remains selected. This build saves the preference;
   it does not yet contain compact character-list rows.
7. Create a backup. Confirm a new file exists in config/lotc-journal/backups and
   normal layout controls remain usable afterward. Never test restoration by
   overwriting your only database; use a separate copy.
8. Press Escape or Done; confirm play resumes and the journal does not pause
   an integrated world.
9. Confirm no chat-capture/AI connection is presented as active.

Report crashes, overlaps, unreadable text, unexpected key conflicts, and settings
that do not persist. Do not include credentials or private logs in screenshots.

Capture accuracy, persona memory, scene grouping, and AI quality are later
milestone tests; they are not expected to work in this foundation build.

