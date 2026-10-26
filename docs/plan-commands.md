# Implementation plan: the agent's commands

*For review. Derived from [review](agent-tools-review.md) → [counterproposal 1](agent-tools-counterproposal.md)
→ [2](agent-tools-counterproposal-2.md) → [3](agent-tools-counterproposal-3.md). Everything below is
settled. §1 lists what re-reading those four documents added to the first draft of this plan — one
item of it is a hole that would hang a turn.*

---

## 0. The target

| # | command | after | change |
|---|---|---|---|
| 1 | `tree` | `path`, `depth`, `name`, `grep`, `from`, `to` | the 200-match cap becomes the char limit |
| 2 | `read` | `path`, `revision`, `grep`, `from`, `to` | +`revision`; **`lines` → `to`**; grep inside the range |
| 3 | `edit` | `path`, `find`/`replace`, `from`/`to`, `at`, `text` | +range, +`at`, +`replace` may be a `read` call |
| 4 | `create` | `path`, `text` | unchanged |
| 5 | `delete` | `path` | unchanged |
| 6 | `move` | `path`, `into`, `name`, **`copy`** | +`copy:true`; **strict in both modes** |
| 7 | ~~`clone`~~ | — | **removed** |
| 8 | `history` | `path`, `grep`, `from`, `to` | content grep; no silent 20; **a distinguishing line per row** |
| 9 | `restore` | `path`, `revision` | unchanged |
| 10 | `background` | `tool`, `args` | **any** command, one dispatch |
| 11 | `reason` | `text` | unchanged |
| 12 | `forget` | `ids` | unchanged |
| — | `ask`, `conclude`, `wait` | — | unchanged |

14 → 12 commands. One limit, one range language, one dispatch table, no `MAX_SCAN`, no `LONG`.

---

## 1. What the re-read found that the first draft of this plan missed

### 1.1 The turn can hang — `LONG` gates the deadline, and `read` is not in it

`Agent.kt:531`:

```kotlin
if (call.tool !in AgentTools.LONG || foregroundMs <= 0L) return runTool(call)
```

**Only `LONG` commands get a deadline.** `LONG` is `setOf("clone", "restore")`. So the moment
`MAX_SCAN` is deleted, `read {"path":"huge.log"}` in the foreground loads hundreds of megabytes with
**no deadline at all** and the turn never returns — and `wait` cannot help, because the loop is
inside the call.

This is the one item that changes the shape of the work, and it is why `MAX_SCAN` cannot be deleted
on its own.

**Fix: delete `LONG` and attach the deadline to every command.** `runWithin` loses its
`if (call.tool !in LONG) return run(call)` line, and `background` loses its speed refusal.

**The safety argument has to be split in two**, because `Timeouts.kt:10–15` and `Agent.kt:526–531`
both argue that a deadline may only be attached to code that *checks* it:

| command | writes? | a stop means |
|---|---|---|
| `restore`, (`clone`) | yes, incrementally | checked inside — real stop, nothing half-kept |
| `read`, `tree`, `history` | **no** | the answer is abandoned; the work carries on harmlessly |
| `edit`, `create`, `delete`, `move`/copy, `forget` | yes, **atomically** | it either finished or never started |

So the rule becomes: *a deadline is honoured inside the work where the work can be interrupted; where
it cannot, a stop abandons the answer, which is safe exactly when the command writes nothing or writes
all at once.* Both doc comments (`Timeouts.kt:10`, `Agent.kt:526`) are rewritten to say that, and
`Timeouts.kt:15`'s "Two commands can outlast a turn and only two" is deleted — it stops being true.

### 1.2 There are three dispatch tables, not one

The first draft said "share the foreground dispatch, not a second table". Rereading: the drift already
happened.

- `run(call)` — `AgentTools.kt:200`, a full `when`.
- `runWithin(call, deadline)` — `:227`, its own `when` with two branches and `else -> run(call)`.
- `background(args)` — `:262`, a third `when` with two branches and `else` → refusal.

Each tool must therefore be added in up to three places, and `clone` appears in all three. **Collapse
to one `when`, with the deadline as a parameter** — `run(call, deadline: Deadline?)` — and `background`
calls it. That is what makes "any command in the background" one line instead of a table.

### 1.3 `history`'s rows are indistinguishable, and that is a separate defect from `take(20)`

From the review, §1: since Save commits `Saved`, twenty versions of one file now read as

```text
a1b2c3d  Saved  14:02:11
9f8e7d6  Saved  13:41:02
```

Content grep (§5) lets the model *find* a version by text, but the list itself still cannot be told
apart, so every candidate needs a `read` to identify. The fix is nearly free: **`versionsOf` already
returns each version's contents** (`GitService.kt:657`, `List<Pair<CommitInfo, String?>>`), which is
what content grep needs anyway. Each row gains one distinguishing fact — the first non-blank line,
trimmed to a width, or the line count when the version is empty. No extra read, no new call.

### 1.4 `clone` has nine references, and the capability behind it must survive

Removing the *tool* is not removing the *feature*: `GitService.clone` (`:172`) and
`ProjectGit.cloneInto` (`:232`) are the operator's add-project and pull-in paths
(`AppViewModel.kt:1691`, `:1852`). They stay untouched.

The tool's own references:

| where | what |
|---|---|
| `AgentTools.kt:69` | `WRITING` set — `clone` out, 6 → 5 |
| `AgentTools.kt:81` | `LONG` — **deleted entirely** (§1.1) |
| `AgentTools.kt:139`, `:159` | spec entry and its example |
| `AgentTools.kt:210`, `:232`, `:267` | three dispatch entries |
| `AgentTools.kt:565` | the body |
| `Agent.kt:525`, `AgentRunner.kt:40` | doc comments naming it |
| `Timeouts.kt:10`, `:40`, `:85` | doc comments naming it |
| `Dialogs.kt:569` | a quoted live-log report — **left alone**, it is history |

`WRITING` needs no entry for `copy`: the flag rides on `move`, which is already in the set. That is a
real benefit of the flag over a new tool name — `Agent.kt:492` keeps working with no change.

### 1.5 Strict collisions, for the person too (your comment)

`Workspace.copy` stops calling `freeName` and refuses like `move` does, reusing `move`'s sentence
(`Workspace.kt:440`, `There is already a "milk.txt" there`) so the app has one refusal, not two. The
consequences the first draft did not price:

- **The doc block at `Workspace.kt:476–487` argues for the behaviour being removed** ("a copy has no
  such promise to keep… `milk.txt` becoming `milk 2.txt` is what a person does by hand"). It is
  rewritten, not deleted — the reasoning is wrong now and should say why.
- **`AppViewModel.copy` (`:975`) loses its `as ${made.name}` branch** — dead the moment names never
  change.
- **`AppViewModel.copySelected` (`:1226`) loses its `renamed` counter**, and a batch needs a rule.
  Recommendation: **per item** — copy what can be copied and name what was refused, using the
  `refused` list the method already builds. Refusing a whole selection because one name is taken would
  punish the person for something they cannot see.
- **`freeName` survives for imports only** (`Imports.kt:91`, `:133`, `:169`), and its doc comment
  (`Workspace.kt:805`, "a copy and an import asking the same question have to get the same answer")
  stops being true and must be reworded. An import is different on purpose: the person did not choose
  the target name, so there is no collision to report against a name they typed.

### 1.6 `tree`'s `from`/`to` need their unit stated

The range language (§3) is line numbers. For `tree` the "lines" are **result lines, i.e. paths**, and
`depth`/`name`/`grep` narrow better. Keeping `from`/`to` is still right — a 4,000-path listing must be
narrowable once the 200-cap is gone — but the tool description has to say "the first N paths", not
leave the model to guess that a path list has lines.

### 1.7 `read`'s grep line numbers are now part of the contract

`read`'s grep prints `N: line` with real line numbers (`AgentTools.kt:404–409`). §3 depends on them:
the scattered-edit recipe is *grep with `read`, then edit by `from`/`to`*. The first draft treated the
format as incidental. It is load-bearing now and gets a test.

### 1.8 Two constants mean one number

`AgentTools.MAX_RESULT = 20_000` (`:50`) is the default of the `maxChars` constructor parameter, and
`SettingsStore.DEFAULT_MAX_RESULT = 20_000` (`:351`) is the setting's default. `Agent.kt:78` already
passes the setting through, so the behaviour is right — but the number is written twice. Keep one: the
`AgentTools` parameter becomes required, or its default reads `SettingsStore.DEFAULT_MAX_RESULT`.

---

## 2. The one limit

**Rule.** Every result that enters the conversation is measured against the operator's `result length`
(`SettingsStore.maxResult`, already plumbed via `Agent.kt:78`). Over it → **an error asking for a
narrower selection.**

Where: `trim()` (`:638`), the only place any result is shortened. Its doc comment ("Never silently
shortens") stays true and gains the second half.

```text
1
That result was 41,289 characters, and the limit is 20,000. Narrow it:
  add {"from":1,"to":200} for a line range, or {"grep":"pattern"} for matching lines.
```

`trim()`'s current body does `body.take(maxChars)` and appends "(showing first N of M…)". The take
goes; the message replaces it — the result is either whole or an error, never a fragment.

**Deleted:** `MAX_MATCHES = 200` (`:53`) and its two uses (`:302`, `:410`); `versions.take(20)`
(`:586`); `MAX_SCAN` (`:56`) with the check at `:335` and the "too big to search" sentence — **after
§1.1**, never before.

**Two things are deliberately not measured**, and they are the same rule from the other side — the
limit is about what enters the conversation, not about what a command may touch:

1. **A result going to a file.** `background` writes the full body at any size; reading that file back
   *is* measured.
2. **A nested `read` in `edit`.** Its text goes to the file; the `edit` result stays one sentence. (The
   literal `text`/`replace` form is bounded for free — the model had to put those characters through
   the context to call it.)

## 3. The one selection language

**`from` and `to` are line numbers, both inclusive. `to` absent = to the end. `grep` applies inside the
range, never outside it. Order is always range first.**

```json
{"from":10,"to":49}                  lines 10–49
{"from":10}                          10 to the end
{"to":20}                            1–20
{"grep":"TODO"}                      matching lines, whole file
{"from":10,"to":49,"grep":"TODO"}    matching lines, inside 10–49
```

In `read`, `history`, `tree`. **`edit` gets `from`/`to` and never `grep`** — a pattern on the
destination is "N scattered lines", which is two different edits in one call (delete each, or delete
one span swallowing everything between matches), and the model has no way to say which. Scattered
edits are two calls: `read {"grep":…}` prints the numbers (§1.7), `edit {"from":N,"to":M}` uses them.

**`lines` disappears.** `read {"from":10,"lines":40}` today means 10–49, and becomes
`{"from":10,"to":49}`.

Work: one helper — `window(text, from, to, grep)` — used by all three. `read`'s current grep block
(`:400–413`) and slice block (`:414–425`) collapse into it.

## 4. `read` gains `revision`

`{"path":"milk.txt","revision":"a1b2c3d"}` — same filters, the text as it stood at that commit.
`ProjectGit.bytesAt` already wraps `GitService.bytesAt` (`restore` uses it), so this is plumbing, not
new logic. Non-text is refused exactly as `read` refuses it today.

```text
history {"path":"milk.txt","grep":"two litres"}   → the hashes whose text matched
read    {"path":"milk.txt","revision":"a1b2c3d"}  → what it said
restore {"path":"milk.txt","revision":"a1b2c3d"}  → put it back
```

## 5. `history`

- `grep` matches **the file's content at each version**; a version that does not match does not appear.
  Free: `versionsOf` already loads every version's bytes.
- `from`/`to` select versions (newest first), then grep inside that window.
- No default 20. Whatever fits under the limit; over it, the too-big error.
- **Each row carries one distinguishing fact** (§1.3), because `Saved` on every row is unreadable on
  its own.

## 6. `edit`: range, insertion, a `read` call as the replacement

```json
{"path":"x","find":"old","replace":"new"}                             every occurrence (today)
{"path":"x","from":10,"to":12,"replace":"new"}                        replace a block
{"path":"x","from":10,"to":12,"replace":{"tool":"read","args":{…}}}   paste a selection over a block
{"path":"x","at":11,"replace":{"tool":"read","args":{…}}}             insert between lines
{"path":"x","text":"whole file"}                                      overwrite (today)
```

- **`at`** is the insertion point: after line `at`; `at:0` prepends, last+1 appends. Not expressible
  as a range — an empty range that happens to mean "here" is a trap a model will fall into.
- **`replace` is a string or a call.** Only `read` is accepted; anything else is refused with a
  sentence saying so, because another tool there turns one call into a small program.
- **The nested `read` runs unbounded** (§2, second rule) and may itself use `from`/`to`/`grep`.
- Direction is unambiguous: the selection is always in the **source**, the range in the **destination**.

## 7. `move` absorbs `copy`; `clone` goes; `background` takes anything

```json
{"path":"milk.txt","into":"archive"}                    move
{"path":"milk.txt","into":"archive","copy":true}        copy, original stays
{"path":"milk.txt","into":"archive","name":"x.txt"}     move and rename
```

- **Strict in both modes** (your ruling, applying to the person too): an occupied destination is
  refused. `Workspace.copy` stops finding a free name; §1.5 lists what falls out.
- A locked **source** is refused for `move` and allowed for `copy` — a copy only reads the source. That
  difference stays, and it is the flag's other job.
- **`clone` deleted** (§1.4); the git capability behind it stays.

**`background` takes any command**, except `ask`/`conclude`/`wait` (they end a turn — no result to
file) and itself (a background of a background loses a result). It calls the one `run(call, deadline)`
rather than its own table (§1.2), and its description gains the hazard in plain words: *a writing
command in the background changes the project while the turn carries on.*

## 8. The deadline, everywhere

`run(call, deadline: Deadline?)`; `Agent.kt:531` becomes `if (foregroundMs <= 0L) runTool(call) else
tools.runWithin(call, Deadline(foregroundMs))`. `LONG` is deleted; `WRITING` loses `clone`. The two
doc comments that argue "only these two can outlast a turn" are replaced by the honest split in §1.1.

## 9. The shipped prompt

Three edits to `SYSTEM_DEFAULT` (`AgentPrompt.kt:40`), and the same in every `PREVIOUS_SYSTEM` text
where the sentence is unchanged (a phone holding an unedited default gets the new one; an edited file
is never touched).

1. `"A result is never silently shortened. If it says 'showing first N of M'…"` →
   **`"A result bigger than the operator's limit is an error that asks you to narrow the selection."`**
2. **`clone` leaves the tool list**; `move` is described as taking `copy:true`.
3. Add: **`"A file too big for a result can still be read with background, which writes it to a file
   instead of here."`**

## 10. Tests

| file | change |
|---|---|
| `AgentTest` (63) | `lines` → `to` everywhere; the 200-cap tests become limit tests; new: `at` insertion, paste from a `read` call, range replace, `copy` flag, strict collision, `read` at a revision, `history` rows distinguishable |
| `BackgroundTest` (5) | its example command is `clone` → `create`; a backgrounded `read` of a large file is written whole |
| `TimeoutsTest` (5) | `clone` → a command that still exists; a foreground `read` gets a deadline |
| `AgentSessionFeatureTest` (20) | any assumption that a slow command is `clone` |
| `FilesFeatureTest`, `CopyFeatureTest` | strict copy: refusal, not `milk 2.txt`; batch partial success |
| `HistoryFeatureTest` | content grep, `from`/`to`, the too-big error |
| `WorkspaceTest` | `copy` refuses an occupied destination; a locked source is still copyable |

Named tests worth writing:

- `a result over the limit is an error, and the error names the selection to add`
- `grep is applied inside the range, not the other way round`
- `a backgrounded read of any size is written in full, and reading it back is measured`
- `an edit whose replacement is a read call keeps the paste out of the conversation`
- `at inserts between lines and never replaces one`
- `a copy onto an occupied name is refused rather than renamed`
- `a foreground read of a huge file stops on the operator's deadline`
- `clone is not a tool any more`

## 11. Order of work

Each step is one commit CI can measure.

1. **One dispatch, deadline everywhere** (§1.2, §8) — behaviour-preserving for the tools that already
   have a deadline, and it removes the hang risk *before* anything is unbounded.
2. **The limit** — `trim()` errors; delete `MAX_MATCHES`, `take(20)`, `MAX_SCAN` (§2). Safe now
   because of step 1.
3. **`window()`** — `from`/`to`/grep in `read`, then `history`, then `tree`; delete `lines`; keep and
   test the grep line numbers.
4. **`read` + `revision`.**
5. **`edit`** — range, `at`, nested `read` replacement.
6. **`move` + `copy`, strict in both modes** — `Workspace.copy`, `AppViewModel.copy`,
   `copySelected`, `freeName`'s doc (§1.5).
7. **`history` rows distinguishable** (§1.3).
8. **`clone` removed** (§1.4); `WRITING` updated.
9. **Prompt + docs** — the review and the three counterproposals fold into `docs/agent-tools-review.md`
   as the reference; `tools/test-map.txt` updated with each source change.

## 12. Open, and only one

**Batch copy on the person's screen**: per item with a refused-name report (my recommendation, §1.5),
or all-or-nothing? Everything else in this document is decided.

Two things I flagged rather than assumed:
- `Dialogs.kt:569` quotes a live-log report containing the old word "clone". Left as written — it is a
  record of what was reported, not a reference to a tool.
- After `clone` goes, nothing in the ordinary path is slow except `restore`. `background` then looks
  unnecessary — until a large `read` or `grep` on a weak phone, which is exactly your case for it.
