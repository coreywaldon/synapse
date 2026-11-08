@file:OptIn(ExperimentalCoroutinesApi::class)

package com.synapselib.arch.base.provider

import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Exercises [ProviderManager] directly: deduplication, idle teardown,
 * concurrency limits, failure isolation and disposal.
 */
class ProviderManagerTest {

    // ── Test types ──────────────────────────────────────────────────────

    data class Result(val value: String)

    /** One-shot fetch. */
    data class Fetch(val key: String) : DataImpulse<Result>()

    /** Streaming observation that never completes on its own. */
    data class Watch(val key: String) : DataImpulse<Result>()

    /** Always fails. */
    data class Explode(val reason: String) : DataImpulse<Result>()

    /** Primitive result type. */
    data class Count(val n: Int) : DataImpulse<Int>()

    /** Records provider lifecycle events. */
    class Probe {
        /** Factory invocations (provider instances created). */
        val instances = AtomicInteger()

        /** Times a provider flow started producing. */
        val started = AtomicInteger()

        /** Times a provider flow was cancelled / finished. */
        val stopped = AtomicInteger()
    }

    private val idle = 1_000L

    /**
     * Manager, switchboard and collectors all live in [scope], a plain scope
     * on the test scheduler. (Work under `backgroundScope` is invisible to
     * `advanceUntilIdle`.) It is cancelled when the test finishes.
     */
    private class Fixture(val manager: ProviderManager, val switchboard: SwitchBoard, val scope: CoroutineScope)

    private fun TestScope.fixture(
        registry: ProviderRegistry,
        maxConcurrentJobs: Int = 0,
        idleTimeoutMillis: Long = idle,
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        backgroundScope.coroutineContext.job.invokeOnCompletion { scope.cancel() }
        val switchboard = DefaultSwitchBoard(scope, ProviderRegistry.EMPTY, dispatcher)
        val manager = ProviderManager(
            registry = registry,
            parentScope = scope,
            workerContext = dispatcher,
            maxConcurrentJobs = maxConcurrentJobs,
            idleTimeoutMillis = idleTimeoutMillis,
        )
        return Fixture(manager, switchboard, scope)
    }

    private fun registry(
        probe: Probe = Probe(),
        cleanupMillis: Long = 0,
        onWatchStart: suspend (Watch) -> Unit = {},
    ): ProviderRegistry = ProviderRegistry.Builder()
        .register<Result, Fetch> {
            probe.instances.incrementAndGet()
            object : Provider<Fetch, Result>() {
                override fun ProviderScope.produce(impulse: Fetch): Flow<Result> = flow {
                    probe.started.incrementAndGet()
                    emit(Result("fetched ${impulse.key}"))
                }
            }
        }
        .register<Result, Watch> {
            probe.instances.incrementAndGet()
            object : Provider<Watch, Result>() {
                override fun ProviderScope.produce(impulse: Watch): Flow<Result> = flow {
                    probe.started.incrementAndGet()
                    try {
                        onWatchStart(impulse)
                        emit(Result("watching ${impulse.key}"))
                        awaitCancellation()
                    } finally {
                        probe.stopped.incrementAndGet()
                        if (cleanupMillis > 0) withContext(NonCancellable) { delay(cleanupMillis) }
                    }
                }
            }
        }
        .register<Result, Explode> {
            object : Provider<Explode, Result>() {
                override fun ProviderScope.produce(impulse: Explode): Flow<Result> = flow {
                    emit(Result("before failure"))
                    throw IllegalStateException(impulse.reason)
                }
            }
        }
        .register<Int, Count> {
            object : Provider<Count, Int>() {
                override fun ProviderScope.produce(impulse: Count): Flow<Int> = flowOf(impulse.n * 2)
            }
        }
        .build()

    private fun <Need : Any, I : DataImpulse<Need>> Fixture.request(
        impulseType: KClass<I>,
        needType: KClass<Need>,
        impulse: I,
    ): SharedFlow<DataState<Need>> = manager.request(impulseType, needType, impulse, switchboard)

    private fun Fixture.watch(key: String) = request(Watch::class, Result::class, Watch(key))
    private fun Fixture.fetch(key: String) = request(Fetch::class, Result::class, Fetch(key))

    private fun <T : Any> Fixture.collectInto(
        flow: Flow<DataState<T>>,
        sink: MutableList<DataState<T>> = CopyOnWriteArrayList(),
    ): Job = scope.launch { flow.collect { sink += it } }

    // ══════════════════════════════════════════════════════════════════════
    // Basic DataState lifecycle
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `one-shot request emits Loading then Success`() = runTest {
        val f = fixture(registry())
        val received = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.fetch("a"), received)
        advanceUntilIdle()

        assertEquals(listOf(DataState.Loading, DataState.Success(Result("fetched a"))), received.toList())
        assertEquals(0, f.manager.activeJobCount, "completed one-shot job leaves the table")
    }

    @Test
    fun `provider failure becomes Error carrying stale data`() = runTest {
        val f = fixture(registry())
        val received = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.request(Explode::class, Result::class, Explode("boom")), received)
        advanceUntilIdle()

        val error = assertInstanceOf(DataState.Error::class.java, received.last())
        assertEquals("boom", error.cause.message)
        assertEquals(Result("before failure"), error.staleData)
    }

    @Test
    fun `late subscriber to an in-flight job receives the latest state via replay`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        f.collectInto(f.watch("x"))
        advanceUntilIdle()

        val late = f.watch("x").first()
        assertEquals(DataState.Success(Result("watching x")), late)
        assertEquals(1, probe.instances.get())
    }

    @Test
    fun `primitive Need types are supported`() = runTest {
        val f = fixture(registry())
        val received = CopyOnWriteArrayList<DataState<Int>>()
        f.collectInto(f.request(Count::class, Int::class, Count(21)), received)
        advanceUntilIdle()

        assertEquals(DataState.Success(42), received.last())
    }

    @Test
    fun `unregistered impulse throws NoProviderException`() = runTest {
        val f = fixture(ProviderRegistry.EMPTY)
        assertThrows(NoProviderException::class.java) { f.fetch("a") }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Deduplication
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `equal impulses share one provider instance`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        val a = CopyOnWriteArrayList<DataState<Result>>()
        val b = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.watch("same"), a)
        f.collectInto(f.watch("same"), b)
        advanceUntilIdle()

        assertEquals(1, probe.instances.get())
        assertEquals(1, f.manager.activeJobCount)
        assertTrue(a.last() is DataState.Success)
        assertTrue(b.last() is DataState.Success)
    }

    @Test
    fun `concurrent equal requests from many threads create exactly one provider`() = runBlocking {
        val probe = Probe()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val switchboard = DefaultSwitchBoard(scope, ProviderRegistry.EMPTY, Dispatchers.Default)
        val manager = ProviderManager(registry(probe), scope, Dispatchers.Default, idleTimeoutMillis = 60_000)
        try {
            val rounds = 40
            val threads = 16
            repeat(rounds) { round ->
                val barrier = CyclicBarrier(threads)
                val flows = arrayOfNulls<SharedFlow<DataState<Result>>>(threads)
                (0 until threads).map { i ->
                    thread {
                        barrier.await()
                        flows[i] = manager.request(Watch::class, Result::class, Watch("k$round"), switchboard)
                    }
                }.forEach { it.join() }
                flows.forEach { flow ->
                    withTimeout(5_000) { flow!!.first { it is DataState.Success } }
                }
            }
            assertEquals(rounds, probe.instances.get(), "one provider per distinct impulse")
            assertEquals(rounds, manager.activeJobCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `requests with different compatible needTypes share the job`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))

        // First caller asks for a super-type; the job must still be typed by
        // the registered Need so a precise caller can dedup onto it.
        val asAny = f.request(Watch::class, Any::class, Watch("k"))
        val asResult = f.request(Watch::class, Result::class, Watch("k"))
        val anyStates = CopyOnWriteArrayList<DataState<Any>>()
        val resultStates = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(asAny, anyStates)
        f.collectInto(asResult, resultStates)
        advanceUntilIdle()

        assertEquals(1, probe.instances.get())
        assertEquals(DataState.Success(Result("watching k")), anyStates.last())
        assertEquals(DataState.Success(Result("watching k")), resultStates.last())
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `incompatible needType fails with a descriptive message`() = runTest {
        val f = fixture(registry())
        val lyingType = Watch::class as KClass<DataImpulse<String>>
        val lyingImpulse = Watch("k") as DataImpulse<String>

        // Both on the creation path and on the dedup path.
        repeat(2) {
            if (it == 1) f.watch("k")
            val error = assertThrows(IllegalArgumentException::class.java) {
                f.manager.request(lyingType, String::class, lyingImpulse, f.switchboard)
            }
            val message = error.message.orEmpty()
            assertTrue(message.contains("Watch"), message)
            assertTrue(message.contains(Result::class.java.name), message)
            assertTrue(message.contains("kotlin.String"), message)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Table removal
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `a finishing job never evicts a newer job for an equal impulse`() = runTest {
        val probe = Probe()
        val cleanup = 500L
        val f = fixture(registry(probe, cleanupMillis = cleanup))

        // Job A: subscribe, then leave → idle-cancelled, slow cleanup in progress.
        val subscriber = f.collectInto(f.watch("k"))
        advanceUntilIdle()
        subscriber.cancel()
        advanceTimeBy(idle)
        runCurrent()
        assertEquals(1, probe.stopped.get(), "job A cancelled after idle timeout")

        // Job B for the same impulse while A is still cleaning up.
        f.collectInto(f.watch("k"))
        runCurrent()
        assertEquals(2, probe.instances.get())
        assertEquals(2, f.manager.activeJobCount, "A (cleaning up) and B are both live")

        // A finishes; its completion must not remove B's entry.
        advanceTimeBy(cleanup)
        runCurrent()
        assertEquals(1, f.manager.activeJobCount)

        f.collectInto(f.watch("k"))
        runCurrent()
        assertEquals(2, probe.instances.get(), "third request dedups onto B")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Uncollected requests (leak)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `a request that is never collected is discarded after the idle timeout`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))

        f.watch("orphan")
        runCurrent()
        assertEquals(1, f.manager.activeJobCount)

        advanceTimeBy(idle)
        runCurrent()
        assertEquals(0, f.manager.activeJobCount)
        assertEquals(0, probe.instances.get(), "provider is never created without a collector")

        // A later equal request is not deduped onto the dead job.
        val received = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.watch("orphan"), received)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(DataState.Success(Result("watching orphan")), received.last())
    }

    @Test
    fun `collecting a flow whose job expired transparently re-requests`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))

        val stale = f.fetch("x")
        advanceTimeBy(idle * 5)
        runCurrent()
        assertEquals(0, f.manager.activeJobCount)

        val received = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(stale, received)
        advanceUntilIdle()
        assertEquals(listOf(DataState.Loading, DataState.Success(Result("fetched x"))), received.toList())
        assertEquals(1, probe.instances.get())
    }

    // ══════════════════════════════════════════════════════════════════════
    // Idle teardown
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `streaming provider is cancelled idleTimeout after the last subscriber leaves`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        val subscriber = f.collectInto(f.watch("k"))
        advanceTimeBy(idle * 10)
        runCurrent()
        assertEquals(1, probe.started.get())
        assertEquals(0, probe.stopped.get(), "active subscriber keeps the provider alive indefinitely")

        subscriber.cancel()
        runCurrent()
        advanceTimeBy(idle - 1)
        runCurrent()
        assertEquals(0, probe.stopped.get(), "still within the idle window")
        assertEquals(1, f.manager.activeJobCount)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, probe.stopped.get(), "provider cancelled once the window elapses")
        assertEquals(0, f.manager.activeJobCount)
    }

    @Test
    fun `resubscribing within the idle window keeps the same provider`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        val flow = f.watch("k")

        val first = f.collectInto(flow)
        runCurrent()
        first.cancel()
        runCurrent()
        advanceTimeBy(idle / 2)
        runCurrent()

        val second = f.collectInto(flow)
        runCurrent()
        advanceTimeBy(idle * 3)
        runCurrent()
        assertEquals(0, probe.stopped.get())

        // A stale timer from the first idle period must not fire early.
        second.cancel()
        runCurrent()
        advanceTimeBy(idle - 1)
        runCurrent()
        assertEquals(0, probe.stopped.get())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, probe.stopped.get())
        assertEquals(1, probe.instances.get())
    }

    @Test
    fun `job survives until the last of several subscribers leaves`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        val a = f.collectInto(f.watch("k"))
        val b = f.collectInto(f.watch("k"))
        runCurrent()

        a.cancel()
        runCurrent()
        advanceTimeBy(idle * 3)
        runCurrent()
        assertEquals(0, probe.stopped.get())

        b.cancel()
        runCurrent()
        advanceTimeBy(idle)
        runCurrent()
        assertEquals(1, probe.stopped.get())
    }

    @Test
    fun `a new request after idle teardown starts a fresh provider`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        val subscriber = f.collectInto(f.watch("k"))
        runCurrent()
        subscriber.cancel()
        advanceTimeBy(idle + 1)
        runCurrent()

        val received = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.watch("k"), received)
        runCurrent()
        assertEquals(2, probe.instances.get())
        assertEquals(listOf(DataState.Loading, DataState.Success(Result("watching k"))), received.toList())
    }

    @Test
    fun `idle teardown can be disabled with Long MAX_VALUE`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe), idleTimeoutMillis = Long.MAX_VALUE)
        val subscriber = f.collectInto(f.watch("k"))
        runCurrent()
        subscriber.cancel()
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(0, probe.stopped.get())
        assertEquals(1, f.manager.activeJobCount)
    }

    @Test
    fun `negative idle timeout is rejected`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderManager(registry(), backgroundScope, idleTimeoutMillis = -1)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Concurrency limit
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `concurrency limit rejects only while live jobs occupy every permit`() = runTest {
        val f = fixture(registry(), maxConcurrentJobs = 1)

        // A completed one-shot job frees its permit.
        f.collectInto(f.fetch("one"))
        advanceUntilIdle()
        assertEquals(0, f.manager.activeJobCount)

        // An uncollected request frees its permit after the idle timeout.
        f.fetch("orphan")
        runCurrent()
        val rejected = f.fetch("blocked").replayCache.single()
        assertInstanceOf(ProviderConcurrencyException::class.java, (rejected as DataState.Error).cause)
        advanceTimeBy(idle)
        runCurrent()

        // A live streaming job holds its permit.
        f.collectInto(f.watch("live"))
        runCurrent()
        val overflow = f.fetch("two").replayCache.single()
        assertInstanceOf(ProviderConcurrencyException::class.java, (overflow as DataState.Error).cause)

        // Dedup onto the live job does not need a permit.
        assertTrue(f.watch("live").first() is DataState.Success)
    }

    @Test
    fun `concurrency limit holds under concurrent requests`() = runBlocking {
        val limit = 4
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val switchboard = DefaultSwitchBoard(scope, ProviderRegistry.EMPTY, Dispatchers.Default)
        val manager = ProviderManager(registry(), scope, Dispatchers.Default, limit, idleTimeoutMillis = 60_000)
        try {
            val threads = 64
            val barrier = CyclicBarrier(threads)
            val flows = arrayOfNulls<SharedFlow<DataState<Result>>>(threads)
            (0 until threads).map { i ->
                thread {
                    barrier.await()
                    flows[i] = manager.request(Watch::class, Result::class, Watch("k$i"), switchboard)
                }
            }.forEach { it.join() }

            val rejected = flows.count { flow ->
                val state = flow!!.replayCache.firstOrNull()
                state is DataState.Error && state.cause is ProviderConcurrencyException
            }
            assertEquals(threads - limit, rejected)
            assertEquals(limit, manager.activeJobCount)
        } finally {
            scope.cancel()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Failure isolation
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `a failing provider does not cancel its siblings`() = runTest {
        val upstream = MutableSharedFlow<String>()
        val registry = ProviderRegistry.Builder()
            .register<Result, Watch> {
                object : Provider<Watch, Result>() {
                    override fun ProviderScope.produce(impulse: Watch): Flow<Result> = flow {
                        upstream.collect { emit(Result(it)) }
                    }
                }
            }
            .register<Result, Explode> {
                object : Provider<Explode, Result>() {
                    override fun ProviderScope.produce(impulse: Explode): Flow<Result> =
                        flow { throw IllegalStateException(impulse.reason) }
                }
            }
            .build()
        val f = fixture(registry)
        val sibling = CopyOnWriteArrayList<DataState<Result>>()
        val failing = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.watch("s"), sibling)
        f.collectInto(f.request(Explode::class, Result::class, Explode("boom")), failing)
        advanceUntilIdle()
        assertInstanceOf(DataState.Error::class.java, failing.last())

        upstream.emit("still alive")
        advanceUntilIdle()
        assertEquals(DataState.Success(Result("still alive")), sibling.last())
    }

    @Test
    fun `a failing coroutine launched from ProviderScope surfaces as Error and stays contained`() = runTest {
        val registry = ProviderRegistry.Builder()
            .register<Result, Explode> {
                object : Provider<Explode, Result>() {
                    override fun ProviderScope.produce(impulse: Explode): Flow<Result> = flow {
                        launch { throw IllegalStateException(impulse.reason) }
                        awaitCancellation()
                    }
                }
            }
            .register<Result, Watch> {
                object : Provider<Watch, Result>() {
                    override fun ProviderScope.produce(impulse: Watch): Flow<Result> = flow {
                        emit(Result("ok"))
                        awaitCancellation()
                    }
                }
            }
            .build()
        val f = fixture(registry)
        val sibling = CopyOnWriteArrayList<DataState<Result>>()
        val failing = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.watch("s"), sibling)
        f.collectInto(f.request(Explode::class, Result::class, Explode("child failed")), failing)
        advanceUntilIdle()

        // runTest would fail with an uncaught exception if it escaped.
        val error = assertInstanceOf(DataState.Error::class.java, failing.last())
        assertEquals("child failed", error.cause.message)
        assertEquals(DataState.Success(Result("ok")), sibling.last())
        assertEquals(1, f.manager.activeJobCount, "only the sibling remains live")
    }

    @Test
    fun `a CancellationException thrown by the provider itself is reported as Error`() = runTest {
        val registry = ProviderRegistry.Builder()
            .register<Result, Fetch> {
                object : Provider<Fetch, Result>() {
                    override fun ProviderScope.produce(impulse: Fetch): Flow<Result> = flow {
                        withTimeout(10) { awaitCancellation() }
                    }
                }
            }
            .build()
        val f = fixture(registry)
        val received = CopyOnWriteArrayList<DataState<Result>>()
        f.collectInto(f.fetch("t"), received)
        advanceUntilIdle()

        assertInstanceOf(DataState.Error::class.java, received.last())
        assertEquals(0, f.manager.activeJobCount)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Disposal
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `dispose cancels all jobs and makes further requests fail fast`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        // Active collectors are terminated by dispose(); see the Termination section below.
        val failures = CopyOnWriteArrayList<Throwable>()
        f.collectCatching(f.watch("a")) { failures += it }
        f.collectCatching(f.watch("b")) { failures += it }
        runCurrent()
        assertEquals(2, f.manager.activeJobCount)
        assertFalse(f.manager.isDisposed)

        f.manager.dispose()
        runCurrent()

        assertTrue(f.manager.isDisposed)
        assertEquals(2, probe.stopped.get())
        assertEquals(2, failures.size, "both collectors terminated")
        assertEquals(0, f.manager.activeJobCount)
        assertThrows(IllegalStateException::class.java) { f.watch("c") }

        f.manager.dispose() // idempotent
        assertTrue(f.manager.isDisposed)
    }

    @Test
    fun `dispose fails collectors that arrive after their job was cancelled`() = runTest {
        val f = fixture(registry())
        val flow = f.watch("a")
        f.manager.dispose()
        runCurrent()

        var failure: Throwable? = null
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            try {
                flow.collect {}
            } catch (e: IllegalStateException) {
                failure = e
            }
        }
        runCurrent()
        assertInstanceOf(IllegalStateException::class.java, failure)
    }

    @Test
    fun `request fails fast when the parent scope is cancelled`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val switchboard = DefaultSwitchBoard(backgroundScope, ProviderRegistry.EMPTY)
        val manager = ProviderManager(registry(), parent, StandardTestDispatcher(testScheduler))
        parent.cancel()

        assertThrows(IllegalStateException::class.java) {
            manager.request(Fetch::class, Result::class, Fetch("x"), switchboard)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Zero idle timeout
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `zero idle timeout with an unconfined worker serves the first collector`() = runBlocking {
        val probe = Probe()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val switchboard = DefaultSwitchBoard(scope, ProviderRegistry.EMPTY, Dispatchers.Unconfined)
            val manager = ProviderManager(registry(probe), scope, Dispatchers.Unconfined, idleTimeoutMillis = 0)

            val fetched = withTimeout(5_000) {
                manager.request(Fetch::class, Result::class, Fetch("a"), switchboard)
                    .first { it is DataState.Success }
            }
            assertEquals(DataState.Success(Result("fetched a")), fetched)

            val watched = withTimeout(5_000) {
                manager.request(Watch::class, Result::class, Watch("w"), switchboard)
                    .first { it is DataState.Success }
            }
            assertEquals(DataState.Success(Result("watching w")), watched)
            assertEquals(2, probe.instances.get(), "each provider is created exactly once")

            // The last (only) collector left: a zero timeout tears down right away.
            withTimeout(5_000) { while (manager.activeJobCount != 0 || probe.stopped.get() != 1) delay(1) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `zero idle timeout on UnconfinedTestDispatcher tears down only once the last collector leaves`() = runTest {
        val probe = Probe()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val switchboard = DefaultSwitchBoard(scope, ProviderRegistry.EMPTY, dispatcher)
            val manager = ProviderManager(registry(probe), scope, dispatcher, idleTimeoutMillis = 0)

            val received = CopyOnWriteArrayList<DataState<Result>>()
            val collector = scope.launch {
                manager.request(Watch::class, Result::class, Watch("k"), switchboard).collect { received += it }
            }
            runCurrent()
            assertEquals(listOf(DataState.Loading, DataState.Success(Result("watching k"))), received.toList())
            assertEquals(1, probe.instances.get())

            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(0, probe.stopped.get(), "an active collector keeps the provider alive")

            collector.cancel()
            runCurrent()
            assertEquals(1, probe.stopped.get(), "torn down as soon as the last collector leaves")
            assertEquals(0, manager.activeJobCount)

            // Never collected: discarded at the next opportunity, provider never created.
            manager.request(Watch::class, Result::class, Watch("orphan"), switchboard)
            runCurrent()
            assertEquals(0, manager.activeJobCount)
            assertEquals(1, probe.instances.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `zero idle timeout on a dispatching worker never tears down before the first collector`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe), idleTimeoutMillis = 0)
        val received = CopyOnWriteArrayList<DataState<Result>>()
        val collector = f.collectInto(f.watch("k"), received)
        runCurrent()
        assertEquals(listOf(DataState.Loading, DataState.Success(Result("watching k"))), received.toList())
        assertEquals(1, probe.instances.get())

        collector.cancel()
        runCurrent()
        assertEquals(1, probe.stopped.get())
        assertEquals(0, f.manager.activeJobCount)
    }

    @Test
    fun `zero idle timeout on a multi-threaded worker creates each provider exactly once`() = runBlocking {
        val probe = Probe()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val switchboard = DefaultSwitchBoard(scope, ProviderRegistry.EMPTY, Dispatchers.Default)
            val manager = ProviderManager(registry(probe), scope, Dispatchers.Default, idleTimeoutMillis = 0)
            val rounds = 200
            withTimeout(30_000) {
                repeat(rounds) { i ->
                    val state = manager.request(Watch::class, Result::class, Watch("k$i"), switchboard)
                        .first { it is DataState.Success }
                    assertEquals(DataState.Success(Result("watching k$i")), state)
                }
            }
            assertEquals(rounds, probe.instances.get(), "no provider is re-created")
        } finally {
            scope.cancel()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Termination of active collectors
    // ══════════════════════════════════════════════════════════════════════

    /** Collects [flow] in the fixture scope, reporting the [IllegalStateException] that ends collection. */
    private fun <T : Any> Fixture.collectCatching(
        flow: Flow<DataState<T>>,
        sink: MutableList<DataState<T>> = CopyOnWriteArrayList(),
        onFailure: (Throwable) -> Unit,
    ): Job = scope.launch {
        try {
            flow.collect { sink += it }
        } catch (e: IllegalStateException) {
            onFailure(e)
        }
    }

    @Test
    fun `dispose terminates active streaming collectors with IllegalStateException`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        var failure: Throwable? = null
        val received = CopyOnWriteArrayList<DataState<Result>>()
        val collector = f.collectCatching(f.watch("a"), received) { failure = it }
        runCurrent()
        assertEquals(DataState.Success(Result("watching a")), received.last())

        f.manager.dispose()
        runCurrent()
        assertTrue(collector.isCompleted, "collector must not hang after dispose")
        val error = assertInstanceOf(IllegalStateException::class.java, failure)
        assertTrue(error.message.orEmpty().contains("disposed"), error.message)
        assertEquals(1, probe.stopped.get())
    }

    @Test
    fun `a collector of a completed one-shot job keeps collecting until dispose`() = runTest {
        val f = fixture(registry())
        var failure: Throwable? = null
        val received = CopyOnWriteArrayList<DataState<Result>>()
        val collector = f.collectCatching(f.fetch("a"), received) { failure = it }
        advanceUntilIdle()
        assertEquals(listOf(DataState.Loading, DataState.Success(Result("fetched a"))), received.toList())
        assertFalse(collector.isCompleted, "normal completion does not terminate collectors")

        f.manager.dispose()
        runCurrent()
        assertTrue(collector.isCompleted)
        assertInstanceOf(IllegalStateException::class.java, failure)
    }

    @Test
    fun `a late collector of a completed one-shot flow receives the replayed final state`() = runTest {
        val probe = Probe()
        val f = fixture(registry(probe))
        val flow = f.fetch("a")
        f.collectInto(flow)
        advanceUntilIdle()
        assertEquals(0, f.manager.activeJobCount)

        assertEquals(DataState.Success(Result("fetched a")), flow.first())
        assertEquals(1, probe.instances.get(), "replayed, not re-requested")
    }

    @Test
    fun `collecting a completed one-shot flow after dispose fails with IllegalStateException`() = runTest {
        val f = fixture(registry())
        val completed = f.fetch("done")
        f.collectCatching(completed) { }
        advanceUntilIdle()
        f.manager.dispose()
        runCurrent()

        var failure: Throwable? = null
        val collector = f.collectCatching(completed) { failure = it }
        runCurrent()
        assertTrue(collector.isCompleted)
        assertInstanceOf(IllegalStateException::class.java, failure)
    }

    @Test
    fun `parent scope cancellation terminates collectors living in another scope`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val parent = CoroutineScope(SupervisorJob() + dispatcher)
        val f = fixture(registry())
        val manager = ProviderManager(registry(), parent, dispatcher)

        var failure: Throwable? = null
        val collector = f.scope.launch {
            try {
                manager.request(Watch::class, Result::class, Watch("a"), f.switchboard).collect { }
            } catch (e: IllegalStateException) {
                failure = e
            }
        }
        runCurrent()
        assertEquals(1, manager.activeJobCount)

        parent.cancel()
        runCurrent()
        assertTrue(collector.isCompleted, "collector must not hang after its job's parent is cancelled")
        assertInstanceOf(IllegalStateException::class.java, failure)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Binary compatibility
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `legacy four-argument constructor JVM signatures are preserved`() {
        val cls = ProviderManager::class.java
        val int = Int::class.javaPrimitiveType
        cls.getConstructor(
            ProviderRegistry::class.java, CoroutineScope::class.java, CoroutineContext::class.java, int,
        )
        // The synthetic `$default` constructor that Kotlin callers compiled against 1.x invoke.
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        cls.getDeclaredConstructor(
            ProviderRegistry::class.java, CoroutineScope::class.java, CoroutineContext::class.java, int, int, marker,
        )
    }
}
