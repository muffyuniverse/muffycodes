# Implementation plan: the agent's commands

**Historical: what was true when this plan was written.** Every step in it has since been built, and
some of what it calls "the code as it stands" — `take(20)`, `MAX_SCAN`, the `LONG` set — has been
deleted. It is kept because it records why the command layer was rewritten; the description of the
code as it *is* is [agent-tools-review.md](agent-tools-review.md).

*For review. Derived from [review](agent-tools-review.md) → [counterproposal 1](agent-tools-counterproposal.md)
→ [2](agent-tools-counterproposal-2.md) → [3](agent-tools-counterproposal-3.md).*

**Rule applied throughout: where the code contradicts the design, the code is rewritten.** The
previous draft of this plan did the opposite once — it split commands into three buckets to justify
attaching a deadline only where the work already checked for one. That is deleted below; §2 is the
rewrite.

---

## 0. The target

| # | command | after | change |
|---|---|---|---|
| 1 | `tree` | `path`, `depth`, `name`, `grep`, `from`, `to` | 200-match cap → the char limit |
| 2 | `read` | `path`, `revision`, `grep`, `from`, `to` | +`revision`; **`lines` → `to`**; grep inside the range |
| 3 | `edit` | `path`, `find`/`replace`, `from`/`to`, `at`, `text` | +range, +`at`, +`replace` may be a `read` call |
| 4 | `create` | `path`, `text` | unchanged |
| 5 | `delete` | `path` | unchanged |
| 6 | `move` | `path`, `into`, `name`, **`copy`** | +`copy:true`; strict collisions |
| 7 | ~~`clone`~~ | — | **removed** |
| 8 | `history` | `path`, `grep`, `from`, `to` | content grep; no silent 20; a distinguishing line per row |
| 9 | `restore` | `path`, `revision` | unchanged |
| 10 | `background` | `tool`, `args` | **any** command, one dispatch |
| 11 | `reason` | `text` | unchanged |
| 12 | `forget` | `ids` | unchanged |
| — | `ask`, `conclude`, `wait` | — | unchanged |

14 → 12 commands. One limit, one range language, one dispatch table, no `MAX_SCAN`, no `MAX_MATCHES`,
no `LONG`.

---

## 1. First, what is already true — and what I got wrong about it

**Background commands never hang a turn.** `Background.start` (`Background.kt:43`) writes `"running"`
to the result file, hands the work to the launcher, and returns the path **before the work starts**.
That is not a design goal to reach; it is the code as it stands. The hang I described was a
*foreground* `read` of an unbounded file — and it is real for that reason alone.

**A background command already has its own limit, applied to the work it started.** The background
branch builds `Deadline(backgroundMs())` (`AgentTools.kt:267`), reads the setting per call, and
`backgroundMs() == 0` means no limit. Nothing to change; it survives the dispatch collapse in §3.

**There is no handover. It was burned.** `LongRunner.kt` is gone — deleted in `b0c4b15` ("A slow
command's result can land in a file, and a limit can stop it"). Nothing in the tree lets a foreground
command keep running past its turn and be asked for again. `AgentTest.kt:448` still asserts the
absence of that sentence, which is the test that caught it.

**`take(20)` is still in the tree**, and no commit ever removed it:

```text
AgentTools.kt:586    versions.take(20).joinToString("\n") { … }
$ git log -S "take(20)" -- AgentTools.kt   →  9104295 (where it was introduced)
```

So if "burned" meant the handover — burned, and §1 above says so. If it meant `take(20)`, it is
step 2 of §9 and it is the one silent shortening left in the file.

## 2. The deadline rule, rewritten — not adapted

**Every foreground command runs under the operator's foreground limit. It returns a result inside
that time, or it errors on timeout. No foreground command outlasts its turn.**

Two consequences, and the second is where the previous draft cheated:

1. **`LONG` is deleted.** `Agent.kt:531`'s `if (call.tool !in AgentTools.LONG || foregroundMs <= 0L)
   return runTool(call)` becomes `if (foregroundMs <= 0L) runTool(call) else
   tools.runWithin(call, Deadline(foregroundMs))`. `LONG` (`AgentTools.kt:81`), its two dispatch
   entries, and its use as `background`'s gate all go. `Timeouts.kt:15`'s "Two commands can outlast a
   turn and only two" is deleted — it stops being true.
2. **Every command that can take time checks the deadline itself.** Not "the answer is abandoned, and
   that is safe because this command writes nothing" — that was the adaptation. The loops get a
   check:

   | where | what is added |
   |---|---|
   | `read` | read the file in chunks, `if (deadline.expired) throw StoppedException(...)` between them |
   | `tree` / `search` (`walk`, `:366`) | check per entry |
   | `history` | check per version (it loads every version's bytes — §5) |
   | `edit` / `create` | check before the write; the write itself is `.new` + rename |
   | `delete` | check between entries of the recursive delete |
   | `restore` | already checks between files |
   | `move` / copy | check before the move; a tree copy checks per file |

   Then `Deadline`'s existing sentence is honest for all of them. For the atomic writers
   (`create`, `edit`, `move`, copy) "nothing was kept" is true by construction. For `delete` it is
   **not** — a recursive delete stopped halfway leaves half a folder — so a stopped delete says what
   it left instead of claiming nothing was kept. That is the one command whose sentence changes.

`Stopped.body(tool, deadline)` (`Timeouts.kt:120`) already produces the right words
(`1\n<tool> stopped after N s; nothing was kept`), and `StoppedException` is already caught first
everywhere. This is where the throw becomes reachable for commands that never threw it before.

**`WRITING` loses `clone`** (6 → 5): `AgentTools.kt:69`. It needs no `copy` entry — the flag rides on
`move`, which is already there, so `Agent.kt:492`'s "did this turn change anything" keeps working
untouched. That is a concrete benefit of the flag over a new tool name.

## 3. One dispatch table

The drift already happened — there are three:

- `run(call)` — `AgentTools.kt:200`, a full `when`.
- `runWithin(call, deadline)` — `:227`, its own `when` with two branches and `else -> run(call)`.
- `background(args)` — `:262`, a third `when` with two branches and `else` → refusal.

Every tool has to be added up to three times, and `clone` appears in all three. **Collapse to one
`when` with the deadline as a parameter** — `run(call, deadline: Deadline?)`. `background` then calls
that same function with `Deadline(backgroundMs())` instead of growing a second table, which is what
makes "any command in the background" one line rather than a table that drifts.

`background` still refuses `ask`/`conclude`/`wait` (they end a turn — there is no result to file) and
itself (a background of a background loses a result). Its description gains the hazard in plain words:
*a writing command in the background changes the project while the turn carries on.*

## 4. The one limit

Every result that enters the conversation is measured against `SettingsStore.maxResult`, already
plumbed through `Agent.kt:78`. Over it → **an error asking for a narrower selection**. Where:
`trim()` (`AgentTools.kt:638`), the only place any result is shortened.

```text
1
That result was 41,289 characters, and the limit is 20,000. Narrow it:
  add {"from":1,"to":200} for a line range, or {"grep":"pattern"} for matching lines.
```

`trim()`'s `body.take(maxChars)` goes, along with its "(showing first N of M…)" tail. A result is whole
or it is an error.

**Deleted:** `MAX_MATCHES = 200` (`:53`) with its two uses (`:302`, `:410`); `versions.take(20)`
(`:586`); `MAX_SCAN` (`:56`) with the check at `:335` and the "too big to search" sentence. **Step 1
of §9 lands the deadline first**, so deleting `MAX_SCAN` removes a limit without removing the only stop
in front of it.

**Two results are deliberately not measured** — the same rule from the other side, since the limit is
about what enters the conversation rather than what a command may touch:

1. A result going to a **file** (`background`). Reading that file back *is* measured.
2. A nested `read` in `edit`. Its text goes to the file; the `edit` result stays one sentence. (The
   literal `text` form is bounded for free — those characters had to come through the context first.)

## 5. The one selection language

**`from`/`to` are line numbers, both inclusive. `to` absent = to the end. `grep` applies inside the
range, never outside it. Range first, always.**

```json
{"from":10,"to":49}                  lines 10–49
{"from":10}                          10 to the end
{"to":20}                            1–20
{"grep":"TODO"}                      matching lines, whole file
{"from":10,"to":49,"grep":"TODO"}    matching lines, inside 10–49
```

In `read`, `history`, `tree`. For `tree` the "lines" are result lines, i.e. paths, and the tool
description must say so — a path list with a `from`/`to` the model is left to guess the unit of is a
trap.

**`edit` gets `from`/`to` and never `grep`.** A pattern on the destination means "N scattered lines",
which is two different edits in one call — replace each, or replace one span that swallows everything
between matches — and the model cannot say which. Scattered edits are two calls by design:
`read {"grep":…}` prints real line numbers (`AgentTools.kt:404–409`), `edit {"from":N,"to":M}` uses
them. That output format is load-bearing now, so it gets a test.

**`lines` disappears**: `read {"from":10,"lines":40}` means 10–49 today and becomes
`{"from":10,"to":49}`.

Work: one helper, `window(text, from, to, grep)`, used by all three. `read`'s grep block (`:400–413`)
and its slice block (`:414–425`) collapse into it.

## 6. `read` gains `revision`

`{"path":"milk.txt","revision":"a1b2c3d"}` — same filters, the text as it stood at that commit.
`ProjectGit.bytesAt` already wraps `GitService.bytesAt` (`restore` uses it), so this is plumbing.

```text
history {"path":"milk.txt","grep":"two litres"}   → the hashes whose text matched
read    {"path":"milk.txt","revision":"a1b2c3d"}  → what it said
restore {"path":"milk.txt","revision":"a1b2c3d"}  → put it back
```

## 7. `history`

- `grep` matches **the file's content at each version**; a non-matching version does not appear.
  `versionsOf` already loads every version's bytes, so it costs no extra read.
- `from`/`to` select versions (newest first), then grep inside that window.
- No default 20 — whatever fits under the limit, else the too-big error.
- **Each row carries one distinguishing fact** — the first non-blank line, trimmed, or the line count
  when the version is empty. Since Save commits `Saved`, twenty versions of one file are otherwise
  twenty identical rows (`a1b2c3d  Saved  14:02:11`), and content grep alone only lets the model
  *find* a version, not tell candidates apart.

## 8. `edit`, `move`, and the batch rule

### `edit`

```json
{"path":"x","find":"old","replace":"new"}                             every occurrence (today)
{"path":"x","from":10,"to":12,"replace":"new"}                        replace a block
{"path":"x","from":10,"to":12,"replace":{"tool":"read","args":{…}}}   paste a selection over a block
{"path":"x","at":11,"replace":{"tool":"read","args":{…}}}             insert between lines
{"path":"x","text":"whole file"}                                      overwrite (today)
```

- **`at`** inserts after line `at`; `at:0` prepends, last+1 appends. Not expressible as a range — an
  empty range meaning "here" is a trap.
- **`replace` is a string or a call**, and only `read` is accepted there; anything else is refused with
  a sentence saying so, because another tool turns one call into a small program.
- The nested `read` runs unbounded (§4) and may itself use `from`/`to`/`grep`.
- Direction is unambiguous: the selection is in the **source**, the range in the **destination**.

### `move` absorbs `copy`

```json
{"path":"milk.txt","into":"archive"}                    move
{"path":"milk.txt","into":"archive","copy":true}        copy, original stays
{"path":"milk.txt","into":"archive","name":"x.txt"}     move and rename
```

An occupied destination is **refused in both modes**, with `move`'s existing sentence
(`Workspace.kt:440`, `There is already a "milk.txt" there`). A locked *source* is refused for `move`
and allowed for `copy` — a copy only reads the source.

### Batches: all or nothing — copy and move alike

`moveSelected` (`AppViewModel.kt:1162`) and `copySelected` (`:1215`) are per-item today: they move or
copy what they can and report the first refusal. Both change to **all-or-nothing**:

1. **Check every destination first**, before touching anything: for each selected file, whether
   `into/<name>` already exists.
2. **Any collision → nothing happens**, and the message names the taken ones:
   `Nothing was moved — there is already "milk.txt" and "notes.txt" there`. For one, the singular
   sentence `move` already uses.
3. Only when every name is free does the work start.

The check-first shape also makes the commit rule simpler: a batch either has something to commit or it
did nothing. `copySelected`'s `renamed` counter (`:1240`) and its `as ${made.name}` branch in
`AppViewModel.copy` (`:975`) both die, and `Workspace.copy` stops calling `freeName` — its doc block
(`Workspace.kt:476–487`) argues *for* the behaviour being removed, so it is rewritten to say why, not
deleted quietly.

`freeName` survives for imports only (`Imports.kt:91`, `:133`, `:169`), and its doc claim
(`Workspace.kt:805`) that "a copy and an import asking the same question have to get the same answer"
stops being true and is reworded: an import's target name was not chosen by the person, so a free name
there is a service, not a surprise.

## 9. Order of work

Each step is one commit CI can measure.

1. **One dispatch and the deadline everywhere** (§2, §3). Behaviour-preserving where a deadline
   already existed; it removes the hang risk *before* anything becomes unbounded. Includes the
   per-loop `deadline.expired` checks and the delete sentence.
2. **The limit** (§4) — `trim()` errors; delete `MAX_MATCHES`, `take(20)`, `MAX_SCAN`. Safe now
   because of step 1.
3. **`window()`** (§5) — `from`/`to`/grep in `read`, then `history`, then `tree`; delete `lines`; test
   the grep line numbers.
4. **`read` + `revision`** (§6).
5. **`edit`** (§8) — range, `at`, nested `read` replacement.
6. **`move` + `copy`, strict in both modes, batches all-or-nothing** (§8).
7. **`history` rows distinguishable** (§7).
8. **`clone` removed** — the tool only; `GitService.clone` and `ProjectGit.cloneInto` are the
   operator's add-project and pull-in paths and stay.
9. **Prompt + docs.** Three edits to `SYSTEM_DEFAULT` (`AgentPrompt.kt:40`) and the same in every
   `PREVIOUS_SYSTEM` where the sentence is unchanged: the "never silently shortened" line becomes
   *"a result bigger than the operator's limit is an error that asks you to narrow the selection"*;
   `clone` leaves the tool list; `move` is described as taking `copy:true`; and *"a file too big for a
   result can be read with `background`, which writes it to a file"*. Then `tools/test-map.txt`.

`clone`'s nine references, so step 8 does not miss one: `AgentTools.kt:69` (`WRITING`), `:81` (`LONG`,
deleted in step 1), `:139`/`:159` (spec + example), `:210`/`:232`/`:267` (three dispatch entries),
`:565` (body); `Agent.kt:525`, `AgentRunner.kt:40`, `Timeouts.kt:10`/`:40`/`:85` (doc comments).
`Dialogs.kt:569` quotes a live-log report containing the word — left as written, it is a record of
what was reported.

## 10. Tests

| file | change |
|---|---|
| `AgentTest` (63) | `lines` → `to`; the 200-cap tests become limit tests; new: `at`, paste from a `read` call, range replace, `copy` flag, strict collision, `read` at a revision, `history` rows distinguishable |
| `BackgroundTest` (5) | its example command is `clone` → `create`; a backgrounded `read` of a large file is written whole; the call returns the path before the work starts |
| `TimeoutsTest` (5) | `clone` → a command that still exists; every foreground command gets a deadline; a stopped `delete` says what it left |
| `AgentSessionFeatureTest` (20) | any assumption that a slow command is `clone` |
| `FilesFeatureTest`, `CopyFeatureTest` | strict copy refuses rather than renames; batches are all-or-nothing and name the taken ones |
| `HistoryFeatureTest` | content grep, `from`/`to`, the too-big error |
| `WorkspaceTest` | `copy` refuses an occupied destination; a locked source is still copyable |

Named tests worth writing:

- `a result over the limit is an error, and the error names the selection to add`
- `grep is applied inside the range, not the other way round`
- `every foreground command stops on the operator's deadline, not only the slow ones`
- `a backgrounded read of any size is written in full, and reading it back is measured`
- `an edit whose replacement is a read call keeps the paste out of the conversation`
- `at inserts between lines and never replaces one`
- `a copy onto an occupied name is refused rather than renamed`
- `a batch with one taken name moves nothing`
- `clone is not a tool any more`

## 11. Nothing left open

Every question from the four discussion documents is answered above. The two things I would otherwise
have raised, and their rulings:

- **`delete` cannot say "nothing was kept"** if it is stopped mid-recursion — its sentence changes
  (§2). Not adapted: the command keeps its deadline like every other, and tells the truth.
- **After `clone` goes, nothing in the ordinary path is slow except `restore`.** `background` is then
  for large reads and greps on a weak phone — which is exactly the case that mattered.
