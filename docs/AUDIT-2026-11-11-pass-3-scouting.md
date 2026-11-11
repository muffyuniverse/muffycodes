# Pass 3 scouting — where refactoring would actually pay

Measured 2026-11-11 on `development`. **No code changed.** This is a candidate list with a pattern
and a benefit for each, ranked by (benefit ÷ risk).

Method: comments and string literals stripped, then each function's body measured by indentation
(which is reliable here) — not by brace counting, which mis-fires on `File(...)` and on multi-line
signatures and produced three false lists before this one.

## The shape of the problem

58 functions are 95 lines or longer. That number alone is not the finding: a Compose screen is
naturally long, and splitting one for its own sake produces parameter lists nobody can read. **The
finding is which ones are long for a reason that is a defect.**

Three distinct defects appear:

| defect | what it looks like | where |
|---|---|---|
| **A table written as a `when`** | 25 cases, each building a different dialog | `MainActivity.Dialogs` |
| **A screen with sections and no section boundary** | 9 headings in one 873-line function | `SettingsScreen` |
| **A rule and its delivery in one body** | 25 `if`s before the thing it does | `AgentTools.write` |

A fourth category — long but coherent, where splitting would only move text — is listed at the end
under **leave alone**, because naming what not to touch is half of a refactoring plan.

---

## Ranked candidates

### 1. `MainActivity.Dialogs` — 439 lines, 25 `Pending` cases → **registry / dispatch table**

**Now.** One `when (pending)` with 25 arms, each constructing a different dialog and wiring its
callbacks inline. `Pending` is a sealed interface with 25 cases; the `when` is exhaustive by
construction.

**Pattern.** Replace the `when` with a lookup: `Pending` gains a `render()`-shaped contract, or a
`Map<KClass<out Pending>, DialogSpec>` is consulted. Each case becomes a small function beside the
dialog it opens, in the file that already owns that dialog.

**Benefit.** Clarity: adding a dialog stops meaning editing a 439-line function. Unification: every
dialog is constructed the same way, so "what a dialog needs" is answerable once. Compactness: the
dispatcher becomes ~15 lines. **Risk: medium** — 25 cases, each with real wiring; the compiler proves
exhaustiveness today and a map does not.

**Do this only if** a dialog is added again. Otherwise the `when` is doing its job and the size is
the price of 25 real windows.

### 2. `SettingsScreen` — 873 lines, 9 sections → **extract by section**

**Now.** One composable containing `Context`, `Command execution timeouts`, `Model response
timeouts`, `The web`, `Secrets the agent may use`, `Keepalive`, `Session log`, `Reminders`, `Chat`,
`Theme`. The section headings are `Text(...)` calls, so the structure exists only in the prose.

**Pattern.** One private composable per section, taking `viewModel` and only the callbacks it uses.
The parent becomes a `Column` of nine calls — a table of contents that is also the code.

**Benefit.** Clarity: the highest of any candidate, because the sections are already named in the UI.
Unification: settings fields that repeat (`LengthField` with a label, a unit, a default) get one
shape. Compactness: 873 → ~120 in the parent, with nine files of 40–120 lines.

**Risk: low.** A pure extraction with no behaviour change, and the existing `SettingsScreenTest`
covers the fields.

### 3. `AgentTools.write` — 269 lines, 25 `if`s → **the selector shape, named**

**Now.** `write` accepts `{path,text}`, `{path,grep,text}`, `{path,original,text}`, `{path,from,to,text}`,
and combinations, and decides between them with 25 sequential `if`s. The doc comment already calls
them "three shapes" and explains that `original` "is not a fourth kind of shape".

**Pattern.** The command's own words say it: **a sealed `Selector`** — `Whole`, `Lines(from,to)`,
`Matches(regex)`, `Literal(text)` — resolved by one function from the `JSONObject`, refusing
conflicts there. `write` then does one thing: apply a `Selector` and a `text` to a file.

**Benefit.** Unification: the same selector is what `read` uses for `grep`/`from`/`to`, so the two
commands stop having separate copies of "which lines". Clarity: the refusal sentences for conflicting
keys move to one place. Compactness: 269 → ~90 plus a ~70-line selector module.

**Risk: medium.** `write` is the command everything depends on; 194 tests in `AgentTest` touch it.
Land it alone, whole suite green.

### 4. `AgentTools.specs` — 408 lines, 0 branches → **a data table**

**Now.** One expression producing 20 tool specs, each an inline `JSONObject` built by hand. It has no
branches at all, which is why it is long: it is data written as code.

**Pattern.** Already half-done — `spec()` and `example()` exist. What is missing is that the 20 specs
are *literal JSON*, so they could be a `resources` asset or a `listOf(ToolSpec(...))` table, with
`spec()` doing the deriving.

**Benefit.** Compactness: the largest of any candidate — 408 → maybe 120. Clarity: a spec is a value,
so "which keys does `search` take" is readable without parsing nested `JSONObject` construction.

**Risk: medium-high.** The spec *is* the prompt surface, and a mistake here changes what the model is
told. The existing test that compares `KEYS` to `specs()` would catch a drift, but not a wrong
description.

### 5. `BrowserScreen.BrowserScreen` — 356 lines, **21 parameters** → **one actions object**

**Now.** 21 parameters, of which 18 are `on*` callbacks. `BrowserActions` also exists, with 12
members.

**I checked whether these are the same concept twice, and they are not.** `BrowserActions` is what a
*row* in a listing can be asked to do — open, rename, move, copy, clone, download, delete, pin, lock,
history, select, ignore. The 18 parameters are *project-level and toolbar* acts — add project, chat,
release, transfer, create file/folder/image, upload, restore deleted, and the four selection verbs.
**The sets do not intersect at all.** Two mechanisms with no overlap are two things, correctly
separated; the naming merely makes them look alike.

**What is still worth doing.** The 18 callbacks *are* one concept — "what this screen can ask the app
to do" — and they belong in one object for the same reason `BrowserActions` does: so the screen takes
three parameters instead of 21, and a test can build one. The pattern is a second object
(`ProjectActions`), not a merge into `BrowserActions`.

**Benefit.** Clarity: a 21-parameter signature is a signature nobody reads. Unification: consistent
with the row-action precedent that already exists. Compactness: modest — the win is the call site,
which loses 18 named arguments.

**Risk: low.** Mechanical, and the compiler finds every call site.

### 6. `AppRoot` — 519 lines, 12 `remember`s, 5 `when` branches → **a navigation state object**

**Now.** 12 `var … by remember` at the top, then a 5-arm `when` that chooses a screen, with the `else`
arm passing 20 callbacks to `BrowserScreen`.

**Pattern.** The 12 flags are one value: `sealed interface Where { Settings; Secrets; Trace; History(path); Editor(file); Chat(project); Branches(project); Accounts; Root }`. Then the `when` is on `Where`, the back gesture is `pop()`, and "which screen is showing" is one field instead of 12 booleans that can disagree.

**Benefit.** Clarity: highest structural win — 12 independent flags cannot express "two screens at
once", but nothing stops them being set. Unification: back handling becomes one rule. Compactness:
519 → ~150.

**Risk: medium-high.** Navigation is where a refactor breaks behaviour silently. **Do after 1 and 5**,
which remove most of what this function carries.

### 7. `Markdown.opens` / `closes` / `appendInline` — 171 + 168 + 165 → **one inline-span parser**

**Now.** Three functions over the same text, each with 12 `if`s, deciding emphasis, code, links.

**Pattern.** One tokenizer producing spans, then one renderer. `OneBlock` and `parse` already exist in
the file, so this is finishing a split that was started.

**Benefit.** Unification: three passes become one, so `**bold _and italic_**` cannot be handled
differently in `opens` than in `closes` — which is the bug this shape produces. Compactness: ~500 → ~200.

**Risk: medium.** Markdown edge cases are where tests are thin.

---

## Leave alone, and why

These are long and should stay long:

- **`EditorScreen` (737), `AgentChatScreen` (746), `Drawing.kt`'s `DrawingSurface` (313)** — Compose
  bodies. Their length is the layout. Splitting produces parameter lists longer than the bodies.
- **`GitService.remoteUrl` / `setRemoteUrl`** — reported at 452/448 lines by the indentation method,
  and **both are two and six lines**. The method counted everything after a one-line expression body
  as belonging to it. This is the one place the measurement is wrong, and it is worth knowing the
  measurement can be wrong this way.
- **`AgentChat.messages` (198), `AgentChat.bubbles` (132), `Agent.messages` (141)** — each is a fold
  over rows with a `when` per row kind. Coherent; splitting hides the order, and the order is the
  behaviour.
- **`AppTheme.lightScheme` / `darkScheme` (142/131)** — colour literals. Data.

## Recommended order

1. **`BrowserScreen`'s 18 callbacks → one `ProjectActions` object** (low risk, mechanical; and
   `BrowserActions` stays as it is — the two sets are disjoint, so this is a second object rather than
   a merge).
2. **`SettingsScreen` by section** (low risk, biggest clarity win, tests already cover the fields).
3. **`AppRoot` → `Where`** (after 1, which removes 18 of its 20 callback arguments).
4. **`write` → `Selector`** (medium risk; land alone).
5. **`Dialogs` → registry** (only if a dialog is added again).
6. **`specs` → table** (high risk, highest compactness — and it is prompt surface).
7. **`Markdown` → one parser** (last; thin tests).

Steps 1–3 are one week of careful work with green between each. Steps 4–7 are judgement calls that
should each be argued on their own.
