# Seeing: images into a conversation, and drawing out of one

Written 2026-11-01. Two capabilities that look like one and are not:

1. **`look`** — the agent can see an image. A picture is a thing it currently cannot receive at all.
2. **Drawing** — the person can sketch or annotate, and the agent reads it.

They are separate because the first is a **transport** problem and the second is a **UI** problem, and the
transport is the one with the constraints. Drawing needs no new mechanism once `look` exists: it ends in a PNG,
and `look` already reads PNGs.

**An image is a file, not a mode.** It has its own editor, it is made by `+` like any other file, and a text
file can be turned into one with **Photo** — a render of its view — to be drawn on. There is no "drawing mode"
inside the text editor, because a drawing is a different kind of file rather than a different way of typing.

---

## 1. What is actually in the way

Three facts, each verified in the code rather than assumed.

### 1.1 `Door` sends `content` as a string, always

```kotlin
// Door.kt, messagesOf()
else -> one.put("content", message.text)
```

Every message — system, user, assistant, tool — goes out as `{"role": …, "content": "<text>"}`. An image needs
`"content": [{"type":"text",…},{"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}]`. That is
**one function**, and it is the only structural blocker in the app.

### 1.2 A tool result cannot carry an image

OpenAI's `role: "tool"` message is text-only, and OpenRouter follows the API it mirrors. So `look(path)` **cannot
return the picture in its tool result** — this is the API's shape, not a choice.

The answer is the one every agent framework converged on, and it is honest rather than a workaround: `look`
records the image as **pending**, and the next message the app sends carries it as a content part. The tool
result says `attached: agent/drafts/03.png (1024×768)` and the picture arrives on the turn the model is asked to
act on it.

### 1.3 The catalogue already knows which models can see

`ModelCatalogue.Entry.inputModalities` is read from `architecture.input_modalities` and the picker already
prints `text+image`. So "can this model see" is a question the app can answer **today**, with no new request and
no new field. A model that cannot see must refuse `look` in words rather than send an image it will ignore.

---

## 2. `look` — the tool

```json
{"path": "agent/drafts/03.png"}
```

**Its result is a sentence, not the image.** The image goes into the next message. That split is the whole
design, and everything below follows from it.

### 2.1 What it accepts, and what it refuses

| input | answer |
|---|---|
| PNG, JPEG, WebP, GIF | accepted, downscaled if needed |
| anything else (`BMP`, `HEIC`, a `.txt`) | refused, naming the four it takes |
| a file over the size cap | refused, naming the size and the cap |
| a picture that will not decode | refused, because a broken image is not a picture |
| a model whose `inputModalities` has no `image` | refused: *"this model cannot see pictures; choose one that can"* |

**Why downscale rather than refuse a big picture.** A phone photo is 4000×3000 and would cost thousands of
tokens for detail no one needs. The cap is on the **longest edge** (proposed 1568 px, which is where OpenAI's
own tiling stops helping), and shrinking is a loss of resolution rather than a loss of the image — the opposite
trade from refusing, which loses the picture.

**Why the four formats and not "whatever `BitmapFactory` opens".** `BitmapFactory` opens BMP and HEIC too, and
a model may or may not. Naming four is a promise the app can keep.

### 2.2 Where the picture is recorded

**In the log, as a row, like every other thing that happens** — that is what makes it visible in the trace, what
makes it forgettable, and what makes it count against the context.

```
NOTE look  agent/drafts/03.png · 1024×768 · about 1,100 tokens
```

and the row the *next* request carries is the pending attachment. Two rows for one act would be a lie about
what happened; one row plus a pending field is the truth: the row is the record, the pending field is the
delivery.

### 2.3 The cost, and it is the constraint that shapes the rest

An image is **roughly 1,100–1,600 tokens** — a fifth of a small conversation, per look. Three things already
built care:

| built thing | what it means for an image |
|---|---|
| `ContextBudget.tokens` | counts **bytes / 3.5**. An image has no text bytes, so it must be counted from its **pixel dimensions**, not its base64 length — base64 is 33% overhead on data whose cost is not proportional to it |
| the **cache prefix** | an image in the middle of a conversation invalidates everything after it. This is why `look` is deliberate and not automatic |
| `forget` | an image row must be forgettable like any other, or a conversation can be wedged open by a picture it cannot drop |

**The accounting rule, stated once:**

```
tokens = 85 + 170 × ceil(width / 512) × ceil(height / 512)
```

OpenAI's published tiling arithmetic — a flat 85 tokens of framing plus 170 per 512×512 tile, with a partial
tile counted as a full one. Every provider roughly follows it, which is why it is the number to use rather
than a per-provider table the app would have to keep current.

**It is a policy number like the 3.5**, and it is shown as `about N tokens` for that reason. A 1024×768 image
is `85 + 170 × 2 × 2 = 765`; a 4000×3000 phone photo is `85 + 170 × 8 × 6 = 8,245`, which is why the downscale
cap matters more than it looks.

### 2.4 What `look` does *not* do

- **It does not scan.** The scanner's scope is `browse` and `mcp`, and a drawing the person made is not foreign
  text. An image the agent fetched *from a page* arrives through `browse` and is already the person's problem.
- **It does not OCR.** The model reads the picture; OCR is for *searching* images, which is a different feature
  and a worse answer to this one.
- **It does not look at the screen.** No screenshot of the app, no accessibility tree. That is a capability
  nobody asked for, and the one that would let a page steer the agent.

---

## 3. Images are files, and the editor already has a slot for them

**The operator's correction, and it is a better model than the one above:**

> we can not mix text and image files but give a "photo" option for a text file to make an image based off its
> "view". and images can have their own editor where user can draw over them, and blank image is just a new one
> added via +

So there is **no separate drawing mode in the text editor.** A file is text or it is an image, and each has its
own editor. The three pieces:

### 3.1 `+` gains **New image**

Beside *New file* and *New folder*, in the same menu: a blank canvas, saved as a PNG in the folder the person is
looking at. That is the whole of "make a drawing" — an empty image, drawn on in the image editor.

`Workspace.kindOf` already answers `EntryKind.IMAGE` for `png`, `jpg`, `jpeg`, `gif`, `webp` and `bmp`, so a new
PNG appears as a picture the moment it is written. Nothing has to be taught what it is.

### 3.2 The image editor — which does not exist yet

Today an image opens as a **read-only** `Picture` composable (`EditorScreen.kt:372`) with **no mode row at all**.
That is the gap: an image is a thing you look at, not a thing you can change.

The image editor is a mode row of its own — `View · Draw · (text files only) Edit · Diff` — and `Draw` is the
canvas: freehand, arrow, box, and an eraser, over the image as it is. Save writes the PNG back to the same path,
so it is a commit like any other edit and the history of a picture works exactly as the history of a file does.

**Drawing over the picture rather than replacing it** is what makes the same editor serve both cases: a blank
PNG is a sketch, a screenshot is an annotation, a photo is a correction. There is one canvas and no modes inside
it.

### 3.3 **Photo** — a text file's view, as an image

A button in the text editor's own bar: *Photo*. It renders the file's **view** — the same markdown-rendered,
wrapped text a person is looking at, not the raw source — into a PNG, and opens it in the image editor.

That is what makes *"circle this paragraph and say it goes there"* possible: the person presses Photo, draws on
the rendered page, and the result is an image in the project with a name they chose.

**Why the view and not the source.** The person is annotating what they *see* — wrapped lines, headings, a
table laid out. Rendering the source would put hard-wrapped raw text under their pen, and the circle would sit
beside the thing it was meant to circle.

**The cost of that choice, stated plainly:** the PNG is a picture of text, so the model reads it lossily and
expensively. Which is why `Mark` in the earlier draft is now just this — and why the agent should be told the
path of the text file too when the person sends a photo of it. The drawing says *where*; the file says *what*.

### 3.4 What the agent is told

A person who wants the agent to act on a drawing sends it as an ordinary message naming its path:

```
operator: here is what I mean — agent/drafts/03.png
```

and `look` runs on it in the same turn. That is a **person** turning, not an agent acting on its own: a picture
arriving unannounced in the middle of the agent's own reasoning is exactly the kind of surprise the rest of the
app avoids.

Drafts live in `agent/drafts/`, which is in git's exclude list, so a scratch drawing is never committed. A
person who wants to keep one moves it into the project — and then it is an ordinary image file, committed and
synced like everything else.

---

## 4. The libraries question

**Freehand drawing needs no library.** `Canvas` + `Path` + `pointerInput(detectDragGestures)` is about eighty
lines, and `androidx.compose.ui:ui-graphics` is already a dependency. The alternatives — a whiteboard SDK, a
sketch-recognition library — bring layers, undo stacks and collaboration, none of which is wanted here.

**Two real pieces of plumbing, both Compose's own:**

| need | how |
|---|---|
| render a Composable to a PNG | `graphicsLayer.toImageBitmap()` — available since Compose 1.7.0-beta02, and this app is on the `2026.09.00` BOM, so it is the shorter path and not a version risk. The fallback, if a future BOM removes it, is drawing to `android.graphics.Bitmap` through `Canvas(bitmap)` and wrapping with `asImageBitmap()` |
| save it | `Bitmap.compress(PNG, 100, stream)` — the app already writes PNGs in `shot` |

**What not to add:** an OCR library (the model reads the image), a sketch-recognition library (right 80% of the
time and silently wrong the rest), and any vector-drawing SDK.

---

## 5. What changes, in the order I would do it

| # | change | proved by |
|---|---|---|
| 1 | `Door.messagesOf` builds a **parts array** when a message has attachments | a door test asserting the JSON shape |
| 2 | `ContextBudget` counts an image by **dimensions**, not base64 bytes | a budget test with a known 1024×768 |
| 3 | `look` — the tool, the four formats, the downscale, the refusal for a blind model | `LookTest`, `AgentToolsTest` |
| 4 | the attachment rides the **next** message and is cleared after it | an `AgentTest` that looks, then asserts the parts |
| 5 | `+` gains **New image**, writing a blank PNG | `BrowserChromeTest`, a `WorkspaceTest` kind check |
| 6 | the image editor's `Draw` mode: canvas, tools, save back to the path | `EditorScreenTest` with a drawn path |
| 7 | **Photo**: the text view rendered to a PNG, opened in the image editor | `EditorScreenTest`, a known-size render |

**Steps 1–4 are `look`, and `look` alone closes a loop the app already has**: `shot` produces a PNG and nothing
can consume one. That is worth shipping before any canvas exists.

**Steps 5–7 are independent of 1–4** and could land first if the drawing matters more — nothing about a canvas
needs the agent to be able to see.

---

## 6. The questions this design leaves open

**Q1 — Is the downscale cap 1568 px, or the operator's number?**

*The operator asked what the difference is before and after 1568 px. Answering it corrected this document, so
the correction is here rather than in a reply.*

**1568 is not a threshold the provider has.** It is a number I derived wrongly from the tiling arithmetic. The
provider's own pipeline, which is published, is two steps and neither is 1568:

1. **Scale to fit inside a 2048×2048 box**, keeping the aspect ratio.
2. **Then scale so the shortest side is 768.**

So a 4000×3000 phone photo becomes 2048×1536 at step one and 1024×768 at step two — four tiles, 765 tokens.
**The provider already shrinks every picture for us**, and it does it after the bytes have crossed the network.

| the picture | what the provider does | tiles | tokens |
|---|---|---|---|
| 1024×768 (landscape) | shortest side 768 already | 4 | **765** |
| 2048×1536 | step 2 shrinks it to 1024×768 | 4 | **765** |
| 4000×3000 | step 1 → 2048×1536, step 2 → 1024×768 | 4 | **765** |
| 1080×1920 (a portrait phone screenshot) | → 768×1365 | 6 | **1,105** |
| 768×2048 (portrait, shortest side already 768) | unchanged | 8 | **1,445** |

**Portrait costs more than landscape, and that is not a bug.** The provider pins the *shortest* side at 768, so a
tall picture keeps its height: 768×2048 is four tiles across and four down. A 16:9 phone screenshot is 1,105 and
a very tall one is 1,445 — the number follows the shape, and the app should show the number it will actually be
charged rather than a guess from one edge.

**So what does the cap actually buy?** Not tokens at the model — the provider normalises those anyway. It buys
three things that are real:

- **Upload.** A 4000×3000 PNG is 3–6 MB, base64-inflated to 4–8 MB, inside a JSON body, over a phone
  connection. At 1024×768 it is ~200 kB. That is the difference between a request that works on a train and one
  that times out.
- **The app's own memory.** Decoding 4000×3000 is 48 MB of `Bitmap`; `Pictures.MAX_BITMAP_PIXELS` already
  refuses above 16 MP for this reason.
- **Our own budget arithmetic.** `ContextBudget` has to count the image *before* it is sent, and the honest
  count is what the provider will actually charge — which is the **post-resize** size, not the file's.

**The revised rule, and it is simpler than the one it replaces:** shrink so the **shortest side is 768**, which
is exactly the provider's own second step, and leave anything already at or below that alone. A 1568 px long-edge
cap would have left a 1024×768 picture untouched while shrinking a portrait 768×2048 to 588×1568 — making it
*cost more*, because the tile count is `ceil(w/512) × ceil(h/512)` and the arithmetic does not care about area.

**One thing the resize costs, and it is the one that matters for this feature.** Text gets smaller when a page
shrinks, and small text is what a vision model misreads. A screenshot of an app is usually already ≤1024 px
wide, so it is untouched; a **photo of a page** is where detail is lost — which is an argument for `Photo`
rendering at a readable size rather than for a different cap.

**Q2 — Does `look` take more than one path?** Two pictures in one call is one request rather than two, and
"here is the before and here is the after" is a real thing to ask. It also doubles the cost of one call.
**Answered: yes, multiple paths.**

**Q3 — Where does **Photo** save?** `agent/drafts/` is scratch and never committed; the file's own folder makes
the picture part of the project and syncs it. My reading of the operator's model is that a photo of a file
belongs beside the file, because it is a note about that file — but a scratch one that is deleted after the turn
is also a real way to work.
**Answered: beside the file**, named after it plus an eight-character hash, renameable later.

**Q4 — Does the image editor's `Draw` keep a history of strokes?** Undo within a session is cheap; a saved undo
stack is a second file format and a second thing to keep in sync with the PNG. My answer is **session-only
undo** — the PNG is the document, and the history of the PNG is git's.
**Answered: session-only undo, plus saved versions.**

**Q5 — What does a **new blank image**'s name default to, and what size is it?** A canvas has to have
dimensions before it has content, and the screen's own size is the wrong answer for a file that outlives the
phone.
**Answered: standard size options in the creation dialogue**, defaulting to **the phone screenshot shape,
1080×1920** — the operator's correction, and it is the better default: the thing a person draws on is almost
always a screenshot they took, so the canvas should be the shape they are already used to rather than a square
that fits nothing they have. It costs 1,105 tokens instead of a square's 765, which is the honest price of the
shape the work is actually in.
