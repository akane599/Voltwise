#!/usr/bin/env python3
"""Fail closed on incomplete AndroidJUnitRunner frames or canonical AGP XML."""
import argparse
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

CADENCE = "com.akane.voltwise.battery.data.sampling.SamplingControllerCadenceDeviceTest"
ON = CADENCE + "#screenOnLastReleaseAfterStampProducesImmediateHandoff"
OFF = CADENCE + "#screenOffLastReleaseAfterStampProducesImmediateHandoff"
SHIZUKU = "com.akane.voltwise.battery.shizuku.ShizukuDeviceTest#shellServiceAuthorizationHelperRestartAndAccessLossPreserveOrdinaryReadings"


def identity(classname, method):
    if not re.fullmatch(r"[A-Za-z_$][\w.$]*", classname) or not method or any(c in method for c in "#\r\n"):
        raise ValueError("Missing or malformed test identity")
    return classname + "#" + method


def completed_identities(output: str, expected=None) -> set[str]:
    """A finish0 only counts when paired with its unique start1 in this run."""
    fields = {}
    pending = None
    completed = set()
    total = None
    summary = None
    finished = False
    for line in output.replace("\r", "").splitlines():
        if re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed", line):
            raise ValueError("Runner failed or crashed")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if summary is not None or finished:
                raise ValueError("Status after run summary")
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if not separator or key in fields:
                raise ValueError("Malformed or duplicate status field")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            if line not in ("INSTRUMENTATION_STATUS_CODE: 1", "INSTRUMENTATION_STATUS_CODE: 0"):
                raise ValueError("Test failed, was skipped, or emitted an unknown status")
            if fields.get("id") != "AndroidJUnitRunner":
                raise ValueError("Missing runner identity")
            test = identity(fields.get("class", ""), fields.get("test", ""))
            if not re.fullmatch(r"[1-9][0-9]*", fields.get("numtests", "")) or not re.fullmatch(r"[1-9][0-9]*", fields.get("current", "")):
                raise ValueError("Missing or invalid frame totals")
            frame_total, current = int(fields["numtests"]), int(fields["current"])
            if total is None:
                total = frame_total
            if total != frame_total or current > total or summary is not None or finished:
                raise ValueError("Inconsistent frame totals or run ordering")
            frame = (test, current, frame_total)
            if line.endswith(" 1"):
                if pending is not None or test in completed or current != len(completed) + 1:
                    raise ValueError("Duplicate or out-of-order start")
                pending = frame
            else:
                if pending != frame or test in completed:
                    raise ValueError("Orphan, duplicate, or inconsistent finish")
                completed.add(test)
                pending = None
            fields = {}
        elif line.startswith("OK ("):
            match = re.fullmatch(r"OK \(([1-9][0-9]*) tests?\)", line)
            if not match or summary is not None or pending or fields or finished:
                raise ValueError("Missing frames, multiple summaries, or invalid summary")
            summary = int(match[1])
            if summary != total or summary != len(completed):
                raise ValueError("Summary does not match completed frame totals")
        elif line.startswith("INSTRUMENTATION_CODE:"):
            if line != "INSTRUMENTATION_CODE: -1" or finished or summary is None:
                raise ValueError("Missing, duplicate, or unsuccessful runner completion")
            finished = True
    if not finished or not completed or pending or fields:
        raise ValueError("No complete successful test run")
    if expected is not None and completed != expected:
        raise ValueError(f"Test identities differ: missing={sorted(expected - completed)}, unexpected={sorted(completed - expected)}")
    return completed


def require_success(output: str) -> int:
    return len(completed_identities(output))


def ordinary_partition(output):
    full = completed_identities(output)
    cadence = {test for test in full if test.split("#")[0] == CADENCE}
    if cadence != {ON, OFF}:
        raise ValueError("Unassigned or missing cadence controls in FULL APK inventory")
    if SHIZUKU in full:
        raise ValueError("Privileged test leaked into ordinary inventory")
    core = full - cadence
    if not core:
        raise ValueError("Empty ordinary core inventory")
    # The sets are disjoint by construction; the independently discovered FULL set is authority.
    return full, core, {ON}, {OFF}


def partition_inventory(ordinary, privileged):
    full, core, on, off = ordinary_partition(ordinary)
    shizuku = completed_identities(privileged, {SHIZUKU})
    if full & shizuku:
        raise ValueError("Ordinary and privileged inventories overlap")
    return core, on, off, shizuku


def xml_identities(directory: Path, expected: set[str]) -> set[str]:
    reports = sorted(directory.rglob("*.xml"))
    if not reports:
        raise ValueError("Missing fresh canonical XML reports")
    completed = set()
    for report in reports:
        try:
            content = report.read_text()
            # Generated AGP reports need no DTD/entity expansion. Keep the new XML
            # input boundary strict using the standard library, without dependencies.
            if "<!DOCTYPE" in content or "<!ENTITY" in content:
                raise ValueError("DTD/entity declarations are not canonical AGP reports")
            root = ET.fromstring(content)
        except ET.ParseError as error:
            raise ValueError(f"Malformed XML: {report}") from error
        if root.tag not in ("testsuites", "testsuite"):
            raise ValueError("Unknown XML root")
        suites = list(root) if root.tag == "testsuites" else [root]
        if not suites or any(suite.tag != "testsuite" for suite in suites):
            raise ValueError("Missing or noncanonical XML suites")
        file_count = 0
        for suite in suites:
            cases = suite.findall("testcase")
            if not cases or len(suite.findall(".//testcase")) != len(cases):
                raise ValueError("Missing, empty, or nested XML test cases")
            check_xml_counts(suite, len(cases))
            for case in cases:
                test = identity(case.get("classname", ""), case.get("name", ""))
                if test in completed:
                    raise ValueError("Duplicate/conflicting XML identity, including mirrored reports")
                completed.add(test)
            file_count += len(cases)
        if root.tag == "testsuites":
            check_xml_counts(root, file_count)
    if completed != expected:
        raise ValueError(f"XML identities differ: missing={sorted(expected - completed)}, unexpected={sorted(completed - expected)}")
    return completed


def check_xml_counts(node, count):
    for key, actual in (("tests", count), ("failures", 0), ("errors", 0), ("skipped", 0)):
        value = node.get(key, "")
        if not re.fullmatch(r"[0-9]+", value) or int(value) != actual:
            raise ValueError(f"Missing, conflicting, or unsuccessful XML {key} count")
    if any(node.find(".//" + tag) is not None for tag in ("failure", "error", "skipped")):
        raise ValueError("XML test failed, errored, or was skipped (including assumptions)")


def read_expected(path):
    lines = path.read_text().splitlines()
    if not lines or len(set(lines)) != len(lines):
        raise ValueError("Missing or duplicate inventory identities")
    for line in lines:
        classname, separator, method = line.partition("#")
        if not separator or identity(classname, method) != line:
            raise ValueError("Invalid inventory identity")
    return set(lines)


def write_inventory(directory, phase, tests):
    (directory / (phase + "-identities.txt")).write_text("".join(test + "\n" for test in sorted(tests)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("inventory-ordinary", "inventory-shizuku"):
        command = commands.add_parser(name)
        command.add_argument("report", type=Path)
        command.add_argument("directory", type=Path)
    for name in ("raw", "xml"):
        command = commands.add_parser(name)
        command.add_argument("report", type=Path)
        command.add_argument("--expected", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "inventory-ordinary":
            full, core, on, off = ordinary_partition(args.report.read_text())
            for phase, tests in (("ordinary", full), ("core", core), ("screen-on", on), ("screen-off", off)):
                write_inventory(args.directory, phase, tests)
            count = len(full)
        elif args.command == "inventory-shizuku":
            tests = completed_identities(args.report.read_text(), {SHIZUKU})
            write_inventory(args.directory, "shizuku", tests)
            count = len(tests)
        elif args.command == "xml":
            count = len(xml_identities(args.report, read_expected(args.expected)))
        else:
            count = len(completed_identities(args.report.read_text(), read_expected(args.expected)))
    except (OSError, ValueError) as error:
        raise SystemExit(f"{error}; inspect {args.report}") from error
    print(f"Confirmed {count} identities for {args.command} in {args.report}")


if __name__ == "__main__":
    main()
