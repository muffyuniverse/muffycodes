#!/usr/bin/env python3
"""Measures what each test class costs, and writes the weights the split uses.

## Why this exists

`test-shard.sh` split the suite by a **hash of each class name**, dealt out round-robin. That is
even by *count* and was never even by *time*: a Robolectric screen test and a pure parser test are
one class each, and they do not cost the same. Measured on this repository, two shards of 23 classes
apiece ran 40.7s and 51.1s — a quarter of a shard's work sitting on one runner while the other
waited. Adding runners does not fix that; it multiplies it.

So the split needs numbers, and the numbers have to come from a run rather than from a guess. This
reads the JUnit XML the test task already writes, and produces a table of class → seconds.

## The file it writes

`tools/test-weights.txt`, committed, because the shard script runs on every runner before anything
is built and cannot afford to run the suite to decide how to run the suite. A class that is not in
the file is **not** left out: it is assigned as if it cost the average, so a new test joins the
split immediately and its true cost is learned on the next measurement.

    # measured <date> from <n> classes
    com.example.notes.AgentLogTest 1.23

## When it is refreshed

By hand, from a green run's artifacts — `test-weights.py --results <dir> --write`. Refreshing it on
every run would make the split depend on which runner measured what, and a split that moves is a
cache that misses.
"""

import argparse
import glob
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEIGHTS = ROOT / "tools/test-weights.txt"


def read(results: Path) -> dict[str, float]:
    """Every class's own seconds, summed over its test cases."""
    found: dict[str, float] = {}
    for path in glob.glob(str(results / "**/TEST-*.xml"), recursive=True):
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        name = root.get("name") or ""
        if not name:
            continue
        # **The class's own `time`, not the sum of its cases.** JUnit writes both, and the class
        # figure includes the fixture the cases share — which is most of what a Robolectric class
        # costs and none of what its cases report.
        try:
            seconds = float(root.get("time") or 0.0)
        except ValueError:
            seconds = 0.0
        found[name] = max(found.get(name, 0.0), seconds)
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", default="app/build/test-results")
    parser.add_argument("--write", action="store_true", help="rewrite tools/test-weights.txt")
    parser.add_argument("--top", type=int, default=10)
    args = parser.parse_args()

    weights = read(Path(args.results))
    if not weights:
        print(f"no test-result XML under {args.results}", file=sys.stderr)
        return 1

    total = sum(weights.values())
    print(f"{len(weights)} classes, {total:.1f}s in total, {total / len(weights):.2f}s average")
    print()
    print("| class | time | share |")
    print("|---|---|---|")
    for name, seconds in sorted(weights.items(), key=lambda kv: -kv[1])[: args.top]:
        print(f"| `{name.split('.')[-1]}` | {seconds:.2f}s | {seconds / total:.0%} |")

    if args.write:
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        lines = [f"# measured {stamp} from {len(weights)} classes"]
        for name, seconds in sorted(weights.items()):
            lines.append(f"{name} {seconds:.2f}")
        WEIGHTS.write_text("\n".join(lines) + "\n")
        print(f"\nwrote {WEIGHTS.relative_to(ROOT)} ({len(weights)} classes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
