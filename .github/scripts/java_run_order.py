#!/usr/bin/env python3
"""Card SBM-6: make the Java tests' random run order repeatable and stop retries hiding an order dependence.

Surefire 2.22.2 runs the test classes in random order but cannot print or take a seed, so this script prints
the order the run used (from Maven's "Running <class>" lines), with a command that runs the same order again.
A test that failed and then passed on a retry fails this step, unless every failed try is the known approval
race (risk SBM-3, fixed by card SBM-5), for which the retries exist (card SBM-2, decision 2a). The race is
recognised by test method, exception type and message, never by class alone, so any other failure in the
same test (a missing report, for instance) still fails the step. SBM-5 is merged (main 0ba05f1), so
KNOWN_RACE is now empty and any test that passes only on a retry fails the step; RACE_BEFORE_SBM5 keeps the
race's forms in case it comes back.

Usage: java_run_order.py <maven log> <folder holding the surefire reports>
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

_PROCESS = "com.example.salesbatch.workflow.SalesBatchProcessTest"
_CONTROLLER = "com.example.salesbatch.workflow.BatchControllerTest"
_ASSERT = "java.lang.AssertionError"
_STATE = "java.lang.IllegalStateException"
# No approval task yet when the status already reads WAITING_APPROVAL (count 0, or approve finds no task) ...
_NO_TASK = re.compile(r"expected:<1> but was:<0>")
_NO_PENDING = re.compile(r"No pending approval for process instance \S+")
# ... or the run still alive when the status already reads FAILED.
_STILL_ALIVE = re.compile(r"expected null, but was:<.*>")

# (class, test method, exception type, full-message pattern): the race as each test shows it.
RACE_BEFORE_SBM5 = [
    (_PROCESS, "approvedBatchIsPublished", _ASSERT, _NO_TASK),
    (_PROCESS, "approvedBatchIsPublished", _STATE, _NO_PENDING),
    (_PROCESS, "rejectedBatchIsNotPublished", _STATE, _NO_PENDING),
    (_PROCESS, "startReturnsBeforeTheBackgroundStepsFinish", _ASSERT, _NO_TASK),
    (_PROCESS, "sparkFailureFailsTheRunWithTheReason", _ASSERT, _STILL_ALIVE),
    (_CONTROLLER, "startThenApproveOverRest", _ASSERT, re.compile(r"Status expected:<200> but was:<409>")),
    (_CONTROLLER, "approvingTwiceIsAConflict", _STATE, _NO_PENDING),
]
KNOWN_RACE = []  # card SBM-5 fixed the race; set to RACE_BEFORE_SBM5 only if it is seen again


def is_known_race(cls, name, kind, message):
    return any(cls == c and name == n and kind == k and p.fullmatch(message)
               for c, n, k, p in KNOWN_RACE)

BUILDING = re.compile(r"^\[INFO\] Building (\S+) \d\S*")
RUNNING = re.compile(r"^\[INFO\] Running ([\w.$]+)$")


def run_order(log_path):
    order, module = [], "?"
    with open(log_path, encoding="utf-8", errors="replace") as log:
        for line in log:
            line = line.rstrip()
            m = BUILDING.match(line)
            if m:
                module = m.group(1)
                continue
            m = RUNNING.match(line)
            if m:
                order.append((module, m.group(1)))
    return order


def flakes(reports_root):
    """Every failed try of a test that passed in the end: (class, method, exception type, message)."""
    found = []
    for path in sorted(glob.glob(os.path.join(reports_root, "**", "TEST-*.xml"), recursive=True)):
        for case in ET.parse(path).getroot().iter("testcase"):
            for tried in list(case.iter("flakyFailure")) + list(case.iter("flakyError")):
                found.append((case.get("classname"), case.get("name"), tried.get("type") or "",
                              (tried.get("message") or "").strip()))
    return found


def main(log_path, reports_root):
    out = ["## Java test classes, in the order this run used", ""]
    order = run_order(log_path)
    by_module = {}
    for module, cls in order:
        by_module.setdefault(module, []).append(cls)
    for module, classes in by_module.items():
        out.append("**%s**: %s" % (module, " ".join(c.rsplit(".", 1)[-1] for c in classes)))
    workflow = [c for c in by_module.get("workflow-app", []) if not c.endswith("RunOrderAcceptanceTest")]
    if workflow:
        out += ["", "Run the workflow-app classes again in this order, one JVM (bash, from the repository root):", "",
                "```",
                "mvn -B -q -pl workflow-app -am test-compile dependency:build-classpath -Dmdep.outputFile=cp.txt",
                "java -cp \"workflow-app/target/test-classes:workflow-app/target/classes:$(cat workflow-app/cp.txt)\" \\",
                "  'com.example.salesbatch.workflow.RunOrderAcceptanceTest$Child' \\",
                "  " + " ".join(workflow),
                "```"]

    bad = 0
    found = flakes(reports_root)
    out += ["", "## Tests that passed only on a retry", ""]
    if not found:
        out.append("None.")
    for cls, name, kind, message in found:
        known = is_known_race(cls, name, kind, message)
        bad += 0 if known else 1
        first_line = (message.splitlines() or [""])[0][:200]
        out.append("- %s %s.%s: %s: %s" % ("known approval race (SBM-3):" if known else "FAIL, not the known race:",
                                           cls, name, kind, first_line))
    if not order:
        out.append("")
        out.append("FAIL: no 'Running <class>' line in %s, so the order cannot be shown" % log_path)
        bad += 1

    text = "\n".join(out) + "\n"
    sys.stdout.write(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(text)
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
