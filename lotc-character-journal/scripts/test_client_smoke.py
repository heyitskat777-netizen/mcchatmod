"""Contract regressions: stale/zero tests, failure classification, and detached-child cleanup."""
import copy
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import client_smoke as smoke


class EvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.profile = Path(self.directory.name)
        image = self.profile / "synthetic.png"
        image.write_bytes(b"\x89PNG\r\n\x1a\nsynthetic-fixture")
        self.expected = dict(runId="new-run", mode="development", phase="write", profile=str(self.profile), startedAfter=time.time() - 1)
        self.report = dict(**{k: v for k, v in self.expected.items() if k != "startedAfter"},
                           processId=1234, startedAt=datetime.now(timezone.utc).isoformat(), complete=True,
                           bodyCompleted=True, shutdownAcknowledged=True, testCount=1, failures=[],
                           stages=[dict(name=n, passed=True, durationMs=1, timeoutMs=20) for n in sorted(smoke.required_stages("write"))],
                           screenshots=[str(image)], journalOrigins=["/synthetic/development/output"])
        self.report["assertionStages"] = len(self.report["stages"])
        self.shutdown = {k: self.report[k] for k in ("runId", "mode", "phase", "processId", "complete", "shutdownAcknowledged")}
        self.log = "\n".join(f"{m} runId=new-run phase=write pid=1234" for m in ("LOTC_SMOKE_BODY_COMPLETE", "LOTC_SMOKE_SHUTDOWN_COMPLETE"))

    def classify(self, report=None, shutdown=None, code=0, timeout=False, log=None):
        return smoke.classify_result(code, timeout, self.report if report is None else report,
                                     self.shutdown if shutdown is None else shutdown, self.expected,
                                     self.log if log is None else log)[0]

    def test_complete_bound_process_passes(self):
        self.assertEqual("PASSED", self.classify())

    def test_exit_zero_without_report_is_failure(self):
        self.assertEqual("EVIDENCE_FAILURE", smoke.classify_result(0, False, None, None, self.expected, "")[0])

    def test_missing_stale_zero_or_incomplete_evidence_cannot_pass(self):
        for key, value in [("testCount", 0), ("runId", "old-run"), ("assertionStages", 0),
                           ("shutdownAcknowledged", False), ("complete", False), ("processId", 0),
                           ("profile", "/wrong/profile"), ("startedAt", "2000-01-01T00:00:00Z")]:
            with self.subTest(key=key):
                report = copy.deepcopy(self.report)
                report[key] = value
                self.assertNotEqual("PASSED", self.classify(report=report))

    def test_malformed_stage_evidence_fails_closed_without_exception(self):
        for stages in (None, [None], {}, "invalid"):
            with self.subTest(stages=stages):
                report = copy.deepcopy(self.report)
                report["stages"] = stages
                self.assertEqual("EVIDENCE_FAILURE", self.classify(report=report))

    def test_missing_required_stage_cannot_pass(self):
        self.report["stages"] = self.report["stages"][:-1]
        self.report["assertionStages"] -= 1
        self.assertEqual("EVIDENCE_FAILURE", self.classify())

    def test_assertion_failure_outranks_graphics_warning(self):
        self.report["stages"][0]["passed"] = False
        self.report["failures"] = ["AssertionError: keybind did not open journal"]
        self.assertEqual("APPLICATION_FAILURE", self.classify(code=1, log="GLFW error 65543\n" + self.log))

    def test_preflight_build_failure_is_not_mislabeled_graphics_environment(self):
        expected = dict(self.expected, phase="preflight", mode="graphics")
        self.assertEqual("STARTUP_FAILURE", smoke.classify_result(1, False, None, None, expected, "Gradle configuration error")[0])
        self.assertEqual("ENVIRONMENT_FAILURE", smoke.classify_result(1, False, None, None, expected, "Failed to initialize GLFW")[0])

    def test_timeout_keeps_failure_even_if_report_claims_success(self):
        self.assertEqual("TIMEOUT", self.classify(timeout=True))

    def test_shutdown_process_id_must_match(self):
        self.shutdown["processId"] = 999
        self.assertEqual("EVIDENCE_FAILURE", self.classify())

    def test_zero_process_markers_cannot_pass(self):
        self.assertEqual("EVIDENCE_FAILURE", self.classify(log="BUILD SUCCESSFUL"))

    def test_stale_or_non_png_screenshot_cannot_pass(self):
        image = Path(self.report["screenshots"][0])
        os.utime(image, (1, 1))
        self.assertEqual("EVIDENCE_FAILURE", self.classify())
        image.write_bytes(b"not-a-png-but-long-enough")
        self.assertEqual("EVIDENCE_FAILURE", self.classify())

    def test_packaged_launch_cannot_mask_sqlite_or_source_outputs(self):
        expected = dict(self.expected, mode="packaged")
        launch = dict(runId="new-run", mode="packaged", phase="write", production=True, journalIncluded=True,
                      mods=["/synthetic/lotc-character-journal-0.1.jar", "/synthetic/smoke-harness.jar", "/synthetic/fabric-api.jar",
                            "/synthetic/fabric-client-gametest-api-v1-4.3.5.jar", "/synthetic/fabric-gametest-api-v1-3.1.27.jar"],
                      classpath=["/synthetic/minecraft.jar", "/synthetic/loader.jar"])
        report = dict(self.report, journalOrigins=["/synthetic/lotc-character-journal-0.1.jar"])
        self.assertEqual([], smoke.validate_launch(launch, expected, report))
        for forbidden in ("/synthetic/sqlite-jdbc-1.jar", str(smoke.PROJECT / "fabric/build/classes")):
            with self.subTest(forbidden=forbidden):
                bad = dict(launch, classpath=launch["classpath"] + [forbidden])
                self.assertTrue(smoke.validate_launch(bad, expected, report))

    def test_junit_retains_failure_and_blocked_stages(self):
        output = self.profile / "junit.xml"
        smoke.write_junit([dict(name="graphics", status="ENVIRONMENT_FAILURE", errors=["No display"]),
                           dict(name="development-write", status="SKIPPED")], output)
        root = smoke.ET.parse(output).getroot()
        self.assertEqual("1", root.attrib["failures"])
        self.assertEqual("1", root.attrib["skipped"])


class OfflineRunnerTest(unittest.TestCase):
    def test_offline_reaches_preparation_and_launch_without_excusing_missing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            commands=[]
            def run(command,log_path,timeout,env):
                commands.append(command);log_path.write_text("BUILD SUCCESSFUL\n");return 0,False,0.01
            with mock.patch.object(smoke,"BASE",Path(directory)), mock.patch.object(smoke,"run_process",side_effect=run), \
                 mock.patch.object(sys,"argv",["client_smoke.py","--offline","--preflight-only","--eula-accepted","--run-id","offline-contract"]):
                self.assertEqual(1,smoke.main(),"Offline mode must still reject missing reports/shutdown evidence")
            self.assertEqual(2,len(commands))
            self.assertTrue(all("--offline" in command for command in commands))
            self.assertIn(":fabric:prepareClientSmoke",commands[0])
            self.assertIn(":fabric:runGraphicsPreflight",commands[1])


@unittest.skipUnless(sys.platform == "linux", "Linux process containment contract")
class ProcessContainmentTest(unittest.TestCase):
    def test_timeout_reaps_a_detached_child_in_a_different_session(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pid_file = root / "detached.pid"
            program = ("import os,time,pathlib\n"
                       "pid=os.fork()\n"
                       "if pid==0:\n"
                       " os.setsid()\n"
                       f" pathlib.Path({str(pid_file)!r}).write_text(str(os.getpid()))\n"
                       " time.sleep(120)\n"
                       "else:\n"
                       " time.sleep(120)\n")
            rc, timed_out, duration = smoke.run_process([sys.executable, "-c", program], root / "process.log", 1, dict(os.environ))
            self.assertTrue(timed_out)
            self.assertNotEqual(0, rc)
            self.assertLess(duration, 22)
            detached = int(pid_file.read_text())
            self.assertFalse(Path(f"/proc/{detached}").exists(), "Detached child must be terminated and reaped")


if __name__ == "__main__":
    unittest.main()
