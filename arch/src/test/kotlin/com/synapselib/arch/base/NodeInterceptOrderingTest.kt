package com.synapselib.arch.base

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** No-op applier: these tests only exercise composition, effects and state. */
private class OrderingNoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun onClear() = Unit
}

/** Drives a headless composition on the JVM. */
private class OrderingHarness(scope: CoroutineScope) {
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val loop = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    private val composition = Composition(OrderingNoOpApplier(), recomposer)

    fun setContent(content: @Composable () -> Unit) = composition.setContent(content)

    fun recompose() {
        Snapshot.sendApplyNotifications()
        clock.sendFrame(0L)
    }

    fun dispose() {
        try {
            composition.dispose()
        } finally {
            recomposer.cancel()
            loop.cancel()
        }
    }
}

/**
 * Regression tests for the ordering of [NodeScope.Intercept] relative to the
 * effect-based verbs ([NodeScope.Request], [NodeScope.ListenFor]).
 *
 * `Intercept` used to register in a `DisposableEffect`, so an effect declared
 * *earlier* in the same body (e.g. a `Request`) ran first and its first
 * emission bypassed the interceptor. In 1.0.x `Intercept` registered
 * synchronously during composition; it does so again now (via a remembered
 * `RememberObserver`), while still registering only once per key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeInterceptOrderingTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var switchBoard: DefaultSwitchBoard
    private lateinit var nodeScope: NodeScope<String, TestState>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        switchBoard = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            providerRegistry = testProviderRegistry(),
            workerContext = testDispatcher,
        )
        nodeScope = NodeScope(
            ContextScope("ctx", switchBoard, CoroutineScope(testDispatcher + SupervisorJob())),
            SimpleMutableState(TestState()),
            testScope,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Issues a request directly on the board and returns the first success payload. */
    private suspend fun requestOnce(id: Int): String =
        (switchBoard.handleRequest(FetchTestResult::class, TestResult::class, FetchTestResult(id))
            .first { it is DataState.Success } as DataState.Success<TestResult>).data.data

    private fun successes(results: List<DataState<TestResult>>): List<String> =
        results.filterIsInstance<DataState.Success<TestResult>>().map { it.data.data }

    // ── Request before Intercept ───────────────────────────────────────

    @Test
    fun `Request declared before Intercept is intercepted from its first emission`() = testScope.runTest {
        val harness = OrderingHarness(this)
        val results = CopyOnWriteArrayList<DataState<TestResult>>()

        harness.setContent {
            nodeScope.Request(FetchTestResult(1)) { results += it }
            nodeScope.Intercept(
                InterceptPoint.REQUEST_UPSTREAM,
                Interceptor.transform<FetchTestResult> { it.copy(id = 99) },
            )
        }

        assertEquals(listOf("result-for-99"), successes(results), "first request must see the interceptor")
        harness.dispose()
    }

    @Test
    fun `downstream Intercept declared after Request sees the first result`() = testScope.runTest {
        val harness = OrderingHarness(this)
        val results = CopyOnWriteArrayList<DataState<TestResult>>()

        harness.setContent {
            nodeScope.Request(FetchTestResult(7)) { results += it }
            nodeScope.Intercept(
                InterceptPoint.REQUEST_DOWNSTREAM,
                Interceptor.transform<TestResult> { it.copy(data = it.data + "!") },
            )
        }

        assertEquals(listOf("result-for-7!"), successes(results))
        harness.dispose()
    }

    @Test
    fun `ListenFor declared before Intercept sees the replayed value intercepted`() = testScope.runTest {
        // Broadcast first so the state lane has a replay value that the listener
        // receives as soon as its effect subscribes.
        switchBoard.broadcastState(TestBroadcast("hello"))
        val seen = CopyOnWriteArrayList<String>()
        val harness = OrderingHarness(this)

        harness.setContent {
            nodeScope.ListenFor<TestBroadcast> { seen += it.value }
            nodeScope.Intercept(
                InterceptPoint.STATE_DOWNSTREAM,
                Interceptor.transform<TestBroadcast> { it.copy(value = it.value.uppercase()) },
            )
        }

        assertEquals(listOf("HELLO"), seen.toList())
        harness.dispose()
    }

    // ── Registration-once guarantees still hold ────────────────────────

    @Test
    fun `synchronous Intercept is still registered once and removed with the node`() = testScope.runTest {
        val harness = OrderingHarness(this)
        val show = mutableStateOf(true)
        val tick = mutableStateOf(0)
        val results = CopyOnWriteArrayList<DataState<TestResult>>()

        harness.setContent {
            CompositionLocalProvider(LocalSwitchBoard provides switchBoard) {
                if (show.value) {
                    CreateContext("ctx") {
                        Node(initialState = TestState()) {
                            tick.value
                            Request(FetchTestResult(tick.value)) { results += it }
                            Intercept(
                                InterceptPoint.REQUEST_DOWNSTREAM,
                                Interceptor.transform<TestResult> { it.copy(data = it.data + "+") },
                            )
                        }
                    }
                }
            }
        }
        repeat(3) {
            tick.value++
            harness.recompose()
        }
        // One interceptor (one "+") on every request, including the very first.
        assertEquals(
            listOf("result-for-0+", "result-for-1+", "result-for-2+", "result-for-3+"),
            successes(results),
        )

        show.value = false
        harness.recompose()

        assertEquals("result-for-5", requestOnce(5), "interceptor removed with the node")
        harness.dispose()
    }

    @Test
    fun `abandoned composition does not leak the interceptor`() = testScope.runTest {
        val harness = OrderingHarness(this)
        val boom = RuntimeException("boom")

        val thrown = runCatching {
            harness.setContent {
                nodeScope.Intercept(
                    InterceptPoint.REQUEST_DOWNSTREAM,
                    Interceptor.transform<TestResult> { it.copy(data = it.data + "leak") },
                )
                throw boom
            }
        }.exceptionOrNull()
        assertEquals(boom, thrown)

        assertEquals("result-for-1", requestOnce(1), "abandoned registration must be released")
        runCatching { harness.dispose() }
    }
}
