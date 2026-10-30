package com.example.notes.browser

import java.net.URI
import java.net.URISyntaxException

/**
 * A web page, as an agent can see it.
 *
 * **The engine is behind this line, and that is the whole point of the line.** What ships is
 * [WebViewBrowser] — the Chromium the phone already has, at no cost in APK size. GeckoView would
 * slot in behind the same interface for a device with no usable WebView, and moli would slot in if
 * a bionic build ever appears. Nothing that calls a `Browser` knows which one ran, so that decision
 * can be revisited without touching the agent.
 *
 * **One-shot, like moli's `fetch`.** A call loads a page, optionally runs one expression against it,
 * and answers with text or pixels. There is no session, no handle to keep, and no second call that
 * continues the first — which is why this interface has no `close()` and no state to leak. An agent
 * that wants two pages makes two calls.
 */
interface Browser {

    /**
     * Loads [url] and answers what the page says.
     *
     * [eval] runs **after the page is ready and before the text is taken**, which is the same
     * contract as moli's `--eval`: it is the only interaction there is, and its return value is
     * discarded. A page that needs a click before its content exists is read by clicking first.
     */
    fun text(
        url: String,
        wait: Wait = Wait.DONE,
        eval: String? = null,
        deadline: Deadline = Deadline.NONE,
    ): Result<Page>

    /**
     * Loads [url] and answers a PNG of it.
     *
     * [full] asks for the whole document rather than the viewport. The bytes are the image and
     * nothing else — no encoding, no wrapper — so a caller writes them to a file and is done.
     */
    fun shot(
        url: String,
        full: Boolean = false,
        eval: String? = null,
        deadline: Deadline = Deadline.NONE,
    ): Result<ByteArray>

    /**
     * Which engine this is, for the record and for a refusal that has to say why it cannot browse.
     *
     * A device with no WebView at all is real — a stripped ROM, a phone with the WebView package
     * disabled — and "the agent cannot browse" must be a sentence rather than a crash.
     */
    val engine: String

    companion object {
        /**
         * The browser for a device that has none.
         *
         * **The default everywhere, including tests.** A fake that silently answered with empty text
         * would let a test pass while the tool was wired to nothing; a refusal is a fact the caller
         * can see. It is also what [WebViewBrowser] degrades to when the WebView package is missing.
         */
        val NONE: Browser = NoBrowser
    }
}

/** How much of the page has to have happened before it is read. */
enum class Wait {
    /**
     * The document has parsed. The fastest answer, and the one that misses content a script adds.
     */
    DOM,

    /** The load event has fired: images and subresources are in, scripts may still be running. */
    LOAD,

    /**
     * Nothing has been requested for a moment, or [LOAD] plus a short quiet period.
     *
     * The one to use for a page whose content is fetched after it renders, which is most of them
     * now. It is a heuristic rather than a promise, so it is bounded by the deadline.
     */
    IDLE,

    /** [IDLE], and then a moment more. The default, because "it looked finished" is usually right. */
    DONE,
}

/**
 * What a page said, and where it said it.
 *
 * [url] is the address that was actually read, which is not the one that was asked for when the
 * page redirected. Keeping both matters to an agent: it asked for one thing and is reading another,
 * and a summary that hides that is a summary that lies about its source.
 */
data class Page(
    /** The page's text, as the extractor produced it. Never null; an empty page is empty. */
    val text: String,
    /** Where the content actually came from, after any redirects. */
    val url: String,
    /** The document's title, when it had one. */
    val title: String? = null,
)

/**
 * How long a call may take.
 *
 * **Its own type rather than a `Long`, so "no limit" cannot be confused with "zero milliseconds".**
 * The app has its own `Deadline` in `Timeouts.kt`, and this one deliberately does not use it: a
 * library that reaches back into the app is not a library, and the app is what depends on this.
 * The shape is the same on purpose, so a caller passes `Deadline(ms)` and reads it the same way it
 * already reads the app's own.
 */
class Deadline(
    private val ms: Long,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val started = now()

    /** True when [ms] has passed. Zero or less means "no limit", never "expired". */
    val expired: Boolean get() = ms > 0L && now() - started >= ms

    /** How long is left, or [ms] itself when there is no limit. */
    val remainingMs: Long get() = if (ms <= 0L) ms else (ms - (now() - started)).coerceAtLeast(0L)

    companion object {
        /** No limit. Named, so a call site reads as a decision rather than a bare zero. */
        val NONE = Deadline(0L)
    }
}

/**
 * The browser for a device that has none, and the one every test gets unless it says otherwise.
 *
 * Answers a refusal that names the reason, because "it did not work" and "this phone cannot do it"
 * are different sentences and an agent can act on only one of them.
 */
object NoBrowser : Browser {
    override val engine: String = "none"

    override fun text(url: String, wait: Wait, eval: String?, deadline: Deadline): Result<Page> =
        Result.failure(UnsupportedOperationException(NO_WEBVIEW))

    override fun shot(url: String, full: Boolean, eval: String?, deadline: Deadline): Result<ByteArray> =
        Result.failure(UnsupportedOperationException(NO_WEBVIEW))

    private const val NO_WEBVIEW =
        "This phone has no usable WebView, so nothing can be loaded from the web. " +
            "The Android System WebView package may be missing or disabled."
}

/**
 * Which addresses a browser may be pointed at.
 *
 * **A browsable address is a capability, and this is where it is granted.** An agent that can reach
 * `http://192.168.1.1/` can reach the operator's router; one that can reach `127.0.0.1` can reach
 * whatever else is listening on the phone. Neither is something a web page should be able to steer
 * the agent into, so the default is the public internet and nothing else.
 *
 * **Refused by address, not by name**, because a name is a claim: `localhost` resolves to loopback
 * and `example.com` can be made to as well. The check runs on what will actually be connected to,
 * which means it must run **after** resolution to be complete — and this app resolves nothing
 * itself, so the check is on the literal and on the host name, and says so.
 */
object Addresses {

    /**
     * Why [url] may not be browsed, or null when it may.
     *
     * [privateAllowed] is the operator's switch. It is off by default: a home server or a NAS is a
     * real use, and it is one a person should choose rather than discover.
     */
    fun problem(url: String, privateAllowed: Boolean = false): String? {
        val parsed = try {
            URI(url.trim())
        } catch (e: URISyntaxException) {
            return "That is not an address: ${url.take(120)}"
        }
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Only http and https can be browsed, and that is ${scheme ?: "not an address"}."
        }
        val host = parsed.host?.lowercase()?.trim('[', ']') ?: return "That address has no host."
        if (privateAllowed) return null
        return when {
            host == "localhost" || host.endsWith(".localhost") ->
                "$host is this phone. Allow private addresses in settings to browse it."
            isLoopback(host) -> "$host is this phone. Allow private addresses in settings to browse it."
            isPrivateV4(host) ->
                "$host is on a private network. Allow private addresses in settings to browse it."
            isLinkLocal(host) ->
                "$host is a link-local address. Allow private addresses in settings to browse it."
            else -> null
        }
    }

    /** `127.0.0.0/8` and `::1`. */
    private fun isLoopback(host: String): Boolean =
        host == "::1" || host.startsWith("127.")

    /**
     * The three private IPv4 ranges: `10/8`, `172.16/12`, `192.168/16`.
     *
     * Written as ranges rather than as a prefix test on the text, because `192.168.1.1` and
     * `192.168.0.1` are the same network while `192.169.1.1` is not — a string test would have to
     * know that, and would get it wrong the first time somebody typed a shorter form.
     */
    private fun isPrivateV4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it < 0 || it > 255 }) return false
        val (a, b) = octets
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** `169.254/16` — the range a phone uses when it is looking for a DHCP server. */
    private fun isLinkLocal(host: String): Boolean =
        host.startsWith("169.254.") || host.lowercase().startsWith("fe80:")
}
