package io.localplayer.app

import org.junit.Assert.*
import org.junit.Test

class OriginalStreamCleanupTest {
    @Test fun uiReleaseFailureDoesNotSkipCredentialAndInputCleanup() {
        val completed=mutableListOf<String>()
        val failure=assertThrows(AssertionError::class.java){
            cleanupOriginalStreamProbe(
                {completed.add("ui");throw IllegalStateException("synthetic release failure")},
                {completed.add("credentials")},
                {completed.add("private-input")}
            )
        }
        assertEquals(listOf("ui","credentials","private-input"),completed)
        assertTrue(failure.cause is IllegalStateException)
    }
}
