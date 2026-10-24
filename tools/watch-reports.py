#!/usr/bin/env python3
"""Reads the app's one log, as it is written.

A debug build of Muffy Codes appends every event to a single file:

    Download/MuffyCodes log/trace.log

with fixed columns — `time  id  kind  name  detail`. A report is a `REPORT` line in
the same stream, so a person's words sit exactly where the context is.

This script follows that file and prints only what is new. It also answers the one
question the format was chosen for: **which taps never got an answer**. A tap and its
answer share an id, so a tap whose id appears only once is a thing that was asked for
and never happened.

    tools/watch-reports.py                  # follow it until interrupted
    tools/watch-reports.py --once           # read what is there now
    tools/watch-reports.py --dir <folder>   # somewhere else
    tools/watch-reports.py --stuck          # only the unanswered taps, then exit

The file is append-only and honest: a line, once written, stays written. The only
thing that removes lines is a trim inside the app, which leaves a marker line where it
happened — so `--stuck` never has to wonder whether it is looking at the whole story.
"""

import argparse
import os
import re
import sys
import time

DEFAULT_DIR = "/root/Downloads/MuffyCodes log"
FALLBACK_DIR = "/root/Downloads"
NAME = "trace.log"

# `16:03:11.412   42 TAP      Save       milk.txt`
LINE = re.compile(r"^(\d\d:\d\d:\d\d\.\d\d\d)\s+(\d+)\s+([A-Z]+)\s+(\S+)(?:\s{2,}(.*))?$")


def log_path(folder):
    """The log, wherever the phone decided to put it."""
    direct = os.path.join(folder, NAME)
    if os.path.isfile(direct):
        return direct
    # The app falls back to its own external folder, which is deeper; a nested search is
    # what saves a person from having to know which path their phone chose.
    for root, dirs, files in os.walk(folder):
        if NAME in files:
            return os.path.join(root, NAME)
        if root.count(os.sep) - folder.count(os.sep) > 2:
            dirs[:] = []
    return None


def read_from(path, offset):
    """New lines, and the offset to continue from."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            handle.seek(offset)
            text = handle.read()
            return text, handle.tell()
    except OSError:
        return "", offset


def as_id(match):
    """The id column as a number, or None when it is not one.

    A line whose id will not parse is not one of the app's events, but it is still kept
    verbatim rather than dropped: a log with a hole and no explanation is worse than a
    longer log.
    """
    try:
        return int(match.group(2))
    except (TypeError, ValueError):
        return None


def parse(lines):
    """Every line this script understands, with its columns split."""
    out = []
    for raw in lines:
        match = LINE.match(raw.rstrip("\n"))
        number = as_id(match) if match else None
        if match and number is not None:
            out.append(
                {
                    "at": match.group(1),
                    "id": number,
                    "kind": match.group(3),
                    "what": match.group(4),
                    "detail": (match.group(5) or "").strip(),
                    "raw": raw.rstrip("\n"),
                }
            )
        elif raw.strip():
            # A trim marker, or anything the app wrote that is not an event. Kept, because
            # a log with holes and no explanation is worse than a shorter log.
            out.append({"at": "", "id": 0, "kind": "—", "what": "", "detail": raw.rstrip("\n"), "raw": raw.rstrip("\n")})
    return out


def stuck(events):
    """Taps whose id never came back — the broken sequences, and nothing else.

    This is the whole reason the id column exists. `TAP` with no other line carrying the
    same id is a request the app accepted and never finished, which is what "I pressed it
    and nothing happened" means, stated as a fact rather than as an impression.
    """
    answered = {e["id"] for e in events if e["kind"] in ("CHANGED", "SAID", "FAIL")}
    return [e for e in events if e["kind"] == "TAP" and e["id"] not in answered]


def describe(event):
    """One event, as a line worth reading."""
    detail = f"  {event['detail']}" if event["detail"] else ""
    return f"{event['at']}  #{event['id']:<4} {event['kind']:<7} {event['what']}{detail}"


def main():
    parser = argparse.ArgumentParser(description="Follow the app's log.")
    parser.add_argument("--dir", default=None, help="where the log is (default: Downloads)")
    parser.add_argument("--once", action="store_true", help="read what is there now, then exit")
    parser.add_argument("--stuck", action="store_true", help="only the unanswered taps, then exit")
    parser.add_argument("--interval", type=float, default=3.0, help="seconds between reads")
    parser.add_argument("--from-start", action="store_true", help="replay the whole log")
    args = parser.parse_args()

    folder = args.dir or (DEFAULT_DIR if os.path.isdir(DEFAULT_DIR) else FALLBACK_DIR)
    if not os.path.isdir(folder):
        print(f"no folder at {folder}", file=sys.stderr)
        return 1

    path = log_path(folder)
    if path is None:
        print(f"no {NAME} under {folder} yet — is the dev build installed and the switch on?")
        print("watching for it to appear…")
    else:
        print(f"reading {path}")

    offset = 0
    seen = 0
    everything = []

    while True:
        current = path or log_path(folder)
        if current is None:
            if args.once or args.stuck:
                return 1
            time.sleep(args.interval)
            continue
        path = current

        # Following a session starts at the end, so it does not replay everything that
        # has ever happened on the phone. Asking a question — `--once`, `--stuck` — is a
        # question about the whole log, so it reads from the beginning.
        following = not (args.once or args.stuck or args.from_start)
        if following and offset == 0 and seen == 0:
            try:
                offset = os.path.getsize(path)
            except OSError:
                offset = 0

        text, offset = read_from(path, offset)
        if text:
            events = parse(text.splitlines())
            everything.extend(events)
            for event in events:
                if event["kind"] == "REPORT":
                    print()
                    print(f"=== A REPORT: {event['detail'] or '(no words)'}")
                    print("    the lines above it are what the app was doing")
                    print()
                elif event["kind"] == "FAIL":
                    print(f"  REFUSED {event['what']}: {event['detail']}")
                elif event["kind"] == "TAP":
                    print(f"  {describe(event)}")
                elif event["kind"] == "CHANGED":
                    # Answers are frequent and mostly uninteresting in a stream. They are
                    # printed only when they answer a tap the reader has already seen.
                    print(f"  {describe(event)}")
            seen += len(events)

        if args.stuck:
            unanswered = stuck(everything)
            if unanswered:
                print(f"\n{len(unanswered)} tap(s) with no answer:")
                for event in unanswered:
                    print(f"  {describe(event)}")
            else:
                print("\nevery tap was answered")
            return 0

        if args.once:
            found = stuck(everything)
            if found:
                print(f"\n{len(found)} tap(s) with no answer:")
                for event in found:
                    print(f"  {describe(event)}")
            return 0

        time.sleep(args.interval)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print()
        sys.exit(0)
