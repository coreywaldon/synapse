package com.synapse.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveFlowTest {

    @Test
    fun `gate BUFFER strategy preserves all data`() = runTest {
        val gate = MutableStateFlow(false)
        val results = mutableListOf<Int>()

        val flow = flow {
            emit(1)
            emit(2)
            delay(100)
            gate.value = true
        }

        val job = launch {
            flow.asReactive()
                .gate(gate, GateStrategy.BUFFER)
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(1, 2), results)
        job.cancel()
    }

    @Test
    fun `gate LATEST handles empty buffer when opening`() = runTest {
        // Edge case: Gate opens, but flow hasn't emitted anything yet.
        // Should not crash or emit null.
        val gate = MutableStateFlow(false)
        val results = mutableListOf<Int>()

        val flow = flow {
            delay(100)
            emit(1)
        }

        val job = launch {
            flow.asReactive()
                .gate(gate, GateStrategy.LATEST)
                .collect { results.add(it) }
        }

        // Open gate immediately (before data)
        gate.value = true
        advanceTimeBy(50)

        // Nothing should happen yet
        assertEquals(emptyList<Int>(), results)

        // Data arrives
        advanceUntilIdle()
        assertEquals(listOf(1), results)

        job.cancel()
    }

    @Test
    fun `gate LATEST strategy keeps only the last value`() = runTest {
        val gate = MutableStateFlow(false)
        val results = mutableListOf<Int>()

        val flow = flow {
            emit(1) // Overwritten
            emit(2) // Overwritten
            emit(3) // Kept
            delay(100)
            gate.value = true // Should emit 3
            delay(10)
            emit(4) // Should emit 4
        }

        val job = launch {
            flow.asReactive()
                .gate(gate, GateStrategy.LATEST)
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(3, 4), results)
        job.cancel()
    }

    @Test
    fun `gate DROP strategy ignores closed data`() = runTest {
        val gate = MutableStateFlow(false)
        val results = mutableListOf<Int>()

        val flow = flow {
            emit(1) // Dropped
            emit(2) // Dropped
            delay(100)
            gate.value = true
            delay(10)
            emit(3) // Emitted
        }

        val job = launch {
            flow.asReactive()
                .gate(gate, GateStrategy.DROP)
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(3), results)
        job.cancel()
    }

    // Helper to bridge TestScope time to the operators
    private fun TestScope.nanoTimeSource(): () -> Long = {
        this.testScheduler.currentTime * 1_000_000
    }

    @Test
    fun `pace delays signals instead of dropping them`() = runTest {
        val results = mutableListOf<Int>()
        val emissionTimes = mutableListOf<Long>()

        // Simulate a burst: 3 items emitted instantly (0ms)
        val flow = flow {
            emit(1)
            emit(2)
            emit(3)
        }

        val job = launch {
            flow.asReactive()
                .pace(100.milliseconds, nanoTimeSource())
                .collect {
                    results.add(it)
                    emissionTimes.add(testScheduler.currentTime)
                }
        }

        advanceUntilIdle()

        // 1. Verify NO data was lost
        assertEquals(listOf(1, 2, 3), results)

        // 2. Verify timing
        // Item 1: 0ms (Immediate)
        // Item 2: 100ms (Delayed by 100ms)
        // Item 3: 200ms (Delayed by another 100ms)
        assertEquals(listOf(0L, 100L, 200L), emissionTimes)

        job.cancel()
    }

    @Test
    fun `chunk does not emit empty lists during idle periods`() = runTest {
        val results = mutableListOf<List<Int>>()

        val flow = flow {
            emit(1)
            // Wait for 3 chunk windows (300ms) without emitting anything
            delay(300)
            emit(2)
        }

        val job = launch {
            flow.asReactive()
                .chunk(100.milliseconds)
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        // We expect:
        // 1. Chunk [1] (triggered by first 100ms)
        // 2. Silence (100ms-300ms) -> NO empty lists
        // 3. Chunk [2] (triggered after 300ms)
        assertEquals(2, results.size)
        assertEquals(listOf(1), results[0])
        assertEquals(listOf(2), results[1])

        job.cancel()
    }

    @Test
    fun `chunk groups emissions into time windows`() = runTest {
        val results = mutableListOf<List<Int>>()

        val flow = flow {
            // Batch 1: Emitted immediately
            emit(1)
            emit(2)
            emit(3)

            // Wait longer than the chunk window (100ms)
            delay(150)

            // Batch 2: Emitted after delay
            emit(4)
            emit(5)

            // Wait again to allow the second batch to flush
            delay(150)
        }

        val job = launch {
            flow.asReactive()
                // Note: We don't inject timeSource here because chunk relies on 'delay()',
                // which is automatically controlled by runTest's virtual time.
                .chunk(100.milliseconds)
                .collect { results.add(it) }
        }

        // Advance time to process the first batch (0-100ms)
        advanceTimeBy(101)
        // Advance time to process the second batch (150-250ms)
        advanceUntilIdle()

        // Verification:
        // Batch 1 should contain [1, 2, 3]
        // Batch 2 should contain [4, 5]
        assertEquals(2, results.size)
        assertEquals(listOf(1, 2, 3), results[0])
        assertEquals(listOf(4, 5), results[1])

        job.cancel()
    }

    @Test
    fun `chunk flushes remaining items and exits cleanly on upstream completion`() = runTest {
        val results = mutableListOf<List<Int>>()

        val flow = flow {
            emit(1)
            emit(2)
            emit(3)
            // Upstream finishes immediately after emitting.
            // This forces the operator to flush the buffer via the cleanup logic
            // rather than waiting for the next 'tick'.
        }

        val job = launch {
            flow.asReactive()
                // Set a very long period so the ticker NEVER fires naturally.
                // This ensures we are testing the completion/cleanup logic.
                .chunk(10.seconds)
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        // Verification:
        // 1. If the exception wasn't handled, the test would crash or the job would fail.
        // 2. If the flush logic was missing, results would be empty.
        assertEquals(1, results.size)
        assertEquals(listOf(1, 2, 3), results[0])

        // Ensure the job completed successfully (no lingering coroutines or exceptions)
        assert(job.isCompleted)
    }

    @Test
    fun `gate propagates upstream error immediately even when closed`() = runTest {
        val gate = MutableStateFlow(false) // Closed
        val errorMsg = "Critical Failure"

        val flow = flow {
            emit(1)
            delay(10)
            throw RuntimeException(errorMsg)
        }

        val job = launch {
            try {
                flow.asReactive()
                    .gate(gate, GateStrategy.BUFFER)
                    .collect {}
                throw IllegalStateException("Should have failed")
            } catch (e: RuntimeException) {
                assertEquals(errorMsg, e.message)
            }
        }

        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun `gate BUFFER strategy handles toggling (Open to Closed to Open)`() = runTest {
        val gate = MutableStateFlow(true) // Start OPEN
        val results = mutableListOf<Int>()

        val flow = flow {
            emit(1) // Should pass immediately
            delay(50)
            gate.value = false // Close gate
            emit(2) // Should buffer
            emit(3) // Should buffer
            delay(50)
            gate.value = true // Open gate
        }

        val job = launch {
            flow.asReactive()
                .gate(gate, GateStrategy.BUFFER)
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        // 1 passes immediately. 2 and 3 are buffered and released when gate re-opens.
        assertEquals(listOf(1, 2, 3), results)
        job.cancel()
    }

    @Test
    fun `gate LATEST emits final value if upstream completes while closed`() = runTest {
        val gate = MutableStateFlow(false)
        val results = mutableListOf<Int>()

        val flow = flow {
            emit(1)
            emit(2)
            // Flow completes here, but gate is still closed!
        }

        val job = launch {
            flow.asReactive()
                .gate(gate, GateStrategy.LATEST)
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        // Gate is still closed, nothing emitted yet
        assertEquals(emptyList<Int>(), results)

        // Open the gate AFTER upstream finished
        gate.value = true
        advanceUntilIdle()

        // Should receive the last value held in the buffer
        assertEquals(listOf(2), results)
        job.cancel()
    }

    @Test
    fun `squash allows oscillation (A-B-A) within time window with maxDistanceValues = 1`() = runTest {
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(10)
            emit("B") // Different value, should emit immediately even if within window
            delay(10)
            emit("A") // Different from previous ("B"), should emit immediately
        }

        val job = launch {
            flow.asReactive()
                .squash(
                    500.milliseconds,
                    maxDistinctValues = 1,
                    timeSource = nanoTimeSource()
                )
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        assertEquals(listOf("A", "B", "A"), results)
        job.cancel()
    }

    @Test
    fun `squash drops duplicates within time window`() = runTest {
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(100)
            emit("A") // 100ms elapsed. Should squash (window 500ms)
            delay(100)
            emit("B") // 200ms elapsed. Should emit (diff value)
            delay(100)
            emit("B") // 300ms elapsed. Should squash
        }

        val job = launch {
            flow.asReactive()
                .squash(500.milliseconds, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        assertEquals(listOf("A", "B"), results)
        job.cancel()
    }

    @Test
    fun `squash drops non-consecutive duplicates within time window`() = runTest {
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(100)
            emit("B") // 100ms. New value. Emit.
            delay(100)
            emit("A") // 200ms. "A" was last seen at 0ms. 200ms < 500ms. Squash.
            delay(100)
            emit("B") // 300ms. "B" was last seen at 100ms. 200ms diff < 500ms. Squash.
        }

        val job = launch {
            flow.asReactive()
                .squash(500.milliseconds, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        assertEquals(listOf("A", "B"), results)
        job.cancel()
    }

    @Test
    fun `squash allows duplicates after time window expires`() = runTest {
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(600) // 600ms elapsed
            emit("A") // Should emit (600ms > 500ms window)
        }

        val job = launch {
            flow.asReactive()
                .squash(500.milliseconds, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        assertEquals(listOf("A", "A"), results)
        job.cancel()
    }

    @Test
    fun `squash evicts oldest value when maxDistinctValues is reached`() = runTest {
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A") // Tracked. Queue: [A]
            delay(10)
            emit("B") // Tracked. Queue: [A, B]
            delay(10)
            emit("C") // Max is 2. Evict A. Track C. Queue: [B, C]
            delay(10)
            emit("A") // A was evicted. Emit A. Queue full -> Evict B. Queue: [C, A]
            delay(10)
            emit("B") // B was evicted. Emit B. Queue full -> Evict C. Queue: [A, B]
        }

        val job = launch {
            flow.asReactive()
                .squash(
                    retentionPeriod = 500.milliseconds,
                    maxDistinctValues = 2,
                    timeSource = nanoTimeSource()
                )
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        // Correct behavior: A, B, C, A, B
        assertEquals(listOf("A", "B", "C", "A", "B"), results)
        job.cancel()
    }

    @Test
    fun `squash respects shouldSquash predicate`() = runTest {
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(10)
            emit("A") // Should squash (predicate true)
            delay(10)
            emit("B")
            delay(10)
            emit("B") // Should NOT squash (predicate false)
        }

        val job = launch {
            flow.asReactive()
                .squash(
                    retentionPeriod = 500.milliseconds,
                    shouldSquash = { it == "A" },
                    timeSource = nanoTimeSource()
                )
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        assertEquals(listOf("A", "B", "B"), results)
        job.cancel()
    }

    @Test
    fun `pace emits pending delayed item before completing`() = runTest {
        val results = mutableListOf<Int>()

        val flow = flow {
            emit(1)
            // Complete immediately after emitting.
            // The 'pace' operator will hold '1' for 100ms.
        }

        val job = launch {
            flow.asReactive()
                .pace(100.milliseconds, nanoTimeSource())
                .collect { results.add(it) }
        }

        // Advance past the pace duration
        advanceTimeBy(150)

        // Verify we got the item and the job finished naturally
        assertEquals(listOf(1), results)
        assert(job.isCompleted)
    }

    @Test
    fun `pace does not delay items arriving slower than pace duration`() = runTest {
        val results = mutableListOf<Int>()
        val emissionTimes = mutableListOf<Long>()

        val flow = flow {
            emit(1)
            delay(200) // Wait 200ms (slower than pace of 100ms)
            emit(2)
        }

        val job = launch {
            flow.asReactive()
                .pace(100.milliseconds, nanoTimeSource())
                .collect {
                    results.add(it)
                    emissionTimes.add(testScheduler.currentTime)
                }
        }

        advanceUntilIdle()

        assertEquals(listOf(1, 2), results)
        // Item 1: 0ms
        // Item 2: 200ms (Should NOT be delayed to 300ms or 200ms + pace)
        assertEquals(listOf(0L, 200L), emissionTimes)

        job.cancel()
    }

    @Test
    fun `chunk drops partial buffer on upstream error`() = runTest {
        val results = mutableListOf<List<Int>>()
        val errorMsg = "Boom"

        val flow = flow {
            emit(1)
            emit(2)
            delay(10)
            throw RuntimeException(errorMsg)
        }

        val job = launch {
            try {
                flow.asReactive()
                    .chunk(10.seconds)
                    .collect { results.add(it) }
            } catch (e: RuntimeException) {
                // Verify it's the correct error
                if (e.message != errorMsg) throw e
            }
        }

        advanceUntilIdle()

        // WAI: The upstream error cancels the flow immediately.
        // The buffer [1, 2] is dropped, not flushed.
        assertEquals(0, results.size)

        job.cancel()
    }

    @Test
    fun `shutter captures value when trigger fires after upstream emission`() = runTest {
        // Use MutableSharedFlow to control exact timing of the trigger
        val trigger = MutableSharedFlow<Unit>()
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(1000) // Keep upstream alive
        }

        val job = launch {
            flow.asReactive()
                .shutter(trigger)
                .collect { results.add(it) }
        }

        // 1. Allow upstream to emit "A" and shutter to cache it
        advanceTimeBy(100)

        // 2. Fire trigger
        trigger.emit(Unit)

        advanceUntilIdle()

        assertEquals(listOf("A"), results)
        job.cancel()
    }

    @Test
    fun `shutter repeats value if triggered multiple times`() = runTest {
        val trigger = MutableSharedFlow<Unit>()
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(1000) // Stay active
        }

        val job = launch {
            flow.asReactive()
                .shutter(trigger)
                .collect { results.add(it) }
        }

        advanceTimeBy(10) // Ensure "A" is ready

        // Trigger once
        trigger.emit(Unit)
        // Trigger again (same upstream value)
        trigger.emit(Unit)

        advanceUntilIdle()

        // Depending on implementation, this might be ["A"] or ["A", "A"].
        // Most "sample" operators emit every time the sampler fires.
        // If the requirement is distinctUntilChanged, this test will fail and alert you to the behavior.
        // Assuming standard sampling:
        assertEquals(listOf("A", "A"), results)

        job.cancel()
    }

    @Test
    fun `shutter captures latest value on trigger`() = runTest {
        val trigger = MutableSharedFlow<Unit>()
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(10)
            emit("B") // Latest is now B
            delay(50)
            // Trigger happens here (approx 60ms)
            delay(50)
            emit("C") // Latest is now C
            // Trigger happens here
        }

        val job = launch {
            flow.asReactive()
                .shutter(trigger)
                .collect { results.add(it) }
        }

        // 1. Let flow emit A and B
        advanceTimeBy(60)
        trigger.emit(Unit) // Should capture "B"

        // 2. Let flow emit C
        advanceTimeBy(60)
        trigger.emit(Unit) // Should capture "C"

        advanceUntilIdle()

        assertEquals(listOf("B", "C"), results)
        job.cancel()
    }

    @Test
    fun `shutter ignores trigger if no value upstream yet`() = runTest {
        val trigger = MutableSharedFlow<Unit>()
        val results = mutableListOf<String>()

        val flow = flow {
            delay(100)
            emit("A")
        }

        val job = launch {
            flow.asReactive()
                .shutter(trigger)
                .collect { results.add(it) }
        }

        // Trigger before data
        trigger.emit(Unit)

        advanceTimeBy(150)
        // Data "A" has arrived, but trigger hasn't fired again

        trigger.emit(Unit) // Now should capture "A"

        advanceUntilIdle()

        assertEquals(listOf("A"), results)
        job.cancel()
    }

    @Test
    fun `combo detects simple sequence`() = runTest {
        val results = mutableListOf<List<Int>>()
        val sequences = listOf(listOf(1, 2))

        val flow = flow {
            emit(1)
            delay(50)
            emit(2)
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = false, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(listOf(1, 2)), results)
        job.cancel()
    }

    @Test
    fun `combo resets buffer on timeout`() = runTest {
        val results = mutableListOf<List<Int>>()
        val sequences = listOf(listOf(1, 2))

        val flow = flow {
            emit(1)
            delay(200) // Exceeds 100ms timeout
            emit(2)
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = false, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        // 1 -> Timeout -> Reset. 2 -> Start new (incomplete). No emission.
        assertEquals(emptyList<List<Int>>(), results)
        job.cancel()
    }

    @Test
    fun `combo greedy waits for longer match`() = runTest {
        val results = mutableListOf<List<Int>>()
        // Detect [1, 2] OR [1, 2, 3]
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3))

        val flow = flow {
            emit(1)
            emit(2) // Matches [1, 2], but [1, 2, 3] is possible. Wait.
            delay(50)
            emit(3) // Completes [1, 2, 3]
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = true, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        // Should skip the shorter match and emit the longer one
        assertEquals(listOf(listOf(1, 2, 3)), results)
        job.cancel()
    }

    @Test
    fun `combo greedy emits partial match on timeout`() = runTest {
        val results = mutableListOf<List<Int>>()
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3))

        val flow = flow {
            emit(1)
            emit(2)
            // Wait longer than timeout.
            // The pending match [1, 2] should fire because [3] never came.
            delay(200)
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = true, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(listOf(1, 2)), results)
        job.cancel()
    }

    @Test
    fun `combo greedy emits partial match on mismatch`() = runTest {
        val results = mutableListOf<List<Int>>()
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3))

        val flow = flow {
            emit(1)
            emit(2)
            delay(10)
            emit(4) // '4' breaks the [1, 2, 3] chain
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = true, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        // Should emit [1, 2] immediately when 4 arrives, as 4 cannot extend the chain
        assertEquals(listOf(listOf(1, 2)), results)
        job.cancel()
    }

    @Test
    fun `combo non-greedy fires immediately on shortest match`() = runTest {
        val results = mutableListOf<List<Int>>()
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3))

        val flow = flow {
            emit(1)
            emit(2) // Should fire [1, 2] immediately
            delay(10)
            emit(3) // Should be treated as a new start (or ignored if not a start)
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = false, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        // [1, 2] fires. State resets. '3' is not a start of any sequence.
        assertEquals(listOf(listOf(1, 2)), results)
        job.cancel()
    }

    @Test
    fun `combo handles overlapping sequences correctly`() = runTest {
        val results = mutableListOf<List<String>>()
        // Detect "A, A"
        val sequences = listOf(listOf("A", "A"))

        val flow = flow {
            emit("A")
            emit("A") // Match 1
            emit("A") // Start of potential Match 2
            emit("A") // Match 2
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = false, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(listOf("A", "A"), listOf("A", "A")), results)
        job.cancel()
    }

    @Test
    fun `combo handles failures sequences correctly`() = runTest {
        val results = mutableListOf<List<String>>()
        // Detect "B, C, D, E"
        val sequences = listOf(
            listOf("A", "B", "C", "E"),
            listOf("B", "C", "D", "E")
        )

        val flow = flow {
            emit("A") // Start of potential match
            emit("B") // Start of second potential match
            emit("C")
            emit("D") // Revert to failure case "B, C, D"
            emit("E") // Match
        }

        val job = launch {
            flow.asReactive()
                .combo(sequences, 100.milliseconds, greedy = false, timeSource = nanoTimeSource())
                .collect { results.add(it) }
        }

        advanceUntilIdle()
        assertEquals(listOf(listOf("B", "C", "D", "E")), results)
        job.cancel()
    }

    @Test
    fun `resilient does not retry on CancellationException`() = runTest {
        var attempts = 0
        val flow = flow<Unit> {
            attempts++
            // Simulate a coroutine cancellation
            throw kotlinx.coroutines.CancellationException("Cancelled")
        }

        val job = launch {
            try {
                flow.asReactive()
                    .resilient(maxRetries = 5)
                    .collect {}
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected
            }
        }

        advanceUntilIdle()

        // Should only run once. If it retried, attempts would be > 1
        assertEquals(1, attempts)
        job.cancel()
    }

    @Test
    fun `resilient retries with exponential backoff on failure`() = runTest {
        var attempts = 0
        val results = mutableListOf<Int>()

        val flow = flow {
            attempts++
            if (attempts <= 2) {
                throw RuntimeException("Fail attempt $attempts")
            }
            emit(1)
        }

        val job = launch {
            flow.asReactive()
                .resilient(maxRetries = 3, initialDelay = 100.milliseconds, factor = 2.0)
                .collect { results.add(it) }
        }

        // Run only current tasks, do not advance virtual time automatically
        runCurrent()

        // Initial attempt (fails immediately) -> attempts=1
        assertEquals(1, attempts)

        // Retry 1: Delay 100ms * 2^0 = 100ms
        // Advance time just enough to trigger the first retry
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, attempts) // Fails again

        // Retry 2: Delay 100ms * 2^1 = 200ms
        advanceTimeBy(200)
        runCurrent()
        assertEquals(3, attempts) // Succeeds

        assertEquals(listOf(1), results)
        job.cancel()
    }

    @Test
    fun `resilient fails after max retries exceeded`() = runTest {
        var attempts = 0
        val flow = flow<Int> {
            attempts++
            throw RuntimeException("Always fail")
        }

        var caughtException: Throwable? = null

        val job = launch {
            try {
                flow.asReactive()
                    .resilient(maxRetries = 2, initialDelay = 100.milliseconds)
                    .collect {}
            } catch (e: Throwable) {
                caughtException = e
            }
        }

        // Attempt 1 (Fail) -> Wait 100ms
        advanceTimeBy(100)
        // Attempt 2 (Fail) -> Wait 200ms
        advanceTimeBy(200)
        // Attempt 3 (Fail) -> Max retries (2) exceeded. Should throw.
        advanceUntilIdle()

        // Total attempts: 1 initial + 2 retries = 3
        assertEquals(3, attempts)
        assertEquals("Always fail", caughtException?.message)
        job.cancel()
    }

    @Test
    fun `broadcast stops emission on upstream error`() = runTest {
        val flow = flow<Int> {
            emit(1)
            delay(100) // Give plenty of time for the collector to receive '1'
            throw RuntimeException("Shared Error")
        }

        // Set replays=1 to ensure the value '1' is cached and available immediately
        val sharedFlow = flow.asReactive().broadcast(backgroundScope, replays = 1)
        val results = mutableListOf<Int>()

        val job = launch {
            sharedFlow.collect { results.add(it) }
        }

        // Advance time just enough to process the emission (1), but BEFORE the error (100ms)
        advanceTimeBy(50)

        // We should have received the value by now
        assertEquals(listOf(1), results)

        // Now let the error happen
        advanceUntilIdle()

        // The list should remain [1] (no new data, and previous data preserved)
        assertEquals(listOf(1), results)

        job.cancel()
    }

    @Test
    fun `broadcast restarts upstream after keepAlive expiration`() = runTest {
        var upstreamStarts = 0
        val upstream = flow {
            upstreamStarts++
            emit(upstreamStarts)
            awaitCancellation() // Keep alive until canceled
        }

        // Keep alive for only 100ms
        val sharedFlow = upstream.asReactive()
            .broadcast(backgroundScope, replays = 1, keepAlive = 100.milliseconds)

        // 1. First subscriber
        val job1 = launch { sharedFlow.collect() }
        runCurrent()
        assertEquals(1, upstreamStarts) // Upstream started

        // 2. Unsubscribe
        job1.cancel()

        // 3. Wait BEYOND keepAlive (100ms)
        advanceTimeBy(200)

        // 4. Second subscriber
        val job2 = launch { sharedFlow.collect() }
        runCurrent()

        // 5. Should have restarted upstream because cache expired
        assertEquals(2, upstreamStarts)

        job2.cancel()
    }

    @Test
    fun `broadcast replays fixed number of latest values to new collectors`() = runTest {
        // 1. Use a MutableSharedFlow as the upstream.
        // This is a "hot" source. It only emits when we tell it to.
        // It does NOT replay by default, so we can be sure any values we get
        // later are coming from the 'broadcast' cache, not the upstream re-emitting.
        val upstream = MutableSharedFlow<Int>()

        // 2. Create the broadcast flow
        val sharedFlow = upstream.broadcast(backgroundScope, replays = 2, keepAlive = 5.seconds)

        // 3. Start the first collector.
        // This is necessary to trigger 'shareIn' to subscribe to our upstream.
        val job1 = launch {
            sharedFlow.collect {}
        }
        runCurrent() // Ensure the subscription is established

        // 4. Emit values to the upstream
        launch {
            upstream.emit(1)
            upstream.emit(2)
            upstream.emit(3)
            upstream.emit(4)
            upstream.emit(5)
        }
        runCurrent() // Let the values flow through to the sharedFlow cache

        // 5. Cancel the first collector.
        // Subscriber count drops to 0.
        // The 'keepAlive' timer starts now.
        job1.cancel()

        // 6. New collector joins immediately (well within 5 seconds)
        val results = mutableListOf<Int>()
        val job2 = launch {
            sharedFlow.collect { results.add(it) }
        }

        runCurrent()

        // Check results:
        // If keepAlive worked: sharedFlow stayed active, preserved [4, 5] in cache.
        // If keepAlive failed: sharedFlow restarted. It resubscribed to 'upstream'.
        //    'upstream' has no new emissions, so results would be empty [].
        assertEquals(listOf(4, 5), results)

        job2.cancel()
    }

    @Test
    fun `broadcast shares single upstream execution among collectors`() = runTest {
        var upstreamExecutions = 0
        val flow = flow {
            upstreamExecutions++
            emit(1)
            delay(1000) // Keep flow open
            emit(2)
        }

        // Create the broadcast flow attached to the test scope
        // replay=1 ensures the first emission is cached for the second collector
        val sharedFlow = flow.asReactive().broadcast(backgroundScope, replay = 1)

        val results1 = mutableListOf<Int>()
        val results2 = mutableListOf<Int>()

        // Start first collector
        val job1 = launch { sharedFlow.collect { results1.add(it) } }

        // Let upstream start and emit 1
        advanceTimeBy(100)

        // Start second collector
        val job2 = launch { sharedFlow.collect { results2.add(it) } }

        // Let second collector receive the replay
        runCurrent()

        // Both should receive the first emission (1)
        assertEquals(listOf(1), results1)
        assertEquals(listOf(1), results2)

        // Upstream should only have run once
        assertEquals(1, upstreamExecutions)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `spy logs lifecycle events correctly`() = runTest {
        val logs = mutableListOf<String>()
        val flow = flow {
            emit("Data")
            throw RuntimeException("Error")
        }

        try {
            flow.asReactive()
                .spy("TEST") { logs.add(it) }
                .collect {}
        } catch (_: Exception) {
            // Ignore expected error
        }

        assertEquals(3, logs.size)
        assertEquals("[TEST] Started", logs[0])
        assertEquals("[TEST] Emitted: Data", logs[1])
        // The error message format depends on the exception toString()
        assert(logs[2].startsWith("[TEST] Failed:"))
    }

    @Test
    fun `spy logs completion event`() = runTest {
        val logs = mutableListOf<String>()
        val flow = flowOf("Data") // Emits and completes naturally

        flow.asReactive()
            .spy("TEST") { logs.add(it) }
            .collect {}

        assertEquals(3, logs.size)
        assertEquals("[TEST] Started", logs[0])
        assertEquals("[TEST] Emitted: Data", logs[1])
        assertEquals("[TEST] Completed", logs[2])
    }

    @Test
    fun `spy is transparent to data`() = runTest {
        val results = mutableListOf<Int>()
        val flow = flowOf(1, 2, 3)

        flow.asReactive()
            .spy("TEST") { } // No-op logger
            .collect { results.add(it) }

        assertEquals(listOf(1, 2, 3), results)
    }

    @Test
    fun `complex integration test`() = runTest {
        // Scenario:
        // 1. Gate is closed.
        // 2. Emit "A", "A" (fast), "B".
        // 3. Gate opens.
        // 4. Expect: "A" (squashed), "B" (buffered).

        val gate = MutableStateFlow(false)
        val results = mutableListOf<String>()

        val flow = flow {
            emit("A")
            delay(50)
            emit("A") // Should be squashed by .squash() before hitting gate buffer
            delay(50)
            emit("B")
            delay(100)
            gate.value = true
        }

        val job = launch {
            flow.asReactive()
                .squash(200.milliseconds) // Squash first
                .gate(gate)               // Then buffer
                .collect { results.add(it) }
        }

        advanceUntilIdle()

        assertEquals(listOf("A", "B"), results)
        job.cancel()
    }
}