#!/usr/bin/env python3
"""
Check that the tests that were asked for are the tests that ran.

Every guard in this pipeline until now has been about the *asking*: the selector
knows what a change touches, the shard script knows who owns which class, and the
build knows what to run. None of them look at what came back. The evidence that
the suite actually ran has been JUnit's exit status and nothing else.

That is exactly the shape of the failure this project has already had: a selector
that matched nothing, every shard deciding it had no work, and a green run that
executed zero tests. The shell-side self-test was added afterwards, and it proves
properties of the asking — it still cannot see a run.

So this reads the JUnit XML and compares it against the classes the run was asked
for. It fails when:

  * no results were produced at all;
  * a class that was selected produced no `<testcase>`;
  * a class that was selected produced zero test cases (an empty class);
  * the number of test cases is zero.

It says nothing about tests that were *not* selected — that is the selector's
question, and `tools/test-map.txt` is where it is answered. This is the other
half: given what was selected, did every one of them actually run?

    check-results.py --results <dir> --expect "A B C"
    check-results.py --results <dir>              # only the non-zero checks

Exit 0 when everything expected ran, 1 otherwise, with the reason on stdout.
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def test_cases(path: Path) -> list[str]:
    """Every test-case name in one JUnit file."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as problem:
        print(f"Could not read {path}: {problem}", file=sys.stderr)
        return []
    return [case.get("name", "?") for case in root.iter("testcase")]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--results",
        default="",
        help="Where JUnit writes its XML. Empty means every module's, which is what the "
        "workflow wants now that there is more than one module.",
    )
    parser.add_argument(
        "--results-root",
        default=".",
        help="Where to look for <module>/build/test-results when --results is empty.",
    )
    parser.add_argument(
        "--expect",
        default="",
        help="The class names this run was asked for, space or comma separated.",
    )
    args = parser.parse_args()

    # **Every module, unless a caller names one.** The app was the only module
    # for as long as this script existed, so its path was the default; `:browser`
    # is the second, and a default that named only the app would report "no
    # results" for a run that tested the new module and never looked at it.
    if args.results:
        folders = [Path(args.results)]
    else:
        root = Path(args.results_root)
        folders = sorted(
            folder
            for folder in root.glob("*/build/test-results/testDebugUnitTest")
            if folder.is_dir()
        )
    files = sorted(file for folder in folders for file in folder.glob("TEST-*.xml"))

    ran: dict[str, int] = {}
    enabled = 0
    skipped = 0
    for file in files:
        # A JUnit file is named TEST-<fully.qualified.Class>.xml. The class name
        # is taken from the file, not from the root element, because the file name
        # is what Gradle writes and what the selection is compared against.
        name = file.stem
        name = name[len("TEST-") :] if name.startswith("TEST-") else name
        cases = test_cases(file)
        ran[name] = len(cases)
        enabled += len(cases)
        try:
            root = ET.parse(file).getroot()
            skipped += sum(1 for case in root.iter("testcase") if case.find("skipped") is not None)
        except (ET.ParseError, OSError):
            pass

    problems: list[str] = []

    if not files:
        # **Said as a path that was looked for, not as a module count.** "no module"
        # would read as a build fault when the truth is that no build ran.
        where = args.results or ", ".join(str(f) for f in folders)
        if not where:
            where = f"{args.results_root}/*/build/test-results/testDebugUnitTest"
        problems.append(f"no JUnit results at all under {where}")

    if enabled == 0 and files:
        problems.append(f"{len(files)} result files but zero test cases")

    expected = [item for item in args.expect.replace(",", " ").split() if item]
    missing: list[str] = []
    empty: list[str] = []
    for want in expected:
        # The selection names classes without a package; the files carry one.
        short = want.rsplit(".", 1)[-1]
        matched = [name for name in ran if name.rsplit(".", 1)[-1] == short]
        if not matched:
            missing.append(short)
        elif sum(ran[name] for name in matched) == 0:
            empty.append(short)

    if missing:
        problems.append(f"asked for but not run: {', '.join(sorted(missing))}")
    if empty:
        problems.append(f"ran but contained no tests: {', '.join(sorted(empty))}")

    print(f"  {len(files)} result files · {enabled} test cases · {skipped} skipped")
    if expected:
        print(f"  asked for {len(expected)} classes; {len(expected) - len(missing) - len(empty)} produced tests")

    for problem in problems:
        print(f"::error::The suite did not run what it was asked to: {problem}")

    if problems:
        print("  Classes that produced results:")
        for name in sorted(ran):
            print(f"    {name} — {ran[name]}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
