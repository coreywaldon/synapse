package com.synapselib.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ReactiveFlowStressTest {

    private fun runStressTest(block: suspend CoroutineScope.() -> Unit) = runBlocking(Dispatchers.Default) {
        block()
    }

    @Test
    fun `stress test gate BUFFER preserves order and count under heavy load`() = runBlocking {
        // Use a standard runBlocking (main thread) to coordinate,
        // and launch workers on Dispatchers.Default explicitly.
        val gate = MutableStateFlow(true)
        val emissionCount = 100_000
        val results = ConcurrentLinkedQueue<Int>()

        // Launch the stress test in the Default dispatcher to ensure parallelism
        val job = launch(Dispatchers.Default) {
            val toggler = launch {
                while (isActive) {
                    gate.value = !gate.value
                    delay(1)
                }
            }

            val flow = flow {
                repeat(emissionCount) {
                    emit(it)
                    if (it % 100 == 0) yield()
                }
            }

            flow.asReactive()
                .gate(gate, GateStrategy.BUFFER)
                .take(emissionCount)
                .collect {
                    results.add(it) }

            toggler.cancel()
        }

        job.join()

        assertEquals(emissionCount, results.size)
        var previous = -1
        for (value in results) {
            assertEquals(previous + 1, value)
            previous = value
        }

        job.cancelAndJoin()
    }

    @Test
    fun `stress test squash handles concurrent emissions`() = runStressTest {
        val squashWindow = 5.seconds
        val jobs = 10
        val emissionsPerJob = 1000

        val flow = MutableSharedFlow<String>(extraBufferCapacity = jobs * emissionsPerJob)

        val results = mutableListOf<String>()

        val collector = launch {
            flow.asReactive()
                .squash(squashWindow)
                .collect { results.add(it) }
        }

        // Launch 10 concurrent emitters
        val emitters = (1..jobs).map {
            launch {
                repeat(emissionsPerJob) {
                    flow.emit("A")
                }
            }
        }

        emitters.joinAll()

        // Small buffer to allow the collector to drain the remaining items from the SharedFlow buffer
        delay(100)
        collector.cancel()

        println("Squash Stress: Reduced ${jobs * emissionsPerJob} emissions to ${results.size}")

        // Verification:
        assert(results.isNotEmpty())
        assertEquals(1, results.size)
        assertEquals("A", results[0])
    }

    @Test
    fun `stress test chunk thread safety`() = runStressTest {
        val emissionCount = 10_000
        val flow = flow {
            repeat(emissionCount) { emit(it) }
        }

        val results = Collections.synchronizedList(mutableListOf<List<Int>>())

        // Chunk rapidly. The operator must handle internal buffer locking correctly.
        flow.asReactive()
            .chunk(1.milliseconds) // Very fast chunks
            .collect { results.add(it) }

        // Flatten results
        val flattened = results.flatten()

        assertEquals(emissionCount, flattened.size)

        // Verify order
        for (i in 0 until emissionCount) {
            assertEquals(i, flattened[i])
        }
    }

    @Test
    fun `stress test broadcast with dynamic subscribers`() = runTest {
        val source = MutableSharedFlow<Int>()

        // 1. Manage the broadcast lifecycle within the TestScope
        val broadcastJob = Job(coroutineContext.job)
        val broadcastScope = CoroutineScope(coroutineContext + broadcastJob)
        val shared = source.asReactive().broadcast(broadcastScope, replays = 0)

        val totalReceived = AtomicInteger(0)
        val persistentResults = Collections.synchronizedList(mutableListOf<Int>())
        val warmUpLatch = CompletableDeferred<Unit>()

        // 2. Use backgroundScope so we don't have to manually cancel persistentJob later
        backgroundScope.launch {
            shared
                .buffer(Channel.UNLIMITED)
                .collect { value ->
                    if (value == -1) {
                        warmUpLatch.complete(Unit)
                    } else {
                        persistentResults.add(value)
                    }
                }
        }

        // 3. Warm-up Loop (Virtual time makes this instant)
        withTimeout(5000) {
            while (!warmUpLatch.isCompleted) {
                source.emit(-1)
                delay(10)
            }
        }

        val subscribers = (1..20).map {
            launch {
                delay((0..100).random().toLong())
                val job = backgroundScope.launch {
                    shared.collect { totalReceived.incrementAndGet() }
                }
                delay((50..200).random().toLong())
                job.cancel()
            }
        }

        launch {
            repeat(1000) {
                source.emit(it)
                delay(2) // Virtual time skips the Windows 15.6ms tick instantly
            }
        }

        // 4. Deterministic wait for completion
        withTimeout(10_000) {
            while (persistentResults.size < 1000) {
                delay(50)
            }
        }

        subscribers.joinAll()

        println("Broadcast Stress: Persistent subscriber saw ${persistentResults.size} items.")
        assertEquals(1000, persistentResults.size)

        // 5. Cleanup
        broadcastJob.cancel()
    }

    @Test
    fun `stress test pace maintains throughput under heavy load`() = runStressTest {
        // Scenario: Upstream emits 2,000 items as fast as possible.
        // Pace is set to 2ms.
        // We expect the total duration to be roughly 4,000ms (4s), not 0s.

        val emissionCount = 2000
        val interval = 2.milliseconds
        val flow = flow {
            repeat(emissionCount) { emit(it) }
        }

        val startTime = System.currentTimeMillis()
        val results = AtomicInteger(0)

        flow.asReactive()
            .pace(interval)
            .collect { results.incrementAndGet() }

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertEquals(emissionCount, results.get())

        // Expected minimum time: 2000 items * 2ms = 4000ms.
        // We allow a small margin for error, but it shouldn't be significantly faster.
        val minExpectedDuration = (emissionCount * interval.inWholeMilliseconds) * 0.8 // 20% buffer for jitter

        assertTrue(duration >= minExpectedDuration, "Pace was too fast! Took $duration ms, expected at least $minExpectedDuration ms")
    }

    @Test
    fun `stress test shutter high frequency trigger`() = runTest {
        val upstream = MutableSharedFlow<Int>()
        val trigger = MutableSharedFlow<Unit>()
        val results = ConcurrentLinkedQueue<Int>()

        // backgroundScope automatically cancels these when the test finishes
        backgroundScope.launch {
            upstream.asReactive()
                .shutter(trigger)
                .collect { results.add(it) }
        }

        backgroundScope.launch {
            var counter = 0
            while (isActive) {
                upstream.emit(counter++)
                delay(100) // Virtual time
            }
        }

        // Instead of measuring system time, we just iterate the exact number of times we want.
        val triggerJob = launch {
            repeat(2000) {
                trigger.emit(Unit)
                delay(1) // Virtual time
            }
        }

        triggerJob.join()

        // Because virtual time is perfect, we can safely assert a much tighter bound.
        // It should be exactly 2000, but we use > 1500 to leave a small margin for Coroutine scheduling.
        assertTrue(results.size > 1500, "Should have captured many samples, got ${results.size}")

        val list = results.toList()
        var previous = -1
        for (value in list) {
            assertTrue(value >= previous, "Shutter values should not regress")
            previous = value
        }
    }

    @Test
    fun `stress test combo complex sequence matching`() = runStressTest {
        // Scenario: Detect the sequence [1, 2, 3] amidst noise.
        // We use a cold flow to ensure deterministic emission order.

        val targetSequence = listOf(1, 2, 3)
        val sequences = listOf(targetSequence)
        val detected = AtomicInteger(0)
        val iterations = 1000

        val input = flow {
            repeat(iterations) {
                // Emit noise
                emit(9)
                emit(8)

                // Emit sequence
                emit(1)
                emit(2)
                emit(3)

                // Emit noise
                emit(7)
            }
        }

        input.asReactive()
            .combo(sequences, 500.milliseconds)
            .collect {
                if (it == targetSequence) {
                    detected.incrementAndGet()
                }
            }

        // We injected the sequence 1000 times.
        // Combo should detect it exactly 1000 times.
        assertEquals(iterations, detected.get())
    }

    @Test
    fun `stress test resilient recovery from massive failure`() = runStressTest {
        // Scenario: Upstream fails 2/3 of the time.
        // We want to ensure it recovers enough times to get exactly 100 successes.

        val successes = AtomicInteger(0)
        val attempts = AtomicInteger(0)

        // Infinite flow that fails intermittently
        val flow = flow {
            while(true) {
                val currentAttempt = attempts.incrementAndGet()
                if (currentAttempt % 3 != 0) {
                    throw RuntimeException("Simulated Failure")
                } else {
                    emit("Success")
                }
            }
        }

        val resilientFlow = flow
            .asReactive()
            .resilient(
                maxRetries = Int.MAX_VALUE,
                initialDelay = 1.milliseconds,
                factor = 1.0 // Keep delay constant to prevent timeout
            )

        resilientFlow
            .take(100)
            .collect {
                successes.incrementAndGet()
            }

        assertEquals(100, successes.get())

        // We expect roughly 300 attempts (100 successes + 200 failures).
        // We check >= 250 to be safe against minor scheduling variances.
        assertTrue(attempts.get() >= 250, "Should have retried failures. Attempts: ${attempts.get()}")
    }
}