"""Host-only real-shaped runner/XML regressions, never device acceptance."""
from pathlib import Path
import tempfile
import unittest
import check_instrumentation_result as checker

CADENCE = "com.akane.voltwise.battery.data.sampling.SamplingControllerCadenceDeviceTest"
ON = CADENCE + "#screenOnLastReleaseAfterStampProducesImmediateHandoff"
OFF = CADENCE + "#screenOffLastReleaseAfterStampProducesImmediateHandoff"
SHIZUKU = "com.akane.voltwise.battery.shizuku.ShizukuDeviceTest#shellServiceAuthorizationHelperRestartAndAccessLossPreserveOrdinaryReadings"
CORE = ["com.akane.voltwise.CoreTest#first", "com.akane.voltwise.CoreTest#second"]


def raw(identities):
    frames = []
    for current, identity in enumerate(identities, 1):
        classname, method = identity.split("#")
        for code in (1, 0):
            frames.append(f"INSTRUMENTATION_STATUS: class={classname}\n"
                          f"INSTRUMENTATION_STATUS: current={current}\n"
                          "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
                          f"INSTRUMENTATION_STATUS: numtests={len(identities)}\n"
                          "INSTRUMENTATION_STATUS: stream=.\n"
                          f"INSTRUMENTATION_STATUS: test={method}\n"
                          f"INSTRUMENTATION_STATUS_CODE: {code}\n")
    return "".join(frames) + f"INSTRUMENTATION_RESULT: stream=\nTime: 0.01\n\nOK ({len(identities)} tests)\n\nINSTRUMENTATION_CODE: -1\n"


def xml(identities, child="", **counts):
    counts = {"tests": len(identities), "failures": 0, "errors": 0, "skipped": 0, **counts}
    cases = []
    for identity in identities:
        classname, method = identity.split("#")
        cases.append(f'<testcase classname="{classname}" name="{method}" time="0.01">{child}</testcase>')
    attributes = " ".join(f'{key}="{value}"' for key, value in counts.items())
    return f'<testsuite name="device" {attributes}>' + "".join(cases) + '</testsuite>'


class InstrumentationResultTest(unittest.TestCase):
    def test_successful_completed_run(self):
        self.assertEqual(2, checker.require_success(raw(CORE).replace("\n", "\r\n")))
        self.assertEqual(1, checker.require_success(raw([ON])))

    def test_summary_without_completed_identity_is_rejected(self):
        with self.assertRaises(ValueError):
            checker.require_success("OK (24 tests)\nINSTRUMENTATION_CODE: -1\n")

    def test_invalid_frames_and_totals_fail_closed(self):
        valid = raw([ON])
        first, finish = valid.split("INSTRUMENTATION_STATUS_CODE: 1\n", 1)
        invalid = {
            "orphan": finish,
            "duplicate_start": first + "INSTRUMENTATION_STATUS_CODE: 1\n" + valid,
            "duplicate_finish": valid.replace("INSTRUMENTATION_RESULT:", finish.split("INSTRUMENTATION_RESULT:")[0] + "INSTRUMENTATION_RESULT:", 1),
            "truncated": first + "INSTRUMENTATION_STATUS_CODE: 1\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n",
            "wrong_total": valid.replace("numtests=1", "numtests=2"),
            "wrong_current": valid.replace("current=1", "current=2"),
            "inconsistent_finish": valid.replace("test=screenOn", "test=screenOff", 1),
            "multiple_summary": valid.replace("INSTRUMENTATION_CODE:", "OK (1 test)\nINSTRUMENTATION_CODE:"),
            "multiple_completion": valid + "INSTRUMENTATION_CODE: -1\n",
            "wrong_summary": valid.replace("OK (1 tests)", "OK (2 tests)"),
            "duplicate_field": valid.replace("current=1", "current=1\nINSTRUMENTATION_STATUS: current=1", 1),
            "missing_field": valid.replace("INSTRUMENTATION_STATUS: numtests=1\n", "", 1),
            "zero": raw([]),
            "crash": "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n",
        }
        for name, output in invalid.items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                checker.require_success(output)
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                checker.require_success(valid.replace("INSTRUMENTATION_STATUS_CODE: 0", f"INSTRUMENTATION_STATUS_CODE: {code}"))

    def test_assumption_status_minus_four_despite_ok_96_is_not_a_pass(self):
        inventory = [f"com.akane.voltwise.CoreTest#method{i}" for i in range(94)] + [ON, OFF]
        output = raw(inventory)
        self.assertEqual(96, checker.require_success(output))
        with self.assertRaises(ValueError):
            checker.require_success(output.replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -4", 1))

    def test_singleton_identity_is_not_just_a_count(self):
        for identities in ([OFF], CORE, [ON, ON]):
            with self.subTest(identities=identities), self.assertRaises(ValueError):
                checker.completed_identities(raw(identities), expected={ON})

    def test_inventory_is_independent_and_complete_not_fixed_count(self):
        core, on, off, privileged = checker.partition_inventory(raw(CORE + [ON, OFF]), raw([SHIZUKU]))
        self.assertEqual(set(CORE), core)
        self.assertEqual({ON}, on)
        self.assertEqual({OFF}, off)
        self.assertEqual({SHIZUKU}, privileged)
        expanded = CORE + ["com.akane.voltwise.NewTest#new"]
        self.assertEqual(set(expanded), checker.partition_inventory(raw(expanded + [ON, OFF]), raw([SHIZUKU]))[0])
        for ordinary, shizuku in ((CORE + [ON], [SHIZUKU]), (CORE + [ON, OFF, CADENCE + "#third"], [SHIZUKU]), (CORE + [ON, OFF], [ON]), (CORE + [ON, OFF, SHIZUKU], [SHIZUKU])):
            with self.subTest(ordinary=ordinary, shizuku=shizuku), self.assertRaises(ValueError):
                checker.partition_inventory(raw(ordinary), raw(shizuku))

    def test_canonical_xml_counts_nodes_and_exact_identities(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = root / "TEST-core.xml"
            report.write_text(xml(CORE))
            self.assertEqual(set(CORE), checker.xml_identities(root, set(CORE)))
            bad = [xml(CORE, '<failure message="AssumptionViolatedException"/>', failures=2),
                   xml(CORE, '<failure/>'), xml(CORE, '<error/>'), xml(CORE, '<skipped/>'),
                   xml(CORE, failures=1), xml(CORE, tests=3), xml([]),
                   xml([CORE[0], CORE[0]]), xml([CORE[0], OFF]),
                   xml(CORE).replace(' errors="0"', ''), '<testsuite',
                   '<!DOCTYPE testsuite [<!ENTITY arbitrary "replacement">]>' + xml(CORE),
                   '<testsuites tests="3" failures="0" errors="0" skipped="0">' + xml(CORE) + '</testsuites>']
            for content in bad:
                report.write_text(content)
                with self.subTest(content=content), self.assertRaises(ValueError):
                    checker.xml_identities(root, set(CORE))
            report.write_text(xml(CORE))
            (root / "TEST-mirror.xml").write_text(xml(CORE))
            with self.assertRaises(ValueError):
                checker.xml_identities(root, set(CORE))
            (root / "TEST-mirror.xml").unlink()
            report.unlink()
            with self.assertRaises(ValueError):
                checker.xml_identities(root, set(CORE))


if __name__ == "__main__":
    unittest.main()
