@file:OptIn(ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package com.synapselib.arch.base

import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

class SwitchBoardObserverTest {

    data class UserState(val name: String)
    data class Toast(val message: String)
    data class Answer(val value: String)
    data class Ask(val q: String) : DataImpulse<Answer>()

    class AskProvider : Provider<Ask, Answer>() {
        override fun ProviderScope.produce(impulse: Ask): Flow<Answer> = flow { emit(Answer("re:${impulse.q}")) }
    }

    data class Seen(val point: InterceptPoint, val clazz: KClass<*>, val data: Any, val trace: TraceContext?)

    private fun TestScope.newBoard(
        extra: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
    ): DefaultSwitchBoard {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val registry = ProviderRegistry.Builder().register<Answer, Ask> { AskProvider() }.build()
        return DefaultSwitchBoard(
            CoroutineScope(dispatcher + SupervisorJob() + extra),
            registry,
            SwitchBoardConfig { workerContext = dispatcher },
        )
    }

    // ── Basic delivery ──────────────────────────────────────────────────

    @Test
    fun `observer sees upstream state after interception`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        board.addInterceptor(InterceptPoint.STATE_UPSTREAM, Interceptor.transform<UserState> { it.copy(name = it.name.uppercase()) })
        val seen = CopyOnWriteArrayList<Seen>()
        board.addObserver { p, c, d, t -> seen += Seen(p, c, d, t) }

        board.broadcastState(UserState::class, UserState("alice"))

        assertEquals(listOf(Seen(InterceptPoint.STATE_UPSTREAM, UserState::class, UserState("ALICE"), null)), seen)
        board.close()
    }

    @Test
    fun `observer sees both directions of state and reaction channels`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val points = CopyOnWriteArrayList<InterceptPoint>()
        board.addObserver { p, _, _, _ -> points += p }

        val jobs = listOf(
            launch(UnconfinedTestDispatcher(testScheduler)) { board.stateFlow(UserState::class).collect { } },
            launch(UnconfinedTestDispatcher(testScheduler)) { board.impulseFlow(Toast::class).collect { } },
        )
        board.broadcastState(UserState::class, UserState("a"))
        board.triggerImpulse(Toast::class, Toast("t"))
        advanceUntilIdle()

        assertEquals(
            setOf(
                InterceptPoint.STATE_UPSTREAM, InterceptPoint.STATE_DOWNSTREAM,
                InterceptPoint.REACTION_UPSTREAM, InterceptPoint.REACTION_DOWNSTREAM,
            ),
            points.toSet(),
        )
        assertEquals(4, points.size)
        jobs.forEach { it.cancel() }
        board.close()
    }

    @Test
    fun `observer sees request upstream and downstream`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val seen = CopyOnWriteArrayList<Seen>()
        board.addObserver { p, c, d, t -> seen += Seen(p, c, d, t) }

        val results = CopyOnWriteArrayList<DataState<Answer>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.handleRequest(Ask::class, Answer::class, Ask("x")).collect { results += it }
        }
        advanceUntilIdle()

        assertTrue(results.any { it is DataState.Success })
        assertEquals(Seen(InterceptPoint.REQUEST_UPSTREAM, Ask::class, Ask("x"), null), seen.first())
        assertTrue(seen.any { it.point == InterceptPoint.REQUEST_DOWNSTREAM && it.data == Answer("re:x") && it.clazz == Answer::class })
        job.cancel()
        board.close()
    }

    // ── Multiple observers & registration ───────────────────────────────

    @Test
    fun `multiple observers are invoked in registration order`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val order = CopyOnWriteArrayList<String>()
        board.addObserver { _, _, _, _ -> order += "A" }
        board.addObserver { _, _, _, _ -> order += "B" }
        board.addObserver { _, _, _, _ -> order += "C" }

        board.triggerImpulse(Toast::class, Toast("x"))

        assertEquals(listOf("A", "B", "C"), order)
        board.close()
    }

    @Test
    fun `unregister removes only that observer and is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val order = CopyOnWriteArrayList<String>()
        val observer = SwitchBoardObserver { _, _, _, _ -> order += "shared" }
        val regA = board.addObserver(observer)
        board.addObserver(observer) // same instance twice → two independent registrations
        board.addObserver { _, _, _, _ -> order += "other" }

        regA.unregister()
        regA.unregister()
        regA.unregister()
        board.triggerImpulse(Toast::class, Toast("x"))

        assertEquals(listOf("shared", "other"), order)
        board.close()
    }

    @Test
    fun `observer can be removed and re-added`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val count = CopyOnWriteArrayList<Int>()
        val observer = SwitchBoardObserver { _, _, _, _ -> count += 1 }

        board.addObserver(observer).unregister()
        board.triggerImpulse(Toast::class, Toast("ignored"))
        board.addObserver(observer)
        board.triggerImpulse(Toast::class, Toast("seen"))

        assertEquals(1, count.size)
        board.close()
    }

    @Test
    fun `observer exception propagates to the emitter`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        board.addObserver { _, _, _, _ -> throw IllegalArgumentException("boom") }

        val result = runCatching { board.broadcastState(UserState::class, UserState("x")) }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        board.close()
    }

    // ── Tracing ─────────────────────────────────────────────────────────

    @Test
    fun `observer receives the TraceContext when present`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val traces = CopyOnWriteArrayList<TraceContext?>()
        board.addObserver { _, _, _, t -> traces += t }

        val trace = TraceContext(emitterTag = "Emitter")
        withContext(trace) { board.broadcastState(UserState::class, UserState("a")) }
        board.broadcastState(UserState::class, UserState("b"))

        assertEquals(2, traces.size)
        assertSame(trace, traces[0])
        assertNull(traces[1])
        board.close()
    }

    @Test
    fun `request upstream observes the collector's trace`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val traces = CopyOnWriteArrayList<Pair<InterceptPoint, String?>>()
        board.addObserver { p, _, _, t -> traces += p to t?.emitterTag }

        val job = launch(UnconfinedTestDispatcher(testScheduler) + TraceContext(emitterTag = "Requester")) {
            board.handleRequest(Ask::class, Answer::class, Ask("q")).collect { }
        }
        advanceUntilIdle()

        assertEquals(InterceptPoint.REQUEST_UPSTREAM to "Requester", traces.first())
        job.cancel()
        board.close()
    }

    // ── Legacy shims ────────────────────────────────────────────────────

    @Test
    fun `legacy logger and trace listener coexist with observers and run first`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val order = CopyOnWriteArrayList<String>()
        board.addObserver { _, _, _, _ -> order += "observer" }
        board.setTraceListener { _, _, _, _ -> order += "trace" }
        board.setGlobalLogger { _, _, _ -> order += "logger" }

        withContext(TraceContext(emitterTag = "T")) { board.triggerImpulse(Toast::class, Toast("x")) }

        assertEquals(listOf("logger", "trace", "observer"), order)
        board.close()
    }

    @Test
    fun `setGlobalLogger replaces the previous logger and null removes it`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val calls = CopyOnWriteArrayList<String>()
        board.addObserver { _, _, _, _ -> calls += "observer" }

        board.setGlobalLogger { _, _, _ -> calls += "first" }
        board.setGlobalLogger { _, _, _ -> calls += "second" }
        board.triggerImpulse(Toast::class, Toast("1"))
        board.setGlobalLogger(null)
        board.triggerImpulse(Toast::class, Toast("2"))

        assertEquals(listOf("second", "observer", "observer"), calls)
        board.close()
    }

    @Test
    fun `setTraceListener only fires with a trace and null removes it`() = runTest(UnconfinedTestDispatcher()) {
        val board = newBoard()
        val tags = CopyOnWriteArrayList<String?>()
        board.setTraceListener { trace, _, _, _ -> tags += trace.emitterTag }

        board.triggerImpulse(Toast::class, Toast("untraced"))
        withContext(TraceContext(emitterTag = "A")) { board.triggerImpulse(Toast::class, Toast("traced")) }
        board.setTraceListener(null)
        withContext(TraceContext(emitterTag = "B")) { board.triggerImpulse(Toast::class, Toast("after")) }

        assertEquals(listOf<String?>("A"), tags)
        board.close()
    }

    // ── Delivery failures ───────────────────────────────────────────────

    @Test
    fun `failing downstream interceptor drops the value but keeps the channel alive`() = runTest(UnconfinedTestDispatcher()) {
        val handled = CopyOnWriteArrayList<Throwable>()
        val board = newBoard(CoroutineExceptionHandler { _, e -> handled += e })
        val failures = CopyOnWriteArrayList<Pair<InterceptPoint, Any>>()
        board.addObserver(object : SwitchBoardObserver {
            override fun onData(point: InterceptPoint, clazz: KClass<*>, data: Any, trace: TraceContext?) = Unit
            override fun onDeliveryFailure(point: InterceptPoint, clazz: KClass<*>, data: Any, error: Throwable) {
                failures += point to data
            }
        })
        board.addInterceptor(InterceptPoint.STATE_DOWNSTREAM, Interceptor.read<UserState> {
            if (it.name == "bad") throw IllegalStateException("rejected")
        })

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received += it }
        }
        board.broadcastState(UserState::class, UserState("good"))
        board.broadcastState(UserState::class, UserState("bad"))
        board.broadcastState(UserState::class, UserState("after"))
        advanceUntilIdle()

        assertEquals(listOf(UserState("good"), UserState("after")), received)
        assertEquals(listOf<Pair<InterceptPoint, Any>>(InterceptPoint.STATE_DOWNSTREAM to UserState("bad")), failures)
        assertEquals(1, handled.size)
        assertTrue(handled[0] is IllegalStateException)
        job.cancel()
        board.close()
    }

    @Test
    fun `failing downstream reaction does not cancel a plain Job caller scope`() = runTest(UnconfinedTestDispatcher()) {
        val handled = CopyOnWriteArrayList<Throwable>()
        val callerJob = Job()
        val board = DefaultSwitchBoard(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + callerJob + CoroutineExceptionHandler { _, e -> handled += e }),
            ProviderRegistry.EMPTY,
        )
        board.addInterceptor(InterceptPoint.REACTION_DOWNSTREAM, Interceptor.read<Toast> {
            if (it.message == "bad") throw IllegalStateException("rejected")
        })
        val received = CopyOnWriteArrayList<Toast>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(Toast::class).collect { received += it }
        }

        board.triggerImpulse(Toast::class, Toast("bad"))
        board.triggerImpulse(Toast::class, Toast("ok"))
        advanceUntilIdle()

        assertEquals(listOf(Toast("ok")), received)
        assertTrue(callerJob.isActive)
        assertEquals(1, handled.size)
        job.cancel()
        board.close()
        callerJob.cancel()
    }

    // ── Interface evolution ─────────────────────────────────────────────

    /** A third-party implementation written against the original interface. */
    private class MinimalFakeSwitchBoard : SwitchBoard {
        private val raw = MutableSharedFlow<Any>()
        override suspend fun <T : Any> broadcastState(clazz: KClass<T>, data: T) = Unit
        override suspend fun <T : Any> triggerImpulse(clazz: KClass<T>, data: T) = Unit
        override fun <Need : Any, I : DataImpulse<Need>> handleRequest(
            impulseType: KClass<I>, needType: KClass<Need>, impulse: I,
        ): Flow<DataState<Need>> = emptyFlow()
        override fun <T : Any> stateFlow(clazz: KClass<T>): SharedFlow<T> = MutableSharedFlow()
        override fun <T : Any> impulseFlow(clazz: KClass<T>): SharedFlow<T> = MutableSharedFlow()
        override fun getRawStateFlow(clazz: KClass<*>): SharedFlow<Any> = raw
        override fun getRawImpulseFlow(clazz: KClass<*>): SharedFlow<Any> = raw
        override fun <T : Any> addInterceptor(
            point: InterceptPoint, clazz: KClass<T>, interceptor: Interceptor<T>, priority: Int,
        ): Registration = Registration { }
    }

    @Test
    fun `implementations without observer support compile and fail clearly`() {
        val fake: SwitchBoard = MinimalFakeSwitchBoard()
        assertThrows(UnsupportedOperationException::class.java) {
            fake.addObserver { _, _, _, _ -> }
        }
    }
}
