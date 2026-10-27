# The chat: bubbles, pages, and what a footer says

Report 36, with the operator's answers of 2026-10-27 folded in. This is the specification for the
chat rebuild, and the reason each decision is what it is.

---

## The shape

The conversation is **a vertical scrolling list of bubbles**. One bubble is one thing that happened,
and the list lasts as long as the session.

**The unit of a bubble is a request and what came of it**, not a line and not a tool call:

| Bubble | Holds |
|---|---|
| The operator's | What the person wrote. One per conversation. |
| A working bubble | One tool call **and its result** — they are one bubble because they are one act |
| The last bubble | The agent's own words, with no call. Rendered as **markdown**, not as a line. |

**`ask` and `conclude` go entirely.** The tools, the `TERMINATING` set, the `SPEAKING` set, the
`ASK`/`CONCLUDE` row kinds, their handling in `Agent.messages()`, and their rendering. A turn now
ends the way a conversation ends: with the agent saying something. The rows those tools wrote are
**refused, not migrated** — a `session.log` containing one shows an unreadable row, which is the
honest outcome and consistent with there being no previous release to be compatible with.

**The token column goes.** It was there for the clear-up, and the clear-up is a sentence in the
context, not a number beside every line.

## The footer

One footer per bubble, on the bubble, saying **id · time · thinking · output**.

- **id** — the row's own number. It is how a person finds the row in `session.log`, which is what
  the box is a view of.
- **time** — *time only, not the date.* The date is the conversation's, not the bubble's; a list
  where every row repeats the same date is a column of noise.
- **thinking** and **output** — the tokens the model spent. **These do not exist yet.** `Reply` is
  `text + calls` and `usage` is never parsed. They arrive with streaming (`docs/PLAN-streaming.md`
  step 3), which is already assembling reasoning and content per chunk. Until then the footer shows
  id and time, and no number is invented to fill the space.

## Where the time comes from — and why it is `session.log`

`session.log` is the evidence: *every event, in the order it happened*. A chronology with no times
is the one thing it could not answer, so **it gains a time column**. `context.log` does not: the
model has no use for when a row was written, and that file is the model's view of its own memory.

This keeps the model's conversation byte-identical — `Agent.messages()` builds from a row's `body`,
and a time is not part of it.

**A correction worth recording:** the obvious place to put the clock was `context.log`, because that
is the file the chat was assumed to read. It is not. The chat box reads `session.log`, and
`context.log` is written for the model and never read back. Putting the clock in the wrong file
would have meant a footer with no time and a context with a useless column.

## What a result row does and does not say

**A `GOT` row does not name the call it answers.** It carries the tool name and the body. The pairing
is positional: `AgentChat.lines()` and `Agent.messages()` each keep a deque of unanswered calls and
pop the oldest when a result arrives. It works because calls and results are written adjacently, one
at a time — but nothing in the file *states* the pairing.

This is recorded here because the footer makes it matter: a bubble is a call **and** its result, and
that grouping is currently a convention held by two deque implementations. The plan does not change
it (the order is a rule the writer keeps), but a bubble that shows its call's id next to its
result's id makes the convention visible enough to be checked.

## The height cap

**Set in settings, in lines.** A bubble taller than the cap is truncated at the cap, and its last
line says that the message continues and must be expanded to be read in full.

Lines rather than a fraction of the screen because that is the unit a person reads in: "eight lines"
is a statement about the text, and "40% of the window" is a statement about the phone.

## The fullscreen view

A bubble's **header carries an item that opens the bubble fullscreen**, where its pages can be
flipped through. Fullscreen is not a bigger bubble: it is the same pages, one per screen, so a long
result is read by turning pages rather than by scrolling a tall box inside a taller list.

---

## Order of work

1. **`session.log` gains a time column.** `LogFormat`, the row regex, `Row`, and the writer. Old
   rows without a time read as unknown rather than wrong.
2. **`ask`/`conclude` removed** — tools, sets, kinds, message building, rendering. Rows of those
   kinds are refused.
3. **`AgentChat` rebuilt as bubbles**: a call and its result are one bubble; the last bubble is the
   agent's words.
4. **The footer**: id and time.
5. **The height cap** in settings, in lines, with the truncation sentence.
6. **The fullscreen page view** from a bubble's header.
7. **The token column removed.**
8. **thinking/output in the footer** — when streaming lands, not before.

## What this is not

- **Not a chat that streams.** Streaming is its own document; this rebuild draws what is recorded.
- **Not a re-reading of `context.log`.** The box stays a view of the evidence.
- **Not a bubble per log line.** A bubble per request-and-what-came-of-it; the lines inside it are
  that request's parts.
