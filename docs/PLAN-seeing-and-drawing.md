# The plan — seeing, and drawing

Written 2026-11-01 from `DESIGN-seeing-images.md` after the operator answered its five open questions. This is
the order the code lands in, what each step touches, and how it is proved.

**Two halves, and they do not depend on each other.**

- **Part 1 — `look`.** Steps 1–5. The agent can see an image. This closes a loop the app already has: `shot`
  writes a PNG today and nothing can consume one.
- **Part 2 — images as files.** Steps 6–11. `+` makes a blank image, an image has an editor that draws, and a
  text file can become an image of its view.

Nothing in Part 2 needs Part 1. If the drawing matters more, start there — but `look` is smaller, and it is the
half that makes the *other* half useful (a drawing nobody can see is a picture in a folder).

---

# The decisions these steps obey

Five, all from the operator, and each one removes a question the design had left open.

| # | decision | what it settles |
|---|---|---|
| D1 | **Resize so the shortest side is 768** | Not a 1568 px cap. That is the provider's own second step, so we send what it will charge for |
| D2 | **`look` takes multiple paths** | One request rather than N, and "here is the before and the after" is a real thing to ask |
| D3 | **Photo saves beside the file**, named `<name>-<8 hex>.png` | Not `agent/drafts/`. A photo of a file is a note about that file, so it lives with it and syncs |
| D4 | **Session-only undo, plus saved versions** | The PNG is the document; git is the stroke history. No second file format |
| D5 | **A new image gets a size from a dialogue**, defaulting to the common phone size | A canvas needs dimensions before it has content, and the screen's size is wrong for a file that outlives the phone |

**And the one rule underneath all of them:** an image is a **file**, not a mode. It is made by `+`, it has its
own editor, and the text editor gains no drawing mode.

---

# Part 1 · `look`

## Step 1 — `Door` can send a picture

**Touches:** `Door.kt` — `Msg`, `messagesOf`.

`Msg` gains attachments:

```kotlin
data class Msg(
    val role: String,
    val text: String,
    val callId: String = "",
    val calls: List<Call> = emptyList(),
    val thinking: String = "",
    /** Pictures to send with this message, as base64 data URLs. Empty for almost every message. */
    val pictures: List<String> = emptyList(),
)
```

`messagesOf` puts an **array** for `content` when `pictures` is not empty, and a string otherwise:

```kotlin
// A message with no picture keeps the string form. It is what every provider has always
// accepted, and changing the shape of every message to serve the rare one is a risk taken
// for nothing.
if (message.pictures.isEmpty()) one.put("content", message.text)
else one.put("content", JSONArray().apply {
    if (message.text.isNotEmpty()) put(JSONObject().put("type", "text").put("text", message.text))
    for (picture in message.pictures) put(
        JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", picture))
    )
})
```

**The `tool` and `assistant` branches stay string-only.** A tool result cannot carry an image — that is the API's
shape, and it is why `look` works the way it does. An assistant turn is text and calls; nothing attaches to it.

**Proved by:** `DoorTest` — a message with no pictures serialises to a string (unchanged), one with a picture
serialises to an array of two parts in order text-then-image, and a tool message is refused an attachment rather
than silently dropping it.

## Step 2 — the budget counts an image by its dimensions

**Touches:** `ContextBudget.kt`.

```kotlin
/**
 * What an image costs, from its **pixels** — never from its base64 length.
 *
 * Base64 is a third larger than the bytes it carries, and the provider's charge is not
 * proportional to the file at all: it resizes to a 2048 box, then to a shortest side of 768,
 * and charges 85 plus 170 per 512-pixel tile of *that*. Counting the encoding would overcharge
 * every picture by a third and still be wrong, because a 4 MB photo and a 200 kB one cost the
 * same 765.
 */
fun imageTokens(width: Int, height: Int): Long {
    val (w, h) = fitted(width, height)   // the provider's own two steps
    return 85L + 170L * ceilDiv(w, 512) * ceilDiv(h, 512)
}
```

`fitted` applies the published pipeline: scale into 2048×2048 preserving aspect, then scale so the shortest side
is 768. A picture already smaller is unchanged.

**Proved by:** `ContextBudgetTest` with the rows from the design doc's table — 1024×768, 2048×1536 and
4000×3000 all costing 765; 1080×1920 costing 1,105; and 768×2048 costing **1,445**. The portrait cases matter
most: they are the ones a "cap the long edge" rule would have got wrong, and they are the shape a phone actually
takes.

## Step 3 — `look`

**Touches:** `AgentTools.kt` (the tool), a new `Look.kt` (decode, resize, encode).

```json
{"paths": ["agent/drafts/03.png", "screens/after.png"]}
```

**What it does:** reads each path, refuses what it cannot use, resizes to the shortest side of 768, base64s it,
and **records it as pending** — the pictures ride the **next** message, not this tool's result. The result is a
sentence:

```
attached: agent/drafts/03.png (1024×768, about 765 tokens), screens/after.png (1024×768, about 765 tokens)
```

**Refusals, each naming what it took and what it wanted:**

| input | answer |
|---|---|
| not a picture (by extension) | *"look takes png, jpg, jpeg, webp and gif; that is a .bmp"* |
| will not decode | *"that file could not be read as a picture"* — a truncated download is not a picture |
| a model with no `image` in `inputModalities` | *"this model cannot see pictures; choose one that can"* |
| more than the cap of pictures in one call | named, with the cap |
| a picture over the byte cap after resize | named, with the size |

**Why the model check is real and not decoration.** `ModelCatalogue.Entry.inputModalities` already carries this
from `architecture.input_modalities`, so the app knows before it spends anything. A blind model sent an image
either errors or, worse, silently ignores it — and the second is a conversation that goes nowhere for no visible
reason.

**Proved by:** `LookTest` — the four refusals, the resize arithmetic, and that `pending` is cleared after one
request (step 4).

## Step 4 — the pictures ride the next message, once

**Touches:** `Agent.kt` (where `Request` is built), `AgentLog.kt` (the row that records it).

The pending pictures are attached to the **next user message** and then cleared. One shot, no retry:

```kotlin
val pictures = pending.take()          // reads and clears, in one call — so it cannot fire twice
```

**Why the next *user* message and not the tool result.** The API forbids it on a tool result, and the operator
turning is the right moment anyway: the picture is context for a request, not an answer to a call.

**The row.** `look` writes one row, like every other act, and the trace shows it:

```
NOTE look  agent/drafts/03.png · 1024×768 · about 765 tokens
```

**Proved by:** `AgentTest` — look, then assert the very next request carries the array form and the one after
does not.

## Step 5 — a picture is forgettable

**Touches:** `AgentLog.kt` only if the row kind is new.

An image row must be droppable by `forget` like any other, or a conversation can be wedged open by a picture it
cannot drop. **This is a check, not a build** — `forget` flips `ctx` to `out` on a row by id, and an image row is
a row. What step 5 does is *prove* it, so a later change to the row shape cannot quietly break it.

**Proved by:** `AgentLogTest` — look, forget that id, assert it is out of the context and the picture is gone
from the next request.

---

# Part 2 · Images as files

## Step 6 — `+` gains **New image**

**Touches:** `BrowserScreen.kt` (the add menu), `MainActivity.kt` (`Pending`), `Dialogs.kt` (the size dialogue),
`Workspace.kt` (writing the file).

A menu item beside *New file* and *New folder* opens a dialogue with **standard sizes** and a name:

| option | size | tokens | why it is offered |
|---|---|---|---|
| **Square** (default) | 1080×1080 | **765** | the phone's own width; the shape of a screenshot of a list or a card |
| Phone | 1080×1920 | 1,105 | a portrait page |
| Landscape | 1920×1080 | 1,105 | a wide diagram |
| A4 at 150 dpi | 1240×1754 | 1,105 | a page to print |

D5 says the dialogue offers sizes; these four are the proposal. The default is **1080×1080**, and it is the
default for a reason beyond being common: after the provider's resize it is 768×768, which is **four tiles —
765 tokens, the cheapest a picture can be**. Every other shape is six tiles. A person sketching a layout is not
spending 40% more of their conversation on it for a rectangle they did not ask for.

**The file is written immediately** — a blank white PNG at the chosen size — so the new image is a real file
before anything draws on it, and a person who backs out has an empty picture rather than a lost one. It appears
in the list as a picture because `Workspace.kindOf` already answers `EntryKind.IMAGE` for `png`.

**Proved by:** `BrowserChromeTest` (the menu item), `WorkspaceTest` (a written PNG is `EntryKind.IMAGE`), and a
`Pictures.decode` check that it is a real, openable image rather than a zero-byte file with the right name.

## Step 7 — the image editor exists

**Touches:** `EditorScreen.kt` — the `EntryKind.IMAGE` branch, which today is a read-only `Picture`.

An image gets a mode row of its own: **View · Draw**. `View` is today's `Picture`, unchanged. `Draw` is a
canvas **over the image as it is**, so one canvas serves all three cases — a blank PNG is a sketch, a screenshot
is an annotation, a photo is a correction.

**Tools, and no more than these:** pen, arrow, box, and eraser. Four is enough to say "this goes there", and
every tool added is a mode inside the canvas, which is exactly the complexity D5's file model avoids.

**Colour and width:** one colour and one width, chosen once. A palette is a settings screen inside a drawing
screen.

**Saving** writes the PNG back to the same path, so it is a commit like any other edit — which is what makes the
history of a picture work exactly as the history of a file does, with no new machinery.

**Proved by:** `EditorScreenTest` — draw a path, save, decode the file back, assert the stroke is in the pixels.

## Step 8 — session-only undo

**Touches:** `EditorScreen.kt` only.

D4: undo is a **stack of stroke lists in memory**, gone when the screen closes. The saved versions are git's
job, and that is the whole of the history story.

**The rule, stated so a later change does not invent a file format for it:** an undo stack that survives a
restart would be a second document beside the PNG, and two documents that can disagree is the thing this
codebase spends its time avoiding.

**Proved by:** `EditorScreenTest` — three strokes, three undos, back to the original bytes.

## Step 9 — **Photo**

**Touches:** `EditorScreen.kt` (the button), `Pictures.kt` (the render), `Workspace.kt` (the name).

A button in the text editor's bar renders the file's **view** — the markdown-rendered, wrapped page the person is
looking at, not the raw source — into a PNG and opens it in the image editor.

**Why the view and not the source:** the person is annotating what they *see*. Rendering the source would put
hard-wrapped raw text under their pen, and the circle would sit beside the thing it was meant to circle.

**The name, per D3:** `<name>-<8 hex>.png` beside the file. The hash is of the content, so the same page rendered
twice is the same name and a changed page is a new one — which is what makes it safe to leave in the folder.

**The cost, kept where it will be read:** the PNG is a picture of text, so the agent reads it lossily and
expensively. When a person sends a photo of a file, the text file's path should go with it. **The drawing says
*where*; the file says *what*.**

**Proved by:** `EditorScreenTest` — a known document renders to a PNG of a known size, and the file lands beside
its source with the hash in the name.

## Step 10 — the editor's own affordances

**Touches:** `EditorScreen.kt`.

Small, and they are what makes the canvas usable rather than present: the drawing survives a rotation, the
canvas scrolls when the image is bigger than the screen, and a tap on **View** does not lose unsaved strokes
without asking.

**Proved by:** `EditorScreenTest`.

## Step 11 — the record

**Touches:** `docs/README.md` and the guide, if this feature needs a line there.

---

# Why this order

**`look` before the canvas** because `look` is smaller, it closes an existing loop (`shot` → nothing), and it is
what makes a drawing *useful*. A canvas that lands first is a picture nobody can show anyone.

**Step 1 before everything** because a picture that cannot be serialised is not a feature. It is also the step
most likely to surprise us — it changes the shape of a message every request carries, which is the one thing
this app does constantly.

**Step 6 before step 7** because an image editor with no way to make an image is a screen you can only reach by
uploading something.

**Step 9 after step 7** because Photo opens the image editor, so the editor has to exist.

---

# What is not in this plan

- **OCR.** The model reads the picture. OCR is for *searching* images, which is a different feature.
- **Screenshots of the app itself.** Nobody asked for it, and it is the capability that would let a page steer
  the agent.
- **A stroke history that survives a restart.** D4 says no.
- **Vector drawing.** A PNG is the document, and an SVG would be a second format to keep in step with it.
- **Scanning an image.** The scanner's scope is `browse` and `mcp`; a drawing the person made is not foreign
  text.
