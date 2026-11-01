# The plan, updated — three leftovers, then the search/MCP build

This supersedes the sequencing at the end of `COUNTERPROPOSAL-V-questions-left.md`, which asked whether
to finish reports 86–88 first or start on `muffycodes_data/`. **The answer is: finish them first, then
proceed.** This document is that order, written down so nothing is half-done while a large feature lands.

Two parts:

- **Part 1 — the leftovers.** Three open reports and one instruction the app cannot obey. Small, already
  reported, and they close the batch that has been in progress.
- **Part 2 — the build.** Eleven steps from the four counterproposals, in dependency order, with what each
  one touches and how it is verified.

---

# Part 1 · The leftovers

Four items. Three are reports; the fourth is a bug the counterproposal round exposed.

## L1 — Report 86: the `>>` chevrons are different sizes

**What is there.** `PagerArrow` draws two `KeyboardArrowRight` icons inside one `IconButton`: the first at
`28.dp`, the second at `20.dp` with `offset(x = (-8).dp)`. That is what makes them look wrong — they are not
the same glyph at two sizes, they are two different optical weights stacked with a hand-tuned offset.

**Why it looks wrong.** Material's `KeyboardArrowRight` is drawn inside a 24×24 box with its own padding. At
`28.dp` the arrow fills more of its box than at `20.dp`, so the pair reads as one large chevron and one
smaller, unrelated one. The `-8.dp` offset is compensating for a gap that comes from the icon's own padding,
not from the layout.

**What to do.** Draw both chevrons at **the same size**, and let the spacing come from an explicit `Spacer`
rather than an offset. One icon size constant, used twice:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(-6.dp)) {
    repeat(if (double) 2 else 1) {
        Icon(..., modifier = Modifier.size(CHEVRON))
    }
}
```

**The test.** A pure function is not available here (it is a composable), so the check is a **screenshot**
of the pager in the scenario tour, plus a note in the code saying both chevrons share one size by
construction. The honest verification is the picture, because the report is about how it looks.

**Size:** S. One composable, one tour screenshot.

## L2 — Report 87: swap pages in chat *and* fullscreen, keeping the page

**What is there.** Two independent page cursors:

| | where | survives? |
|---|---|---|
| the list | `showing: Map<Int, Int>` — a page per message id | yes, per message |
| fullscreen | `var at by remember(open.message.id) { mutableStateOf(open.at…) }` | **no** — a local, reset on open |

So opening a message fullscreen always starts at the page it was showing (`open.at`), but turning pages
there **does not write back**. Closing returns to the list at the old page. That is the report: *"make it so
that I can swap between message pages both in chat and fullscreen modes"* — the swap exists in each, but the
two do not agree.

**What to do.** One cursor. Fullscreen reads and writes `showing[message.id]`, and the `held` set gets the
same treatment it already gets in the list:

```kotlin
// fullscreen no longer owns a page; it edits the map the list reads
val at = showing[open.message.id] ?: open.at
onPage = { next ->
    showing = showing + (open.message.id to next)
    held = held + open.message.id   // turning a page in fullscreen holds it, same as in the list
}
```

**And the consequence that matters:** paging in fullscreen must *hold* the message, or the next arriving page
snaps the person back to the end while they are reading. That is the same rule the list already has, applied
to the second place it was forgotten.

**The test.** A `AgentChatScreenTest` case: open a message fullscreen, page back, close, assert the list shows
the same page; and a second: open fullscreen, page back, deliver a new page, assert the view stays put.

**Size:** S.

## L3 — Report 88: selection, and the handles in random places

**What is there now.** The last commit (`be24424`) put `SelectionContainer` **outside** the `verticalScroll`
in fullscreen, and themed the handles. The report said *"selection broke completely… now even choice options
arent rendered"* — the "choice options" part is a separate symptom worth naming.

**What is left**, and it is the part I explicitly deferred:

> handles in random places, no toolbar — per-paragraph containers pending

The reason a per-paragraph container is the answer: `PageBody` renders markdown as **many** `Text`
composables (a heading, paragraphs, list items). One `SelectionContainer` around all of them means a selection
that starts in one paragraph and ends in another has handles positioned by a container that measures the whole
column — and the scroll moves the text under it. **A container per paragraph** gives each one its own
geometry, so a handle sits where the text is.

**What to do.**

1. **One `SelectionContainer` per block**, not one per page. `PageBody` already emits blocks in a loop, so
   this is the loop's own body wrapped.
2. **The toolbar comes back.** Compose's default selection toolbar needs `LocalTextToolbar`; if the app has
   overridden it or the theme hides it, that is why *"even choice options aren't rendered"*. Check
   `AppTheme` for a `LocalTextToolbar` provider and remove any that suppresses it.
3. **The list's bubble keeps one container per block too**, for the same reason.

**The test.** A screenshot scenario (the tour has one for selection) plus a `SelectionFeatureTest` case
asserting the number of `SelectionContainer`s matches the number of text blocks — the mechanical proof that
per-block is what was built.

**Size:** M — it touches `Markdown`/`PageBody` and both call sites.

## L4 — The `downloads` instruction that cannot be obeyed

**The bug.** `downloads`' description ends:

> *"A folder is refused — a project is a tree and Downloads is one flat folder — so **zip it first** if you
> mean several files."*

**There is no zip tool.** `grep ZipOutputStream` across the app finds nothing; the only zip code is
`java.util.zip.ZipFile` in `artifact`, which reads. So the agent has been told to do something it cannot do
since the sentence was written.

**Why this is in Part 1 and not Part 2.** It is a **lie in the prompt** — the worst kind of bug, because the
model spends a call discovering it and then improvises. The full archive tool (`pack`/`unpack`) is step 7 of
Part 2; **this is the one-line fix that stops the lie now**, and it costs one sentence:

```
"...so zip it first if you mean several files." 
  → "...so put several files into one zip with `pack` first."
```

That is only true once `pack` exists — so the honest order is: **do step 7's `pack` as part of Part 1**, or
**soften the sentence** until it does. My answer: soften now, and the sentence becomes true at step 7.

**Size:** XS now; the tool is Part 2 step 7.

---

# Part 2 · The build

Eleven steps. Each names what it touches and how it is proved. Sizes: **XS** under an hour, **S** a day,
**M** a few days, **L** a week or more.

| # | Step | Size | Proved by |
|---|---|---|---|
| 1 | `muffycodes_data/` layout; move prompts; `project_types/` → `starters/` | S | `WorkspaceTest`, `AppTypeTest` |
| 2 | Read-only outside the project + the new refusal wording | M | `AgentToolsTest`, a new cross-project case |
| 3 | Notices: red, after the triggering message, gone on the next | S | `AgentChatTest`, `AgentLogTest` |
| 4 | `dictionary/`, `skills/`, `wiki/`, `mcp/` + the system-prompt paragraph | S | `WorkspaceTest`, a prompt-order test |
| 5 | `search` keyword-only, folder filters, `paths` | S | `AgentToolsTest` |
| 6 | Catalogue paths: one parameter, three pickers | M | `ModelCatalogueTest`, `ModelSectionTest` |
| 7 | `pack` / `unpack` — and it makes the `downloads` sentence true | M | a new `ArchiveTest`, zip-slip cases |
| 8 | `fetch` — bytes from a URL into `agent/fetched/` | M | `AgentToolsTest` with a door, SSRF cases |
| 9 | Embeddings + rerank + the index, with history | L | a new `SearchIndexTest`, dimension mismatch |
| 10 | MCP over `background` | L | `AgentToolsTest`, article parsing |
| 11 | The chunk scanner, over `browse`/`mcp` | M | a door that answers a score |

## Why this order

**1 before everything** because every later step writes into `muffycodes_data/`, and moving a folder twice
costs twice.

**2 before 3–8** because it is a **security boundary**, and every tool added later inherits it. Doing it late
means auditing every tool for the same question.

**3 and 4 are independent of the rest** and can land while anything else is in flight — they are the two that
fix live complaints and add folders.

**5 before 9** deliberately: keyword search may be enough, and finding that out costs a day rather than a
week. **It is allowed to be thrown away.**

**6 before 9** because the pickers are how a person chooses the embedding model, and building the index
against a hardcoded model means rebuilding it when the picker lands.

**7 and 8 before 9** because a fetched archive is exactly the kind of file a person will want searched, so the
index should be built knowing they exist.

**10 and 11 last** because MCP widens the attack surface and the scanner exists to narrow it again — building
the scanner first would be guarding a door that does not exist yet.

## The decisions already taken

| Decision | Value | From |
|---|---|---|
| Notice placement | after the triggering row; gone on the next message or burst | answer to Q1.1 |
| Notice removal | **flip `ctx` to `out`**, like `forget` — not a delete | Q2.1 (II) |
| Notice look | red background, full width | Q1.3 |
| Scanner scope | `browse` and `mcp` only | Q2.2 (II) |
| Scanner threshold | 0.8 default, per-account; removed text stays in the log | Q2.1 (III) |
| Scanner action | **cut the offending chunks**, keep the rest, say what was cut | answer to Q2.2 |
| Archive scanning | none — Android scans programs itself | Q2.1 (IV) |
| Embedding default | `qwen/qwen3-embedding-8b` at `dimensions: 1024` | Q3.1 (II) |
| Rerank | `voyageai/rerank-2.5-lite` | measured |
| Catalogue | one class, a path argument | answer to Q1.1 (II) |
| Dictionary folder | `dictionary/`, not `glossary/` | Q3.1 (IV) |
| Dictionary use | the agent **notices**, the app does not expand | Q3.2 (IV) |
| `fetch` destination | `agent/fetched/` always; `unpack` moves files in | D5 |
| `pack`/`unpack` | one tool, a mode | D2 |
| `unpack` safety | refuses absolute paths and `..` in entries | D3 |
| Migration | none — the app is unpublished | Q4.1 (II) |
| History search | `history: true`, answers `path@sha7` | Q3.3 (III) |
| Type prompts | **do not exist**; `project_types/` becomes `starters/` | answer to Q3.2 (II) |

## The four things that are not obvious, kept where they will be read

**The dimension is a request parameter.** `qwen3-embedding-8b` answers 1024, 2048 or 4096 on demand. So the
dimension belongs in `ModelConfig` beside the model id, exactly as `contextLength` already does — because the
builders have no catalogue to ask. An index stores the dimension it was built at; a mismatch is "re-index
needed", never a comparison.

**History is cheap because of the hash.** A version's chunk and the current file's chunk with the same text
share a hash, so the vector is stored **once and referenced twice**. History is not 10× the storage; it is the
deltas.

**`system` is one index, not one per project.** Every project shares it, so indexing it per project would
embed the same article N times.

**`agent/` is already git-excluded**, so `fetch` landing there needs no new ignore rule — and the person's own
`.gitignore` layers on top, which is what the operator's question was asking.

---

# Part 3 · What "done" means for each

A step is done when the **test named above passes in CI**, not when the code compiles — the container has no
Kotlin compiler, so CI is the compiler. Two standing rules from the earlier plans still apply:

- **A test that pins a sentence is doing its job.** When a sentence changes on purpose, the test changes with
  it and the commit says why. `ScenariosTest` and `StatsScreenTest` both failed this way this week and both
  were right to.
- **An assertion should check the absence when the change is a removal.** A test that only checks the rows
  still draw would pass with the old table restored.

And the one that cost the most time this week, recorded because it will happen again:

- **A test that fails may be asserting the wrong thing.** The stats dash test failed three times and the code
  was right twice — once because a share line carries a `%`, once because the log *did* reach the previous
  period. **Read what the assertion claims before changing the code.**

---

# Part 4 · Order of work, starting now

```
L1  >> chevrons same size                      XS–S
L2  one page cursor, shared with fullscreen    S
L4  soften the downloads sentence              XS
L3  per-block selection + the toolbar          M
    ── commit, push, CI green ──
1   muffycodes_data/ + starters rename         S
2   read-only boundary                         M
3   notices                                    S
4   dictionary/skills/wiki/mcp + prompt        S
5   keyword search                             S
6   catalogue paths                            M
7   pack / unpack                              M
8   fetch                                      M
9   embeddings + rerank + index + history      L
10  MCP over background                        L
11  the chunk scanner                          M
```

**L1–L4 are one batch and get one CI run**, because they are all in the chat screen and a broken one would
hide behind the others. Steps 1–8 are one commit each — small, independent, and each one green before the
next.
