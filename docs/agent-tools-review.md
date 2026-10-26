# The agent's tools: what they are

*The reference. It replaces the review that opened this work and the three counterproposals that
followed it; the decisions in them are what this says, and the plan that implemented them is
[plan-commands.md](plan-commands.md).*

---

## 1. The twelve commands

| tool | arguments | what it does |
|---|---|---|
| `tree` | `path`, `depth`, `name`, `grep`, `from`, `to` | lists a folder; with a pattern, answers with paths and matching lines. `from`/`to` pick a range of **paths** |
| `read` | `path`, `revision`, `grep`, `from`, `to` | a text file, or one of its versions. With `grep`, the matching lines with their real line numbers, inside the range |
| `edit` | `path`, and one of `find`+`replace`, `from`/`to`+`replace`, `at`+`replace`, `text` | changes part of a file. `replace` is text **or a `read` call** |
| `create` | `path`, `text` | makes a file, and the folders above it |
| `delete` | `path` | a file, or a folder and everything in it |
| `move` | `path`, `into`, `name`, `copy` | moves, renames, or — with `copy: true` — copies |
| `history` | `path`, `grep`, `from`, `to` | the versions of a file: id, message, time, **and what the file said** |
| `restore` | `path`, `revision` | puts a version back; it becomes a new version |
| `background` | `tool`, `args` | any command, with its result written to a file instead of into the conversation |
| `reason` | `text` | the agent's own note in the record |
| `forget` | `ids` | takes rows out of the context; the whole of compaction |
| `ask`, `conclude`, `wait` | — | end the turn. Called alone |

There is no `clone`. Cloning a repository is the operator's, through the add-project window — the
agent never needed it, and naming a tool `clone` while it meant `git clone` is what made the whole
conversation confusing.

## 2. The rules that hold everywhere

**One limit, and it is where the result is going.** A result that enters the conversation is measured
against the operator's `result length`; over it the body is **written whole to a file** under
`agent/background_results/` and the answer is that path — never a fragment, never a refusal that would
have to be measured by the same limit. It is the same file, the same sentence and the same protocol as
`background`, so there is one rule to learn instead of two. Two results are deliberately not measured,
because they do not enter the conversation at all: one going to a file through `background`, and one
going into another file as `edit`'s replacement. The limit itself is the operator's number clamped by
the model's input window, because a result bigger than the window cannot be sent.

**A key a command does not take stops it, before it runs.** Every command declares the arguments it
takes; anything else is refused with the key named, the keys that work, and the command's own example.
The alternative is silent misbehaviour — `read {"lines":40}` used to mean "everything from line 1",
because `lines` was a retired name the parser had stopped looking at.

**One range language.** `from` and `to` are line numbers, both inclusive, 1-based; `to` absent means
the end. In `read`, `tree` and `history` they select from the result — lines, paths, versions. **A
pattern is always applied inside the range, never the other way round**, so a line number read in one
call means the same line in the next.

**No command has a limit of its own.** There is no 200-match cap and no silent twentieth version.
`MAX_SCAN` is gone: a phone has gigabytes, and the limit exists to protect the conversation, not the
memory.

**No foreground command outlasts its turn.** Every command runs under the operator's foreground
limit — three minutes unless the operator says otherwise — and either answers inside it or is stopped
with a sentence saying so. There is no list of "slow
commands" — a read of a huge log, a tree of a huge project and a history of a file saved a thousand
times are all slow on a phone. Each asks `Deadline.check` inside its own loop. Commands that write
once (`create`, `edit`, `move`) either finished or never started when the time passes; `delete` is the
one command that can stop half way, and it says so rather than claiming nothing happened.

**`background` takes any command.** Its answer — the path — comes back **before the work starts**, so
it never holds a turn open; the command's own limit is the operator's *background* number, applied to
the work. The three that end a turn and `background` itself are refused. A command that changes files
still changes them while the turn carries on, and the tool description says so.

**A name that is taken is refused, in a copy as much as in a move.** A silent `milk 2.txt` puts a file
where the person did not ask for it, under a name they will not recognise, and reports success. An
import is different: the person never chose that name, so `freeName` still numbers it.

**A batch is all or nothing.** Move and copy check every destination name first; one taken name means
nothing happens and the refusal names it. Locks are checked the same way for a move — a locked member
is a member that cannot go, and moving the others would leave the act half done.

## 3. Why `edit` is per line and has no `grep`

A range renames a block; a pattern matches scattered lines. `grep:"TODO"` on a file means N
disconnected lines, and one call could mean "replace each of them" or "replace one span that swallows
everything between two matches". The model cannot say which, so the tool does not offer it.

The scattered case is handled by **deriving** the lines rather than editing them: `read {"grep":…}`
prints real line numbers, then `edit {"from":N,"to":M}` uses them. Two calls, each with one meaning.
`find`/`replace` remains because replacing every occurrence of a string is exactly one meaning.

## 4. Why a paste is an argument, not a tool

`edit`'s `replace` may be `{"tool":"read","args":{…}}` — only `read`, nothing else, because another
tool there would turn one call into a small program with an order nobody wrote down. The selection is
always taken from the **source** and applied at the **destination**, which is the unambiguous
direction; `at` is the insertion point, because a position between lines is not a range and an empty
range that happens to mean "here" is a trap.

The nested read is not measured against the limit: its text goes into a file and never through the
conversation. A 200 KB paste is exactly the case a tool must not refuse for being large.

## 5. Two dispatch tables became one

There were three: `run`, `runWithin` and `background`, each with its own `when`, so every tool had to
be added up to three times. There is now one `run(call, deadline)`, and `background` calls it. That is
what makes "any command in the background" one line rather than a table that drifts.
