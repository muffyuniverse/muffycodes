# The 17 reports from build 191

Written 2026-10-30 from `trace.log (3).txt` (build 191, 09:44–11:02). Every report checked against the
code, not against a plan. **Nothing here is built yet** — this is the plan and the questions.

Two reports from that trace are already in (`09:47` the editor selector, `09:58` the clock presets,
commit `1191509`). The rest are below.

---

## 1. What is actually wrong, by report

| # | Report (10:17–11:02) | State in the code | What it needs |
|---|---|---|---|
| **1** | "maximum displayed message height somehow ended up in session log block but it needs a new separate agent chat block" | **Open.** The line cap is in the *Session log* block. My last commit moved it under a "Messages" heading — but the operator wants a proper **agent chat** block, not a stray heading. | A block titled for the chat, carrying the line cap |
| **2** | "make milk a plain white theme, brownie should be brown theme from chocolate, chocolate should be near black" | **Open.** `MILK` is pale green `#CFE3D6`; `BROWNIE` is **purple** `#8B5FA8`; `CHOCOLATE` is `#5A3A24`. | Milk → white; Brownie → the brown Chocolate has now; Chocolate → near black |
| **3** | "back to defaults should be the last element on the settings list" | **Open.** It sits before "Running" and "Background". | Move to the end |
| **4** | "unsync row button hasn't been changed to a disconnect icon from its current cross, as I asked. and grey, black and red colours… still unimplemented" | **Partly.** The icon is still `Icons.Filled.Clear` (a cross). The three tints *are* coded (`APP_HOLD` red / `onSurface` black / `outline` grey) — so either a state never occurs, or the colour is invisible in practice. | A drawn "disconnected" glyph, and a check that all three tints actually render |
| **5** | "bug report icon still isn't added to agent chat as I instructed in an older report from yesterday!" | **Open and confirmed.** `ReportButton` is in `BrowserScreen` only; `AgentChatScreen` has none. | Add it, in the same place as the browser's |
| **6** | "app errors like no openrouter key etc — all should go into session log only, they should not reach the context" | **Open.** The `blip`/failure rows are written as `NOTE` rows, which are in the conversation and printed as pages. | Errors visible in the log, absent from what the model is sent |
| **7** | "when folder is locked or unsynced then all of its content also gets icons with the same state" | **Open.** Lock/unsync are per-entry; a folder's rows do not inherit. | Inherit down the tree, red for app-set |
| **8** | "locked files should hide and forbid 3-dot options" | **Open.** The menu is drawn for a locked row. | Hide it |
| **9** | "make it so that logs don't have history and aren't saved to git" | **Half.** `agent/`, `.muffy/`, `requests/` are in `APP_IGNORES`, so they are not committed. **History is the open half** — a log written into a project that saves versions would still be versioned. | Confirm no log path can be versioned |
| **10** | "still tells 'no openrouter key on this account yet' in agent block after setting the key" | **Open, and it was marked CLOSED once before** (`1053cf7`). It recurs, so the earlier fix addressed a different path. | Find the stale read |
| **11** | "OpenRouter answered 400: The `reasoning_content` in the thinking mode must be passed back to the API" | **Open.** `Streamed.thinking` is deliberately *not* sent back — that decision is now a bug for thinking-mode models. | Echo reasoning back for the models that require it |
| **12** | "arrows to page through the message should be at the top of the message bubble" | **Open.** They are at the bottom. | Move up |
| **13** | "strange white space above write input box… last message should snap to the input box, no gaps" | **Open.** | Remove the gap; pin to the box |
| **14** | "input box should not have send button, it should send on enter. and when the keyboard pops up, screen should move down to keep messages in view" | **Open.** There is a `Send` button; no IME action; no keyboard inset handling. | Enter sends; `imePadding` |
| **15** | "remove the `per M in` / `per M out` cost rows, provider already lists the prices, better show in agent block in settings the charge rates for selected endpoint: input, output, cache" | **Open.** Both rows are in `StatsScreen` (lines ~597, ~606). All three prices are *already fetched* (`ModelCatalogue.priceIn/priceOut/priceCache`). | Delete the two rows; show the three rates in the agent block |

---

## 2. How I would order it

**Badge first** — #10 and #11 are the two that stop the app working at all. A 400 on every turn with a
thinking-mode model, and a settings row that lies about whether a key is set, are worse than any layout
complaint. #11 in particular is a design decision being reversed (we deliberately do *not* send reasoning
back), so it wants care rather than speed.

**Then the things that are one-line truths** — #2 themes, #3 reset position, #12 arrows, #15 cost rows.

**Then the tree semantics** — #7 and #8 are one change (inheritance and the menu) and want a test each.

**Then the chat screen** — #1, #5, #13, #14. Four reports about one screen, so they should land together
and be photographed together: the scenario suite has `13-the-chat`, and this is a screen where the
picture is the proof.

**Then #6 and #9** — the context/log separation and the log-never-versioned rule. Both are about what the
record *is*, and both want a test that fails when the other half creeps back.

---

## 3. The answers (2026-10-30, from the operator)

| Q | Answer | Consequence |
|---|---|---|
| **1. Reasoning 400** | **Always echo it back.** | `reasoning_content` goes into the assistant message on every request, not only for models that ask. The standing rule "nothing the model thought is sent back as something it said" is **reversed**, and the comment that states it must be rewritten rather than left to contradict the code. |
| **2. Stale key row** | **After setting it in Settings.** | The path is the dialog on the same screen, so the AGENT block's read is the stale one — not the account, not the test-account seed. |
| **3. Lock inherits** | **Genuinely locked, maximum restriction.** | A locked folder's children cannot be **added, removed or renamed**, not merely not-edited. Only *read* and *copy into an unlocked folder* are allowed. And the two kinds differ: **a system lock binds users and agents alike; a user lock binds the agent and not the user.** |
| **4. Charge rates** | **Say it is not fetched yet, with a way to fetch.** | Three rows in the agent block, and an honest sentence plus a control when the catalogue has never been read. |

**The lock answer is the largest item on this list and was not obvious from the report.** It turns the
lock from "this file cannot be written" into "this subtree is read-only", and it splits the state in two:
a user's lock stops the agent and not the person, a system lock stops both. #7 and #8 are the visible
half of it; the write path is the half that decides whether it is true.

---

## 4. The questions I asked (kept, with the answers above)

Asked as a group, because the answers change what gets built, not just how.

**Q1 — the reasoning 400.** The current design deliberately keeps `Streamed.thinking` out of the
conversation ("nothing the model thought is ever sent back to it as something it said"), and that is now
rejected by thinking-mode models. Which do you want:

- **echo it back** in the assistant message (`reasoning_content`), which is what the provider asks for,
  and keeps thinking models working — the cost is that the thought goes back into the request that
  produced it, so it is paid for again as input on the next turn;
- **stop requesting reasoning** for these models, so nothing needs echoing back — the cost is that the
  live "thinking" progress in the footer disappears, which is what the idle timeout uses to tell a busy
  model from a dead connection;
- **echo it back only for models that ask**, keeping it out for the rest.

I would pick the third, and I would like your ruling because it reverses a documented decision.

**Q2 — "no OpenRouter key yet" that will not go away.** It was marked CLOSED once and is back. Is the key
you set on the *account* that the agent block is reading — i.e. does the message appear on a fresh
account where the key was set from the Settings dialog, or after switching accounts? Knowing which path
was used tells me which of the two reads is stale; guessing would mean fixing the wrong one twice.

**Q3 — locks inheriting down a tree (#7).** When a parent folder is locked, are its children *locked* (a
real state, which also forbids editing) or *shown as locked* (the icon and the menu only)? A real state
is the honest reading, and it is a bigger change: it has to touch the write path, not just the row.

**Q4 — "logs don't have history" (#9).** Which logs: the agent's session log and the request log
(already out of git), or also the app's trace file in `Download/MuffyCodes log/`? The trace is written
outside the repository, so it has no history today — if you are seeing commits of a log, it is one of the
two inside the project, and I would like to know which so the fix lands on the right one.

**Q5 — the charge rates in the agent block (#15).** Those numbers come from the model catalogue, which is
downloaded from OpenRouter. Do you want them shown **only when the catalogue has been fetched**, or
should the block say "not fetched yet" with something to press? The first is simpler and honest; the
second is more useful on a phone that has not refreshed since install.
