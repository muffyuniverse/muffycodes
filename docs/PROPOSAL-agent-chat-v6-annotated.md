# The agent chat — sixth round: one record, one column

Status: **built.** Round 5's two files are gone: there is one record, and whether the model is sent
a row is a column on the row. This supersedes round 5 §0 and §4.2 — the audit no longer subtracts
anything — and it keeps every rule of rounds 1–4 that is not about file layout.

Round 5 is kept as `-v5-annotated`.

---

## 0. One record

```text
files/accounts/<login>/
  system_prompt.txt            how to use this app: the tools, the rules, the shape of a turn
  model_conf.json
  projects/shopping/
    project_prompt.txt         how to work in this project
    agent/
      session.log              every event, in order, and what the model is sent
    GitHub/                    the worktree; his edits become commits here
```

One file, one row per event, five columns plus one:

```text
14:02:11.410    1 TOLD    in  operator  rename milk.txt to dairy.txt
14:02:18.902    2 CALL    in  tree      {"path":"GitHub"}
14:02:18.955    3 GOT     in  tree      0 · GitHub/ GitHub/milk.txt
14:06:11.007    4 CALL    app wait      {}
14:11:03.418    5 CALL    in  forget    {"ids":[2,3]}
14:11:03.421    6 GOT     in  forget    0 · forgot 2 rows. The task is still 1.
```

| ctx | meaning | who put it there |
|---|---|---|
| `in` | the model is sent this row | the turn, or the agent's own words |
| `out` | the model is not sent it | **the agent, by `forget`** |
| `app` | the model is not sent it | the app: a poll's repeats, a turn boundary, a trim, the reconnect line, a reset |

**The request is ① + ② + the `in` rows.** Nothing else.

## 1. Why one column instead of two files

Round 5 kept the context as a second file and defined the audit as the difference between the two.
That worked, and it cost a rule for every row the app wrote to only one of them — plus, because a
removal was a *text edit*, four more: whole rows only, a final newline, a line-by-line comparison
to prove nothing was rewritten, and an escaping path that had to round-trip exactly.

A column deletes that class of problem:

- **Removal is a set of ids.** `forget` carries no text, so compaction cannot change what the record
  says — it is not that the app checks; the text is not offered.
- **The judgement is stated, not inferred.** `out` *is* the agent's decision, written at the moment
  it is made. No subtraction, and no exceptions to name: a row the app wrote is `app` from birth.
- **A reset cannot be misread as a decision.** It marks `app`, so the person's act never appears in
  the agent's judgement.
- **Nothing is re-rendered.** A row carries its own line, and a flip rewrites four characters of the
  ctx column; the name and the body are carried over byte for byte.

## 2. Compaction: `forget`

The agent calls `forget` with ids. The app refuses only three things:

1. an id that is **not in the context** — already out, or never there;
2. **the newest task**;
3. **half a pair** — a call without its result, or a result without its call.

It answers with what it did, and which task is still standing: `0 · forgot 2 rows. The task is
still 1.` The forgotten rows stay in the record, marked `out`, with their text — so a drop that
turned out to matter can still be read afterwards.

**This is the audit, and it is the whole of it.** In every other agent design compaction is a
summary: the model writes one and the source is gone, so "I kept what mattered" can be neither
proved nor disproved, and a forgotten instruction is indistinguishable from an instruction that
never arrived. Here the answer is a column: `thrownAway()` is the `out` rows, in order.

## 3. What went with the second file

- `rawRow`, and the double-escaping hazard it existed to avoid.
- The final-newline rule, the "same lines in the same order" comparison, and the in-flight-call
  exception they needed.
- `byTheApp` and the reset boundary — a table of exceptions that existed only to make a subtraction
  mean what a column now says outright.
- `Workspace.writeRecord`: the one door through which the agent's compaction used to reach a text
  file. Nothing outside [AgentLog] writes the record now, so the person and the agent are under the
  same rule — and that is checked once, in `Workspace.write`.

## 4. What did not change

- **The poll is the same bytes.** A repeated `wait` is written `app`, so it never enters the request;
  nothing in the request carries a clock.
- **Placement.** ① then ② then the rows, so a compaction edit invalidates the conversation and never
  the instructions.
- **The trim.** The app's own limit still drops the oldest whole messageboxes — the only place rows
  leave the record for good, and therefore the audit's horizon: what the agent forgot survives until
  the session's own size limit takes it, and not for ever.
- **States, reminders, the ladder, the pin, the lock, the record's format and the tests**, as in the
  rounds this document corrects.

## 5. The record is the app's, and the person cannot save over it

`agent/session.log` opens in the editor as read-only text: Save is grey and a sentence says whose
file it is. The person's levers stay **Reset** (empties the working context, keeps everything) and
**deleting the project** (takes the record with it). A single log file cannot be renamed, moved or
deleted on its own.

This is the app refusing, not the system: a file manager on the phone can still edit the file, and
nothing here pretends otherwise. What it buys is that the app never offers the person a way to make
its own record mean nothing.
