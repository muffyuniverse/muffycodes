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

**The conversation ends with the agent's words, and then it is idle.** That is not a new behaviour to
build — it is what the loop already does. `wordsWithoutCalls()` sees a reply with text and no calls,
records the words as the agent's own note, and returns `End.Concluded`, which moves the session to
`IDLE`/`FINISHED` with `warmFrom = now`. Idle is exactly what the keepalive polls. So the last bubble
is followed by warmth, not by a stop.

**The session stops for exactly two reasons**, both already implemented:

- **The operator presses Stop the agent** — `End.Stopped(BY_OPERATOR)` → `state = STOPPED`, and
  `End.onto()` refuses to move a session the operator stopped, so nothing can quietly switch it back on.
- **The wait limit is reached** — `warmDue(..., forAt = warmMs)` answers false once the wait has run
  its length, and polling simply stops. Deliberately *not* the same as stopping: a session that ran out
  of warmth is not a session that failed.

**`ask` and `conclude` go entirely.** The tools, the `TERMINATING` set, the `SPEAKING` set, the
`ASK`/`CONCLUDE` row kinds, `End.Asked`, their handling in `Agent.messages()`, and their rendering.
A turn now ends the way a conversation ends: with the agent saying something. The rows those tools
wrote are **refused, not migrated** — a `session.log` containing one shows an unreadable row, which
is the honest outcome and consistent with there being no previous release to be compatible with.

**Removing `CONCLUDE` removes a duplicate, and that is worth stating.** The words of a conclusion are
already on the record as the agent's own note (`log.note("agent", …)`), which is what the model reads
back. The `CONCLUDE` row was bookkeeping written beside them *on purpose* so the box and the evidence
got an ending — and a second row carrying the same paragraph is exactly the duplication the app
avoids elsewhere. So the ending does not lose its words; it stops saying them twice.

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
is the one thing it could not answer, so **it gains a time column** — in the same edit that adds
`answers` (see above). `context.log` does not: the model has no use for when a row was written, and
that file is the model's view of its own memory.

This keeps the model's conversation byte-identical — `Agent.messages()` builds from a row's `body`,
and a time is not part of it.

**A correction worth recording:** the obvious place to put the clock was `context.log`, because that
is the file the chat was assumed to read. It is not. The chat box reads `session.log`, and
`context.log` is written for the model and never read back. Putting the clock in the wrong file
would have meant a footer with no time and a context with a useless column.

## The `answers` column — the pairing becomes a record

**A `GOT` row does not name the call it answers.** It carries the tool name and the body. The pairing
is positional: `AgentChat.lines()` and `Agent.messages()` each keep a deque of unanswered calls and
pop the oldest when a result arrives. It works because the writer interleaves — `CALL GOT CALL GOT` —
so the deque is never more than one deep.

**The agent can call several tools in one reply, and there is no cap.** `Agent.work()` loops over
`reply.calls` and writes a `CALL`/`GOT` pair per call. A reply with three calls writes six rows. Two
tests already pin the *shape* ("two calls in one reply are one turn") but **nothing pins the
interleaving** — and the deque silently mispairs if it ever changes. A parallel implementation that
wrote all the calls and then all the results would pair call 1 with result 3, and the box would show
the wrong result under the wrong command with no indication anything was wrong.

**So `session.log` gains an `answers` column.** A result names the call it answers:

```text
  41   812 CALL    in  -     edit        {"path":"milk.md","content":"..."}
  42   310 GOT     in  41    edit        0 · wrote 4 lines
```

Three consequences, and each removes a guess:

1. **The deques go.** `AgentChat.lines()` and `Agent.messages()` look the call up by id instead of
   assuming the next result belongs to the oldest open call.
2. **A dangling call is a fact, not an inference.** Today `AgentChat.kt:186` renders "a result whose
   call is not in this conversation" by *not finding a pending call* — a conclusion drawn from the
   absence of a deque entry. With the column, a result says which call it answers and the answer is
   either there or not.
3. **A mismatch becomes detectable.** A result naming a call that does not exist is a broken record,
   and it can be refused rather than drawn as something plausible.

**Where the column goes, and why not the body.** The columns are `id size kind ctx answers name
detail`. `answers` sits beside `ctx` because it is the same kind of thing: the app's own bookkeeping
about a row, not the model's content. **It must not go in the body** — the model is sent a row's
body, and which row answers which is a fact about the record, not something the model needs in order
to work. `-` means "this row answers nothing", which is every row that is not a `GOT`.

**One format change, not two.** The time column (below) and `answers` are one edit to the same row
format: one `LogFormat`, one row regex, one round of test fallout. Splitting them would mean editing
the regex twice for no benefit, and a file format is a thing to change once.

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

1. ~~One format change to `session.log`: a time column and an `answers` column~~ — **done**
   (`ee3e6ec`, `c306152`, `a720fea`, `9af5ab5`, `a70a4e0`). Rows are now
   `time id size kind ctx answers name detail`. Four readers were pairing by position and all four
   now join by id: `AgentChat.lines()`, `Agent.messages()`, `AgentLog.forgetProblem()` and
   `AgentLog.dangling()`. `got()` has **no default** for `answers` — a default would let a caller
   write an orphan result, which readers then refuse to send, so the mistake would surface as a hole
   in a conversation rather than at the call site.
2. **`ask`/`conclude` removed** — tools, sets, kinds, message building, rendering. Rows of those
   kinds are refused.
3. **`AgentChat` rebuilt as bubbles**: a call and its result are one bubble, joined by `answers`
   rather than by position; the last bubble is the agent's words.
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
