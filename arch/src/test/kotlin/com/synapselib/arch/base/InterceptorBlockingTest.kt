@file:OptIn(ExperimentalCoroutinesApi::class)

package com.synapselib.arch.base

import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * "The terminal is the dispatch": an interceptor that does not call `proceed`
 * blocks the value at its [InterceptPoint] — nothing is emitted, routed to a
 * provider or delivered — while values that reach the end of the chain are
 * dispatched as the value the chain returned, exactly as before.
 */
class InterceptorBlockingTest {

    // ── Fixtures ────────────────────────────────────────────────────────

    /** Marker for requests that need a logged-in session (docs' auth gate). */
    interface RequiresAuth

    data class Profile(val name: String)
    data class FetchProfile(val userId: Int) : DataImpulse<Profile>(), RequiresAuth

    data object NavigateToLogin

    data class Order(val id: Int, val total: Int)
    data class UserState(val name: String, val attempt: Int = 0, val tag: String = "")

    data class Score(val value: Int)
    data class FetchScores(val values: List<Int>, val failAtEnd: Boolean) : DataImpulse<Score>()

    class ScoresProvider : Provider<FetchScores, Score>() {
        override fun ProviderScope.produce(impulse: FetchScores): Flow<Score> = flow {
            impulse.values.forEach { emit(Score(it)) }
            if (impulse.failAtEnd) throw IllegalStateException("boom")
        }
    }

    private companion object {
        const val VIP = 100
    }

    private class Fixture(
        val board: DefaultSwitchBoard,
        val profileProvidersCreated: AtomicInteger,
        val profileProduceCalls: AtomicInteger,
    )

    private fun TestScope.fixture(
        maxConcurrentJobs: Int = 0,
        configure: SwitchBoardConfig.Builder.() -> Unit = {},
    ): Fixture {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val created = AtomicInteger(0)
        val produced = AtomicInteger(0)
        val registry = ProviderRegistry.Builder()
            .register<Profile, FetchProfile> {
                created.incrementAndGet()
                object : Provider<FetchProfile, Profile>() {
                    override fun ProviderScope.produce(impulse: FetchProfile): Flow<Profile> = flow {
                        produced.incrementAndGet()
                        emit(Profile("user-${impulse.userId}"))
                    }
                }
            }
            .register<Score, FetchScores> { ScoresProvider() }
            .build()
        val board = DefaultSwitchBoard(
            CoroutineScope(dispatcher + SupervisorJob()),
            registry,
            SwitchBoardConfig {
                workerContext = dispatcher
                this.maxConcurrentJobs = maxConcurrentJobs
                configure()
            },
        )
        return Fixture(board, created, produced)
    }

    /** The auth gate from the README, verbatim modulo the session source. */
    private fun SwitchBoard.installAuthGate(sessionValid: () -> Boolean): Registration =
        addInterceptor<RequiresAuth>(
            InterceptPoint.REQUEST_UPSTREAM,
            Interceptor.full { impulse, proceed ->
                if (sessionValid()) proceed(impulse) else {
                    triggerImpulse(NavigateToLogin::class, NavigateToLogin)
                    impulse
                }
            },
        )

    /** The VIP downstream filter from the README. */
    private fun vipFilter(): Interceptor<Order> =
        Interceptor.full { order, proceed -> if (order.total >= VIP) proceed(order) else order }

    // ── (a) REQUEST / UPSTREAM: the auth gate ───────────────────────────

    @Test
    fun `auth gate without a session never runs the provider and stalls in Loading`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = fixture()
            f.board.installAuthGate { false }
            val navigations = CopyOnWriteArrayList<NavigateToLogin>()
            val navJob = launch { f.board.impulseFlow(NavigateToLogin::class).collect { navigations += it } }

            val states = CopyOnWriteArrayList<DataState<Profile>>()
            val job = launch {
                f.board.handleRequest(FetchProfile::class, Profile::class, FetchProfile(1)).collect { states += it }
            }
            advanceUntilIdle()

            assertEquals(listOf<DataState<Profile>>(DataState.Loading), states)
            assertEquals(0, f.profileProvidersCreated.get(), "no provider may be created for a blocked request")
            assertEquals(0, f.profileProduceCalls.get())
            assertEquals(listOf(NavigateToLogin), navigations)
            assertTrue(job.isActive, "the blocked request stalls in Loading until its collector cancels")

            job.cancel()
            advanceUntilIdle()
            assertTrue(job.isCancelled && job.isCompleted, "cancelling the collector ends the stalled request")
            assertEquals(listOf<DataState<Profile>>(DataState.Loading), states)

            navJob.cancel()
            f.board.close()
        }

    @Test
    fun `auth gate with a valid session proceeds normally`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        f.board.installAuthGate { true }
        val navigations = CopyOnWriteArrayList<NavigateToLogin>()
        val navJob = launch { f.board.impulseFlow(NavigateToLogin::class).collect { navigations += it } }

        val states = CopyOnWriteArrayList<DataState<Profile>>()
        val job = launch {
            f.board.handleRequest(FetchProfile::class, Profile::class, FetchProfile(7)).collect { states += it }
        }
        advanceUntilIdle()

        assertEquals(listOf(DataState.Loading, DataState.Success(Profile("user-7"))), states)
        assertEquals(1, f.profileProvidersCreated.get())
        assertTrue(navigations.isEmpty())

        job.cancel()
        navJob.cancel()
        f.board.close()
    }

    @Test
    fun `a request blocked earlier leaves no dedup entry behind`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        var sessionValid = false
        f.board.installAuthGate { sessionValid }

        val blocked = CopyOnWriteArrayList<DataState<Profile>>()
        val blockedJob = launch {
            f.board.handleRequest(FetchProfile::class, Profile::class, FetchProfile(3)).collect { blocked += it }
        }
        advanceUntilIdle()

        sessionValid = true
        val allowed = CopyOnWriteArrayList<DataState<Profile>>()
        val allowedJob = launch {
            f.board.handleRequest(FetchProfile::class, Profile::class, FetchProfile(3)).collect { allowed += it }
        }
        advanceUntilIdle()

        assertEquals(listOf<DataState<Profile>>(DataState.Loading), blocked, "the blocked collector never sees data")
        assertEquals(listOf(DataState.Loading, DataState.Success(Profile("user-3"))), allowed)
        assertEquals(1, f.profileProvidersCreated.get())
        assertEquals(1, f.profileProduceCalls.get())

        blockedJob.cancel()
        allowedJob.cancel()
        f.board.close()
    }

    @Test
    fun `blocked requests do not count against the provider concurrency gate`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = fixture(maxConcurrentJobs = 1)
            f.board.installAuthGate { false }
            val stalled = (1..3).map { id ->
                launch { f.board.handleRequest(FetchProfile::class, Profile::class, FetchProfile(id)).collect {} }
            }
            advanceUntilIdle()

            // FetchScores is not RequiresAuth, so the gate lets it through.
            val scores = CopyOnWriteArrayList<DataState<Score>>()
            val job = launch {
                f.board.handleRequest(FetchScores::class, Score::class, FetchScores(listOf(1), failAtEnd = false))
                    .collect { scores += it }
            }
            advanceUntilIdle()

            assertEquals(listOf(DataState.Loading, DataState.Success(Score(1))), scores)
            assertTrue(stalled.all { it.isActive })

            stalled.forEach(Job::cancel)
            job.cancel()
            f.board.close()
        }

    @Test
    fun `a stalled blocked request completes normally when the board is closed`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = fixture()
            f.board.installAuthGate { false }
            val states = CopyOnWriteArrayList<DataState<Profile>>()
            var failure: Throwable? = null
            val job = launch {
                try {
                    f.board.handleRequest(FetchProfile::class, Profile::class, FetchProfile(1)).collect { states += it }
                } catch (t: Throwable) {
                    failure = t
                }
            }
            advanceUntilIdle()
            assertTrue(job.isActive)

            f.board.close()
            advanceUntilIdle()

            assertTrue(job.isCompleted && !job.isCancelled, "the collection completes normally on close")
            assertEquals(null, failure)
            assertEquals(listOf<DataState<Profile>>(DataState.Loading), states)
        }

    // ── (b) STATE / REACTION UPSTREAM gates ─────────────────────────────

    @Test
    fun `STATE_UPSTREAM gate blocks emission and the state keeps its previous value`() =
        runTest(UnconfinedTestDispatcher()) {
            val board = fixture().board
            board.addInterceptor<UserState>(
                InterceptPoint.STATE_UPSTREAM,
                Interceptor.full { state, proceed -> if (state.name != "blocked") proceed(state) else state.copy(tag = "x") },
            )
            val received = CopyOnWriteArrayList<UserState>()
            val job = launch { board.stateFlow(UserState::class).collect { received += it } }

            board.broadcastState(UserState::class, UserState("a"))
            board.broadcastState(UserState::class, UserState("blocked"))
            board.broadcastState(UserState::class, UserState("b"))
            board.broadcastState(UserState::class, UserState("blocked"))
            advanceUntilIdle()

            assertEquals(listOf(UserState("a"), UserState("b")), received)
            assertEquals(listOf<Any>(UserState("b")), board.getRawStateFlow(UserState::class).replayCache)

            job.cancel()
            board.close()
        }

    @Test
    fun `STATE_UPSTREAM gate blocking the first value leaves the state empty`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.addInterceptor<UserState>(InterceptPoint.STATE_UPSTREAM, Interceptor { data, _ -> data })

        board.broadcastState(UserState::class, UserState("never"))
        advanceUntilIdle()

        assertTrue(board.getRawStateFlow(UserState::class).replayCache.isEmpty())
        board.close()
    }

    @Test
    fun `REACTION_UPSTREAM gate blocks emission`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.addInterceptor<Order>(
            InterceptPoint.REACTION_UPSTREAM,
            Interceptor.full { order, proceed -> if (order.id % 2 == 0) proceed(order) else order },
        )
        val delivered = CopyOnWriteArrayList<Order>()
        val raw = CopyOnWriteArrayList<Any>()
        val job = launch { board.impulseFlow(Order::class).collect { delivered += it } }
        val rawJob = launch { board.getRawImpulseFlow(Order::class).collect { raw += it } }

        (1..6).forEach { board.triggerImpulse(Order::class, Order(it, total = it)) }
        advanceUntilIdle()

        val expected = listOf(Order(2, 2), Order(4, 4), Order(6, 6))
        assertEquals(expected, delivered)
        assertEquals(expected, raw, "blocked reactions never reach the raw flow either")

        job.cancel()
        rawJob.cancel()
        board.close()
    }

    // ── (c) STATE / REACTION DOWNSTREAM filters ─────────────────────────

    @Test
    fun `REACTION_DOWNSTREAM VIP filter delivers only passing values`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.addInterceptor(InterceptPoint.REACTION_DOWNSTREAM, vipFilter())
        val delivered = CopyOnWriteArrayList<Order>()
        val job = launch { board.impulseFlow(Order::class).collect { delivered += it } }

        listOf(50, 150, 99, 100, 20, 200).forEachIndexed { i, total -> board.triggerImpulse(Order::class, Order(i, total)) }
        advanceUntilIdle()

        assertEquals(listOf(Order(1, 150), Order(3, 100), Order(5, 200)), delivered)

        job.cancel()
        board.close()
    }

    @Test
    fun `STATE_DOWNSTREAM VIP filter delivers only passing values and replays the last passing one`() =
        runTest(UnconfinedTestDispatcher()) {
            val board = fixture().board
            board.addInterceptor(InterceptPoint.STATE_DOWNSTREAM, vipFilter())
            val first = CopyOnWriteArrayList<Order>()
            val firstJob = launch { board.stateFlow(Order::class).collect { first += it } }

            board.broadcastState(Order::class, Order(1, 150))
            board.broadcastState(Order::class, Order(2, 50))
            board.broadcastState(Order::class, Order(3, 99))
            advanceUntilIdle()

            assertEquals(listOf(Order(1, 150)), first)
            assertEquals(listOf(Order(1, 150)), board.stateFlow(Order::class).replayCache)

            // A late listener gets the last value that passed, not the blocked ones.
            val late = CopyOnWriteArrayList<Order>()
            val lateJob = launch { board.stateFlow(Order::class).collect { late += it } }
            advanceUntilIdle()
            assertEquals(listOf(Order(1, 150)), late)

            // Upstream was not blocked: the raw state holds the latest broadcast.
            assertEquals(listOf<Any>(Order(3, 99)), board.getRawStateFlow(Order::class).replayCache)

            board.broadcastState(Order::class, Order(4, 300))
            advanceUntilIdle()
            assertEquals(listOf(Order(1, 150), Order(4, 300)), first)
            assertEquals(listOf(Order(1, 150), Order(4, 300)), late)

            firstJob.cancel()
            lateJob.cancel()
            board.close()
        }

    @Test
    fun `STATE_DOWNSTREAM filter blocking the first value delivers nothing`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.addInterceptor(InterceptPoint.STATE_DOWNSTREAM, vipFilter())
        val received = CopyOnWriteArrayList<Order>()
        val job = launch { board.stateFlow(Order::class).collect { received += it } }

        board.broadcastState(Order::class, Order(1, 10))
        advanceUntilIdle()

        assertTrue(received.isEmpty())
        assertTrue(board.stateFlow(Order::class).replayCache.isEmpty())

        job.cancel()
        board.close()
    }

    // ── (d) REQUEST / DOWNSTREAM filter ─────────────────────────────────

    private fun SwitchBoard.installScoreFilter(): Registration =
        addInterceptor<Score>(
            InterceptPoint.REQUEST_DOWNSTREAM,
            Interceptor.full { score, proceed -> if (score.value >= 10) proceed(score) else score.copy(value = -1) },
        )

    private suspend fun TestScope.collectScores(board: SwitchBoard, impulse: FetchScores): List<DataState<Score>> {
        val states = CopyOnWriteArrayList<DataState<Score>>()
        val job = launch { board.handleRequest(FetchScores::class, Score::class, impulse).collect { states += it } }
        advanceUntilIdle()
        job.cancel()
        return states
    }

    @Test
    fun `REQUEST_DOWNSTREAM filter drops blocked Success values`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.installScoreFilter()

        val states = collectScores(board, FetchScores(listOf(5, 50, 7, 70), failAtEnd = false))

        assertEquals(listOf(DataState.Loading, DataState.Success(Score(50)), DataState.Success(Score(70))), states)
        board.close()
    }

    @Test
    fun `REQUEST_DOWNSTREAM filter strips blocked staleData from an Error`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.installScoreFilter()

        val states = collectScores(board, FetchScores(listOf(5, 50, 7), failAtEnd = true))

        assertEquals(3, states.size, "states: $states")
        assertEquals(DataState.Loading, states[0])
        assertEquals(DataState.Success(Score(50)), states[1])
        val error = states[2] as DataState.Error
        assertEquals("boom", error.cause.message)
        assertEquals(null, error.staleData, "the last success (7) was blocked, so no stale payload is delivered")
        board.close()
    }

    @Test
    fun `REQUEST_DOWNSTREAM filter keeps passing staleData on an Error`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.installScoreFilter()

        val states = collectScores(board, FetchScores(listOf(5, 50), failAtEnd = true))

        assertEquals(DataState.Success(Score(50)), states[1])
        val error = states.last() as DataState.Error
        assertEquals(Score(50), error.staleData)
        board.close()
    }

    // ── (e) nested ──────────────────────────────────────────────────────

    @Test
    fun `outer interceptor proceeds but an inner one blocks - the value is blocked`() =
        runTest(UnconfinedTestDispatcher()) {
            val board = fixture().board
            val outerSaw = CopyOnWriteArrayList<UserState>()
            board.addInterceptor<UserState>(
                InterceptPoint.STATE_UPSTREAM,
                Interceptor.full { state, proceed -> proceed(state).also { outerSaw += it }.copy(tag = "outer") },
                priority = 0,
            )
            board.addInterceptor<UserState>(
                InterceptPoint.STATE_UPSTREAM,
                Interceptor { state, _ -> state.copy(tag = "inner-substitute") },
                priority = 10,
            )
            val laterRan = AtomicInteger(0)
            board.addInterceptor<UserState>(
                InterceptPoint.STATE_UPSTREAM,
                Interceptor.read { laterRan.incrementAndGet() },
                priority = 20,
            )

            board.broadcastState(UserState::class, UserState("x"))
            advanceUntilIdle()

            assertTrue(board.getRawStateFlow(UserState::class).replayCache.isEmpty())
            assertEquals(0, laterRan.get(), "interceptors after the blocking one never run")
            // The outer interceptor still sees what the inner one returned.
            assertEquals(listOf(UserState("x", tag = "inner-substitute")), outerSaw)
            board.close()
        }

    // ── (f) retry and post-processing ───────────────────────────────────

    @Test
    fun `proceeding twice dispatches once with the returned value`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        val terminalHits = AtomicInteger(0)
        board.addInterceptor<UserState>(
            InterceptPoint.STATE_UPSTREAM,
            Interceptor.full { state, proceed ->
                proceed(state)
                proceed(state.copy(attempt = 2))
            },
            priority = 0,
        )
        board.addInterceptor<UserState>(
            InterceptPoint.STATE_UPSTREAM,
            Interceptor.read { terminalHits.incrementAndGet() },
            priority = 10,
        )
        val received = CopyOnWriteArrayList<UserState>()
        val job = launch { board.stateFlow(UserState::class).collect { received += it } }

        board.broadcastState(UserState::class, UserState("r"))
        advanceUntilIdle()

        assertEquals(2, terminalHits.get())
        assertEquals(listOf(UserState("r", attempt = 2)), received)

        job.cancel()
        board.close()
    }

    @Test
    fun `retry after an inner block dispatches the retried value`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.addInterceptor<UserState>(
            InterceptPoint.REACTION_UPSTREAM,
            Interceptor.full { state, proceed ->
                val first = proceed(state)
                if (first.tag == "rejected") proceed(state.copy(attempt = state.attempt + 1)) else first
            },
            priority = 0,
        )
        board.addInterceptor<UserState>(
            InterceptPoint.REACTION_UPSTREAM,
            Interceptor.full { state, proceed -> if (state.attempt > 0) proceed(state) else state.copy(tag = "rejected") },
            priority = 10,
        )
        val received = CopyOnWriteArrayList<UserState>()
        val job = launch { board.impulseFlow(UserState::class).collect { received += it } }

        board.triggerImpulse(UserState::class, UserState("r"))
        advanceUntilIdle()

        assertEquals(listOf(UserState("r", attempt = 1)), received)
        job.cancel()
        board.close()
    }

    @Test
    fun `post-processing after proceed still applies to the dispatched value`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.addInterceptor<UserState>(
            InterceptPoint.STATE_UPSTREAM,
            Interceptor.full { state, proceed -> proceed(state.copy(name = state.name + "!")).copy(tag = "post") },
        )
        val received = CopyOnWriteArrayList<UserState>()
        val job = launch { board.stateFlow(UserState::class).collect { received += it } }

        board.broadcastState(UserState::class, UserState("hi"))
        advanceUntilIdle()

        assertEquals(listOf(UserState("hi!", tag = "post")), received)
        job.cancel()
        board.close()
    }

    @Test
    fun `substituting via proceed dispatches the substitute`() = runTest(UnconfinedTestDispatcher()) {
        val board = fixture().board
        board.addInterceptor<Order>(
            InterceptPoint.REACTION_UPSTREAM,
            Interceptor.full { order, proceed -> proceed(Order(order.id, total = 0)) },
        )
        val delivered = CopyOnWriteArrayList<Order>()
        val job = launch { board.impulseFlow(Order::class).collect { delivered += it } }

        board.triggerImpulse(Order::class, Order(1, 999))
        advanceUntilIdle()

        assertEquals(listOf(Order(1, 0)), delivered)
        job.cancel()
        board.close()
    }

    // ── (g) concurrency through the board ───────────────────────────────

    @Test
    fun `concurrent producers through one gate give exact counts`() = runTest(UnconfinedTestDispatcher()) {
        // SUSPEND: no reaction may be lost to buffer overflow, so counts are exact.
        val board = fixture { reactionOverflow = BufferOverflow.SUSPEND }.board
        board.addInterceptor<Order>(
            InterceptPoint.REACTION_UPSTREAM,
            Interceptor.full { order, proceed ->
                yield() // interleave executions of the same pipeline
                if (order.id % 2 == 0) proceed(order) else order
            },
        )
        val delivered = CopyOnWriteArrayList<Order>()
        val job = launch { board.impulseFlow(Order::class).collect { delivered += it } }

        val n = 1_000
        (0 until n).map { id -> async { board.triggerImpulse(Order::class, Order(id, id)) } }.awaitAll()
        advanceUntilIdle()

        assertEquals(n / 2, delivered.size)
        assertEquals((0 until n step 2).toSet(), delivered.map { it.id }.toSet())
        assertFalse(delivered.any { it.id % 2 == 1 })

        job.cancel()
        board.close()
    }
}
