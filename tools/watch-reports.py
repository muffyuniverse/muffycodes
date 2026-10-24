#!/usr/bin/env python3
"""Reads what the app wrote about itself, without anybody having to tell me.

The app is on a phone; the folder that folder is shared with this container is
`/root/Downloads`. A debug build of Muffy Codes rewrites `live-trace.md` there every
few seconds, and writes a `report-*.md` whenever a person presses the bug button. This
script watches that folder and prints only what changed — so a session of using the app
turns into a stream of findings rather than a pile of files somebody has to describe.

Usage:
    tools/watch-reports.py                  # watch until interrupted
    tools/watch-reports.py --once           # scan once and exit
    tools/watch-reports.py --dir <folder>   # somewhere else
    tools/watch-reports.py --interval 5     # seconds between scans

What it prints, and why that shape:

  * A **new report** is printed in full. It is short, and its first paragraphs are the
    only part with a person's intent in them, which is the part a machine cannot guess.
  * **live-trace.md** is printed only when it changes, and only the parts that carry a
    finding: the summary line, the unanswered taps, and the refusals. Printing the whole
    log every four seconds would bury the signal in exactly the way the app's own trace
    screen is designed not to.
  * **A refused action** — a `FAIL` line that has not been seen — is called out on its
    own line, because it is the commonest thing worth fixing and the easiest to miss.
"""

import argparse
import os
import sys
import time

DEFAULT_DIR = "/root/Downloads/MuffyCodes reports"
# The app falls back to its own external folder when MediaStore is not there, so both
# are watched. A person should not have to know which path their phone chose.
FALLBACK_DIRS = [
    DEFAULT_DIR,
    "/root/Downloads",
]

def digest(path):
    """A fingerprint of a file's identity, cheap enough to take every few seconds.

    `(size, mtime)` rather than a hash: this compares a file against itself a moment
    later, it is not protecting anything, and hashing a growing trace every four
    seconds is work for no answer. A file rewritten within the same nanosecond at the
    same length would be missed, which for a log with a timestamp on every line cannot
    happen.
    """
    try:
        info = os.stat(path)
    except OSError:
        return None
    return (info.st_size, info.st_mtime_ns)


def scan(folder):
    """Every file this script cares about, by name, with its fingerprint."""
    found = {}
    try:
        names = sorted(os.listdir(folder))
    except OSError:
        return found
    for name in names:
        if not name.endswith(".md"):
            continue
        if not (name.startswith("report-") or name.startswith("live-trace")):
            continue
        path = os.path.join(folder, name)
        if not os.path.isfile(path):
            continue
        found[name] = (path, digest(path))
    return found


def headline(text):
    """The part of a live trace worth reading at a glance.

    The full log is ~600 lines of taps; what a person watching wants is the verdict and
    the things that did not answer. Everything else is for the record, and the record is
    in the file.
    """
    lines = text.splitlines()
    out = []
    keeping = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("Muffy Codes") or stripped.startswith("Muffy Codes "):
            out.append(stripped)
            continue
        # The summary sentence: "12 of 14 answered, 2 still waiting, 0 failed."
        if " answered," in stripped:
            out.append(stripped)
            continue
        if stripped.startswith("Taps with no answer") or stripped.startswith("Refused:"):
            keeping = True
            out.append(stripped)
            continue
        if stripped.startswith("—") or stripped.startswith("Also in"):
            keeping = False
            continue
        if keeping and stripped:
            out.append(stripped)
    return out


def fail_lines(text):
    """Every `FAIL` line in a trace, which are the refusals worth naming."""
    return [l.strip() for l in text.splitlines() if " FAIL " in l]


def report(text, limit=60):
    """A report in full, bounded so one enormous file cannot swamp the terminal."""
    lines = text.splitlines()
    if len(lines) <= limit:
        return lines
    return lines[:limit] + [f"… {len(lines) - limit} more lines"]


def folders(requested):
    if requested:
        return [requested]
    return [d for d in FALLBACK_DIRS if os.path.isdir(d)]


def main():
    parser = argparse.ArgumentParser(description="Watch what the app wrote about itself.")
    parser.add_argument("--dir", help="the folder to watch (default: the app's report folder)")
    parser.add_argument("--once", action="store_true", help="scan once and exit")
    parser.add_argument("--interval", type=float, default=4.0, help="seconds between scans")
    parser.add_argument(
        "--all",
        action="store_true",
        help="print every report already there, not only new ones",
    )
    args = parser.parse_args()

    seen = {}
    seen_fails = set()
    watched = folders(args.dir)
    if not watched:
        print(f"nothing to watch: neither {DEFAULT_DIR} nor /root/Downloads exists", file=sys.stderr)
        return 1

    print("watching " + ", ".join(watched))
    print("the app rewrites live-trace.md as it is used; a report appears when the bug button is pressed")
    print()

    first = True
    while True:
        for folder in folders(args.dir):
            for name, (path, stamp) in scan(folder).items():
                before = seen.get(path)
                if before == stamp:
                    continue
                changed = before is not None
                seen[path] = stamp
                try:
                    with open(path, "r", encoding="utf-8", errors="replace") as handle:
                        text = handle.read()
                except OSError:
                    continue

                if name.startswith("report-"):
                    # A report is the person's own words. Everything else can wait.
                    if changed and not args.all:
                        continue
                    print(f"=== {name}")
                    for line in report(text):
                        print(f"    {line}")
                    print()
                else:
                    # A live trace: only what is new, and only what is a finding.
                    fresh = [l for l in fail_lines(text) if l not in seen_fails]
                    seen_fails.update(fresh)
                    lines = headline(text)
                    if not lines and not fresh:
                        continue
                    if first:
                        print("=== live-trace.md (current state)")
                    else:
                        print("=== live-trace.md (changed)")
                    first = False
                    for line in lines:
                        print(f"    {line}")
                    for line in fresh:
                        print(f"    NEW FAILURE: {line}")
                    print()
        if args.once:
            return 0
        try:
            time.sleep(args.interval)
        except KeyboardInterrupt:
            print()
            return 0


if __name__ == "__main__":
    sys.exit(main())
