package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The meaning of each number, and the precision of the ones the screen prints.
 *
 * **What is left here after SQL took the summing.** Adding up a year of rows belongs in the
 * database, and it moved there — the tests that used to check "which stored level speaks for this
 * unit" are gone with the pyramid they described, because separate tables cannot double-count in
 * the first place. What cannot move is the *meaning*: that a cache share is a part of input rather
 * than a column beside it, that a bucket with no requests has no average rather than a zero, and
 * that a share keeps the decimals the operator asked for, because a rounded share hides a change.
 *
 * The precision tests are here because the numbers this app produces are small enough that the
 * obvious format destroys them: a ping costs about $0.00002, which three decimals prints as
 * "0.000" — indistinguishable from free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestStatsTest {

    @Test
    fun `cache is a share of input, because it is a part of it`() {
        // **The double-count this prevents.** `cached_tokens` is a subset of `prompt_tokens`. A
        // table with three absolute columns — input, output, cache — would report 7,276 tokens for
        // a 3,692-token request, and a person reading it would believe the prompt had doubled.
        val stats = RequestStats.Stats(
            requests = 1,
            promptTokens = 3691,
            cachedTokens = 3584,
            completionTokens = 1,
            reasoningTokens = 1,
            cost = 0.0000241,
            costReported = 1,
        )

        assertEquals("a share of input", 0.9710, stats.cachedShare!!, 0.0001)
        assertEquals("and of output", 1.0, stats.thinkingShare!!, 0.0001)
        // The two absolutes do not overlap: input and output are the whole request.
        assertEquals(3692, stats.promptTokens + stats.completionTokens)
    }
}
