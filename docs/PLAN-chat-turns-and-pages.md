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

## 2. Height: a message is as tall as its tallest page

**The rule.** A message's height is set by its **tallest page** — the page with the most content —
and every page is drawn in that height. Paging therefore never changes the message's height, and the
list never jumps as a person turns pages.

That is the whole point of it: a pager whose box resizes on every page makes the list beneath it
move, and the reader loses their place in the conversation to follow the text.

## 3. The height cap, in lines, from settings

- **Set in settings, as a number of lines.** `bubbleLines` already exists
  (`DEFAULT_BUBBLE_LINES = 0` = no cap). Lines rather than a fraction of the screen because "eight
  lines" is a statement about the text and "40% of the window" is a statement about the phone.
- **When the message's height exceeds the cap**, only the first part is shown, and **the last visible
  line says how many lines are hidden and that expanding will show them.** A message that simply
  stopped would be indistinguishable from one that finished — the sentence is what makes truncation
  honest.
- **The cap applies to the tallest page**, because that is the message's height (§2). So a message
  is truncated when *any* page is taller than the cap, and the count in the sentence is that page's
  hidden lines.
- **`0` means no cap**, and then the sentence never appears.

## 4. Fullscreen: an icon in the message's top-right corner

- **A fullscreen icon sits in the top-right corner of the message** — not in a menu, not on the
  footer.
- It opens the message fullscreen, where:
  - **any page can be scrolled fully** (no cap, the whole page), and
  - **pages can be swapped** — ‹ › between the pages of that one message.
- Fullscreen is **not a bigger message**: it is the same pages, one at a time, read without the cap
  that applies in the list.

## 5. Opening the chat pins to the bottom

On open, the list is scrolled to the **latest message**. "Pinned" means the initial position is the
bottom; it is not a lock that fights the person's scrolling afterwards.

This is why the list is a `LazyColumn` rather than the current `Column` inside a height-capped
`BoxWithConstraints`: a `Column` inside `verticalScroll` composes every message, so a long session
gets slower the longer it runs, and "scroll to the bottom" has to measure the whole thing first.

---

## 6. What this changes in the code

| Where | Now | Needed |
|---|---|---|
| `AgentChat.Bubble` | one bubble = one call+result, or the agent's words | one bubble = one turn; `pages: List<Page>`, the words as the last page |
| `AgentChat.bubbles()` | emits per `CALL`/`GOT`; skips `TURN` | groups by `TURN`; `TURN` is the divider |
| `AgentChatScreen` | `Column` + `verticalScroll` in a 75%-height box | `LazyColumn`, scrolled to the last index on open |
| The cap | `bubbleLines` truncates a single `detail` string | applies to the tallest page; the sentence counts that page's hidden lines |
| Fullscreen | a `TextButton`/tap on the bubble, one `verticalScroll` | top-right icon; pager over the pages; uncapped |
| The operator's message | a bubble with a caption and a footer | a plain row |

## 7. What this does **not** change

- **The record.** `session.log` is unchanged. This is entirely how it is drawn — the evidence already
  has the turn boundaries and the ids the footer shows.
- **The footer**, which stays `id · time · thinking · output` on the agent's message (the operator's
  plain row has none). `PLAN-chat.md`'s footer rules still hold; the only change is that the id comes
  first and the date is gone (done, `0128a1f`).
- **The session-length trim**, which is a different setting (report 10) — though it now has the right
  unit to delete by, because a message is a turn.

## 8. Order of work

1. **Regroup.** One message per turn, pages inside it, the words last. Keep the current drawing (the
   pages stacked). Ship — this is the shape the operator asked for and it needs no new UI.
2. **The cap, applied to the tallest page**, with the hidden-lines sentence.
3. **The fullscreen icon and the pager.**
4. **`LazyColumn` + open at the bottom.**
5. **The operator's row as a plain row.**

Steps 1 and 2 are the whole of both corrections. 3–5 are named in them and are independent.
