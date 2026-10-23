#!/usr/bin/env python3
"""
Check that the test map and the tests agree with each other.

`tools/test-map.txt` decides which tests a pull request runs. Its failure mode is
silent: a test class that no line names is never selected, and a source file that
no line names widens every run to the whole suite. Neither announces itself. The
selector's own self-test checks the *arithmetic* — that a name reaches exactly one
shard — but it cannot see that the table is missing a row.

What this checks:

  1. every test class that exists is named by a line, or is deliberately excluded;
  2. no line names a test class that does not exist (after a rename, a stale name
     means that class silently stops being selected);
  3. no line names a source file that does not exist;
  4. every source file is named by some line, or is listed as always-running.

It proves *inventory*, not coverage. It cannot prove that `SyncFeatureTest` really
exercises everything in `ProjectGit.kt` — only that the file is accounted for. The
honest limit is written here so nobody mistakes a green run of this for a proof.

    check-test-map.py [--map tools/test-map.txt] [--sources DIR] [--tests DIR]
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# Classes that are shared harness rather than a test in their own right. They have
# no `@Test` of their own and must not appear on a map line; the classes that use
# them are the ones that matter.
HARNESS = {"Scenario", "FeatureTest", "ScenarioShots"}

ROW = re.compile(r"^(\w+)\s+(.+)$")
# `# always-full <path>` names a source that deliberately runs the whole suite
# when touched. It lives in the map rather than in this script so that there is
# one place that answers "which tests does this file need".
ALWAYS_FULL = re.compile(r"^#\s*always-full\s+(\S+)")


def read_map(path: Path) -> tuple[list[tuple[str, list[str], int]], set[str]]:
    rows = []
    always: set[str] = set()
    for number, line in enumerate(path.read_text().splitlines(), start=1):
        stripped = line.strip()
        directive = ALWAYS_FULL.match(stripped)
        if directive:
            always.add(Path(directive.group(1)).name)
            continue
        if not stripped or stripped.startswith("#"):
            continue
        match = ROW.match(stripped)
        if not match:
            print(f"  {path}:{number}: not a map line: {stripped!r}", file=sys.stderr)
            continue
        rows.append((match.group(1), match.group(2).split(), number))
    return rows, always


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--map", default="tools/test-map.txt")
    parser.add_argument("--sources", default="app/src/main/java/com/example/notes")
    parser.add_argument("--tests", default="app/src/test/java/com/example/notes")
    args = parser.parse_args()

    map_path = Path(args.map)
    rows, always = read_map(map_path)

    # The universe of test classes, taken from the files themselves rather than
    # from prose, because prose is what drifts.
    tests = {}
    for file in sorted(Path(args.tests).glob("*.kt")):
        stem = file.stem
        if stem in HARNESS:
            continue
        if "@Test" in file.read_text():
            tests[stem] = file
        else:
            # A file with no @Test of its own: a harness. It should not be mapped,
            # and if it is, that line is a mistake.
            HARNESS.add(stem)

    sources = {file.name for file in Path(args.sources).glob("*.kt")}

    named_tests: set[str] = set()
    named_sources: set[str] = set()
    problems: list[str] = []

    for test_name, files, number in rows:
        if test_name in HARNESS:
            problems.append(
                f"{map_path}:{number}: {test_name} is harness, not a test — it has no tests of its own"
            )
        elif test_name not in tests:
            problems.append(
                f"{map_path}:{number}: names {test_name}, which is not a test class. "
                f"A stale name means that test silently stops being selected."
            )
        named_tests.add(test_name)

        for source in files:
            name = Path(source).name
            named_sources.add(name)
            if name not in sources:
                problems.append(
                    f"{map_path}:{number}: names {name}, which is not in {args.sources}. "
                    f"A stale source name is a line that can never match."
                )

    for test_name in sorted(tests):
        if test_name not in named_tests:
            problems.append(
                f"{test_name} exists but no map line names it, so a change to anything "
                f"it covers never selects it"
            )

    # Sources that are deliberately not mapped. Each one here means "a change to
    # this file runs the whole suite", which is safe and slow — so the list stays
    # short and each entry is a reason, not a habit.
    for name in sorted(sources - named_sources - always):
        problems.append(
            f"{name} is on no map line, so every run that touches it falls back to the "
            f"whole suite (safe, slow). Map it, or add `# always-full` for it."
        )

    print(f"  {len(rows)} map lines · {len(tests)} test classes · {len(sources)} source files")
    print(
        f"  mapped: {len(named_tests)} tests · {len(named_sources & sources)} sources · "
        f"always-full: {len(always)}"
    )

    for problem in problems:
        print(f"::error title=Test map:: {problem}")

    if problems:
        print(f"  {len(problems)} problem(s) in {map_path}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
