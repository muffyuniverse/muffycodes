# Plan: the tool API, arguments first

Four decisions from the operator, 2026-11-04:

1. **`multi: true` is granted** — and **there is already a cap on how big a tool response can be**, so the
   multiline grant needs no cap of its own.
2. **Arguments first, and a plan before implementing.**
3. **Three Downloads tools** — listing, `download`, `upload` — `download` capped at 100 MB, read-only.
4. **Index the whole account**, and **report how many files are not indexed yet**.

This document is the plan. Nothing is built yet.

---

## Step 0 — the one fact that changes `multi`'s design

I proposed a 2000-character cap on a multiline match. **You are right that it is duplicate machinery.**
The cap already exists, and it is better than mine because it is the operator's, in their own units:

- `AgentTools.maxTokens` — default `SettingsStore.DEFAULT_MAX_RESULT = 6_000` **tokens**, operator-tunable.
- `forConversation` is applied to **every** tool result. Over the limit, the body is written whole to
  `agent/background_results/…` and the model is told the path and how to read it in ranges.

So a `multi` match that produces 40 KB is not an error the tool must invent a refusal for. It is a result
over the limit, and the existing machinery already turns that into "written whole to this path; read it in
ranges". **Removing the 2000-character cap also removes a number nobody chose** — the same reason the three
width buttons went from the drawing toolbar.

**Consequence for `multi`:** no cap of its own, no refusal. It reports start line and end line so the answer
is still positional, and it is bounded only by `forConversation`.

**One thing that needs care:** `hit()`/`hits()` are also called from paths that count matches for a
*summary* (the "and 39 more" line). A multiline match cannot be counted the same way, so the code has to
say which mode it is in rather than infer it.

---

## Step 1 — `write`: one tool where `create` and `edit` were two

Reports 29, 31, 32. Every call shape changes here, so it goes first and alone.

| before | after |
|---|---|
| `create {path, text}` | `write {path, text}` |
| `edit {path, text}` (whole file) | `write {path, text}` |
| `edit {path, from, to, text}` (line range) | `write {path, from, to, text}` |
| `edit {path, grep, replace}` (regex) | `write {path, grep, text}` |
| `edit {path, at, text}` | **gone** — `at` was the line cursor for repeated edits, and both remaining shapes locate themselves |

- **Creates when missing, including parent folders** — your words: *"if path doesn't exist it creates it
  automatically (folders and files)"*.
- **`replace` is deleted.** Your question was why `replace` exists when `text` does; the answer was that
  the line shape had taken `text`, which is naming by accident. `text` is the new text in every shape.
- A call still sending `replace` gets the existing wrong-key refusal, whose message will name `text`.
- **`KEYS["write"] = {path, text, grep, from, to, revision}`** — `revision` because a write from an old
  version is the `restore` case and refusing it loudly is better than silently writing stale text.

**Tests:** the three shapes each round-trip; a missing folder is created; a bare `path`+`text` replaces the
whole file; `replace` is refused with a message naming `text`; `at` is refused as an unknown key.

---

## Step 2 — the argument clean-up

Reports 26, 27, 28, 30, 34. One commit, because they are all the same class of change: a key that does not
describe the act.

| tool | change |
|---|---|
| `tree` | `grep` → **`content`**; keep `name` (it *is* the name filter) |
| `tree` | drop `from`/`to` — a listing is bounded by `paths`, not by line numbers |
| `history` | drop `from`/`to` — a log is not read by line number |
| `move` | drop `name`, and **create parent folders**, matching `write` |
| `read` | **keep** `from`/`to` — see below |
| `runs`, `browse` | drop `from`/`to` where they are just a paging cursor over a result |

**Why `read` keeps `from`/`to`.** You asked whether line selection is needed at all now that patterns span
lines. For *replacing*, no. For *reading*, yes: **you cannot grep text you have not found yet.** "Show me
lines 40–60 of a file I have never seen" has no pattern to grep with, and it is the most common first read
an agent does. A hit says "line N" for the same reason — position is how a next call names a place.

**`multi` lands here**, since it changes the same surface:
- `grep` stays per-line by default (one line, one excerpt, one line number).
- `grep` + `multi: true` → compile with `DOTALL or MULTILINE`, run over the whole file, answer
  **start line–end line**. No cap; `forConversation` bounds the result as it does everything else.
- `[\s\S]*?` is the idiom to tell the agent to use for "any character including newline", since it needs no
  flag knowledge.

---

## Step 3 — `fetch` and `archive`

Reports 36, 38. Independent and small.

- **`fetch`**: `Fetched.LIMIT_BYTES` 20 MB → **100 MB**, and the refusal sentence reads the constant so it
  cannot go stale.
- **`archive`**: `{path}` packs, `{path, unpack: true}` unpacks, output **beside the input** with the same
  name and the other suffix. Collision → next free name, matching `Export`'s rule. `as` and `into` are gone.
  (`-u` stays a boolean named `unpack`, since a one-letter key is a CLI habit.)

**Two things the plan did not foresee, found by the suite (`6da6f00`).**

1. **"Beside the input" collides with two read-only folders, and it is the same collision twice.** `agent/` is
   the app's own folder, so unpacking a *fetched* zip — the workflow `fetch`'s own description promises — would
   have been a refusal; and a project's parent is the account, also read-only, so zipping a whole project would
   have put the zip outside it. Resolved with one rule, `outputFolder`: **beside the input, or the project when
   beside is not writable.** Both exceptions reduce to "inside the project", which is the only other answer, and
   every answer still says which folder it used. The alternative — refuse and tell the agent to `move` the zip
   first — protects a folder holding a download cache, not the record, at the cost of the documented step.
2. **Entry names had to stop being project-relative.** With the destination derived from the input, a
   project-relative name repeated the top folder on the way back out (`src/src/…`). They are relative to the
   packed thing now, so packing `src/` and unpacking gives back a folder shaped exactly like `src/`.

**One test was pinning a number the plan changed**: `a size past the cap` asserted the literal `20.0 MB`. It
reads `Fetcher.size(Fetched.LIMIT_BYTES)` now, so raising a cap cannot look like a broken test.

---

## Step 4 — Downloads: listing, `download`, `upload`

Reports 37, 23, and your log-reading scenario: *"dumping and reading logs via downloads… this way agent
inside muffycodes can read logs from some other debug application and fix bugs"*.

```
downloads {}                        names, sizes, dates — never contents
download  {name}                    one file into agent/downloaded/<name>
upload    {path}                    one project file into Downloads
```

- `upload` is today's `downloads`, renamed; same `withinProject` bound, folder refused, same `Export` code
  the person's ⋮ action uses so the two cannot drift.
- `download` lands in `agent/downloaded/`, **never over a project file** — two visible steps, the same
  reason `fetch` lands in `agent/fetched/`.
- Cap **100 MB**, refusing above it with the size named.
- Read-only and no delete: uploading does not remove the export; clearing Downloads is a person's act.
- **The Android fact goes in the tool's own text**: `MediaStore.Downloads` lists what other apps wrote
  through MediaStore. A file written by raw path may not appear, and the listing must say so rather than let
  an agent conclude a file does not exist.
- **The ⋮ menu gains "Copy to Downloads"** in the same pass (report 23), so person and agent share one path.

---

## Step 5 — one `search`, and `tree`/`history` fold into it — **BUILT**

**Built and pushed; CI is queued behind a GitHub Actions incident, so this is code-complete and not yet
verified by a compiler.** What is below is the design as agreed; the three things the build added that the
design did not foresee are listed at the end.

**The operator's design, 2026-11-04**, reached over two rounds of pushing back and forth. It replaces three
commands with one:

```
search {
  scope:   regex over the relative path            (absent = everywhere)
  query:   regex over file content, or meaning      (absent = no content filter)
  mode:    regex | semantic                          (what `query` means)
  history: also search every saved version          (→ path@hash)
  depth:   how deep to walk
  paths:   how many paths to answer with            (10 default, 100 max)
}
```

**Where I was wrong, recorded because it decided the shape.** I first argued against this on three grounds,
and the operator answered all three:

1. *"`name` has nowhere to go."* **Wrong.** `scope` is a regex over the whole relative path, so
   `scope: "\\.md$"` **is** the name filter. The axis folds into scope and the command needs one fewer key
   than the six it started with.
2. *"`rows` means two different things."* **Resolved by the operator's naming**: it is `paths` because the
   answer is `path@hash`. Which forces a decision worth stating — a content match answers **one row per
   path** ("`x.kt:12: …` and 39 more"), not 39 rows. "Every occurrence" is then `read {path, grep}`, which is
   the cleaner layering: search finds files, read finds lines.
3. *"Listing with no query is a degenerate case."* **Wrong, and this was my worst argument.** With `scope`
   present, listing is `scope` + `depth`. The filters are **independent axes**; setting none of them is
   setting none, not a mode switch. That is a different thing from `grep`/`from`/`to`/`at`, where three keys
   competed to say *where* and two at once was a refusal. Orthogonal filters are not competing selectors, by
   the same standard step 1 used to delete `replace`.

### What each old call becomes

| today | after |
|---|---|
| `tree {path, depth}` — list a folder | `search {scope, depth}` |
| `tree {name:"\\.md$"}` — list markdown | `search {scope:"\\.md$"}` |
| `tree {content:"needle"}` — files whose text matches | `search {query:"needle"}` |
| `search {query, in, paths}` — account-wide | `search {query, scope, paths}` |
| `history {path, grep, paths}` — a file's versions | `search {scope:"milk.txt", query:"…", history:true, paths}` |
| `search {query, history:true}` — versions anywhere | same, without scope |

### The one thing to get right: `mode` changes the engine, not the filter

Every other key **narrows** one search. `mode` **swaps** the engine. That is the only key here that can make
a model believe it searched when it did not, so:

- **The answer says which engine ran.** Semantic hits carry their score; regex hits carry a line number.
  A model reading an answer can tell what produced it without being told.
- **`mode: semantic` with no index is a refusal that names the setting**, never a silent regex run.
- **`mode: regex` is the default**, because it works offline and instantly and is what most questions want.
- **Both keys are checked from `query`'s presence**: `mode` without `query` is a refusal, because there is
  nothing to interpret.
- **Terminology**: the operator's own word is `mode`, and it is one key with two values rather than two
  mutually-exclusive keys — the wrong value is then a refusal naming both, not a missing key.

### The operator's decision: semantic search is per project

> *"semantic search is per project, make it this way, so that account wide semantic search is forbidden and
> scope must be project and lower."* — 2026-11-04

This answered the one question the plan could not: **where the index lives.** It had two candidate homes —
one database per project (`agent/search.db`, which `SearchIndex.open` already took) or one for the account.
The operator chose per project, so:

| | |
|---|---|
| **index** | one `SearchIndex` per project, in that project's `agent/` — the existing `open(projectDir)` shape, so the decision cost nothing to place |
| **extent** | `mode: "semantic"` walks and answers inside the current project only, by construction: the walk root is the project and anything whose canonical path leaves it is skipped |
| **`scope`** | still a pattern over the **account-relative path** (one spelling in both modes), but the search is bounded by the project — and a `scope` that names **another folder in the account is refused by name**, because answering "nothing matches" about a folder never looked in is the silent no-op this app treats as a defect |
| **the answer** | ends with `shopping/ only — semantic search is per project`, so the limit is visible rather than inferable |
| **`history`** | refused with `semantic`: versions are not indexed, and half an answer under a wider name is worse than a refusal |

**Why per project is the right shape and not just the chosen one:** a vector space is only meaningful against
the vectors it was built with. Per account, every project's saves would write to one database, and one
project's re-index would hold the write lock for the whole account's search.

### What already exists (verified, not assumed)

| piece | state |
|---|---|
| `Chunker` | **built and in use** — 800 chars, 160 overlap, SHA-256, 11 tests. Used today by `Scanner` |
| `SearchIndex` | **built and tested, never constructed in main** — 15 tests: hash sharing across versions, per-version rows, size refusal, orphan collection, `path@sha7` as the caller-facing form |
| `Embedder` | **built, never called in main** — real OpenRouter implementation, `/api/v1/embeddings` + `/api/v1/rerank` |
| model picker | **built** — `CatalogueKind` already has three catalogues; the settings screen picks from them |

Measured live: **40 embedding models, 12 rerank models, rerank all priced 0.** Cheapest paid embedding
`pplx-embed-v1-0.6b` at $0.004/M — indexing all app source once is $0.0006.

**Defaults:** `qwen/qwen3-embedding-8b` at 1024 dims (honours `dimensions`), rerank `cohere/rerank-4-fast`.

### Why chunking, measured

| model | 45 000-token input | result |
|---|---|---|
| `pplx-embed-v1-0.6b` (32k ctx) | **accepted** | `prompt_tokens: 32768` — **silently truncated** |
| `llama-nemotron-embed-vl-1b-v2:free` | **rejected** | `400: exceeds maximum 8192` despite declaring 131 072 |

`AppViewModel.kt` is 61 747 tokens — whole-file embedding would index the app's own largest file as half of
itself, with no error. Chunking makes that impossible by construction, and costs **1.25×** (the overlap), not
the 10× the old argument assumed.

### The work, in order — **1–6 BUILT**

1. **`scope` replaces `path` + `in`.** ✅
2. **`history` becomes a third axis.** ✅ (and refused with `semantic`, which is the operator's boundary)
3. **`paths` counts paths in both modes.** ✅
4. **The write path.** ✅ **but not where the plan said.** The plan said "on save, in the background". There
   is no single save: the editor writes, the agent writes, a clone writes, a restore writes, and a save hook
   would have to exist in each — with the one that was missed being a project whose search silently misses
   its newest work. Instead the **walk runs inside the search**, with a modification time per file in
   `files(path, mtime, state, error)`, so anything that ever wrote a file is caught the next time anybody
   searches, and an unchanged file costs one directory entry and no request. It is bounded by the same
   `Deadline` as every other command: a pass that runs out of time answers with what it has and says what is
   left, and asking again carries on from there because the rows are already recorded.
   **The status is in the answer's note, not in the session log** — the plan asked for `0`/`1` rows there.
   The note is what both the model and the person read, and a log row would be a second copy of the same
   fact with its own chance of being wrong; a session-log row is still the right place for *what a tool
   did*, and indexing is a step inside one answer rather than an act of its own.
5. **The unindexed count, from the database — not a counter.** ✅ `files(path, mtime, state, error)`; the
   count is a query, the note appears on the answer, and a **failed** file has no `indexedAt`, so the next
   pass retries it instead of skipping it for ever.
6. **The read path.** ✅ embed → `nearest` → **drop rows whose cosine is not positive** → rerank the top
   `RERANK_DEPTH = 30` → answer `path:line: text  [0.87]`. No regex fallback. A reranker that is down is a
   **note**, not a failure: vector order is still a meaning search, unlike a text search standing in for one.

### What the build found that the design did not foresee

1. **`spec()` marked every example key `required`.** That was harmless while every tool's example was its
   only shape, and wrong here: `search` has five optional filters, and a schema saying `query` is required
   forbids the listing — the mode the operator named. `spec` takes an optional `required` list now, and
   `search` passes none, so `{}` is a legal call. A test pins it.
2. **`paths` needed two defaults, not two meanings.** A pure listing is read whole (default 100), while a
   `query` or `history` answers a handful (default 10 — the operator's own number). One key, one meaning.
3. **The walk's "there is more" flag was wrong for a folder.** When the entry that filled the limit was a
   *folder*, its contents were never visited and nothing recorded that they had been skipped, so a
   truncated listing looked complete. `walkForSearch` now returns whether it stopped with work left.

### Why `search` keeps no regex fallback, restated

The machinery already exists on all three sides: `refusal` is `1` + a sentence; `Deadline` is handed to every
command by `run` and throws `StoppedException` so the result reads "stopped after N s; nothing was kept"; and
`forConversation` files an oversized result rather than cutting it. A fallback would be a second mechanism
doing a worse job than the first — answering with a text search nobody asked for, with a hedge that makes a
model believe it searched. And it would hide the one thing worth seeing: **`search` is the first tool here
with a network dependency**, and an agent that reads a network error as "nothing matches" will conclude the
corpus is empty and act on it.

## How each report closes

| report | step |
|---|---|
| 26, 27, 28, 30, 34 | 2 |
| 29, 31, 32 | 1 |
| 33, 35 | 2 (`multi`, `read` keeps `from`/`to`) |
| 36 | 3 |
| 37, 23 | 4 |
| 24 | 5 |
| 26, 27, 28, 33, 35 | 5 (re-expressed as `scope`/`query`/`paths`) |

**22** ("1 session" row) is not a tool report — it goes with the project-screen work and stays on the ledger.

Every step lands with its ledger row marked in the same commit, per `docs/REPORTS-LEDGER.md`.

## Risks worth naming before I start

- **Step 1 breaks every agent call that uses `create`/`edit`.** Tests will catch the app's own; a model in
  flight will see the old spec. Acceptable because the refusal names the new shape, but it is a one-way
  door — there is no compatibility shim, deliberately: two names for one act is the defect.
- **Step 2's `grep`→`content` rename touches `tree`'s spec text**, which is part of every request's cache
  prefix. One cache miss per conversation, once.
- **Step 5's write path is the only step that can corrupt an index.** It must be idempotent and resumable —
  a half-written index has to be repairable by re-running the walk, not by deleting the database.
- **Step 5 adds a network dependency to a tool that had none.** `read`, `tree` and `write` work offline
  forever; `search` will not, once it is semantic. The refusal must say so plainly ("no embedding model is
  chosen"), because an agent that reads a network error as "nothing matches" will conclude the corpus is
  empty and act on it.
- **`MediaStore` cannot be tested in CI** (no device). The listing gets a unit test against a fake
  resolver, and the honest limitation is written into the tool's description rather than trusted to memory.
- **Step 5 deletes two commands.** `tree` and `history` go, so every prompt, example and test that names them
  changes at once — the same one-way door as step 1, one size up. The refusals have to name the new shape or a
  model in flight is stranded with a sentence about a command that no longer exists.
- **`scope` + `history` + `mode: semantic` is the expensive corner**: every version of everything under the
  scope, embedded. The hash-shared index makes it cheap for unchanged chunks, but a broad scope over a long
  history is the one combination that costs real money, and the description should say so rather than let an
  agent discover it.
- **`mode` is the only key that swaps the engine rather than narrowing a filter.** A model that omits it gets
  regex and may believe it searched semantically. That is why the answer must say which engine ran and
  semantic hits carry their score.
