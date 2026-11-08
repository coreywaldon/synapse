@file:OptIn(ExperimentalCoroutinesApi::class)

package com.synapselib.arch.base

import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DefaultSwitchBoardLifecycleTest {

    data class Counter(val n: Int)
    data class Ping(val n: Int)
    data class Answer(val value: String)
    data class Hang(val id: Int) : DataImpulse<Answer>()

    /** Emits once, then suspends until cancelled, recording the cancellation. */
    class HangingProvider(
        private val started: CompletableDeferred<Unit>,
        private val cancelled: AtomicBoolean,
    ) : Provider<Hang, Answer>() {
        override fun ProviderScope.produce(impulse: Hang): Flow<Answer> = flow {
            try {
                emit(Answer("first"))
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.set(true)
            }
        }
    }

    private class Fixture(
        val callerJob: Job,
        val board: DefaultSwitchBoard,
        val providerStarted: CompletableDeferred<Unit>,
        val providerCancelled: AtomicBoolean,
    )

    private fun TestScope.fixture(eager: Boolean = false): Fixture {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val started = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        val registry = ProviderRegistry.Builder()
            .register<Answer, Hang> { HangingProvider(started, cancelled) }
            .build()
        // A plain Job (not a SupervisorJob) to prove close() never cancels the caller's scope.
        val callerJob = Job()
        val board = DefaultSwitchBoard(
            CoroutineScope(dispatcher + callerJob),
            registry,
            SwitchBoardConfig {
                workerContext = dispatcher
                if (eager) eagerSharing()
            },
        )
        return Fixture(callerJob, board, started, cancelled)
    }

    // ── close() basics ──────────────────────────────────────────────────

    @Test
    fun `isClosed reflects close and close is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        assertFalse(f.board.isClosed)
        f.board.close()
        assertTrue(f.board.isClosed)
        f.board.close()
        f.board.close()
        assertTrue(f.board.isClosed)
        f.callerJob.cancel()
    }

    @Test
    fun `board works with use-block via AutoCloseable`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val board = f.board.use { board ->
            board.broadcastState(Counter::class, Counter(1))
            assertEquals(listOf<Any>(Counter(1)), board.getRawStateFlow(Counter::class).replayCache)
            board
        }
        assertTrue(board.isClosed)
        f.callerJob.cancel()
    }

    // ── Fail fast after close ───────────────────────────────────────────

    @Test
    fun `broadcast and trigger fail fast after close`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.board.close()

        assertTrue(runCatching { f.board.broadcastState(Counter::class, Counter(1)) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { f.board.triggerImpulse(Ping::class, Ping(1)) }.exceptionOrNull() is IllegalStateException)
        f.callerJob.cancel()
    }

    @Test
    fun `flow accessors fail fast after close`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.board.close()

        assertThrows(IllegalStateException::class.java) { f.board.stateFlow(Counter::class) }
        assertThrows(IllegalStateException::class.java) { f.board.impulseFlow(Ping::class) }
        assertThrows(IllegalStateException::class.java) { f.board.getRawStateFlow(Counter::class) }
        assertThrows(IllegalStateException::class.java) { f.board.getRawImpulseFlow(Ping::class) }
        f.callerJob.cancel()
    }

    @Test
    fun `handleRequest fails fast after close, both at call and at collection`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val createdBeforeClose = f.board.handleRequest(Hang::class, Answer::class, Hang(1))
        f.board.close()

        assertThrows(IllegalStateException::class.java) {
            f.board.handleRequest(Hang::class, Answer::class, Hang(2))
        }
        val collected = runCatching { createdBeforeClose.collect { } }
        assertTrue(collected.exceptionOrNull() is IllegalStateException)
        f.callerJob.cancel()
    }

    @Test
    fun `registering interceptors and observers after close is harmless`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.board.close()

        f.board.addInterceptor(InterceptPoint.STATE_UPSTREAM, Interceptor.read<Counter> { }).unregister()
        f.board.addObserver { _, _, _, _ -> }.unregister()
        f.callerJob.cancel()
    }

    // ── Teardown effects ────────────────────────────────────────────────

    @Test
    fun `close cancels active provider jobs`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val results = CopyOnWriteArrayList<DataState<Answer>>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            f.board.handleRequest(Hang::class, Answer::class, Hang(1)).collect { results += it }
        }
        f.providerStarted.await()
        advanceUntilIdle()
        assertTrue(results.any { it is DataState.Success })
        assertFalse(f.providerCancelled.get())

        f.board.close()
        advanceUntilIdle()

        assertTrue(f.providerCancelled.get(), "provider job should be cancelled by close()")
        collector.cancel()
        f.callerJob.cancel()
    }

    @Test
    fun `close stops sharing coroutines without cancelling the caller scope`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(eager = true)
        val downstreamCalls = AtomicInteger(0)
        f.board.addInterceptor(InterceptPoint.STATE_DOWNSTREAM, Interceptor.read<Counter> { downstreamCalls.incrementAndGet() })
        f.board.stateFlow(Counter::class)
        f.board.impulseFlow(Ping::class)
        advanceUntilIdle()

        // The board's coroutines live under the caller's job while open…
        assertTrue(f.callerJob.children.any { it.isActive })

        f.board.close()
        advanceUntilIdle()

        // …and are all gone after close, while the caller's job is untouched.
        assertTrue(f.callerJob.isActive)
        assertTrue(f.callerJob.children.none { it.isActive })

        // The caller's scope is still fully usable.
        val ran = CompletableDeferred<Unit>()
        CoroutineScope(UnconfinedTestDispatcher(testScheduler) + f.callerJob).launch { ran.complete(Unit) }
        ran.await()
        assertEquals(0, downstreamCalls.get())
        f.callerJob.cancel()
    }

    @Test
    fun `flows obtained before close stop receiving values`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val received = CopyOnWriteArrayList<Counter>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            f.board.stateFlow(Counter::class).collect { received += it }
        }
        f.board.broadcastState(Counter::class, Counter(1))
        advanceUntilIdle()
        assertEquals(listOf(Counter(1)), received)

        f.board.close()
        advanceUntilIdle()

        // Collector is not completed (SharedFlows never complete) — the owner cancels it.
        assertTrue(collector.isActive)
        collector.cancel()
        assertEquals(listOf(Counter(1)), received)
        f.callerJob.cancel()
    }

    @Test
    fun `cancelling the caller scope does not mark the board closed`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.callerJob.cancel()
        assertFalse(f.board.isClosed)
        // Raw channels don't depend on the scope, so producing still works.
        f.board.broadcastState(Counter::class, Counter(3))
        assertEquals(listOf<Any>(Counter(3)), f.board.getRawStateFlow(Counter::class).replayCache)
        f.board.close()
    }
}
