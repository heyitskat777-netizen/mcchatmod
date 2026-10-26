# Requirements and decisions

## Established by Kat

- Fabric and Minecraft 1.21.11.
- In-game interface; historical and live capture; compressed log imports.
- Phase 2 includes **Import all logs**: a user-selected folder, bounded background
  file queue, existing deduplication/privacy/recovery rules, batch progress and
  per-file outcomes. The accepted workflow includes preview, a durable dashboard, retry and safe undo.
- One mod-created journal.sqlite for imported logs, live observations, characters, favorites and persona memories; no unrelated old-database importer.
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
