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

## Step 5 — semantic `search`, account-wide

Reports 24 and your four answers. **This is the biggest step and it comes last** because it is a feature,
not an argument fix, and every tool above changes shape while it is untouched.

### What already exists (verified, not assumed)

| piece | state |
|---|---|
| `Chunker` | **built and in use** — 800 chars, 160 overlap, SHA-256, 11 tests. Used today by `Scanner` |
| `SearchIndex` | **built and tested, never constructed in main** — 15 tests: hash sharing, per-version rows, size refusal, orphan collection |
| `Embedder` | **built, never called in main** — real OpenRouter implementation |
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
itself, with no error. Chunking makes that impossible by construction, and costs **1.25×** (the overlap),
not the 10× the old argument assumed.

### The four parts

1. **Write path.** On save, chunk and embed *changed* chunks in the background (the queue exists as
   `Background`). Session-log status `0`/`1`, per your instruction. `SearchIndex.put` already skips chunks
   whose hash is present and reports how many, so a no-op save is cheap.
2. **The unindexed count — and you are right that it comes from the database.** It has to be *derived*,
   not a counter, or it drifts the first time a write fails midway. The shape:

   - `meta` gains `scanned_at` (when the account walk last completed) — one row, already exists as a table.
   - **`pending`** gets a row per known file: `path`, `mtime`, `state` (`0` indexed / `1` failed / `NULL`
     not attempted), `error`. The walk inserts/updates a row per file it finds, which is the list of what
     *should* be indexed.
   - **The count is a query**, not a variable: `SELECT COUNT(*) FROM pending WHERE state IS NULL OR state = 1`,
     plus files whose `mtime` is newer than their indexed row.

   That is the part worth calling out: **a counter cannot answer "how many are behind"**, because a file
   added on disk while the app was closed was never counted by anything. A table of known files makes the
   answer a query over what exists versus what is indexed, which is what makes it able to *notice* an
   embedding bug rather than hide it.
3. **Read path.** Embed the query → score → rerank top ~30 → answer path + line. **Fall back to the regex
   walk** when the index is behind or the model is unset, and *say so*: "the semantic index is behind by 12
   files; this is a text search." A degraded answer that admits it is usable; a silent one is not.
4. **`tree` keeps the regex** — that is the whole point of two tools.

**Account-wide**, per your answer: the walk covers every project, so `search`'s reach does not change, and
the unindexed count is a count over the whole account — which is exactly what makes it a useful warning.

---

## How each report closes

| report | step |
|---|---|
| 26, 27, 28, 30, 34 | 2 |
| 29, 31, 32 | 1 |
| 33, 35 | 2 (`multi`, `read` keeps `from`/`to`) |
| 36 | 3 |
| 37, 23 | 4 |
| 24 | 5 |

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
- **`MediaStore` cannot be tested in CI** (no device). The listing gets a unit test against a fake
  resolver, and the honest limitation is written into the tool's description rather than trusted to memory.
