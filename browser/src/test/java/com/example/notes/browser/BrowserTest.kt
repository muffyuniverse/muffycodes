package com.example.notes.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What can be proved about a browser without a browser.
 *
 * **The honest split, said once and meant.** Robolectric's `ShadowWebView` is a fake: it records
 * `loadUrl` and answers canned values, and it does not execute JavaScript. So no test in this module
 * can prove that a real page is read correctly — that needs a device, and it is the same limit
 * report 42 hit with text selection.
 *
 * What *can* be proved, and is worth proving, is everything around the engine: which addresses are
 * refused, what a missing WebView says, how a JSON-encoded result becomes text, and that the
 * extraction script is packaged at all. Those are the parts that break silently. The engine itself
 * breaks loudly, on the phone, where the operator will see it.
 */
class BrowserTest {

    // ---- what a page may be -------------------------------------------------

    @Test
    fun `the public internet is allowed and this phone is not`() {
        // **The default is the public internet and nothing else**, because a browsable address is a
        // capability. An agent steered by a web page must not be able to reach the operator's
        // router, a NAS, or whatever else is listening on the phone.
        assertNull("a public site", Addresses.problem("https://example.com/page"))
        assertNull("a public site on http", Addresses.problem("http://example.com"))
        assertNull("an address with a port", Addresses.problem("https://example.com:8443/x"))

        // The three private IPv4 ranges, one example each, plus loopback and link-local.
        for (refused in listOf(
            "http://127.0.0.1:8080/",
            "http://127.1.2.3/",
            "http://localhost/",
            "http://foo.localhost/",
            "http://10.0.0.1/",
            "http://172.16.5.4/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://169.254.1.1/",
            "http://[::1]/",
        )) {
            assertNotNull("$refused must be refused by default", Addresses.problem(refused))
        }
    }

    @Test
    fun `the private ranges are ranges, not prefixes of text`() {
        // **`192.168` is a network; `192.169` is not.** A string-prefix test would refuse the
        // second and, worse, a test written the same lazy way would agree with it. The octets are
        // parsed so that the answer is about the address rather than about how it is written.
        assertNotNull("the bottom of 172.16/12", Addresses.problem("http://172.16.0.1/"))
        assertNotNull("the top of 172.16/12", Addresses.problem("http://172.31.255.254/"))
        assertNull("and one past it", Addresses.problem("http://172.32.0.1/"))
        assertNull("below the range", Addresses.problem("http://172.15.0.1/"))
        assertNull("192.169 is public", Addresses.problem("http://192.169.1.1/"))
        assertNull("and so is 11.x", Addresses.problem("http://11.0.0.1/"))
    }

    @Test
    fun `only http and https are addresses a browser may open`() {
        // A `file://` URL would read the phone's storage, and `javascript:` would run a script in
        // whatever document happened to be loaded. Neither is a page, and both are worse than a
        // refusal.
        for (refused in listOf(
            "file:///data/data/com.example.notes/files/agent/session.log",
            "javascript:alert(1)",
            "content://com.android.contacts/data/1",
            "ftp://example.com/file",
            "data:text/html,<h1>hi</h1>",
        )) {
            assertNotNull("$refused must be refused", Addresses.problem(refused))
        }
        assertTrue(
            "and the refusal says what is allowed",
            Addresses.problem("file:///etc/passwd")!!.contains("http"),
        )
    }

    @Test
    fun `a refused address says which address and how to allow it`() {
        // A refusal that only says "no" leaves a person unable to tell a rule from a bug. The
        // sentence names the host and the switch, so both are actionable.
        val said = Addresses.problem("http://192.168.1.1/")!!
        assertTrue("names the host: $said", said.contains("192.168.1.1"))
        assertTrue("names the switch: $said", said.contains("settings"))
    }

    @Test
    fun `the operator's switch turns the refusal off`() {
        // A home server or a NAS is a real use. It is a choice a person makes, not a default they
        // discover — which is the whole reason the switch exists rather than a cleverer rule.
        assertNull("allowed when the operator says so", Addresses.problem("http://192.168.1.1/", privateAllowed = true))
        assertNull("and loopback too", Addresses.problem("http://127.0.0.1:8080/", privateAllowed = true))
        // But a scheme is still not a page, switch or no switch: the capability is about *where*,
        // not about *what*.
        assertNotNull("a file is still not a page", Addresses.problem("file:///etc/passwd", privateAllowed = true))
    }

    // ---- a phone with no browser -------------------------------------------

    @Test
    fun `a phone with no WebView says so instead of failing quietly`() {
        // **The default everywhere, including tests.** A fake that answered with empty text would
        // let a test pass while the tool was wired to nothing; a refusal is a fact the caller sees.
        val tried = Browser.NONE.text("https://example.com")
        assertTrue("it fails", tried.isFailure)
        val said = tried.exceptionOrNull()?.message.orEmpty()
        assertTrue("and says why: $said", said.contains("WebView"))
        assertEquals("the engine is named", "none", Browser.NONE.engine)
    }

    // ---- the value that comes back -----------------------------------------

    @Test
    fun `a JSON-encoded result becomes the text it encoded`() {
        // `evaluateJavascript` answers the JSON encoding of the result, so a string arrives quoted
        // and a null arrives as the four-letter word. Both are unquoted in one place so no caller
        // has to know — the same trap the app's streaming parser hit with `optString`.
        assertEquals("a plain string", "plain", WebViewBrowser.unquote("\"plain\""))
        assertEquals(
            "escaped quotes come back as quotes",
            "with a \"quote\" in it",
            WebViewBrowser.unquote("\"with a \\\"quote\\\" in it\""),
        )
        assertEquals("an escaped newline is a newline", "one\ntwo", WebViewBrowser.unquote("\"one\\ntwo\""))
        assertEquals("a bare number is left alone", "42", WebViewBrowser.unquote("42"))
        assertNull("null is nothing, not the word", WebViewBrowser.unquote("null"))
        assertNull("and neither is an absent answer", WebViewBrowser.unquote(null))
    }

    // ---- the script that does the reading ----------------------------------

    @Test
    fun `the extraction script is where the browser looks for it`() {
        // **A packaging fault that would otherwise look like a page problem.** Without the asset
        // every page would answer "it had no readable text", which is a wrong answer about the web
        // rather than a right answer about the build.
        //
        // Asserted against the source tree rather than through a class loader, because a unit test
        // runs from the module's build folder and the asset is only merged into the APK by a later
        // task. The path is the contract: `WebViewBrowser` opens `assets/extract.js` from the
        // module's own assets, so the file must exist at `src/main/assets/extract.js`. If somebody
        // moves it, this fails and says so, which is the part a test can honestly check — the
        // script's own behaviour needs a real DOM.
        val script = java.io.File("src/main/assets/extract.js")
        assertTrue("extract.js is in the module's assets: ${script.absolutePath}", script.isFile)
        val loaded = script.readText()
        assertTrue("and it is the extractor, not a placeholder", loaded.contains("document.body"))
        assertTrue("it answers text", loaded.contains("return kept.join"))
    }
}
