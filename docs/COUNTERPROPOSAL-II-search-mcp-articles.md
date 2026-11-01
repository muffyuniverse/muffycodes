# Counterproposal II — the answers, the measurements, and one finding that changes the plan

Answers are in the margins of `COUNTERPROPOSAL-search-mcp-articles.md`. This document does four things:

1. records what each answer settles,
2. reports **a measurement that contradicts a premise of the request** (§1),
3. proposes the `muffycodes_data` layout the answers ask for, and says what it does to the code that exists,
4. asks the questions the answers opened rather than closed.

---

## 1. The finding: OpenRouter serves embeddings and rerank, but does not list them

The request says *"we need to add embedding and rerank model selection from openrouter to settings"*. That is
still true, and it is harder than it looks, because of this:

```
GET  /api/v1/models          → 420 models, and NONE of them is an embedding or rerank model
POST /api/v1/embeddings      → works
POST /api/v1/rerank          → works
```

**The catalogue the app already reads cannot discover them.** `ModelCatalogue.model(id)` asks `/models`, so an
embedding model chosen in settings would be a model the catalogue says does not exist — and
`ModelConfigFile` refuses a config it cannot parse rather than silently replacing it, so a person who typed
`qwen/qwen3-embedding-8b` would get a refusal that is wrong.

Verified live, all of these **work** through `/api/v1/embeddings` despite being absent from `/models`:

| model | dims | cost / chunk (~230 tok) |
|---|---|---|
| `openai/text-embedding-3-small` | 1536 | $0.0000046 |
| `qwen/qwen3-embedding-8b` | **4096** | $0.0000023 |
| `qwen/qwen3-embedding-4b` | 2560 | — |
| `google/gemini-embedding-001` | 3072 | — |
| `baai/bge-m3` | 1024 | — |
| `intfloat/e5-large-v2` | 1024 | — |

And rerank, verified live:

| model | cost / call |
|---|---|
| `voyageai/rerank-2.5-lite` | $0.0000002 |
| `voyageai/rerank-2.5` | (same tier) |
| `cohere/rerank-v3.5` | $0.001 |
| `cohere/rerank-4-fast` | $0.002 |
| `baai/bge-reranker-v2-m3` | **does not exist** |
| `jina/jina-reranker-v2-base-multilingual` | **does not exist** |

**Three consequences, and the first is a design change:**

**1.1 — The embedding and rerank pickers cannot be the model picker.** The model picker is built from the
catalogue, and the catalogue has no entry for these. Either the app ships a **fixed list of known slugs** with
their dimensions, or the person types an id and the app *probes* it (`POST /embeddings` with one word) to learn
the dimension. My proposal: **a fixed list with a "something else" field that probes**, because the list is
five names that change rarely and a probe costs a fraction of a cent.
@ it seems that they are just inside different catalogues, we already have model picker screens and mechanics, generalize them to take catalogue path as an argument and implement for models, embedding and rerank. Same structure as we already use: model + provider with some predefined and ability to add new ones by their openrouter model names.

**1.2 — Dimension is not a constant, so the schema must store it.** `qwen3-embedding-8b` is 4096 dims and
`bge-m3` is 1024. A project indexed under one and searched under the other is not a wrong answer, it is a
crash — the vectors are different lengths. The `model` column in §3.3 of the first document was right and is
now **load-bearing rather than defensive**: a row whose model does not match the current choice is not
comparable, and the tool must say "re-index needed" instead of scoring it.
@ get it, we need a compact and cheap variant.

**1.3 — 4096 dims is 16 KB per chunk, not 4 KB.** My storage arithmetic in the first document was wrong by 4×
for the best-value model. Corrected:

| | 1024 dims | 1536 | 4096 |
|---|---|---|---|
| one chunk | 4 KB | 6 KB | **16 KB** |
| 500-file project (~1,500 chunks) | 6 MB | 9 MB | **24 MB** |
| + history (5,000 versions) | 60 MB | 90 MB | **240 MB** |

**240 MB on a phone, for one project.** That does not change the recommendation — current files only — it
makes it mandatory rather than prudent. And it argues for `bge-m3` (1024 dims, $0.0000/chunk) as the default:
the ranking difference between it and a 4096-dim model is not worth 4× the phone's storage.
@ I think that versions should keep their embedding (because we already embed them when they are saved!) and we just need to use 1024 dims and add a special flag to search - "history" so that results show not just filepaths but also version hashes. I believe history search will be important.

**1.4 — cost, measured, so nobody has to guess.** A 500-file project is roughly 1,500 chunks ≈ $0.0035 to
index with `text-embedding-3-small`. An edit to one file is one or two chunks ≈ **$0.00001**. Reranking 50
candidates is $0.00001. **The whole feature costs less per day than one chat message.** The argument against it
was never price; it is storage and the failure modes.

---

## 2. What each answer settles

### 2.1 Notices — settled, and the mechanism simplifies

> *"app error messages should be separate from agent's or user's messages, they are added as the last message
> after the one that triggered them to show up, and they are removed from chat message list **and session log**
> with the next message or burst page from user or agent."*
> *"ok, with red background."*

Two things changed from my proposal:

- **The notice is a message-shaped thing, not a line between messages.** It is placed *after* the message that
  triggered it, which is a position in the list, not a decoration on a row.
- **It is removed from the session log too**, not merely hidden from the chat. That is stronger than
  "derived": the operator wants the row gone.

**And "see q1.1" answers the trimming question by making it moot** — if a notice is removed when the next
message arrives, whether trimming would have removed it later is not a question anyone has to answer.

**But "removed from the session log" and "nothing is rewritten" cannot both be true**, so this needs a
decision, and it is Q2.1 below.

### 2.2 MCP — three of four settled, one answered with a better idea

| Q | Answer | What it means |
|---|---|---|
| Q2.1 SSRF | *"ok"* | The same `Addresses.problem`, same opt-in |
| Q2.2 what a server may cause | marked containers + **a scanning model** | See §2.3 |
| Q2.3 timeouts | *"shouldn't we just use `background`"* | **Yes — and it deletes a design** |
| Q2.4 does `system/` sync | *"yes"* + **read-only access to other projects** | See §2.4 |

**Q2.3 was a good catch and it removes work.** `Background.start(project, call, work)` already writes a
running file, runs the work off the turn, and returns a path — and its doc says a background result and an
oversized result are *"the same result in the same place, so there is one answer the agent has to learn"*.
MCP needs no deadline machinery of its own: **`mcp` is a tool, so `background {"tool":"mcp", ...}` already
works**, provided `mcp` is in `KEYS` and `Background` accepts it. The only addition is a per-server deadline in
the article, used as the inner deadline — which is what `backgroundMs()` already does for every other tool.

### 2.3 The injection scanner — the answer that adds a model, and where it belongs

> *"we can also add a scanning model, like jev, that can scan the response on potential malicious prompt
> injections and return probability score that we then can use to block dangerous responses"*

OpenRouter does carry guard models, verified live:

| model | $/1k in | $/1k out |
|---|---|---|
| `nvidia/nemotron-3.5-content-safety` | $0.0002 | $0.0002 |
| `openai/gpt-oss-safeguard-20b` | $0.000075 | $0.0003 |
| `meta-llama/llama-guard-4-12b` | $0.00018 | $0.00018 |

**The design question this raises is not "which model" but "what happens on a hit".** A probability score is
not a decision. Three options, and they differ in what a false positive costs:

- **block** — a legitimate page that trips the scanner is unreadable, and the agent cannot tell why
- **mark and continue** — the agent sees the score and decides, which puts the decision on the model that is
  being attacked
- **mark, continue, and require a second call to act** — the agent may read anything, but an `edit`/`delete`
  that follows a high-score result in the same turn gets refused once

**My recommendation: the third.** It matches the app's existing shape — the model must call a tool to act, and
`mayWrite` is already the gate — and it is the only one where a false positive costs a retry rather than a
blind spot. It also needs no new concept: the score is written on the row, and `AgentTools` can see the
previous row.
@ I think that we can chank the content and remove chanks that have propmpt injections. for example jev can take up to 32 questions with 256 samples for each, all in one request, I bet that other such models also allow to analyze text in blocks and remove most dangerous elements.

**Where the scanner runs is a separate decision** and it is the expensive one: on every tool result, or only on
foreign results (`browse`, `mcp`, and anything from outside the project)? Scanning the agent's own `read` of
its own project is paying a model to inspect text the person wrote.
@ foreign, browse and mcp.

### 2.4 Read-only access to other projects — the biggest change in the answers

> *"any project's agent gets readonly access to account folders outside his project (other projects, including
> system)"*

This is a real capability change and it collides with two existing rules:

**Collision 1 — `resolve()` refuses anything outside the project.** It canonicalises and compares prefixes, and
its refusal is "That path is outside this project". So `read ../../other_project/x` fails today by design.

**Collision 2 — and this is the dangerous one — the account root holds the credentials.** The keys are not in
a project, they are in the account. If `read` accepts account-relative paths, the first thing a prompt-injected
agent reads is the key file. The app's whole `Secrets.scrub` machinery exists because that text must never
reach a conversation.

So the answer needs a **boundary**, and my proposal is:

```
readable (read-only)            not readable, ever
─────────────────────────       ──────────────────────
<account>/<project>/**          <account>/.muffy/**
<account>/system/**             any *.json under .muffy
                                the credential store
```

**Read-only means `read`, `tree` and `search` — not `edit`, `create`, `delete`, `move`, `restore`.** And the
refusal for a write must name the project that owns the file, so the agent can tell the person "this is in
`other_project`, I cannot change it from here" rather than failing with "outside this project".
@ sure, only project must be readable, not secrets.

**Q2.4 — Does read-only access extend to another project's `.muffy/`?** My answer: no, and the reason is that
`.muffy/` is *state*, not content — a lock file, a sync marker, the request database. An agent reading another
project's request database would see the person's spending, and one reading another project's agent log would
see a conversation it was not part of. **That is a privacy boundary that happens to be a folder.**
@ app state readonly access is ok, logs also - only truely configential info should be hidden and it should be kept outside projects' folders, directly under account, hidden from everyone but the app itself.

### 2.5 Search — settled, with two corrections

> *"embed only text files' contents, titles (filenames) are embedded for all files"*
> *"both should exist"* (two wikis)
> *"system prompt should instruct how to use wiki, and wiki is just a folder (convention, not a mechanism)"*
> *"search should return filepaths (and there should be a flag that agent sets on how many paths are shown)"*
> *"all text files are embedded and searched the same way with folders as search filter flags"*
> *"no special treatment"*

**"No special treatment" is the strongest simplification in the whole set of answers**, and it deletes a
mechanism I proposed. I had three scopes (`project`, `wiki`, `system`). The operator's answer: **folders are
filter flags, not scopes.**

```json
{"query":"how are retries timed","in":["src/","wiki/"],"paths":10}
```

- **`in`** is a list of folder prefixes. Empty means the whole readable area.
- **`paths`** is the flag the operator asked for: how many paths to show. Not "k", not a score threshold — a
  count of file paths, because that is what the agent asked for and what it will `read` next.

**And "titles are embedded for all files"** means a binary file is findable by name and not by content — which
is the honest answer, and it means `search` can answer "where is the logo" without indexing a PNG.

**"Wiki is just a folder (convention, not a mechanism)"** is the answer that removes the most code. There is no
`wiki/` handler, no registration, no special read path. The system prompt says *"articles live in `wiki/`;
search before you guess"*, and that is the whole mechanism. **A convention costs one paragraph of prompt; a
mechanism costs a schema, a test, and a bug.**

---

## 3. The layout: `muffycodes_data`

> *"all projects should be spawned with muffycodes_data folder that contains all of this per subject folders
> (skills, wiki, prompts, workflows, mcp etc..) — system is shipped with some general core ones, projects grew
> their specialized ones."*

This replaces the layout I proposed in §4.1 of the first document. One folder name, per-subject inside it:

```
<project>/
  muffycodes_data/
    prompts/
      project_prompt.txt        ← ③ this project
      preset_prompt.txt         ← ② copied from the type, editable
    wiki/                       ← this project's articles
    skills/<name>/SKILL.md      ← this project's checklists
    workflows/                  ← the templates, before they go to .github/workflows/
    mcp/<name>.md               ← servers this project may use
    search/                     ← the index, or a pointer to it
system/
  muffycodes_data/
    prompts/system_prompt.txt   ← ① locked
    wiki/ · skills/ · mcp/ · project_types/
```

### 3.1 What this changes in the code that exists

**The good news: `muffycodes_data/` is a better home than what is there now, for three reasons already in the
code.**

1. **`APP_IGNORES = listOf("agent/", ".muffy/", "requests/")`** — a project's `.gitignore` already excludes the
   app's folders. `muffycodes_data/` is *content*, so it should **not** be ignored: it is the project's
   articles and prompts, and they belong in git and on GitHub. That is a one-line decision, and it is the right
   one — a wiki that does not sync is a wiki that dies with the phone.
2. **`isTemplatePath`** matches `system/project_types/` today. It becomes `system/muffycodes_data/`, still a
   prefix, still recursive.
3. **`AgentPrompt.PROJECT_FILE = "project_prompt.txt"`** moves under `muffycodes_data/prompts/`. The reader
   changes in one place because it already goes through one function.

**The cost, stated plainly: this moves files that exist.** Every project on every phone has a
`project_prompt.txt` at its root today. Moving it means either a migration (read the old path if the new one is
missing, and say so once) or a break. **My proposal: read both, prefer the new, and write only the new** — the
same "a file that exists is the person's" rule the prompt layer already follows, applied to a path instead of
a text.
@ mo migration, app hasnt been published yet.

**And workflows are the one thing that must not move.** `.github/workflows/` is where GitHub looks. A workflow
in `muffycodes_data/workflows/` is a template; the copy that runs is still in `.github/workflows/`. The layout
above says so, and it is the reason `workflows/` appears in both places.

### 3.2 Skills, and the answer that was not given

> *"we didnt discuss presets, the only place where I remember presets was statistics screen."*

This is a correction I have to accept: **"preset" is not a word in this project.** What I have been calling a
preset is the type's prompt and workflow, and the operator's memory of the word is the stats screen's interval
presets. The folder is real (`system/project_types/` exists in the tree, `Presets` and `ensurePresets` are
written) but the *name* is mine, not theirs.

So: **the word goes.** In this layout it is `muffycodes_data/prompts/preset_prompt.txt`, and if the operator
prefers "type prompt" or "the type's brief", that is the name. Q3.2 below asks.
@ you mean a folder for generic prompts written beforehand and stored for quick use?

**Q4.1 (skill body in context) was not answered**, and it is still the question that decides whether skills are
a prompt feature or a search feature. I will ask it again rather than assume.
@ skills are sort of continuation to system and project prompts, they are used to teach an agent how to act in specific situations and are used on demand and are loaded in context by reading - no special machinery either.

---

## 4. What I would build, in order, with the sizes

| # | Step | Size | Why first |
|---|---|---|---|
| 1 | `muffycodes_data/` folder + move the prompts + read-old-write-new | **S** | Everything else lands in this layout; moving later means moving twice |
| 2 | Read-only access outside the project, with `.muffy/` excluded | **M** | It is a security boundary, and every later tool inherits it |
| 3 | Notices (red, after the triggering message, removed on the next) | **S** | Independent of the rest; fixes a live complaint |
| 4 | `wiki/`, `skills/`, `mcp/` as plain folders + the system-prompt paragraph | **S** | Conventions cost a paragraph; the folders must exist before search has input |
| 5 | `search` with **keyword only**, folder filters, `paths` flag | **S** | Tells you whether embeddings are needed at all |
| 6 | Embedding + rerank, per §1 | **L** | Two model pickers that the catalogue cannot supply, a schema, a queue |
| 7 | MCP over `background`, with the scanner | **L** | Widest attack surface; wants the article format proven by 4 |

**Steps 1–5 are a week of small, independent commits.** Step 6 is the large one, and step 5 may make it
unnecessary — which is why it goes first and is allowed to be thrown away.

---

## 5. The questions these answers opened

| # | Question | Why it matters | My answer |
|---|---|---|---|
| **Q2.1** | *"removed from session log"* — delete the row, or flip it to `out` like `forget` does? | `forget` already flips `ctx` and keeps the row. Deleting loses the evidence that a notice was ever raised. | **Flip to `out`**, the same as `forget`: gone from the conversation, still in the record |
| **Q2.2** | Where does the scanner run — every tool result, or foreign only? | Scanning the agent's own project costs a model call per read | **Foreign only** (`browse`, `mcp`, anything outside the project) |
| **Q2.3** | On a high score, block, mark, or require a second call? | A false positive that blocks makes a page unreadable with no explanation | **Mark + require a second call to act** |
| **Q2.4** | Does read-only reach another project's `.muffy/`? | It holds keys, spending and other people's conversations | **No** — `.muffy/` is state, not content |
| **Q2.5** | Does `muffycodes_data/` sync to GitHub? | A wiki that does not sync dies with the phone | **Yes** — it is content, and it is not in `APP_IGNORES` |
| **Q3.1** | Default embedding model? | 4096 dims is 24 MB for 1,500 chunks vs 6 MB at 1024 | **`baai/bge-m3`** (1024 dims) — 4× the storage is not worth the ranking |
| **Q3.2** | What is the type's prompt called, if not "preset"? | The word is mine; the folder is real | Ask |
| **Q3.3** | Skill body in context, or title only? | Decides whether skills are a prompt feature or a search feature | **Title only**, body by `read` |
| **Q4.1** | Migration: does the old root `project_prompt.txt` keep working? | Every existing project has one at the root | **Read both, prefer new, write only new** |

The three I need before starting step 1: **Q2.5** (does `muffycodes_data/` sync — it decides whether the
folder is git content or app state), **Q3.2** (what the type prompt is called — it is the name in every path),
and **Q3.3** (skill body — it decides whether skills are a folder of checklists or a folder of prompt
fragments).

---

## 6. What I got wrong in the first document

Recorded so the corrections are visible rather than quietly absorbed:

1. **Storage was 4× optimistic** for the best-value model — 4096 dims is 16 KB per chunk, not 4 KB.
2. **The embedding models are not in the catalogue**, which I did not check. The picker design in the first
   document assumed `ModelCatalogue` could supply them. It cannot.
3. **I proposed three search scopes**; the answer is folder filters, which is simpler and better.
4. **I proposed a `wiki/` mechanism**; the answer is a convention and a paragraph of prompt.
5. **I proposed MCP deadline machinery**; `background` already is that door, and duplicating it was the error
   the operator named.
6. **I invented the word "preset"** for the type's prompt, and it is not a word in this project.
