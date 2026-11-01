# Are there any questions left?

The answer is: **no blocking questions.** Six answers landed, all of them decisive, and between them they
close every question the four documents opened. This page records what each answer settles, then names the
handful of things that are not questions but **decisions I am taking on the operator's behalf**, so they are
visible rather than buried.

---

## 1. Every question, and its answer

| # | Question (doc) | Answer | Settles |
|---|---|---|---|
| Q1.1 (I) | Notice scope: per-request or per-session? | *"added as the last message after the one that triggered them… removed with the next message or burst"* | **Neither list.** A notice lives from the row that raised it to the next message. One rule. |
| Q1.3 (I) | Notice styling? | *"ok, with red background"* | Red, full width |
| Q2.1 (I) | SSRF check on MCP endpoints? | *"ok"* | Same `Addresses.problem`, same opt-in |
| Q2.2 (I) | What may an MCP server cause? | *"mark any foreign data inside a marked container… plus a scanning model"* | Marked containers + chunk removal |
| Q2.3 (I) | MCP timeouts? | *"shouldn't we just use `background`"* | **`background` — the deadline machinery is deleted** |
| Q3.1 (I) | Chunk by lines or meaning? | *"ok"* | Overlapping line windows |
| Q3.2 (I) | Binaries? | *"embed only text files' contents, titles are embedded for all files"* | Filenames indexed, contents only for text |
| Q3.4 (I) | Do articles enter context? | *"system prompt should instruct how to use wiki"* | Convention + one paragraph |
| Q4.1 (I) | Skill body in context? | *"loaded in context by reading — no special machinery"* | Title only, body by `read` |
| Q2.1 (II) | Delete a notice or flip it? | *"removed from chat message list and session log"* | Flip to `out`, like `forget` |
| Q2.2 (II) | Scanner scope? | *"foreign, browse and mcp"* then *"no need to scan other projects"* | `browse` and `mcp` only |
| Q2.4 (II) | Another project's `.muffy/`? | *"app state readonly access is ok, logs also"* | **Readable** |
| Q3.1 (II) | Default embedding model? | *"compact and cheap"* → `dimensions: 1024` | `qwen3-embedding-8b` at 1024 |
| Q3.2 (II) | What is a "preset"? | *"you mean a folder for generic prompts…?"* → glossary | **No type prompts** |
| Q4.1 (II) | Migration? | *"no migration, app hasnt been published yet"* | None |
| Q2.1 (III) | Scanner threshold? | *"ok"* (0.8 default, cut chunks, keep the removed text in the log) | 0.8, per-account |
| Q3.1 (III) | Write-into-another-project refusal? | *"right"* | Names the owner, the allowed thing, the way across |
| Q3.2 (III) | May `tree`/`search` cross projects? | *"right, across all projects"* | Yes, read-only |
| Q1.1 (IV) | Where does a `fetch` land? | *"everything can land there"* + background unsynced/gitignored | `agent/`, always |
| Q1.2 (IV) | Does `fetch` extract? | *"we do need a tool to archive and to unpack archives"* | **A separate archive tool** |
| Q1.3 (IV) | Foreign git repo? | *"only tarball fetch"* | Tarball; no clone |
| Q2.1 (IV) | Is a fetched archive scanned? | *"we dont scan archives… android scans them"* | No |
| Q3.1 (IV) | `glossary/`? | *"for me dictionary is clearer"* | **`dictionary/`** |
| Q3.2 (IV) | Invoke or notice? | *"noticing"* | One sentence in the system prompt |

**Nothing in that table is still open.** The four documents asked 23 questions across them and every one has an
answer.

---

## 2. The answer that changes the layout most

> *"I think that background and files inside it should be unsynced and gitignored (we still keep .gitignore file
> inside projects and they can be used on top of unsync, right?) either way — everything can land there."*

**Yes, and the code already works this way — but not the way the question assumes.** There are **two** ignore
mechanisms and they do different jobs:

| mechanism | who writes it | what it does | survives a clone? |
|---|---|---|---|
| `.git/info/exclude` | **the app** (`ensureIgnore`) | hides `agent/`, `.muffy/`, `requests/` from `git status` | **no** — it is inside `.git` |
| `.gitignore` | **the person** (`ignoreInGit`) | the project's own rules | yes |
| `.muffy/unsynced.json` | **the person** (the sync toggle) | paths left out of the **push** | no, it is app state |

So the operator's sentence is exactly right and the app is already built for it: **`agent/` is in the app's
exclude list**, the person's `.gitignore` layers on top (git honours both), and unsync is a third, stronger
rule for a push. Nothing needs to change for `fetch` to land in `agent/`.

**One thing does need to change**, and it is a real decision rather than a question: `fetch` writing into
`agent/` means a fetched archive **never reaches the repository**. The operator said *"everything can land
there"* — so vendoring a dependency is not a `fetch` into `vendor/`; it is `fetch` into `agent/`, then
`unpack` into the project. That is a cleaner story than my `into` parameter: **`fetch` has no destination
choice, and the archive tool is what puts files into the project.**

---

## 3. The answer that exposes a broken instruction

> *"ok, we do need a tool to archive and to unpack archives."*

**Right — and it is worse than a missing feature: the app already tells the agent to do something it cannot
do.**

`downloads`' description says:

> *"A folder is refused — a project is a tree and Downloads is one flat folder — so **zip it first** if you
> mean several files."*

There is **no zip tool.** `grep` for `ZipOutputStream` across the whole app finds nothing; the only zip code is
`java.util.zip.ZipFile` in `artifact`, which **reads**. So for however long that sentence has been there, the
agent has been told to zip files and has had no way to comply — and a model told to do the impossible either
invents a shell command (there is no shell) or gives up.

**That is a bug found by the operator's answer, not a new feature.** The archive tool fixes it, and it is worth
fixing early because it is an instruction the app currently gives and cannot honour.

---

## 4. Decisions I am taking, stated so they can be overruled

These are not questions — the answers above settle them — but they involve a judgement the operator has not
seen, so they are listed:

**D1 — `dictionary/` is per-account core plus per-project additions, in one folder each.** The operator said
*"system is shipped with some general core ones, projects grew their specialized ones"*. Two folders with one
name is what the wiki answer already established; `dictionary/` follows it. Search takes both by default.

**D2 — the archive tool is `pack` and `unpack`, one tool with a mode, not two tools.** Two names in the tool
list is two descriptions in every request's cache prefix, for one capability. `{"unpack":"agent/fetched/x.zip","into":"vendor/x"}`
and `{"pack":["src/","README.md"],"as":"agent/out.zip"}`.

**D3 — `unpack` refuses absolute paths and `..` in entry names.** Zip-slip is the one way an unpacker writes
outside its destination, and the refusal must name the entry. This is not negotiable and it is not a question.

**D4 — the archive tool reports entry names on `unpack`, the same shape `artifact` returns.** *"an artifact is a
zip, and a zip in this conversation is binary that costs the whole context to say nothing"* — the same
reasoning applies to an unpacked one.

**D5 — `fetch` has no `into` parameter.** §2 above: it lands in `agent/fetched/`, always, and `unpack` is what
moves files into the project. This is simpler than what I proposed and follows from *"everything can land
there"*.

**D6 — the `downloads` sentence stays, because now it can be obeyed.** It gains one clause: *"use `pack` to
make the zip."*

---

## 5. What is left, in one list

**No questions.** The build order from Counterproposal IV stands, with two additions that the answers force:

| # | Step | Size |
|---|---|---|
| 1 | `muffycodes_data/` layout; move prompts; `project_types/` → `starters/` | S |
| 2 | Read-only outside the project + the new refusal wording | M |
| 3 | Notices: red, after the triggering message, removed on the next | S |
| 4 | `dictionary/`, `skills/`, `wiki/`, `mcp/` + the system-prompt paragraph | S |
| 5 | `search` keyword-only, folder filters, `paths` | S |
| 6 | Catalogue paths: one parameter, three pickers | M |
| 7 | **`pack` / `unpack`** — and it fixes the `downloads` instruction | **M** |
| 8 | **`fetch`** — bytes from a URL into `agent/fetched/` | M |
| 9 | Embeddings + rerank + index, with history | L |
| 10 | MCP over `background` | L |
| 11 | The chunk scanner, over `browse`/`mcp` | M |

Steps 1–8 are small-to-medium and independent. Steps 9–11 are the large ones.

---

## 6. One thing I would still ask, if asking were free

Not a design question — a sequencing one, and it is the only thing I would put to the operator before
starting:

**The four documents propose a lot of work, and none of it is the bug batch 85–96 that was in progress.**
Reports **86** (the `>>` icon sizes), **87** (swap pages between chat and fullscreen) and the per-paragraph
selection work in **88** are still open, and they are small. The search/MCP work is a week of commits that
will make the app bigger in every direction.

So the question is not *what* but *when*: **finish 86–88 first, or start on step 1 (`muffycodes_data/`) and come
back?** My answer: **finish 86–88**, because they are small, they are already reported, and a batch that sits
half-done while a large feature lands is the batch that gets forgotten.
