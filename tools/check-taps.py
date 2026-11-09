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
    # the editor's three modes, and the diff's own two acts
    "editor view", "editor edit", "editor diff", "diff", "diff commit",
    # entering a file from the list of what is waiting to be sent
    "open changed file",
    # a row's own switches
    "lock", "unlock", "pin", "unpin", "select", "unselect",
    # what a row's menu offers, and what the selection bar offers
    # **`ignore` / `track` are the pair that replaced `unsync` / `sync again`**, and the history is
    # worth keeping because it went in a circle. `add to gitignore` came first; it was replaced by
    # `unsync` because an ignore untracks the file and the app wanted a tracked file kept off GitHub;
    # and now the operator has ruled the other way — "gitignore should be just classic gitignore, not
    # as sync currently implemented but a simple tracked/untracked switch". So the act is an ignore
    # again, and the derived-tree mechanism that made `unsync` necessary is deleted rather than kept.
    #
    # `add to gitignore`, `unsync` and `sync again` stay in the vocabulary because a log written by
    # an older build still holds them, and a trace that cannot be read is not a trace.
    "move", "copy", "delete", "rename",
    "add to gitignore", "unsync", "sync again", "ignore", "track",
    # **Taking a copy out of a folder the app keeps.** A different name from `copy` because
    # it is a different button: the one item a system-locked row offers, and the trace
    # should name what the person pressed rather than the code path behind it.
    "clone",
    # **Copying a file out to the phone's Downloads** — report 23's "clone/move to the downloads".
    # Named after the button, which says Downloads, because `export` already means the project
    # menu's "the newest CI artifact" and the two are not the same act.
    "copy to downloads",
    # the chat: turning to another page of a message, and opening one fullscreen
    "message page", "message fullscreen",
    # making things
    "new file", "new folder", "new image", "upload files", "upload folder", "restore deleted",
    # drawing on a picture: the four tools, the two pickers, and the one undo
    "draw tool", "draw colour", "draw width", "draw undo",
    # a text file's view, rendered to a picture so it can be drawn on
    "photo",
    # projects
    "add project", "sync", "see changes", "rename project", "delete project",
    # **The address is the verb** (2026-11-08): a project's name follows where its repository is, so
    # publishing a local project and renaming a synced one are the same press on the same field. The
    # old names stay because a log written by an older build still holds them.
    "set address", "confirm set address", "publish", "keep local",
    "confirm publish", "confirm keep local",
    # **A project leaving or being copied into another account** (§3.9). One name each because a move
    # and a copy are different promises — one ends the source, one does not — and the log has to be
    # able to answer "did the folder leave this account" without reading the arg.
    "move project", "copy project",
    "confirm move project", "confirm copy project",
    # a project's newest artifact out to the phone, through the same function the agent's tool uses
    "export",
    # publishing a release from inside the app (§13b.7): the strip's button, then the window's press
    "publish release", "confirm publish release",
    # **A row's credentials** (`PLAN-credentials` §2.2). Two kinds because they are two acts with
    # different blast radii — a git token can push, a model key can spend money — and two doors per
    # kind (`model connection` opens the window, `confirm model connection` keeps what it typed)
    # because a log that could not tell them apart could not answer "did the person get as far as
    # saving it", which is the question a connection that does not work asks.
    "model connection", "confirm model connection", "check model connection",
    "git connection", "confirm git connection", "check git connection", "forget git connection",
    # the account
    # **One confirm for both doors.** `add account` opens the choice; the choice is which door. The
    # account that is made is the same kind of thing either way, so the log has one name for the save
    # and the door is the arg — a second name would make "how was this account added" a filter over
    # two words instead of one.
    "add account", "confirm add account", "remove account", "use test account",
    # entering and leaving an account's folder — the main menu is the list of them (§13c.2)
    "enter account", "leave account",
    # the branches of a project (§13d)
    "add branch", "enter branch", "confirm add branch", "confirm remove branch",
    "open branches", "remove branch",
    # the log itself, and the table counted from the request logs
    "share log", "stats", "stats clock", "stats from", "stats generate", "stats interval",
    "stats rolling", "stats time", "stats to",
    # how much of it is kept
    "request log limit",
    # dialogs' buttons — the acts above, confirmed
    "confirm new file", "confirm new folder", "confirm new image", "confirm delete",
    "confirm rename",
    "confirm move", "confirm copy", "confirm restore deleted", "confirm sync",
    "confirm rename project", "confirm delete project", "confirm remove account",
    # settings, which go through the store rather than the view model
    "theme", "dark", "light", "history limit", "reset settings",
    # the model's three rows: choosing one, and editing what a model may be asked through
    "model", "provider", "effort", "add model", "remove model", "add provider", "remove provider",
    # **Reordering the endpoints, which is the fallback order.** A move rather than an edit, so it
    # gets its own name: a log that said "provider" for both a selection and a drag could not answer
    # "which endpoint did the agent try first".
    "reorder provider",
    # the agent's model: the three lists, the two ways to change the list of models, and the
    # catalogue the numbers come from. A list opened and closed is not here — it changed nothing.
    "reset model config", "set model key", "save key",
    # **The vault.** One name for opening it, because the screen is one place: adding a secret and
    # removing one are edits inside it, and a log that called each of them "secrets" could not tell
    # a person who looked from a person who changed something.
    "secrets",
    # **Signing in to OpenRouter instead of pasting a key.** Two names because they are two acts:
    # opening the page and spending a code. A log that called both "sign in" could not answer
    # "did the person get as far as OpenRouter", which is the question a failed sign-in asks.
    "openrouter sign in", "openrouter code",
    # **A link pressed in a document.** Two names, because they are different acts: one leaves the
    # app for a web page, the other makes the app do something. A log that called both "link" could
    # not answer "did the onboarding page get them as far as the sign-in dialog".
    "link", "link action",
    # **A GitHub device-flow sign-in.** Its own name because it is a different act from `save key`
    # and from `sign in`: no credential is typed, and the person is on github.com while it runs.
    "github device",
    # **Opening github.com/login/device from the dialog.** Its own name because it is a departure
    # from the app, like `openrouter sign in`, and the trace should show a person left to finish the
    # flow somewhere else.
    "open github device",
    # **A write that followed a read of a file that changed underneath it.** The one lost-update the
    # app cannot prevent — there is no merge, so the second write wins — and the trace line is the
    # whole mitigation: it stops being invisible.
    "stale-read",
    # **Leaving fullscreen, and going from reading to writing.** Two acts: one returns to the frame,
    # the other puts a cursor in the text. A log that called both "editor" could not answer "did the
    # person ever try to edit this page", which is the question an onboarding report asks.
    "editor exit", "editor edit",
    # **Putting the text back to what is on disk.** Named for what it does rather than "reset",
    # because "reset" alone could be resetting the view, the drawing or the name.
    "editor reset",
    # **Moving to the next file by swiping.** Its own name rather than "open file", because a swipe
    # and a press are different ways of arriving: a log that called both "open file" could not answer
    # "did the person browse the folder or jump straight to one".
    "swipe file",
    # **Swiping past the last onboarding page.** Its own name because it is the only swipe that ends
    # something rather than moving to another file, and a log that called it "swipe file" would
    # suggest a page was opened.
    "model", "provider", "effort", "add model", "remove model", "read catalogue",
    # making a project, which is a different act from adding one that is already on GitHub
    # **`add from address` is the universal half of the add window.** `add project` opens the window;
    # this is its confirm for the address field, and `confirm add project` is the confirm for a row of
    # GitHub's list. Two confirms because they are two different acts with two different sentences when
    # they fail — one is a clone from an address, the other is a repository picked from an account.
    "new project", "confirm add project", "add from address",
    # **What a project is.** Its own name rather than "new project", because the two are different
    # questions: one makes a project and the other changes what an existing one is, and a log that
    # called both "project" could not answer "when did this project stop being an Android app".
    "project type",
    # replacing a copy that is already here
    "replace", "confirm replace", "already here",
    # the agent's conversation: one project's, and the turns inside it
    "chat", "close chat", "send", "previous turn", "next turn", "latest turn", "turn",
    # **A bubble is what the conversation is made of now**, so opening one is its own act - and the
    # navigator's three names above stay because a tap log is a record of what happened, not of
    # what the screen offers today.
    "bubble", "bubble height",
    # opening one fullscreen and coming back - a page view of the same bubble, so the name says
    # "page" rather than "bubble" a second time
    "bubble page", "close page",
    # the agent itself, how often it reminds, and the numbers it runs with
    "start agent", "stop agent", "stop all agents", "reset agent", "remind me",
    "answer timeout", "result length",
    # **The one clock, after the two were merged again.** They were split when streaming landed -
    # nothing came back at all, versus it went quiet halfway through - and the operator merged them
    # back: "lets merge connection and stalling timeouts into a single response timeout". The number
    # is an idle timeout now, restarting on every packet, so one name covers both silences.
    #
    # The two old names stay in the vocabulary because a log written by an older build still holds
    # them, and a vocabulary that rejected its own history could not read the file it exists for.
    "response timeout", "ack timeout", "idle timeout",
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
    # the two compactions, named for the hand that does them: the agent forgetting rows, and the app
    # trimming the oldest ones. They share a target and have separate triggers, so they are two
    # numbers to set rather than one.
    "forget compaction", "trim compaction", "compaction target",
    # the browser: whether the agent may reach this phone and the private network, and how long one
    # page may take. Two acts because they answer two questions - *where* may it read, and *how long*
    # may it spend - and the first is a capability rather than a limit.
    "browse private", "browse timeout", "fetch size",
    # the injection scanner's threshold: how sure a guard model must be before a section of a page is cut.
    # Its own act rather than part of the browse block, because it decides *what survives* rather than
    # where or how long.
    "scan at",
    # the two capabilities themselves, on or off: whether the agent may download a file at all, and
    # whether foreign text is scanned before it reads it. Both remove the thing rather than making it
    # refuse, so both are worth a tap of their own.
    "fetch", "scan", "http",
    # the two ways back through a long conversation: the pager's own arrows, and the jump to the end
    # that only appears when the person has scrolled away from it
    "chat to bottom",
    # **asking the person's request log a question in SQL.** Its own act because it is the one tool
    # whose effect lands in a file rather than in the conversation, and because a log that could not
    # say "the agent queried the log" would leave the person unable to tell what produced a CSV.
    "query",
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
