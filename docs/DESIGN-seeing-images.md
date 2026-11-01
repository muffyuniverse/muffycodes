# Seeing: images into a conversation, and drawing out of one

Written 2026-11-01. Two capabilities that look like one and are not:

1. **`look`** — the agent can see an image. A picture is a thing it currently cannot receive at all.
2. **Drawing** — the person can sketch or annotate, and the agent reads it.

They are separate because the first is a **transport** problem and the second is a **UI** problem, and the
transport is the one with the constraints. Drawing needs no new mechanism once `look` exists: it ends in a PNG,
and `look` already reads PNGs.

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

## 3. Drawing — two modes, one canvas

The operator's framing, which is the requirement:

> user can draw drafts or design corrections for the model to act upon — something that is almost impossible to
> transfer via text

Both modes, as two segments in the editor's existing mode row (`View · Edit · Diff · **Sketch** · **Mark**`).

### 3.1 Sketch — a blank canvas

Freehand, arrows, boxes, and text. For *"put the header here, the list under it, the button bottom-right"* —
layouts, flows, structure. The model reads geometry well, and a sketch is the only way to say "this goes there".

### 3.2 Mark — the file's own text, drawn on

The file's text rendered underneath, and the person draws **over** it: circle the bad line, arrow to where it
should go, cross out a block.

**This is the valuable one, and it has a trap.** A PNG of the editor is a *picture of text* — expensive and
lossy. So `Mark` saves **two things**: the annotation image, and the file's text. The next message carries both,
so the model reads **exact text** and uses the drawing only for **where**. A circle means nothing without the
line it circles; the line means nothing without the circle.

### 3.3 What is saved, and where

```
agent/drafts/<name>.png          the drawing
agent/drafts/<name>.txt          the file's text, for Mark (and for Sketch, the prompt that asked for it)
```

`agent/` is in git's exclude list, so a draft is never committed and never pushed — the same rule every other
result file follows. A person who wants to keep one can `move` it into the project.

### 3.4 What the model is told

The draft is not attached by magic. The person draws, presses **Send**, and the app writes an ordinary operator
message whose text is the draft's path, and `look` runs on it in the same turn:

```
operator: here is what I mean — agent/drafts/03.png
```

That is a **person** turning, not an agent acting on its own, and it means the drawing arrives as a message
rather than as a surprise in the middle of the agent's own reasoning.

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
| 5 | the editor's `Sketch` and `Mark` segments, and the save | `EditorScreenTest` with a drawn path |
| 6 | the person's **Send** writing the draft as an operator message | `AgentChatScreenTest` |

**Steps 1–4 are `look`, and `look` alone closes a loop the app already has**: `shot` produces a PNG, and today
nothing can consume one. That is worth shipping before any canvas exists.

---

## 6. The questions this design leaves open

**Q1 — Is the downscale cap 1568 px, or the operator's number?** 1568 is where OpenAI's tiling stops helping;
a phone photo is 4000 px wide, so it is a real change rather than a formality.

**Q2 — Does `look` take more than one path?** Two pictures in one call is one request rather than two, and
"here is the before and here is the after" is a real thing to ask. It also doubles the cost of one call, and a
model given four images spends its attention across them.

**Q3 — Should a draft be attached automatically when the person draws?** This design says **no** — Send writes
an ordinary message, because a picture arriving unannounced in the middle of the agent's reasoning is exactly
the kind of surprise the rest of the app avoids. The alternative is one fewer tap and a stranger conversation.
