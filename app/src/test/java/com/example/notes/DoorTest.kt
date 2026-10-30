package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which failures move on to the next endpoint, **and which must not.**
 *
 * The operator's rule, in their words: *"make sure that openrouter specific errors (like not enough
 * balance or access denied) dont trigger fallback (they should trigger reconnection)"*.
 *
 * That is a rule about whose problem a failure is. An endpoint that is down is a machine somebody
 * else can replace; an account with no money is the same account at every endpoint, so trying
 * another one spends a request to learn a fact the app already knew — and, worse, reports the
 * second endpoint's answer instead of the real cause. A person who is told "insufficient balance"
 * can act; a person who is told "the last provider timed out" goes looking for a network fault.
 */
class DoorTest {

    @Test
    fun `an empty balance is the account's problem, not the endpoint's`() {
        // 402 is the case the operator named. It is the one that must never fall back: the balance
        // is attached to the key, and every endpoint will refuse for the same reason.
        assertEquals(
            "402 falls back to nothing",
            Failure.ACCOUNT,
            classify(Refused(402, "OpenRouter answered 402: insufficient balance")),
        )
    }

    @Test
    fun `a refused key and a denied model are the account's too`() {
        // **The three codes that are about the key rather than the machine.** 401 is no key or a
        // bad one, 403 is a key that may not do this, 404 is a model this key cannot see. All three
        // follow the account wherever it goes.
        for (code in listOf(401, 403, 404)) {
            assertEquals(
                "$code is the account's",
                Failure.ACCOUNT,
                classify(Refused(code, "OpenRouter answered $code")),
            )
        }
    }

    @Test
    fun `a broken endpoint is the endpoint's problem`() {
        // The other half, and the reason fallback exists at all: a 5xx, a rate limit and a provider
        // timeout are all facts about one machine. A second machine with its own queue and its own
        // health may answer perfectly well.
        for (code in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals(
                "$code is the endpoint's",
                Failure.ENDPOINT,
                classify(Refused(code, "OpenRouter answered $code")),
            )
        }
    }

    @Test
    fun `a request the app built wrongly does not fall back`() {
        // **A 400 is the app's own mistake.** Every endpoint would refuse it in the same words, so
        // walking the list would multiply one complaint by the number of endpoints allowed - and
        // the person would see the last one's message rather than the first one's.
        assertEquals(
            "400 is nobody's endpoint",
            Failure.NOTHING,
            classify(Refused(400, "OpenRouter answered 400")),
        )
    }

    @Test
    fun `a stalled answer is the endpoint's, and that is the commonest case`() {
        // Nothing came back before the clock ran out. That is a fact about this provider right now,
        // which is exactly when a second one is worth asking.
        assertEquals(
            "a stall falls back",
            Failure.ENDPOINT,
            classify(Idle(30_000, Streamed())),
        )
        assertEquals(
            "and so does a request nothing acknowledged",
            Failure.ENDPOINT,
            classify(Unacknowledged(20_000, Streamed())),
        )
    }

    @Test
    fun `a failure with no code falls back to nothing`() {
        // A socket that would not open is a phone with no network. Several endpoints would all fail
        // for that reason, and the session's reconnect ladder is what waits for the network to come
        // back - not a walk down a list.
        assertEquals(
            "no code, no endpoint to blame",
            Failure.NOTHING,
            classify(java.net.UnknownHostException("openrouter.ai")),
        )
    }

    @Test
    fun `a wrapped refusal is still read`() {
        // **The code is sometimes one layer down.** `OpenRouterDoor.turn` wraps whatever it caught
        // in an `IllegalStateException` with a sentence for the person, so a classifier that only
        // looked at the top-level type would call every refusal "nothing" - and every empty balance
        // would then walk the whole list, which is the bug this test exists to prevent.
        val wrapped = IllegalStateException(
            "OpenRouter answered 402: insufficient balance",
            Refused(402, "insufficient balance"),
        )
        assertEquals("the cause is read", Failure.ACCOUNT, classify(wrapped))
    }

    @Test
    fun `the two sets do not overlap`() {
        // A falsification of the obvious way to get this wrong: a range that swallows a code it
        // should not. 5xx is the endpoint's and 4xx is split, so the boundary is where a careless
        // `code in 500..599 || code >= 400` would put 402 on the wrong side.
        val account = listOf(401, 402, 403, 404)
        val endpoint = listOf(408, 429, 500, 503)
        for (code in account) {
            assertFalse(
                "$code must not be treated as the endpoint's",
                classify(Refused(code, "")) == Failure.ENDPOINT,
            )
        }
        for (code in endpoint) {
            assertTrue(
                "$code must not be treated as the account's",
                classify(Refused(code, "")) != Failure.ACCOUNT,
            )
        }
    }
}
