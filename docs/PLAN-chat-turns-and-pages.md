# The chat: what the operator specified, in full

Written 2026-10-29 from the operator's own words, in two corrections. This is the authority for the
chat rebuild; `PLAN-chat.md` (report 36) is the round it corrects, and `PLAN-trace-2026-10-29.md` §1
is where it sits in the queue.

**The two corrections, verbatim.**

> chat always returns "provider returned error 400" - and chat is still not drawn as i specified. I
> need it be vertically scrollable with each message bubble (a container that holds multiple pages of
> agent actions and results, and that ends with a finishing message from the agent as the last page).
> And user messages are less complicated - they dont have multiple toolcalls and turns, - they are
> just a plain message row. And that is how chat should look like: a vertical list of rows
> (agent-user-agent-user-agent... - from the beginning of the season log and to the last message).
> and on opening of the chat you are pinned to the bottom of it (where is the latest message).

> and elements from the previous plan are also considered? such as that max hight of a row is set by
> its highest page (it has most lengthy content and message row keeps this hight). and max allowed
> message hight is set in settings (as amount of lines). and when when message height is bigger then
> allowed max height that only part of it is shown and the last line says how much lines are left
> hidden and to expand to see more. on the top right corner of the message there is full screen
> expand icon that allows you to scroll fully through any page and list (swap) between pages of the
> message.

---

## 1. The unit is a turn, not a call

A **message** (the operator's word; `PLAN-chat.md` says "bubble") is one side of the conversation:

| Message | Holds | Pages |
|---|---|---|
| **The operator's** | What the person wrote | **None.** A plain row — no pages, no footer chrome. It has no tool calls and no turns. |
| **The agent's** | Every tool call and result of one turn, **plus** the agent's closing words | **Many.** One call+result per page; the closing words are **the last page**. |

So the list alternates, from the first row of `session.log` to the last:

```
user · agent · user · agent · user · agent · …
```

A turn with three tool calls is **one** message of **four** pages: call₁, call₂, call₃, and the
agent's words. It is not four messages.

**The turn boundary already exists on the record.** `AgentLog.TURN` is a bookkeeping row the loop
writes before each request (`log.bookkeeping(AgentLog.TURN, "turn", "$requests")`). `bubbles()`
currently skips it. It becomes the divider — no new bookkeeping, and nothing inferred from gaps.

**The closing words are already the last thing a turn writes.** `Agent.work()` writes the agent's
note when the reply has text, and a reply with text and no calls is what ends the turn. So the last
page is not a new row kind; it is the `agent` note that closes the turn.

## 2. Height: the active page's own height, bounded by the cap

**A message is as tall as the page being shown**, not as its tallest page. Swapping pages resizes
the message.

**This reverses the earlier rule, and the reason is worth keeping.** Tallest-page was specified to
stop the list moving, and it does — at the cost of a one-line page reserving twenty lines of blank
space, in a chat that is already height-capped. That reads as a broken message rather than as a
reserved box, and the page indicator already says there are more pages without the space having to
prove it.

### The two things that make a resizing message safe

The cost of a dynamic height is **not** computation. A swap is one tap: the box re-measures, the
messages below are re-positioned but **not recomposed** (their inputs did not change), and messages
below the fold are not composed at all in a `LazyColumn`. That is one measure pass over the visible
rows.

The cost is that things move, and two rules keep that from being a nuisance:

1. **The pager is anchored to the message's TOP**, beside the fullscreen icon — never at the bottom
   of the box. A box that shrinks with its controls inside it moves the buttons out from under the
   finger that just tapped one, so the person has to chase it. Anchored at the top, the message grows
   and shrinks downward and the controls never move.
2. **A swap keeps the message's top in place.** The list scrolls to accommodate, so the new page is
   read from its beginning. Without this a taller page grows past the fold and the reader is looking
   at its middle.

Both are layout rules, and both are cheap. Neither needs the height to be fixed.

**Animating the height is optional.** `animateContentSize()` makes the shift smooth rather than a
jump; it costs about twenty extra measure passes of text, which is still nothing. Worth having, not
worth planning around.

### Rejected: growth-only

A middle option — never shrink below the tallest page seen so far — removes the upward push but keeps
the wasted space once a long page has been visited, and makes the height depend on the order pages
were read. Two rules instead of one, for a worse result.

## 3. The cap, in lines, from settings

- **Set in settings, as a number of lines.** `bubbleLines` already exists
  (`DEFAULT_BUBBLE_LINES = 0` = no cap). Lines rather than a fraction of the screen because "eight
  lines" is a statement about the text and "40% of the window" is a statement about the phone.
- **When the active page is taller than the cap**, only its first part is shown, and **the last
  visible line says how many lines are hidden and that expanding will show them.** A page that
  simply stopped would be indistinguishable from one that finished — the sentence is what makes
  truncation honest.
- **The count is the active page's**, because that page is the message's height (§2). It therefore
  changes as pages are swapped, which is correct: it is a statement about what is on screen.
- **`0` means no cap**, and then the sentence never appears.

## 4. Fullscreen: an icon in the message's top-right corner

- **A fullscreen icon sits in the top-right corner of the message** — not in a menu, not on the
  footer.
- It opens the message fullscreen, where:
  - **any page can be scrolled fully** (no cap, the whole page), and
  - **pages can be swapped** — ‹ › between the pages of that one message.
- Fullscreen is **not a bigger message**: it is the same pages, one at a time, read without the cap
  that applies in the list.

## 5. The chat is drawn from `session.log`, in full, every time it opens

**Nothing is held between openings.** `readChat` → `AgentLog.open(dir).rows()` → `AgentChat.box`,
which is the whole file: that is why the history before a restart is there, and why the chat and the
evidence can never disagree.

What this costs, and what it means for the two items below: every open parses the entire file, and
every token figure is recomputed by scanning rows. That is correct and it is O(n) on each open.

## 6. Session and context sizes: cached, and changed incrementally

**Not built, and asked for explicitly.** The operator's words:

> we can keep size in tokens for both session and context logs cached in memory (there is a simple
> persistent kv storage in kotlin/android), so that on every change it is increased/decreased and we
> reference it instead of recalculating through all size column entries.

Today:

- `AgentLog.size()` is `file.length()` — a `stat`, already cheap;
- **token counts are not cached at all.** `ContextBudget.tokens(detail)` runs per row, and
  `burst.sumOf(size)` runs per trim decision. Nothing is remembered between calls.

What it should be:

- **One running total per file**, in a small persistent key-value store, next to the record's own
  state. Appending a row adds its cost; deleting or trimming subtracts it; a whole-record rewrite
  recomputes once and replaces.
- **The unit of change is a row**, because that is the unit the file has. A burst's cost is then the
  sum of its rows' cached costs rather than a rescan, which is what makes the size column and the
  trim decision O(1).
- **A file edited outside the app breaks the total**, so the store keeps the file's length and mtime
  beside the total and recomputes when they disagree. Without that the number is a claim about a file
  the app did not write, which is exactly the class of lie the rest of this app avoids.
- **The cache is an optimisation, never an authority.** If it is missing, the number is computed and
  the cache rebuilt — so a corrupted store costs one slow open rather than a wrong figure.

## 7. The footer says whether the row is in the context

**Not built.** The footer should say, per page, whether that row is currently in the model's
context — so a person can see at a glance what the agent still has and what it has forgotten.

The check is **the row's id against the context log's ids** — no inference, no second bookkeeping.
The record already carries it: `AgentLog.contextRows()` is `rows().filter { it.ctx == IN }`, and a
forgotten row is the same row with `ctx` flipped to `out`. So "in context" is a set membership test
on an id, which is O(1) against a set built once per open.

Drawn as a short word in the footer beside the id — `in context` / `forgotten` — because the footer
is already the row's handle line, and the state belongs with the handle rather than in a colour the
reader has to decode. The struck-through text stays: the word says what happened, the line through
the text says it without needing to be read.

**One caveat worth stating.** A row can be `out` because the agent forgot it or because the app's
trim removed it, and the two are different facts. `ctx = out` covers the agent's decision; a trimmed
row is gone from the file entirely, so it cannot be drawn at all. So the footer's `forgotten` means
"the agent took it out of its context", not "it no longer exists" — and the wording should say so.

## 8. Opening the chat pins to the bottom

On open, the list is scrolled to the **latest message**. "Pinned" means the initial position is the
bottom; it is not a lock that fights the person's scrolling afterwards.

This is why the list is a `LazyColumn` rather than the current `Column` inside a height-capped
`BoxWithConstraints`: a `Column` inside `verticalScroll` composes every message, so a long session
gets slower the longer it runs, and "scroll to the bottom" has to measure the whole thing first.

---

## 9. What this changes in the code

| Where | Now | Needed |
|---|---|---|
| `AgentChat.Bubble` | one bubble = one call+result, or the agent's words | one bubble = one turn; `pages: List<Page>`, the words as the last page |
| `AgentChat.bubbles()` | emits per `CALL`/`GOT`; skips `TURN` | groups by `TURN`; `TURN` is the divider |
| `AgentChatScreen` | `Column` + `verticalScroll` in a 75%-height box | `LazyColumn`, scrolled to the last index on open |
| The cap | `bubbleLines` truncates a single `detail` string | applies to the active page; the sentence counts that page's hidden lines |
| Fullscreen | a `TextButton`/tap on the bubble, one `verticalScroll` | top-right icon; pager over the pages; uncapped |
| The pager | — | anchored to the message's **top**, so a resizing box never moves it |
| The operator's message | a bubble with a caption and a footer | a plain row |

## 10. What this does **not** change

- **The record.** `session.log` is unchanged. This is entirely how it is drawn — the evidence already
  has the turn boundaries and the ids the footer shows.
- **The footer**, which stays `id · time · thinking · output` on the agent's message (the operator's
  plain row has none). `PLAN-chat.md`'s footer rules still hold; the only change is that the id comes
  first and the date is gone (done, `0128a1f`).
- **The session-length trim**, which is a different setting (report 10) — though it now has the right
  unit to delete by, because a message is a turn.

## 11. Order of work

1. ~~**Regroup.**~~ — **done** (`6b377af`, `b4dfede`, `07067e4`). A message is a **burst**, not a
   turn: `AgentLog.TURN` is written before every request, so a call-then-speak reply is two turns and
   grouping by turn split a call from the words that closed it. `messages()` groups by burst, emits
   the operator's message per burst, appends every agent utterance as its own page (the earlier code
   kept one `words` slot and **overwrote**, losing all but the last), and marks the page that came
   after the last call as `closing`.
2. ~~**The cap, applied to the active page**~~ — **done**, with the hidden-lines sentence and
   `animateContentSize()` so the height change is smooth rather than a jump.
3. ~~**The fullscreen icon and the pager.**~~ — **done**. Top-right icon, pager anchored to the
   message's top row, fullscreen uncapped with a fresh scroll per page.
4. ~~**`LazyColumn` + open at the bottom.**~~ — **done**.
5. **The footer's context word** — `in context` / `forgotten`, from the row's id against the
   context log's ids (§7). Small, and it is what makes the forgetting visible without reading the
   struck-through text.
6. **The cached sizes** (§6) — the only item here that is an optimisation rather than a behaviour,
   and the one that needs the most care, because a cache that disagrees with the file is worse than
   no cache at all.

Steps 1 and 2 are the whole of both corrections. 3–5 are named in them and are independent.
