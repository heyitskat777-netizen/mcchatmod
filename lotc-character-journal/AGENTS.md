# LoTC Character Journal — project instructions

Read README.md, docs/STATUS.md, docs/DECISIONS.md, and docs/MILESTONES.md first.
Target Minecraft 1.21.11, Fabric, Java 21. Do not silently upgrade Minecraft.

## Workflow
- Work on the current milestone until its automated criteria pass.
- Use a coordinator and narrowly scoped subagents for independent implementation,
  verification, or review when parallel work is requested. Assign non-overlapping
  files or use separate worktrees. Only the coordinator integrates changes.
- For delegated milestones, confirm native subagent tools are available. If they
  are unavailable, say so explicitly and work as one agent; do not simulate agents.
  Use a coordinator and at most three native subagents with these default scopes:
  build agent owns build/configuration changes and environment investigation;
  verification agent owns meaningful tests and synthetic fixtures; review agent
  independently checks modularity, integrity, threading, and privacy without edits.
- Give each agent concrete non-overlapping file ownership. Keep production fixes,
  documentation, integration, commits, and pull requests with the coordinator unless
  explicitly reassigned. Use the existing isolated checkout, not new worktrees,
  unless the user requests them. Agents must not switch the shared branch.
- Parallelize independent investigation, but sequence dependent changes and shared
  Gradle runs. Wait for relevant findings before integrating. The coordinator runs
  the integrated checks, fixes defects, and repeats affected checks. After three
  unsuccessful repair cycles, document the blocker and reassess instead of retrying
  the same approach. Never weaken assertions or checked expectations to pass.
- Keep main as the reviewed baseline; use feature branches, commit verified work,
  and open a pull request when ready. Record compilation, executed automated client
  tests, and user/modpack testing separately. Include a concise user-test checklist.
- Keep interfaces explicit. No global service locator or central all-purpose manager.
- Prefer small coherent features; do not create empty modules merely to fill a chart.
- Run ./gradlew check :fabric:build. For plain logic, use the narrow module tests first.
- Run ./gradlew :fabric:runClientGameTest only in an environment prepared to launch
  Minecraft, with its EULA accepted by the user. Never call compilation an in-game test.
- For the isolated graphics/world/keybind/restart/packaged-JAR suite, use
  `python3 scripts/client_smoke.py --eula-accepted` on Linux after EULA acceptance.
  The helper supplies required profile/phase properties and validates shutdown and
  assertion evidence; a raw Gradle task exit alone is not the full suite result.
  See docs/CLIENT_SMOKE_TESTS.md. Record failed and skipped phases explicitly.
- Review failures independently where possible. After three unsuccessful repair
  cycles, document the blocker and reassess; do not weaken acceptance criteria.
- Do not change checked reference expectations just to match implementation.
- Record exact commands, outcomes, skipped checks, and user testing still needed
  in docs/STATUS.md. Preserve milestone state across sessions.

## Boundaries
- domain: contracts and models, no external dependencies.
- parsing: domain only; uncertain channels and identities remain unresolved.
- application: domain only; injected ports and bounded worker queues.
- storage: domain only; owns SQL, schema migration, and backups.
- fabric: Minecraft adapter and composition root; keep feature logic outside it.
- Future identity, encounter, statistics, analysis, and provider features get
  coherent modules as implemented; provider-specific details stay at the edges.
- Keep file, SQL, and network operations off the client/render thread.

## Data rules
- Never add real RP logs, databases, secrets, tokens, or user identifiers to Git,
  generated fixtures, screenshots for distribution, or source archives.
- Use synthetic log samples with realistic formatting.
- PM, OOC, unknown channels, and other-persona knowledge cannot enter AI analysis.
- Store cleaned dialogue observations separately from interpretations. Strip only
  recognized leading transport/formatting decorations; preserve speaker wording,
  emotes, punctuation, unknown bracketed actions and quoted markers inside speech.
  Keep source coordinates, content identities and clock metadata separately. Never
  persist the redundant raw logger line in current transcript tables.
- Use the same injected dialogue normalizer for import and backed-up migration.
  Check privacy before and after recognized wrapper cleanup. Migration removal of
  known system/empty/private records must adjust source and owned-file counters
  atomically without moving cursors, changing source IDs or breaking undo.
- Historical import identity hashes exact decompressed bytes. Keep observations,
  counters and cursor checkpoints atomic. Preserve repeated text at different
  source coordinates; never deduplicate observations by text alone.
- Partial import resumes are bound to a versioned parser/reviewed-name policy.
  Bump the policy version and test incompatibility when classification changes;
  do not silently mix policies or reclassify completed sources on repeat import.
- Folder previews validate without writes; confirmation is bound to the approved
  digest. Exclude symlinks, nested folders and active logs from bulk/watch discovery.
- Batch ownership is attached atomically only to newly inserted observations. Undo
  must preserve older data and original files, record payload-free removed coordinates,
  and remain terminal. Cumulative source counters include accounted removed records;
  active transcript counts do not. Never silently restore removed records on repeats.
- Folder watch requires persisted explicit opt-in, stable finished-file checks and
  bounded polling. It is separate from live capture; interrupted batches do not resume
  automatically. Safe diagnostics contain codes, never private payload/exception text.
- Roleplay classification requires original IC-channel/sender evidence or an
  explicitly reviewed name observation, never generic Minecraft CHAT alone.
  Automatically create observed characters only from anchored speaker/action grammar
  within trusted ROLEPLAY observations. Mentions are separate derived evidence;
  duplicate names/aliases stay unresolved. Lost legacy channel proof stays unknown.
- Noise filters match complete known unattributed server notices. Preserve real
  roleplay, quoted notices and unknown bracketed actions; privacy gates take priority.
  Persona command replies do not become RP or automatic identity links.
- Favorites key immutable observation coordinates; never copy dialogue or imply
  identity/analysis eligibility. Deleting a message atomically removes its favorite.
- Live capture starts automatically when connected, with persisted pause/resume.
  New persona knowledge stays unassigned until explicitly reviewed; persona
  selection never silently attributes the shared archive.
- Live capture must filter before disk, use durable event identity/replay and bounded
  off-thread queues. Daily files are not immutable historical content-hash sources.
- Scene/extraction suggestions are separate evidence-backed interpretations. Ask
  about uncertain identities, allow skip/unassigned and persist manual overrides;
  never keep an import transaction open for user review.
- Identity merges require user confirmation and must be reversible.
- Explicit keep-separate decisions persist across restarts, alias updates and backups.
  Block direct and indirect merges across their resolved identity groups. Only an
  explicit user reversal may clear a denial; clearing never links automatically.
- AI defaults off. Never silently fall back to a paid provider.
- Never run unattended tests against the live LoTC server.
- Use two mod-owned SQLite files: `config/lotc-journal/archive.sqlite` for imported
  and live dialogue/settings/favorites, and `characters.sqlite` for characters,
  aliases, personas, evidence and confirmed assignments. Preserve native
  `journal.sqlite` during upgrade. Back up/restore both members together.
- Cross-file changes use one attached connection, rollback journals and FULL sync
  on both files; validate pair bindings. Never silently replace a missing member.
- Message tag reviews store an exact per-message set separately from automatic
  evidence. Reviewed-empty means reject all; only explicit Restore automatic may
  resume suggestions. Preserve reviews across imports/reindex/backups. Compare
  revisions under the write lock, retain automatic revision markers after restore,
  and reject stale saves. UNKNOWN allows manual MENTION only; never promote channel
  or grant persona knowledge. Undo removes review rows at exact owned coordinates.
- Shared confirmed identity groups affect related-history display only; exact
  observation coordinates deduplicate the union, undo changes it immediately, and
  no group or shared tag correction grants persona knowledge.
- Scenes are revisioned interpretations with exact message references. Keep
  explicitly reviewed/skipped MANUAL groups and participants across automatic refresh;
  protected coordinates are hard boundaries. AUTO confirmed groups stay reassessable.
  Shared automatic creation and new split/combine results need 10 RP observations
  and 2 distinct resolved effective actors; mentions/manual inherited attendees do
  not prove participation. Preserve existing reviewed small scenes. Neutral windows
  may retain persona evidence while failing shared qualification. Unknown clocks/locations stay unknown.
  Split/combine and source undo use the paired transaction without rewriting text.
  Retain revision tombstones against stale/ABA writes. Mentions are not attendees.
- Persona scene projections use only explicit owned RP observations and their own
  speaker assignments, preserving unassigned/raw identity decisions without global
  link resolution. Never expose shared titles, locations, silent attendees or
  shared tag interpretations through persona results/counts/search. DIRECT/OVERHEARD
  needs an explicit perspective, never an implicit grant of memories.
- Automatic scene confirmation records grouping confidence only. Persona views
  hide shared origin/confidence/qualification and ignore shared state filters.
  Shared-name/alias identity review queues require explicit Confirm/Deny and advance
  only after durable success. Empty and failed queues must remain distinguishable;
  never propose every unrelated character pair or infer account/persona identity.
- New characters/aliases search existing dialogue; preserve source observations,
  manual persona assignments and ambiguity. Automatic tags never grant memories,
  infer accounts, select personas or merge identities. An unrelated legacy
  character-database importer remains out of scope at Kat's request.

Do not claim missing features are implemented. User testing is a distinct status.

