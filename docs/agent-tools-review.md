# The agent's tools: what they are, and what they should become

*Written 2026-10-26, after reading the code rather than the design notes. Every "today" statement
below was checked against the source; the file and line are given so it can be re-checked.*

---

## 1. The tools as they stand

Fourteen. Eleven do work; three end the turn.

| tool | arguments | what it actually does |
|---|---|---|
| `tree` | `path`, `depth`, `name` (glob), `grep` (regex) | lists files; with a pattern, lists matches instead |
| `read` | `path`, `from`, `lines`, `grep` | one text file. **`grep` and `from`/`lines` are mutually exclusive** (`AgentTools.kt:398–425`) |
| `edit` | `path`, `find`, `replace` **or** `path`, `text` | find-and-replace, or overwrite the whole file |
| `create` | `path`, `text` | makes a file **and every missing parent folder** (`AgentTools.kt:487–506`) |
| `delete` | `path` | one file, or an empty folder |
| `move` | `path`, `into`, `name` | move; with `name`, move-and-rename |
| `clone` | `url` | **`git clone` of a GitHub repository into this project's folder** — refused when the project has uncommitted changes (`AgentTools.kt:565–577`) |
| `history` | `path` | up to 20 versions: `shortId  message  timestamp` |
| `restore` | `path`, `revision` | puts one version back. `revision` is any hash `Repository.resolve` accepts — 7+ characters, so short hashes work (`GitService.kt:577`) |
| `background` | `tool`, `args` | runs `clone`/`restore`; result goes to a file |
| `reason` | `text` | writes a thought into the record |
| `forget` | `ids` | the agent's own context compaction |
| `ask`, `conclude`, `wait` | a string / `{}` | end the turn |

### Things about that table that are not obvious

**`wait` does end the turn.** It runs no tool, but the loop stops, a call-and-result pair is
written so the next request is a legal conversation, and the session goes idle until the operator's
interval (`Agent.kt:402–415`). "Does nothing" is true; "does not end the turn" is not.

**`create`, `delete` and `move` are adjacent in the file for a reason that is not their
arguments.** They sit together because each one commits after writing. Their arguments are as
different as they look: `create` takes a path whose last segment is the name, `delete` takes a path,
`move` takes a path and a destination.

**`clone` is misnamed.** It is `git clone` — it fetches a whole repository into the project folder.
Every other tool name is about files, so "clone" reads like "duplicate this file", which is exactly
the confusion it caused. There is no tool named for copying a file, and none for pasting.

**`read`'s `grep` returns `N: line` with real line numbers** (`AgentTools.kt:404–409`) — they exist
so the next call can edit what was found. But **nothing accepts a line number**: `edit` can only
replace a string or overwrite the file. To insert one line the model must read the whole file,
splice it in its head, and write it back.

**`history` shortens silently.** `versions.take(20)` (`AgentTools.kt:586`) drops everything past the
twentieth with no note, against the same file's promise that "a result is never silently shortened"
(`AgentTools.kt:12–15`). It also shows only the commit message, and since Save now commits
`Saved`, twenty versions of one file can read as twenty identical rows.

---

## 2. The work, in four parts

### A. A result too big to read becomes a file

`trim()` (`AgentTools.kt:639`) is the **single** place any result is shortened, and it has exactly
one call site. That is the whole surface of this change.

Today it cuts the body at `maxChars` (the *result length* setting) and appends
`(showing first N of M characters; ask again with a range)`.

Instead:

```text
0
read: 41289 characters, written to agent/background_results/read-42.txt

Narrow it — one of:
  {"tool":"read","args":{"path":"agent/background_results/read-42.txt","from":1,"lines":200}}
  {"tool":"read","args":{"path":"agent/background_results/read-42.txt","grep":"pattern"}}
```

Three rules make it sound:

1. **The sentence names the path and shows the call**, because a model can copy a call but cannot
   infer one.
2. **A filed result is never re-filed.** Reading a result file returns it as it is. Without this the
   feature recurses: read a big file → it becomes a file → read that → it is big → it becomes a file.
3. **The truncation note disappears.** Either the result fits, or the result *is* the path.

`read`'s `grep` and `from`/`lines` must then compose — grep first, window second — so that "matches,
then the lines around one of them" is one call. Today they are exclusive, with a comment defending
it: *"two windows at once is a range that means two things."* That is true of two **ranges**; it is
not true of a filter and a range.

**Same rule for `tree`:** its match list is capped at 200 (`AgentTools.kt:410`). One behaviour for
both, or the model has to learn which tool truncates and which files.
@ lets simplify even further - if content is bigger than the limit the result is error that asks to narrow selection, that is all.

### B. `history`

- **`lines`** (default 20) and **`from`**, so the cap is a choice and the note says how many were
  left out.
- **`grep`**, narrowing the list to versions that match.

*Question for you:* grep against the **commit message** or against the **file's content at each
version**? Content is the useful one — "which version still had `TODO` in it" — and
`GitService.versionsOf` already loads every version's bytes (`GitService.kt:657–680`), so it costs
nearly nothing. Message-only would mostly match the word `Saved`.
@ (default 20) - did I ever ask to make any other limit than content char limit!? MY CONTENT LIMIT applies to every command results. dont think up your own! agent can select however many lines he wants - but if response is bigger than the limit then he gets error. grep against file contents for each version, if grep fails then version line doesnt show. grep applies after lines filter. Everywhere - if both lines and grep are used then grep is applied to the selected lines.

### C. `read` at a version

```json
{"path":"milk.txt","revision":"a1b2c3d","grep":"two litres"}
```

Same file, same filters, the text as it stood at that commit. `GitService.bytesAt` already exists
and is what `restore` uses. This is the tool that makes `history` pay for itself: history finds the
version, read shows it, restore puts it back — and today the middle step is missing.

### D. Copy, paste, insert

- **`copy`** — wrap `Workspace.copy`, which exists and already handles a free name
  (`milk 2.txt`), refuses copying a folder into itself, and is not blocked by a lock on the source.
  The agent has no access to it at all today.
- **Paste a selection into another file** — either a selector on the *destination* side of `edit`,
  or a `copy` that takes a line range. This is the case you described: take lines/grep from one file,
  put them into another.
- **Insert without replacing** — `edit` today is `find`→`replace` or whole-file `text`. A line-based
  form (`{"path":"x","at_line":12,"insert":"…"}`) is the missing primitive.
  @ I thought that clone is about cloning folders/files, and copy is about copying content from one file into another. And that is why I asked whether grep can be used in this case for prepend/append/insert/replace - those questions were about copy command? I dont even know where you get the idea about cloning remote repo - it is what operator does and we already have this functionality!!!

---

## 3. The five questions I need answered

1. **`history` grep — message or content?** I recommend content.
2. **Insert: a fourth shape on `edit`, or its own tool?** `edit` would then have three contradictory
   argument sets (`find/replace`, `text`, `at_line/insert`), and a contradictory argument set is how
   a model picks the wrong branch. I lean to a separate `insert` tool.
3. **Copy/paste shape:** `copy {path, into, name}` + a destination selector on `edit`; **or**
   `copy {from:{path,lines}, into, name}` that takes the lines directly? The second is fewer round
   trips and closer to your description, but one tool's argument becomes another file's selector.
4. **Rename `clone`?** It is `git clone`. `fetch`? `pull_repo`? Or keep it and fix only the
   description?
5. **A read that is too big: error or result?** You said "returns error and asks to apply tighter
   selection". I would make it a **result** whose body is the path — the read *succeeded* and the
   data is reachable. An error tells the model the read failed when it did not, and a model told
   that will try something else instead of narrowing.

## 4. What is not blocked

A, B (with content grep) and C can land now, in that order, with tests:

- `trim()` files instead of cutting, with the no-refile rule and a test that proves the recursion
  stops.
- `read` composes `grep` with `from`/`lines`.
- `history` gains `from`/`lines`/`grep` and says what it left out.
- `read` gains `revision`.

D waits on questions 2 and 3. Question 1 changes three lines. Questions 4 and 5 are free.
