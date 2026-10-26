# The agent's context and loop: what is built, checked against the rounds

Written after the audit, on `main`. Read this against `PROPOSAL-agent-chat.md` (round 7, the
current one) and against the `-annotated` rounds, never against an older copy. The element-by-element
evidence — every rule, its test and its trace line — is in `docs/audit-agent-chat.md`.

## Where the three layers come from, on every turn

| layer | file | when it is read | what it is |
|---|---|---|---|
| ① instructions | `<account>/system_prompt.txt` | at the **start of each turn** | the app's own: the tools, the rules, the shape of a turn |
| ② project | `projects/<p>/project_prompt.txt` | at the **start of each turn** | how to work in this project; the person's file |
| ③ working context | `projects/<p>/agent/session.log` | **every request** | the rows whose `ctx` is `in` |

**There is no projection file and no cached copy.** The request is composed from these three
sources at the moment it is built: `AgentPrompt.read()` opens both text files, `AgentLog` parses
the one record off the disk, and nothing is remembered between turns. Two consequences worth
naming, because both are tested:

- The context **is** a filter over the record — `rows.filter { it.ctx == IN }` — not a second file.
  What the model is sent and what a person reads are the same rows, and "what did the agent throw
  away" is `ctx == out`.
- Editing a text file on disk — with the app's editor, or with a shell — is visible at the next
  turn, because the file *is* the source. A restore from history writes the same file, so the next
  turn is answered under the restored text.

The two layers are read **once per turn, not once per request**: one conversation is answered
under one brief. An edit made while the agent is working arrives at the next turn, not in the
middle of the current one.

`①` comes before `②` in the system message, and the separator between them is
`AgentPrompt.SEPARATOR`. A provider's implicit cache matches on the longest common prefix, so
editing ② costs a re-read of ② and of the rows below it — never of ①. Nothing in the request
carries a clock: the keepalive's own rows are written `app` and never enter the request, so every
ping of one conversation is the same bytes.

**A default that changed is replaced; an edited file never is.** ① is written when the file is
missing, and also when it is byte-for-byte one of the texts the app shipped before
(`AgentPrompt.PREVIOUS_SYSTEM`). That is how the one-record round reached a phone that already had
the old instructions — which told the agent to edit `agent/context.log`, a file that no longer
exists.

## What the record's columns mean

```text
14:02:11.410    1 TOLD    in  operator  rename milk.txt to dairy.txt
14:02:18.902    2 CALL    in  tree      {"path":"GitHub"}
14:02:18.955    3 GOT     in  tree      0 · GitHub/ GitHub/milk.txt
14:06:11.007    4 NOTE    app ping      kept warm
14:11:03.418    5 CALL    in  forget    {"ids":[2,3]}
14:11:03.421    6 GOT     in  forget    0 · forgot 2 rows. The task is still 1.
```

| column | meaning |
|---|---|
| time | when the app wrote it. The model never sees it: `TURN`, the keepalive's `ping`, the gap row and the trim markers are `app` |
| id | the row's handle. `forget` takes ids; the box and `read` take **lines**, which is why the clear-up index names both |
| kind | `TOLD` · `TURN` · `CALL` · `GOT` · `ASK` · `CONCLUDE` · `NOTE` (`ping` and `woke` are the app's, and `woke` is the one note the model is sent) |
| ctx | `in` — the model is sent it · `out` — the agent called `forget` on it · `app` — the app wrote it |
| name | the tool, the operator, or the app's own word (`turn`, `trimmed`, `gap`, `retry`) |
| detail | the body, escaped: a newline is `\n` |

## Reset

`resetAgent` (`AppViewModel` → `AgentLog.reset()`) is the **person's** act and has its own door:

- every row that was `in` becomes `app` — the app took it out, the agent did not judge it — so a
  reset can never be read as the agent's clearing;
- a `NOTE reset` marker is added, and it is not rendered into the request;
- nothing is deleted: `session.log` keeps every row.

The agent's own clear-up is `forget` by id, checked by `AgentLog.forgetProblem`: an id must be in
the context, the newest task stays, and a call and its result go together. A context the agent can
empty is a context it can quietly restart from; a person restarting it is a different act.

## The loop, in the order the app does it

1. `AgentPrompt.read` — ① and ②, once.
2. `beginSession` — seal a call the app was killed between (see below).
3. `told` — the operator's words, in the context.
4. Per request: `TURN` row (`app`) → `build` → the door → the reply's calls.
5. Over the hard limit, `trimContext` marks the oldest whole conversation `app` and writes a
   marker saying how many rows went; the next request reads the file again.
6. Over the trigger, the app appends one line to the system message — the sizes, the target, and
   the biggest rows by **line** and id. The same bytes for the rest of the turn.
7. Every call: a `CALL` row, the tool, a `GOT` row whose first line is `0` or `1`.
8. An ending (`ask`, `conclude`, words alone, stopped) — one trace line, and the session moves.
9. `trimSession` — the app's own limit on the evidence, at a burst boundary, oldest first.

**A call with no result is an illegal conversation**, so two things keep the record legal: a
terminating call is never sealed (`dangling` skips it), and a turn that was cut short answers every
call it carried with `1 interrupted` before the next request. `beginSession` seals what a kill left
behind, and puts the seal in the context only when its call is in the context.

## What a person debugging should look at

| question | where |
|---|---|
| what was the model sent? | `agent/session.log`, the `in` rows in order |
| what did the agent decide to forget? | the `out` rows, in order |
| what is the app's own bookkeeping? | the `app` rows: `TURN`, `ping`, `trimmed`, `forgot`, `gap`, `retry`, `reset` |
| which request was which? | `TURN` rows, and the box's `‹ n of m ›` |
| which model and endpoint answered? | the trace, `agent-request`, once per turn the person started |
| why did a turn stop? | the `stopped` note in the record, and the trace's `agent … stopped:` |
| why is a conversation not warm? | the trace, `agent-warm`, `agent-warm-failed` and `agent-gap` |
| what did a press do? | the trace, `TAP …` then its answer |
