# Counterproposal III — the answers, the corrections, and what is now cheap

Answers are inline in `COUNTERPROPOSAL-II-search-mcp-articles.md`. Three of them change the design
materially, one of them corrects me on a fact I got wrong, and one is a question back to me. This document
answers it, records what the corrections make cheap, and asks the small number of things still open.

---

## 1. The correction: there are catalogues, and I said there were none

> *"it seems that they are just inside different catalogues, we already have model picker screens and mechanics,
> generalize them to take catalogue path as an argument and implement for models, embedding and rerank."*

**The operator is right and I was wrong.** I checked `/api/v1/models`, found no embedding models, and concluded
there were none. There are three catalogues, and all three answer:

| catalogue | how | count |
|---|---|---|
| chat | `GET /api/v1/models` | 420 |
| embedding | `GET /api/v1/embeddings/models` | **38** |
| rerank | `GET /api/v1/models?output_modalities=rerank` | **10** |

**And the payloads are the same shape** — `id`, `context_length`, `pricing`, `architecture.output_modalities`,
`supported_parameters`. `parseModels` already reads those fields. So the generalization is not a parser, it is
**a URL**:

```kotlin
class OpenRouterCatalogue(private val key: () -> String?, private val path: String = "/api/v1/models")
```

That is the whole change to the catalogue layer, and it is the operator's design, not mine. `Catalogue` stays
an interface, `ModelInfo` stays one data class, and the picker screens take a catalogue instead of building one.

**What the picker must show differently, per catalogue** — and this is the only real work:

| | chat | embedding | rerank |
|---|---|---|---|
| `architecture.output_modalities` | `["text"]` | `["embeddings"]` | `["rerank"]` |
| `pricing.prompt` | per-token | per-token | **0** (charged per call) |
| `context_length` | the window | the input window | the query+documents window |
| "can call tools" | matters | **meaningless** | meaningless |

So the picker needs a **catalogue kind** that decides which columns are worth showing. Showing "tools: no" on
an embedding model is noise; showing "tools: no" on a chat model is a warning. That is a `when (kind)`, not a
second screen.

### 1.1 The measurement that makes the storage argument go away

> *"get it, we need a compact and cheap variant."*

It is better than compact — **the dimension is a request parameter**, verified live:

```
qwen/qwen3-embedding-8b  + dimensions:1024  →  1024 dims
qwen/qwen3-embedding-8b  + dimensions:2048  →  2048 dims
qwen/qwen3-embedding-8b  + dimensions:4096  →  4096 dims
```

So the operator can have the **better model and the compact storage**, which I presented as a trade-off. It is
not one. `baai/bge-m3` is 1024 natively and also fine; `qwen3-embedding-8b` at `dimensions: 1024` is the better
default because it is a stronger model at the same size.

**This changes the schema in one place:** the dimension is not a property of the model, it is a property of the
*choice*. `ModelConfig` must store it beside the model id, exactly as `ActiveModel` already stores
`contextLength` and `maxOutput` — and for exactly the reason its doc gives: *"the two places that build an
agent have no catalogue to ask."* The index stores the dimension it was built with, and a mismatch is
"re-index needed", not a crash.

**And there are free options**, verified: `nvidia/nemotron-3.5-embed-8b:free` (4096 dims, cost 0) and
`nvidia/nemotron-3.5-embed-1b:free` (2048 dims, cost 0). A person who wants a semantic index without paying
for it can have one. That is worth a line in the picker.

### 1.2 History keeps its embeddings — and I was wrong about it twice

> *"I think that versions should keep their embedding (because we already embed them when they are saved!) and
> we just need to use 1024 dims and add a special flag to search - 'history' so that results show not just
> filepaths but also version hashes. I believe history search will be important."*

Two things here, and the first is a correction to my correction:

**My 240 MB figure was for 4096 dims. At 1024 it is 60 MB**, and the operator's point is that those vectors
are *already being computed* — a version is embedded when it is saved, so keeping it costs storage and nothing
else. **No second pass, no second cost.** My argument was "history is the least useful half", and the answer is
"it is free, and it is the half nobody else can search".

**And `versionsOf` already returns exactly what history search needs:**

```kotlin
fun versionsOf(repo, path, limit, deadline): List<Pair<CommitInfo, String?>>
```

Every commit that changed a path, **with the file's contents at that commit**. The substrate exists; the index
just needs `(path, commitHash, chunk, vector)` instead of `(path, chunk, vector)`.

So the schema becomes:

```sql
CREATE TABLE chunks(
  path    TEXT NOT NULL,
  commit  TEXT NOT NULL DEFAULT '',   -- '' = the current file; a sha = that version
  hash    TEXT NOT NULL,              -- sha256 of the chunk text
  start   INTEGER NOT NULL,
  end     INTEGER NOT NULL,
  vector  BLOB NOT NULL,
  model   TEXT NOT NULL,              -- slug
  dims    INTEGER NOT NULL,           -- what this vector actually is
  PRIMARY KEY(path, commit, start)
)
```

**`hash` does double duty now, and this is the part worth stating:** a version's chunk and the current file's
chunk with the same text have the **same hash**, so the vector is stored **once** and referenced twice. A file
saved ten times with one paragraph edited stores ten changed chunks and one copy of everything else. **That is
what makes history affordable**: it is not "10× the storage", it is "the deltas".

**`history` as a search flag, per the answer:**

```json
{"query":"how were retries timed before","in":["src/"],"paths":5,"history":true}
```

`history: false` (default) searches current files and returns paths. `history: true` searches every version and
returns **`path@sha7`**, which is exactly what `restore` and `read {revision}` already accept — so a search hit
is directly usable as the next call, with no translation.

---

## 2. Injection scanning — the answer replaces "block" with "surgery"

> *"we can chunk the content and remove chunks that have prompt injections. for example jev can take up to 32
> questions with 256 samples for each, all in one request, I bet that other such models also allow to analyze
> text in blocks and remove most dangerous elements."*

This is better than my proposal and it changes the shape of the feature. I proposed *mark and require a second
call to act*; the answer is **remove the dangerous part and keep the rest**. That is strictly better because a
false positive costs a paragraph rather than a page.

**The mechanism this implies — and it is one request per tool result:**

```
result text
   → split into chunks (the same chunks the index uses, so one splitter)
   → one batched request: all chunks, each as a separate classification
   → drop the chunks scored above the threshold
   → stitch the survivors back together, and say what was removed
```

**What the stitched result must say** is the design question, and it is the one that matters:

> "This page was read. 2 of 7 sections were removed as suspected instructions aimed at you. What remains: …"

**Not silence.** A silently-shortened page is a page that lies about its own contents, and an agent that does
not know something was removed cannot tell the person "the part that would answer you was removed".

**Q2.1 — what threshold, and who sets it?** A guard model answers a probability. Removing at 0.5 will cut
legitimate prose about, say, prompt injection *itself* — which is a real subject for this project. My proposal:
**a per-account threshold in settings, default 0.8**, and the removed text is **kept in the record** (not in
the conversation) so a person can look at what was cut. That is the same split as `forget`: out of the
conversation, still in the log.
@ ok

**Q2.2 — does the scanner see the agent's own project?** The answer is *"foreign, browse and mcp"*. So `read` of
the project is not scanned, and `browse`/`mcp`/read of *another* project is. That is a clear rule and it needs
one thing the current code does not have: **`read` must know which project the path landed in**, which it does
not need today because everything is inside one project.
@ no need to scan other projects. and what about web tool, does it support downloading artifacts from web to background folder (such as archives, other git projects not from our account, etc)?

---

## 3. Read-only access — the answer is stricter than my proposal, and cleaner

> *"sure, only project must be readable, not secrets."*
> *"app state readonly access is ok, logs also — only truly confidential info should be hidden and it should be
> kept outside projects' folders, directly under account, hidden from everyone but the app itself."*

So: **other projects' content and their app state and logs are readable; the credentials are not, and they live
at the account root where nothing reads them.**

**This is already almost true, and that is the finding.** The keys are not in the account folder at all —
`CredentialStore` keeps them in `EncryptedSharedPreferences`, encrypted with an Android keystore key:

```kotlin
private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
// token(login) → decrypt(prefs.getString(tokenKey(login), null))
```

So the boundary the answer asks for is **not a new rule, it is the current state made explicit**:

```
readable, read-only                    not readable
────────────────────────────           ──────────────────────────────────
<account>/<project>/**                 nothing under <account>/ holds a credential
<account>/system/**
other projects' .muffy/ and logs       (keys are in SharedPreferences, not on disk here)
```

**One thing to check, because it would break this:** `model_conf.json` sits at the account root and
`ModelConfig` is written there. It holds the model choice, providers and effort — **not the key**. The key is
looked up by login from `CredentialStore`. So it is safe to leave readable, and it *should* be readable,
because an agent helping with model choice needs to see it.
@ ok, good observation

**Q3.1 — the refusal for a write into another project.** My proposal was to name the owning project. With
read-only across projects, this matters more, not less: the agent will *read* `other_project/src/x.kt` and then
try to `edit` it. The refusal must be:

> "`other_project/src/x.kt` belongs to another project. You can read it; changing it needs that project
> open. Say so if the change is needed."

That is a refusal that names the wrong thing (this project), the allowed thing (read it), and the way across
(open that project). The current message — *"That path is outside this project"* — would be false.
@ right

**Q3.2 — does `tree` list other projects?** It should be able to (`tree path=../other/`), because otherwise the
agent cannot discover what it may read. And `search` across all projects is then the same feature, which is
what *"folders as search filter flags"* already implies.
@ right, across all projects

---

## 4. Skills — the answer, and the question back to me

> *"skills are sort of continuation to system and project prompts, they are used to teach an agent how to act
> in specific situations and are used on demand and are loaded in context by reading — no special machinery
> either."*

That answers Q3.3 (title only, body by `read`) and it settles skills as **a folder of markdown files and
nothing else**. No registration, no manifest, no loader. The only machinery is the sentence in the system
prompt that says the folder exists.

**The question back to me:**

> *"you mean a folder for generic prompts written beforehand and stored for quick use?"*

Yes — and the distinction worth drawing, because it is what makes the layout work:

| | what it is | where it goes | in context? |
|---|---|---|---|
| **system prompt** | how to use this app | `muffycodes_data/prompts/system_prompt.txt` | **always**, locked |
| **project prompt** | how to work in this project | `muffycodes_data/prompts/project_prompt.txt` | **always**, editable |
| **type prompt** | how to work in this *kind* of project | `system/.../project_types/<type>/` | always, copied in |
| **skill** | how to act in *this situation* | `muffycodes_data/skills/<name>/SKILL.md` | **on demand**, by reading |
| **wiki article** | a fact worth finding later | `muffycodes_data/wiki/` | **on demand**, by search or reading |

**The rule that separates them:** *always* or *on demand*. The three prompts are read every turn and must
therefore be short. A skill and an article are read when they are relevant, so they can be long.

So it is a folder of prompts written beforehand — **but the "quick use" is the agent reading it, not the app
inlining it.** That is the same shape as everything else in the answer set: a convention and a `read`.
@ no type prompts. what I mean is just a list of user prompts that can be used by operator, he can just write "analize" (for example), and agent can look at how to analize properly (it is similar to skills but simpler, just a kind of dictionary what explains what particular terms mean, no special machinery).

---

## 5. No migration

> *"no migration, app hasnt been published yet."*

Good — that deletes step 1's hardest part. The prompt files **move**, and nothing reads the old path. One
commit, no compatibility branch, no "read both, prefer new" rule to carry forever.

**But one thing must still be handled**, and it is not migration: a project on the developer's own phone
already has `project_prompt.txt` at its root. After the move, the app reads
`muffycodes_data/prompts/project_prompt.txt`, finds nothing, and **writes the default** — which would sit
beside the old file rather than replacing it. My proposal: on first open, if the old root file exists and the
new one does not, **move it** and say so once in the trace. That is not a compatibility layer; it is a
one-line cleanup that stops the developer's own notes from being shadowed by a default.

---

## 6. What the answers make cheap

| Was | Now | Because |
|---|---|---|
| A new catalogue parser | **one URL parameter** | the operator was right about the catalogues |
| A storage trade-off (better model vs smaller) | **neither** — `dimensions: 1024` | dimension is a request parameter |
| History is unaffordable | **free** — versions are embedded when saved, deduped by hash | the answer |
| Block or allow a suspicious result | **cut the bad chunks, keep the rest** | the answer |
| A `.muffy/` exclusion rule to invent | **already true** — keys are in SharedPreferences | the code |
| Migration code | **none** | unpublished |

---

## 7. The build order, revised

| # | Step | Size | Note |
|---|---|---|---|
| 1 | `muffycodes_data/` layout + move the prompts (move, not migrate) | **S** | no compatibility branch |
| 2 | Read-only outside the project + the new refusal wording | **M** | needs `read` to know the owning project |
| 3 | Notices: red, after the triggering message, removed on the next | **S** | flip to `out`, like `forget` |
| 4 | `wiki/`, `skills/`, `mcp/` folders + the system-prompt paragraph | **S** | conventions only |
| 5 | `search`, **keyword**, folder filters, `paths` | **S** | may prove embeddings unnecessary |
| 6 | Catalogue paths: one parameter, three pickers | **M** | the operator's design |
| 7 | Embeddings + rerank + the index, **with history** | **L** | the schema in §1.2 |
| 8 | MCP over `background` | **L** | wants 4 and 6 |
| 9 | The chunk scanner | **M** | wants 8 (it is for foreign results) |

**Step 6 before 7, not after** — because the pickers are what a person uses to choose the embedding model, and
building the index against a hardcoded model means rebuilding it when the picker lands.

---

## 8. Still open

| # | Question | My answer |
|---|---|---|
| **Q2.1** | The scanner's threshold, and where a removed chunk goes | **0.8 default, per-account**; removed text stays in the log, not the conversation |
| **Q2.2** | Does `read` of *another project* get scanned? | Yes — it is foreign. Needs `read` to resolve the owning project |
| **Q3.1** | The refusal wording for writing into another project | "belongs to another project; you can read it; changing it needs that project open" |
| **Q3.2** | May `tree`/`search` cross project boundaries by default? | Yes, read-only; the filter flags decide |
| **Q4.1** | Default embedding model | **`qwen/qwen3-embedding-8b` at `dimensions: 1024`** — stronger than bge-m3 at the same size |
| **Q4.2** | Does the free tier get offered in the picker? | Yes, marked `free` — a person may want an index without paying |
| **Q4.3** | Is `history: true` the flag name? | Yes, and it returns `path@sha7`, which `read {revision}` already accepts |

The two I need before step 7: **Q2.1** (the threshold — it decides whether the scanner is a setting or a
constant) and **Q4.1** (the default model — it is the dimension every existing index is built at).

---

## 9. Corrections recorded

1. **I said there were no embedding or rerank models on OpenRouter.** There are 38 and 10, in catalogues I did
   not look for. The operator's design — catalogue path as an argument — is the fix.
2. **I presented a storage-vs-quality trade-off that does not exist.** `dimensions` is a request parameter.
3. **I argued history was too expensive to index.** It is free: versions are embedded when saved, and the hash
   rule stores an unchanged chunk once.
4. **I proposed blocking suspicious results.** Cutting the offending chunks is better and cheaper.
5. **I proposed a `.muffy/` exclusion rule.** The keys are not in the account folder; the rule was already
   satisfied by the code.
