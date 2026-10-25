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
- Store original observations separately from interpretations; preserve provenance.
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
- Identity merges require user confirmation and must be reversible.
- AI defaults off. Never silently fall back to a paid provider.
- Never run unattended tests against the live LoTC server.
- Do not overwrite the previous chat database. Import it read-only in a later phase.

Do not claim missing features are implemented. User testing is a distinct status.

