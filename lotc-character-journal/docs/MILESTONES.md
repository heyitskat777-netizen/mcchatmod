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

Implementation is delivered in reviewable slices while retaining the full Phase 2
exit criterion below:

- **2a — Historical import and review:** finished `.log`/`.log.gz` validation,
  content identities, atomic checkpoints, cancellation/restart recovery, recognized
  channel exclusions, source-traceable transcript search/paging, and an **Import all
  logs** folder queue. The single-file importer is implemented; the folder queue
  now includes the implemented preview/dashboard/filter/undo/watch workflow.
  Alpha.3 smoke tests were accepted by Kat. The alpha.4 refinement adds a native
  scrollable dialogue conversation and backed-up cleanup of existing payloads;
  new refinement user testing is separate from alpha.3 acceptance. Alpha.5 adds
  positive RP classification and anchored system-notice cleanup while preserving
  uncertain observations in review; results are tracked in STATUS.md.
- **2b — Live capture and reconciliation:** shared normalized observations, live
  events or tailing, rotation/partial-line handling, and overlapping historical/live
  provenance without collapsing repeated emotes.
- **2c — Real-source acceptance:** manually checked private examples, unexplained
  count differences resolved, and measured import/capture performance budgets.

Completing 2a does not mark live capture or the entire Phase 2 complete.

**Accepted favorites/access refinement (alpha.6)**

- Bookmark individual observations, including repeated-looking dialogue, by source
  and coordinate. Provide visible star controls and a Favorites filter which
  combines with source/channel/search. A bookmark is not an identity or permission
  for AI analysis. Persist through restart and backups; undoing its message removes
  the bookmark without resurrecting observations on repeat import.
- Offer **View transcripts** directly on the main journal screen, including a useful
  empty state. Keep the import dashboard entry point too.

**2b — accepted automatic daily live-log requirement**

Kat requests Chat Logs-style recording in the mod's own dated folder, starting
without a manual command whenever connected to a world, with an explicit pause
control. Alpha.7 implements the live recording/replay slice; actual verification and
remaining cross-format overlap limits are recorded in STATUS.md. Use the
same RP/noise/privacy policy before any disk persistence. Capture received chat
and non-overlay server/plugin chat, not outbound commands or action bars. Save
on every eligible message through a bounded worker; display pending/error state.
Provide a versioned daily `.lotc-chat.jsonl` journal with immutable session/event
coordinates and a readable timestamp/dialogue representation, plus a derived
plain-text view/export. Do not put server addresses or account identities in names.

Prove append/force/checkpoint/replay behavior with abrupt-process-death tests;
acknowledged durable messages must survive crashes, while any unforced queue window
is measured and disclosed. Test disk errors, queue pressure, UTF-8/multiline
messages, midnight/clock changes, partial tails, restarts, and packaged loading.
Never model the growing file as an immutable historical content hash. Reconcile
historical overlap without text-only deduplication or losing favorites/undo.
See [LIVE_CHAT_LOG_DESIGN.md](LIVE_CHAT_LOG_DESIGN.md) for the inspected Chat Logs
reference, exact Fabric hooks, storage authority and acceptance criteria.

**Deliverables**

Historical log and supported archive import, live chat capture, channel filtering, timestamp provenance, source locations, import progress, and a transcript inspector. Unrecognized formats go to a local diagnostic/review path rather than being confidently attributed.

Historical logs and live events share a normalized message representation. Reconcile overlapping sources without collapsing genuine repeated emotes merely because their text matches.

**Import all logs — accepted addition**

- Let the user select a log folder and queue supported finished `.log`/`.log.gz`
  files. Default to that folder only; do not scan unrelated directories or silently
  follow directory symlinks. Nested-folder selection can be added explicitly later.
- Reuse existing per-file validation, classification policy, source identities and
  atomic checkpoints. Skip active `latest.log` and its resolved aliases. Recognize
  exact already-imported sources without inserting duplicate observations.
- Process files through bounded background discovery/queues. Show whole-batch and
  current-file progress, plus imported, already imported, excluded, unknown, skipped
  and failed outcomes with their units distinguished (files versus observations).
- A corrupt/unreadable/oversized file must fail individually with an actionable
  status; continue eligible independent files. Do not mark the batch fully passed
  if a file failed, and do not roll back unrelated successful imports.
- Cancellation stops further queue work and preserves committed checkpoints. A
  user-initiated repeat resumes eligible partial sources under their original
  policy and leaves completed sources unchanged. Restart must not silently launch
  filesystem scanning or resume a queue without user intent.
- Report the batch outcome and per-file failures without copying private chat text
  into diagnostics or changing source files.

**Accepted import workflow additions**

- Validate a read-only folder preview before confirmation, showing eligible files,
  encoded/decompressed workload and known sources without importing records.
- Persist batch/per-file progress, safe error codes and reviewed-name policy;
  provide explicit retry for failed/interrupted files after restart.
- Browse across sources and filter channels, including a review-only UNKNOWN queue.
- Confirm undo of only records newly inserted by a selected batch. Preserve original
  files, older imports and source checkpoint provenance. Keep payload-free removal
  accounting; repeating a completed source must not silently restore undone rows.
- Offer a persisted, explicit opt-in folder watch for finished stable archives only.
  Require two unchanged scans; exclude active latest.log, links and nested folders.
  This is not live chat capture and does not close Phase 2b.

**Developer verification**

Test repeat import, interrupted import, log rotation, truncated files, partial lines, midnight, multipart messages, and restart recovery. Stable source identities and saved positions make processing repeatable. Clock and timezone uncertainty remains visible.

Check exclusions before storage according to the chosen policy and again before any later AI context construction. Uncertain channel classification is ineligible for AI until resolved.

For Import all, use synthetic mixed folders containing plain/gzip duplicates,
distinct repeated lines, a corrupt archive, an oversized file, an active log,
directory/symlink entries and an incomplete prior source. Verify bounded queues,
per-file failure isolation, cancellation/restart/repeat behavior, stable counts,
and identical privacy exclusions to single-file import. Include actual UI smoke
coverage for folder selection, batch progress and the final report.

**Kat's test — one known log and a 30–60 minute play session**

Compare a short familiar conversation with the transcript inspector. Include RP, OOC, and a private message to verify the chosen exclusions. Restart and re-import the same source; inspect the resulting counts.

**Exit criterion**

The reviewed sample has no unexplained missing or duplicated eligible messages. Every displayed message can be traced to a received event or original source. Recovery tests pass.

## Phase 3 — Character identity and persona memories

Alpha.7 implements **3a — manual identities and persona assignments**: archive
names/aliases/notes/account observations, explicit persona selection, per-message
confirmation/unassigned, isolated memory views and reversible link overlays.
**3b — previous-database import** remains pending inspection of its actual schema.
**3c — private multi-persona acceptance** requires Kat's user checks. Do not mark
all Phase 3 complete merely because 3a's automated tests pass. Live capture leaves
knowledge unassigned until explicitly reviewed; selecting a persona is not proof
that every archived or received event belongs to that persona.

**Deliverables**

Character pages, manual notes, account observations, aliases, persona selection, reversible identity decisions, and the prior-database importer with a dry-run report. Back up before applying an existing-database import or schema migration.

Future favorites, scene participants and extracted records must use these identity
contracts. The user can explicitly tie a bookmark/record to a specific identity;
unknown or ambiguous attribution requires a confirmation prompt with evidence,
plus an unassigned/skip choice. Confirmed links are reversible and persona-scoped.
Never create a persona/account identity link from a command reply automatically.

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

**Accepted scene-view requirement**

Add a Scene view listing suggested continuous dialogue groups, location and observed
participants, with message-level provenance. Use time/session and explicit location
cues where present; missing historical positions, speakers or dates remain unknown.
A client position is evidence of the observer's location, not proof that every
speaker occupied it. A name mentioned in speech is not automatically a participant.

Present uncertain boundaries, locations and participant identities in the import
review flow after atomic observation commits. Let the user confirm/correct them,
choose **Skip for now / import without grouping**, or override automatic grouping.
No import worker/SQL transaction may wait for a user response. Persist skip and
manual override decisions across retry/restart; allow later review, split/combine
and participant edits without copying or deleting source dialogue. Do not silently
confirm an identity based on a scene suggestion. Scenes also work with AI disabled.
Test interleaved nearby conversations, missing locations, explicit location changes,
ambiguous names, skipped review, retries and manual overrides; record false joins
and splits separately from import correctness.

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

**Accepted favorite-driven extraction milestone (depends on Phases 3 and 5)**

Review starred messages for **item**, **name/character mention**, or **task/commitment**
records and propose the appropriate record type and fields with exact source
references. Let the user edit, accept or dismiss each proposal and select its
specific identity/persona. If identity cannot be established, ask for confirmation
or allow it to remain unassigned; never guess a confirmed owner, speaker or target.
An ordinary name mention alone cannot create/merge a character. Generation creates
reviewable interpretations, never rewrites original dialogue or silently resolves
identity links. A favorite alone does not make UNKNOWN/private/other-persona data
eligible for AI. Manual creation/editing remains available with AI off.

Test quoted/hypothetical items, ambiguous possessors, several identical names,
negative/completed tasks, repeated generation, source undo and corrections. Avoid
repeated duplicate records; preserve edits/rejections and invalidate stale results.
Unstarring a source does not silently delete an accepted user record. Accepted
records retain evidence and uncertainty even when the original is later removed.

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

Phase 1's user smoke test has been accepted; historical import is the current
Phase 2a slice. STATUS.md records exact implementation and verification results;
live capture, real-source acceptance, and later phases remain pending.

