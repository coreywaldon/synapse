@file:OptIn(ExperimentalCoroutinesApi::class)

package com.synapselib.arch.base

import com.synapselib.arch.base.routing.RequestParams
import com.synapselib.arch.base.routing.Router
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

class DefaultSwitchBoardTest {

    // ── Test data types ──────────────────────────────────────────────────

    data class UserState(val name: String, val age: Int = 0)
    data class ThemeState(val dark: Boolean)
    data class ToastReaction(val message: String)
    data class AnalyticsReaction(val event: String)

    data class TestRequestParams(val query: String) : RequestParams()
    data class TestResult(val answer: String)

    // ── Fake Router ──────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private class FakeRouter : Router {
        val routedParams = CopyOnWriteArrayList<RequestParams>()
        var resultProvider: (RequestParams) -> Any? = { null }
        
        override fun <Need : Any, T : RequestParams> route(
            needType: KClass<Need>,
            params: T
        ): Flow<Need> {
            routedParams.add(params)
            return flow {
                val result = resultProvider(params)
                if (result != null) {
                    emit(result as Need)
                }
            }
        }
    }

    // ── State: broadcast & listen ────────────────────────────────────────

    @Test
    fun `broadcastState emits value that stateFlow receives`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<UserState>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("Alice", 30))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(UserState("Alice", 30), received.first())

        job.cancel()
    }

    @Test
    fun `broadcastState replays latest value to new collectors`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.broadcastState(UserState::class, UserState("Bob", 25))
        advanceUntilIdle()

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(UserState("Bob", 25), received.first())

        job.cancel()
    }

    @Test
    fun `broadcastState delivers multiple values in order`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<UserState>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("A"))
        board.broadcastState(UserState::class, UserState("B"))
        board.broadcastState(UserState::class, UserState("C"))
        advanceUntilIdle()

        assertEquals(listOf("A", "B", "C"), received.map { it.name })

        job.cancel()
    }

    @Test
    fun `reified broadcastState and stateFlow work correctly`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<UserState>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState("reified"))
        advanceUntilIdle()

        assertEquals(UserState("reified"), received.first())

        job.cancel()
    }

    // ── State: type isolation ────────────────────────────────────────────

    @Test
    fun `state channels for different types are isolated`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val users = CopyOnWriteArrayList<UserState>()
        val themes = CopyOnWriteArrayList<ThemeState>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { users.add(it) }
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(ThemeState::class).collect { themes.add(it) }
        }

        board.broadcastState(UserState::class, UserState("only-user"))
        advanceUntilIdle()

        assertEquals(1, users.size)
        assertEquals(0, themes.size)

        job1.cancel()
        job2.cancel()
    }

    // ── Reaction: trigger & listen ───────────────────────────────────────

    @Test
    fun `triggerImpulse emits value that impulseFlow receives`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<ToastReaction>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("Hello!"))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals("Hello!", received.first().message)

        job.cancel()
    }

    @Test
    fun `reaction has no replay - late collectors miss past events`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.triggerImpulse(ToastReaction::class, ToastReaction("missed"))
        advanceUntilIdle()

        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }
        advanceUntilIdle()

        assertTrue(received.isEmpty(), "Late collector should not receive past reactions")

        job.cancel()
    }

    @Test
    fun `multiple reactions delivered in order`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<ToastReaction>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("first"))
        board.triggerImpulse(ToastReaction::class, ToastReaction("second"))
        board.triggerImpulse(ToastReaction::class, ToastReaction("third"))
        advanceUntilIdle()

        assertEquals(listOf("first", "second", "third"), received.map { it.message })

        job.cancel()
    }

    @Test
    fun `reified triggerImpulse and impulseFlow work correctly`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<ToastReaction>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction("reified"))
        advanceUntilIdle()

        assertEquals("reified", received.first().message)

        job.cancel()
    }

    // ── Reaction: type isolation ─────────────────────────────────────────

    @Test
    fun `reaction channels for different types are isolated`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val toasts = CopyOnWriteArrayList<ToastReaction>()
        val analytics = CopyOnWriteArrayList<AnalyticsReaction>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { toasts.add(it) }
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(AnalyticsReaction::class).collect { analytics.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("only-toast"))
        advanceUntilIdle()

        assertEquals(1, toasts.size)
        assertEquals(0, analytics.size)

        job1.cancel()
        job2.cancel()
    }

    // ── State upstream interceptors ──────────────────────────────────────

    @Test
    fun `state upstream interceptor transforms data before emission`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = it.name.uppercase()) },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("alice"))
        advanceUntilIdle()

        assertEquals("ALICE", received.first().name)

        job.cancel()
    }

    @Test
    fun `state upstream read interceptor observes without modifying`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val observed = CopyOnWriteArrayList<UserState>()

        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.read { observed.add(it) },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        val original = UserState("observe-me", 99)
        board.broadcastState(UserState::class, original)
        advanceUntilIdle()

        assertEquals(original, observed.first())
        assertEquals(original, received.first())

        job.cancel()
    }

    // ── State downstream interceptors ────────────────────────────────────

    @Test
    fun `state downstream interceptor transforms data before delivery`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(age = it.age + 100) },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("age-test", 5))
        advanceUntilIdle()

        assertEquals(105, received.first().age)

        job.cancel()
    }

    @Test
    fun `state upstream and downstream interceptors compose correctly`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "up_${it.name}") },
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_down") },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("core"))
        advanceUntilIdle()

        assertEquals("up_core_down", received.first().name)

        job.cancel()
    }

    // ── Reaction upstream interceptors ────────────────────────────────────

    @Test
    fun `reaction upstream interceptor transforms data before emission`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
            clazz = ToastReaction::class,
            interceptor = Interceptor.transform { it.copy(message = "[!] ${it.message}") },
        )

        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("warn"))
        advanceUntilIdle()

        assertEquals("[!] warn", received.first().message)

        job.cancel()
    }

    // ── Reaction downstream interceptors ─────────────────────────────────

    @Test
    fun `reaction downstream interceptor transforms data before delivery`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.DOWNSTREAM),
            clazz = ToastReaction::class,
            interceptor = Interceptor.transform { it.copy(message = it.message.uppercase()) },
        )

        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("hello"))
        advanceUntilIdle()

        assertEquals("HELLO", received.first().message)

        job.cancel()
    }

    @Test
    fun `reaction upstream and downstream interceptors compose correctly`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
            clazz = ToastReaction::class,
            interceptor = Interceptor.transform { it.copy(message = "up_${it.message}") },
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.DOWNSTREAM),
            clazz = ToastReaction::class,
            interceptor = Interceptor.transform { it.copy(message = "${it.message}_down") },
        )

        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("core"))
        advanceUntilIdle()

        assertEquals("up_core_down", received.first().message)

        job.cancel()
    }

    // ── Interceptor priority ─────────────────────────────────────────────

    @Test
    fun `interceptors execute in priority order within a given point`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_second") },
            priority = 10,
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_first") },
            priority = 0,
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("base"))
        advanceUntilIdle()

        assertEquals("base_first_second", received.first().name)

        job.cancel()
    }

    // ── Interceptor unregistration ───────────────────────────────────────

    @Test
    fun `unregistered interceptor no longer applies`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val reg = board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "INTERCEPTED") },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("before"))
        advanceUntilIdle()
        assertEquals("INTERCEPTED", received.last().name)

        reg.unregister()

        board.broadcastState(UserState::class, UserState("after"))
        advanceUntilIdle()
        assertEquals("after", received.last().name)

        job.cancel()
    }

    // ── Reified addInterceptor ───────────────────────────────────────────

    @Test
    fun `reified addInterceptor extension works correctly`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            interceptor = Interceptor.transform<UserState> { it.copy(name = "reified!") },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("test"))
        advanceUntilIdle()

        assertEquals("reified!", received.first().name)

        job.cancel()
    }

    // ── InterceptPoint isolation ─────────────────────────────────────────

    @Test
    fun `interceptors at different points do not interfere`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val upstreamHits = AtomicInteger(0)
        val downstreamHits = AtomicInteger(0)

        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.read { upstreamHits.incrementAndGet() },
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.DOWNSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.read { downstreamHits.incrementAndGet() },
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { }
        }

        board.broadcastState(UserState::class, UserState("point-test"))
        advanceUntilIdle()

        assertEquals(1, upstreamHits.get(), "Only STATE/UPSTREAM should fire")
        assertEquals(0, downstreamHits.get(), "REACTION/DOWNSTREAM should not fire")

        job.cancel()
    }

    // ── Raw flow access ──────────────────────────────────────────────────

    @Test
    fun `getRawStateFlow returns a flow that receives broadcast values`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val flow = board.getRawStateFlow(UserState::class)

        val received = CopyOnWriteArrayList<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("raw-state"))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(UserState("raw-state"), received.first())

        job.cancel()
    }

    @Test
    fun `getRawStateFlow bypasses downstream interceptors`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "INTERCEPTED") },
        )

        val flow = board.getRawStateFlow(UserState::class)

        val received = CopyOnWriteArrayList<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("raw"))
        advanceUntilIdle()

        assertEquals(UserState("raw"), received.first())

        job.cancel()
    }

    @Test
    fun `getRawStateFlow still receives upstream-intercepted values`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = it.name.uppercase()) },
        )

        val flow = board.getRawStateFlow(UserState::class)

        val received = CopyOnWriteArrayList<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("hello"))
        advanceUntilIdle()

        assertEquals(UserState("HELLO"), received.first())

        job.cancel()
    }

    @Test
    fun `getRawImpulseFlow returns a flow that receives triggered reactions`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val flow = board.getRawImpulseFlow(ToastReaction::class)

        val received = CopyOnWriteArrayList<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("raw-reaction"))
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(ToastReaction("raw-reaction"), received.first())

        job.cancel()
    }

    @Test
    fun `getRawImpulseFlow bypasses downstream interceptors`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.DOWNSTREAM),
            clazz = ToastReaction::class,
            interceptor = Interceptor.transform { it.copy(message = "INTERCEPTED") },
        )

        val flow = board.getRawImpulseFlow(ToastReaction::class)

        val received = CopyOnWriteArrayList<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("raw"))
        advanceUntilIdle()

        assertEquals(ToastReaction("raw"), received.first())

        job.cancel()
    }

    // ── Request channel ──────────────────────────────────────────────────

    @Test
    fun `handleRequest routes params through the router`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRouter = FakeRouter()
        fakeRouter.resultProvider = { params ->
            TestResult("answer for ${(params as TestRequestParams).query}")
        }
        val boardWithRouter = DefaultSwitchBoard(router = fakeRouter, scope = backgroundScope)

        val received = CopyOnWriteArrayList<TestResult>()
        val resultFlow = boardWithRouter.handleRequest(
            needClazz = TestResult::class,
            params = TestRequestParams("question"),
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            resultFlow.collect { received.add(it) }
        }
        advanceUntilIdle()

        assertEquals(1, fakeRouter.routedParams.size)
        assertEquals(TestRequestParams("question"), fakeRouter.routedParams.first())
        assertEquals(TestResult("answer for question"), received.first())

        job.cancel()
    }

    @Test
    fun `request upstream interceptor transforms params before routing`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRouter = FakeRouter()
        fakeRouter.resultProvider = { TestResult("ok") }
        val boardWithRouter = DefaultSwitchBoard(router = fakeRouter, scope = backgroundScope)

        boardWithRouter.addInterceptor(
            point = InterceptPoint(Channel.REQUEST, Direction.UPSTREAM),
            clazz = TestRequestParams::class,
            interceptor = Interceptor.transform { it.copy(query = it.query.uppercase()) },
        )

        val resultFlow = boardWithRouter.handleRequest(
            needClazz = TestResult::class,
            params = TestRequestParams("hello"),
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            resultFlow.collect { }
        }
        advanceUntilIdle()

        assertEquals("HELLO", (fakeRouter.routedParams.first() as TestRequestParams).query)

        job.cancel()
    }

    // ── Multiple listeners ───────────────────────────────────────────────

    @Test
    fun `multiple collectors on same state channel all receive broadcasts`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received1 = CopyOnWriteArrayList<UserState>()
        val received2 = CopyOnWriteArrayList<UserState>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received1.add(it) }
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received2.add(it) }
        }

        board.broadcastState(UserState::class, UserState("shared"))
        advanceUntilIdle()

        assertEquals(1, received1.size)
        assertEquals(1, received2.size)
        assertEquals(UserState("shared"), received1.first())
        assertEquals(UserState("shared"), received2.first())

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `multiple collectors on same reaction channel all receive events`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received1 = CopyOnWriteArrayList<ToastReaction>()
        val received2 = CopyOnWriteArrayList<ToastReaction>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received1.add(it) }
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received2.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("both"))
        advanceUntilIdle()

        assertEquals(1, received1.size)
        assertEquals(1, received2.size)

        job1.cancel()
        job2.cancel()
    }

    // ── Cancellation ─────────────────────────────────────────────────────

    @Test
    fun `cancelling collector job stops receiving state updates`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("before"))
        advanceUntilIdle()
        assertEquals(1, received.size)

        job.cancel()
        advanceUntilIdle()

        board.broadcastState(UserState::class, UserState("after"))
        advanceUntilIdle()
        assertEquals(1, received.size, "Should not receive after cancellation")
    }

    @Test
    fun `cancelling collector job stops receiving reactions`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("before"))
        advanceUntilIdle()
        assertEquals(1, received.size)

        job.cancel()
        advanceUntilIdle()

        board.triggerImpulse(ToastReaction::class, ToastReaction("after"))
        advanceUntilIdle()
        assertEquals(1, received.size, "Should not receive after cancellation")
    }

    // ── No interceptors registered (passthrough) ─────────────────────────

    @Test
    fun `state works without any interceptors registered`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        val original = UserState("passthrough", 42)
        board.broadcastState(UserState::class, original)
        advanceUntilIdle()

        assertEquals(original, received.first())

        job.cancel()
    }

    @Test
    fun `reaction works without any interceptors registered`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        val original = ToastReaction("passthrough")
        board.triggerImpulse(ToastReaction::class, original)
        advanceUntilIdle()

        assertEquals(original, received.first())

        job.cancel()
    }

    // ── Full interceptor (short-circuit) ─────────────────────────────────

    @Test
    fun `full interceptor can short-circuit state upstream`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.full { _, _ ->
                UserState("blocked", -1)
            },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("original"))
        advanceUntilIdle()

        assertEquals(UserState("blocked", -1), received.first())

        job.cancel()
    }

    // ── InterceptPoint data class ────────────────────────────────────────

    @Test
    fun `InterceptPoint equality and hashCode work correctly`() {
        val a = InterceptPoint(Channel.STATE, Direction.UPSTREAM)
        val b = InterceptPoint(Channel.STATE, Direction.UPSTREAM)
        val c = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `all six InterceptPoint combinations are distinct`() {
        val points = Channel.entries.flatMap { ch ->
            Direction.entries.map { dir -> InterceptPoint(ch, dir) }
        }.toSet()

        assertEquals(6, points.size)
    }

    // ── Request downstream interceptor ──────────────────────────────────

    @Test
    fun `request downstream interceptor transforms result before delivery`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRouter = FakeRouter()
        fakeRouter.resultProvider = { TestResult("raw") }
        val board = DefaultSwitchBoard(router = fakeRouter, scope = backgroundScope)

        board.addInterceptor(
            point = InterceptPoint(Channel.REQUEST, Direction.DOWNSTREAM),
            clazz = TestResult::class,
            interceptor = Interceptor.transform { it.copy(answer = it.answer.uppercase()) },
        )

        val received = CopyOnWriteArrayList<TestResult>()
        val resultFlow = board.handleRequest(
            needClazz = TestResult::class,
            params = TestRequestParams("q"),
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            resultFlow.collect { received.add(it) }
        }
        advanceUntilIdle()

        assertEquals(TestResult("RAW"), received.first())

        job.cancel()
    }

    @Test
    fun `request upstream and downstream interceptors compose correctly`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRouter = FakeRouter()
        fakeRouter.resultProvider = { params ->
            TestResult("echo:${(params as TestRequestParams).query}")
        }
        val board = DefaultSwitchBoard(router = fakeRouter, scope = backgroundScope)

        board.addInterceptor(
            point = InterceptPoint(Channel.REQUEST, Direction.UPSTREAM),
            clazz = TestRequestParams::class,
            interceptor = Interceptor.transform { it.copy(query = it.query.uppercase()) },
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.REQUEST, Direction.DOWNSTREAM),
            clazz = TestResult::class,
            interceptor = Interceptor.transform { it.copy(answer = "${it.answer}!") },
        )

        val received = CopyOnWriteArrayList<TestResult>()
        val resultFlow = board.handleRequest(
            needClazz = TestResult::class,
            params = TestRequestParams("hello"),
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            resultFlow.collect { received.add(it) }
        }
        advanceUntilIdle()

        assertEquals(TestResult("echo:HELLO!"), received.first())

        job.cancel()
    }

    // ── Multiple interceptors on same point (chaining) ──────────────────

    @Test
    fun `multiple upstream interceptors chain in priority order for state`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())

        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_A") },
            priority = 0,
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_B") },
            priority = 5,
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_C") },
            priority = 10,
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("x"))
        advanceUntilIdle()

        assertEquals("x_A_B_C", received.first().name)

        job.cancel()
    }

    // ── Multiple downstream interceptors chaining ───────────────────────

    @Test
    fun `multiple downstream interceptors chain in priority order for state`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())

        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(age = it.age + 1) },
            priority = 0,
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.DOWNSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(age = it.age * 10) },
            priority = 10,
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("test", 5))
        advanceUntilIdle()

        // (5 + 1) * 10 = 60
        assertEquals(60, received.first().age)

        job.cancel()
    }

    // ── Full interceptor ignoring proceed ───────────────────────────────

    @Test
    fun `full interceptor can replace value ignoring original for reactions`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
            clazz = ToastReaction::class,
            interceptor = Interceptor.full { _, _ ->
                ToastReaction("replaced")
            },
        )

        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        board.triggerImpulse(ToastReaction::class, ToastReaction("original"))
        advanceUntilIdle()

        assertEquals(ToastReaction("replaced"), received.first())

        job.cancel()
    }

    @Test
    fun `full interceptor can delegate to proceed`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.full { data, proceed ->
                val modified = data.copy(name = "pre_${data.name}")
                proceed(modified)
            },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("test"))
        advanceUntilIdle()

        assertEquals("pre_test", received.first().name)

        job.cancel()
    }

    // ── Interceptor type isolation ──────────────────────────────────────

    @Test
    fun `interceptor for one type does not affect another type on same channel`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "INTERCEPTED") },
        )

        val themes = CopyOnWriteArrayList<ThemeState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(ThemeState::class).collect { themes.add(it) }
        }

        board.broadcastState(ThemeState::class, ThemeState(dark = true))
        advanceUntilIdle()

        assertEquals(ThemeState(dark = true), themes.first())

        job.cancel()
    }

    // ── Unregister one of multiple interceptors ─────────────────────────

    @Test
    fun `unregistering one interceptor leaves others intact`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())

        val reg1 = board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_A") },
            priority = 0,
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_B") },
            priority = 10,
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("x"))
        advanceUntilIdle()
        assertEquals("x_A_B", received.last().name)

        reg1.unregister()

        board.broadcastState(UserState::class, UserState("y"))
        advanceUntilIdle()
        assertEquals("y_B", received.last().name)

        job.cancel()
    }

    // ── Reified handleRequest ───────────────────────────────────────────

    @Test
    fun `reified handleRequest extension works correctly`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRouter = FakeRouter()
        fakeRouter.resultProvider = { TestResult("reified-answer") }
        val board = DefaultSwitchBoard(router = fakeRouter, scope = backgroundScope)

        val received = CopyOnWriteArrayList<TestResult>()
        val resultFlow = board.handleRequest<TestResult, TestRequestParams>(
            params = TestRequestParams("reified-q"),
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            resultFlow.collect { received.add(it) }
        }
        advanceUntilIdle()

        assertEquals(TestResult("reified-answer"), received.first())

        job.cancel()
    }

    // ── State replay after downstream interceptor change ────────────────

    @Test
    fun `stateFlow replays latest value including upstream interception to new collectors`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = it.name.uppercase()) },
        )

        board.broadcastState(UserState::class, UserState("alice"))
        advanceUntilIdle()

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }
        advanceUntilIdle()

        assertEquals("ALICE", received.first().name)

        job.cancel()
    }

    // ── Rapid broadcast overwrites (state replay = 1) ───────────────────

    @Test
    fun `getRawStateFlow replays latest value to late collectors`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())

        board.broadcastState(UserState::class, UserState("first"))
        board.broadcastState(UserState::class, UserState("second"))
        advanceUntilIdle()

        val received = CopyOnWriteArrayList<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.getRawStateFlow(UserState::class).collect { received.add(it) }
        }
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(UserState("second"), received.first())

        job.cancel()
    }

    // ── getRawImpulseFlow has no replay ─────────────────────────────────

    @Test
    fun `getRawImpulseFlow has no replay for late collectors`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())

        board.triggerImpulse(ToastReaction::class, ToastReaction("missed"))
        advanceUntilIdle()

        val received = CopyOnWriteArrayList<Any>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.getRawImpulseFlow(ToastReaction::class).collect { received.add(it) }
        }
        advanceUntilIdle()

        assertTrue(received.isEmpty(), "Late collector on raw impulse flow should not receive past events")

        job.cancel()
    }

    // ── Read interceptor does not modify data ───────────────────────────

    @Test
    fun `reaction upstream read interceptor observes without modifying`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val observed = CopyOnWriteArrayList<ToastReaction>()

        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
            clazz = ToastReaction::class,
            interceptor = Interceptor.read { observed.add(it) },
        )

        val received = CopyOnWriteArrayList<ToastReaction>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(ToastReaction::class).collect { received.add(it) }
        }

        val original = ToastReaction("observe-me")
        board.triggerImpulse(ToastReaction::class, original)
        advanceUntilIdle()

        assertEquals(original, observed.first())
        assertEquals(original, received.first())

        job.cancel()
    }

    // ── Same type on different channels ─────────────────────────────────

    @Test
    fun `same type used on state and reaction channels are independent`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        val stateReceived = CopyOnWriteArrayList<UserState>()
        val reactionReceived = CopyOnWriteArrayList<UserState>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { stateReceived.add(it) }
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.impulseFlow(UserState::class).collect { reactionReceived.add(it) }
        }

        board.broadcastState(UserState::class, UserState("state-only"))
        advanceUntilIdle()

        assertEquals(1, stateReceived.size)
        assertEquals(0, reactionReceived.size)

        board.triggerImpulse(UserState::class, UserState("reaction-only"))
        advanceUntilIdle()

        assertEquals(1, stateReceived.size)
        assertEquals(1, reactionReceived.size)

        job1.cancel()
        job2.cancel()
    }

    // ── handleRequest without router result ─────────────────────────────

    @Test
    fun `handleRequest emits nothing when router returns no result`() = runTest(UnconfinedTestDispatcher()) {
        val fakeRouter = FakeRouter()
        fakeRouter.resultProvider = { null }
        val board = DefaultSwitchBoard(router = fakeRouter, scope = backgroundScope)

        val received = CopyOnWriteArrayList<TestResult>()
        val resultFlow = board.handleRequest(
            needClazz = TestResult::class,
            params = TestRequestParams("empty"),
        )

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            resultFlow.collect { received.add(it) }
        }
        advanceUntilIdle()

        assertTrue(received.isEmpty())
        assertEquals(1, fakeRouter.routedParams.size)

        job.cancel()
    }

    // ── Downstream interceptor on reaction does not affect state ────────

    @Test
    fun `downstream interceptor on reaction channel does not affect state channel`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())
        board.addInterceptor(
            point = InterceptPoint(Channel.REACTION, Direction.DOWNSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "SHOULD_NOT_APPEAR") },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("clean"))
        advanceUntilIdle()

        assertEquals(UserState("clean"), received.first())

        job.cancel()
    }

    // ── Default priority (all zero) preserves insertion order ───────────

    @Test
    fun `interceptors with same priority execute in registration order`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(scope = backgroundScope, router = FakeRouter())

        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_1") },
        )
        board.addInterceptor(
            point = InterceptPoint(Channel.STATE, Direction.UPSTREAM),
            clazz = UserState::class,
            interceptor = Interceptor.transform { it.copy(name = "${it.name}_2") },
        )

        val received = CopyOnWriteArrayList<UserState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.stateFlow(UserState::class).collect { received.add(it) }
        }

        board.broadcastState(UserState::class, UserState("x"))
        advanceUntilIdle()

        assertEquals("x_1_2", received.first().name)

        job.cancel()
    }
}