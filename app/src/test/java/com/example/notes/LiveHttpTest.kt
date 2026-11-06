package com.example.notes

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * **One real request, by hand — the only test in this repo that reaches the network.**
 *
 * Every other `http` test uses a fake `Fetcher`, so the redirect rule, header casing and `PATCH`
 * behaviour are reasoned rather than observed. That is the largest untested risk in the tree, and
 * this is the smallest thing that retires it.
 *
 * **Skipped unless `MUFFY_LIVE_HTTP=1`.** A test suite that needs the internet is a suite that fails
 * on a plane, and CI must not depend on GitHub's uptime to say whether this app compiles.
 */
class LiveHttpTest {

    private fun live() = assumeTrue(
        "set MUFFY_LIVE_HTTP=1 to run the one test that reaches the network",
        System.getenv("MUFFY_LIVE_HTTP") == "1",
    )

    @Test
    fun `a real GET answers a real status, real headers and real bytes`() {
        live()
        val answer = Fetcher.http().request(
            "https://api.github.com/zen",
            "GET",
            mapOf("Accept" to "application/vnd.github+json"),
            null,
            1024 * 1024,
        ).getOrThrow()

        assertTrue("a 2xx: ${answer.status}", answer.status in 200..299)
        assertTrue("and it really is GitHub's zen", answer.text().isNotBlank())
        // **The header map is really populated**, which is what `rateLimitNote` depends on and what
        // a fake never proves.
        assertTrue(
            "and headers arrived: ${answer.headers.keys}",
            answer.headers.keys.any { it.equals("Content-Type", ignoreCase = true) },
        )
    }

    @Test
    fun `a GET follows a real redirect, and a POST does not`() {
        live()
        // httpbin redirects /redirect/1 → /get, which is a real hop across a real server.
        val got = Fetcher.http().request(
            "https://httpbin.org/redirect/1", "GET", emptyMap(), null, 1024 * 1024,
        ).getOrThrow()
        assertTrue("the GET was followed: ${got.status}", got.status in 200..299)

        // **And a POST is NOT followed** — the answer is the 3xx itself, with its Location, because
        // a POST that became a GET is not the request the caller made.
        val posted = Fetcher.http().request(
            "https://httpbin.org/redirect-to?url=/get&status_code=302",
            "POST",
            emptyMap(),
            "x=1".toByteArray(),
            1024 * 1024,
        ).getOrThrow()
        assertTrue("the POST was returned, not followed: ${posted.status}", posted.status in 300..399)
        assertTrue("and it said where to go", posted.headers["Location"].orEmpty().isNotEmpty())
    }

    @Test
    fun `a PATCH really arrives as a PATCH`() {
        live()
        // **The method is the point.** `HttpURLConnection` silently rewrites some methods, and a
        // `PATCH` that arrived as a `POST` would be a bug no fake could show.
        val answer = Fetcher.http().request(
            "https://httpbin.org/anything", "PATCH", emptyMap(), "{\"a\":1}".toByteArray(), 1024 * 1024,
        ).getOrThrow()

        assertTrue("it worked: ${answer.status}", answer.status in 200..299)
        assertTrue("and the server saw PATCH: ${answer.text()}", answer.text().contains("\"PATCH\""))
    }

    @Test
    fun `a private address is refused before anything is opened`() {
        // **No network needed, and this is the one that matters for safety.** A check that ran after
        // the connection would already have reached the operator's router.
        val refused = Fetcher.http().request("http://127.0.0.1:8080/x", "GET", emptyMap(), null, 1024)
        assertTrue("refused: ${refused.exceptionOrNull()?.message}", refused.isFailure)
    }
}
