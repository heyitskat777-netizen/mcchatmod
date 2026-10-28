# LoTC Character Journal

A modular client-side journal for **Minecraft 1.21.11 / Fabric / Java 21**.
This repository starts the agreed milestone plan. Read docs/STATUS.md for what has
actually been verified. It is not the complete character-memory mod yet.

## Current build

The user has accepted the Phase 1 foundation smoke test. Press **J** in a world
to open the journal; its key is rebindable in Controls. Layout settings and backup
creation remain available. Characters now supports explicit names, aliases and notes; persona memories require manual message assignment.

The `0.1.0-alpha.12` development build expands **Log import** for finished UTF-8 `.log`
and `.log.gz` files, background validation, progress/cancellation, restart-safe
checkpoints, repeat-import deduplication, and a transcript browser with literal
text search. **Import all / dashboard** adds a read-only folder preview,
confirmed batch import, durable per-file reports/retry and batch-owned undo.
Transcript browsing supports All sources, channel filters, a direct **Roleplay only**
view and UNKNOWN review. Recognized persona/plugin notices are ignored; IC-channel
and sender/emote evidence classifies new RP; trusted anchored speech/actions also
create observed character records.
Older cleaned UNKNOWN observations are not promoted from wording alone.
The main journal now has **View transcripts**, without opening the import screen.
Click an individual dialogue message to select its highlighted row, then **Favorite selected**, or click its
`[ ]` / `[*]` marker directly. **F** also toggles the selected message while the
conversation pane is focused and that message is visible. **Favorites: Only**
combines with source/channel/search filters. Stars persist across restart/backup;
undoing their imported message removes them. They do not assign an identity or
change AI eligibility. See [the favorites checklist](docs/USER_TEST_FAVORITES.md).

An explicitly confirmed folder watch handles stable finished archives every 30s;
it starts disabled and persists your opt-in. See
[the folder workflow checklist](docs/USER_TEST_BATCH_IMPORT.md), especially the
undo and watch limits. Check STATUS.md for actual checks on this build.

The transcript is a scrollable conversation with fixed controls, a draggable bar,
keyboard navigation and bounded 50-message pages. Technical provenance is available
through Details. The database stores cleaned dialogue without redundant raw logger
lines. Logger/thread tags, recognized leading RP tags, log clocks and color escapes
are removed from the payload; quoted markers inside speech remain. Source
fingerprints, line coordinates, clocks and channel uncertainty remain separate
metadata. Names found in trusted ROLEPLAY speaker/action headers create observed characters.
New names and aliases search earlier dialogue; ambiguous collisions stay unresolved.
**Related dialogue** on a saved character filters speech, actions and mentions.
Confirmed identity links combine the transitive group in this shared view once per
source coordinate; undo separates it again. Denials and persona scopes are preserved.
Persona memories still require explicit confirmation through Assign.

Dialogue, import/live state, settings and favorites live in
`config/lotc-journal/archive.sqlite`. Characters, aliases, personas and evidence
live in `config/lotc-journal/characters.sqlite`. Backups are stored in
`config/lotc-journal/backups/`. A schema migration creates a verified backup of an existing schema-1 through schema-8
database before upgrading the journal. Undo removes active batch-owned records
while keeping source checkpoints and payload-free removal accounting; it does not
restore them on repeat import or erase existing backups. Live capture now starts automatically in worlds and offers pause/resume on the main
screen. Filtered messages are forced to dated versioned journals under
`lotc-journal/live/`, then committed to the database; `.chat.txt` is a readable
rebuildable view. Received player chat and non-overlay server/plugin events are
supported, subject to other mods canceling/modifying events. PM/OOC and recognized
notices are filtered before disk. Pending, Saved and errors are separate states.
Only acknowledged forced writes receive the crash-recovery guarantee; messages
still in memory when the process dies can be lost. See the actual verification
limits and restore behavior in STATUS.md.

**Characters** shows a searchable, paged list of saved characters. Click a row to
review its name, aliases, notes and account observation. **New character** opens a
separate draft; Save character persists it. **Use as persona** is available for a
saved, unchanged character; unsaved drafts/edits explain why saving is required.
Saved characters remain selectable after reopening/search; resizing preserves the
current editor draft.
Aliases support escaped semicolons/backslashes and the full native field bounds.
**Use as persona** creates/selects a persona; **Assign** on an individually selected
message asks for its owning persona, identity or unassigned choice, and whether it
is a reference or speaker attribution. **Review tags** on a selected transcript message opens its shared character tags.
Search for a character, choose its UUID, then Add/Replace/Remove tags and
**Save reviewed tags**. **Reject all tags** stores an empty reviewed list;
**Restore automatic** resumes current suggestions. Saved reviews survive alias
changes, imports and restarts. UNKNOWN supports mentions only. Failed/stale saves
keep the draft and require explicit reload. These controls leave original dialogue
and persona memories intact. See [the tag-review checklist](docs/USER_TEST_MESSAGE_TAGS.md).
**Persona memories** shows only explicit
assignments. Shared archive Transcripts remains separate. Review identity links
supports explicit confirmation, **Deny link / keep separate**, and persistent undo.
Denials block direct/indirect linking until **Clear selected denial**; clearing does
not link automatically. Duplicate names/aliases never merge automatically.
Characters schemas 1/2/3 upgrade to schema 4 after a verified paired backup. The mod owns two bound SQLite files: `.log`/`.log.gz` imports and live recording
populate `archive.sqlite`; character records and confirmed assignments use
`characters.sqlite`. Upgrading copies native `journal.sqlite` without changing it,
then migrates the copy. Backups contain both SQLite files and a `.pair.json`
manifest: keep and restore all three together. Missing/mismatched members fail
closed. See [the upgrade checklist](docs/USER_TEST_CHARACTER_DISCOVERY.md). An unrelated old-database importer is no longer planned.

Native `.lotc-chat.jsonl` repeat imports reuse event IDs. Ordinary `.log` overlaps
with live events remain separate when their identity cannot be proved; genuine
repeated text is not discarded.

**Scenes** on the main journal suggests source/session-local RP windows, with a
configurable 15-minute gap. Import screens offer **Review scenes / skip** after
commits. Review participants, enter a title/location, and **Confirm / save scene**;
**Skip for now** is durable. **Scene dialogue / split** provides bounded searchable,
scrollable messages and exact-message split confirmation. Choose and combine saved
scenes explicitly. Manual decisions survive imports, settings changes, restart and
paired backups; source dialogue is never copied or rewritten by scene edits.
Automatic actors come from speech/action evidence, not mentions. Add/remove silent
participants manually. Direct/overheard requires an explicit persona perspective.
Persona scene views use only explicitly assigned evidence and speaker identities,
without inheriting shared identity tags, merges, titles, locations or attendees.
Missing clocks/locations stay unknown; crowded conversation windows need review.
Counts are observations, and active time estimates cap each gap at 60 seconds.
See [the Phase 3/4 checklist](docs/USER_TEST_SCENES.md) for controls and limitations.
Nearby recognition, AI and favorite-driven extraction remain future work.
See [docs/USER_TEST_CHARACTER_BROWSER.md](docs/USER_TEST_CHARACTER_BROWSER.md) for alpha.8
and [docs/USER_TEST_LIVE_PERSONAS.md](docs/USER_TEST_LIVE_PERSONAS.md) for alpha.7
and the policy-4 partial-import upgrade requirement.
See [docs/USER_TEST_ROLEPLAY_FILTER.md](docs/USER_TEST_ROLEPLAY_FILTER.md) before
upgrading, especially if alpha.4 imports are incomplete.
See [docs/USER_TEST_IMPORT.md](docs/USER_TEST_IMPORT.md) for historical limits and recognized
privacy exclusions, and the real-log test still required to advance Phase 2.

## Start this project in Codex on your computer

1. Extract the source ZIP into a normal local folder, for example
   `C:\\Projects\\lotc-character-journal`.
2. Install a **JDK 21** (not only a JRE). Set `JAVA_HOME` to that JDK.
3. Open the extracted folder as a local project in Codex, or open it in VS Code
   with the Codex extension. The folder containing this README is the project root.
4. Start a chat in that project and use the task in `docs/CODEX_START.md`.
   Codex will read `AGENTS.md` and the saved milestone/status documents.
5. Keep the project under Git locally. No GitHub account is required to start.
   Publishing to GitHub is a separate decision.

You do not need to paste the entire planning conversation. The requirements,
boundaries, milestone plan, and current blockers are included here.

## Build and verify

Windows PowerShell:

```powershell
.\\gradlew.bat check :fabric:build
```

macOS / Linux:

```sh
./gradlew check :fabric:build
```

The installable remapped JAR is produced under `fabric/build/libs/`.
Use the JAR without the `-sources` suffix. Install Fabric Loader and Fabric API for
**1.21.11** separately. Check docs/STATUS.md before treating a provided JAR as tested.

To launch a development client:

```sh
./gradlew :fabric:runClient
```

To run the isolated automated client smoke suite (requires a working graphical
or virtual-display environment and your acceptance of Minecraft's EULA):

```sh
python3 scripts/client_smoke.py --eula-accepted
```

It first checks Minecraft graphics, then runs local-world/keybind/UI tests in
development and packaged-mod profiles, with separate processes for persistence.
See [docs/CLIENT_SMOKE_TESTS.md](docs/CLIENT_SMOKE_TESTS.md) for setup, isolation,
timeouts, and evidence. A build or unit-test pass is not evidence of successful
in-game testing; actual results remain in docs/STATUS.md.

If an environment cannot initialize Loom, verify just the independent core with
\`./gradlew -PcoreOnly check\`. This does not compile or validate the Minecraft adapter.

## Structure

| Module | Owns | Production dependencies |
| --- | --- | --- |
| domain | Models and service contracts | None |
| parsing | Conservative channel/name observations | domain |
| ingestion | Bounded historical-file validation/import worker | domain, parsing |
| storage | SQLite settings, import checkpoints/transcripts, migrations, backups | domain, SQLite JDBC |
| application | Background scheduling and immutable UI snapshots | domain |
| fabric | Minecraft UI, keybind, import/transcript screens, startup wiring | domain, application, storage, ingestion, Fabric |

Future identity, encounter, statistics, analysis, and provider modules are added
as coherent features rather than empty placeholders. The development-agent
workflow runs outside Minecraft.

## Useful files

- `AGENTS.md`: instructions and boundaries for coding agents.
- `docs/STATUS.md`: verified state and exact remaining work.
- `docs/MILESTONES.md`: the full agreed phase plan.
- `docs/DECISIONS.md`: accepted requirements and provisional defaults.
- `docs/USER_TEST_PHASE1.md`: the first in-game smoke-test checklist.
- `docs/CODEX_START.md`: a continuation task for local Codex.

No private logs, previous database, account tokens, AI weights, or Minecraft
binaries are included. A distribution license has not been selected.

