# Automated client smoke tests

Target: Minecraft **1.21.11**, Fabric, **JDK 21**. This suite uses synthetic local
worlds and generated test profiles. It never connects to LoTC or loads a personal
Minecraft directory. It does not establish compatibility with the user's modpack.

## Run

Build and core checks remain:

```sh
./gradlew check :fabric:build
python3 -m unittest discover -s scripts -p 'test_*.py'
```

On Linux, provide an X display with OpenGL support. GitHub Actions installs Xvfb
and Mesa, starts a virtual display, and runs the suite with software rendering.
For an existing display, activate JDK 21 and run:

```sh
LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null python3 scripts/client_smoke.py --eula-accepted
```

Pass `--eula-accepted` only after accepting the [Minecraft EULA](https://www.minecraft.net/eula).
Kat accepted it for this development test runtime in the setup session; the
explicit flag documents that acceptance for a run. `--preflight-only` establishes
the graphics-only launch before application testing. `--timeout 300` sets the
bounded per-launch timeout; preparation is bounded separately. Do not retry a
failure without reading its report and diagnosing it.

The runner prepares its required Gradle outputs itself. `--prepared` is for an
already completed `./gradlew :fabric:prepareClientSmoke` preparation. Do not use
it with stale outputs. The Gradle client tasks are implementation steps and need
the runner's isolation/report properties; use the Python entrypoint for complete
validation rather than treating a standalone task exit as a suite pass.

## Coverage and isolation

1. A production-namespace graphics preflight launches the test harness with
   Fabric API but without the journal. Failure here cannot be attributed to the
   journal. It must establish a working Minecraft title screen/rendering context.
2. The development writer enters a generated local world, uses the registered
   journal keybind, interacts with widgets, saves settings, creates/checks a
   backup, closes the screen, leaves the world, and shuts down.
3. A second development JVM opens the same test profile and verifies the saved
   settings before exercising the journal again.
4. The packaged writer/reader repeat those checks through Loom's supported
   production-client task, using the remapped mod and its nested dependencies.
   Production runs exclude development application/domain/storage outputs and
   external SQLite JDBC so those cannot hide missing bundled classes.

Each suite run has a unique run ID. Graphics, development, and packaged game
directories live separately under `fabric/build/client-smoke/profiles/<runId>/`.
The writer and reader within a mode intentionally share a profile; different
modes/runs do not. Do not point these tasks at an existing personal profile.

## Evidence and failures

Selected logs, assertion reports, JUnit XML, and screenshots go under
`fabric/build/client-smoke/evidence/<runId>/`. GitHub Actions uploads the evidence
even on failure. It does not upload game directories, world saves, databases,
Minecraft binaries, account credentials, or arbitrary user files.

A screenshot alone is not a pass. The runner checks process exit, report identity,
required phases/stages, explicit assertions, and clean-shutdown evidence. Missing,
stale, incomplete, and timed-out outcomes fail the job. Graphics/preparation
failures are reported separately from application assertion failures; optional
audio/profile lookup/cursor warnings do not excuse an application failure.

The client has a dummy audio device in CI, and Mesa uses software rendering.
These checks do not validate hardware audio, GPU-driver compatibility, other mods,
live capture, persona isolation, or AI features. See [STATUS.md](STATUS.md) for
actual commands/outcomes and [USER_TEST_PHASE1.md](USER_TEST_PHASE1.md) for the
remaining user test.
