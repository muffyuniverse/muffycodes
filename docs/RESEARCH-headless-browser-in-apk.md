# A headless browser that ships inside the APK

*Research, 2026-10-30. Answers: which agentic-browser engines can actually live inside a standalone
muffycodes APK, at what cost, and what they can do. Every size below was measured today by
downloading the artifact and unpacking it, not read from a blog.*

---

## 0. The answer first

**Use the WebView the phone already has. It costs 0 bytes, it is real Chromium, and it is the only
candidate that ships standalone.**

| candidate | added to APK | JS | DOM | screenshot | verdict |
|---|---|---|---|---|---|
| **System WebView** (`android.webkit`) | **0 MB** | **yes**, Chromium | **yes** | yes | **recommended** |
| GeckoView (arm64-v8a) | **+91 MB** (168 MB unpacked) | yes, Gecko | yes | yes | viable, enormous |
| Servo (`servo-aarch64-android.aar`) | +40 MB (89 MB `.so`) | **no** | yes | needs SurfaceView | **disqualified** |
| Jetpack JavaScriptEngine | **+73 KB** | yes, V8 | **no** | no | not a browser |
| moli / Lightpanda | no bionic build | yes | yes | yes | needs a V8 cross-compile |
| Ultralight | no Android target | yes, WebKit | yes | yes | not viable |

Current release APK is **10.2 MB**. The question is whether browsing is worth +91 MB (GeckoView),
+40 MB for an engine with no JavaScript (Servo), or **nothing at all** (WebView).

---

## 1. What I measured

### 1.1 GeckoView — real, and enormous

`org.mozilla.geckoview:geckoview-omni-arm64-v8a:159.0.20261022150525`

```
AAR download            91,438,720 B   91.4 MB
unpacked .so total     168.7 MB
  libxul.so            154,134,352 B  154.1 MB   ← the engine
  libcrashtools.so       5,984,120 B
  libgkcodecs.so         5,141,360 B
  libcrashhelper.so      3,435,168 B
  libnss3.so             2,881,184 B
  libmozavcodec.so       2,123,704 B
  (+ 7 smaller)
```

GeckoView is a *complete* browser engine with a WebView-shaped API: `GeckoSession`,
`loadUri`, `GeckoSession.evaluateJS`, `GeckoView.capturePixels`. It would work. Mozilla's own
answer on shrinking it is "we can't do much to reduce the size of GeckoView" — it can load any web
content, so it retains all of Gecko.

**+91 MB to a 10 MB app is a 10× increase.** That is not a build detail; it is a different product.

### 1.2 Servo — the right size, and it cannot run JavaScript

`servo-aarch64-android.aar` from `servo/servo` v0.7.0 — a real, published Android artifact, 39.9 MB.

```
AAR download            39,860,285 B   39.9 MB
unpacked .so total      98.5 MB
  libservoshell.so     89,209,632 B
  libc++_shared.so      9,290,184 B
```

I checked whether the JavaScript engine is in there:

```
strings libservoshell.so | grep -E "^JS_[A-Za-z]+|mozjs|SpiderMonkey|V8_"   → 0 matches
```

**Zero.** Servo's SpiderMonkey is not in the shipped Android build. Servo upstream does integrate
SpiderMonkey, so this is a packaging choice for the mobile artifact, not a permanent limit — but the
artifact that exists today cannot execute a page's script, which is the entire reason to want a
browser engine instead of an HTTP client.

It also embeds as `org.servo.servoview.ServoView` — a `SurfaceView`. That is a *rendering* embedding,
for building a browser UI. Extracting text headlessly through it is fighting the API's grain.

**Disqualified on two independent counts.**

### 1.3 Jetpack JavaScriptEngine — 73 KB, and not a browser

`androidx.javascriptengine:javascriptengine:1.0.0-alpha06` — **73,342 B**.

V8 in an isolated sandbox process. Genuinely useful, genuinely tiny. But the API is
`JavaScriptSandbox.createConnectedInstanceAsync()` → `evaluateJavaScript(code)` with a `ConsoleMessage`
handler. **There is no DOM, no `document`, no `window`, no fetch of a URL.** It evaluates expressions
you already wrote. It cannot load a page.

Worth remembering as a *component* (a place to run the agent's extraction script safely), but it does
not browse.

### 1.4 moli and Lightpanda — the same wall as before

Both are Rust + their own V8 + glibc. Lightpanda's releases: `x86_64-linux`, `aarch64-linux`,
`aarch64-macos`, `x86_64-macos`. No bionic. Rebuilding either means a **bionic V8**, which is the
multi-hour cross-compile that upstream rusty_v8 does not publish and that the platform's W^X rules
fight.

### 1.5 Ultralight — checked and out

A WebKit fork with a small footprint and an ARM64 release in 1.4. Targets are Windows, macOS, Linux,
Xbox, PS4, PS5. **No Android.** Not viable, and its licence is commercial.

---

## 2. The WebView option, examined honestly

It is easy to dismiss "just use WebView" as the cheap answer. It is not cheap; it is the *same
architecture* as moli with the engine supplied by the operating system instead of bundled.

### What it gives

| capability | how |
|---|---|
| real JavaScript | Chromium, updated by the Play Store independently of the app |
| real DOM | `evaluateJavascript` returns a JSON-encoded value |
| a page's text | `evaluateJavascript("document.body.innerText")` |
| structured extraction | a script that walks the DOM and returns JSON — the same idea as moli's `--dump markdown`, written in JS instead of Rust |
| screenshots | `measure()` + `layout()` + `draw(Canvas)` into a `Bitmap`, offscreen |
| interaction | `evaluateJavascript("document.querySelector(sel).click()")` — exactly moli's `--eval` |
| cookies, storage, auth | the WebView's own cookie jar and `localStorage` |

**The critical comparison to moli's selling point.** moli's advantage is "structure first, pixels on
demand". A WebView with `--layout` never on, driven by a JS extraction script, gives the *same*
property: you get `innerText` or a semantic tree, and you never pay for paint. The engine differs; the
economics are identical.

And on JavaScript compatibility, WebView wins outright: it is Chromium, the thing sites are tested
against. moli's DOM is its own implementation.

### What it costs, and where the pain is

1. **The engine is not in the APK, so its version is the device's.** `WebView.getCurrentWebViewPackage()`
   reports it. Old devices have old Chromium. This is a real portability risk that moli would not have
   — and it is the price of shipping 0 bytes.

2. **WebView needs a `Looper` thread — but not necessarily the main one.** Chromium's own threading
   doc is explicit: *"it's not safe to assume there's only a single **view** thread, or that the
   **view** thread and the **main** thread is the same"* — WebView uses *the first view thread* as its
   UI thread. So a `HandlerThread` with a `Looper`, created before any other view tree, is a
   supported arrangement rather than a hack. The app's agent work currently runs on a plain executor
   thread, so this needs one `HandlerThread` owned by the browsing component, and the WebView must be
   created on it before anything else creates a view.

3. **Context.** `WebView` needs an `Activity`-ish context for full function; `applicationContext`
   works for loading but some features (dialogs, some media) need more. The app already holds a
   foreground service (`FOREGROUND_SERVICE_SPECIAL_USE` is in the manifest), so a service-hosted
   WebView is legitimate — but the agent must be able to browse while the UI is elsewhere, and that
   needs care.

4. **It cannot be tested in Robolectric.** Robolectric ships `ShadowWebView`, which is a *fake*: it
   records `loadUrl` and returns canned results. **It does not execute JavaScript.** So the engine
   itself is untestable in this suite.

   This is the same problem as report #42 (text selection) and it deserves the same honesty: the
   *interface* can be tested, the *engine* cannot. The design below is shaped to keep everything that
   matters on the testable side of that line.

---

## 3. The design that makes the engine swappable

The mistake would be to write `WebView` calls directly into `AgentTools`. Then the engine is welded to
the tool, and switching to GeckoView later — or to moli, if a bionic build ever appears — means
rewriting the tools.

Instead: **one interface, two implementations, and the tools know neither.**

```kotlin
/**
 * A page, as an agent can see it.
 *
 * The interface exists so the engine is a decision rather than a fact. WebView is what ships;
 * GeckoView is what a device without a usable WebView could use; moli is what a future bionic
 * build would slot in behind. Nothing above this line knows which one ran.
 */
interface Browser {
    /** Loads [url] and answers its text, or a refusal that says why not. */
    fun text(url: String, wait: Wait, eval: String?, deadline: Deadline): Result<String>

    /** The page after [eval], as pixels. Null when the engine cannot paint. */
    fun shot(url: String, full: Boolean, eval: String?, deadline: Deadline): Result<ByteArray>
}
```

Then:

- `WebViewBrowser` — the shipping one. Owns a `HandlerThread`, creates the `WebView` once, reuses it.
- `NoBrowser` — the default in tests and on a device where the engine is missing. Answers
  "this phone cannot browse" rather than pretending.
- A future `GeckoBrowser` or `MoliBrowser` — same interface, no change above it.

`AgentTools` gains `browse` and `shot`, and takes a `Browser`. In the test suite it takes `NoBrowser`
or a hand-written fake, so **the tool logic — keys, ranges, the result-size limit, refusals — is fully
tested**, and only the engine call is not. That is the same split the app already uses for `Door`
(tested against fixtures) and for git (`JGit`, pure Java, testable).

---

## 4. Is this "a headless agentic browser like moli"?

Mostly yes, and one part no.

**Yes:** real JS, real DOM, extraction to text or structure, one-shot `eval` interaction, offscreen
screenshots, no window shown to the user. That is moli's capability set.

**No:** moli also speaks **CDP / WebDriver Classic / BiDi**, so an external harness can drive it like
Chrome. WebView has no such server. If "agentic browser" means "something my other tools can attach to
over CDP", WebView cannot do it — and neither can any in-APK engine except a full Chromium bundle,
which is the 150 MB problem again.

Worth stating plainly because it is the one capability that would force GeckoView or a remote moli.

---

## 5. What I would build

1. **`Browser.kt`** — the interface above, plus `NoBrowser`. No Android imports in the file beyond
   what the interface needs, so it compiles in the JVM tests.
2. **`WebViewBrowser.kt`** — `HandlerThread` + one reused `WebView`, `evaluateJavascript` wrapped in a
   `suspendCancellableCoroutine`, timeouts through the existing `Deadline`. Its JS extractor lives in a
   separate `assets/extract.js` so it can be read and edited as JavaScript.
3. **`browse` and `shot` in `AgentTools`** — a `KEYS` entry, a `specs()` example, a dispatch line, and
   the existing result-size path. Tested against a fake `Browser`.
4. **The two safety pieces from the previous proposal**, which do not change with the engine:
   - **private networks refused by default** (an agent must not reach `192.168.x.x`, `10.x`, or
     `127.0.0.1` — the last one matters because the app itself listens on nothing, but other apps do);
   - **fetched text labelled as data** where it enters the context, because a page can contain
     instructions and the model cannot tell them from the operator's.
5. **Settings**: one switch ("let the agent browse the web"), off by default.

Steps 1–3 are the feature. Step 4 is the part that decides whether it is safe to ship.

---

## 6. If the operator wants the engine bundled anyway

The honest ranking, if 0 MB is not acceptable for some reason:

1. **GeckoView** (+91 MB) — the only bundled engine that actually works today. Full JS, full DOM,
   screenshots, a real embedding API, MPL 2.0. It would make the APK ~101 MB.
2. **Servo** (+40 MB) — cheaper, and cannot run a page's JavaScript today. Only worth it if Servo
   starts shipping SpiderMonkey in the Android artifact.
3. **moli / Lightpanda** — needs a bionic V8 that nobody ships. This is the option that looks
   cheapest on paper and is the most expensive in reality.

---

## 7. Open questions

1. **Is CDP interoperability required?** If yes, nothing in-APK works and the answer changes
   completely. If no, WebView is sufficient.
2. **Is a ~100 MB APK acceptable?** If yes, GeckoView is a real option and removes the "device's
   WebView version" risk.
3. **Must browsing work with no UI at all?** WebView needs a `Looper` thread and, in practice, a
   service. The app has a foreground service already; confirm the agent should browse from it.
4. **Should the agent be able to reach the local network?** Default no, as before.
