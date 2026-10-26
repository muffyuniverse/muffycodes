# Counterproposal: the agent's tools

*Answers to the three comments on [agent-tools-review.md](agent-tools-review.md). Where the earlier
draft was wrong it says so instead of defending itself.*

---

## 0. Where I was wrong

**`clone` is not `git clone`.** I described the tool at `AgentTools.kt:565` faithfully — that *is*
what the code does today — but I then designed on top of the mistake instead of naming it as one.
You are right about what the name should mean and about who clones a repository: the operator,
who already has the add-project window and `ProjectGit.cloneInto`. An agent that can pull an
arbitrary remote into a working project is a capability nobody asked for and one I would not give
a language model.

**And the whole of section D was answering the wrong question.** You asked whether `grep`/`lines`
can serve prepend/append/insert/replace — a question about **copy** — and I answered with selectors
on `read` and a separate `insert` tool. The tools divide like this:

| | about | today |
|---|---|---|
| `copy` | copying a file/folder | **does not exist** |
| paste | putting a *selection* of one file into another | **does not exist** |
| `clone` | (was) fetching a repo | **should not exist** |

So the rename-and-add is one move, not two: `clone` disappears, `copy` takes the name's place with
the meaning the name always had, and paste is a new thing beside it.
@ maybe copy should be just a flag on move command? they do practically the same.

---

## 1. A result too big is an error, and that is all

Your rule, restated so there is nothing to argue about:

- Every command's result is measured against **one limit** — `result length`, the setting that
  already exists. There is no per-tool limit, no default-20, no cap of 200, no second number.
- Over the limit: **the result is an error** whose body says the result was too big and asks for a
  narrower selection. It carries the numbers (how big, what the limit is) because a model told only
  "too big" guesses at how much to narrow.
- Inside the limit: the result is the result.

### Why this is better than what I proposed

My version wrote the oversized body to `agent/background_results/` and returned the path. Yours is
strictly simpler:

| | file it | error it (yours) |
|---|---|---|
| new files appearing under `agent/` | yes, one per oversized call | none |
| reader could mistake a filed result for a result | yes | no |
| what the model must learn | a new place results live | one rule it already has: narrow the selection |
| second rule needed (don't re-file a filed result) | yes | **none** |
| grows without bound if a model retries | yes | no |

The recursion I was defending against does not exist if nothing is ever filed. And the sentence the
model gets is the one it can act on immediately, because the fix — `grep`, `from`, `lines` — is the
same argument set it already used.

**This removes `Background`'s reason to write results at all.** `background_results/` was carrying
two jobs: a slow command's result, and an oversized result. The first is still real. The second is
gone, and the folder keeps one meaning instead of two.

**Same rule everywhere, including `tree`'s 200-cap and `read`'s 200-match cap.** Both become the
char limit. A `tree` that matches 4,000 paths is an error asking for a narrower `name`/`grep`/
`depth`; it is not a list of the first 200. That deletes `MAX_MATCHES` and its two notes.
@ make it so background can take any other command - it is simple.

## 2. One limit, applied to whatever the selection returns

Your rule, which settles the composition question I asked:

> **Everywhere — if both `lines` and `grep` are used, `grep` is applied to the selected lines.**

So the order is always: **`lines`/`from` first, then `grep` inside that window**, then the char
limit on the result. One order in `read`, in `history`, in `tree`. Not "grep first, then window" as
I proposed — that was a different answer to a question you had already answered.

This is also the order that makes the error actionable: the model narrows with `lines`, then with
`grep`, and the result shrinks monotonically as it does.

## 3. `history`

- **`grep` matches the file's content at each version.** A version whose content does not match does
  not appear at all. `versionsOf` already loads those bytes (`GitService.kt:657–680`), so this costs
  nothing extra.
- **grep applies after the lines filter**, per the rule above.
- **No `lines` default of 20.** The number of versions shown is whatever fits under the char limit;
  past it, the result is the too-big error. `take(20)` and its silent drop go away entirely.

One consequence worth stating: a file with 300 versions will need `lines` to answer at all, which is
correct — it is the same "narrow the selection" the model already knows from `read`.

## 4. `read` at a version

Unchanged and uncontroversial: `revision` is an argument to `read`, `history` finds the hash,
`restore` puts it back. With content-grep in `history` this becomes a complete loop:

```text
history {"path":"milk.txt","grep":"two litres"}   → the hashes whose text matched
read    {"path":"milk.txt","revision":"a1b2c3d"}  → what it said
restore {"path":"milk.txt","revision":"a1b2c3d"}  → put it back
```

## 5. `copy` and paste — the part I got wrong

**`copy`** copies a file or a folder, and is the tool the name should always have meant:

```text
copy {"path":"milk.txt","into":"archive"}          a copy in another folder
copy {"path":"milk.txt","into":"archive","name":"dairy.txt"}
copy {"path":"letters","into":"archive"}           a whole folder
```

It wraps `Workspace.copy`, which already exists for the person's screen: it finds a free name
(`milk 2.txt`), refuses copying a folder into itself, and is not stopped by a lock on the source.

**Paste** is the answer to your real question — *can `grep`/`lines` serve prepend, append, insert
and replace?* — and the answer is yes, if they select **on the source** and the mode says what to do
at the destination:

```json
copy {
  "path": "notes.txt",              source file
  "lines": 40, "grep": "TODO",      the selection, in the one order we settled on
  "into": "todo.txt",               destination file
  "mode": "append"                  append | prepend | insert | replace
  "at_line": 12                     insert only
}
```

- `append` / `prepend` — at the end or the start of the destination.
- `insert` — after line `at_line`.
- `replace` — replaces the destination's selection, which uses the **same** `lines`/`grep`
  arguments applied to the destination. One argument set, two files.

**This kills the separate `insert` tool I proposed**, and it kills the "fourth shape on `edit`"
question — both were answers to a question about `copy`. Insertion is not an operation on a file; it
is a mode of paste.
@ make this as paste command. lines and grep apply separately and sequentially (can be either either or both), lines take a range, from and to (both inclusive) - and standardize that.

### The one open question I still have

`edit` already does find-and-replace inside **one** file. Once `copy` has `mode: replace`, the two
overlap: `edit {"path","find","replace"}` is `copy` with both files the same and the selection given
by a pattern. Do you want:

- **`edit` narrowed to whole-file writes** (`text` only), with every in-place change going through
  `copy`'s selection? Then one tool owns "change part of a file", and `edit` shrinks.
- **or `edit` kept as it is**, with `copy` only ever crossing between two files? Then there are two
  ways to replace text in a file, which is the duplication I would normally argue against — but the
  existing one is the one models are best at using, and it is already written.

I lean to keeping `edit` and restricting `copy`'s `replace` to mean *between two different files* —
because that is the case `edit` cannot do, and the one you actually asked about. But it is your call,
and it is the only decision left in this document.
@ we can actually merge paste into edit by allowing edit to take read command as replace argument so that replace can take both pure content and read output.

---

## 6. Resulting tool set

| tool | change |
|---|---|
| `tree` | the 200-cap becomes the char limit (too-big error) |
| `read` | gains `revision`; `grep` applies inside the `lines` window |
| `edit` | unchanged, pending the one question above |
| `create`, `delete`, `move` | unchanged |
| **`copy`** | **new** — file/folder copy, plus paste with `mode` |
| ~~`clone`~~ | **removed** — cloning a repository is the operator's |
| `history` | content-grep, no silent 20; too-big error |
| `restore` | unchanged |
| `background` | unchanged for **slow** commands only; no longer a home for oversized results |
| `reason`, `forget`, `ask`, `conclude`, `wait` | unchanged |

Following the removal of `clone`, `LONG` is `restore` alone. That is honest — restoring a large
folder from a version is the only command that can take a while — and it means `BackgroundTest`'s
fixtures, which all use `clone` as their example command, need renaming to `restore`.
