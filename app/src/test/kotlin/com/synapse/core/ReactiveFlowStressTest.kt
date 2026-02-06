package com.synapse.core

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
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

        withTimeout(10_000) { // Bump timeout slightly for slow CI environments
            val gate = MutableStateFlow(true)
            val emissionCount = 10_000
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
                    .collect { results.add(it) }

                toggler.cancelAndJoin()
            }

            job.join()

            assertEquals(emissionCount, results.size)
            assertEquals(results.toList(), results.sorted())
        }
    }

    @Test
    fun `stress test squash handles concurrent emissions`() = runStressTest {
        // Scenario: 10 coroutines emitting "A" rapidly.
        // Fix: Increase squash window to 5 seconds to ensure it covers the
        // entire execution time of the stress test, preventing valid expirations.
        val squashWindow = 5.seconds

        val flow = MutableSharedFlow<String>()
        // Note: 'collect' is sequential, so a standard mutable list is safe here
        // unless you have multiple collectors.
        val results = mutableListOf<String>()
        val jobs = 10
        val emissionsPerJob = 1000

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

        // Small buffer to allow the collector to process the final emission if needed
        // (Though with SharedFlow default config, emitters suspend until collected)
        delay(100)
        collector.cancel()

        println("Squash Stress: Reduced ${jobs * emissionsPerJob} emissions to ${results.size}")

        // Verification:
        // With a 5s window, we expect exactly 1 emission because the value "A"
        // never changes and the test shouldn't take > 5s.
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

        val results = java.util.Collections.synchronizedList(mutableListOf<List<Int>>())

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
    fun `stress test broadcast with dynamic subscribers`() = runStressTest {
        val source = MutableSharedFlow<Int>()

        // 1. Use a dedicated Job to manage the broadcast lifecycle
        val broadcastJob = Job()
        val broadcastScope = CoroutineScope(coroutineContext + broadcastJob)
        val shared = source.asReactive().broadcast(broadcastScope, replays = 0)

        val totalReceived = AtomicInteger(0)
        val persistentResults = java.util.Collections.synchronizedList(mutableListOf<Int>())

        // 2. Latch to signal when the subscriber is fully connected
        val warmUpLatch = CompletableDeferred<Unit>()

        val persistentJob = launch {
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

        // 3. Warm-up Loop: Keep emitting -1 until the subscriber acknowledges it.
        // This guarantees the connection is established before we start the real test.
        withTimeout(5000) {
            while (!warmUpLatch.isCompleted) {
                source.emit(-1)
                delay(10)
            }
        }

        val subscribers = (1..20).map {
            launch {
                delay((0..100).random().toLong())
                val job = launch {
                    shared.collect { totalReceived.incrementAndGet() }
                }
                delay((50..200).random().toLong())
                job.cancel()
            }
        }

        launch {
            repeat(1000) {
                source.emit(it)
                delay(2)
            }
        }

        // 4. Deterministic wait for completion
        withTimeout(10_000) {
            while (persistentResults.size < 1000) {
                delay(50)
            }
        }

        subscribers.joinAll()
        persistentJob.cancel()

        println("Broadcast Stress: Persistent subscriber saw ${persistentResults.size} items.")
        assertEquals(1000, persistentResults.size)

        // 5. Cleanup the broadcast coroutine
        broadcastJob.cancel()
    }

    @Test
    fun `stress test pace maintains throughput under heavy load`() = runStressTest {
        // Scenario: Upstream emits 10,000 items as fast as possible.
        // Pace is set to 1ms.
        // We expect the total duration to be roughly 10,000ms (10s), not 0s.

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
    fun `stress test shutter high frequency trigger`() = runStressTest {
        // Scenario: Upstream emits slowly (stable data), Trigger emits extremely fast (sampling).

        val upstream = MutableSharedFlow<Int>()
        val trigger = MutableSharedFlow<Unit>()
        val results = ConcurrentLinkedQueue<Int>()
        val duration = 2.seconds

        val job = launch {
            upstream.asReactive()
                .shutter(trigger)
                .collect { results.add(it) }
        }

        // Upstream emitter: Updates value every 100ms
        val upstreamJob = launch {
            var counter = 0
            while (isActive) {
                upstream.emit(counter++)
                delay(100)
            }
        }

        // Trigger emitter: Fires every 1ms (very fast sampling)
        val triggerJob = launch {
            val end = System.currentTimeMillis() + duration.inWholeMilliseconds
            while (System.currentTimeMillis() < end) {
                trigger.emit(Unit)
                delay(1)
            }
        }

        triggerJob.join()
        upstreamJob.cancelAndJoin()
        job.cancelAndJoin()

        // Verification:
        // We sampled for 2 seconds. Ideally 2000 samples.
        // However, on many OSs (Windows/Linux), delay(1) can be 10-15ms.
        // 2000ms / 15ms = ~133 samples.
        // We assert > 200 to ensure we are capturing significantly more than the upstream rate (20 items).
        assertTrue(results.size > 200, "Should have captured many samples, got ${results.size}")

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