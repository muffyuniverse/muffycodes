# The agent chat: what is built, what was agreed, and where they differed

An audit of the whole feature against the design rounds (`PROPOSAL-agent-chat.md` and the
`-annotated` rounds), against the original brief (`docs/proposal-operator-10`) and against every
`@` comment the operator wrote while it was being built. Its job is not to praise the code: it is
to name every element, point at the test that covers it and the trace line it writes, and list the
places where the app and the agreement were not the same thing.

**How to read a row.** *Agreed* is where the rule comes from. *Code* is where it lives. *Test* is
the test that fails if the rule is broken. *Trace* is what a person debugging sees (`TAP …` is a
press, a `note` is the app talking to itself). Where a cell says **—**, there is deliberately
nothing: a rule that needs no line of its own is covered by the test named.

The differences the audit found are in §9. Everything else in this document is a check that came
out clean.

---

## 1. The record

| element | agreed | code | test | trace |
|---|---|---|---|---|
| one file, `agent/session.log` | r6 §0 | `AgentLog` | `a turn is written once, and the ctx column says what the model is sent` | — |
| `id size kind ctx name detail`, fixed width, escaped body, newest row first | r2 (format), r6 (ctx), r7 (size, order) | `LogFormat.recordRow` | `the columns stay aligned after a flip, so the file still greps and parses` · `the newest row is the first line of the file, and the rows come back oldest first` | — |
| `ctx` = `in` / `out` / `app` | r6 §0 | `AgentLog.IN/OUT/APP` | `a forgotten row stays in the file and leaves the context` | — |
| the request is ① + ② + the `in` rows | r3 §1.5, r6 §0 | `Agent.build` / `Agent.messages` | `the request carries the instructions, the project, the tools and the operator` | — |
| a row carries its own line (no re-render) | r6 §1 | `Row.line`, `LogFormat.flipCtx` | `forgetting changes four characters and not one other byte` | — |
| ids are monotonic and never reused | r3, audit fix | `AgentLog.nextId` | `ids keep counting after a trim drops the early rows` | — |
| one lock per **file**, not per object | audit fix (2026-10-25) | `AgentLog.lockFor`, `AgentSessions.lockFor` | `two writers at once never hand out the same number` | — |
| an unreadable line is shown, not dropped | r2 | `Row.broken` | `a line that does not parse is shown as a row that says so` | — |
| a half-written last line is not an event | r2 | `AgentLog.parse` | `a half-written last line is not a row yet` | — |
| the session is trimmed at a burst boundary, keeping the newest | r3 (`@` "one messagebox at a time"), r5 §3 | `trimSession`, `keepNewest` | `trimming keeps whole conversations, newest first, and says what went` | — |
| an old two-file record is folded in once | audit fix (2026-10-25) | `foldLegacy` | `an old build's record is read, not rejected` | — |

## 2. The turn

| element | agreed | code | test | trace |
|---|---|---|---|---|
| a turn ends in `ask`, `conclude` or `wait`, called **alone** | r1 `@`, r3 §1.4 | `AgentTools.TERMINATING`, `Agent.refusedInCompany` | `a terminating tool called with others is refused rather than obeyed` | `agent call <tool> #id` |
| `ask` ends the turn and is the seam | r1 `@` | `Agent.asked` | `ask ends the turn and the question is the seam the answer needs` | `agent … asked: …` |
| `conclude` means idle | r3 `@` | `Agent.concluded` | `a concluded turn is idle and finished, not waiting` | `agent … concluded: …` |
| `wait` ends the turn and is **not** re-sent | r3 `@` | `Agent.waited` | `wait ends the turn and is not repeated into the context` | `agent … idle: …` |
| the repeated `wait` is `app`, so the poll's bytes never change | r5 §1 | `Agent.waited` | `a second poll is in the evidence and not in the context`, `two polls send the same context twice` | `agent-poll` |
| words without a call stop the turn | r3 | `wordsWithoutCalls` | `words without a call end the turn instead of looping` | `agent … stopped: …` |
| the person's message cuts a turn short; its calls are answered anyway | r1 `@` | `Agent.cutShort` | `the operator's words cut a turn short, and its calls are answered anyway` | `agent: … the operator spoke during a turn` |
| a terminating tool in company is refused, the rest still run | v1 `@` ("doesn't hold them") | `refusedInCompany` | same as above | `agent call … #id` |
| calls that change nothing stop the loop | audit fix | `AgentTools.IDLE_CALLS` | `calls that change nothing and ask nothing stop the loop` | `agent … stopped: …` |
| the request budget is a backstop, not a policy | audit fix | `MAX_REQUESTS` | `a turn that never ends stops at the request budget` | `agent … stopped: …` |
| every ending is on the trace, once | audit fix | `Agent.finish` | `every way a turn ends is on the trace, and once` | `agent …` |

## 3. The tools

| tool | agreed | notes | test |
|---|---|---|---|
| `tree` `path` `depth` | r3 §1.4 | folders end with `/` | `the record's numbers are the tool's arguments, not the app's guesses` |
| `tree` `name`, `tree` `grep` | **brief** ("grep filter for filenames and separately for content") | new in this audit; content matches come back as `path:line: text` | `tree with a name answers with the paths that match and nothing else`, `tree with grep answers with the line that matched, and where it is` |
| `read` `from` `lines` | r3 | never silently shortened | `a tool result is truncated with a one, and says how much it is showing` |
| `read` `grep` | **brief** ("then same grep read") | new in this audit; numbered lines | `read with grep answers with numbered lines and leaves the rest out` |
| a bad pattern | — | refused in words about the pattern | `a pattern that is not a regular expression is refused, and says so about the pattern` |
| `edit` find/replace or whole text | r1 `@` (grep-based edit) | each edit is a commit | `a write is committed and counted as unsent, and never pushed` |
| `create` / `delete` / `move` / `clone` | r1, bare names fixed | through the funnel | `a file outside the two writable folders is refused` |
| `history` / `restore` | r1 | a restore is a new version | `a locked file is refused to the agent, and stays as it was` |
| `reason` | r1 `@` | goes in the record, not in the person's files | `a reason is written into the record and stays out of the person's files` |
| `forget` (ids) | r6 §2 | the agent's compaction and the whole of it | §4 below |
| paths outside the project | r2 | `..`, absolute, symlink | `a path that leaves the project is refused, and the file is not touched` |
| the record is not writable | r6 §5 | same rule as the person's | `a write is committed …` and `WorkspaceTest` |

## 4. Compaction and the audit

| element | agreed | code | test | trace |
|---|---|---|---|---|
| `forget` refuses three things only | r6 §2 | `forgetProblem` | `the newest task cannot be forgotten`, `a call and its result are forgotten together`, `a row that is already out is told so` | `agent call forget #id` |
| the call being made now cannot be forgotten | r6 §2 | `forgetProblem` | `the call being made now cannot be forgotten` | — |
| nothing is deleted; rows are marked `out` | r6 §2 | `AgentLog.forget` | `a forgotten row stays in the file and leaves the context` | — |
| text cannot be rewritten, because none is offered | r6 §2 | `forget(ids)` | `a forget cannot carry text, so the record cannot be rewritten` | — |
| the audit is the `out` rows | r6 §2 | `thrownAway` | `what the agent forgot is exactly what it named, and nothing else` | — |
| the app's own rows are never the agent's judgement | r6 §0 | `bookkeeping`, `reset`, `trimContext` | `the app's own rows are never called his judgement` | — |
| a row the model is not sent is still on the record | r6 | `AgentChat.Line.forgotten` | `a row the model is not sent is still on the record` | — |
| a reset is the person's act | r3 `@`, r6 | `AgentLog.reset` | `a reset is the person's act, so nothing before it is read as his clearing` | `TAP reset-agent`, `agent-reset` |

## 5. The two limits

| element | agreed | code | test | trace |
|---|---|---|---|---|
| trigger and hard are token counts, the operator's | r3 §1.8 | `SettingsStore.triggerTokens/hardTokens` | `the two context limits are the settings', not the design's` | `TAP clear-up`, `TAP trim-context` |
| the pin's ceiling clamps, the number typed survives | r3 §2.4 | `ContextBudget.Limit` | `a big number on a small pin is clamped in force and kept as asked` | `said: … asked … in force` |
| trigger 0 follows the pin, hard 0 is never | r3 §1.8 | `Limits.triggerAt/hardAt` | `a trigger of zero follows the pin …`, `a hard limit of zero is never` | — |
| over the trigger the request ends with the app's line | r3 §1.8 | `clearUpLine` | `over the trigger the request ends with the app's clear-up line` | `agent-clear-up` |
| the line names sizes, a target, and the biggest rows by id | audit fix | `readOpening` | `the clear-up index names an id a read can open, and the biggest rows first` | — |
| the same line on every request of a turn | r5 §2.1 | `Opening` | `the system message is the same bytes on every request of one turn` | — |
| over the hard limit the app trims one conversation and says so | r3 `@`, r6 | `Agent.build` → `trimContext` | `over the hard limit the app trims the oldest conversations and says how many` | `agent-trim` |
| bytes are exact, tokens are the estimate | audit fix | `ContextBudget.bytes/tokens` | `bytes are exact, and are what a person is shown`, `the estimate rounds up` | — |

## 6. The box

| element | agreed | code | test | trace |
|---|---|---|---|---|
| one conversation, and the turns inside it | r1 `@`, v2 `@` (no cross-burst) | `AgentChat.conversation/turns` | `a conversation is the operator's message and everything after it` | — |
| a turn is a request, not a call | v2 | `AgentLog.TURN` | `a turn is a request, so two calls in one reply are one turn` | — |
| `‹ 3 of 6 ›` and `»` | r1 `@` | `Navigator` | `the newest turn is where the box starts, and one press shows the one before` | `TAP previous-turn`, `TAP next-turn`, `TAP latest-turn` |
| a call and its result are one line | r1 `@` | `AgentChat.callLine` | `a call and its result are one line` | — |
| tapping a turn adds the arguments and the whole result | r1 `@` | `AgentChatScreen` | `the box draws what was asked, what the agent called, and what it answered` | `TAP turn` |
| a terminating turn is the agent's words, never a call | v2 `@` | `lines()` | `the ending is the agent's words, never a call` | — |
| forty idle polls are one line | r3 §1.3 | `Box.idle` | `forty idle polls are one line`, `an idle agent is one line, not a turn` | — |
| ¾ of the window, then scroll; the composer stays | r1 `@` | `heightIn(max = maxHeight * 0.75f)`, `imePadding` | `a long conversation leaves the line to speak in on the screen` — the composer is still displayed after forty calls. `imePadding` is the modifier that lifts it above the keyboard; a Robolectric test cannot raise a keyboard, so that half is decoration, not assertion | — |
| a forgotten row is drawn struck through | r6 | `Line.forgotten` | `a row the model is not sent is still on the record` | — |
| the row's size, in bytes, beside its caption | audit fix | `Line.bytes` → `ContextBudget.sizeLabel` | `bytes are exact, and are what a person is shown` | — |
| the state line is the notification's words | r3 §1.6 | `AgentSession.label` | `the standing notification says what the strip says` | — |

## 7. States, reminders and being on

| element | agreed | code | test | trace |
|---|---|---|---|---|
| idle / asked / stopped | v2 `@`, r3 §1.6 | `AgentSession.State` | `a healthy agent is silent, however long it waits` | — |
| a poll every four minutes, at the session's own due time | r3, r5 §1 | `pollDue`, `nextPollAt` | `a poll is due after four minutes and not a millisecond before` | `agent-poll` |
| no poll while asked, finished or reconnecting | r3 §1.6 | `pollDue` | `an agent that asked, concluded or is reconnecting is not polled` | — |
| a blip reconnects 1, 2, 4, 4…; a wall stops with words | v3 `@` | `failed`, `retryDelay`, `MAX_RETRIES` | `one dropped poll does not end the session, and the ladder backs off to four seconds` | `agent-state` |
| an outage is one row, and its end carries the count | r5 §2.3 | `Agent.blip`, `AgentRunner` | `an outage is written down once, and its end carries the count` | `agent … stopped` |
| the reminder repeats at the setting's interval until answered | v1 `@` | `reminderDue`, `reminded` | `a question reminds once an interval has passed, and not before`, `the reminder interval is the setting's` | `agent-reminder` |
| one notification per session, replaced in place | r3 §1.6 | `AgentService.idFor` | `two sessions that need a person are two notifications, not one` | — |
| Open and Stop this session on the notification | r3 §1.6 | `AgentService.notify/stop` | `a session's notification carries the two ways to answer it`, `the tray's stop writes the same session the box does` | `TAP stop-agent` |
| the permission to post is asked for, from settings | v1 `@` | `Notices`, `SettingsScreen` | `the notification permission is offered only while it is missing`, `NoticesTest` | `TAP notifications`, `answered: notifications on/off` |
| a `foregroundServiceType="specialUse"` service with its subtype | r3 §1.7, v2 `@` | `AndroidManifest`, `AgentService` | `the manifest declares a special-use foreground service, and says why` | — |
| sticky, so the phone does not decide the session is over | v2 `@` | `onStartCommand` | `a service starts, ticks its own clock, and stops cleanly` | — |
| it starts for a session that is on and stops when none is | r3 §1.7 | `apply`, `changed` | `starting is refused when there is nothing to run, and asked for when there is` | — |
| a session already on disk gets its clock back | v2 `@` | `AppViewModel.applyService` | `a session that was on gets its clock back without anybody pressing anything` (a reopened app polls with nobody pressing), and `starting is refused when there is nothing to run, and asked for when there is` for the decision | — |
| every agent can be switched off from settings | v1 `@` (assumed it existed) | `AppViewModel.stopAllAgents` | `one switch in settings stops every agent on the phone`, `every agent can be switched off from settings` | `TAP stop-all-agents` |
| battery exemption asked once, from settings, with the reason | r5 §2.2 | `Battery`, `SettingsScreen` | `the battery row says what the system is doing, and offers the only fix` | `TAP battery`, `answered: exempt now` |
| the phone's interference is visible, not silent | r5 §2.5 | `missedBy`, `AgentRunner` gap row | `a session the phone was not running is written down as a gap, and goes on`, `a poll that is a little late is not the phone stopping anything` | `agent-gap` |

## 8. Config, model and key

| element | agreed | code | test | trace |
|---|---|---|---|---|
| `model_conf.json`, edited only by forms, committed | r3 §1.1 | `ModelConfigFile`, `ModelSection` | `ModelConfigTest`: `whatTheAppWritesIsWhatItCanRead`, `aFileThatWillNotParseIsRefusedWithItsLine` | `TAP reset-model-config` |
| a provider is a **tag**, and the request is `only` + no fallbacks | r3 §1.1 | `OpenRouterDoor.send` | `aProviderTagThatEndsInASlashIsRefused`, `aProviderThatIsNotOneOfTheModelsIsRefused` | — |
| no fallback, no autorouting, only the pin | v1 `@` | `OpenRouterDoor` | the two above, and `no fallback` in `Door`'s request body | — |
| add model: fetch, then endpoints, tools required | r3 §1.1 | `AddModelDialog`, `ModelCatalogue` | `aProviderThatCannotCallToolsIsNotOneTheAgentCanUse`, `toolSupportIsReadPerEndpoint`, `aModelWithNothingInItsCatalogueCannotBeRead` | — |
| remove refuses the active model; reset writes the shipped copy | r3 §1.1 | `AppViewModel`, `ModelSection` | `theActiveModelCannotBeRemovedAndTheChoiceSaysWhy`, `resetPutsBackTheModelTheAppShips` | `said: Back to the model the app ships` |
| every missing number is `—`, never 0 | v1 `@` | `money`, `number`, `tokens`, `released` | `aQuietEndpointHasNoThroughputRatherThanZero`, `aModelWithNoIntelligenceIndexHasNoneRatherThanZero` | — |
| the effort clamps down and the operator may raise it | v1 `@` | `ModelConfigFile.effortFor` | `effortMovesDownToWhatTheModelSupports`, `effortMovesUpWhenThereIsNothingBelow` | `said: effort: … -> …` |
| reasoning traces are not received | r1 | `Door` (`reasoning.exclude`) | the request body in `Door`, asserted by `ModelConfigTest`'s round trip | — |
| a key per account, in the encrypted store | v1 `@` | `CredentialStore`, `Secrets` | `KeySafetyTest`, `KeySafetyFeatureTest` | `TAP set-model-key` |
| the answer timeout is the person's number | **brief** | `SettingsStore.timeoutMinutes` → `OpenRouterDoor` | `the timeout and the result length are the person's numbers, clamped to what works` | `TAP answer-timeout` |
| one result's length is the person's number | **brief** | `SettingsStore.maxResult` → `AgentTools` | same | `TAP result-length` |

## 9. Where the app and the agreement differed

Seven differences. Each was either built (with a test and a trace line) or retired with a reason.
Nothing was left as a silent gap.

| # | the agreement | what was there | what was done |
|---|---|---|---|
| 1 | **Open** and **Stop this session** on the notification (r3 §1.6) | a notification that only opened the app | both buttons; Stop calls `AgentRunner.stop`, the same write the box does. `AgentServiceTest` |
| 2 | one notification **per session**, replaced in place (r3 §1.6) | one id for the whole app, so two questions hid each other | an id per project. `two sessions that need a person are two notifications` |
| 3 | `POST_NOTIFICATIONS` **asked for** (v1 `@`, r3 §1.7) | declared in the manifest, never requested — a reminder posted into nowhere on Android 13+ | a Settings row with its state and **Allow**; the manifest's "not yet asked for" comment corrected. `NoticesTest`, `SettingsScreenTest` |
| 4 | a session that is on **keeps its clock** (v2 `@`) | `apply()` was only reached from a press, so after a reboot nothing polled until a session changed | `applyService` asked wherever the sessions are read (refresh and every tick), on change; the deprecated `getRunningServices` replaced by an owner flag |
| 5 | `stopped · the phone stopped the app` (r5 §2.3, §2.5) | **unreachable** — nothing set it, and nothing honestly could: a force-stop is indistinguishable from a cold start, and a resume contradicts "never stop until you remove it" | **removed.** Replaced by the fact the app can see: a `gap` row (`app`, so the poll's bytes are unchanged) saying how long the phone did not run the agent, drawn in the box and traced as `agent-gap`. Round 7 §2 |
| 6 | the shipped `system_prompt.txt` describes this build | a file written only when missing, so the old text — which tells the agent to edit `agent/context.log` — survived the update | **removed 2026-10-26**: the app is developed, not released, so no phone holds a previous default. A file that exists is the person's, word for word; the shipped text is written only when it is missing or empty. Three tests in `AgentTest` |
| 7 | `tree`/`read` with grep; the timeout and the result length as settings; a global off switch | all five absent (the brief asked for each; the v1 `@` assumed the switch) | `tree name/grep`, `read grep`; `SettingsStore.timeoutMinutes`/`maxResult` wired to the door and the tools; **Stop all agents** in Settings |

### Leftovers cleared

- Stale "two files" wording in `AgentLog` (four comments), `AppViewModel`, `LogFormat` and the
  editor's sentence — they described a design that no longer exists.
- An orphaned KDoc block sitting above `ContextBudget.sizeLabel` instead of `short`.
- `highestId()` and `load()` taking the maximum of the two files — the same number
  twice, left over from when the context was a second file.
- The manifest comment claiming the battery permission was "not yet asked for".
- `docs/agent-context-and-loop.md` — rewritten for the one-record model.
- The tap vocabulary and the test map, extended with the new element and the four new controls.

### What was open when this was written — and what became of each

**Nothing here is an outstanding report.** This audit was written while the feature was being built;
each question below has since been answered by the operator or by the code, and the answer is
recorded so a reader does not file it again.

- **The `gap` threshold.** Three poll intervals (twelve minutes). Shorter and every late wake is a
  row; longer and a real Doze window is missed. It is one constant (`AgentSession.GAP_MS`).
  **Settled: the constant stands, and the gap writes its own row** rather than being folded into the
  ping that followed it.
- **`BY_THE_PHONE` is gone.** If a "the phone stopped this" *state* is wanted after all, it needs a
  rule for what happens when the session comes back — and that rule has to be compatible with
  "never stop until the operator removes it". The audit's answer was the row, not the state.
  **Settled: the row, not the state** — `StopReason` carries `BY_OPERATOR` alone.
- **The search scans up to 2,000 files and 2 MB per file.** A project big enough to pass that is a
  project where the agent should narrow the path instead. Both are constants in `AgentTools`.
  **Settled: the constants stand**, and the refusal names the limit so the agent narrows the path.
- **`ask` and `conclude` write a `CALL` row** as well as the `ASK`/`CONCLUDE` row. It is not drawn
  and not sent; it is there so the record says the tool was called. If the record should say it
  once, the `CALL` row is the one to drop.
  **SUPERSEDED: both tools are gone.** A turn now ends when the model speaks and calls nothing
  (`wordsWithoutCalls`), so there is no `ASK`/`CONCLUDE` row to duplicate.
