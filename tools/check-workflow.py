#!/usr/bin/env python3
"""Check the workflow for the mistakes that cost whole runs.

This is not a second lint. The lint job checks the app — Kotlin, resources,
Android's own rules. This checks one file, `.github/workflows/`, and it checks
the things that only exist there. Nothing overlaps.

It is also not a YAML parser in the usual sense. GitHub already validates the
YAML when a ref is pushed: a file that does not parse is refused, loudly, at the
moment it is pushed. That failure is cheap and self-announcing. The failures that
cost time are the ones YAML accepts:

  * shell that does not parse          — caught by `bash -n`, nothing else
  * `needs:` naming a job that is gone  — GitHub refuses the whole workflow
  * an `if:` reading `steps.<id>` where no step has that id
        This is the quiet one. GitHub evaluates the reference to an empty string,
        the condition is false, the step is skipped, and the job reports success.
        A gate that never ran looks exactly like a gate that passed.

Every check here is about this file alone, so it runs in milliseconds.
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

WORKFLOW_DIR = Path(".github/workflows")

# A job key sits at two spaces; the `on:` block's keys sit at the same depth, so
# everything is scanned after `jobs:` rather than guessing from indentation.
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):\s*$")
NEEDS = re.compile(r"^\s+needs:\s*(.+?)\s*$")
USES = re.compile(r"^\s+(?:-\s+)?uses:\s*(\S+)")
STEP_ID = re.compile(r"^\s+id:\s*([A-Za-z0-9_-]+)\s*$")
STEP_REF = re.compile(r"\bsteps\.([A-Za-z0-9_-]+)\.")
RUN_HEAD = re.compile(r"^(\s+)run:\s*(.*)$")
INTERPOLATION = re.compile(r"\$\{\{")

problems: list[str] = []


def fail(where: str, what: str) -> None:
    problems.append(f"{where}: {what}")


def indent_of(line: str) -> int:
    return len(line) - len(line.lstrip())


def jobs(lines: list[str]) -> dict[str, tuple[int, int]]:
    """Each job's name, and the line span of its body."""
    start = next((i for i, line in enumerate(lines) if line.rstrip() == "jobs:"), None)
    if start is None:
        return {}
    found = {}
    current = None
    for i in range(start + 1, len(lines)):
        line = lines[i]
        match = JOB_KEY.match(line) if line.strip() and indent_of(line) == 2 else None
        if match:
            if current:
                found[current] = (found[current][0], i)
            current = match.group(1)
            found[current] = (i, len(lines))
    return found


def run_blocks(lines: list[str], begin: int, end: int) -> list[tuple[int, str]]:
    """Every `run:` in a job, as (line number, shell text).

    A block scalar's text is the lines indented deeper than its `run:`; a
    one-liner is the rest of the line. Both become shell that `bash -n` reads.
    """
    blocks = []
    i = begin
    while i < end:
        match = RUN_HEAD.match(lines[i])
        if not match:
            i += 1
            continue
        base = len(match.group(1))
        rest = match.group(2).strip()
        if rest not in ("|", ">", "|-", ">-", "|+", ">+"):
            blocks.append((i + 1, rest))
            i += 1
            continue
        body = []
        j = i + 1
        while j < end:
            line = lines[j]
            if line.strip() and indent_of(line) <= base:
                break
            body.append(line)
            j += 1
        while body and not body[-1].strip():
            body.pop()
        width = min((indent_of(l) for l in body if l.strip()), default=0)
        blocks.append((i + 1, "\n".join(l[width:] for l in body) + "\n"))
        i = j
    return blocks


def check_shell(where: str, line: int, shell: str) -> None:
    if INTERPOLATION.search(shell):
        # The rule is not stylistic: an expression spliced into a script is a
        # template injection, and a value with a quote or a newline in it changes
        # what the script means. Values arrive through `env:` instead.
        fail(f"{where}:{line}", "an expression is spliced into a shell block; pass it through env:")

    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as handle:
        handle.write(shell)
        path = handle.name
    result = subprocess.run(["bash", "-n", path], capture_output=True, text=True)
    Path(path).unlink()
    if result.returncode != 0:
        # bash names the temporary file and counts lines inside the block, so the
        # path is dropped and the count is kept: "line 21 of this block" is what
        # someone can act on.
        detail = (result.stderr or "").replace(path, "the block").strip().splitlines()
        fail(f"{where}:{line}", "shell does not parse — " + (detail[0] if detail else "no reason given"))


# **Encryption is not checked for, because it is not allowed.** The operator rejected it outright
# ("I explicitly rejected it and left it to be just an example of what can be added to a workflow"), so
# this file no longer asks whether a sealing step can reach its key. It refuses the step instead: a
# pipeline that seals its own artifacts cannot be read by the person reading a failure, which is the
# cost that made the removal worth doing.
ENCRYPTS = re.compile(r"openssl\s+enc\b")


# **The one branch this repository has.** The workflow is muffycodes' own CI, and since the
# operator's ruling of 2026-11-07 (`docs/PLAN-universal-2026-11-06.md` §13b.1) it is `main` only —
# `development` was collapsed into `main` and deleted on 2026-11-11.
#
# This is checked rather than remembered because the mistake has already happened twice, in both
# directions: narrowing the trigger to `[main]` while `development` was still in use stopped every
# run for three commits, and leaving a `development` special case behind after the branch was gone
# is the same bug with the sign flipped — a step that can never run, or a branch that gets no CI.
ONLY_BRANCH = "main"
PUSH_BRANCHES = re.compile(r"^\s+branches:\s*\[(.*?)\]\s*$")

def check_one_branch(where: str, lines: list[str]) -> None:
    """The push trigger names one branch, and it is `main`.

    **A trigger is the one thing in this file whose failure is silent.** A step with a broken `if:`
    skips and says so; a trigger that names a branch nobody pushes to simply never starts, and the
    absence of a run looks exactly like nothing having happened. That is why it is checked here.
    """
    for i, line in enumerate(lines):
        match = PUSH_BRANCHES.match(line)
        if not match:
            continue
        # Only the `push:` block's own `branches:` counts; `pull_request:` may name its own.
        above = next((l.rstrip() for l in reversed(lines[:i]) if l.strip()), "")
        if above.strip() == "pull_request:":
            continue
        named = [b.strip().strip("'\"") for b in match.group(1).split(",")]
        named = [b for b in named if b]
        if named != [ONLY_BRANCH]:
            fail(
                f"{where}:{i + 1}",
                f"the push trigger names {named}, but this repository has one branch: "
                f"[{ONLY_BRANCH!r}]. A branch that is not main is a pull request waiting to "
                f"happen, and a branch listed here but never pushed to silently gets no CI.",
            )

    # A leftover branch comparison is the other half: it is a condition that is now always true or
    # always false, which is how "no release APK was ever built on main" happened.
    for i, line in enumerate(lines):
        if line.lstrip().startswith("#"):
            continue
        if re.search(r"ref_name\s*[!=]=\s*'development'|refs/heads/development", line):
            fail(
                f"{where}:{i + 1}",
                "a condition still compares against `development`, which no longer exists; "
                "it is now always true or always false",
            )


def check_no_sealing(where: str, job: str, body: list[str]) -> None:
    """A workflow must not encrypt its own output.

    **This is a policy check, not a safety check.** The app holds no encryption rule, and a pipeline
    that invents one puts the rejected design in a second place — where it is harder to find and it
    hides the assertion messages a red run exists to show.
    """
    for i, line in enumerate(body):
        if ENCRYPTS.search(line) and not line.lstrip().startswith("#"):
            fail(
                where,
                f"job {job!r} encrypts its own output; the project holds no encryption rule "
                f"and a sealed artifact cannot be read by the person diagnosing a failure",
            )
            return


def check_one(workflow: Path) -> None:
    """Every check, applied to one workflow file."""
    text = workflow.read_text()
    lines = text.splitlines()
    where = str(workflow)
    found = jobs(lines)

    if not found:
        fail(where, "no jobs were found at all")
        return

    # needs: must name a job that exists. A dangling one is refused by GitHub for
    # the whole file, so the run does not even start.
    for name, (begin, end) in found.items():
        for i in range(begin, end):
            match = NEEDS.match(lines[i])
            if not match:
                continue
            value = match.group(1).strip()
            value = value.strip("[]")
            for need in (n.strip().strip("'\"") for n in value.split(",")):
                if need and need not in found:
                    fail(f"{where}:{i + 1}", f"job {name!r} needs {need!r}, which is not a job")

        # A step id that is read but never declared makes its condition quietly
        # false. The step never runs and nothing fails.
        ids = {m.group(1) for m in (STEP_ID.match(lines[i]) for i in range(begin, end)) if m}
        for i in range(begin, end):
            for ref in STEP_REF.findall(lines[i]):
                if ref not in ids:
                    fail(
                        f"{where}:{i + 1}",
                        f"job {name!r} reads steps.{ref}, but no step in that job has id {ref!r}",
                    )

    # Every action is pinned to a commit, so that what ran is what was reviewed.
    for i, line in enumerate(lines):
        match = USES.match(line)
        if not match:
            continue
        target = match.group(1)
        if target.startswith("./"):
            continue
        if not re.search(r"@[0-9a-f]{40}$", target):
            fail(f"{where}:{i + 1}", f"{target} is not pinned to a commit")

    for name, (begin, end) in found.items():
        for line, shell in run_blocks(lines, begin, end):
            check_shell(f"{where} (job {name})", line, shell)

        check_no_sealing(f"{where}:{begin + 1}", name, lines[begin:end])

    check_one_branch(where, lines)


def main() -> int:
    # **Every workflow, not one named file.** This checked a hardcoded path while its own docstring
    # said it checked `.github/workflows/` — so a second workflow was reported clean without being
    # read at all, which is the silent-pass shape this file exists to catch. It was found when a
    # second workflow was added and passed a check that had never opened it.
    #
    # **The glob stays, even though there is one workflow now.** The bug was the hardcoded name, not
    # the number of files; a version that named the only file it expected would come back the moment
    # somebody added a second one.
    workflows = sorted(WORKFLOW_DIR.glob("*.yml"))
    if not workflows:
        print(f"  no workflows in {WORKFLOW_DIR}", file=sys.stderr)
        return 1
    for workflow in workflows:
        check_one(workflow)
    return report()


def report() -> int:
    if problems:
        print(f"{len(problems)} problem(s) in the workflow:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print(f"  workflow clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
