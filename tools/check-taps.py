#!/usr/bin/env python3
"""The tap vocabulary, checked rather than remembered.

## Why this exists

A log is only readable if every line obeys the same two rules. Both were broken before
this file existed, and both were broken quietly:

1. **One name per element.** "history" and "history from editor" were the same button on
   two screens, so a log could not be compared across them. "Save" was capitalised while
   every other name was lowercase, so `grep " save "` missed it.
2. **`confirm <what opened it>`.** Opening a window and pressing its button are two
   different acts. When a button was called something else entirely — `move chosen` for
   the button of `move selected` — the log could not say whether a window was dismissed
   or an action happened.

## What it checks

- every `Trace.tap(...)` with a literal first argument is in the vocabulary below;
- every name is lowercase, one to three words, and has no digits or slashes;
- every `confirm X` has a matching `X` recorded somewhere, because a confirmation
  without the act it confirms is a name that drifted;
- no file records the same name twice with different meanings (the one-gesture-one-id
  rule can only be checked by reading, but a duplicate literal in two files is a smell).

Exit code 1 with a list of problems, 0 when clean, like the other check- scripts.

    python3 tools/check-taps.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "app/src/main/java/com/example/notes"

# The whole vocabulary. Adding a tap means adding a word here, which is the point: the
# list is the answer to "what can a person press", and it should stay short enough to
# read in one go.
VOCABULARY = {
    # getting around
    "open file", "open folder", "close editor", "close history", "close changes", "settings",
    # editing
    "save", "history", "restore version",
    # a row's own switches
    "lock", "unlock", "pin", "unpin", "select", "unselect",
    # what a row's menu offers, and what the selection bar offers
    "move", "copy", "delete", "rename", "add to gitignore",
    # making things
    "new file", "new folder", "upload files", "upload folder", "restore deleted",
    # projects
    "add project", "new local project", "sync", "see changes", "rename project", "delete project",
    # the account
    "add account", "confirm add account", "remove account", "use test account",
    # the log itself
    "share log",
    # dialogs' buttons — the acts above, confirmed
    "confirm new file", "confirm new folder", "confirm delete", "confirm rename",
    "confirm move", "confirm copy", "confirm restore deleted", "confirm sync",
    "confirm rename project", "confirm delete project", "confirm remove account",
    # settings, which go through the store rather than the view model
    "theme", "dark", "light", "history limit",
    # the agent's model: the three lists, the two ways to change the list of models, and the
    # catalogue the numbers come from. A list opened and closed is not here — it changed nothing.
    "reset model config", "set model key", "save key",
    "model", "provider", "effort", "add model", "remove model", "read catalogue",
    # making a project, which is a different act from adding one that is already on GitHub
    "new project", "local project", "confirm add project",
    # replacing a copy that is already here
    "replace", "confirm replace",
    # the agent's conversation: one project's, and the turns inside it
    "chat", "close chat", "send", "previous turn", "next turn", "latest turn", "turn",
    # the agent itself, how often it reminds, and the numbers it runs with
    "start agent", "stop agent", "stop all agents", "reset agent", "remind me",
    "answer timeout", "result length",
    # **how long a failing connection is retried before somebody is told.** Its own name rather than
    # folded into "answer timeout": they bound different things, and a log that could not tell them
    # apart would not answer "did it give up, or was it still trying".
    "reconnect window",
    # the three waits the operator owns: the keepalive that keeps a waiting conversation warm (its
    # switch, its interval and its ceiling), and the two command limits — how long a command whose
    # result goes into the conversation may run, and how long one whose result goes to a file may
    # run. The last two are separate numbers because the results land in two places.
    "keepalive", "keepalive interval", "keepalive cap",
    "foreground timeout", "background timeout",
    # how big the evidence may get, which is a different number from the context's: one takes a row
    # out of what the model is sent, the other removes it
    "session log",
    # the two system settings this app needs, both asked for from settings
    "battery", "notifications",
    "clear up", "trim context",
}

# Names built from a value, so they cannot be a literal in the list.
DYNAMIC = {"lock", "unlock", "pin", "unpin", "select", "unselect", "dark", "light"}

# Both forms: `Trace.tap(...)` and the screen's own `tap(...)`, which is the same
# recorder under a shorter name.
CALL = re.compile(r'(?:Trace\.)?tap\(\s*"([^"]*)"')
TERNARY = re.compile(r'(?:Trace\.)?tap\(\s*if \([^)]*\)\s*"([^"]*)"\s*else\s*"([^"]*)"')


def main() -> int:
    problems = []
    seen = {}

    for path in sorted(SOURCES.glob("*.kt")):
        if path.name == "Trace.kt":
            continue
        text = path.read_text()
        for match in TERNARY.finditer(text):
            for name in match.groups():
                seen.setdefault(name, set()).add(path.name)
        for match in CALL.finditer(text):
            seen.setdefault(match.group(1), set()).add(path.name)

    for name, where in sorted(seen.items()):
        if name not in VOCABULARY:
            problems.append(f"{'/'.join(sorted(where))}: \"{name}\" is not in the vocabulary")
            continue
        if name != name.lower():
            problems.append(f"\"{name}\" is not lowercase")
        if not re.fullmatch(r"[a-z]+( [a-z]+){0,2}", name):
            problems.append(f"\"{name}\" is not one to three lowercase words")
        if name in DYNAMIC and len(where) > 1:
            problems.append(
                f"\"{name}\" is recorded in {len(where)} files ({', '.join(sorted(where))}) — "
                "one gesture should have one recorder"
            )

    # every confirmation must confirm something that is also recorded
    for name in sorted(seen):
        if not name.startswith("confirm "):
            continue
        opener = name[len("confirm "):]
        if opener not in seen:
            problems.append(
                f"\"{name}\" confirms \"{opener}\", which is never recorded — "
                "the pair is `X` then `confirm X`"
            )

    if problems:
        print("::error title=Tap vocabulary::the log's names drifted from the standard")
        for problem in problems:
            print("  " + problem)
        print(f"\n{len(problems)} problem(s)")
        return 1

    print(f"  {len(seen)} names, all in the vocabulary, all paired")
    return 0


if __name__ == "__main__":
    sys.exit(main())
