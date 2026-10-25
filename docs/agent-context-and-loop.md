# The agent's context and loop: what is built, checked against the rounds

Written after implementing the context rules, on branch `main`. Read this against
`PROPOSAL-agent-chat.md` (round 5, the current one) and the annotated rounds, not against an
older copy.

## Where the three layers come from, on every turn

| layer | file | when it is read | what it is |
|---|---|---|---|
| ① instructions | `<account>/system_prompt.txt` | at the **start of each turn** | the app's own: the tools, the rules, the shape of a turn |
| ② project | `projects/<p>/project_prompt.txt` | at the **start of each turn** | how to work in this project; the person's file |
| ③ working context | `projects/<p>/agent/context.log` | **every request** | the rows, which the agent may clear up |

**There is no projection file and no cached copy.** The request is composed from these three
sources at the moment it is built: `AgentPrompt.read()` opens both text files, `AgentLog` parses
`context.log` off the disk, and nothing is remembered between turns. Two consequences worth
naming, because both are tested:

- `context.log` **is** a projection, and it is the only one — the agent's own editable object.
  `session.log − context.log` is exactly what he threw away, which is the point.
- Editing a text file on disk — with the app's editor, or with a shell — is visible at the next
  turn, because the file *is* the source. A restore from history writes the same file, so the
  next turn is answered under the restored text.

The two layers are read **once per turn, not once per request**: one conversation is answered
under one brief. An edit made while the agent is working arrives at the next turn, not in the
middle of the current one.

`①` comes before `②` in the system message, and the separator between them is
`AgentPrompt.SEPARATOR`. A provider's implicit cache matches on the longest common prefix, so
editing ② costs a re-read of ② and of the context below it — never of ①. Nothing in the request
carries a clock: the repeated `wait` is appended to `context.log` **once**, and to `session.log`
every time.

## Reset

`agent reset` (`Agent.resetAgent(project)` → `AgentLog.reset()`) is the **person's** act and has
its own door:

- `context.log` becomes one row — `NOTE reset the working context was cleared` — written under the
  same id into both files, so the diff between them still means one thing;
- `session.log` is untouched: a reset is a fact about the working context, not a way to make
  history disappear;
- the row is not rendered into the request, so the model is not handed an app-authored sentence.

The agent's own clear-up is checked by `AgentLog.clearUpProblem` and is stricter: whole rows only,
a call with its result, and never the newest task. A context the agent can empty is a context it
can quietly restart from; a person restarting it is a different act.

## The loop

One `turn()` for both a burst and a poll (they differ by whether the person spoke), a `finally`
that trims the evidence and redraws the box however the turn ended, four named endings
(`asked`, `concluded`, `waited`, `wordsWithoutCalls`/`blip`/`tooIdle`/`overBudget` as `Stopped`),
and one place — `finish()` — that puts a turn on the trace, using the word and detail `End` owns.

| transition | what it writes | test |
|---|---|---|
| turn starts (burst) | seal, `TOLD`, trace line, `agent-request` | `AgentTest`, `AgentSessionFeatureTest` |
| turn starts (poll) | nothing; no `TOLD` | "a poll says nothing in the person's name" |
| `ask` | `CALL`, `ASK` | "ask ends the turn and the question is the seam" |
| `conclude` | `CALL`, `CONCLUDE` | "a concluded turn is idle and finished" |
| `wait` | first: the pair in both files; every repeat: session only | "wait ends the turn and is not repeated into the context" |
| terminating in company | refused as each call's result, turn continues | "a terminating tool called with others is refused" |
| words, no calls | `NOTE stopped`, `End.Stopped(LOOP)` | "an agent that answers with words and no call stops" |
| transport failure | `NOTE stopped`, `End.Stopped(retryable)` | "one dropped poll reconnects" |
| `IDLE_CALLS` reached | `NOTE stopped` | "calls that change nothing and ask nothing" |
| `MAX_REQUESTS` reached | `NOTE stopped` | "a turn that never ends stops at the request budget" |
| reset | one row in both files | "a reset clears the working context" |

## Still not built (and deliberately not faked)

- the foreground service (`specialUse` + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); the tick runs
  from a 15-second coroutine while the app is open;
- the **token** limits (trigger/hard) for `context.log` and the settings that hold them — round 5
  says the app trims the context at the hard limit, and the numbers are tokens, so nothing was
  invented in bytes;
- a UI for editing `project_prompt.txt` (it is an ordinary file, editable in the app's editor).
