# The tool API: 17 reports, and what each one is asking for

`trace-1264-1000001264.log.txt`, builds 1285 (10:03–11:09). Every report in that stretch is about the
**agent's tool list** — not the UI, not the agent loop. I have read all of them, checked the current code
against each, and **all 17 are still open**. Nothing in this batch has been answered by a commit.

Read alongside `REPORTS-2026-11-03-v1264.md`, which covers the earlier eleven (those are closed).

## Where the 17 stand, checked against the code

| # | Report, in your words | State | Why |
|---|---|---|---|
| 22 | `"1 session" row is still visible - remove it` | **OPEN, UI** | Not a tool report. Belongs with the project-screen batch. |
| 23 | `I should be able to clone/move to the downloads - add this as a choice` | **OPEN** | `downloads` tool exists (outbound only); the ⋮ *Clone* menu has no Downloads destination. |
| 24 | `search query is regex or just text? … if we search via reranking why do we need regex` | **OPEN — needs your answer first** | **It is regex, and there is no reranking.** See §1. |
| 25 | `why output paths for search 10-100 instead of >=1 with default set to 10?` | **OPEN** | `DEFAULT_PATHS = 10`, `MOST_PATHS = 100`, `coerceIn(1, 100)` — the floor is already 1. §2. |
| 26 | `tree has arguments name and grep? rename them in name and content` | **OPEN** | KEYS still `name`, `grep`. |
| 27 | `why tree command has from and to arguments` | **OPEN** | KEYS still `from`, `to`. |
| 28 | `history command doesnt need from and to args` | **OPEN** | KEYS still `from`, `to`. |
| 29 | `merge create and edit into one command "write"` | **OPEN** | Both `create` and `edit` still exist. |
| 30 | `remove from and to args from commands` | **OPEN** | 5 tools still take them. |
| 31 | `also remove "at" flag` | **OPEN** | `edit` still takes `at`. |
| 32 | `for write command what does replace flag mean?` | **OPEN — needs your answer first** | §3. |
| 33 | `regex selection allows fine per symbol selection granularity, right?` | **OPEN — a question, answered in §4** | |
| 34 | `move doesnt need "name" arg` | **OPEN** | KEYS still `path`, `into`, `name`, `copy`. |
| 35 | `Remove from and to arguments only if line selection can be done with regex` | **OPEN — needs your answer first** | §5. |
| 36 | `make it so that fetch can load up to 100MB` | **OPEN** | `Fetched.LIMIT_BYTES = 20 MB`. |
| 37 | `what is downloads command? shouldn't it be 2 commands: download and upload` | **OPEN** | One tool, outbound only. §6. |
| 38 | `make archive simpler: -u (flag to unarchive) path` | **OPEN** | `archive` takes `pack`/`as`/`unpack`/`into`. §7. |
| 39 | `why system prompt still tells about ask and conclude - they were removed` | **OPEN — and it's a real bug** | §8. |

Three reports (24, 32, 35) and one question (33) **cannot be settled by me** — they are forks in the
design where guessing is worse than asking. Those are §1, §3, §4, §5. The rest I can build as stated.

---

## §1 — Report 24: "search is regex or text? if we rerank why regex?"

**The premise is wrong, and that is the answer.** There is no reranking in `search`. The embedder exists
(`Embedder.kt`, `SearchIndex.kt`) and **the agent's `search` does not use it** — it is a plain
case-insensitive regex walk over every text file in the account (`AgentTools.searchTool` →
`walkForSearch`). The only place the word "embed" appears in `AgentTools.kt` is a doc comment quoting
your earlier instruction, and that instruction was never built this way.

So the regex is not a redundant second mechanism on top of a semantic one. It *is* the mechanism.

That leaves a real fork, which is why this needs you:

**Option A — keep it literal, and stop calling it semantic.** Rename nothing, but fix the doc comment
that quotes the unbuilt rule, and never speak of reranking. Regex is genuinely good at what an agent asks:
"where is this identifier", "which file mentions this path". It is deterministic, instant, works offline,
and costs nothing.

**Option B — build the semantic search that was described.** The embedder and the index are both there
and unused by tools. `search` would send the query for embedding, rank files, then use regex only inside
the winners. Costs: an OpenRouter call per search, a few seconds per query, and a stale-index problem the
agent must be told about.

**My recommendation: A now, B as an explicit later project.** The agent's searches in this log are all of
the form "find the file that mentions X" — a reranker does not beat a regex at that, it just makes it
slower and occasionally vague. But it *is* your call, because "all text files are embedded" is your rule
and I am proposing not to build it.
@ regex is used in "tree", search should be semantic. and set default embedding and rerank model from openrouter catalogue (cheap and reliable ones).

---

## §2 — Report 25: "why 10-100 instead of >=1 with default 10?"

The floor is already 1 — `coerceIn(1, MOST_PATHS)`. If you typed 3, you got 3. **The only real limit is
the ceiling of 100**, and I think the reason it felt like a hard range is that the spec text says
`"paths":10` with no mention that 1 works.

**Proposal:** keep the ceiling at 100, and say so in the spec — *"`paths` is how many files to answer
with: 1 or more, 100 at most, 10 by default."* The ceiling stays because each answer is a path the agent
reads next, and 1000 paths is a context filled with filenames. If you want the ceiling gone, say so and it
goes, but I would keep it.
@ ok, keep 100 max for search results

---

## §3 — Report 32: "for write what does replace mean? shouldn't write replace selection by default?"

Today `edit` has two mutually exclusive shapes, and this is genuinely confusing reading:

- **`{"path":…, "grep":…, "replace":…}`** — regex replace. `replace` is the **new text**. Replaces
  *each match* of `grep`.
- **`{"path":…, "from":1, "to":5, "text":…}`** — line replace. `text` is the new text; `replace` is not
  involved.
- **`{"path":…, "text":…}`** with no selector — the whole file becomes `text`.

So `replace` means "the replacement string" and nothing else. It does not mean "a boolean: replace or
append". I suspect you read it as the latter.

**The fork:** your report 29 asks for `create`+`edit` to become one `write` that creates when the path is
missing. Once there is one `write`, what does it do when the file *does* exist and no selector is given?

- **Option A (I recommend):** whole-file replace. `write` with `path` + `text` and no selector means
  "this file now says exactly this". Creating and overwriting are the same act, which is what "if path
  doesn't exist it creates it" implies.
- **Option B:** refuse. "The file exists — name a selector." Safer against a truncated file, but it makes
  the create case and the overwrite case two different sentences for one tool.

With A, `replace` needs no rename at all: it is only ever the replacement string in the regex shape, and
the confusion dies with the merge, because there is no longer a `create` whose semantics could be read
into it.
@ ok, option a. I just asked why we even show replace as argument if we already have "text" argment and write command always means replace?

---

## §4 — Report 33: "regex selection allows fine per symbol selection granularity, right?"

**Yes — down to one character.** `grep` is a Kotlin/JVM regex matched inside a single line, and
`read {grep}` with a pattern matching *part* of a line returns **exactly the matched characters**
(`hits`, `findAll`), which is what you paste into `replace`. So a selector can be `\d+`, or `(?<=foo)bar`,
or a single `.` — there is no word-boundary floor and no tokenisation anywhere in the path.

Two honest limits, because "per symbol" oversells it slightly:

1. **It cannot cross a line boundary.** `grep` is applied per line, so a match that spans two lines is not
   expressible. That is deliberate — a regex engine run over a whole file with `.` allowed to match
   newlines turns a typo into a 40 MB match.
2. **It is not a selection you can see before it happens.** `read {grep}` shows the matches, so the safe
   shape is *read the matches, then replace them* — which is exactly what the tool descriptions tell the
   agent to do.
   @ why cant I grep a selection over multiple lines? is it limitation of regex or our restriction? If agent needs to replace a function block by grepping its signature and curly braces how doe he do that?

---

## §5 — Report 35: "remove from/to only if line selection can be done with regex"

This is the one where I want to be blunt: **line selection cannot be done with regex, so `from`/`to` cannot
be removed on that condition.**

The reason is the same limit as §4: a pattern matches *inside one line*, so there is no pattern that means
"lines 40 through 60". Regex can express *content*, never *position*. `^(?:.*\n){39}` fails for the reason
above (it needs `.` to cross newlines, and enabling that is how you get pathological matches).

So the two are not alternatives, they are two different selectors:

| selector | answers | can express |
|---|---|---|
| `grep` | *what the text says* | any content, down to one character |
| `from`/`to` | *where in the file* | a line range, down to one line |

**Recommendation: keep `from`/`to` — but stop making every tool take them.** That is the real complaint
behind 27, 28, 30. You are right that `history` does not need them and `tree`'s are odd. Put line ranges
where a line range is the natural selector (`read`, `write`), and drop them everywhere else. That answers
"remove from and to args from commands" without pretending regex replaced a positional selector.
@ I was just thinking whether we even need line selection when we have grep - the only question is whether it can span and detect line breakes on its own. if so then we dont even need to know what lines are - we can grep everything by pattern.

---

## §6 — Report 37 + 23: `downloads` is half a tool

You are right, and this is the clearest defect in the batch. Today:

- **Outbound exists.** `downloads {path}` copies one project file into the phone's Downloads. A person can
  open it. It is the only way a file leaves.
- **Inbound does not exist at all.** Every `read`/`edit`/`write` is bounded by `withinProject`, which is
  the app's **private** account directory. Downloads is outside it. The agent **cannot see a file you put
  there.**
- **And it cannot be reached round the side.** `downloads` explicitly refuses a folder and refuses any path
  outside the project.

So the answer to your earlier question — *"can muffycode agents see the downloads folder like you do?"* —
is **no**. They can write to it and cannot read from it. That asymmetry is the bug.

**Proposal: two tools, matching the two directions, with different risk.**

- **`upload {path}`** (project → Downloads): exactly what `downloads` does today, renamed. Same
  `withinProject` bound, same folder refusal.
- **`download {name}`** (Downloads → project): **read-only on the Downloads side, and it must be.** The
  shape I propose:
  - `name` matches a file **the app itself put there first** — i.e. a row in Downloads whose name is in a
    known list, or that the person picked through the system file picker. Not "any file in Downloads":
    that folder holds every download from every app, and an agent that can read it can read your bank
    statement PDF.
  - The file lands in `agent/downloaded/<name>` in the project. Never overwrites a project file
    directly — a second visible step is what stops a download quietly becoming an edit.
  - A refusal that names the alternatives when the name is not one the app is allowed to reach.

**The question I need you to answer:** is `download` worth having at all, given that the person can already
put a file into the project by other means? I think yes for one case — *"here is a file from another app,
summarise it"* — but it is the single most dangerous capability in the list, so I would rather you said
yes explicitly than have me add it because it seemed symmetric.
@ worth, I want to give agent access to device downloads folder, readonly for all files and with ability to download and upload.

---

## §7 — Report 38: `archive` is four arguments for two acts

Today: `{"pack":[…], "as":"x.zip"}` or `{"unpack":"x.zip", "into":"folder/"}`.

Your proposal: **`-u` as a flag, one `path`, output lands beside the input with the same name and a
different suffix.** That is much smaller and I agree with it. Concretely:

- **Pack:** `{"path":"notes/"}` → `notes.zip` beside it. No `as`, no list. One folder or one file.
  (Losing "pack several files into one" is real but rare; if you need it, `path` can take a list —
  that is the smallest version of the old shape that survives.)
- **Unpack:** `{"path":"notes.zip", "unpack":true}` → `notes/` beside it.
- A produced file that already exists gets the next free name rather than overwriting, matching what
  `Export` already does for Downloads.

Naming: you wrote `-u`, which is a CLI flag. In this API the equivalent is a boolean key. I would call it
`unpack: true` rather than `u: true`, because a one-letter key is a CLI habit and every other key here is
a word. Say the word if you want literally `-u`.
@ ok

---

## §8 — Report 39: the system prompt still says `ask` and `conclude`. This is a bug.

Confirmed, and it is worse than stale text:

- `AgentTools.run` never dispatches them, and `KEYS` no longer contains them.
- But `AgentTools.kt:351` still documents them as terminating, and `AgentPrompt` still tells the model
  about them.
- **So the model is told two endings exist, and neither can be called.** A model that calls `ask` gets an
  unknown-tool refusal; a model that follows the prompt and expects a turn-ending tool finds none. That is
  a contradiction in the one text that shapes every request.

Fix: delete every mention of both from `AgentPrompt`, and correct the `AgentTools` doc comment that still
describes them as the loop's endings. This one needs no decision from you — it is simply wrong — and I will
do it first, before anything else in this document.
@ ok

---

## What I need from you

1. **§1** — literal search (A) or build the semantic one (B)?
2. **§3** — with one `write`, does a bare `path`+`text` replace the whole file (A) or refuse (B)?
3. **§6** — do you want inbound `download` at all?
4. **§7** — `unpack: true`, or literally `-u`?

Everything else I can start now: **§8 first** (a bug), then the argument clean-up (26, 27, 28, 30, 34),
then `write` (29, plus 32 once you answer), then `fetch` at 100 MB (36), then `archive` (38), then §2's
wording, then 23's Downloads destination in the ⋮ menu.

## Two things I am not proposing, and why

- **Report 31, `remove the at flag`** — that is inside §3's merge. `at` is the line-cursor for repeated
  edits; once `write` exists with `grep`+`replace` and whole-file replace, the cursor has no remaining job.
- **Report 22, the "1 session" row** — not a tool report. It goes with the project-screen work, and I
  have noted it there rather than here so it is not lost.
