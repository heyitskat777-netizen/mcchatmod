# LoTC Character Journal — Milestones and Verification Plan

Status: roadmap; initial implementation underway. See STATUS.md for execution state.
Prepared: October 24, 2026.

## Goal and delivery approach

Deliver one client-side Minecraft mod with an in-game journal, historical and live dialogue capture, its own local character database, separate persona memories, editable identity decisions, encounter histories, and optional AI analysis.

Each phase produces something concrete to inspect or test. User testing checks whether the result reflects actual roleplay; developer verification checks correctness, recovery, performance, and module boundaries. A phase is complete when its acceptance criteria pass and material findings from its user test are resolved or explicitly deferred. Internal development and automated checks do not require separate user approval at every step.

Suggested test durations below are observation windows, not development estimates. Record each reviewed build version so findings remain reproducible.

## Requirements carried forward

- Import historical logs and compressed archives; ingest new dialogue during play.
- Import the previous character database and confirmed corrections after inspecting its actual format.
- Separate account observations, character identities, names, aliases, and knowledge held by each of your personas.
- Allow manual records, annotations, identity corrections, scene edits, and participant corrections.
- AI can suggest aliases, scene refinements, summaries, relationship changes, and unfinished business. Linking or merging identities requires confirmation.
- Distinguish direct interaction, overhearing, and mentions, with uncertainty visible.
- Private messages and OOC are excluded from AI analysis. Excluding them from journal storage is a proposed default awaiting Phase 0 review.
- All ordinary journal functions work with AI disabled. Previously saved AI results remain viewable.
- AI provider options are disabled, a local endpoint, a hosted API, and a subscription connection if feasibility and account access are verified.
- Emote counts and estimated active interaction time remain provisional requirements because question 13 was skipped.
- Nearby recognition remains a separately scoped feature pending technical and server-rule review.

## Milestone overview

| Phase | Milestone | Review or user test | Completion evidence |
| --- | --- | --- | --- |
| 0 | Scope, compatibility, and evidence samples | Review a short design and sample parser output | Target environment, data policy, module contracts, and test fixtures established |
| 1 | Installable modular foundation | Brief install and interface smoke test | Loadable mod, offline journal shell, safe persistence, dependency checks |
| 2 | Reliable historical and live capture | Compare a known log and a normal play session | Source-traceable records; repeat imports and restarts preserve counts |
| 3 | Character identity and persona memories | Test known characters, switches, and a deliberate wrong match | Reversible identity decisions and isolated persona views |
| 4 | Encounters, transcripts, and statistics | Test quiet, crowded, and interrupted scenes | Editable scenes and understandable, evidence-backed counts |
| 5 | Optional AI and encounter summaries | Compare summaries with original dialogue | Validated provider connection, grounded results, safe offline fallback |
| 6 | Relationships, commitments, and identity suggestions | Judge suggestions against several real scenes | Reviewable conclusions with evidence and persistent corrections |
| 7 | Everyday beta and release candidate | Use through several sessions, ideally one week | Recovery, performance, upgrade, compatibility, and usability evidence |

## Phase 0 — Scope, compatibility, and evidence samples

**Deliverables**

- Confirm the exact Minecraft version, loader, Java requirements, operating system, launcher, and existing mod list.
- Inspect representative real logs and the current prior-database schema before designing import mappings.
- Establish a small manually checked sample set: ordinary conversation, crowded room, persona switch, midnight rollover, multipart emote, duplicate-looking messages, ambiguous alias, PM, and OOC.
- Document module responsibilities and allowed dependencies. Keep Minecraft integration, persistence, and provider-specific code outside the domain logic.
- Prototype only the uncertain integrations needed to establish feasibility: chat metadata and subscription authorization, if selected.
- Record initial decisions about retention, backups, capture exclusions, persona selection, performance targets, and recognition scope.

**Developer verification**

Each sample has expected channel, speaker observation, timing confidence, and source location. Unknown information stays unknown. No previous-database identity or schema is assumed from conversation descriptions.

Subscription access is a feasibility check, not a dependency of the journal. An unavailable connection is recorded clearly and does not silently select another billing method.

**Kat's review**

Review the proposed persona workflow, excluded channels, recognition controls, and illustrative character page. Confirm whether to retain statistics, the proposed 15-minute scene inactivity threshold, and the intended AI default.

**Exit criterion**

The initial compatibility target and behavior are recorded, the sample set is usable for regression checks, and unresolved items have explicit defaults or deferred scope.

## Phase 1 — Installable modular foundation

**Deliverables**

A first installable development JAR with a configurable journal key, an empty character search screen, settings, version display, local database initialization, and a minimal diagnostic view. Establish migrations and backup primitives before importing existing user data.

Use feature modules within a shared process and artifact. Each feature owns its logic and data access through narrow interfaces. Use explicit dependency wiring and a small startup coordinator.

**Developer verification**

- The core runs without Minecraft or a model provider.
- Dependency checks prevent circular references and forbidden imports between modules.
- The mod loads and closes cleanly, and a second launch opens the existing database.
- Slow work is scheduled outside the game thread; UI updates return through the client thread.
- AI disabled causes no analysis network requests.

**Kat's test — approximately 10–15 minutes**

Install alongside your usual mods, open the journal, change its keybind, adjust GUI scale, join and leave a world, and restart. Check text readability and whether controls feel natural.

**Exit criterion**

The shell is usable in the target installation and has no known loading, keybind, or persistence blocker. No character-recognition accuracy is claimed at this stage.

## Phase 2 — Reliable historical and live capture

**Deliverables**

Historical log and supported archive import, live chat capture, channel filtering, timestamp provenance, source locations, import progress, and a transcript inspector. Unrecognized formats go to a local diagnostic/review path rather than being confidently attributed.

Historical logs and live events share a normalized message representation. Reconcile overlapping sources without collapsing genuine repeated emotes merely because their text matches.

**Developer verification**

Test repeat import, interrupted import, log rotation, truncated files, partial lines, midnight, multipart messages, and restart recovery. Stable source identities and saved positions make processing repeatable. Clock and timezone uncertainty remains visible.

Check exclusions before storage according to the chosen policy and again before any later AI context construction. Uncertain channel classification is ineligible for AI until resolved.

**Kat's test — one known log and a 30–60 minute play session**

Compare a short familiar conversation with the transcript inspector. Include RP, OOC, and a private message to verify the chosen exclusions. Restart and re-import the same source; inspect the resulting counts.

**Exit criterion**

The reviewed sample has no unexplained missing or duplicated eligible messages. Every displayed message can be traced to a received event or original source. Recovery tests pass.

## Phase 3 — Character identity and persona memories

**Deliverables**

Character pages, manual notes, account observations, aliases, persona selection, reversible identity decisions, and the prior-database importer with a dry-run report. Back up before applying an existing-database import or schema migration.

Store raw observations separately from attribution decisions. A shared archive must not make all of its facts available to every persona. Ambiguous historical ownership stays unassigned until reviewed.

**Developer verification**

Test identical names belonging to different characters, multiple personas on one account, renames, explicit aliases, ambiguous aliases, merge reversal, and corrected attribution. Verify persona restrictions in searches, statistics, transcript navigation, exports, and AI context preparation.

**Kat's test — two of your personas and several known characters**

Switch personas, inspect which memories appear, deliberately make a wrong identity link, undo it, and verify the affected history. Inspect the legacy import's corrections and unresolved conflicts.

**Exit criterion**

Identity decisions are reversible without losing source dialogue; the reviewed personas expose only their assigned knowledge. AI cannot confirm identity merges.

## Phase 4 — Encounters, transcripts, and statistics

**Deliverables**

Deterministic encounter suggestions based on time gaps, participants, and interaction cues; split/combine controls; participant corrections; transcript search; direct/overheard/mentioned labels; and the confirmed statistics scope.

The initial 15-minute inactivity threshold is configurable. Estimate active dialogue time separately from elapsed scene duration, with a visible explanation of how gaps are counted. Preserve uncertainty in reconstructed emote totals.

**Developer verification**

Test overlapping conversations, a silent participant, mentions without meetings, long pauses, departure and return, persona changes, and multipart messages. Splitting or combining a scene must not duplicate source records. Corrections update dependent views and invalidate outdated analysis.

**Kat's test — at least three contrasting scenes**

Use a quiet conversation, a crowded group event, and an interrupted scene. Review the proposed boundaries and classifications. Split a scene and correct participants; inspect counts afterward.

**Exit criterion**

Grouping is useful and correctable, estimates are understandable, and any unavoidable ambiguity is visible. Record false joins and false splits to tune the rules.

## Phase 5 — Optional AI and encounter summaries

**Deliverables**

A provider-neutral analysis interface, persistent job queue, cancellation, retries, request limits, result caching, and summaries with message-level evidence. Implement and verify the provider chosen in Phase 0; treat additional providers as separate adapters with their own connection tests.

Start with a fake provider for failure testing. A subscription adapter remains conditional on a successful supported authentication and inference test. AI can be disabled at any time, and quota failure never triggers an automatic paid fallback.

**Developer verification**

- PM, OOC, uncertain channels, and other personas' knowledge cannot enter requests.
- Generated claims cite eligible source IDs; invented or unrelated citations are rejected.
- Transcript text is data and cannot issue application commands.
- Model output cannot execute SQL, modify source records, or confirm identity links.
- Source or attribution changes make pending/outdated results ineligible to overwrite newer state.
- Test timeouts, disconnection, invalid output, throttling, revoked credentials, and repeated jobs.
- Credentials are excluded from logs, exports, and ordinary database backups.

**Kat's test — three to five completed scenes**

Compare each summary with its transcript. Check participants, important actions, uncertainty, and missing context. Disconnect or disable the provider and continue using the journal.

**Exit criterion**

All displayed substantive summary claims have valid evidence; incorrect interpretations can be flagged or corrected; stale results cannot replace current work; the journal works fully without AI.

## Phase 6 — Relationships, commitments, and identity suggestions

**Deliverables**

Dated and directional relationship developments; promises, debts, meetings, and resolutions; alias suggestions with evidence; and a review queue with accept, reject, edit, and keep-separate actions.

Confirmed corrections and rejected identity suggestions persist across re-analysis. AI-assisted encounter refinement is a proposal and preserves manual scene edits.

**Developer verification**

Test sarcasm, quoted speech, threats, hypothetical promises, changed relationships, duplicate commitments, and contradicting passages. Prevent a mention from becoming a direct encounter and prevent interaction volume from becoming a friendship claim.

A confirmed link updates dependent results; rejected suggestions are not repeatedly presented unchanged. New evidence can reopen a suggestion with its change explained.

**Kat's test — several scenes with different outcomes**

Review friendly interaction, conflict, an explicit commitment, a completed commitment, and an ambiguous identity. Test whether the reminders are useful and how quickly incorrect suggestions can be dismissed.

**Exit criterion**

Evidence and interpretations are distinguishable, relationship direction is correct, review controls work, and corrections survive regeneration. Uncertain interpretations remain suggestions.

## Phase 7 — Everyday beta and release candidate

**Deliverables**

A release candidate, installation/update instructions, backup and restore controls, portable export, known limitations, and a concise troubleshooting guide. Pin dependency versions and include license notices appropriate to distribution.

**Developer verification**

Measure startup, capture latency, journal search, memory, queue growth, and import throughput against the archive and targets established in Phase 0. Test supported modpack compatibility, interrupted writes, upgrade migrations, a restored backup, malformed inputs, and disk-write failures.

A backup test restores a copy and checks records and corrections; creating a backup alone is insufficient. Full recovery, persona-isolation, dependency, and AI-disabled regression suites must pass.

**Kat's test — several sessions, ideally about one week**

Use the mod for normal play across persona switches, restarts, busy events, and at least one larger import. Track crashes, missing dialogue, wrong identity matches, slow screens, confusing controls, and unwanted reminders. Include an AI-disabled session.

**Exit criterion**

No unresolved data-loss, persona-isolation, duplicate-import, or game-stability blocker. Performance meets the agreed targets, restoration succeeds, and remaining limitations are documented.

## Review record for every user-testing build

| Field | What to record |
| --- | --- |
| Build and environment | Mod version, Minecraft/loader, relevant modpack changes |
| Scenario | What was happening; persona and scene identifiers where useful |
| Expected behavior | What should have appeared or happened |
| Actual behavior | What appeared, including uncertainty or errors |
| Evidence | Local source/message IDs, diagnostic reference, optional screenshot |
| Impact | Data loss/isolation/stability blocker, accuracy issue, or usability issue |
| Resolution | Fixed, retested, deferred with explanation, or needs another example |

Do not put credentials or unrestricted raw private messages in diagnostic reports.

## Scope controls

The initial release is one client-side mod with a local database. A local model endpoint is an optional external inference service; the mod owns its own capture, journal, scheduling, and provider adapters.

Cloud synchronization, a server plugin, a standalone desktop app, multi-version support, and automatic nearby-player recognition are not prerequisites for the first release. Revisit them as independent milestones if requested.

Development has started with Phase 0 discovery and the Phase 1 foundation. STATUS.md records the verified implementation state; later phases and user verification remain pending.

