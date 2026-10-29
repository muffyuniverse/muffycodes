# Compaction: when the app asks, and what `forget` is allowed to do

Written 2026-10-29 from the operator's three corrections. This is the design for the context clear-up;
it supersedes the paragraphs in `PLAN-chat-turns-and-pages.md` and `PROPOSAL-agent-chat-v6` that
describe it, and it is the authority for the code that follows.

**The corrections, verbatim.**

> and on every turn app checks cached context size to monitore when to fire off compaction request to
> the model, right?

> and we need to tell what is the current context size with the ask to forget so that it has an idea
> of where it is at the moment and where it should be, and that it should list all ids and id spans in
> one request to forget in bulk

> it should return error when agent tries to use it with context under the trigger limit

---

## 1. The app measures; the agent decides

Two hands, and the split is the whole design:

| Who | Does | Never does |
|---|---|---|
| **The app** | measures the context every turn, and **asks** when it is over the trigger | deletes a row the agent chose |
| **The agent** | decides which rows do not serve the task, and names them | measures, or decides when to be asked |

The app's own trim is the third hand: **forget compaction** (the agent names rows) and **trim
compaction** (the app drops the oldest whole bursts) are two hands doing one job. Compaction never
deletes anything — `forget` flips a row's `ctx` to `out` and the row stays. Only the trim removes
rows, and only when the record's own storage limit asks it to.

### One target, two triggers

The operator's correction, 2026-10-29:

> both forget and trim compactions should have same target but different triggers (so that forget
> triggers much earlier)

Three numbers, and they are not three independent settings:

| Number | Meaning |
|---|---|
| **Compaction target** | where the context should **end up** — shared by both hands |
| **Forget compaction at** | when the agent is asked to reach it |
| **Trim compaction at** | when the app reaches it itself |

Two destinations would let the app trim to a size the agent was never asked to reach, or ask the
agent to reach a size the app had already given up on. One destination means **whichever hand acts,
the context lands in the same place**.

**The only ordering required is that the target sits below each trigger that is on.** A target at or
above a trigger means the act that fires there removes nothing — the context is already under the
number it is being asked to reach.

**A trim below the forget point is a mode, not a mistake.** The operator: "trim compaction actually
can be set below forget and then it is only the app that handles the compaction (a feature, not a
bug) because forget is just never triggered." Somebody who would rather the app do the cutting than
have the agent choose says so by putting the trim first; the agent is simply never asked, and the two
hands never compete. The screen says this out loud when the numbers are in that order, because a
legitimate choice that looks like a mistake should be named either way.

**A target of zero is half the forget point**, derived rather than typed. A default that landed *on*
the trigger would ask the agent to cut to exactly the number that asked it, so the next row to arrive
would cross the trigger again and the ask would repeat. Half is the floor that makes one clear-up buy
real quiet.

## 2. The measurement is cached, and it is read every turn

- **`AgentLog.contextTokens()`** — the sum of the `size` column over the `in` rows, memoised against
  `context.log`'s own length and mtime. A forget, a trim, or a hand edit changes one of those and the
  next read measures again.
- **`Agent.size()`** asks it. This is the question asked on a timer, and re-reading and re-summing
  every row to answer it is what the memo exists to avoid.
- **`readOpening()`** does *not* ask it, deliberately: it has already read every context row to find
  the biggest ones for the clear-up line, so its sum is free there and the memo would mean reading the
  file twice.
- The check happens **once per turn**, at `readOpening`, before the loop — so a turn that grows past
  the trigger mid-way is asked at its next turn, not immediately. That is deliberate: the trigger is
  a budget for a request, and a request already on the wire cannot be un-sent.

## 3. It asks on every request while over — and stops the moment it is not

This is the part that makes the whole design economical, and the operator stated it as a rule:

> it should stop asking when context is below compaction trigger. that is explicitely done so that
> there is just one forget call and one cache miss

The condition is one expression, evaluated per request:

```
trigger > 0 && size > trigger && !trimmed
```

- **`size` is the measurement this request was built from** — not the size the turn opened at. The
  old code read `opening.size`, measured once before the loop and never changing, so within a turn the
  sentence kept being appended after the agent had already forgotten enough. It was stopped only by a
  separate `clearedUp` flag that required the app to *notice* a row leave the context — a second
  answer to the question the size already answers, and one that cost **two reads of the context file
  per turn**, which is precisely the cache miss the size memo exists to avoid. Both are gone.
- **`!trimmed`** — the app's own trim already ran, so the warning would describe a context that no
  longer exists.
- **Below the trigger there is no sentence at all.** One `forget` takes the context under the point,
  the next request measures it, and the turn goes quiet. No second call, no second measurement, no
  nagging a model into deleting rows that matter in order to satisfy a sentence that is already true.

## 4. What the ask says

The clear-up line goes in the request when `opening.size > limits.triggerAt`. It must give the agent
**three numbers and one command**, because a model told only "you are over your limit" has no way to
judge how much to remove:

1. **Where it is now** — the measured size when the turn began.
2. **Where it should be** — the trigger, stated as a target, and **a floor rather than a ceiling**:
   "get it to this or less, and further if the rows do not serve the task". Cutting past the target is
   wanted, not tolerated: a model told to stop exactly at a number keeps the near-irrelevant rows that
   come before it.
3. **How much room the endpoint has** — the ceiling, so a model can see the difference between a
   budget it is over and a window it would be refused for.
4. **One command, in bulk.** The ids and spans must be named **in a single `forget` call**. A model
   that forgets one row per request spends a request per row at exactly the moment the context is
   largest, which is the worst possible time to pay for a round trip.

The line also names the biggest rows by `context.log` line number, because the file cannot be read in
one go — a `read` stops at the tool's own limit — so a model deciding what to remove would otherwise
page through it a request at a time.

## 5. `forget` is unavailable unless the app asked

**`forget` refuses when the context is under the trigger.** This is the third correction, and it is a
rule about authority rather than about safety:

- **Compaction is the app's call, not the agent's habit.** A model free to forget whenever it likes
  will forget the moment a result is verbose, which is exactly when the result matters — and the
  person reading the record afterwards sees a conversation the agent pruned for reasons nobody asked
  for.
- **Under the trigger there is no problem to solve.** The context fits, the request fits, and the only
  effect of forgetting is to lose rows the person paid to put there.
- **It is refused, not hidden.** The tool stays in the spec — it is the tool the ask names — so a model
  that calls it early gets a sentence saying why, which teaches it the rule. A tool that vanished would
  look like a bug in the request.

The refusal says the two numbers, because a model that has just been told "no" needs to know by how
much:

```
1
Your context is about 12.0k tokens, under the 24.0k clear-up point, so nothing needs to go.
Forget only when you are asked to, and name every row in one call.
```

**The trigger is passed to the tools, not read by them.** `AgentTools` gains a `triggerTokens: () -> Long`
the way it already has `backgroundMs` — read per call, because the setting can change while an agent is
idle and the number that applies is the one in force when the call arrives.

## 6. The trim reaches the target in one pass

`trimContext` used to take a `budget` and consult it only to check it was not zero — then drop
exactly **one** burst. A context a hundred thousand tokens over its limit came down one conversation
per request, while the app kept asking itself to trim.

It now walks whole bursts from the newest backwards via `keepNewest` and stops where the next one
would not fit, so it converges in a single act by construction. **Not a loop**: a loop that re-trimmed
until the size fitted would empty the context against a limit below what the instructions alone cost,
and could never converge.

**Whole bursts, newest kept.** A conversation is never cut in half, and the thing that just happened
is never deleted — a size limit does not get to remove the task being worked on, which is the same
rule `forgetProblem` enforces for the agent.

## 7. What this does not change

- **`forget` still cannot carry text.** It names ids, so compaction cannot alter what the record says.
- **The three refusals stay**: an unknown id, the newest task, and half a pair.
- **The trim stays** — the app's own last resort against the hard limit, one burst at a time.
- **`ctx = out` stays the only trace of a compaction**, so the audit remains a column rather than a
  claim.

## 8. Order

1. `AgentTools.triggerTokens` and the refusal in `forget` — the rule.
2. The clear-up line's wording: the three numbers, and "in one call".
3. A test for each: the refusal's numbers, and that the ask names the current size and the target.
