# Counterproposal: the tool API, against your answers

Answering `/root/Downloads/PROPOSAL-tool-api-2026-11-04.md`, which you annotated inline. Your comments
first, then what I agree with, then **the four places where I think the answer should be different** and
why. Nothing here is built yet.

Your annotations, in order:

| § | You said |
|---|---|
| 1 (report 24) | `@ regex is used in "tree", search should be semantic. and set default embedding and rerank model from openrouter catalogue (cheap and reliable ones).` |
| 2 (report 25) | `@ ok, keep 100 max for search results` |
| 3 (report 32) | `@ ok, option a. I just asked why we even show replace as argument if we already have "text" argment and write command always means replace?` |
| 4 (report 33) | `@ why cant I grep a selection over multiple lines? is it limitation of regex or our restriction? If agent needs to replace a function block by grepping its signature and curly braces how doe he do that?` |
| 5 (report 35) | `@ I was just thinking whether we even need line selection when we have grep - the only question is whether it can span and detect line breakes on its own. if so then we dont even need to know what lines are - we can grep everything by pattern.` |
| 6 (report 37) | `@ worth, I want to give agent access to device downloads folder, readonly for all files and with ability to download and upload.` |
| 7 (report 38) | `@ ok` |
| §8 | `@ ok` |

---

## Where we agree, settled

- **§2** — 100 stays as the `search` ceiling. The spec text will say *"1 or more, 100 at most, 10 by
  default"* so the floor is visible.
- **§3** — `write` with `path` + `text` and no selector **replaces the whole file**. And your follow-up
  is the sharper question, so: **`replace` goes away entirely.** Once `create` and `edit` are one `write`,
  `text` is the new text in every shape, and a second key meaning "the new text" is two names for one
  thing. See §3 below for what that leaves in the regex shape.
- **§7** — `archive` becomes one `path` and `unpack: true`, output beside the input, next free name on
  collision.
- **§8** — done (`989c502`), green, with a test that reads the live brief.

---

## §1 — semantic search: agreed, but it is bigger than a tool change

You are right that `tree` is the regex tool and `search` should be semantic. What I under-sold in the
proposal is the size of it: **the embedding machinery exists and nothing runs it.**

- `Embedder` — interface plus a real OpenRouter implementation (`/api/v1/embeddings`, `/api/v1/rerank`).
- `SearchIndex` — the SQLite vector store, keyed by text hash so history costs deltas.
- `Chunker` — the split.
- **`SearchIndex.open(...)` appears in exactly one file: its own test.** No app code constructs it, no
  save path feeds it, no query reads it.

So "search should be semantic" is not "point `search` at the embedder". It is: **an index that something
keeps up to date.** That has four parts, and only the last is the tool:

1. **A model `search` can use — and here your instruction runs into a measured fact.**

   I checked, rather than assuming: **`/api/v1/models` lists no embedding model and no rerank model at
   all.** `[m for m in models if "embed" in id or "rerank" in id]` is empty. So "set the default from the
   catalogue" cannot be done as written — there is nothing in the catalogue to pick.

   But the **endpoints** work anyway, which is exactly what `Embedder`'s own doc comment says was the
   finding the design turned on. I confirmed all three by hand just now:

   | id | result |
   |---|---|
   | `qwen/qwen3-embedding-8b` | works, honours `dimensions` |
   | `openai/text-embedding-3-small` | works, 1536 dims |
   | `cohere/rerank-4-fast` | works, answers `relevance_score` |
   | `cohere/rerank-v3.5` | works |

   **And the cost is reported per call**, not just priced in a catalogue: the embeddings response carries
   `usage.cost` — 2 tokens cost `4e-08` dollars. So the app can *measure* what a search costs instead of
   quoting a price list that does not contain these models.

   **Counterproposal: a fixed default list in code, not a catalogue lookup.** Four ids, cheapest-and-
   most-reliable first, chosen by hand and pinned by a test. That is honest about the catalogue gap and
   costs no network call to decide. `qwen3-embedding-8b` at 1024 dims is my pick for the default (it
   reports its own cost, and its `dimensions` parameter is what the index already stores).
2. **A write path.** On save, chunk the file and embed the changed chunks. This is the part with a real
   cost: an agent writing 40 files is 40 embedding calls, and it must not block the editor. It goes in
   the background queue, and until it catches up `search` says *"3 files are not indexed yet"* rather
   than silently missing them.
3. **A read path.** `search` embeds the query, scores chunks, reranks the top ~30, and answers with
   paths — **then falls back to the regex walk when the index is empty**, so the tool never becomes
   useless on a fresh install or after a model change.
4. **`tree` keeps the regex.** That is the whole point of having two tools: `tree` is "what is in this
   folder, matching this text", `search` is "which file answers this question".

**Counterproposal on sequencing:** items 1–3 are a feature, not a report fix, and doing them together
with the argument clean-up would put a network-dependent index behind an unrelated rename. I propose
**the argument clean-up and `write` land first** (they are pure wins and touch every single call), then
§1 as its own project with its own tests. The reports are not waiting on it: none of the 17 says
"search is too slow" or "wrong".

---

## §3 — `replace` goes, and here is the shape that survives

Your question — *why show `replace` when `text` exists and write always means replace?* — has one answer
today and it is a bad one: `edit` has three shapes, and the regex shape uses `replace` because `text`
was taken by the line shape. That is naming by accident.

After the merge there are **two** shapes, and no ambiguity:

```
{"path":"x.kt","text":"…"}                     the whole file becomes this
{"path":"x.kt","grep":"fun foo\\(","text":"…"}  every match of grep becomes this
```

`text` is the new text in both. `replace` is deleted from `KEYS` and from the spec. A call still writing
`replace` gets the refusal that already exists for a wrong key, and the message names the replacement.

That leaves `at` (report 31) with no job, as predicted — the line cursor exists for repeated positional
edits, and both shapes that remain are self-locating.

---

## §4 and §5 — the multi-line question: **it is our restriction, and I think it is the wrong one**

This is the substantive disagreement, so let me be exact.

**Why it cannot span lines today:** `hit()` and `hits()` call `Regex.find` on **one line at a time**. The
walk splits the file into lines and applies the pattern per line. There is no regex flag and no key that
changes that.

**Is it a regex limitation? No.** Java/Kotlin regex handles multi-line input natively —
`Pattern.DOTALL` makes `.` match `\n`, `Pattern.MULTILINE` makes `^`/`$` match at line boundaries. The
gap is entirely ours: we never hand the engine more than one line.

**So: should we?** You are asking the right question, and I think the answer is **yes, with a size bound**,
for exactly the case you name — an agent replacing a function block by grepping its opening line and its
closing brace:

```
{"path":"x.kt","grep":"fun render\\([\\s\\S]*?\\n\\}","text":"…"}
```

`[\s\S]` is the idiom for "any character including newline" that works on any regex engine regardless of
flags, and it is what the agent should be told to use.

**The counterproposal — a `multi` key rather than DOTALL-by-default:**

- **`grep` stays per-line by default.** A pattern that matches one line is 95% of uses, and it is what
  makes a hit cheap to show: one line number, one excerpt, no enormous match.
- **`grep` + `multi: true` compiles the pattern with `DOTALL or MULTILINE`** and runs it over the whole
  file. The hit then reports the line the match **starts** on and the line it **ends** on, so the answer
  is still positional and still readable.
- **A match is capped** — 2000 characters, say — and a longer one is refused with *"that pattern matched
  40 KB; narrow it"*. This is the trap the current per-line rule was avoiding by accident: `.*` over a
  whole file, or a nested quantifier, is how a regex engine melts a phone.

**What this does to `from`/`to` (report 35):** your instinct — *"if it can span, we can grep everything by
pattern"* — is **right for replacing**, and **wrong for reading**. Two reasons, and they are why I still
want line ranges in `read`:

- **You cannot grep text you do not know.** Reading lines 40–60 of an unfamiliar file is "show me this
  region"; there is no pattern, because you are looking *for* the pattern. That is the most common first
  read an agent does.
- **A line-aware excerpt is how a hit is located.** Every hit says "line N". Without line numbers the
  answer is a wall of text with no place to act on it.

So: **`from`/`to` stay on `read` and `write`, and go everywhere else** — `tree`, `history`, `runs`,
`browse`. That is your report 30, honoured, without pretending a pattern is a cursor.

---

## §6 — device Downloads: agreed on the intent, and the "read-only for all files" needs one hard bound

You want read-only access to all of Downloads plus download/upload. Two facts about Android 16 decide how
this can honestly be built:

1. **`MediaStore.Downloads` is the only route, and it only shows you media-indexed files.** There is no
   "list the Downloads folder" API on a modern Android without a storage permission this app does not
   request (`READ_EXTERNAL_STORAGE` is gone for new apps on 14+; `MANAGE_EXTERNAL_STORAGE` is a Play
   policy rejection for a notes app). Reading `Environment.getExternalStoragePublicDirectory()` directly
   is denied.
2. So "read-only for all files" means **all files MediaStore will list**, which in practice is what other
   apps put there through MediaStore — not, for example, a file an app dropped via raw file access.

That is still a big and useful surface. My counterproposal on **how** it is granted, because this is the
one capability that can read your bank statement PDF:

- **`upload {path}`** — project → Downloads, exactly today's behaviour renamed. Bounded by
  `withinProject`, folder refused, same `Export` code as the person's own Downloads action.
- **`download {name}`** — Downloads → `agent/downloaded/<name>` in the project, never straight over a
  project file. A second visible step is what stops a download quietly becoming an edit.
- **Listing, because the agent cannot guess a name.** One more read-only tool — **`downloads {path?}`** as
  a listing when `path` is absent — that answers names and sizes only, never contents. Without it
  `download` is uncallable in practice, and adding a listing *inside* `download` would make one tool
  answer two questions again, which is the thing this whole pass is fixing.
- **The bound I want you to confirm:** a read of a MediaStore row is **bytes into the project**, and I
  propose the same 100 MB ceiling as `fetch`. Above it: refused with the size named.

**And one thing I will not do without you saying it explicitly:** `download` reads. It does not delete,
does not move, and refuses any path outside the Downloads collection. If you later want it to clear the
app's own exports after uploading, that is a separate grant.

---

## The order I propose

1. **Argument clean-up** (26, 27, 28, 30, 34) — `write` first, since `edit`'s `replace` is deleted by it
   (29, 31, 32). One commit, every call shape changes, tests pin the new refusal messages.
2. **`fetch` 100 MB** (36) and **`archive` one-path** (38) — small, independent.
3. **Downloads: `upload` + listing + `download`** (37, 23) — new capability, permission-shaped, needs
   the export ⋮ entry in the same pass so person and agent share one code path.
4. **`search` semantic** (§1) — its own project: models, write path, read path, fallback.
5. **§4's `multi` grep** — the one that makes "replace a function block" possible; I would put it *with*
   step 1, because it is the same argument surface and the same refusal messages.

## What I need from you

1. **§4/§5** — is `multi: true` the right grant, with the 2000-character cap? Or per-line by default with
   no escape hatch?
2. **§1 order** — arguments first, semantic search as its own project? Or does semantic search jump the
   queue?
3. **§1 models** — the catalogue has none, so the defaults must be hard-coded. I propose
   `qwen/qwen3-embedding-8b` (1024 dims) + `cohere/rerank-4-fast`. Say if you want different ids.
4. **§6** — a `downloads` listing tool returning names and sizes, plus `download` capped at 100 MB? And
   read-only means read-only, no delete.
