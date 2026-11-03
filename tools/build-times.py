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

# A real task is `:project:task`, or deeper. `:app` alone is the *project's*
# aggregate row, which already contains every task under it — counting it would
# add the children's time to itself, and did: one run reported 50 seconds of
# work inside a 24-second build.
TASK = re.compile(r"^:([A-Za-z0-9_.-]+):([A-Za-z0-9_.:-]+)$")
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
    parser.add_argument("--label", default="release build",
                        help="what is being measured, for the heading")
    parser.add_argument("--no-budget", action="store_true",
                        help="print the table without judging it against a budget")
    args = parser.parse_args()

    # **One glob, not two, and only where a profile can be.**
    #
    # This used to walk the whole results tree twice with two patterns, and after an `assembleRelease`
    # plus an `assembleDebug` that tree holds tens of thousands of files — generated sources,
    # intermediates, caches, merged resources. Measured on run 41673254774 it took **135 seconds**, on a
    # job whose build is 161: the report about where the time went was the second-largest thing in the
    # build. The operator noticed before any dashboard did: *"now it takes so long. is something broken
    # with ci?"*
    #
    # Gradle writes its profile to `build/reports/profile/` at the root of the *invocation*, so the
    # first pattern below is the one that matches. The second is kept because a project built from a
    # subdirectory puts it one level down, and it is cheap: it only looks at direct children.
    root = Path(args.results)
    found = sorted(glob.glob(str(root / "build/reports/profile/*.html")))
    for child in sorted(root.glob("*/build/reports/profile/*.html")):
        found.append(str(child))
    reports = found
    if not reports:
        print("No build profile was found, so this build cannot say where its time went.")
        return 0
    if len(reports) > 1:
        # **Both builds in one job write a profile**, so this is the normal case rather than an
        # oddity: `assembleDebug` and `assembleRelease` each leave one, and reading the older of the
        # two describes a build that is not the one being reported on. Newest, and named.
        reports.sort(key=lambda p: Path(p).stat().st_mtime, reverse=True)
        print(f"{len(reports)} profiles in this job; reading the newest, {Path(reports[0]).name}.")

    total, tasks = read_report(Path(reports[0]))
    if not tasks:
        print(f"A profile exists at {reports[0]} but no task times could be read from it.")
        return 0

    measured = sum(s for s, _ in tasks)
    # Tasks can overlap when Gradle builds in parallel, so the parts may add up to
    # more than the whole. The whole is the truth, and it is what the shares use.
    base = max(total or 0, measured)
    budget = {}
    path = Path(args.budget)
    if args.no_budget:
        budget = {}
    elif path.exists():
        for line in path.read_text().splitlines():
            line = line.split("#", 1)[0].strip()
            if "=" in line:
                key, value = line.split("=", 1)
                try:
                    budget[key.strip()] = float(value.strip())
                except ValueError:
                    pass

    lines: list[str] = [f"### Where the {args.label} spent its time", ""]
    if total and measured > total:
        lines.append(
            f"The whole build took **{total:.1f}s**. The tasks below overlap — they ran "
            f"in parallel — so they add up to more ({measured:.1f}s) than the build did."
        )
    elif total:
        lines.append(f"The whole build took **{total:.1f}s**, and the tasks below are "
                     f"{measured:.1f}s of it.")
    else:
        lines.append(f"The tasks below add up to **{measured:.1f}s**.")
    lines.append("")
    lines.append("| task | time | share |")
    lines.append("|---|---|---|")
    for length, name in tasks[: args.top]:
        # When the report did not name a total, the share is of the task time that
        # could be read. Saying "—" would leave a column that means nothing.
        share = f"{length / base * 100:.0f}%" if base else "—"
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
