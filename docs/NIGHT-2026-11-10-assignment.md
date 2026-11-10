# The nightly assignment — 2026-11-10

Written before starting, from the code rather than from memory. Every item below was checked against
`AgentTools.kt`, `ProjectGit.kt`, `GitService.kt` or `AgentLog.kt` in the hour before this was
written, and each says **what is there now** so the change can be judged against a fact.

The order is deliberate: **unblocking first** (the branch is red), then the one-line-per-command
reshaping, then the new capability (`log`), then the two that need a decision from the operator.

---

## 0. Where the branch actually stands

`ce142bb6` and `05e521fe` are pushed; CI was red on `ce142bb6` for three tests I wrote myself, and
`05e521fe` fixes them. **Nothing else starts until that run is green** — a red branch makes every
subsequent verdict worthless, and this session has already spent two rounds on my own test mistakes.

Two defects found and fixed tonight, both worth keeping in mind while reading the rest:

- **A call-only reply lost its reasoning**, so a thinking model got its own turn back with the
  working erased and re-derived it. That is the loop.
- **Both trims were invisible to the model.** Rows vanished between requests with nothing saying so.

---

## 1. `search` — make it uniform, and stop it lying about what it is

**What is there now** (`AgentTools.kt:1281`): `scope`, `query`, `mode`, `history`, `depth`, `paths`,
`word`, `count`.

The operator's objections, each one measured:

| objection | what the code says |
|---|---|
| "why is the first parameter `scope` instead of a uniform `path`?" | correct — `scope` is a **pattern over the path**, so a folder, a file and a suffix are one filter. The *name* is the only thing wrong with it. Rename to `path`. |
| "why `query` and not `grep`?" | `query` is a **case-insensitive regex matched inside one line**, which is exactly what `grep` means in `read` and `write`. Two words for one act. Rename to `grep`. |
| "why `mode` when there is only one?" | correct — `mode` accepts only `regex`, refuses `semantic` with a sentence, and refuses to be present without a `query`. It is a parameter whose whole job is to refuse things. **Delete it**, and keep the `semantic` refusal as a sentence on the `grep` key so a model repeating an old call is still told what changed. |
| "why `paths` if it always shows a list of paths?" | `paths` is the **count** (how many results), not a flag. The name is what is wrong: it reads as "return paths". Rename to `count`… **but `count` is already taken** by a boolean that switches the answer to a tally. So: rename the boolean `total`, and the count becomes `count`. |

**Work:** rename `scope`→`path`, `query`→`grep`, delete `mode`, `paths`→`count`, `count`→`total`.
Update the spec text (which is long and mostly about `scope`/`query`), the refusals that name the old
keys, and the tests. **`word` stays** — it is the whole-word flag both `read` and `write` share.

**Risk:** every saved prompt, template and old session that says `scope` breaks. The app's own
`KNOWN`-key refusal will name it, which is the honest failure. **No aliases** — the operator's rule.

---

## 2. `git {op:"log"}` — the one-line log

**What is there now:** `ProjectGit.log(project, path?, limit)` exists and returns
`GitService.CommitInfo(id, shortId, message, author, authorEmail, timestamp)`. `GitService.log`
already builds it from jgit. **The plumbing is done; only the tool is missing.**

**Shape:** `{"op":"log","path":"a file, optional","count":20}` — one line per commit,
`shortId  when  message`, newest first. `path` restricts to commits that touched that file, which is
what `versions` does today but without the per-file ids.

**Work:** a branch in `gitTool`, a spec entry for `op`, and the `op` list in the refusal updated from
`diff or tag` to `diff, tag or log`.

---

## 3. `restore` with no `path` — check out the whole tree

**What is there now:** `restore(path, revision)` puts **one file** back, and `GitService.restore`
already handles a path that is absent from the commit (it deletes the target). There is no
whole-tree form.

**The change:** `{"revision":"…"}` with **no `path`** checks the whole tree out to that commit:
every file becomes its state at that revision, and files that did not exist then are removed. Then
**the index is reset** so nothing is staged — the operator's words: *"all files change to its state,
and stage is cleared"*.

**Why the stage must be cleared, and it is not a detail:** `push` refuses over unsent work and counts
the **index**. A checkout that left the index full of "everything changed" would make the next push
either refuse or commit the whole tree as one version. Clearing it is what makes the checkout a
*reading* act rather than a *writing* one — and the app's model is that save stages and push commits,
so a restore that staged would be a second committer.

**Work:** `GitService.checkoutTree(repo, revisionId)` (jgit `Checkout` or `ResetCommand` with
`setMode(HARD)`), `ProjectGit.restoreTree`, the `path`-optional branch in `restore`, and a test that
a file deleted in the target revision is gone afterwards and that `stagedPaths` is empty.

**Danger:** this destroys uncommitted work in the tree. The tool must refuse when the working tree
has changes that are not in a version — the same "a loss needs an asking" rule the person's UI
follows. **I will implement the refusal and ask before writing it.**

---

## 4. `diff` is a command; `tag` becomes an argument of `push`

**What is there now:** `git` takes `op` ∈ {`diff`, `tag`}. `tag` refuses off `main` and over unsent
work, and traces as `release`.

**The change:** `git` disappears as a wrapper. `diff` becomes its own tool. `push` gains an optional
`tag`, and the refusals move with it — **which is better than it sounds**: `push` already reads the
staged count, so "over unsent work" is the same number it is about to commit. A tag on a push is a
tag on the version that push makes, which is the only thing a tag can mean.

**Work:** new `diff` tool (moves `AgentTools`'s existing diff branch verbatim), `tag` argument on
`push`, delete `gitTool`, update the tool count in `AGENT-COMMANDS-2026-11-10.md` and the `KNOWN`
map. **`docs/AGENT-COMMANDS-2026-11-10.md` must be updated in the same commit** — it is the reference
the next session reads.

---

## 5. Brace expansion for paths — `{a,b}` and ranges

**What is there now:** nothing. `pattern()` turns a string into a `Regex`; paths are literal.

**The shape:** `write {"path":"notes/{milk,bread}.txt"}` writes two files;
`search {"path":"v{1..3}/"}` searches three folders. Bash semantics: comma lists, numeric ranges
`{1..9}`, and **nested braces**.

**Where it belongs:** one function, `Braces.expand(text): List<String>`, called by **the path
resolver** — so every command gets it from one place rather than each tool implementing it. That is
the same rule `Expand.kt` follows for `{{file:…}}`, and the two must not be confused: **braces are
path syntax, `{{}}` is template syntax**, and a path holding both is expanded braces-first.

**Refusals that matter:** a brace group that expands to more than a cap (say 1000) is refused rather
than run; an unbalanced brace is refused rather than treated literally; and **a command that takes
one path must refuse a path that expands to many** rather than silently acting on the first.

**Work:** `Braces.kt` + tests, then wire into `resolveFile` and the search walk. This is the largest
single piece of new code in the night.

---

## 6. `query` — no `path`, results to a folder

**What is there now:** `query` requires `path` and writes the CSV to the person's own files, with the
full `mayWrite` lock rules.

**The change:** the `path` argument goes; every answer lands in `agent/sql/<name>.csv` beside
`agent/browser/` and `agent/background_results/`, named from the call id so two queries cannot
overwrite each other. **This is what `shot` already does** (`agent/browser/shot-<ms>.png`) and what
`background` already does — so it makes `query` the same shape as every other command that produces
a file rather than an answer.

**Work:** drop `path` from `KEYS` and the spec, write to `RESULTS_DIR/sql/`, keep the conversation
answer as columns plus a few rows, and delete the `mayWrite` path (there is no longer a person's file
to protect).

---

## 7. `browse` — dump the whole page, no `from`/`to`

**What is there now:** `browse` returns the page text **inline**, sliced by `from`/`to`.

**The change:** write the whole text to `agent/browser/<name>.txt` and answer with the path plus the
first few lines — the shape `background` and `shot` already have. `from`/`to` go, because paging a
file is what `read` is for, and a second pager is a second way to say one thing.

**Work:** `Beside.write` to the results folder, drop the two keys, keep the `url → redirected` line
(which is a real fact and must survive), and update the spec.

**`shot` is already a folder** — `agent/browser/shot-<ms>.png`, confirmed at `AgentTools.kt:3715`.
No change.

---

## 8. `write` — `path` + `original`, and every argument takes an id, a path, or a call

This is the biggest and the one I would **not** start without the operator's answer.

**What is there now:** `write` takes three shapes, `text` is the new text in all of them, and `text`
may already be a tool call (`{"tool":"read","args":{…}}`, `AgentTools.kt:2521`).

**The proposal, in two halves:**

1. **`path` + `original`** instead of `path`+`from`/`to` and `path`+`grep`. `original` is the exact
   text to replace, so a model never counts lines. This is closer to what a model is good at
   (quoting) and further from what it is bad at (arithmetic over line numbers).
2. **Any argument may be an id, a path, or a call**, expanded into text before use — "response column
   from that session row, or file content, or command result".

**The half I can build tonight:** `original` as a third selector shape, kept alongside the others.
**The half that needs a decision:** "any argument may be a row id" makes **every** argument
polymorphic, which changes what the schema can say and what `unknownIn()` can check. It also
interacts with the reasoning bug fixed tonight: a row id must refuse loudly when the row was trimmed,
not substitute empty.

**I will build `original` and leave the polymorphic-arguments question open**, because guessing it
would be exactly the kind of unasked-for build the operator has ruled against.

---

## 9. The chat-mechanics test pass

**What is there now:** the suite runs against seams that never open a socket. `MediaStore`, `push`,
`pull` and `http` have **never run against a device or a real network** — which is how the app
shipped 79 releases without `INTERNET`.

**What this pass is:** tests for the chat's own mechanics — a burst of several requests, a reply that
calls and then speaks, a trimmed context mid-turn, a stop between two calls — asserting the record
and the request rather than the screen.

---

## What I will NOT do tonight

| | why |
|---|---|
| **`ssh` (P4)** | still blocked on the passphrase question. Not guessable. |
| **Polymorphic arguments on every key** | changes the schema's whole meaning; needs a ruling. |
| **Deleting `mode`'s refusal sentences** | a model repeating an old call must still be told what changed. |
| **Anything that rewrites `main`** | 887 commits behind and cannot build a signed release. |

---

## The order, and why

1. **Green the branch** — nothing is measurable before this.
2. **`search` rename** — the most visible lie in the tool list, and the smallest diff per unit of
   improvement.
3. **`git log`, `diff` split, `tag`→`push`, `query` folder, `browse` dump** — four small, independent
   changes, each with its own commit and its own doc update.
4. **`restore` whole-tree** — needs the uncommitted-work refusal first.
5. **Brace expansion** — the biggest new code; goes after the small ones so the branch is never far
   from green.
6. **`write {original}`** — last, because it touches the command every other one depends on.
7. **Chat-mechanics tests** — with whatever time is left, because they protect everything above.

Each commit: QC clean, push, CI green before the next. If a run is red, **that is the night's work**
and nothing else starts.

---

## What I need from the operator before starting

1. **`restore` with no path**: confirm it should **refuse** when the working tree has uncommitted
   changes, rather than warn and proceed.
2. **`write`'s `original`**: confirm it is a **third shape** kept alongside `from`/`to` and `grep`,
   rather than replacing them.
3. **`search`'s renames**: confirm no aliases — an old call using `scope` gets a refusal naming the
   new key, not a silent translation.

Everything else above I can decide from the code and the standing rules.
