# Requirements and decisions

## Established by Kat

- Fabric and Minecraft 1.21.11.
- In-game interface; historical and live capture; compressed log imports.
- Phase 2 includes **Import all logs**: a user-selected folder, bounded background
  file queue, existing deduplication/privacy/recovery rules, batch progress and
  per-file outcomes. The accepted workflow includes preview, a durable dashboard, retry and safe undo.
- Two actual SQLite files: archive.sqlite for imported/live dialogue and characters.sqlite for characters/personas; no unrelated old-database importer.
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

## Accepted roleplay/noise filtering refinement

Kat supplied examples of persona commands/search results, map hints, friend
requests, news, Soulstone notices, lock replies and build/region diagnostics in
the dialogue view, and requested a meaningful roleplay-only filter. The classifier
uses deterministic whole-message server templates plus positive original IC-channel
and sender/emote evidence; generic Minecraft `[CHAT]` alone is insufficient.
Privacy checks take priority. Reviewed-name observations remain explicit input;
channel evidence does not infer a speaker, character, account link or persona.

New imports can classify recognized IC dialogue as ROLEPLAY and exclude recognized
system output from storage. Other unmarked/ambiguous messages remain UNKNOWN for
review. The view keeps All dialogue available and adds a direct Roleplay only
control. Persona lookup output is system metadata, not RP dialogue or a confirmed
identity link; future identity work can inspect original sources separately.

Schema 5 makes a verified backup and removes recognized notices from older imports,
updating source/owned-file ignored counts without moving cursors or breaking undo.
Old cleaned rows lost their IC-channel wrappers, so migration does not invent RP
proof or promote old UNKNOWN rows from wording alone. Explicit ROLEPLAY records
resembling notices are preserved; truly indistinguishable plain-text cases can
remain for review. Parser policy 3 blocks incompatible older partial resumes;
finish alpha.4 imports before upgrading or use the matching pre-upgrade backup
and older mod first. Originals/backups remain untouched by filtering.

## Accepted favorites, scenes and live-log additions

Kat reports all tests passed so far and requests:

- Alpha.6: persistent individual-message favorites and a Favorites filter, plus
  main-screen View transcripts. Favorites are coordinate-based archive bookmarks;
  they do not confirm a speaker/identity or make UNKNOWN data AI-eligible.
- Phase 2b next: automatically started daily mod-local live-chat journals with a
  readable format, per-message saving, crash recovery and a pause setting. This is
  explicitly requested automatic capture, distinct from opt-in finished-folder
  watch. Privacy/noise filters run before file or database persistence. Authoritative
  durable event IDs/checkpoints precede derived readable exports; never text-dedup
  genuine repeated emotes. It remains disabled until implemented and verified.
- Phase 3: reversible explicit identity/persona assignment for bookmarks and future
  extracted records; ask when uncertain and allow unassigned/skip. Existing persona
  command text is not a confirmed identity source.
- Phase 4: scene view, continuous-dialogue/location grouping and participant lists.
  During import review ask about uncertainty; allow skip and durable manual
  overrides. Missing locations/participants stay unknown, and mentions are not
  automatically attendees. Review must not block an open database transaction.
- Phase 6: optional favorite-driven item/name/task proposals, appropriate record
  types, evidence, user-selected identity and confirmation when uncertain. Preserve
  manual edits/rejections and avoid duplicate generation. AI stays optional/off.

These are accepted requirements, not claims that live capture, scenes, identity
links or extraction are enabled. See MILESTONES.md for acceptance criteria and
LIVE_CHAT_LOG_DESIGN.md for the source review. No third-party Chat Logs dependency
or code has been added.

## Toolchain

- Java 21 target and runtime.
- Minecraft 1.21.11 with official Mojang mappings.
- Fabric Loader 0.19.5 and Fabric API 0.141.6+1.21.11, from Fabric's 1.21.11 example.
- Loom pinned to 1.14.10; do not use the template's moving snapshot.
- Gradle wrapper 9.7.1, copied from the Fabric 1.21.11 example.
- SQLite JDBC 3.51.3.0.
- JUnit 5.12.2.


## October 26 continuation — authorized Phase 2b / 3 implementation

Kat reports an individual-selection usability defect and authorizes live capture
and identities/personas. Alpha.7 removes implicit first-message selection and shows
an unmistakable chosen row; automated input must exercise a non-first wrapped row
after scrolling, not infer this from a first-row star. Automatic received capture,
durable daily journals and a manual identity/persona slice are implemented; see
STATUS.md for actual checks. Persona notes in the shared archive are administrative
metadata, not automatically shared persona knowledge. Memory searches/favorites
are restricted to explicit owning-persona assignments. No account/persona link is
inferred from operational notices. The then-pending previous-database importer
was withdrawn by Kat in the character-browser follow-up below.

Comma-titled persona replies now belong to policy 4. Older partial jobs must finish
under their original policy. Backed-up schema 8 migration cleans uncertain legacy
noise and retains previously proven roleplay/undo/source identities. Exact wording
alone cannot safely discard every historically mislabeled server notice.

## Character browser and native database — accepted October 26

Kat reports live recording seems OK, but cannot review/list saved characters and
Use as persona stays disabled. Alpha.8 adds a visible searchable/paged list and a
separate saved-character editor, preserves exact UUID/drafts across refresh/resize,
and enables persona activation only for a persisted, unchanged record. This fixes
an existing discoverability/selection bug; the earlier create-and-immediately-use
smoke did not prove reopening a saved character worked.

Kat explicitly withdraws the previous-database importer requirement. It is removed
from active milestones and no schema/file clarification is pending. The native
schema 8 already stores all mod data in config/lotc-journal/journal.sqlite; retain
it and its records. Historical .log/.log.gz import and live recording populate the
same dialogue archive, with manual character records and explicit identity/persona
confirmation alongside it. No reset or replacement database is needed. Native
schema migrations/backups remain necessary and distinct from a legacy importer.

## Two-file storage and observed characters — October 26, 2026

Kat explicitly selected **two SQLite files**, superseding the alpha.8 single-file
choice above. Imported logs and live recordings share `archive.sqlite`; character
records, aliases, personas, assignments and derived name evidence use
`characters.sqlite`. Preserve the old native `journal.sqlite` as a recovery source
when upgrading. Both members form one bound storage pair; backup/restore the pair.

Kat also requests automatic creation of characters observed speaking/acting in
imported logs, and archive searches when creating/editing a character or aliases.
The same indexing applies to live observations. Only trusted ROLEPLAY plus anchored
name/speech/action grammar discovers a character. Literal whole-token name/alias
mentions are a separate classification and may include UNKNOWN archive text;
that does not reclassify its channel or make it eligible for AI. Ambiguous shared
names/aliases remain unresolved. Original observations and parser policy stay
unchanged. Account ownership, merges and persona memories still require review.
The first observed spelling remains linked after renaming an automatic character
to prevent rediscovery as a duplicate; user aliases can be removed independently.

## Identity denial — October 27, 2026

Kat requests a **Deny link** action in identity review, reports persistence is good
and no persona-separation issue so far. Implement denial as an unordered, persistent,
reversible keep-separate decision. It blocks both direct linking and bridges between
the current resolved groups. Require explicit clearing before later linking; clear
does not imply acceptance. An existing positive link must be undone first. Denials
do not rewrite dialogue, aliases, favorites or persona knowledge and must survive
backups and future analysis. These user checks do not complete automatic-tag
correction, scene detection, optional AI or large-archive performance acceptance.

## Per-message tag review — October 27, 2026

Kat reports alpha.10 smoke tests passed and asks to continue. The next Phase 3
slice provides durable corrections/rejections of automatic archive character tags.
The user edits an exact per-message tag list (identity plus SPEAKER/ACTION/MENTION),
can save a reviewed-empty list to reject all tags, or explicitly restore current
automatic suggestions. Source text/channel and persona assignments are unchanged.
UNKNOWN can receive only MENTION interpretations; this does not make it RP or AI
knowledge. Duplicate names remain distinguishable by UUID.

Implementation stores the review separately from the rebuildable automatic index.
Saved lists survive alias edits, reimports, live replay, restart and paired backups.
Optimistic revisions prevent stale edits; restoring automatic mode retains a revision
marker so an older draft cannot overwrite it. Save supports at most 64 reviewed
tags with explicit limits; larger automatic sets are not silently truncated.

## Through Phase 4 — October 27, 2026

Kat authorizes completing the remaining Phase 3 implementation and Phase 4.
Shared related-dialogue views project the reversible transitive confirmed identity
component, deduplicating exact source coordinates. Neither merges nor tag reviews
rewrite original observations or grant any persona knowledge.

Scenes are conservative source/session-local RP windows, with a configurable
15-minute inactivity default (1–120 minute bound), strict greater-than boundary,
clock reversal/missing-clock splits, explicit ownership-context boundaries and
5,000-message chunks. Windows may contain interleaved conversations; grouping is a
suggestion, never confirmed identity/location/interaction proof. Missing historical
locations/dates/timezones stay unknown. Manual location entry is an interpretation.

Scene metadata and memberships live in characters.sqlite schema 4, separate from
immutable dialogue in archive schema 9. Confirmed/skipped exact memberships and
participant edits persist across refresh/settings changes/import/restart/backup.
Optimistic revisions and inactive header tombstones protect against stale/ABA
writes. Split/combine are atomic coordinate operations; source undo trims only
owned membership and invalidates affected revisions. Review occurs after commits,
with Skip for now and later correction; no import transaction waits on UI.

Shared automatic participants use effective SPEAKER/ACTION tags and confirmed
identity groups, never MENTION alone. Manual participants can include a silent
attendee. DIRECT/OVERHEARD requires an explicit persona perspective; this grants no
knowledge. Persona scene reads project every message/count from explicit owned RP
assignments, hide shared titles/locations, and expose only their own explicit
SPEAKER identity IDs without resolving global links. Unknown assignment identities
remain unknown. Their interpretation counts derive from their own assignment and
owned text syntax, so shared tag/merge edits do not expose a secret identity or
change their knowledge. Shared manual attendees never become persona memories.

Speech/action/mention totals count observations, not exact reconstructed emotes.
Active time estimates sum adjacent gaps capped at 60 seconds; elapsed/active are
unknown for missing, mixed-source/context or reversed chronology. Cross-source
manual combine is allowed explicitly but cannot invent a common clock/location.
AI remains off/unimplemented; scenes work entirely offline. Real-scene acceptance,
large-archive budgets and actual modpack backup restoration are separate checks.

## Alpha.13 scene/review refinements — October 28, 2026

Kat requests minimum 10 messages and 2 participants for creating a scene, automatic
confirmation except low confidence, Suggested-first listing and an explicit
Suggested filter; identity review must advance through a queue; main search must
respond visibly. These supersede the prior all-suggestions automatic scene policy.

Shared automatic eligibility counts trusted RP observation coordinates and distinct
resolved effective SPEAKER/ACTION identities. Mentions and inherited/manual silent
attendees do not prove actors. Newly split halves and combined scenes also meet the
minimum; preserve existing explicit small scenes instead of deleting user work.

High confidence means every message has one resolved actor, complete common-context
monotonic timing, no gap over 300 seconds and at most8 distinct actors. Eligible
uncertain groups remain Suggested with reasons; high-confidence groups are
Confirmed(auto). This confirms grouping only. Default15-minute boundary remains
configurable; missing dates/locations and identities are never fabricated. Shared
lists order Suggested first, then newest within status; Suggested-only intersects
search/skip filters. Automatic confirmed groups can be reassessed until an explicit
Save/Skip/split/combine sets MANUAL provenance. Explicit Save of an unchanged AUTO
scene still freezes it. Schema5 persists AUTO/MANUAL origin and shared qualification.

Neutral automatic windows remain internally for persona projections, including
windows never eligible as shared scenes. Shared tag/merge changes therefore cannot
remove a persona's assigned evidence window or disclose shared confidence. Persona
views ignore shared qualification/status filters, retain raw explicit attribution,
and expose generic origin/assessment. Character schemas 1–4 upgrade with a verified
paired backup; native archive schema 9/parser policy 4 stay unchanged.

Identity review queues only literal normalized saved-name/alias overlaps between
unlinked groups, excludes rooted keep-separate decisions and collapses confirmed
components. It never queues the Cartesian product of unrelated characters or uses
account observations as identity proof. Full queue metadata is read in one storage
snapshot, separately from the 1000-row character-browser limit. Only 100 pending
pairs are returned at once, with a more flag and exact matching-name hints; saved
decisions exclude processed pairs after restart. Confirmation/denial refreshes
persistent state before advancing; failure retains selection, undo reopens eligible
candidates. Pending actions survive resize. Manual selectors remain available.

Main search now provides debounced match feedback and Enter navigation into the
actual filtered Characters list. Queries survive resize, including beyond the
EditBox default 32-character limit. Editing a query clears completed operation
feedback; a fresh operation result remains visible until the next query edit.
Search/list work stays on bounded workers and loading hides stale scene results.

## Phase 4 synthetic scale verification — October 28, 2026

The next unfinished engineering check is measured archive/scene/identity-queue scale,
not Phase 5 AI integration. Add an opt-in synthetic storage workload and separate
source-path-triggered CI job. Keep runtime version/schema and dependency pins
unchanged when only verification tooling changes. Preserve the existing native
coordinator/build/verification/review workflow.

The reference workload and generous timeout ceilings are engineering regression
criteria declared before measurement. They are not user-approved interactive
performance budgets. Record known-character imports separately from fresh automatic
discovery/backfill, and storage-port reads/writes separately from parser, Minecraft,
Windows modpack and concurrent live-capture behavior. Do not generalize a passing
storage workload into real-scene accuracy or private persona acceptance. Only
synthetic report artifacts may be uploaded; databases and transcript payloads stay
outside the artifact selection. See ARCHIVE_SCALE_TESTS.md and actual STATUS.md
results. Real-log scene acceptance and alpha.13's focused user checks remain open.

The first reference run failed the scene-search ceiling (466.454s versus60s).
Alpha.14 scopes effective actor evidence to each scene before UNION, retaining
manual overrides, reviewed-empty suppression, restored AUTO and persona raw
assignments. Existing message indexes suffice; schema and parser policy remain
unchanged. Retain the failed evidence and original workload/budgets.

The follow-up run measured notes-only saves taking 108.811s, the same full archive
reindex as an alias edit. Alpha.14 compares old exact names/alias sets under the
existing write reservation and skips index rebuilding only for unchanged inputs.
Notes/account values still persist; case-only renames, alias add/remove and new
identities retain backfill and atomic rollback. Trigger-guard tests preserve exact
evidence, reviewed tags, favorites, cursors and persona assignments.
