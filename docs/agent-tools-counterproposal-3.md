# Counterproposal 3: settled, plus the two consequences nobody has priced yet

*Answers the four comments on [counterproposal 2](agent-tools-counterproposal-2.md). Three of the
four are settled and need no argument. The fourth exposed a hole in my own draft, and answering your
question about `edit` + grep exposed a second. Both are below, because they change the work.*

---

## 1. Settled, as approved

| decision | ruling |
| --- | --- |
| `copy` | **a flag on `move`** — `{"path","into","copy":true}` |
| collisions | **strict in both modes** — refuse an occupied destination |
| `edit` selection | **`from`/`to`/`at` only; no `grep`** |
| `background` | **any command**, except the three that end a turn and `background` itself |

### "And no grep — `edit` is strictly per line, right?"

**Yes, and it has to be, for a reason stronger than tidiness.** A range renames a block; a pattern
matches scattered lines. Those are different edits:

- `from:10,to:12` → the twelve lines are **one block**, replaced by one block. Unambiguous.
- `grep:"TODO"` applied to a file → **N scattered lines**. To delete them you must know whether the
  result is "N disjoint blocks" (delete each) or "one selection" (delete a span that swallows
  everything between two matches). One call, two meanings, and the model has no way to tell which it
  asked for.

The scattered case — where grep genuinely helps — is *deriving lines*, not *editing them*: find the
matches with `read {"grep":…}`, note the numbers it prints, then `edit {"from":N,"to":M}`. Two
calls, each unambiguous, and the line numbers `read` already returns are exactly what the second one
needs. So: **`edit` never takes a pattern on its own file.** `find`/`replace` stays as it is — it
replaces *all* occurrences of a string, which is one meaning — and the new range/`at` shapes are
line-based.

The one place a pattern *does* appear in `edit` is inside the nested `read`, and there it selects
lines in the **source** file, which is the unambiguous direction.

## 2. `background` with any command — and the hole it opened

Your reasoning is right and mine was lazy: the device is weak, files are large, and "grep an
enormous log" is exactly the case. So `background` takes any command.

**But it does not currently take "any command".** It has a `when` with two branches:

```kotlin
when (tool) {
    "clone"   -> clone(JSONObject(inner.args), Deadline(backgroundMs()))
    "restore" -> restore(JSONObject(args), Deadline(backgroundMs()))
    else      -> refusal("There is no slow command called $tool")
}
```

Every other tool refuses. Making it accept any command means the background path must run the
**same** dispatch as the foreground — one `run(call)`, shared — rather than a second `when` that can
drift from it. That is the actual work item, and it is worth stating because a second dispatch table
is how a tool gets added to one path and not the other.

### The interaction this creates, which changes §5 of the last draft

A backgrounded command's result goes **into the file, unmeasured**. The char limit applies to results
that go into the conversation. So:

- `background {"tool":"read","args":{"path":"huge.log"}}` writes the whole thing to the file —
  **no too-big error**, because nothing is going into the context.
- Then `read` that file: *that* result is measured, and if it is still too big it is the error.

That is coherent, and it is a genuinely useful pair: file first, narrow second. But it means the
too-big error is a property of **where the result is going**, not of the command. Written plainly:
*an oversized result is refused when it would enter the conversation, and written in full when it
would go to a file.*

**Consequence for the tool description:** `read` of a huge file must not be offered as one call
without saying that it can only succeed into a file. The description should say: *a file too large to
come back as a result can be read with `background`, or narrowed with a range.*

### The second hole: a paste that arrives as text still has to fit

You asked whether `edit`'s nested `read` is bounded. It is not — and it must not be, which I said,
but I did not check *where* the text comes from.

- `replace: {"tool":"read", …}` — the text never becomes a result, so any size. Correct.
- `replace: "<200 KB of text>"` — the model has to *type* the replacement into the call, which means
  it went through the context first and the limit already refused it. So the literal form is bounded
  by the same rule, for free.

No change needed; stating it so the two forms' limits are not guessed at later.

### `MAX_SCAN` — the one thing that contradicts the whole plan

`read` and `tree` refuse to search any file over **2 MB** (`AgentTools.kt:56`, checked at `:335`).
That check exists because loading a huge file into memory on a phone is the bug. But a background
`read` of a huge file is now a *supported* thing, and it will hit that wall first:

> "huge.log is too big to search"

That sentence is about **searching**, and it is wrong the moment the intent is "write it to a file".
Decision needed: does `background` bypass `MAX_SCAN` (stream the bytes to the file instead of reading
them into memory), or does `MAX_SCAN` remain a hard wall and the error re-worded? The first is more
work; the second makes "grep an enormous log" — your example — still impossible at 2 MB and above.

## 3. The work, in order

1. **One result limit, and it is where the result is going.** Delete `MAX_MATCHES` and `take(20)`;
   over the limit → error asking for a narrower selection.
2. **One selection language**: `from`/`to`, both inclusive, `to` absent = to the end; `grep` applied
   **inside** the range, in that order, everywhere (`read`, `history`, `tree`). `edit`: `from`/`to`
   and `at`, never `grep`.
3. **`edit` takes a `read` call as its replacement** (text or call), plus `at` for insertion.
4. **`move` gains `copy:true`**; strict collision in both modes; the agent never silently renames.
5. **`background` runs any command** by sharing the foreground's dispatch — no second `when`.
6. **`clone` removed**; `read` gains `revision`; `history` greps content and loses its silent cap.
7. **`MAX_SCAN`** decided (§2) before item 5 is finished, because a backgrounded `read` of a large
   file is the case that runs into it.

## 4. The one question left

**`MAX_SCAN` at 2 MB.** Bypass it for a result going to a file (stream, don't load), or keep it as a
wall and re-word the sentence? Everything else in this document is approved and ready to build.
