# What the trace asks for, and what to do about it

Written from `Download/MuffyCodes log/trace.log (2).txt` (dev-3a70b7d, 13:17–16:26) plus the
operator's corrections of 2026-10-29. Every item is either **done** (with the commit), **open**, or
**blocked** on a decision. Nothing is listed as done on the strength of "the code exists" — the
evidence column says how it was checked.

---

## 0. The one that made the chat useless

**The chat answered every message with `400 Provider returned error`.**

Two defects, one symptom.

1. **Every tool schema was an example, not a schema.** The tools are written as filled-in samples
   (`{"ids":"2 5 7-12 14"}`) because that is readable and stays honest, and `spec()` parsed the
   sample straight into `ToolSpec.parameters` and sent it. A sample has no `"type":"object"`, so
   DeepSeek refused the whole request before reading a single message:

   ```
   Invalid schema for function 'tree': schema must be a JSON Schema of 'type: "object"',
   got 'type: null'
   ```

   The tools ride on **every** request, so no message could have worked. It failed at `tree` only
   because that is first in the list; fixing it alone would have moved the refusal to `read`, then
   `edit`, through all eleven. Proven both ways against the live endpoint: all eleven bare → 400 at
   `tree`; all eleven wrapped → OK, same model, same pin.

2. **The error message hid the reason.** `why()` read only `error.message`, which for a provider
   refusal is always the wrapper sentence `"Provider returned error"`. The sentence that said why
   sat in `error.metadata.raw`, one level deeper, unread. So eleven distinct failures all printed
   the same three useless words.

Fixed in `459223e`: `spec()` wraps each example in `{"type":"object","properties":…,"required":[…]}`,
deriving each property's type from its example value and using the example's text as the
description. `why()` unwraps `metadata.raw`.

**Why nothing caught it.** No test had ever asserted that a schema *was* a schema.
`AgentToolsTest` now pins the object type, that every property is typed, that `required` names only
what is described, that `KEYS` and the schema agree, and that `wait` is not a tool.

**A second bug found by the same test** (`0128a1f`): `edit` reads seven arguments but its example
showed three, so `from`/`to`, `at` and `text` were described in prose and absent from the schema —
arguments the model was never told it could send.

---

## 1. The chat is not drawn as specified

The operator's correction, verbatim:

> chat is still not drawn as i specified. I need it be vertically scrollable with each message
> bubble (a container that holds multiple pages of agent actions and results, and that ends with a
> finishing message from the agent as the last page). And user messages are less complicated - they
> dont have multiple toolcalls and turns, - they are just a plain message row. And that is how chat
> should look like: a vertical list of rows (agent-user-agent-user-agent... - from the beginning of
> the season log and to the last message). and on opening of the chat you are pinned to the bottom
> of it (where is the latest message).

### What is there now

`docs/PLAN-chat.md` (report 36) says all eight steps are **done**, and the code matches *that*
document. The document's unit is:

| bubble | holds |
|---|---|
| the operator's | what the person wrote |
| a working bubble | **one** tool call **and** its result |
| the last bubble | the agent's words |

So the list is `user · call · call · call · agent-words · user · …` — a bubble per *call*.

### What the operator is asking for

A bubble is **one agent turn**: every tool call and result of that turn inside it, as pages, ending
with the agent's closing words as the last page. The list is then strictly alternating:

```
user → agent (pages: call, call, …, closing words) → user → agent (…) → …
```

That is a different unit, and it changes four things:

1. **Grouping.** `AgentChat.bubbles()` emits one bubble per `CALL`/`GOT` pair. It must emit one
   bubble per turn, holding the pairs as pages.
2. **Paging, not scrolling, inside a bubble.** Today `detail` is one string and the fullscreen view
   is one `verticalScroll`. Pages mean an index and a ‹ › control, or a pager.
3. **User rows get no pages and no footer chrome** — a plain row, not a bubble with a caption.
4. **Pinned to the bottom on open.** This exists (`scroll.animateScrollTo(maxValue)`), but it runs
   against a `Column` inside a `BoxWithConstraints` capped at 75% height, not a `LazyColumn` — so it
   cannot survive a long session and it is not the scroll the spec describes.

### What this costs

- `AgentChat.Bubble` gains `pages: List<Page>`; `body`/`detail` become the first page and the joined
  text.
- `AgentChat.bubbles()` regroups by turn. The turn boundary already exists on the record: `TURN` is
  a bookkeeping row (`AgentLog.TURN`), currently skipped. It is the natural divider.
- `AgentChatScreen` becomes a `LazyColumn` with `reverseLayout = false` plus an initial scroll to
  the last index — which is what "pinned to the bottom" means for a lazy list.
- The fullscreen view becomes a pager over the same pages.
- `PLAN-chat.md` needs a **round 2**: its §"The shape" table is now wrong, and it is the document
  the code was built from, so leaving it saying "done" is how the next person builds this again.

### Order

1. Regroup into turns, keep the current drawing (one bubble per turn, all its lines stacked).
   Ship it. This is the shape change, and it is testable without touching the UI.
2. Add pages + the pager.
3. Make the user row plain.
4. `LazyColumn` + open-at-bottom.

Step 1 is the whole complaint. Steps 2–4 are the polish that the complaint also names, and they are
independent of it.

---

## 2. Reports from this trace, and their state

> **This table is out of date.** It was written before the work landed and still says "open" about
> items that are closed. The current state of every report is in
> `AUDIT-2026-10-29-consolidated.md` §3; this section is kept only for the reasoning it records
> about what each report *meant*.

| # | Report | State |
|---|---|---|
| 1 | Make all text selectable, to copy to the clipboard | **open** |
| 2 | Why sign in for the system folder or a local project? | **done** — provisional `~local` account |
| 3 | Remove "sign in to begin"; open README on first launch | **done** — `0346358`, `6b84d6d` |
| 4 | Context block: clarify compacted/trimmed at/to | **open** |
| 5 | Rename the timeout block to "command execution timeouts" | **done** — "While the app works on this phone" |
| 6 | Rename to "model response timeouts" | **done** |
| 7 | Three fields: connection, stalling, reconnect | **done** |
| 8 | Keep descriptions short | **done** |
| 9 | If "the record" means session log, call it so | **open** — the block is still titled "The record" |
| 10 | Session: one field, max length in tokens (default 10,000,000); drop MB and line limits | **open** |
| 11 | Over the limit, delete the oldest bubble (its full span); cache sizes in a KV store | **open** — depends on the turn grouping above |
| 12 | Reminders and Running have two horizontal lines | **done** — `4efed9d` |
| 13 | Add the debug icon to the stats screen | **done** — `4efed9d` |
| 14 | Rename "What it costs" → "Account statistics" | **done** — `4efed9d` |
| 15 | A better statistics icon; turn the gitignore icon into **unsync** | **done** — drawn chart; unsync rebuilt (`f5fb51c`) |
| 16 | Lock/unsync: grey unset, black by user, red by app | **done** — `Workspace.Hold` / `Unsync` |
| 17 | Why does a locked file still offer Move? | **done** — `4efed9d` |
| 18 | Local projects should not have a sync bar | **done** — already `isLocalProject`-guarded |
| 19 | Local projects should have "add to GitHub"; the local↔synced cycle must be complete | **open** |
| 20 | Footer: hour:minute only, id first | **done** — `0128a1f` |
| 21 | Remove "1 session bar" | **done** — no such string remains |
| 22 | After setting the key, "no OpenRouter key on this account" still shows | **open** — likely fixed by the 400 fix, needs a re-test |
| 23 | Provider picker: Add/Remove should list providers for the model, not ask for a tag | **open** |
| 24 | Effort popup needs one select button, not a template per option | **open** |
| 25 | Move Reset from the agent block to the bottom of settings, resetting to defaults | **open** — needs a decision on where defaults live |
| 26 | Make the themes truly different; rename dark chocolate → chocolate | **open** |

---

## 3. The four open items that need a decision before code

### 3.1 Where do defaults live? (report 25)

Asked in the report itself. Today every setting's default is a Kotlin literal at its read site
(`SettingsStore`), so "reset to defaults" would have to re-state each one — a second copy that can
drift. The honest answer is a single `Defaults` object that both the read site and the reset use, so
there is one statement of each number. **Recommendation: do that, then the button is four lines.**

### 3.2 Session length in tokens (report 10)

The field is easy; the behaviour is not. Deleting "the oldest bubble" needs the bubble unit, which
is §1. Doing this before the regrouping would delete per-call rows while the person thinks in turns.
**Recommendation: after §1 step 1.**

The KV cache for sizes (report 11) is a real design: a running total per file, adjusted on every
append and every deletion, so the size column is O(1) instead of a rescan. Worth doing, but it is
only a performance fix — the screen is correct without it.

### 3.3 The local↔synced cycle (report 19)

"Synced already gives the ability to keep the project as local in delete, right?" — **it does not**,
and that is the gap. Today `delete` offers to keep the *files*; it does not offer to keep the
*project* as a local one. The complete cycle is:

```
local --add to GitHub--> synced --keep as local--> local
```

The second arrow does not exist. It means: create the repository, push, set the remote, and *stop
being a project that has a remote* — while keeping every commit. That is a git operation with a
sharp edge (the remote still holds a copy), so it should say so.

### 3.4 Text selection (report 1)

The simplest of the four and the most often asked. `SelectionContainer` over the text-heavy screens
(stats, history, trace, dialogs, chat) — leaving the file list's long-press multi-select alone,
because the two gestures conflict.

---

## 4. What to do next, in order

1. **Verify the 400 fix on the phone.** Everything else about the chat is unverifiable until a turn
   completes. (Reports 22 and the whole of §1 assume a working turn.)
2. **§1 step 1** — regroup bubbles into turns. The complaint, and the prerequisite for 11.
3. **§3.1** — one `Defaults` object; then report 25 is small.
4. **Reports 23, 24** — the provider picker and the effort popup. Both are "the screen asks for the
   wrong thing", which is the same class of defect.
5. **Report 26** — themes.
6. **§3.3** — the local↔synced cycle, as its own piece of work with its own confirmation.
7. **Report 1** — selectable text.
8. **Reports 4, 9, 10, 11** — the context/session block, after §1.

---

## 5. The lesson worth keeping

Three of this trace's reports were *"the app did the thing but said nothing useful"*: the 400 hid its
reason behind a wrapper, the lock icon was `Info`, and the chat drew a shape the person had not
asked for while a document said it was done.

The common cause is a test that asked whether code **exists** rather than whether the result is
**right**. `AgentToolsTest` is the counter-example: it asks what a schema looks like, and it found
two defects on its first run.
