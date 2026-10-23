#!/usr/bin/env python3
"""Says whether the suite still scales the way this project assumes.

The law is simple and it is written down in tools/scaling-budget.txt: adding
tests must not add wall time in proportion. Compilation is shared, a runner is
paid for once, and the tests themselves are small — so a suite twice the size
should cost about what it did before.

This reads the JUnit reports the test run already writes and checks the places
where that stops being true:

  * the suite as a whole grew past the point where one runner is the wrong shape
  * one class has become most of the suite's time
  * the average test is doing a whole scenario instead of one thing
  * a red run is spending its time waiting instead of failing
  * sharding is switched on after it stopped being worth a runner

It never fails anything. A slow suite is still a suite that passed, and a check
that blocks on speed only teaches people to skip tests. It prints warnings, and
it writes a table into the run's summary, so drift is visible on every run
rather than the day somebody notices the pipeline. Says one thing and is
deliberately quiet about everything else: the numbers, the budget they crossed,
and the specific thing to do next.

Usage:
  scaling-report.py [--results DIR] [--budget FILE] [--shards N]

Exit code: always 0. This is a report, not a gate.
"""

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET


def read_budget(path):
    budget = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.split("#", 1)[0].strip()
                if not line or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                try:
                    budget[key.strip()] = float(value.strip())
                except ValueError:
                    continue
    except OSError:
        # A missing budget file is not an error: every check below has a default,
        # and a run must never be stopped by the absence of a number.
        pass
    return budget


def read_results(folder):
    """Every test case the run recorded, as (class, name, seconds, failed)."""
    cases = []
    for path in sorted(glob.glob(os.path.join(folder, "*.xml"))):
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        for case in root.iter("testcase"):
            name = case.get("classname") or ""
            try:
                seconds = float(case.get("time") or 0)
            except ValueError:
                seconds = 0.0
            failed = any(child.tag in ("failure", "error") for child in case)
            cases.append((name.split(".")[-1], case.get("name") or "", seconds, failed))
    return cases


def main():
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("--results", default="app/build/test-results/testDebugUnitTest")
    parser.add_argument("--budget", default="tools/scaling-budget.txt")
    parser.add_argument("--shards", type=int, default=1)
    parser.add_argument("--summary", default=os.environ.get("GITHUB_STEP_SUMMARY", ""))
    args = parser.parse_args()

    budget = read_budget(args.budget)
    cases = read_results(args.results)

    warnings = []
    notes = []
    lines = []

    def warn(message):
        warnings.append(message)
        print(f"::warning title=Scaling::{message}")

    def note(message):
        notes.append(message)

    if not cases:
        # Nothing ran — a change that could not reach the app, or a build that
        # died before the tests started. Not this script's business to complain.
        print("No test results to read; nothing to say about scaling.")
        return 0

    total = sum(case[2] for case in cases)
    passed = sum(case[2] for case in cases if not case[3])
    failed = sum(case[2] for case in cases if case[3])
    failures = sum(1 for case in cases if case[3])

    # With more than one shard, a job only ever sees its own share of the suite.
    # The per-test budgets below still mean something; the whole-suite advice
    # does not, because the job cannot see the whole suite. Saying so is better
    # than guessing a total.
    whole_suite = args.shards <= 1

    by_class = {}
    for name, _, seconds, _ in cases:
        by_class[name] = by_class.get(name, 0.0) + seconds
    ranked = sorted(by_class.items(), key=lambda item: -item[1])

    average_ms = (total / len(cases)) * 1000 if cases else 0.0
    largest_name, largest_seconds = ranked[0]
    largest_share = largest_seconds / total if total else 0.0
    failure_share = failed / total if total else 0.0

    # ---- the table everybody reads first -------------------------------------
    lines.append("### How the suite scales")
    lines.append("")
    lines.append("| | |")
    lines.append("|---|---|")
    lines.append(f"| tests | {len(cases)} ({failures} failing) |")
    lines.append(f"| test time | {total:.1f}s |")
    lines.append(f"| average test | {average_ms:.0f} ms (budget {budget.get('max_average_ms', 0):.0f} ms) |")
    lines.append(
        f"| largest class | {largest_name} — {largest_seconds:.1f}s, "
        f"{largest_share * 100:.0f}% of the suite (budget {budget.get('max_class_share', 0) * 100:.0f}%) |"
    )
    if whole_suite:
        lines.append(
            f"| sharding | {args.shards} shard; "
            f"break-even at {budget.get('shard_break_even_s', 0):.0f}s of tests |"
        )
    else:
        lines.append(
            f"| sharding | this is one of {args.shards} shards, so the times below are "
            f"this shard's share |"
        )
    lines.append("")

    # ---- one class has become the suite --------------------------------------
    limit = budget.get("max_class_share", 0.5)
    if total and largest_share > limit:
        warn(
            f"{largest_name} is {largest_share * 100:.0f}% of the suite's time "
            f"({largest_seconds:.1f}s of {total:.1f}s, budget {limit * 100:.0f}%). "
            f"One class cannot be split and cannot be parallelised: split it along "
            f"what it actually verifies, or move its shared setup into a fixture "
            f"that is built once."
        )

    # ---- the average test ---------------------------------------------------
    cap = budget.get("max_average_ms", 1500)
    if average_ms > cap:
        warn(
            f"The average test takes {average_ms:.0f} ms (budget {cap:.0f} ms). "
            f"Tests that cost seconds are usually doing a whole scenario: narrow "
            f"each one to the single thing it is named after, and let the scenario "
            f"live in one end-to-end test rather than in twenty."
        )

    # ---- the cost of being red ----------------------------------------------
    red_cap = budget.get("max_failure_share", 0.30)
    if failures and failure_share > red_cap:
        warn(
            f"This run spent {failure_share * 100:.0f}% of its test time in failing "
            f"tests (budget {red_cap * 100:.0f}%). A red run must fail fast: check "
            f"the waits in the test harness — a timeout that is generous enough to "
            f"hide a hang is generous enough to make every red run slow to read."
        )

    # ---- sharding, in both directions ---------------------------------------
    break_even = budget.get("shard_break_even_s", 17)
    recommend = budget.get("shard_recommended_s", 60)
    if not whole_suite:
        note(
            f"Sharding is on ({args.shards} shards). This job ran its share in "
            f"{total:.1f}s; the whole suite is roughly a multiple of that, and the "
            f"run's own job list is where the real figure is."
        )
    elif args.shards <= 1:
        if total >= recommend:
            warn(
                f"The tests take {total:.1f}s on one shard (recommended split at "
                f"{recommend:.0f}s). Set the repository variable TEST_SHARDS to "
                f"[0,1] — but only if this job is the pipeline's longest: "
                f"sharding shortens the suite, not the pipeline, so a build that "
                f"outlasts the tests is the thing to look at first."
            )
        elif total >= break_even:
            note(
                f"Sharding would pay ({total:.1f}s of tests against a "
                f"{break_even:.0f}s break-even), but is not worth a second runner "
                f"until it is past {recommend:.0f}s."
            )
        else:
            note(
                f"One shard is correct: the tests cost {total:.1f}s and a second "
                f"runner would cost {break_even:.0f}s of setup to save half of it."
            )
    elif whole_suite:
        if total < break_even:
            warn(
                f"Sharding is on ({args.shards} shards) but the tests cost only "
                f"{total:.1f}s — less than the {break_even:.0f}s a second runner "
                f"costs to start. Each shard pays more than it saves: set "
                f"TEST_SHARDS back to [0]."
            )
        else:
            saved = total - (total / args.shards) - break_even
            note(
                f"Sharding is on: {args.shards} shards over {total:.1f}s of tests, "
                f"about {saved:.0f}s saved per run against one runner."
            )

    # ---- the panic floor ----------------------------------------------------
    panic = budget.get("panic_total_s", 300)
    if total > panic:
        print(
            f"::error title=Scaling::The suite takes {total:.1f}s, past the panic "
            f"floor of {panic:.0f}s. Nothing here is failing — this is the shape of "
            f"the suite, and it needs sharding or narrowing before it grows further."
        )

    # ---- the slowest classes, always shown ----------------------------------
    lines.append("<details><summary>The four slowest classes</summary>")
    lines.append("")
    lines.append("| class | time | tests | average |")
    lines.append("|---|---|---|---|")
    counts = {}
    for name, _, _, _ in cases:
        counts[name] = counts.get(name, 0) + 1
    for name, seconds in ranked[:4]:
        avg = seconds / counts[name] * 1000
        lines.append(f"| {name} | {seconds:.1f}s | {counts[name]} | {avg:.0f} ms |")
    lines.append("")
    lines.append("</details>")
    lines.append("")

    if warnings:
        lines.append(f"**{len(warnings)} thing(s) to do about the shape of the suite.**")
    else:
        lines.append("Within every budget: adding tests is still close to free.")
    if notes:
        lines.append("")
        for message in notes:
            lines.append(f"- {message}")

    text = "\n".join(lines)
    print(text)
    if args.summary:
        try:
            with open(args.summary, "a", encoding="utf-8") as handle:
                handle.write(text + "\n")
        except OSError:
            # The summary is a convenience for the reader. A run whose summary
            # could not be written has still done its job and said its piece above.
            pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
