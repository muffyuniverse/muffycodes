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
            # **The module matters here too.** `always-full` names a path, and the
            # comparison below is by `module/file.kt` - so a bare name would never
            # match and a deliberate exemption would read as a gap.
            full = directive.group(1)
            parts = Path(full).parts
            module = parts[parts.index("src") - 1] if "src" in parts else "."
            always.add(f"{module}/{Path(full).name}")
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
    # **Comma separated, and every module must be named.** A source in a module
    # nobody listed would be invisible to this check, and invisible is the one
    # failure mode it exists to prevent: the table would look complete while a
    # whole module sat outside it.
    parser.add_argument(
        "--sources",
        default="app/src/main/java/com/example/notes,browser/src/main/java/com/example/notes/browser",
    )
    parser.add_argument(
        "--tests",
        default="app/src/test/java/com/example/notes,browser/src/test/java/com/example/notes/browser",
    )
    args = parser.parse_args()

    map_path = Path(args.map)
    rows, always = read_map(map_path)

    # The universe of test classes, taken from the files themselves rather than
    # from prose, because prose is what drifts.
    tests = {}
    test_files = sorted(f for root in args.tests.split(",") for f in Path(root.strip()).glob("*.kt"))
    for file in test_files:
        stem = file.stem
        if stem in HARNESS:
            continue
        if "@Test" in file.read_text():
            tests[stem] = file
        else:
            # A file with no @Test of its own: a harness. It should not be mapped,
            # and if it is, that line is a mistake.
            HARNESS.add(stem)

    # **Keyed by module-relative path, not by bare file name.** Two modules can
    # both hold a `Browser.kt` one day, and a check that keyed on the name would
    # then treat them as one file - which is the same silent-completeness bug as
    # a table that omits a module.
    def module_of(root: str) -> str:
        """The module a source root belongs to: the first path part before `src/`."""
        parts = Path(root).parts
        if "src" in parts:
            index = parts.index("src")
            if index > 0:
                return parts[index - 1]
        return "."

    def files_in(roots: str) -> dict[str, str]:
        found: dict[str, str] = {}
        for root in roots.split(","):
            root = root.strip()
            if not root:
                continue
            module = module_of(root)
            for file in sorted(Path(root).glob("*.kt")):
                found[f"{module}/{file.name}"] = str(file)
        return found

    sources = files_in(args.sources)

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
            # **The map names a path, so the module comes from the path.** The
            # lookup is by `module/file.kt`, which is what lets two modules hold a
            # file of the same name without the check confusing them.
            module = module_of(source)
            key = f"{module}/{name}"
            named_sources.add(key)
            if key not in sources:
                problems.append(
                    f"{map_path}:{number}: names {source}, which does not exist. "
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
    for name in sorted(set(sources) - named_sources - always):
        problems.append(
            f"{name} is on no map line, so every run that touches it falls back to the "
            f"whole suite (safe, slow). Map it, or add `# always-full` for it."
        )

    print(f"  {len(rows)} map lines · {len(tests)} test classes · {len(sources)} source files")
    print(
        f"  mapped: {len(named_tests)} tests · {len(named_sources & set(sources))} sources · "
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
