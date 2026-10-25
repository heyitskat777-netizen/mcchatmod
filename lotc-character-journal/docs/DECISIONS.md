# Requirements and decisions

## Established by Kat

- Fabric and Minecraft 1.21.11.
- In-game interface; historical and live capture; compressed log imports.
- Phase 2 includes **Import all logs**: a user-selected folder, bounded background
  file queue, existing deduplication/privacy/recovery rules, batch progress and
  per-file outcomes. The accepted workflow includes preview, a durable dashboard, retry and safe undo.
- Previous character-database import, preserving source provenance and decisions.
- Separate persona memory views; observed account/persona information when available.
- Manual character creation, renaming, notes, and corrections.
- AI-suggested aliases, with confirmation required for identity linking/merging.
- Scene detection by time and interactions; manual splitting/combining/participant edits.
- Distinguish direct interaction, overhearing, and mentions.
- Optional summaries, relationship developments, and unfinished business.
- No private messages or OOC in AI analysis.
- AI entirely optional; ordinary journal functionality survives provider failure.
- Small services/modules within one mod; no all-purpose central manager.

## Provisional defaults, not additional user decisions

- Windows is the first user-test platform; code remains portable.
- AI starts disabled; no silent paid fallback.
- Emote counts and active interaction time retained provisionally (question 13 omitted).
- PM/OOC excluded from journal storage by default, separate from confirmed AI exclusion.
- Unknown channels ineligible for AI.
- Manual persona selection before automatic switching.
- 15-minute configurable encounter inactivity boundary.
- No automatic nearby-player recognition in the first build.
- Personal project with all rights reserved until Kat selects a license.
- Source archive contains synthetic examples only, never private user data.

## Still to establish

- Actual modpack/launcher and Fabric Loader version on Kat's machine.
- Desired journal workflow after the first UI smoke test.
- A manually checked real-log reference set and timezone policy.
- Performance budgets measured against real imports.
- Provider selected first; subscription authorization and inference feasibility.
- Permissibility and exact scope of nearby-character recognition.

## Phase 2 additions — accepted by Kat

- Import preview: eligible files, estimated workload and already known sources
  before committing a batch; cancel any expensive preview work safely.
- A durable import dashboard with per-file errors and retry-failed controls.
- Transcript filters across sources/channels and review-only unknown formats,
  retaining uncertainty about dates and timezones.
- Undo newly added records from a selected batch without touching original log
  files or previously imported data; define provenance/backup behavior first.
- An opt-in watched folder for stable finished historical files. Active logs and
  symlinks stay excluded; live capture/tailing remains the separate Phase 2b slice.

Kat accepted all these additions for Phase 2. Implementation and actual verification
are tracked in STATUS.md; an accepted requirement is not itself an enabled feature. Character identities, persona memories and AI remain in later phases.

## Accepted transcript refinement

Kat accepted all alpha.3 smoke tests and requested a scrollable conversation and
persistent dialogue without log wrappers. Alpha.4 uses a native scroll pane with
bounded pages, fixed controls and optional technical Details. Schema 4 removes the
redundant raw-line column and cleans existing text through the same injected parser
normalizer used for imports, after a verified schema-1/2/3 backup. Source IDs,
coordinates, clocks, policy history, ownership and undo remain separate metadata.
Recognized PM/OOC is excluded; known system notices and blank chat are ignored.
Unknown bracketed actions and quoted markers inside dialogue are preserved.

Parser policy 2 refuses to resume policy-1 partial imports. Finish incomplete
alpha.3 imports before upgrading, or use the pre-upgrade backup with alpha.3 to
finish them, then upgrade again. Completed-source identities/classifications stay
frozen; cleanup does not silently re-import removed records. Migration reclassifies
removed non-dialogue observations into excluded/ignored counters atomically, keeping
processed positions fixed. Existing backups/original log files can retain raw bytes;
this is not a secure-erasure feature.

## Toolchain

- Java 21 target and runtime.
- Minecraft 1.21.11 with official Mojang mappings.
- Fabric Loader 0.19.5 and Fabric API 0.141.6+1.21.11, from Fabric's 1.21.11 example.
- Loom pinned to 1.14.10; do not use the template's moving snapshot.
- Gradle wrapper 9.7.1, copied from the Fabric 1.21.11 example.
- SQLite JDBC 3.51.3.0.
- JUnit 5.12.2.

