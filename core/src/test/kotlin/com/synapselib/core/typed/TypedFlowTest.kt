package com.synapselib.core.typed

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypedFlowTest {

    // ── Happy path ──────────────────────────────────────────────────────

    @Test
    fun `TypedStateFlow narrows matching values`() = runTest {
        val source = MutableStateFlow<Any>(42)
        val typed = TypedStateFlow(source, Int::class)

        assertEquals(42, typed.value)
        assertEquals(listOf(42), typed.replayCache)
        assertEquals(42, typed.first())
    }

    @Test
    fun `TypedSharedFlow narrows replayed values`() = runTest {
        val source = MutableSharedFlow<Any>(replay = 1).apply { emit("hello") }
        val typed = TypedSharedFlow(source, String::class)

        assertEquals(listOf("hello"), typed.replayCache)
        assertEquals("hello", typed.first())
    }

    @Test
    fun `TypedDataStateFlow casts inner data and passes data-less states through`() = runTest {
        val source = MutableStateFlow<DataState<*>>(DataState.Loading)
        val typed = TypedDataStateFlow(source, String::class)

        assertEquals(DataState.Loading, typed.value)
        source.value = DataState.Success("ok")
        assertEquals(DataState.Success("ok"), typed.value)
        val cause = RuntimeException("x")
        source.value = DataState.Error(cause, staleData = "stale")
        assertEquals(DataState.Error(cause, "stale"), typed.value)
    }

    // ── Error messages ──────────────────────────────────────────────────

    @Test
    fun `TypedStateFlow mismatch names the adapter and both types`() {
        val typed = TypedStateFlow(MutableStateFlow<Any>("oops"), Int::class)

        val error = assertThrows(ClassCastException::class.java) { typed.value }

        val message = error.message.orEmpty()
        assertTrue(message.startsWith("TypedStateFlow element:"), message)
        assertTrue("kotlin.Int" in message, message)
        assertTrue("kotlin.String" in message, message)
    }

    @Test
    fun `TypedSharedFlow mismatch names the adapter`() = runTest {
        val source = MutableSharedFlow<Any>(replay = 1).apply { emit(1L) }
        val typed = TypedSharedFlow(source, String::class)

        val error = assertThrows(ClassCastException::class.java) { typed.replayCache }

        val message = error.message.orEmpty()
        assertTrue(message.startsWith("TypedSharedFlow element:"), message)
        assertTrue("kotlin.Long" in message && "kotlin.String" in message, message)
    }

    @Test
    fun `TypedDataStateFlow mismatch names the DataState slot`() {
        val source = MutableStateFlow<DataState<*>>(DataState.Error(RuntimeException(), staleData = 3))
        val typed = TypedDataStateFlow(source, String::class)

        val error = assertThrows(ClassCastException::class.java) { typed.value }

        assertTrue(error.message.orEmpty().startsWith("TypedDataStateFlow Error.staleData:"), error.message)
    }

    @Test
    fun `TypedDataSharedFlow mismatch names the DataState slot`() = runTest {
        val source = MutableSharedFlow<DataState<*>>(replay = 1).apply { emit(DataState.Success(3)) }
        val typed = TypedDataSharedFlow(source, String::class)

        val error = assertThrows(ClassCastException::class.java) { typed.replayCache }

        assertTrue(error.message.orEmpty().startsWith("TypedDataSharedFlow Success.data:"), error.message)
    }
}
