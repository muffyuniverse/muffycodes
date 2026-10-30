# Streaming, and what the footers say while it happens

> **COMPLETE — this is history, not a work list.** Everything specified here is in the app; the
> reasoning is kept because it is not recoverable from the code. The current state of every report
> is in `AUDIT-2026-10-29-consolidated.md` §3. See `docs/README.md` for which document is true.


Two things that belong together, because the second is what makes the first worth having: streaming
gives you a **signal**, and the footer is where a person **sees** it.

---

## Part 1 · Why streaming, now

The app sends `"stream": false`. Everything in this document follows from that one field.

**What it costs today:**

1. **No acknowledgement.** OpenRouter sends nothing until the completion is finished. A dropped
   request and a model thinking hard are the same silence, so one number (`timeoutSeconds`) has to
   serve two opposite situations: retry a dead socket *now*, or wait for a diligent model. Retrying
   a diligent model throws away work already paid for.
2. **A diligent model can be killed.** Measured on `moonshotai/kimi-k3.1-pro` at effort `max`:
   **19 m 8 s, 53,453 reasoning tokens** for one request. Any setting in the current 60–1800 s range
   kills that request, and the money is spent anyway.
3. **Reasoning is paid for and discarded.** `"reasoning": {"exclude": true}` means the tokens are
   generated and billed but not returned. There is no way to see that anything is happening.

**No reason for `stream: false` is recorded.** The operator confirmed there was no design reason —
it was simply the simpler thing to parse. The old decision note in memory
(`[[muffycodes-agent]]`, 2026-10-25) argued streaming buys "a faster-looking empty screen" because
the unit of progress is a tool call. **That argument is superseded**: the unit of progress for a
*person watching a slow model* is not a tool call, it is evidence that the model is alive.

---

## Part 2 · The three timeouts, and the acknowledgement

`stream: true` gives two distinct signals, and each gets its own number:

| # | Setting | Bounds | When it trips |
|---|---|---|---|
| 1 | **Connect** | The TCP socket. 20 s, unchanged, not exposed. | The network is down |
| 2 | **Ack** | Silence *before the first chunk*. | The provider never took the request. **Retry is free** — nothing was generated |
| 3 | **Idle** | Silence *between chunks*. | The stream died mid-answer. Distinct from a slow model, which keeps sending |

**Setting 3 replaces today's `timeoutSeconds`.** The current single number disappears, because with
a stream it no longer means anything coherent: it would be a limit on the total, which is exactly
the limit that kills a diligent model.

**Two details that must not be glossed over:**

- **The first chunk is not instant.** OpenRouter may hold the connection while it queues, and some
  upstreams buffer their first delta. So the ack timeout cannot be tight; it is a genuine "too long
  to still be silence" number, not "one second".
- **A total cap may still be wanted**, separately and off by default — a runaway model that streams
  for an hour is alive the whole time and trips no idle timeout. It is a different setting with a
  different name, and it should not be confused with either of the above.

---

## Part 3 · The footer: what the model is doing between answers

**This is the item the operator asked for.** The mechanism is already proven — it is how the kimi
audit was watched — and it belongs in the message bubble's footer.

### What the proven mechanism actually did

`/root/audit/ask.py` read the SSE stream and printed, **every 30 seconds**, a line like:

```text
   30s  answer=0c  thinking=12060c
   60s  answer=0c  thinking=28640c
   90s  answer=0c  thinking=43974c
```

38 such lines over 19 minutes, ending at `reasoning_tokens: 53453`.

**Correction to how I first described it:** it counted **characters**, not tokens, and the token
totals arrived only at the end in the `usage` block. Per-chunk `usage` is not guaranteed on
OpenRouter, so a per-chunk *token* count would be an estimate dressed as a measurement. Characters
are what the stream actually carries.

### What the footer shows

On a message bubble's page, while the turn is still streaming:

| Field | Source | Why |
|---|---|---|
| **Time in this request** | The app's clock, from the moment bytes went out | The number that answers "how long has it been" |
| **Thinking** | Sum of `delta.reasoning` / `delta.reasoning_details[].text` lengths | The observable form of "diligent" |
| **Answer so far** | Sum of `delta.content` lengths | Distinguishes "thinking" from "writing" |
| **Tool calls so far** | Count of assembled `delta.tool_calls` | The unit of progress the agent actually acts on |

And **when the stream ends**, the footer keeps the final numbers and adds the real ones from
`usage`: prompt, completion, reasoning tokens, cost. That is the difference between a live estimate
and a recorded fact, and the footer should not blur them — the live counts are characters, the final
ones are tokens, and the label says which.

### Why the footer, and not somewhere else

Because the question "what is it doing?" is asked while looking at the conversation, and the answer
must not require opening another screen. It also gives the timeout settings something to point at:
"past N s with no chunk" is a rule a person can *watch happen*.

### The one thing this must not become

**A progress bar that implies progress.** A model that has streamed 40,000 reasoning characters has
not necessarily done 40% of anything — there is no denominator. The footer shows **elapsed and
produced**, never a percentage and never an estimate of time remaining.

---

## Part 4 · What has to be built

### The reply path

`parseReply(exchange(...))` currently parses one complete JSON body. It becomes:

1. **Read SSE lines** from the socket (`data: {...}` until `data: [DONE]`).
2. **Accumulate `delta.content`** into the reply text.
3. **Accumulate `delta.tool_calls`** — these arrive **fragmented** and must be joined by `index`,
   then by `id`, `function.name`, `function.arguments`. This is the part that is easy to get wrong
   and has bitten this codebase before (`sorted toolIdx keys — non-contiguous indices silently
   dropped`).
4. **Count** reasoning, content and tool calls as they arrive, and hand each count to a listener.
5. **Handle a mid-stream error**: an SSE `error` event, or a stream that ends without `[DONE]`.
6. **Keep `Refused(code, ...)`** for a non-200 that arrives before any stream starts.

### The seams that make it testable

- **The SSE parser is a pure function** over a sequence of lines → deltas. It must not open a
  socket, so it can be tested with a recorded stream, including the fragments-in-tool-calls case.
- **The listener is an interface**, so a test asserts what the footer was told without a UI.
- **A recorded stream fixture** from a real response, committed, so the parser is tested against
  reality rather than against my idea of it.

### What does not change

- `ping()` keeps `max_tokens: 1`. It may stay non-streaming — it reads no answer, and a stream would
  be more work for less. **Decided at implementation time, recorded here so it is a decision.**
- The request body stays otherwise identical: same tools, same provider pin, same effort. A
  provider's cache matches the longest common prefix, so the prompt must not change shape.
- `Secrets.scrub` and the error sentences stay.

### The settings screen

- `timeoutSeconds` is **replaced** by two fields: *"Before the first word (seconds)"* and
  *"Between words (seconds)"*.
- Both go in the existing **"While it talks to the model"** group, in seconds like everything else.
- The line that currently says *"A dropped request and a model thinking hard look the same from
  here"* **is deleted** — with streaming, they no longer do, and leaving it would be a stale sentence
  of exactly the kind this session has been removing.

---

## Part 5 · Order of work — **done**

1. ✅ **The SSE parser**, pure, with a recorded fixture and the fragmented-tool-call case.
   `Stream.kt` + `StreamTest`, three recordings under `app/src/test/resources/streams/`.
2. ✅ **`exchange` streams**, behind the parser; `parseReply` rebuilt from assembled deltas.
   `OpenRouterDoor.streamed()` reads the socket; `Streamed.reply()` assembles the answer.
3. ✅ **The listener**, `OpenRouterDoor.listener`, fed the stream and the elapsed time per chunk.
4. ✅ **The three timeouts** — connect, ack, idle. The ack/idle distinction is `stall()`, a function
   of one boolean, so both branches are tested directly rather than by waiting on a socket.
5. ✅ **The footer**, `chatLive`: elapsed, characters, thinking, calls, and the `usage` facts at the
   end. Shown where the word "working" used to be.
6. ✅ **The settings**, two fields where one was, and the stale sentence deleted. The test that
   asserted that sentence was *displayed* now asserts it is **gone**.
7. ✅ **`exclude: false`**, so reasoning is visible rather than merely counted.

**Acceptance — how each is met.** A stall before the first chunk trips *ack*: `stall()` on a stream
with nothing in it returns `Unacknowledged`, and a comment line counts as an ack because an upstream
that is queueing sends one. A long stream trips nothing: only the *gap* is bounded, so steady output
never accumulates against a clock. A stall mid-answer trips *idle*: `stall()` on a stream that has
content returns `Idle` with what had already arrived. The footer shows all of it live, in characters,
because a per-chunk `usage` block is not guaranteed and a token count there would be an estimate
dressed as a measurement.

**One thing deliberately not done.** There is no total cap. A turn can now run for as long as the
model keeps producing, because that is what a diligent model looks like — bounding the total is a
different setting with a different meaning, and folding it into the idle clock would put the old
problem back.

---

## Part 6 · What this is not

- **Not a progress bar.** No percentage, no estimate of remaining time — there is no denominator.
- **Not a reason to keep `exclude: true`.** Counting reasoning characters while hiding them is
  worse than showing them: the person pays for the tokens either way.
- **Not a rewrite of the turn loop.** `Agent` still gets a `Reply`; only how the `Reply` is
  assembled changes.
