package com.example.notes.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The browser the phone already has.
 *
 * **Chromium, supplied by the operating system, at no cost in APK size.** `android.webkit.WebView`
 * is a full Chromium with a real V8, a real DOM, real CSS and real network — the same engine the
 * user's browser runs, updated by the Play Store independently of this app. GeckoView would cost
 * 91 MB of APK for the same capability; Servo's Android artifact costs 40 MB and cannot execute a
 * page's JavaScript at all. This costs nothing.
 *
 * ### The one real cost, said plainly
 *
 * **The engine's version is the device's, not the app's.** A phone that has not updated WebView in
 * three years runs an old Chromium, and a page that needs something newer will fail in a way this
 * code cannot predict. `engine` reports the version so a failure can be explained rather than
 * guessed at. That is the trade for shipping zero bytes, and it is worth stating before it bites.
 *
 * ### Threading
 *
 * WebView must be created and driven on a thread with a `Looper`, and Chromium's own threading
 * document says it uses *the first view thread* as its UI thread — so it is not required to be the
 * main thread, but the thread must exist and must be the same one throughout. A `HandlerThread`
 * created in this class's constructor is that thread. **The WebView is created lazily, on first
 * use, on that thread**, and every call is posted to it; the caller blocks on a latch, which is
 * safe because the caller is never the browser thread.
 */
class WebViewBrowser(private val context: Context) : Browser {

    private val thread = HandlerThread("muffy-browser")
    private val handler: Handler

    /** The WebView, or null until the first call. Only ever touched on [thread]. */
    private var web: WebView? = null

    /** Why the WebView could not be made, once we have found out. */
    private var unavailable: String? = null

    /** The extraction script, read on first use and kept for the life of this browser. */
    private val extract: String by lazy { extractScript(context.applicationContext) }

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    /**
     * Which Chromium this phone is running.
     *
     * Read from the system rather than assumed, because the answer decides what a page that fails
     * can be blamed on. A device with the WebView package disabled reports `none`.
     */
    override val engine: String by lazy {
        val info = try {
            WebView.getCurrentWebViewPackage()
        } catch (e: Throwable) {
            null
        }
        if (info == null) {
            "none"
        } else {
            "${info.packageName} ${info.versionName}"
        }
    }

    override fun text(url: String, wait: Wait, eval: String?, deadline: Deadline): Result<Page> =
        runCatching {
            Addresses.problem(url, privateAllowed)?.let { throw Refused(it) }
            val view = viewOrThrow()
            // **Images are not loaded for a text read.** They cannot appear in the text, and on a
            // phone connection they are most of the bytes: a page of prose is often 20 images and
            // 2 KB of words. A screenshot asks for them; a read does not.
            onWeb { view.settings.loadsImagesAutomatically = false }
            load(view, url, wait, eval, deadline)
            val finalUrl = onWeb { view.url ?: url }
            val title = onWeb { view.title }
            val text = onWeb { evaluate(view, extract, deadline) }.orEmpty()
            if (text.isBlank()) {
                throw IllegalStateException(
                    "The page at $finalUrl had no readable text. It may be empty, it may draw " +
                        "everything with a canvas, or it may still have been loading.",
                )
            }
            Page(text = text, url = finalUrl, title = title?.takeIf { it.isNotBlank() })
        }

    override fun shot(url: String, full: Boolean, eval: String?, deadline: Deadline): Result<ByteArray> =
        runCatching {
            Addresses.problem(url, privateAllowed)?.let { throw Refused(it) }
            val view = viewOrThrow()
            // A screenshot is the one case where the pictures are the point.
            onWeb { view.settings.loadsImagesAutomatically = true }
            load(view, url, Wait.DONE, eval, deadline)
            onWeb { capture(view, full, deadline) }
        }

    /**
     * Releases the engine.
     *
     * Not part of [Browser], because the interface is one-shot and a caller that never made one has
     * nothing to release. The app calls this when its service stops; a process that exits without it
     * leaks nothing that outlives the process.
     */
    fun close() {
        runCatching { onWeb { web?.destroy() } }
        web = null
        thread.quitSafely()
    }

    /** The operator's switch, set by the app. Off by default: a browsable address is a capability. */
    @Volatile
    var privateAllowed: Boolean = false

    // ---- loading ------------------------------------------------------------

    /**
     * Navigates and waits for as much of the page as [wait] asks for.
     *
     * **Polled rather than listened to.** `WebViewClient.onPageFinished` fires for the main frame
     * and for error pages, and there is no callback at all for "the scripts have settled" — so the
     * honest way to ask "is it ready" is to ask the page, which is `document.readyState` and a
     * quiet period after it. Every wait here is bounded by [deadline]; a page that never finishes
     * answers with what it has rather than hanging the agent's turn.
     */
    private fun load(view: WebView, url: String, wait: Wait, eval: String?, deadline: Deadline) {
        onWeb { view.loadUrl(url) }
        // `interactive` is DOM-parsed-but-subresources-running; `complete` is the load event.
        val target = if (wait == Wait.DOM) "interactive" else "complete"
        while (!deadline.expired) {
            val state = onWeb { evaluate(view, "document.readyState", deadline) }.orEmpty()
            if (state == "interactive" || state == "complete") {
                if (state == target || target == "interactive") break
            }
            sleep()
        }
        // **The quiet period, for IDLE and DONE.** A page whose content arrives after it renders is
        // the ordinary case now; `complete` says the first load finished, not that the page has
        // stopped changing. The check is the document's own text length holding still, which is a
        // cheap proxy for "nothing is still arriving" and does not need a network observer.
        if (wait == Wait.IDLE || wait == Wait.DONE) {
            var last = -1L
            var stable = 0
            val rounds = if (wait == Wait.DONE) QUIET_ROUNDS_DONE else QUIET_ROUNDS_IDLE
            while (!deadline.expired && stable < rounds) {
                val size = onWeb {
                    evaluate(view, "(document.body?document.body.innerText:'').length", deadline)
                }?.toLongOrNull() ?: -1L
                if (size == last && size >= 0L) stable++ else stable = 0
                last = size
                sleep()
            }
        }
        // The caller's own expression, after readiness and before anything is read — moli's
        // `--eval` contract. Its value is discarded on purpose: it is an act, not a question.
        if (!eval.isNullOrBlank()) {
            onWeb { evaluate(view, eval, deadline) }
            // A click that navigates leaves the old document behind; give the new one the same
            // bounded chance to arrive rather than reading the page that was just left.
            sleep(QUIET_SETTLE_MS)
            var last = -1L
            var stable = 0
            while (!deadline.expired && stable < 2) {
                val size = onWeb {
                    evaluate(view, "(document.body?document.body.innerText:'').length", deadline)
                }?.toLongOrNull() ?: -1L
                if (size == last && size >= 0L) stable++ else stable = 0
                last = size
                sleep()
            }
        }
    }

    // ---- pixels -------------------------------------------------------------

    /**
     * Draws the page into a PNG.
     *
     * **`measure` and `layout` with an explicit size, then `draw`.** A WebView that is not attached
     * to a window has no size of its own, so asking it to draw would produce a 0×0 bitmap; giving
     * it one is what makes an offscreen capture possible at all. The height comes from the page
     * itself for a full-page shot, which is why it is read before the measure rather than guessed.
     */
    private fun capture(view: WebView, full: Boolean, deadline: Deadline): ByteArray {
        val width = if (view.width > 0) view.width else DEFAULT_WIDTH
        val height = if (full) {
            val page = evaluate(view, "Math.max(document.documentElement.scrollHeight, " +
                "document.body?document.body.scrollHeight:0)", deadline)?.toIntOrNull() ?: 0
            if (page > 0) page else DEFAULT_HEIGHT
        } else {
            if (view.height > 0) view.height else DEFAULT_HEIGHT
        }
        // A page can claim an enormous height; the cap is what stops a bitmap allocation from
        // failing the whole call on a phone with a small heap.
        val capped = height.coerceIn(1, MAX_SHOT_HEIGHT)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, capped)
        val bitmap = Bitmap.createBitmap(width, capped, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val out = ByteArrayOutputStream()
        // PNG, not JPEG: text is what these are for, and JPEG puts a halo around every letter.
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    // ---- the thread ---------------------------------------------------------

    /**
     * Runs [block] on the browser thread and waits for it.
     *
     * **A latch, not `runBlocking`.** The caller is the agent's worker thread and the work must
     * happen on the thread that owns the WebView; blocking the caller for the duration is exactly
     * the intended behaviour, and a coroutine here would add a dispatcher to explain without
     * removing the wait.
     */
    private fun <T> onWeb(block: () -> T): T {
        if (Looper.myLooper() == thread.looper) return block()
        val latch = CountDownLatch(1)
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        handler.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    /**
     * The WebView, made on first use, or the reason it could not be.
     *
     * **A missing WebView is a sentence, not a crash.** A stripped ROM or a disabled system package
     * makes `WebView` throw when it is constructed, and an agent that asked to read a page deserves
     * to be told that this phone cannot, in words it can pass on.
     */
    private fun viewOrThrow(): WebView = onWeb {
        web?.let { return@onWeb it }
        unavailable?.let { throw Refused(it) }
        if (engine == "none") {
            val said = "This phone has no WebView installed, so no page can be loaded."
            unavailable = said
            throw Refused(said)
        }
        try {
            WebView(context).apply {
                settings.javaScriptEnabled = true
                // **Storage, because a page that cannot keep a session is a page that shows a
                // cookie banner on every call.** It is the WebView's own store, shared with the
                // app and nothing else.
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                // A phone-sized viewport, so a page renders its mobile layout rather than
                // pretending to be a desktop and serving the agent a different page.
                settings.useWideViewPort = false
                settings.loadWithOverviewMode = false
                // Nothing here needs a gesture, and a page that requires one should fail loudly.
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.mediaPlaybackRequiresUserGesture = true
                // **No zoom controls and no scrollbars**: this view is never shown, and a visible
                // scrollbar would be drawn into the screenshot.
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }.also { web = it }
        } catch (t: Throwable) {
            val said = "The WebView on this phone could not be started: ${t.message ?: t::class.simpleName}"
            unavailable = said
            throw Refused(said)
        }
    }

    /**
     * Runs [script] and answers its value as text.
     *
     * `evaluateJavascript` answers the **JSON encoding** of the result, so a script that evaluates
     * to a string arrives quoted. Unquoting here is what keeps every caller from having to know
     * that, and `null` becomes empty rather than the four-letter word "null" — the same trap the
     * app's streaming parser hit, where `optString` turned a JSON null into a literal `"null"`.
     */
    private fun evaluate(view: WebView, script: String, deadline: Deadline): String? {
        val latch = CountDownLatch(1)
        val value = AtomicReference<String?>()
        val ok = handler.post {
            try {
                view.evaluateJavascript(script, ValueCallback { raw ->
                    value.set(unquote(raw))
                    latch.countDown()
                })
            } catch (t: Throwable) {
                value.set(null)
                latch.countDown()
            }
        }
        if (!ok) return null
        val waited = if (deadline.remainingMs > 0L) deadline.remainingMs else SCRIPT_TIMEOUT_MS
        if (!latch.await(waited, TimeUnit.MILLISECONDS)) return null
        return value.get()
    }

    private fun sleep(ms: Long = POLL_MS) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** A refusal with a reason, so the agent gets a sentence instead of a stack trace. */
    class Refused(message: String) : Exception(message)

    companion object {
        /**
         * The extraction script, read once from `assets/extract.js`.
         *
         * **Loaded here rather than held as a Kotlin string**, so the part of the browser that is
         * about the web is JavaScript in a file: readable, diffable, and editable without rebuilding
         * a string with every quote escaped. Read through the module's own assets, which is why it
         * takes a `Context` — the app cannot see another module's assets.
         */
        private fun extractScript(context: Context): String = try {
            context.assets.open("extract.js").bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            // A missing asset is a packaging fault, and an empty script would answer "the page had
            // no text" for every page - a wrong answer that looks like a page problem. Fail loudly.
            throw IllegalStateException("The browser's extraction script is missing from the APK.", t)
        }

        private const val POLL_MS = 60L
        private const val QUIET_SETTLE_MS = 250L
        private const val QUIET_ROUNDS_IDLE = 3
        private const val QUIET_ROUNDS_DONE = 5
        private const val SCRIPT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 1920

        /** 16,384 px at 1080 wide is ~70 MB of ARGB; a page claiming more is a page to refuse. */
        private const val MAX_SHOT_HEIGHT = 16_384

        /**
         * The JSON string an `evaluateJavascript` callback carries, as plain text.
         *
         * `null` is empty, because "the page said nothing" and "the page said the word null" are
         * different and only one of them is a value.
         */
        fun unquote(raw: String?): String? {
            if (raw == null || raw == "null") return null
            if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
                return try {
                    org.json.JSONTokener(raw).nextValue() as? String
                } catch (e: Throwable) {
                    raw.substring(1, raw.length - 1)
                }
            }
            return raw
        }
    }
}
