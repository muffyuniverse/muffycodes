# Implementation plan: the agent's commands

*For review. Derived from [review](agent-tools-review.md) → [counterproposal 1](agent-tools-counterproposal.md)
→ [2](agent-tools-counterproposal-2.md) → [3](agent-tools-counterproposal-3.md). Everything below is
settled unless it appears in §7.*

---

## 0. The target, in one table

| # | command | after | change |
|---|---|---|---|
| 1 | `tree` | `path`, `depth`, `name`, `grep`, `from`, `to` | the 200-match cap becomes the char limit |
| 2 | `read` | `path`, `revision`, `grep`, `from`, `to` | +`revision`; **`lines` → `to`**; grep inside the range |
| 3 | `edit` | `path`, `find`/`replace`, `from`/`to`, `at`, `text` | +range, +`at`, +`replace` may be a `read` call |
| 4 | `create` | `path`, `text` | unchanged |
| 5 | `delete` | `path` | unchanged |
| 6 | `move` | `path`, `into`, `name`, **`copy`** | +`copy:true`; strict collisions |
| 7 | ~~`clone`~~ | — | **removed** |
| 8 | `history` | `path`, `grep`, `from`, `to` | content grep; no silent 20 |
| 9 | `restore` | `path`, `revision` | unchanged |
| 10 | `background` | `tool`, `args` | **any** command, one shared dispatch |
| 11 | `reason` | `text` | unchanged |
| 12 | `forget` | `ids` | unchanged |
| — | `ask`, `conclude`, `wait` | — | unchanged |

14 commands → 12. One limit, one range language, no second dispatch table, no `MAX_SCAN`.

---

## 1. The one limit

**Rule.** Every result that enters the conversation is measured against `result length`
(`SettingsStore.maxResult`, already exists). Over it → **an error asking for a narrower selection**.

**Where.** `AgentTools.trim()` (`:638`) — the only place any result is shortened, one call site pair
(`success`, `refusal`). Nothing else changes shape.

```text
1
That result was 41,289 characters, and the limit is 20,000. Narrow it:
  add {"from":1,"to":200} for a line range, or {"grep":"pattern"} for matching lines.
```

**Deleted:**
- `MAX_MATCHES = 200` (`:53`) and its two notes (`:302`, `:410`).
- `versions.take(20)` (`:586`).
- `MAX_SCAN = 2 MB` (`:56`) and the check at `:335` — **whole constant out**, per your comment. A
  phone has gigabytes; the limit exists for the conversation, not for memory.

**Two rules that follow, and must be tested:**

1. **A result going to a file is not measured.** `background` writes the full body; the limit is
   about what enters the context. So `background {"tool":"read","args":{"path":"huge.log"}}` works
   at any size, and reading that file back *is* measured.
2. **A nested `read` in `edit` is not measured.** Its text goes to the file, not to the conversation.
   The `edit` result stays one sentence. (The literal `text`/`replace` form is bounded for free,
   because the model had to put those characters through the context to call it.)

---

## 2. One selection language

**`from` and `to` are line numbers, both inclusive. `to` absent means "to the end". Order is always
range first, then `grep` inside it.** Either may be used alone; both together; neither = everything.

```json
{"from":10,"to":49}                  lines 10–49
{"from":10}                          10 to the end
{"to":20}                            1–20
{"grep":"TODO"}                      matching lines, whole file
{"from":10,"to":49,"grep":"TODO"}    matching lines, inside 10–49
```

Applied in `read`, `history` and `tree`. **`edit` gets `from`/`to` but never `grep`** — a pattern in
the destination is "N scattered lines", which is two possible edits in one call (delete each, or
delete one span that swallows everything between matches). Scattered edits are two calls by design:
`read {"grep":…}` prints the line numbers, `edit {"from":N,"to":M}` uses them.

**`lines` disappears.** `read {"from":10,"lines":40}` today means 10–49 and becomes `{"from":10,"to":49}`.

Work: one helper, `window(text, from, to, grep)`, used by all three. `read`'s current grep-with-
indices block (`:400–413`) and its slice block (`:414–425`) collapse into it.

---

## 3. `read` gains `revision`

`{"path":"milk.txt","revision":"a1b2c3d"}` — same filters, the text as it stood at that commit.
`GitService.bytesAt` already does the reading (`restore` uses it). (`ProjectGit.bytesAt` already
wraps it too.)

The loop this completes:

```text
history {"path":"milk.txt","grep":"two litres"}   → the hashes whose text matched
read    {"path":"milk.txt","revision":"a1b2c3d"}  → what it said
restore {"path":"milk.txt","revision":"a1b2c3d"}  → put it back
```

---

## 4. `history`

- `grep` matches the **file's content at each version**; non-matching versions do not appear at all.
  `versionsOf` already loads every version's bytes (`GitService.kt:657`), so this costs no extra read.
- `from`/`to` select versions, then grep inside that window.
- No default 20. Whatever fits under the char limit; over it, the too-big error.

## 5. `edit`: range, insertion, and a `read` call as the replacement

Four shapes, one meaning each:

```json
{"path":"x","find":"old","replace":"new"}                    replace every occurrence (today)
{"path":"x","from":10,"to":12,"replace":"new"}               replace a block
{"path":"x","from":10,"to":12,"replace":{"tool":"read","args":{…}}}   paste a selection over a block
{"path":"x","at":11,"replace":{"tool":"read","args":{…}}}    insert between lines
{"path":"x","text":"whole file"}                             overwrite (today)
```

- **`at`** is the insertion point: after line `at`. Not expressible as a range — `from:11,to:10`
  would be an empty range that happens to mean "here", which a model will get wrong.
- **Append/prepend are `at`.** `at` = last line + 1 appends; `at:0` prepends.
- **`replace` is either a string or a call.** Only `read` is accepted; anything else is refused with
  a sentence saying so — allowing another tool there turns one call into a small program.
- **The nested `read` runs unbounded** (§1 rule 2).

---

## 6. `move` absorbs `copy`; `clone` goes; `background` takes anything

**`move`** grows one boolean and one behaviour change:

```json
{"path":"milk.txt","into":"archive"}                    move
{"path":"milk.txt","into":"archive","copy":true}        copy, original stays
{"path":"milk.txt","into":"archive","name":"x.txt"}     move and rename
```

- `copy:true` → `Workspace.copy`; otherwise `Workspace.move` (`Workspace.kt:408`, `:488`).
- **Strict in both modes**: an occupied destination is refused. `Workspace.copy` keeps finding a free
  name for the person's screen; the agent's tool refuses, because a silent `milk 2.txt` is a surprise
  it will not notice and cannot undo.
- A locked source is refused for `move` and allowed for `copy` — already true in `Workspace`.

**`clone` is deleted** (`:565`, its spec, and its `LONG` membership). Cloning a repository is the
operator's, and the add-project window already does it. Its only test neighbour is `KeySafetyTest`,
which clones a *local path* — that stays, it exercises `GitService`, not the tool.

**`background` takes any command.** Today it has a `when` with two branches (`:262`) and refuses
everything else. It must **share the foreground dispatch** — one `run(call)`, not a second table that
drifts. Refused: `ask`, `conclude`, `wait` (they end a turn, no result to file) and `background`
itself (a background of a background loses a result).

Its description gains the hazard in plain words: *a writing command in the background changes the
project while the turn carries on.*

**After this, `LONG` is `restore` alone**, and `background`'s deadline comes from the background
setting as it does now.

---

## 7. The shipped prompt

Three edits to `SYSTEM_DEFAULT` (`AgentPrompt.kt:40`), and the same in `PREVIOUS_SYSTEM` where the
text is unchanged (a phone holding an unedited default gets the new one; an edited one is left alone).

1. `"A result is never silently shortened. If it says 'showing first N of M'…"` →
   **`"A result bigger than the operator's limit is an error that asks you to narrow the selection."`**
   (The old sentence describes behaviour that no longer exists.)
2. **`clone` leaves the tool list**; the tool list gains `copy` as a flag on `move`.
3. Add: **`"A file too big for a result can still be read with `background`, which writes it to a
   file instead of here."`**

---

## 8. Tests

| test file | what changes |
|---|---|
| `AgentTest` (63) | `lines` → `to` in every `read` call; the 200-cap tests become char-limit tests; new: insertion with `at`, paste from a `read` call, range replace, `copy` flag, strict collision, `read` at a revision |
| `BackgroundTest` (5) | its example command is `clone`, which is gone → `restore` (or `create`, now that any command may be backgrounded) |
| `TimeoutsTest` (5) | `Stopped.body("clone", …)` → a command that still exists |
| `AgentSessionFeatureTest` (20) | any test that assumes a slow command is `clone` |
| `HistoryFeatureTest` | new: content grep, `from`/`to`, the too-big error |

New tests worth naming:
- `a result over the limit is an error, and the error names the selection to add`
- `grep is applied inside the range, not the other way round`
- `a backgrounded read of any size is written in full, and reading it back is measured`
- `an edit whose replacement is a read call keeps the paste out of the conversation`
- `at inserts between lines and never replaces one`
- `a copy onto an occupied name is refused rather than renamed`
- `clone is not a tool any more`

---

## 9. Order of work

Each step is one commit that CI can measure.

1. **The limit** — `trim()` errors; delete `MAX_MATCHES`, `take(20)`, `MAX_SCAN`. *(Behaviour change
   with no new API — do it first, alone, so a red run is unambiguous.)*
2. **`window()`** — `from`/`to`/grep in `read`, then `history`, then `tree`; delete `lines`.
3. **`read` + `revision`.**
4. **`edit`** — range, `at`, nested `read` replacement.
5. **`move` + `copy`** and strict collisions.
6. **`clone` removed**; `background` shares the dispatch.
7. **Prompt + docs** (`docs/agent-tools-review.md` becomes the reference; the four drafts fold into
   it).
8. **`tools/test-map.txt`** each time a source file changes, and the new `docs/` entry.

---

## 10. Open, but not blocking

- **`Workspace.copy` for the person's screen** keeps its free-name behaviour; the divergence is
  deliberate and documented in one place. If you would rather they agree, that is a one-line change
  plus a UI question about what the person sees when the name is taken.
- **`restore` is the only slow command left.** A background `restore` therefore looks odd
  (`background {"tool":"restore"}` is exactly what it was built for), and that is fine.
