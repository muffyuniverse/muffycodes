#!/usr/bin/env python3
"""Say where a release build spent its time.

A build whose only output is "1m 51s" invites guessing, and guessing is how the
release job came to spend a third of its time on tasks that were never going to
be cached. Gradle will write a profile report if it is asked; this reads it back
and prints the part that matters — the tasks that took the most time, and how
much of the build they account for.

It is deliberately tolerant about the report's markup. The report is Gradle's to
format, and none of this is worth breaking a build over, so it looks for a task
path and a duration in the same table row and says nothing about the rest.

It never fails a build. A measurement that can fail is a measurement that gets
deleted.
"""

from __future__ import annotations

import argparse
import glob
import html
import os
import re
import sys
from pathlib import Path

TASK = re.compile(r"^:[A-Za-z0-9_.:-]+$")
DURATION = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*(ms|s|m)\s*$")
ROW = re.compile(r"<tr\b[^>]*>(.*?)</tr>", re.S | re.I)
CELL = re.compile(r"<td\b[^>]*>(.*?)</td>", re.S | re.I)
TAG = re.compile(r"<[^>]+>")


def seconds(text: str) -> float | None:
    match = DURATION.match(text)
    if not match:
        return None
    try:
        value = float(match.group(1))
    except ValueError:  # cannot happen for a matched pattern; kept so that a
        return None     # change to the pattern fails quietly rather than loudly
    return {"ms": value / 1000, "s": value, "m": value * 60}[match.group(2)]


def read_report(path: Path) -> tuple[float | None, list[tuple[float, str]]]:
    """The total, and every (seconds, task) pair the report holds."""
    text = path.read_text(errors="replace")
    total = None

    # The summary says how long the whole build took; it is the only figure that
    # can be checked against the job's own clock.
    for row in ROW.findall(text):
        cells = [html.unescape(TAG.sub("", c)).strip() for c in CELL.findall(row)]
        for cell in cells:
            if "Total Build Time" in cell or re.match(r"^Total\b.*time", cell, re.I):
                for other in cells:
                    found = seconds(other)
                    if found is not None:
                        total = found
                        break

    tasks: list[tuple[float, str]] = []
    seen: set[str] = set()
    for row in ROW.findall(text):
        cells = [html.unescape(TAG.sub("", c)).strip() for c in CELL.findall(row)]
        name = next((c for c in cells if TASK.match(c)), None)
        length = next((seconds(c) for c in cells if seconds(c) is not None), None)
        if name and length and name not in seen:
            seen.add(name)
            tasks.append((length, name))

    tasks.sort(reverse=True)
    return total, tasks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", default=".", help="where to look for profile reports")
    parser.add_argument("--top", type=int, default=10)
    parser.add_argument("--budget", default="tools/build-budget.txt")
    args = parser.parse_args()

    reports = sorted(
        glob.glob(str(Path(args.results) / "**/build/reports/profile/*.html"), recursive=True)
        + glob.glob(str(Path(args.results) / "**/reports/profile/*.html"), recursive=True)
    )
    if not reports:
        print("No build profile was found, so this build cannot say where its time went.")
        return 0
    if len(reports) > 1:
        print(f"Found {len(reports)} profiles; reading the newest.")
        reports.sort(key=lambda p: Path(p).stat().st_mtime, reverse=True)

    total, tasks = read_report(Path(reports[0]))
    if not tasks:
        print(f"A profile exists at {reports[0]} but no task times could be read from it.")
        return 0

    measured = sum(s for s, _ in tasks)
    budget = {}
    path = Path(args.budget)
    if path.exists():
        for line in path.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if "=" in line:
                key, value = line.split("=", 1)
                try:
                    budget[key.strip()] = float(value.strip())
                except ValueError:
                    pass

    lines: list[str] = ["### Where the release build spent its time", ""]
    if total:
        lines.append(f"The whole build took **{total:.1f}s**, and the tasks below are "
                     f"{measured:.1f}s of it.")
    else:
        lines.append(f"The tasks below add up to **{measured:.1f}s**.")
    lines.append("")
    lines.append("| task | time | share |")
    lines.append("|---|---|---|")
    for length, name in tasks[: args.top]:
        share = f"{length / total * 100:.0f}%" if total else "—"
        lines.append(f"| `{name}` | {length:.1f}s | {share} |")
    lines.append("")

    biggest, biggest_name = tasks[0]
    ceiling = budget.get("single_task_s")
    if ceiling and biggest > ceiling:
        lines.append(
            f"::warning title=Build::`{biggest_name}` alone took {biggest:.1f}s, past the "
            f"{ceiling:.0f}s a single task is allowed. The pipeline cannot get shorter than "
            f"its longest task, so this is the one to split or avoid."
        )
    elif ceiling:
        lines.append(
            f"No single task is past its {ceiling:.0f}s budget, so the build is spread "
            f"across many tasks rather than blocked on one."
        )

    text = "\n".join(lines)
    print(text)

    # The same words on the run's own page, so a person looking at the run does
    # not have to open a log to find out why it took as long as it did.
    where = os.environ.get("GITHUB_STEP_SUMMARY")
    if where:
        try:
            with open(where, "a") as handle:
                handle.write(text + "\n")
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
