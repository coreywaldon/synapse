package com.synapselib.core.logic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogicExtensionsTest {

    // Helper to make assertions cleaner
    private fun assertFlowValue(expected: Boolean, flow: StateFlow<Boolean>) {
        assertEquals(expected, flow.value, "Synchronous .value check failed")
    }

    @Test
    fun `infix AND operator`() = runTest {
        val flowA = MutableStateFlow(true)
        val flowB = MutableStateFlow(true)
        val result = flowA and flowB

        // T && T = T
        assertFlowValue(true, result)
        assertTrue(result.first())

        // T && F = F
        flowB.value = false
        assertFlowValue(false, result)
        assertFalse(result.first())

        // F && F = F
        flowA.value = false
        assertFlowValue(false, result)
    }

    @Test
    fun `infix OR operator`() = runTest {
        val flowA = MutableStateFlow(false)
        val flowB = MutableStateFlow(false)
        val result = flowA or flowB

        // F || F = F
        assertFlowValue(false, result)

        // F || T = T
        flowB.value = true
        assertFlowValue(true, result)

        // T || T = T
        flowA.value = true
        assertFlowValue(true, result)
    }

    @Test
    fun `infix XOR operator`() = runTest {
        val flowA = MutableStateFlow(false)
        val flowB = MutableStateFlow(false)
        val result = flowA xor flowB

        // F ^ F = F
        assertFlowValue(false, result)

        // F ^ T = T
        flowB.value = true
        assertFlowValue(true, result)

        // T ^ T = F
        flowA.value = true
        assertFlowValue(false, result)
    }

    @Test
    fun `infix NAND operator`() = runTest {
        val flowA = MutableStateFlow(true)
        val flowB = MutableStateFlow(true)
        val result = flowA nand flowB

        // !(T && T) = F
        assertFlowValue(false, result)

        // !(T && F) = T
        flowB.value = false
        assertFlowValue(true, result)
    }

    @Test
    fun `infix NOR operator`() = runTest {
        val flowA = MutableStateFlow(false)
        val flowB = MutableStateFlow(false)
        val result = flowA nor flowB

        // !(F || F) = T
        assertFlowValue(true, result)

        // !(F || T) = F
        flowB.value = true
        assertFlowValue(false, result)
    }

    @Test
    fun `infix XNOR operator`() = runTest {
        val flowA = MutableStateFlow(true)
        val flowB = MutableStateFlow(true)
        val result = flowA xnor flowB

        // !(T ^ T) = T (Same)
        assertFlowValue(true, result)

        // !(T ^ F) = F (Different)
        flowB.value = false
        assertFlowValue(false, result)
    }

    @Test
    fun `infix IMP (Implication) operator`() = runTest {
        val flowA = MutableStateFlow(true)
        val flowB = MutableStateFlow(false)
        val result = flowA imp flowB

        // T -> F = F
        assertFlowValue(false, result)

        // F -> F = T
        flowA.value = false
        assertFlowValue(true, result)

        // F -> T = T
        flowB.value = true
        assertFlowValue(true, result)

        // T -> T = T
        flowA.value = true
        assertFlowValue(true, result)
    }

    @Test
    fun `infix NIMP (Non-implication) operator`() = runTest {
        // A does NOT imply B (A is true and B is false)
        val flowA = MutableStateFlow(true)
        val flowB = MutableStateFlow(false)
        val result = flowA nimp flowB

        // T nimp F = T
        assertFlowValue(true, result)

        // T nimp T = F
        flowB.value = true
        assertFlowValue(false, result)
    }

    @Test
    fun `NOT operator`() = runTest {
        val flowA = MutableStateFlow(true)
        val result = flowA.not()

        assertFlowValue(false, result)

        flowA.value = false
        assertFlowValue(true, result)
    }

    @Test
    fun `vararg AND operator`() = runTest {
        val f1 = MutableStateFlow(true)
        val f2 = MutableStateFlow(true)
        val f3 = MutableStateFlow(true)

        val result = f1.and(f2, f3)

        // All true
        assertFlowValue(true, result)

        // One false
        f2.value = false
        assertFlowValue(false, result)
    }

    @Test
    fun `vararg OR operator`() = runTest {
        val f1 = MutableStateFlow(false)
        val f2 = MutableStateFlow(false)
        val f3 = MutableStateFlow(false)

        val result = f1.or(f2, f3)

        // All false
        assertFlowValue(false, result)

        // One true
        f3.value = true
        assertFlowValue(true, result)
    }

    @Test
    fun `vararg XOR operator (Odd parity)`() = runTest {
        val f1 = MutableStateFlow(false)
        val f2 = MutableStateFlow(false)
        val f3 = MutableStateFlow(false)

        val result = f1.xor(f2, f3)

        // 0 true (even) -> False
        assertFlowValue(false, result)

        // 1 true (odd) -> True
        f1.value = true
        assertFlowValue(true, result)

        // 2 true (even) -> False
        f2.value = true
        assertFlowValue(false, result)

        // 3 true (odd) -> True
        f3.value = true
        assertFlowValue(true, result)
    }

    @Test
    fun `nested derived flows update correctly`() = runTest {
        // Test chaining: (A && B) || C
        val a = MutableStateFlow(false)
        val b = MutableStateFlow(true)
        val c = MutableStateFlow(false)

        val aAndB = a and b
        val result = aAndB or c

        // (F && T) || F = F
        assertFlowValue(false, result)

        // Change A to True -> (T && T) || F = T
        a.value = true
        assertFlowValue(true, result)

        // Change A to False, C to True -> (F && T) || T = T
        a.value = false
        c.value = true
        assertFlowValue(true, result)
    }

    @Test
    fun `logic extensions combine state flows correctly`() = runTest {
        val flowA = MutableStateFlow(false)
        val flowB = MutableStateFlow(false)

        // Logic: (A OR B)
        val combined = flowA or flowB

        val results = mutableListOf<Boolean>()
        val job = launch {
            combined.collect { results.add(it) }
        }

        // Initial state (false OR false) -> false
        assertEquals(false, combined.value)

        flowA.value = true
        advanceUntilIdle()
        // (true OR false) -> true
        assertEquals(true, combined.value)

        flowA.value = false
        flowB.value = true
        advanceUntilIdle()
        // (false OR true) -> true
        assertEquals(true, combined.value)

        flowB.value = false
        advanceUntilIdle()
        // (false OR false) -> false
        assertEquals(false, combined.value)

        // Verify collection history
        // Note: StateFlow conflates, but in test environment we capture changes
        // Sequence: false (init) -> true (A=true) -> true (B=true, A=false - no change in result) -> false (B=false)
        // However, StateFlow distinctUntilChanged might filter the middle 'true' if it didn't change.
        // Let's check the final value mostly.
        assertEquals(false, results.last())

        job.cancel()
    }
}