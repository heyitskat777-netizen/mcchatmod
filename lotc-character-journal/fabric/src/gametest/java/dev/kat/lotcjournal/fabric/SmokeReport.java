package dev.kat.lotcjournal.fabric;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Test-only evidence: body success cannot pass until the production lifecycle has stopped its worker. */
final class SmokeReport {
    private final String phase = System.getProperty("lotc.journal.smoke.phase", "standalone");
    private final String mode = System.getProperty("lotc.journal.smoke.mode", "development");
    private final String runId = System.getProperty("lotc.journal.smoke.runId", UUID.randomUUID().toString());
    private final long processId = ProcessHandle.current().pid();
    private final String startedAt = Instant.now().toString();
    private final Path result = Path.of(System.getProperty("lotc.journal.smoke.result",
        FabricLoader.getInstance().getGameDir().resolve("lotc-smoke-result.json").toString())).toAbsolutePath();
    private final List<Map<String, Object>> stages = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> screenshots = new ArrayList<>();
    private final Map<String, String> codeSources = new LinkedHashMap<>();
    private boolean bodyCompleted;
    private boolean shutdownAcknowledged;

    SmokeReport() {
        if (!List.of("preflight", "write", "read", "standalone").contains(phase))
            throw new IllegalArgumentException("Unknown smoke phase: " + phase);
        // Registered while the test runs, after production initialization registered its close listener.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> captureShutdown());
        Runtime.getRuntime().addShutdownHook(new Thread(this::finish, "lotc-smoke-report-writer"));
    }

    String phase() { return phase; }
    String mode() { return mode; }

    void stage(String name, long timeoutMs, Action action) throws Exception {
        measure(name, timeoutMs, () -> { action.run(); return null; });
    }

    <T> T measure(String name, long timeoutMs, Query<T> action) throws Exception {
        long start = System.nanoTime();
        try {
            T value = action.run();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (elapsed > timeoutMs) throw new AssertionError(name + " exceeded its " + timeoutMs + "ms stage budget");
            record(name, true, elapsed, timeoutMs);
            return value;
        } catch (Exception | Error failure) {
            record(name, false, (System.nanoTime() - start) / 1_000_000, timeoutMs);
            throw failure;
        }
    }

    synchronized void screenshot(Path path) { screenshots.add(path.toAbsolutePath().normalize().toString()); }

    synchronized void origin(String name, Class<?> type) {
        codeSources.put(name, type.getProtectionDomain().getCodeSource().getLocation().toString());
    }

    synchronized void bodyComplete() {
        bodyCompleted = true;
        System.out.println("LOTC_SMOKE_BODY_COMPLETE runId=" + runId + " phase=" + phase + " pid=" + processId);
    }

    synchronized void bodyFailure(Throwable error) {
        failures.add(error.getClass().getSimpleName() + ": " + error.getMessage());
        write(result, snapshot(false));
    }

    private synchronized void record(String name, boolean passed, long elapsed, long timeoutMs) {
        stages.add(Map.of("name", name, "passed", passed, "durationMs", elapsed, "timeoutMs", timeoutMs));
        System.out.println("LOTC_SMOKE_STAGE runId=" + runId + " phase=" + phase + " stage=" + name + " passed=" + passed);
    }

    private synchronized void captureShutdown() {
        long start = System.nanoTime();
        var workers = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && (thread.getName().equals("lotc-journal-io")
                || thread.getName().equals("lotc-journal-import"))).toList();
        shutdownAcknowledged = workers.isEmpty();
        if (!shutdownAcknowledged) failures.add("Journal I/O worker remained alive after CLIENT_STOPPING");
        record("shutdown", shutdownAcknowledged, (System.nanoTime() - start) / 1_000_000, 10_000);
        if (shutdownAcknowledged)
            System.out.println("LOTC_SMOKE_SHUTDOWN_COMPLETE runId=" + runId + " phase=" + phase + " pid=" + processId);
    }

    private synchronized void finish() {
        if (!bodyCompleted && failures.isEmpty()) failures.add("Process stopped before the smoke test body completed");
        if (!shutdownAcknowledged) failures.add("Missing successful CLIENT_STOPPING witness");
        boolean passed = bodyCompleted && shutdownAcknowledged && failures.isEmpty()
            && stages.stream().allMatch(stage -> Boolean.TRUE.equals(stage.get("passed")));
        var report = snapshot(passed);
        // File I/O runs on this shutdown hook, never on the Minecraft client/render thread.
        write(Path.of(result + ".shutdown.json"), Map.of("runId", runId, "mode", mode, "phase", phase,
            "processId", processId, "shutdownAcknowledged", shutdownAcknowledged,
            "complete", passed));
        write(result, report);
    }

    private Map<String, Object> snapshot(boolean passed) {
        var report = new LinkedHashMap<String, Object>();
        report.put("runId", runId);
        report.put("mode", mode);
        report.put("phase", phase);
        report.put("processId", processId);
        report.put("startedAt", startedAt);
        report.put("profile", FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().toString());
        report.put("journalOrigins", FabricLoader.getInstance().getModContainer("lotc_journal")
            .map(container -> container.getOrigin().getPaths().stream().map(path -> path.toAbsolutePath().normalize().toString()).toList())
            .orElse(List.of()));
        report.put("codeSources", Map.copyOf(codeSources));
        report.put("complete", passed);
        report.put("bodyCompleted", bodyCompleted);
        report.put("shutdownAcknowledged", shutdownAcknowledged);
        report.put("testCount", 1);
        report.put("assertionStages", stages.size());
        report.put("stages", List.copyOf(stages));
        report.put("screenshots", List.copyOf(screenshots));
        report.put("failures", List.copyOf(failures));
        return report;
    }

    private void write(Path path, Map<String, Object> contents) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Path.of(path + ".tmp-" + processId);
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(contents));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not write smoke-test evidence", failure);
        }
    }

    @FunctionalInterface interface Action { void run() throws Exception; }
    @FunctionalInterface interface Query<T> { T run() throws Exception; }
}
