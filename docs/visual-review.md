# The visual review — every release, every screen, every window

A passing suite is not an audited one. Every test in this repository asks a question about
behaviour, and **none of them can see**. A row that is the wrong colour, a button pushed past the
right edge, a gap where a section should start, a chip that says one thing while the state says
another — all of those pass a green run, and all of them are the first thing a person notices.

So the pictures are read, not just produced. This is the procedure, and it is not optional.

## What produces the pictures

`ScenariosTest` draws each scenario twice — before and after — into
`build/outputs/scenarios/<n>-<name>/`, and the run uploads the folder as the `scenarios` artifact.
`ScenarioShots.capture` takes the **topmost root**, so a window is photographed rather than the
screen behind it.

Coverage is the whole app, not a sample:

| What | Where |
|---|---|
| every screen | `BrowserScreen`, `SettingsScreen`, `EditorScreen`, `HistoryScreen`, `AgentChatScreen`, `ObservabilityScreen` |
| every window | `PromptDialog`, `ConfirmDialog`, `RemoveAccountDialog`, `DeleteProjectDialog`, `AddProjectDialog`, `NewProjectDialog`, `SignInDialog`, `PlaceDialog`, `SyncDialog`, `ReportDialog`, `ModelKeyDialog`, `ChangesWindow` |

A new screen or window is not finished until it has a scenario. That is the same rule as
`tools/test-map.txt` for tests and `tools/check-taps.py` for taps: the list of what exists is
written down and checked, rather than remembered.

## How to read them

For each picture, in this order — the first three find most of what there is to find:

1. **Is anything cut off, overlapping, or off the edge?** The row that scrolls sideways hides its
   overflow, so a primary button can be pushed out of sight and look absent rather than broken.
2. **Is there a gap or a doubled gap?** Sections separated by a divider and by `spacedBy` at once
   read as a hole. A block whose caption is centred and whose rows are not is two alignments.
3. **Does the picture agree with the words?** A chip reading `View` while the file opens in Edit, a
   count that says `2 to send` beside a list of one, a caption naming a state the screen is not in.
4. **Is the empty state an empty state?** A list with nothing in it must say it is empty, not look
   like a list that failed to load.
5. **Is anything unreadable?** Contrast on the locked-file tertiary colour, the dark theme's green
   for additions, a placeholder over a placeholder.

Then compare **before** and **after**: the before picture is the previous screen, so a scenario
whose two pictures differ only in a word is a scenario that is not showing a change.

## What has been found this way

Kept, because each one was invisible to the suite and obvious in the picture:

- **The editor's Save button was pushed off the right edge** by the third mode chip. The row
  scrolled, so nothing was unreachable — and a primary button that has to be scrolled to is a
  button that looks missing. Fixed by pinning Save outside the scrolling part.
- **A dead gap in Settings** between the account block and the AGENT section, left by the
  regrouping in report 31.

## What this is not

- **Not a pixel test.** There is no golden-image comparison, on purpose: a golden image fails on a
  font's antialiasing and passes a button in the wrong place, which is the opposite of useful. The
  reader is the comparison.
- **Not a substitute for the tests.** The pictures are checked *in addition*, and a scenario that
  shows a wrong number is still a scenario whose assertions ran.
