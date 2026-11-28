#!/usr/bin/env python3
"""Card SBM-6: make the Java tests' random run order repeatable and stop retries hiding an order dependence.

Surefire 2.22.2 runs the test classes in random order but cannot print or take a seed, so this script prints
the order the run used (from Maven's "Running <class>" lines), with a command that runs the same order again.
A test that failed and then passed on a retry fails this step, unless its class is one of the classes of
the known approval race (risk SBM-3, fixed by card SBM-5), for which the retries exist (card SBM-2,
decision 2a). Empty KNOWN_RACE_CLASSES when SBM-5 is merged.

Usage: java_run_order.py <maven log> <folder holding the surefire reports>
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

KNOWN_RACE_CLASSES = {
    "com.example.salesbatch.workflow.SalesBatchProcessTest",
    "com.example.salesbatch.workflow.BatchControllerTest",
}

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
    found = []
    for path in sorted(glob.glob(os.path.join(reports_root, "**", "TEST-*.xml"), recursive=True)):
        for case in ET.parse(path).getroot().iter("testcase"):
            first = case.find("flakyFailure")
            if first is None:
                first = case.find("flakyError")
            if first is not None:
                message = (first.get("message") or first.get("type") or "").splitlines()
                found.append((case.get("classname"), case.get("name"), message[0][:200] if message else ""))
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
    for cls, name, message in found:
        known = cls in KNOWN_RACE_CLASSES
        bad += 0 if known else 1
        out.append("- %s %s.%s: %s" % ("known approval race (SBM-3):" if known else "FAIL, not a known flaky class:",
                                       cls, name, message))
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
