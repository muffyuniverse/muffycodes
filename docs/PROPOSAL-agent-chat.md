# The agent chat — seventh round: the audit

Status: **built.** Round 6 (`-v6-annotated`) is unchanged in substance: one record, one `ctx`
column, `forget` by id. This round is what an audit of the *whole* feature against rounds 1–6 and
against the original brief found, and what was done about each difference. The evidence — every
element, the test that covers it and the trace line it writes — is in `docs/audit-agent-chat.md`.

Nothing here changes a rule. Six things were missing, one was a state the app could not honestly
report, and the rest were sentences that described a file that no longer exists.

---

## 1. What the design promised and the app did not do

| promised | where | what was there | what there is now |
|---|---|---|---|
| a notification with **Open** and **Stop this session** | v3 §1.6 | a notification that only opened the app | both buttons; Stop goes through `AgentRunner.stop`, the same session write the box uses |
| **one notification per session**, replaced in place | v3 §1.6 | one id for the whole app, so two questions hid each other | an id per project |
| `POST_NOTIFICATIONS` **asked for** | v1 `@` (system settings), v3 §1.7 | declared in the manifest, never requested | a row in Settings with its state and an **Allow** button, like the battery exemption |
| a session that was on **gets its clock back** | v3 §1.7, v2 `@` ("must never stop until the operator removes it") | `apply()` was called only when the person pressed something, so after a reboot nothing polled until the app was opened and a session changed | `apply()` is asked wherever the sessions are read; the deprecated `getRunningServices` became an owner flag |
| **`tree` and `read` with grep** | the original brief ("grep filter for filenames and separately for content … then same grep read") | `tree` took a path and a depth; `read` took a range | `tree` takes `name` (paths) and `grep` (contents, with line numbers); `read` takes `grep` |
| the **answer timeout** and the **result length** as settings, 4 min and 20k | the original brief | `OpenRouterDoor(timeoutMs = 120_000)` and `AgentTools.MAX_RESULT = 20_000` | two fields, clamped, wired to the door and the tools |
| one switch that turns **every** agent off, in Settings | v1 `@` ("we already have agent turn off per chat and in settings for all") | it did not exist | **Agents** row with the strip and **Stop all agents** |

## 2. What was promised and could not be true

**`stopped · the phone stopped the app` is gone.** Round 5 §2.3 named it and §2.5 promised it would
be visible "rather than silent". It was unreachable: nothing ever set it, and nothing honestly
could. Android does not tell an app it was force-stopped — a cold start afterwards is
indistinguishable from any other — and an OEM battery manager usually kills without calling
anything. The state also contradicted the stronger rule it sat under, which is the operator's own:
*it must never stop until the operator removes it.* A phone that stops the app has not been asked
to end the session, so on resume the session goes on, and a label saying "stopped" about a session
that is carrying on would be the label lying.

What replaces it is the fact the app can see: **the clock.** A session whose last poll is more than
three intervals old was not running, and `AgentRunner` writes one row — `gap`, `app`, so the model
is never sent it and the poll stays byte-identical — saying how long for. It is in the box, in the
record and on the trace (`agent-gap`). The hole is visible; the state is not invented.

## 3. The update that had to reach the phone

The one-record round rewrote ① (`system_prompt.txt`) — it stopped telling the agent to edit
`agent/context.log`. The file is written **only when missing**, so every phone that had already run
the agent kept the old text: the model would be told to edit a file that no longer exists and the
app would refuse it, for ever, with the person's only clue a confused agent.

`AgentPrompt.layer` now also replaces a file that is **whitespace-for-whitespace one of the texts
this app shipped before** (`AgentPrompt.PREVIOUS_SYSTEM`), and never one that has been edited. A
default may be replaced while it is still the default; an edit is never touched. And if the person
*did* edit the old prompt, the one name it will ask for — `agent/context.log` — now gets an answer
that names `agent/session.log` and `forget` instead of "there is no file".

## 4. What the audit did not change, and why

- **The poll's bytes.** The repeated `wait` is born `app`; the gap row is `app`; nothing added here
  enters a request. Measured by the tests that compare two polls' messages.
- **The record's shape.** One file, six columns, ids monotonic, the lock per file. Unchanged.
- **`wait` in company.** The refusal is still `1 … is a terminating tool; call it alone`, and the
  other calls still run — the operator's "it doesn't hold them". Each call gets its own result row
  rather than the other results being pasted into the refusal, because a result belongs to its call.
- **`hard` and `trigger`.** Tokens, the pin's ceiling, the number typed survives being clamped.
- **The effort picker.** A vertical sheet of the model's declared efforts, clamped down on a model
  change — the round-1 horizontal scroller was superseded by §1.2's sheet, not dropped by accident.
- **`ask`/`conclude` write a `CALL` row.** It is not drawn and not sent (`SPEAKING`), but it is what
  makes the record say the agent called the tool it called.

## 5. The leftovers that were cleared

Stale "two files" wording in `AgentLog`, `AppViewModel`, `LogFormat` and the editor's sentence; an
orphaned KDoc above `ContextBudget.sizeLabel`; `highestId()` and `load()` taking the maximum of two
files that are now one value twice; the manifest comment claiming the battery permission was "not
yet asked for"; `docs/agent-context-and-loop.md`, which was written for round 5. The test map and
the tap vocabulary were extended with the new element and the four new controls.
