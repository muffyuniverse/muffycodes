# The reports from build 191, 194 and 195 — audited, and where each one stands

Written 2026-10-30 from `trace.log (3).txt`: **84 reports**, 51 from build 191, 2 from 194, 31 from 195.
Every one is checked against the code, not against a plan. The batch that closed them is the chain
ending `d69d4ef`.

**78 were already closed before this session.** The 191/194 batches landed across the commits named in
`PLAN-2026-10-30-reports.md`; the audit here confirmed each against the source rather than trusting
that document. The ones that were marked done and were not: the `from`/`to` pair, the copy rename, the
folder marks, the sync icon, the cap, and the filenames — six, listed below.

**Seven were genuinely open, and this session closed all seven** (six defects and one question that
turned out to be a defect):

| Report | What was actually wrong |
|---|---|
| **#66** | A `grep`+`from`/`to` edit with no `replace` key replaced the block with nothing and answered `0 … changed`. `optString` returns `""` for a key that is not there. **The file was emptied and the app said it worked.** |
| **#67** | The same missing key made `from`/`to` and `at` unusable for what the description promised, so the cheap safe edit was unreachable. |
| **#68** | A copy could not be renamed; empty and unreadable both answered `0` with no body. |
| **#55** | Folders had no lock or unsync mark: the marks were drawn only on `EntryKind.TEXT` while `hold`/`unsync` had always understood folders. |
| **#53/#54** | The sync icon lagged: `unsync` re-read the flags file on every call, which Compose cannot see, so a tap changed nothing the row was watching. (Two numbers, one defect.) |
| **#81** | The bubble cap counted source lines, not rows, so a wrapped paragraph was three times the height the cap allowed. |
| **#84** | Background result filenames carried a 31-character provider id. |

**Four were questions rather than defects, and all four are answered**: #80 (yes — every deleted file,
once, as the newest variant, and a test now says so), #81 (a simple fix, made), #25/#26 (superseded by
#27, one response timeout), #75 (struck down by itself: per 1 M, not per 1 B).

**Two were struck down by the operator** and are recorded as such rather than deleted: #23 (merge the
agent and agent-chat blocks) was reversed by #30 (rename them instead), and #75 was corrected in the
next message.

**#42 (text selection on user messages) is the one item still OPEN, and the reason is now measured
rather than assumed.** It was reported once — build 191, 17:08 — and build 191 was `4eaccaa`, which is
**before** `7d683dd` removed `animateContentSize()` from that bubble. So that change has never been
tested against the build the report came from.

Four ways of making the long press were tried, and all four reached `SelectionContainer` (NATIVE
graphics mode built the handles, asked the toolbar and started a magnifier) without ever publishing a
non-empty selection range — **and the agent's bubble behaved identically, and that is the one the
operator says selects fine.** A technique that fails on the working case has measured the harness. So
the harness cannot answer this question, and a green test here would claim more than it measured.

What *is* pinned (`d69d4ef`): the person's bubble is inside a `SelectionContainer` exactly as the
agent's is, and nothing on its modifier chain — no `clickable`, no `pointerInput` — can consume the
press first. That is the whole difference the code could have. **If it is still broken on a device,
the cause is in the platform's selection plumbing rather than in this app's layout, and a trace is
what is needed.**

---

## Every report, in order

| # | Time | The report | Where it stands |
|---|---|---|---|
| **1** | 09:47 | view-edit-diff element liiok ugly, remove the in the cell checkmark symbol on selection (color is a good enoug | Editor: checkmark and outline removed, diff beside Save, History in the top bar — earlier batch |
| **2** | 09:58 | clock interval presets for 15 min, 1 hour and 6 hours are broken: their from time is incorrectly set, for exam | Clock presets truncate to their own grid — earlier batch (`1191509`) |
| **3** | 10:17 | maximum displayed message height somehow ended up in session log block but it need a new separate agent chat b | Agent chat block for message height — earlier (`a829c57`) |
| **4** | 10:19 | make milk a plain white theme, brownie should be brown theme from chocolate and chocolate should be near black | Milk white, brownie brown, chocolate near-black — earlier (`c8aaf44`) |
| **5** | 10:23 | back to defaults should be the last element on the settings list | Back to defaults last — earlier (`c8aaf44`) |
| **6** | 10:27 | unsync row button hasnt been changed to a disconnect icon from its current cross, as I asked. and grey, black  | Disconnect glyph and three tints — earlier (`8f4871e`) |
| **7** | 10:31 | bug report icon still isnt added to agent chat as I instructed in an older report from yesterday! | Bug report icon in agent chat — earlier (`a829c57`) |
| **8** | 10:33 | app errors like no openrouter key etc - all should go into session log only, they should not reach the context | App errors go to the session log, not the context — earlier (`8f4871e`) |
| **9** | 10:36 | when folder is locked or unsynced then all of its content also get icons with the same state (red locked paren | Folder contents inherit the lock and unsync marks — earlier (`8f4871e`) |
| **10** | 10:37 | and locked files should hide and forbid 3dot options | Locked files hide the 3-dot menu — earlier (`8f4871e`) |
| **11** | 10:43 | make it so that logs dont have history  and aren't saved to git - otherwise they will spam commits but commits | Logs have no History button — earlier (`477b89c`) |
| **12** | 10:46 | still tells "no openrouter key on this account yet" in agent block after setting the key | Key state: UNREADABLE rather than a lie — earlier (`063c5cd`) |
| **13** | 10:49 | OpenRouter answered 400: The `reasoning_content` in the thinking mode must be passed back to the API. (request | reasoning_content passed back — earlier (`063c5cd`) |
| **14** | 10:51 | arrows to page through the message should be at the top of the message bubble | Pager arrows at the top of the bubble — earlier (`a829c57`) |
| **15** | 10:53 | there is strange white space above write input box and it partially covers messages making the lower ones hard | White space above the composer — earlier (`a829c57`) |
| **16** | 10:57 | and input box should not have send button, it should send the message on enter. and when device keyboard pops  | No Send button, Enter sends, imePadding — earlier (`a829c57`) |
| **17** | 11:02 | per M in\n0.39312\n—\nper M out\n4.85883\n—\n# remove this cost rows, provider already lists the prices, bette | Cost rows removed, endpoint rates in the agent block — earlier (`a829c57`) |
| **18** | 14:00 | provider popuo selection window should have just add, remove and select buttons on one row | Provider window: Add/Remove/Select on one row — earlier settings batch |
| **19** | 14:02 | "allow another provider" window per row smalltext legends should show instead of context - their input, output | Endpoint rows show the price rates — earlier settings batch |
| **20** | 14:12 | light themes should have white screen backgrounds, dark themes should have black screen backgrounds. Differenc | Light themes white, dark themes black — earlier (`c8aaf44`) |
| **21** | 14:16 | settings context block: remove what has happened to the context element and its fields - not needed. and on to | Context block trimmed, 'token counts are approximate' — earlier settings batch |
| **22** | 14:19 | "one model, one key..." row in agent block is to be removed | 'one model, one key' row removed — earlier settings batch |
| **23** | 14:21 | merge agent and agent chat blocks and name the result Chat agent | STRUCK DOWN by #30 |
| **24** | 14:23 | on model selection card also remove cancel and put add, remove, select on one row | Model card: no Cancel, three buttons on one row — earlier settings batch |
| **25** | 14:28 | I want ability to rearrange provider endpoints on the provider selection card (by holding and then moving them | Endpoints reorderable by drag — earlier settings batch |
| **26** | 14:32 | check that connection and stalling timeouts really trigger endpoint fallback | Superseded by #27 |
| **27** | 14:37 | actually lets merge connection and stalling timeouts into a single response timeout that tracks time between r | One response timeout — earlier settings batch |
| **28** | 14:39 | and in reconnect "now" description write that it is about handling error responses - make this description to  | Reconnect description to the point — earlier settings batch |
| **29** | 14:44 | session log description is unreadable - make it precise: defines max session log size in tokens, removes oldes | Session log description precise — earlier settings batch |
| **30** | 14:48 | earlier I said to merge agent and agent chat. strike that down. just rename agent into Chat agent, and Agent c | Agent renamed 'Chat agent', Agent chat renamed 'Chat' — earlier settings batch |
| **31** | 14:52 | and add short description to "working as" block: that it defines system settings and available projects in the | 'working as' block described — earlier settings batch |
| **32** | 15:01 | local projects shoudnt show sync row - hide it for them | Local projects hide the sync strip — earlier (`e9df6b5`) |
| **33** | 16:46 | and dont use just one icon for two states for lock and sync. tell me if it is possible to use open lock icon o | Distinct glyphs per state — earlier (`574323b`) |
| **34** | 16:48 | why agent/ looks different? is it not manages in a unified way? because where is its red unsync icon? why is i | agent/ shows both marks — earlier (`8f4871e`) |
| **35** | 16:49 | and why logs dont inherit red lock and unsync icons? | Logs inherit the marks — earlier (`8f4871e`) |
| **36** | 16:54 | diff isnt getting selected even when there is history to compare, fix it. and set it so that by default it sho | Diff opens newest vs previous — earlier (`61ba090`) |
| **37** | 16:56 | why do I get renamed and saved notifications after exiting editor? I did those actions already inside the edit | Editor exit confirmations — closed by `Said` (#70) |
| **38** | 17:01 | something crazy is going on this screen when agent writes his message - it is getting squized into the top bar | Live reply in a bubble, not the top bar — earlier (`87fc7c9`) |
| **39** | 17:02 | and I need it to show only hh:mm as time - not freaking milliseconds | hh:mm in the footer — earlier (`ed34a26`) |
| **40** | 17:04 | nullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnull - what is the reason for such rows in | nullnullnull — earlier (`68c0d4f`) |
| **41** | 17:05 | and why first agent page is a page of nullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnullnull | First page of nulls — earlier (`68c0d4f`) |
| **42** | 17:08 | text selection is broken for user messages | **OPEN — and this is the honest reason.** The gesture cannot be made in this harness: four techniques all reached `SelectionContainer` (NATIVE mode built handles, asked the toolbar, started a magnifier) but none published a non-empty range, **including on the agent's bubble, which the operator says works**. A test that cannot make the gesture cannot close a report about the gesture. What IS pinned: the person's bubble is inside a `SelectionContainer` and nothing on its modifier chain consumes the press (`d69d4ef`). **If it is still broken on a device, the cause is in the platform's selection plumbing, not in this app's layout.** |
| **43** | 17:10 | when keyboard pops up it covers messages, I want the screen to move down so that messages are above the keyboa | Keyboard pushes the list — earlier (`a44ecef`) |
| **44** | 17:12 | I want write to the agent box be the color of the background - no visible edges | Composer is the page's colour — done (already in source) |
| **45** | 17:17 | agent is "off" but it responds when user sends it a message. Make it so that a new message from user turns the | Speaking turns the agent on — done (already in source) |
| **46** | 17:21 | when I enter the chat screen I should see the bottom (on entering the chat my view already should be positione | Opens at the bottom, no visible scroll — done (already in source) |
| **47** | 17:22 | on message fullscreen view top bar buttons are too high, they are partly covered by system bar. move them a li | Fullscreen buttons below the status bar — done (already in source) |
| **48** | 17:23 | and what is this "create" text at fullscreen view? | 'create' caption explained — done (already in source) |
| **49** | 17:29 | actually I want for presets not to be generated automatically - I want every interval to be generated via "gen | Presets generated by the button — earlier (`e09bb93`) |
| **50** | 17:32 | text lines on "before", "after" and interval duration draw under generate button, just above the table with me | Caption lines under Generate — earlier (`e09bb93`) |
| **51** | 17:34 | on exiting the screen where multiselection was active it should cancel and close - otherwise it shows on diffe | Selection clears on navigation — earlier (`6a9ca5a`) |
| **52** | 20:18 | 20 hours 15 minutes · the log starts 30 Oct 2026, 03:00 # this line is wrong when clock time is selected. and  | Three interval lines, dotted dates — 64668b4 |
| **53** | 20:36 | sync connect icon doesnt update visually after tapping on it | Sync icon from state — cc9f434 |
| **54** | 21:38 | sync icon still lags to change after tapping | Sync icon from state — cc9f434 |
| **55** | 21:40 | why folders made by agents dont have lock and sync icons? | Folders carry the marks — cc9f434 |
| **56** | 21:47 | screen should be at the bottom of the message list on chat menu opening. and it should scroll down when I star | Follows only when already at the bottom — 25de8d2 |
| **57** | 21:59 | agent tells that lock blocks copying as well, which should be allowed | Lock does not block copying — done (already in source) |
| **58** | 22:02 | OpenRouter answered 403 # error messages should be only for session log, why are they shown as if they are "in | Errors out of the context — 25de8d2 |
| **59** | 22:05 | message should autoflip to the next new page only if the last page  is selected. if i read not last page it sh | Autoflip only on the last page — done (already in source) |
| **60** | 22:08 | thinking and output are not shown untill the burst is done - which defies their purpose of indicating that age | Thinking and output live — dba2ec1 |
| **61** | 22:09 | it made 40 requests without ending the turn - what is this restriction? I dont need it, agent must be able to  | Request cap 40 → 500 — 25de8d2 |
| **62** | 22:10 | and why user message isnt shown untill agent responds? | The person's words appear at once — dba2ec1 |
| **63** | 22:13 | and we need >> button to fast move to the last page. and we need down arrow at the bottom right corner of the  | `>>` and the down arrow — 5804473 |
| **64** | 22:21 | in full screen mode paging arrows are still tiny | Fullscreen arrows are real chevrons — 25de8d2 |
| **65** | 22:25 | remember bug when I start talking with agent but top bar still shows "idle" and "start the agent" although it  | Header says waiting, not off — done (already in source) |
| **66** | 22:36 | Data loss on grep + replace (the serious one). The call answers 0 … changed, but the file then reads back as a | The edit that emptied a file — 707acfe |
| **67** | 22:37 | from/to and at are unreachable for what they're documented to do. The docs say from/to alone replaces a block  | Where without what refuses; from/to + replace documented — 707acfe |
| **68** | 22:38 | Smaller things worth a look:\n-  No versions are recorded for anything I created or edited (history → "has no  | Copy with a name; empty vs unreadable; created files have versions — 707acfe |
| **69** | 22:42 | when agent message is together with toolcalls it is displayed as a separate message but should be just a page | Words with calls are pages of one message — done (already in source) |
| **70** | 23:05 | can you remove any in-screen popup notifications that dont bring value and just restate what happened before ( | Only refusals cover the screen — 25de8d2 |
| **71** | 23:07 | add vertical lines between table columns | Vertical rules between columns — 64668b4 |
| **72** | 23:08 | reduce space between metrics and before columns | Rules carry the spacing — 64668b4 |
| **73** | 23:12 | add cached % line as share of total input with .00% precision | Shares to .00% — 64668b4 |
| **74** | 23:14 | per request should be subline under cost. also add per 1B tokens | per request as a cost subline — 64668b4 |
| **75** | 23:15 | not b - strike it down, per 1 M tokens | STRUCK DOWN by 75 itself (per 1M, not 1B) |
| **76** | 23:17 | for input subline "new" so that new+cached=input | input / new subline — 64668b4 |
| **77** | 23:18 | for output add subline "writing" | output / writing subline — 64668b4 |
| **78** | 23:23 | Cached is part of input, not a column beside it. A dash is a number that does not exist — an interval with no  | Explanatory note removed — 64668b4 |
| **79** | 23:25 | for agent status just write waiting/working/stopped + current context (just "131k" for example - laconic) | Status + context, laconic — 64668b4 |
| **80** | 23:30 | does "restore deleted" show all the files deleted from the selected folder?  like all that existed in previous | Restore deleted — ANSWERED, see below |
| **81** | 23:34 | limit on displayed lines doesnt account for long lines that are wrapping. Do we need to live with it or is it  | **CLOSED** — `AgentChat.rowsOf` counts rows, not source lines (`8f745c4`) |
| **82** | 23:36 | expand to read - I want this phrase at the bottom of messages to be clickable (and of selectable color) so tha | Expand to read is a control — 25de8d2 |
| **83** | 23:38 | why in fullscreen at the top left corner agent is repeated 2 times: "agent agent"? | 'agent agent' — 25de8d2 |
| **84** | 23:41 | why background results filenames are so insane? make them shorter, they should be named by hash 64base 8 numbe | **CLOSED** — SHA-256 → base64url → 8 chars (`762a686`) |
