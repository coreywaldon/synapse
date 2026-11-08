package com.synapselib.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for bugs found during the core correctness pass, plus coverage for the
 * [kotlin.time.TimeSource]-based API. Tests marked "(regression)" failed or hung against the
 * pre-fix implementation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReactiveFlowRegressionTest {

    // ── Time source ─────────────────────────────────────────────────────

    @Test
    fun `(regression) chained operators keep the time source of the originating ReactiveFlow`() = runTest {
        val flow = flow {
            emit("A")
            delay(600) // longer than the squash window in virtual time
            emit("A")
        }

        // Before the fix, spy() re-wrapped the flow with the default wall-clock
        // source, so squash measured ~0ns of real time between the two "A"s.
        val results = ReactiveFlow(flow, testTimeSource)
            .spy(logger = {})
            .squash(500.milliseconds)
            .toList()

        assertEquals(listOf("A", "A"), results)
    }

    @Test
    fun `asReactive with a TimeSource drives pace on virtual time`() = runTest {
        val times = mutableListOf<Long>()

        flow {
            emit(1); emit(2)
            delay(250)
            emit(3)
        }.asReactive(testTimeSource)
            .pace(100.milliseconds)
            .collect { times.add(currentTime) }

        // 1 immediately, 2 paced to 100, 3 arrives at 350 (> 100 since 2) and is not delayed.
        assertEquals(listOf(0L, 100L, 350L), times)
    }

    @Test
    fun `combo extension accepts a TimeSource`() = runTest {
        val results = flow {
            emit(1); delay(50); emit(2)
            delay(150); emit(1); delay(150); emit(2) // too slow: no match
        }.combo(listOf(listOf(1, 2)), 100.milliseconds, timeSource = testTimeSource).toList()

        assertEquals(listOf(listOf(1, 2)), results)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `deprecated nano-clock overloads still work`() = runTest {
        val clock: () -> Long = { testScheduler.currentTime * 1_000_000 }
        val source = flow {
            emit(1); emit(1)
            delay(600)
            emit(1)
        }

        assertEquals(listOf(1, 1), source.squash(500.milliseconds, timeSource = clock).toList())
        assertEquals(listOf(1, 1), ReactiveFlow(source, clock).squash(500.milliseconds).toList())
        assertEquals(listOf(1, 1), ReactiveFlow(source) { clock() }.squash(500.milliseconds).toList())
        assertEquals(listOf(1, 1, 1), source.pace(10.milliseconds, clock).toList())
        assertEquals(
            listOf(listOf(1, 1)),
            source.combo(listOf(listOf(1, 1)), 100.milliseconds, timeSource = clock).toList(),
        )
    }

    // ── shutter ─────────────────────────────────────────────────────────

    @Test
    fun `(regression) shutter completes when the trigger completes even if upstream never does`() = runTest {
        val upstream = MutableStateFlow(1) // never completes
        val trigger = flow {
            delay(10); emit(Unit)
            delay(10); emit(Unit)
        }

        val results = withTimeout(5.seconds) { upstream.shutter(trigger).toList() }

        assertEquals(listOf(1, 1), results)
    }

    @Test
    fun `shutter keeps sampling the last value after upstream completes`() = runTest {
        val trigger = flow {
            delay(10); emit(Unit)
            delay(10); emit(Unit)
        }

        val results = flowOf(1, 2, 3).shutter(trigger).toList()

        assertEquals(listOf(3, 3), results)
    }

    @Test
    fun `shutter propagates upstream errors`() = runTest {
        val upstream = flow<Int> {
            emit(1)
            delay(5)
            throw IllegalStateException("boom")
        }
        val trigger = MutableSharedFlow<Unit>()

        val error = runCatching { upstream.shutter(trigger).toList() }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "Expected upstream error, got $error")
        assertEquals("boom", error?.message)
    }

    @Test
    fun `shutter propagates trigger errors`() = runTest {
        val trigger = flow<Unit> {
            emit(Unit)
            throw IllegalStateException("trigger")
        }

        val error = runCatching { MutableStateFlow(1).shutter(trigger).toList() }.exceptionOrNull()

        assertEquals("trigger", error?.message)
    }

    @Test
    fun `(regression) shutter never emits a torn value on a multi-threaded dispatcher`() = runBlocking(Dispatchers.Default) {
        val upstream = flow {
            var i = 0
            while (true) {
                emit(Box(i++))
                if (i % 64 == 0) yield()
            }
        }
        val trigger = flow {
            repeat(20_000) {
                emit(Unit)
                if (it % 16 == 0) yield()
            }
        }
        val results = ConcurrentLinkedQueue<Box?>()

        withTimeout(10.seconds) {
            upstream.shutter(trigger).collect { results.add(it) }
        }

        var previous = -1
        for (box in results) {
            assertNotNull(box)
            assertTrue(box!!.value >= previous, "Samples should never regress")
            previous = box.value
        }
    }

    private data class Box(val value: Int)

    // ── combo ───────────────────────────────────────────────────────────

    @Test
    fun `(regression) combo greedy does not fire the short match while a longer one is still being typed`() = runTest {
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3, 4))
        val flow = flow {
            emit(1); emit(2)
            delay(10); emit(3) // no match at [1,2,3] yet, but the chain continues
            delay(10); emit(4)
        }

        val results = flow.combo(sequences, 100.milliseconds, greedy = true, timeSource = testTimeSource).toList()

        assertEquals(listOf(listOf(1, 2, 3, 4)), results)
    }

    @Test
    fun `combo greedy emits the held match when an intermediate step times out`() = runTest {
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3, 4))
        val emittedAt = mutableListOf<Long>()
        val flow = flow {
            emit(1); emit(2)
            delay(10); emit(3)
            delay(500) // [1,2,3,4] never completes
        }

        val results = flow.combo(sequences, 100.milliseconds, greedy = true, timeSource = testTimeSource)
            .toList()
            .also { emittedAt.add(currentTime) }

        assertEquals(listOf(listOf(1, 2)), results)
        assertEquals(listOf(500L + 10L), emittedAt) // flow completes with the upstream
    }

    @Test
    fun `combo greedy hold fires exactly one timeout after the last step`() = runTest {
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3))
        val times = mutableListOf<Long>()

        val job = launch {
            MutableSharedFlow<Int>(replay = 2).apply { emit(1); emit(2) }
                .combo(sequences, 100.milliseconds, greedy = true, timeSource = testTimeSource)
                .collect { times.add(currentTime) }
        }

        advanceTimeBy(99); runCurrent()
        assertEquals(emptyList<Long>(), times)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf(100L), times)
        job.cancel()
    }

    @Test
    fun `(regression) combo greedy does not drop a held match when a different combo completes`() = runTest {
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3), listOf(4))
        val flow = flow {
            emit(1); emit(2) // held: [1,2]
            delay(10); emit(4) // breaks the chain AND completes [4]
        }

        val results = flow.combo(sequences, 100.milliseconds, greedy = true, timeSource = testTimeSource).toList()

        assertEquals(listOf(listOf(1, 2), listOf(4)), results)
    }

    @Test
    fun `combo greedy flushes a held match immediately when upstream completes`() = runTest {
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3))

        val results = flowOf(1, 2).combo(sequences, 10.seconds, greedy = true, timeSource = testTimeSource).toList()

        assertEquals(listOf(listOf(1, 2)), results)
        assertEquals(0L, currentTime) // did not wait for the 10s hold
    }

    @Test
    fun `(regression) combo ignores empty sequences instead of matching every element`() = runTest {
        val sequences = listOf(emptyList(), listOf(1, 2))

        val results = flowOf(7, 1, 2, 8).combo(sequences, 100.milliseconds, timeSource = testTimeSource).toList()

        assertEquals(listOf(listOf(1, 2)), results)
    }

    @Test
    fun `combo propagates upstream errors`() = runTest {
        val upstream = flow {
            emit(1)
            throw IllegalStateException("combo-boom")
        }

        val error = runCatching {
            upstream.combo(listOf(listOf(1, 2)), 100.milliseconds, greedy = true).toList()
        }.exceptionOrNull()

        assertEquals("combo-boom", error?.message)
    }

    @Test
    fun `combo with infinite timeout never resets and never overflows`() = runTest {
        val results = flow {
            emit(1)
            delay(365.days)
            emit(2)
        }.combo(listOf(listOf(1, 2), listOf(1, 2, 3)), Duration.INFINITE, greedy = true, timeSource = testTimeSource)
            .toList()

        assertEquals(listOf(listOf(1, 2)), results)
    }

    @Test
    fun `(regression) combo greedy emits each match exactly once on a multi-threaded dispatcher`() = runBlocking(Dispatchers.Default) {
        val sequences = listOf(listOf(1, 2), listOf(1, 2, 3))
        val iterations = 2_000
        val long = AtomicInteger()
        val short = AtomicInteger()

        val input = flow {
            repeat(iterations) {
                emit(1); emit(2); emit(3) // longer match completes
                emit(1); emit(2); emit(9) // chain broken: held short match fires
                if (it % 32 == 0) yield()
            }
        }

        withTimeout(30.seconds) {
            input.combo(sequences, 5.seconds, greedy = true).collect {
                when (it) {
                    listOf(1, 2, 3) -> long.incrementAndGet()
                    listOf(1, 2) -> short.incrementAndGet()
                    else -> error("unexpected match $it")
                }
            }
        }

        assertEquals(iterations, long.get())
        assertEquals(iterations, short.get())
    }

    // ── resilient ───────────────────────────────────────────────────────

    @Test
    fun `(regression) resilient applies fractional backoff factors exactly`() = runTest {
        var attempts = 0
        val flow = flow {
            attempts++
            if (attempts <= 2) throw RuntimeException("fail $attempts")
            emit(attempts)
        }

        val job = launch {
            flow.resilient(maxRetries = 3, initialDelay = 100.milliseconds, factor = 1.5).collect {}
        }

        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(101) // first retry after 100ms * 1.5^0
        assertEquals(2, attempts)

        // Second retry must wait 150ms (100 * 1.5^1). The old implementation
        // truncated 1.5^1 to 1 and retried after only 100ms.
        advanceTimeBy(101)
        assertEquals(2, attempts)
        advanceTimeBy(50)
        assertEquals(3, attempts)

        job.cancel()
    }

    @Test
    fun `backoff delay saturates instead of overflowing or throwing`() {
        assertEquals(150.milliseconds, backoffDelay(100.milliseconds, 1.5, 1))
        // Old code: Long overflow produced a negative (i.e. zero) delay -> hot retry loop.
        assertEquals(Duration.INFINITE, backoffDelay(100.milliseconds, 2.0, 2_000))
        // ZERO * Infinity would be NaN and throw inside Duration.times.
        assertEquals(Duration.ZERO, backoffDelay(Duration.ZERO, 2.0, 2_000))
        assertEquals(Duration.ZERO, backoffDelay(100.milliseconds, -2.0, 1))
    }

    // ── spy ─────────────────────────────────────────────────────────────

    @Test
    fun `(regression) spy reports downstream cancellation as Cancelled rather than Failed`() = runTest {
        val logs = mutableListOf<String>()

        flowOf(1, 2, 3).spy("T") { logs.add(it) }.take(1).collect {}

        assertEquals(listOf("[T] Started", "[T] Emitted: 1", "[T] Cancelled"), logs)
    }

    // ── gate ────────────────────────────────────────────────────────────

    @Test
    fun `(regression) gate rejects a non-positive buffer capacity up front`() {
        assertThrows(IllegalArgumentException::class.java) {
            flowOf(1).gate(MutableStateFlow(false), GateStrategy.BUFFER, bufferCapacity = 0)
        }
    }

    @Test
    fun `(regression) gate completion does not surface as a cancellation to an outer catch`() = runTest {
        var caught: Throwable? = null
        val results = mutableListOf<Int>()
        try {
            flowOf(1, 2).gate(MutableStateFlow(true)).collect { results.add(it) }
        } catch (e: CancellationException) {
            caught = e
        }
        assertEquals(listOf(1, 2), results)
        assertNull(caught)
    }

    @Test
    fun `gate completes normally for every strategy`() = runTest {
        for (strategy in GateStrategy.entries) {
            val gate = MutableStateFlow(false)
            val source = flow {
                emit(1); emit(2)
                delay(10)
                gate.value = true
                delay(10)
                emit(3)
            }
            val expected = when (strategy) {
                GateStrategy.BUFFER -> listOf(1, 2, 3)
                GateStrategy.LATEST -> listOf(2, 3)
                GateStrategy.DROP -> listOf(3)
            }
            assertEquals(expected, source.gate(gate, strategy).toList(), "strategy=$strategy")
        }
    }

    @Test
    fun `gate waits for the gate to open before completing with buffered data`() = runTest {
        val gate = MutableStateFlow(false)
        launch {
            delay(100)
            gate.value = true
        }

        val results = flowOf(1, 2).gate(gate).toList()

        assertEquals(listOf(1, 2), results)
        assertEquals(100L, currentTime)
    }

    @Test
    fun `gate accepts a plain Flow condition and honours bufferCapacity`() = runTest {
        val condition = flow {
            emit(false)
            delay(50)
            emit(true)
        }
        val source = flow {
            repeat(5) { emit(it) }
        }

        val results = source.gate(condition, GateStrategy.BUFFER, bufferCapacity = 2).toList()

        assertEquals(listOf(3, 4), results) // oldest dropped on overflow
    }

    // ── chunk ───────────────────────────────────────────────────────────

    @Test
    fun `(regression) chunk rejects a non-positive period up front`() {
        assertThrows(IllegalArgumentException::class.java) { flowOf(1).chunk(Duration.ZERO) }
    }

    @Test
    fun `chunk extension exposes maxBufferSize`() = runTest {
        val results = flowOf(1, 2, 3, 4, 5).chunk(10.seconds, maxBufferSize = 2).toList()

        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), results)
    }

    @Test
    fun `chunk drops the partial buffer and rethrows on a multi-threaded dispatcher`() = runBlocking(Dispatchers.Default) {
        repeat(200) { round ->
            val results = mutableListOf<List<Int>>()
            val error = runCatching {
                flow {
                    emit(1); emit(2)
                    throw IllegalStateException("chunk-$round")
                }.chunk(10.seconds).collect { results.add(it) }
            }.exceptionOrNull()

            assertEquals("chunk-$round", error?.message)
            assertEquals(emptyList<List<Int>>(), results, "round=$round")
        }
    }

    // ── broadcast ───────────────────────────────────────────────────────

    @Test
    fun `broadcast rejects a negative keepAlive`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            flowOf(1).broadcast(backgroundScope, keepAlive = (-1).milliseconds)
        }
    }

    @Test
    fun `broadcast with zero keepAlive stops upstream as soon as the last collector leaves`() = runTest {
        var starts = 0
        val shared = flow {
            starts++
            emit(starts)
            awaitCancellation()
        }.broadcast(backgroundScope)

        val first = launch { shared.collect() }
        runCurrent()
        first.cancel()
        runCurrent()

        val second = launch { shared.collect() }
        runCurrent()

        assertEquals(2, starts)
        second.cancel()
    }
}
