# Counterproposal IV — the web tool's real gap, and what "no type prompts" means for the layout

Answers are inline in `COUNTERPROPOSAL-III-search-mcp-articles.md`. Three settle things, and one asks a
question that turns out to be **the most important question in the whole set**: whether the web tool can fetch
a file. This document answers it with what the code actually does, then follows the consequences.

---

## 1. "Does the web tool support downloading artifacts to the background folder?"

> *"and what about web tool, does it support downloading artifacts from web to background folder (such as
> archives, other git projects not from our account, etc)?"*

**No. There is no way to fetch bytes from an arbitrary URL today**, and the gap is structural rather than an
oversight. Here is exactly what exists:

| tool | what it fetches | lands where | bytes? |
|---|---|---|---|
| `browse` | a page's **text** (`Browser.text` → `Page.text`) | into the conversation | **no** |
| `shot` | a **PNG** of a page | `agent/browser/` | yes, but only a screenshot |
| `artifact` | **one file a CI run produced** — GitHub only | `agent/ci/artifacts/*.zip` | yes |
| `ci` / `runs` | workflow metadata | conversation | no |
| `downloads` | *exports* a project file outward | phone Downloads | outbound only |

`Browser` has exactly two methods — `text()` and `shot()`. There is **no `bytes(url)`**, so nothing in the app
can turn a URL into a file. `artifact` looks like the thing but is not: it takes a **run id**, goes through
`ciWhere()` to a GitHub repo the account owns, and its own refusal says *"Say which run"*. A URL is not a run.

**And "other git projects not from our account" is refused twice over:**

1. `cloneInto(project, remoteUrl, …)` clones **into the project's own folder**, and only when that folder has
   no repository yet — it is the "import an existing repo as this project" path, not a fetch.
2. Even if a URL were accepted, `Addresses.problem` refuses anything private unless the person opted in, and
   the token path is scoped to the active account.

### 1.1 What I propose — one tool, and it is the missing primitive

```text
fetch {"url":"https://example.com/data.zip","into":"agent/fetched/"}
fetch {"url":"https://github.com/other/repo","into":"vendor/other"}
```

- **It writes bytes, never text.** The answer is the path, the size, and (for an archive) the names inside —
  exactly the shape `artifact` already returns, because that shape was right: *"an artifact is a zip, and a zip
  in this conversation is binary that costs the whole context to say nothing."*
- **It runs through `background`**, which is the operator's own answer from the MCP round. A download is slow
  and `Background.start` already writes a running file and hands back a path. **No new async machinery.**
- **`Addresses.problem` applies**, the same call `browse` makes, because a fetcher is an SSRF surface with a
  worse payload than a page: a page can lie, a download can *write into the project*.
- **Size cap, and it is a refusal not a truncation.** A 2 GB ISO must not fill the phone. The refusal names the
  size and the cap, so the agent can tell the person rather than retrying.

**Q1.1 — where does a fetched file land?** `agent/` is already git-excluded (`APP_IGNORES`), so
`agent/fetched/` is invisible to commits — which is right for a scratch download and **wrong for a vendored
dependency** the project needs to build. My proposal: **`into` is required and names the folder**, so the
agent chooses:
- `agent/fetched/` — scratch, excluded from git
- `vendor/<name>/` — a real addition to the project, committed

Making it required is the point: a default would put a 40 MB archive in the project root and commit it.
@ I think that background and files inside it should be unsynced and gitignored (we still keep .gitignore file inside projects and they can be used on top of unsync, right?) either way - everything can land there.

**Q1.2 — does `fetch` unpack archives?** No. It reports the entry names (the `ZipFile` read `artifact`
already does) and the agent decides. An unpacker is a second tool with its own zip-slip defence, and *"list
what is inside"* plus `create`/`write` covers the real cases. **But that leaves no way to extract a tar or a
`.tar.gz`** — Q1.2 asks whether that matters.
@ ok, we do need a tool to archive and to unpack archives.

**Q1.3 — a foreign git repo: `fetch` or `clone`?** `fetch` gets a tarball from a URL. A git clone keeps
history and is what a person means by "vendor this library". My proposal: **`fetch` handles
`https://github.com/o/r/archive/refs/heads/main.tar.gz`**, and a real clone of a foreign repo is **out of
scope** — it needs a credential story (a foreign private repo) and a "does this become the project?" answer
that `cloneInto` already owns.
@ right, only tarball fetch

---

## 2. "No need to scan other projects"

> *"no need to scan other projects."*

Accepted, and it **simplifies** §2 of the last document: the scanner's scope is `browse` and `mcp`, full stop.
No `read`-crossing-project-boundary scan, which means `read` does **not** need to learn which project a path
landed in — the one piece of new plumbing I said the scanner required. That is deleted.

The rule becomes one line:

> **Text this app obtained from outside it is scanned. Text from the phone is not.**

`browse` and `mcp` are the only two tools that bring in text the app did not read from disk. A `fetch`ed
archive's *contents* are read from disk afterwards, so they are not scanned — **and that is a hole worth naming
plainly**: a downloaded archive is foreign text that arrives via `fetch` and then enters as a `read`. If the
scanner is worth having, `fetch` must be on its list, or a malicious archive is a way around it.

**Q2.1 — is `fetch` scanned?** My answer: **the archive is not, its extracted files are not, and that is a
decision to make deliberately.** Scanning a 40 MB archive is expensive and mostly pointless (binaries); the
cheap alternative is to **mark every file under a `fetch` destination as foreign for the rest of the session**,
so a `read` of one is scanned then. One line of state, and the hole closes where it is cheap.
@ right, we dont scan archives, if they contain programms then android scans them on its own so it is not a problem.

---

## 3. "No type prompts" — the layout change

> *"no type prompts. what I mean is just a list of user prompts that can be used by operator, he can just write
> 'analize' (for example), and agent can look at how to analize properly (it is similar to skills but simpler,
> just a kind of dictionary what explains what particular terms mean, no special machinery)."*

This is the clearest statement of the set, and it **removes `project_types/` from the design entirely**. Let me
be precise about what changes, because `system/project_types/` exists in the tree and `Presets.copy` writes to
it.

**What I had been calling a "type prompt"** — the prompt for `android`/`web`/`plain` — is **not what the
operator wants**. What they want is:

```text
muffycodes_data/glossary/       ← a dictionary of terms the operator uses
    analyze.md                  ← "when the operator says 'analyze', this is what they mean"
    review.md
    test.md
```

**A glossary entry is a prompt the operator invokes by name.** They type `analyze`, and the agent looks up what
`analyze` means *here* rather than guessing. That is:
- **not** a type (no `android/` vs `web/` distinction),
- **not** always in context (so it can be long),
- **not** a mechanism (a folder of markdown, read on demand),
- and it is **for the operator**, not for the app — the person defines their own vocabulary.

### 3.1 What this does to the three-layer prompt

| layer | what it is | in context |
|---|---|---|
| ① system prompt | how to use this app — locked | always |
| ② project prompt | how to work in this project | always |
| **glossary** | what the operator's words mean | **on demand** |
| skill | how to act in a situation | on demand |
| wiki | a fact worth finding | on demand |

**The middle layer is gone.** Three layers become two, and the thing I kept calling a "preset" is not a layer
at all — it is the *starting text* of ② for a new project, and a glossary entry is a third kind of thing that is
never in context.

**So `project_types/` has exactly one remaining job: what a new project starts with.** That is worth keeping,
and it is worth keeping *small*:

```text
system/muffycodes_data/
    starters/<kind>/            ← what a new project of this kind starts with
        project_prompt.txt      ← the initial ②, copied in, editable
        workflows/<name>.yml    ← the initial CI
```

`<kind>` is `android`/`web`/`plain` — but it is **a starter, not a type**. The difference matters: a starter is
copied once and then it *is* the project's own prompt; nothing later reads the folder. A type would be
consulted on every turn. **The tree already behaves like a starter** (`Presets.copy` writes `ProjectMeta` and
never reads the folder again) — the *name* is what was wrong.

**Q3.1 — is `glossary/` the right name?** The operator said *"a kind of dictionary"*. `glossary/` and
`dictionary/` both say it; `prompts/` does not, because it is not what ② is. My answer: **`glossary/`**, and
the system prompt says: *"The operator may use a word that has a meaning here. Look in `glossary/` before
assuming."*
@ for me dictionary is clearer

**Q3.2 — does a glossary entry get invoked, or just found?** If the operator types `analyze`, does the app
*expand* it into the prompt, or does the agent notice and read `glossary/analyze.md`? **Expanding** is a
mechanism (a parser, an escape syntax, an ambiguity when a sentence merely contains the word). **Noticing** is
a sentence in the system prompt and costs nothing. My answer: **noticing** — and the test of whether it works
is whether the agent reads the file when the person says the word.
@ noticing

---

## 4. The layout, final

```text
<account>/
  .muffy/                       ← app state (readable by agents, per the answer)
  model_conf.json               ← model choice; no key in it
  <project>/
    muffycodes_data/
      prompts/project_prompt.txt      ← ② always in context
      glossary/<term>.md              ← the operator's vocabulary, on demand
      skills/<name>/SKILL.md          ← how to act in a situation, on demand
      wiki/<article>.md               ← facts, found by search
      mcp/<name>.md                   ← servers
    .github/workflows/                ← where GitHub looks; copies of starters
    agent/                            ← git-excluded: results, screenshots, fetched scratch
    vendor/                           ← committed: fetched dependencies
  system/
    muffycodes_data/
      prompts/system_prompt.txt       ← ① locked
      starters/<kind>/{project_prompt.txt,workflows/}
      glossary/ · skills/ · wiki/ · mcp/   ← the core set every project inherits
```

**Five folders under `muffycodes_data/`, each with one meaning, none with a mechanism.** The system copy holds
the core; a project grows its own. Search takes folder filters, so *"look only in glossary"* is
`in:["glossary/"]` — no new concept.

---

## 5. Build order, revised again

| # | Step | Size | Change from last |
|---|---|---|---|
| 1 | `muffycodes_data/` layout; move prompts; rename `project_types` → `starters` | **S** | rename, not new work |
| 2 | Read-only outside the project + new refusal wording | **M** | unchanged |
| 3 | Notices: red, after the message, removed on the next | **S** | unchanged |
| 4 | `glossary/`, `skills/`, `wiki/`, `mcp/` folders + the system-prompt paragraph | **S** | glossary added, type prompt removed |
| 5 | `search` keyword-only, folder filters, `paths` | **S** | unchanged |
| 6 | Catalogue paths: one parameter, three pickers | **M** | unchanged |
| 7 | **`fetch`** — the missing primitive | **M** | **new, and it goes here** |
| 8 | Embeddings + rerank + index, with history | **L** | unchanged |
| 9 | MCP over `background` | **L** | unchanged |
| 10 | The chunk scanner, over `browse`/`mcp`/fetched | **M** | narrowed |

**`fetch` moved to 7, before the index**, because it is a *primitive* the rest of the app is missing — and
because a fetched archive is exactly the kind of thing a person will want searched, so the index should be
built knowing such files exist.

---

## 6. Open questions

| # | Question | My answer |
|---|---|---|
| **Q1.1** | Where does a `fetch` land — required `into`, or a default? | **Required**, because a default commits a 40 MB archive |
| **Q1.2** | Does `fetch` extract archives? | No; it lists entries. But `.tar.gz` has no extractor then — does that matter? |
| **Q1.3** | Foreign git repo: `fetch` a tarball, or a real clone? | **Tarball**; a real clone is out of scope (credentials, and "does it become the project?") |
| **Q2.1** | Is a `fetch`ed file scanned when read? | Mark the `into` folder foreign for the session; scan `read` of it |
| **Q3.1** | `glossary/` — right name? | **Yes**, and the system prompt names it |
| **Q3.2** | Does the app expand `analyze`, or does the agent notice? | **Notices** — expansion is a parser and an ambiguity |
| **Q4.1** | Is `starters/` the right name for what `project_types/` becomes? | Yes — a starter is copied once; a type would be consulted every turn |

The two that matter most: **Q1.2** (whether an archive extractor is needed — it decides if `fetch` is one tool
or two) and **Q3.2** (invoke vs notice — it decides whether the glossary is a convention or a syntax).

---

## 7. Corrections recorded

1. **I said "no need to scan other projects" was a narrowing. It deleted plumbing** — `read` no longer needs to
   know the owning project.
2. **I designed a "type prompt" layer that the operator does not want.** The middle prompt layer is not a type;
   `project_types/` is only a *starter*, and the operator's real ask is a **glossary of their own words**.
3. **I never checked whether the web tool could fetch a file, and it cannot.** `Browser` has `text()` and
   `shot()` only; `artifact` is GitHub-run-scoped. `fetch` is a missing primitive, not a feature request.
