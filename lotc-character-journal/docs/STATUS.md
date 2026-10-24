# Current status — October 24, 2026

## Outcome

Implementation has started. The independent Java core is verified.
The Fabric client adapter is written but is NOT compiled or tested in Minecraft.
No installable mod JAR is available from this run.

| Phase | State |
| --- | --- |
| 0 — Discovery | Target and architecture established; current legacy schema inspected read-only. Real-log gold set, modpack compatibility, performance budgets, and subscription access still pending. |
| 1 — Foundation | Core verified; Fabric shell and client-test source written; full build blocked by hosted environment. User smoke test pending. |
| 2 — Capture | Conservative parser prototype with synthetic tests only. No import pipeline or live capture. |
| 3–7 | Planned; not implemented. |

## Verification completed

- Java 21 compiler and JUnit console: 29 tests passed, 0 failed, 0 skipped.
- Gradle 9.7.1, Java 21: `gradle -PcoreOnly check --no-daemon --console=plain`
  passed, 14 tasks executed. All 29 tests also passed through Gradle.
- Architecture check passed through Gradle. A deliberate temporary forbidden
  network import into domain was also rejected; the probe was removed.
- SQLite tests include restart persistence, snapshot backup restoration, refusing
  an existing backup, rejecting newer/unrelated databases, and leaving corrupted
  bytes intact.
- Application tests include worker-thread execution, serialized changes,
  initialization failure, shutdown rejection, and bounded-queue overload.
- Parser tests cover known observed names, original clocks/source coordinates,
  PM/OOC exclusions, unknown channels, name-prefix ambiguity, and malformed lines.
- Current legacy master: read-only quick_check passed. See LEGACY_DATABASE_DISCOVERY.md.

These checks do NOT establish persona isolation, live capture correctness,
full historical parsing support, successful client startup, AI accuracy, or
compatibility with Kat's actual modpack.

## Full Fabric build blocker

Command attempted:
`gradle check :fabric:build --no-daemon --console=plain --stacktrace`

The host used the downloaded Gradle distribution with its existing network proxy
configured on the command line. No host-specific proxy settings are in the project.

Loom 1.14.10 stops during configuration, before compiling client source:

```text
java.lang.ExceptionInInitializerError
  at net.fabricmc.loom.util.Platform.<clinit>
Caused by: java.io.UncheckedIOException: java.net.SocketException: Operation not permitted
  at net.fabricmc.loom.util.CurrentPlatform.isUnixDomainSocketsSupported
```

The host denies the Unix-domain socket probe. No permissions were escalated and
no dependency binary was patched to avoid it. Run the full build in a normal
local development environment to establish whether additional project errors exist.

An initial attempt with Loom 1.18.4 also established that it requires Java 25.
The project was corrected to pin Loom 1.14.10, whose published runtime target is
Java 21. This is a resolved toolchain choice, not an outstanding bug.

## Next work in local Codex

1. Use JDK 21 and run `./gradlew check :fabric:build`
   (Windows: `.\\gradlew.bat check :fabric:build`).
2. Fix any client compilation or packaging failures. Do not assume the adapter
   compiles merely because the core tests pass.
3. Compile and run the client gametest after preparing the runtime and accepting
   Minecraft's EULA. Inspect its screenshot and journal behavior.
4. Produce the remapped JAR; run USER_TEST_PHASE1.md in Kat's modpack.
5. Record actual versioned results here, then begin Phase 2 import/capture work.

## Remaining decisions

The actual modpack/launcher is unknown. Statistics and PM/OOC storage defaults
remain provisional. Subscription access has not been tested or implemented.
The complete accepted/proposed split is in DECISIONS.md.

## Contents and data safety

The source package contains code, synthetic tests, configuration, documentation,
and the standard Gradle wrapper. It excludes the private legacy database, RP logs,
credentials, downloaded Minecraft files, build caches, and local toolchain.

The source is ready to continue in Codex; it is not a finished mod release.

