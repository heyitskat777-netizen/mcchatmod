# LoTC Character Journal

A modular client-side journal for **Minecraft 1.21.11 / Fabric / Java 21**.
This repository starts the agreed milestone plan. Read docs/STATUS.md for what has
actually been verified. It is not the complete character-memory mod yet.

## Current build

Phase 1 foundation: press **J** in a world to open the journal shell. The key is
rebindable in Minecraft's normal Controls menu. The shell shows an empty character
search field, a persisted layout preference, and a backup control. Character
search is visibly marked unavailable until import is implemented. The layout
preference is saved now; full compact character rows arrive with the character UI.

Settings live in `config/lotc-journal/journal.sqlite`. Backups are stored in
`config/lotc-journal/backups/`. The shell does not capture chat, recognize nearby
players, or call an AI service. The parser prototype is tested separately and is
not connected to live capture.

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

To run the separate client test (requires a working graphical or virtual-display
environment and your acceptance of Minecraft's EULA):

```sh
./gradlew :fabric:runClientGameTest
```

A build or unit-test pass is not evidence of successful in-game testing.

If an environment cannot initialize Loom, verify just the independent core with
\`./gradlew -PcoreOnly check\`. This does not compile or validate the Minecraft adapter.

## Structure

| Module | Owns | Production dependencies |
| --- | --- | --- |
| domain | Models and service contracts | None |
| parsing | Conservative log-format prototype | domain |
| storage | SQLite initialization, settings, backups | domain, SQLite JDBC |
| application | Background scheduling and immutable UI snapshots | domain |
| fabric | Minecraft UI, keybind, startup wiring | domain, application, storage, Fabric |

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

