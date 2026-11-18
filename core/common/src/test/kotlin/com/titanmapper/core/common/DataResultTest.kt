package com.titanmapper.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataResultTest {

    @Test
    fun `Success holds value`() {
        val result = DataResult.Success(42)
        assertEquals(42, result.value)
    }

    @Test
    fun `Error holds message`() {
        val result = DataResult.Error<Int>("oops")
        assertEquals("oops", result.message)
        assertFalse(result.recoverable)
    }

    @Test
    fun `safeCall returns Success on normal execution`() = runTest {
        val result = safeCall("fail") { 7 }
        assertTrue(result is DataResult.Success)
        assertEquals(7, (result as DataResult.Success).value)
    }

    @Test
    fun `safeCall returns Error on exception`() = runTest {
        val result = safeCall("fail") { throw RuntimeException("boom") }
        assertTrue(result is DataResult.Error)
        assertTrue((result as DataResult.Error).message.contains("fail"))
    }

    @Test
    fun `safeCall rethrows CancellationException`() = runTest {
        var caughtCancel = false
        // CancellationException must propagate so structured concurrency works correctly.
        val job = launch {
            try {
                safeCall("fail") { throw CancellationException("test cancel") }
            } catch (e: CancellationException) {
                caughtCancel = true
                throw e
            }
        }
        job.join()
        assertTrue(caughtCancel)
    }
}
