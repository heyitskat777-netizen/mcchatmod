# Phase 2b — daily live dialogue journal design

Implemented recording/replay slice in alpha.7; see STATUS.md for actual verification
and remaining cross-format overlap/user-acceptance limits. Not enabled in alpha.6. Target remains Fabric/Minecraft
1.21.11 and Java 21. Kat explicitly requests automatic start and per-message saving.
This design records the public-source review and verification contract; it is not
proof of crash durability until the implementation and fault tests run.

## Public Chat Logs reference

Reviewed **Raconteur32/chatlogs**, named Chat Logs in its metadata, version
0.1.0-alpha, commit
[98c4b7e970ec35e81213c3bc346addc207765b04](https://github.com/Raconteur32/chatlogs/tree/98c4b7e970ec35e81213c3bc346addc207765b04)
(September 20, 2025). Its build targets Minecraft **1.21.8**, Fabric API
0.131.0+1.21.8; permissive `minecraft >=1.21.3` metadata does not verify 1.21.11.
The GitHub source was accessible; Modrinth access was blocked. The repository
README retains older Permanent Chatlog material; source is the firmer reference.
If Kat meant another similarly named mod, this review should not be attributed to it.

The [session recorder](https://github.com/Raconteur32/chatlogs/blob/98c4b7e970ec35e81213c3bc346addc207765b04/src/main/java/fr/raconteur/chatlogs/session/SimpleSessionRecorder.java)
writes `chatlogs/<server-or-world>_<timestamp>.txt`, with session headers/footers
and `[yyyy-MM-dd HH:mm:ss] <display text>` lines. Its
[SQLite schema](https://github.com/Raconteur32/chatlogs/blob/98c4b7e970ec35e81213c3bc346addc207765b04/src/main/resources/database/sessions/v1.sql)
stores sessions/messages, timestamps and full text-component JSON. The recorder
uses ChatHud.addMessage and guards against restored ChatPatches history. The
[license](https://github.com/Raconteur32/chatlogs/blob/98c4b7e970ec35e81213c3bc346addc207765b04/LICENSE)
is CC0-1.0; dependency licenses remain independent. No dependency or code is copied
into this project by this slice.

Useful reference: separately readable chat files with timestamps. Unsuitable
unchanged: broader unfiltered content, an unbounded text queue, SQL in a message
callback, independently committed text/SQL, and flush without an explicit disk
force. We cannot claim its crash guarantees or compatibility for our mod.

## Exact supported Fabric events

The pinned API 0.141.6+1.21.11 provides
[ClientReceiveMessageEvents](https://github.com/FabricMC/fabric-api/blob/28e4019ae9dc6713d6797da19f60386debb25460/fabric-message-api-v1/src/client/java/net/fabricmc/fabric/api/client/message/v1/ClientReceiveMessageEvents.java)
(Apache-2.0): CHAT includes nullable signed-message/sender, chat type and receipt
Instant; GAME provides display component and overlay boolean. Plugin RP can arrive
through GAME, so CHAT alone is insufficient. Ignore overlays/action bars and do not
capture outbound commands or both send and echoed receive. Receive events exclude
messages canceled by other mods; GAME receives post-MODIFY_GAME text. Record those
coverage limits and test modpack interaction rather than promising every server byte.
JOIN/DISCONNECT and CLIENT_STOPPING establish session/lifecycle boundaries.

## Format, privacy and durability authority

- Start automatically when connected to a world, show capture state, and offer a
  persisted pause control. User request authorizes this default once Phase 2b is
  delivered; no extra opt-in is inferred from the separate finished-folder watch.
- Normalize/classify before any persistence, sharing historical privacy/noise rules.
  Do not save PM/OOC, known notices, outbound commands, raw components, hover/click
  JSON or inferred persona links. Ambiguous eligible text remains UNKNOWN locally
  and ineligible for AI. Never add raw catch-all fallback logging on a failure.
- Write owned UTF-8 `<gameDir>/lotc-journal/live/<date>/<session>-<segment>.lotc-chat.jsonl`
  files. Versioned records contain immutable event/session/sequence IDs, UTC receipt
  time, explicit local offset/zone, capture kind and parser policy separately from
  the cleaned dialogue. No server/account names in filenames. Document retention
  and disk usage; rotation is not automatic deletion of an archive.
- Make the journal the durability authority: append complete bounded records on a
  bounded background queue, force before durable acknowledgement, then apply event
  IDs and replay checkpoint atomically to SQLite. A daily readable `.chat.txt` view
  or export may be derived/rebuilt from these records; it is not a second independent
  authority. Show pending vs saved/error, not an unconditional 'saved' on enqueue.
- Save on every eligible received message, without waiting for clean shutdown. State
  the measured queue/unforced loss window honestly; OS/process death before force
  cannot be promised lossless. Disk-full/permission/force failures and queue pressure
  must produce visible bounded failure state without hanging the render thread.
- Replay stable event IDs idempotently after restart; validate/quarantine incomplete
  tails only within owned files. Never invent a clean session ending after a crash.
  Date/size rotation uses immutable segment IDs across midnight, DST and clock jumps.
  Protect against concurrent profiles/processes writing the same file.
- Growing daily files are not existing content-hashed immutable ImportSources.
  Add an explicit live provenance/replay port and versioned-format import adapter.
  Historical/live reconciliation preserves original provenance, repeated identical
  emotes, favorites and undone coordinates. Text/time equality alone is not proof
  of duplicate reception. Ambiguous overlaps remain reviewable.
- Location evidence, if implemented later, distinguishes the observing player's
  position from another speaker's position. Do not infer participants from mentions.

## Required evidence to close Phase 2b

1. Synthetic CHAT and non-overlay GAME events captured through actual Fabric hooks;
   overlays, canceled history/restoration, private/OOC/noise, paused capture and
   reconnects explicitly tested. No SQL/file operations on the render thread.
2. Abrupt kill/restart at append, force and SQL-checkpoint boundaries: acknowledged
   messages survive; replay neither duplicates nor invents messages. Incomplete tails
   recover without damaging earlier records or unrelated files.
3. UTF-8, embedded line breaks, repeated emotes, midnight/DST/clock changes, disk
   errors, bounded queue pressure, latency and concurrent-profile isolation.
4. Historical overlap/repeat import and daily-format import retain coordinates,
   uncertainty, favorites and undo decisions without text deduplication.
5. Development and packaged-JAR restart suites with explicit assertions, timeouts,
   screenshots, safe logs and reports. Environment failures remain distinct from
   application failures; after three unsuccessful repairs reassess/document.
6. A separate normal-play user checklist and private-source accuracy sample. Tests
   never connect unattended to the live LoTC server. Phase 2c remains incomplete
   until measured real-source differences are explained.
