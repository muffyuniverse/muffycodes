package com.example.notes

import org.junit.Assert.assertTrue
import org.junit.Test

/** Temporary: proves the pipeline stops when a test fails. Deleted at once. */
class RedProbeTest {
    @Test
    fun thisIsDeliberatelyRed() {
        assertTrue("a deliberate failure", false)
    }
}
