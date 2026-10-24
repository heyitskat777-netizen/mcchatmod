# Current status — October 24, 2026

## Cloud continuation — feature/foundation-build

The repository is available at `/workspace/mcchatmod`; the project is in
`lotc-character-journal`. The empty local checkout was populated from the selected
GitHub `main` commit `2b39688b9ec728e76efab429e38058e0ea746216` without deleting
local work or changing the saved repository selection. Development continues on
`feature/foundation-build`; `main` remains unchanged.

Phase 1 remains incomplete. This session fixes the Unix executable bit on the
Gradle wrapper and excludes generated build/cache/game-run directories from Git.
The uploaded checkout did not contain a GitHub Actions workflow. A root-level
`.github/workflows/build.yml` now runs the core checks followed by the required
Fabric build on pushes and pull requests, with test-report and JAR artifacts.
The workflow has been parsed locally; execution on GitHub has not been observed.

### Current-instance verification

The provisioned machine initially had a Java 21 runtime without `javac`. A
Temurin JDK **21.0.13+9** was installed outside the checkout and verified against
the publisher's SHA-256 checksum. Gradle remains **9.7.1**. Minecraft and Fabric
dependency pins are unchanged. Java uses the provisioned system trust store;
certificate verification was not disabled.

Commands run from `/workspace/mcchatmod/lotc-character-journal`, with this
machine-specific activation kept outside repository build configuration:

```sh
export JAVA_HOME=/workspace/toolchains/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/workspace/.gradle
export JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts'
./gradlew -PcoreOnly check --no-daemon --console=plain
./gradlew check :fabric:build --no-daemon --console=plain --info
```

- Core: **passed**, 29 tests, 0 failures, 0 errors, 0 skipped, confirmed from the
  current JUnit XML reports. Architecture verification also passed.
- Full build: **blocked before compilation**. The diagnostic run reports HTTP
  **403** fetching the Loom plugin marker from `https://maven.fabricmc.net/`.
  Direct connectivity checks also report proxy CONNECT 403 for that host and
  `piston-meta.mojang.com`. This is an environment network restriction, not
  evidence of a client source defect or a successful Fabric build.
- Earlier attempts diagnosed the unwritable default Gradle cache, absent JDK,
  Java's lack of automatic proxy discovery, and the downloaded JDK's missing
  proxy trust anchor. Writable cache, explicit proxy options, JDK installation,
  and the provisioned system trust store resolved those prerequisites.
- The reusable environment install script passed when executed again; Gradle
  reused valid task outputs (13 up-to-date tasks, architecture check executed).
- `git diff --check`: passed. Workflow YAML parsed and expected job/step commands
  inspected locally. This does not establish GitHub Actions execution.
- Minecraft startup, client gametest, screenshots, modpack compatibility, and
  Kat's Phase 1 smoke test: **not run**. No EULA acceptance was supplied, and no
  installable JAR was produced.

### Saved configuration and next action

The environment draft now contains tested `install_script` contents and
`start_skill` activation/check instructions. Required custom network additions:
`maven.fabricmc.net`, `piston-meta.mojang.com`, `piston-data.mojang.com`,
`libraries.minecraft.net`, and `resources.download.minecraft.net`. Existing
package-manager presets and the repository selection were preserved.

Review and save those changes in environment settings, then publish the prepared
environment. Draft persistence does not apply network policy or publish a
snapshot. After policy application, retry the full command above and investigate
any subsequent compilation/packaging failures. Fresh-task restoration has not
been tested. The older socket-probe blocker below describes the original host;
this host has not yet reached Loom initialization. Do not start Phase 2 on the
assumption that Phase 1 passed.

## Original implementation record

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

