package com.synapselib.arch.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

// ── Shared Test Infrastructure ──────────────────────────────────────────

class SimpleMutableState<T>(initial: T) : MutableState<T> {
    override var value: T = initial
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}

data class TestState(val count: Int = 0, val name: String = "")
data class TestImpulse(val message: String) : Impulse()
data class AnotherImpulse(val code: Int) : Impulse()
data class TestBroadcast(val value: String)
data class OtherBroadcast(val number: Int)

// ── Shared DataImpulses & Providers ─────────────────────────────────────

data class TestResult(val data: String)

data class FetchTestResult(val id: Int) : DataImpulse<TestResult>()
data class FetchByQuery(val query: String) : DataImpulse<TestResult>()

class FetchTestResultProvider(
    private val resultProvider: (Int) -> TestResult = { TestResult("result-for-$it") },
) : Provider<FetchTestResult, TestResult>() {
    override fun ProviderScope.produce(impulse: FetchTestResult): Flow<TestResult> = flow {
        emit(resultProvider(impulse.id))
    }
}

class FetchByQueryProvider(
    private val resultProvider: (String) -> TestResult = { TestResult("query=$it") },
) : Provider<FetchByQuery, TestResult>() {
    override fun ProviderScope.produce(impulse: FetchByQuery): Flow<TestResult> = flow {
        emit(resultProvider(impulse.query))
    }
}

/** Creates a [ProviderRegistry] with the standard test providers. */
fun testProviderRegistry(
    fetchResult: (Int) -> TestResult = { TestResult("result-for-$it") },
    fetchByQuery: (String) -> TestResult = { TestResult("query=$it") },
): ProviderRegistry = ProviderRegistry.Builder()
    .register<TestResult, FetchTestResult> { FetchTestResultProvider(fetchResult) }
    .register<TestResult, FetchByQuery> { FetchByQueryProvider(fetchByQuery) }
    .build()

// ── ContextScope Tests ──────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class ContextScopeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `exposes context and switchboard`() {
        val sb = DefaultSwitchBoard(scope = testScope)
        val scope = ContextScope("ctx", sb)
        assertEquals("ctx", scope.context)
        assertSame(sb, scope.switchboard)
    }

    @Test
    fun `works with null context`() {
        assertNull(
            ContextScope<String?>(
                null,
                DefaultSwitchBoard(scope = testScope),
            ).context
        )
    }
}

// ── NodeScope Tests ─────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class NodeScopeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var switchBoard: DefaultSwitchBoard
    private lateinit var stateHolder: SimpleMutableState<TestState>
    private lateinit var nodeScope: NodeScope<String, TestState>

    @BeforeEach
    fun setup() {
        val testDispatcher = UnconfinedTestDispatcher()
        val boardScope = CoroutineScope(testDispatcher + SupervisorJob())
        switchBoard = DefaultSwitchBoard(
            scope = boardScope,
            providerRegistry = testProviderRegistry(),
            workerContext = testDispatcher
        )
        stateHolder = SimpleMutableState(TestState())
        val contextScope = ContextScope("testContext", switchBoard)
        nodeScope = NodeScope(contextScope, stateHolder, testScope)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── context / state ─────────────────────────────────────────────

    @Test
    fun `context delegates to contextScope`() {
        assertEquals("testContext", nodeScope.context)
    }

    @Test
    fun `state reads from stateHolder`() {
        assertEquals(TestState(), nodeScope.state)
        stateHolder.value = TestState(count = 99, name = "direct")
        assertEquals(TestState(count = 99, name = "direct"), nodeScope.state)
    }

    // ── update ──────────────────────────────────────────────────────

    @Test
    fun `update applies reducer and writes through to stateHolder`() {
        nodeScope.update { it.copy(count = 5) }
        assertEquals(5, nodeScope.state.count)
        assertEquals(5, stateHolder.value.count)
    }

    @Test
    fun `sequential updates read previous state and preserve untouched fields`() {
        nodeScope.update { it.copy(name = "Alice") }
        repeat(10) {
            nodeScope.update { it.copy(count = it.count + 1) }
        }
        assertEquals(TestState(count = 10, name = "Alice"), nodeScope.state)
    }

    @Test
    fun `identity update does not change state`() {
        val before = nodeScope.state
        nodeScope.update { it }
        assertSame(before, nodeScope.state)
    }

    @Test
    fun `update with replacing entire state`() {
        nodeScope.update { it.copy(count = 5, name = "Alice") }
        nodeScope.update { TestState() }
        assertEquals(TestState(), nodeScope.state)
    }

    @Test
    fun `update with equivalent but new object instance updates reference`() {
        val initial = nodeScope.state
        nodeScope.update { it.copy() }
        assertNotSame(initial, nodeScope.state)
        assertEquals(initial, nodeScope.state)
    }

    // ── Broadcast / stateFlow ───────────────────────────────────────

    @Test
    fun `broadcast is received by listener`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        nodeScope.Broadcast(TestBroadcast("a"))
        nodeScope.Broadcast(TestBroadcast("b"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestBroadcast("a"), TestBroadcast("b")), received)
    }

    @Test
    fun `broadcast replays only latest value to late listener`() = testScope.runTest {
        (1..500).forEach { nodeScope.Broadcast(TestBroadcast("v$it")) }

        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestBroadcast("v500")), received)
    }

    @Test
    fun `broadcast of different types are independent`() = testScope.runTest {
        val broadcasts = mutableListOf<TestBroadcast>()
        val others = mutableListOf<OtherBroadcast>()

        val job1 = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { broadcasts.add(it) }
        }
        val job2 = launch {
            switchBoard.stateFlow(OtherBroadcast::class).collect { others.add(it) }
        }

        nodeScope.Broadcast(TestBroadcast("hello"))
        nodeScope.Broadcast(OtherBroadcast(42))
        nodeScope.Broadcast(TestBroadcast("world"))
        testScheduler.advanceUntilIdle()
        job1.cancel()
        job2.cancel()

        assertEquals(listOf(TestBroadcast("hello"), TestBroadcast("world")), broadcasts)
        assertEquals(listOf(OtherBroadcast(42)), others)
    }

    @Test
    fun `multiple listeners all receive same broadcast`() = testScope.runTest {
        val r1 = mutableListOf<TestBroadcast>()
        val r2 = mutableListOf<TestBroadcast>()
        val r3 = mutableListOf<TestBroadcast>()

        val job1 = launch { switchBoard.stateFlow(TestBroadcast::class).collect { r1.add(it) } }
        val job2 = launch { switchBoard.stateFlow(TestBroadcast::class).collect { r2.add(it) } }
        val job3 = launch { switchBoard.stateFlow(TestBroadcast::class).collect { r3.add(it) } }

        nodeScope.Broadcast(TestBroadcast("shared"))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel(); job3.cancel()

        val expected = listOf(TestBroadcast("shared"))
        assertEquals(expected, r1)
        assertEquals(expected, r2)
        assertEquals(expected, r3)
    }

    @Test
    fun `broadcast same value twice delivers both`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        nodeScope.Broadcast(TestBroadcast("same"))
        nodeScope.Broadcast(TestBroadcast("same"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(2, received.size)
        assertTrue(received.all { it == TestBroadcast("same") })
    }

    @Test
    fun `cancelled state listener stops receiving broadcasts`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        nodeScope.Broadcast(TestBroadcast("before"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        nodeScope.Broadcast(TestBroadcast("after"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(TestBroadcast("before")), received)
    }

    // ── Trigger / impulseFlow ───────────────────────────────────────

    @Test
    fun `triggered impulse is received by listener`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        nodeScope.Trigger(TestImpulse("fire"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("fire")), received)
    }

    @Test
    fun `impulse does not replay to late listener`() = testScope.runTest {
        nodeScope.Trigger(TestImpulse("old"))

        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()

        nodeScope.Trigger(TestImpulse("caught"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("caught")), received)
    }

    @Test
    fun `rapid triggers are all delivered in order`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        val messages = (1..500).map { TestImpulse("msg-$it") }
        messages.forEach { nodeScope.Trigger(it) }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(messages, received)
    }

    @Test
    fun `impulse only received by matching type`() = testScope.runTest {
        val testImpulses = mutableListOf<TestImpulse>()
        val anotherImpulses = mutableListOf<AnotherImpulse>()

        val job1 = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { testImpulses.add(it) }
        }
        val job2 = launch {
            switchBoard.impulseFlow(AnotherImpulse::class).collect { anotherImpulses.add(it) }
        }

        nodeScope.Trigger(AnotherImpulse(1))
        nodeScope.Trigger(TestImpulse("yes"))
        nodeScope.Trigger(AnotherImpulse(2))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        assertEquals(listOf(TestImpulse("yes")), testImpulses)
        assertEquals(listOf(AnotherImpulse(1), AnotherImpulse(2)), anotherImpulses)
    }

    @Test
    fun `multiple listeners all receive same impulse`() = testScope.runTest {
        val r1 = mutableListOf<TestImpulse>()
        val r2 = mutableListOf<TestImpulse>()

        val job1 = launch { switchBoard.impulseFlow(TestImpulse::class).collect { r1.add(it) } }
        val job2 = launch { switchBoard.impulseFlow(TestImpulse::class).collect { r2.add(it) } }

        nodeScope.Trigger(TestImpulse("shared"))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        assertEquals(listOf(TestImpulse("shared")), r1)
        assertEquals(listOf(TestImpulse("shared")), r2)
    }

    @Test
    fun `cancelled listener stops receiving impulses`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        nodeScope.Trigger(TestImpulse("before"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        nodeScope.Trigger(TestImpulse("after"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(TestImpulse("before")), received)
    }

    @Test
    fun `trigger with no listeners does not throw`() = testScope.runTest {
        nodeScope.Trigger(TestImpulse("nobody listening"))
    }

    // ── Request (DataState lifecycle) ────────────────────────────────

    @Test
    fun `request delivers DataState Success via switchboard`() = testScope.runTest {
        val stateFlow = switchBoard.handleRequest(
            FetchTestResult::class, TestResult::class, FetchTestResult(id = 7),
        )
        val received = mutableListOf<DataState<TestResult>>()

        val job = launch { stateFlow.collect { received.add(it) } }
        testScheduler.advanceUntilIdle()
        job.cancel()

        val terminal = received.last()
        assertTrue(terminal is DataState.Success)
        assertEquals(TestResult("result-for-7"), (terminal as DataState.Success).data)
    }

    @Test
    fun `request passes correct params to provider`() = testScope.runTest {
        val receivedIds = mutableListOf<Int>()
        val registry = ProviderRegistry.Builder()
            .register<TestResult, FetchTestResult> {
                object : Provider<FetchTestResult, TestResult>() {
                    override fun ProviderScope.produce(impulse: FetchTestResult): Flow<TestResult> = flow {
                        receivedIds.add(impulse.id)
                        emit(TestResult("ok"))
                    }
                }
            }
            .build()
        val board = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + Job()),
            providerRegistry = registry,
        )

        val job1 = launch {
            board.handleRequest(FetchTestResult::class, TestResult::class, FetchTestResult(1)).collect {}
        }
        testScheduler.advanceUntilIdle()
        job1.cancel()

        val job2 = launch {
            board.handleRequest(FetchTestResult::class, TestResult::class, FetchTestResult(2)).collect {}
        }
        testScheduler.advanceUntilIdle()
        job2.cancel()

        assertEquals(listOf(1, 2), receivedIds)
    }

    @Test
    fun `request with different impulse types routes independently`() = testScope.runTest {
        val flow1 = switchBoard.handleRequest(
            FetchTestResult::class, TestResult::class, FetchTestResult(id = 5),
        )
        val flow2 = switchBoard.handleRequest(
            FetchByQuery::class, TestResult::class, FetchByQuery(query = "hello"),
        )

        val received1 = mutableListOf<DataState<TestResult>>()
        val received2 = mutableListOf<DataState<TestResult>>()

        val job1 = launch { flow1.collect { received1.add(it) } }
        val job2 = launch { flow2.collect { received2.add(it) } }
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        val success1 = received1.last() as DataState.Success
        val success2 = received2.last() as DataState.Success
        assertEquals(TestResult("result-for-5"), success1.data)
        assertEquals(TestResult("query=hello"), success2.data)
    }

    @Test
    fun `multiple concurrent requests all deliver results`() = testScope.runTest {
        val results = mutableListOf<TestResult>()

        val jobs = (1..10).map { id ->
            launch {
                val flow = switchBoard.handleRequest(
                    FetchTestResult::class, TestResult::class, FetchTestResult(id),
                )
                flow.collect { state ->
                    if (state is DataState.Success) results.add(state.data)
                }
            }
        }
        testScheduler.advanceUntilIdle()
        jobs.forEach { it.cancel() }

        assertEquals(10, results.size)
        (1..10).forEach { id ->
            assertTrue(results.contains(TestResult("result-for-$id")))
        }
    }

    @Test
    fun `request emits Loading then Success for one-shot provider`() = testScope.runTest {
        val states = mutableListOf<DataState<TestResult>>()
        val flow = switchBoard.handleRequest(
            FetchTestResult::class, TestResult::class, FetchTestResult(1),
        )

        val job = launch { flow.collect { states.add(it) } }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(states.any { it is DataState.Loading })
        assertTrue(states.any { it is DataState.Success })
        val loadIdx = states.indexOfFirst { it is DataState.Loading }
        val successIdx = states.indexOfFirst { it is DataState.Success }
        assertTrue(loadIdx < successIdx)
    }

    // ── dispose ─────────────────────────────────────────────────────

    @Test
    fun `dispose unregisters all interceptors and clears registrations`() = runTest {
        val registry = InterceptorRegistry()
        val counter = AtomicInteger(0)

        val regs = (1..100).map {
            registry.add(Interceptor.read<TestBroadcast> { counter.incrementAndGet() })
        }
        nodeScope.registrations.addAll(regs)

        registry.applyInterceptors(TestBroadcast("before"))
        assertEquals(100, counter.get())

        nodeScope.dispose()
        counter.set(0)

        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, counter.get())
        assertTrue(nodeScope.registrations.isEmpty())
    }

    @Test
    fun `dispose with no registrations does not throw`() {
        nodeScope.dispose()
        assertTrue(nodeScope.registrations.isEmpty())
    }

    @Test
    fun `dispose is idempotent`() = runTest {
        val registry = InterceptorRegistry()
        val counter = AtomicInteger(0)
        nodeScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { counter.incrementAndGet() })
        )

        nodeScope.dispose()
        nodeScope.dispose()

        assertTrue(nodeScope.registrations.isEmpty())
        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, counter.get())
    }

    @Test
    fun `dispose only affects own registrations`() = runTest {
        val registry = InterceptorRegistry()
        val ownCounter = AtomicInteger(0)
        val foreignCounter = AtomicInteger(0)

        val ownRegs = (1..50).map {
            registry.add(Interceptor.read<TestBroadcast> { ownCounter.incrementAndGet() })
        }
        (1..50).forEach { _ ->
            registry.add(Interceptor.read<TestBroadcast> { foreignCounter.incrementAndGet() })
        }
        nodeScope.registrations.addAll(ownRegs)

        nodeScope.dispose()
        registry.applyInterceptors(TestBroadcast("check"))

        assertEquals(0, ownCounter.get())
        assertEquals(50, foreignCounter.get())
    }

    @Test
    fun `dispose of one nodeScope does not affect another`() = runTest {
        val registry = InterceptorRegistry()
        val counter1 = AtomicInteger(0)
        val counter2 = AtomicInteger(0)

        val otherScope = NodeScope(
            ContextScope("other", switchBoard),
            SimpleMutableState(TestState()),
            testScope,
        )

        nodeScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { counter1.incrementAndGet() })
        )
        otherScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { counter2.incrementAndGet() })
        )

        nodeScope.dispose()

        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, counter1.get())
        assertEquals(1, counter2.get())
    }

    // ── Lifecycle: dispose during active operations ─────────────────────

    @Test
    fun `update after dispose still works on stateHolder`() {
        nodeScope.dispose()
        nodeScope.update { it.copy(count = 999) }
        assertEquals(999, nodeScope.state.count)
    }

    @Test
    fun `broadcast after dispose still emits`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        nodeScope.dispose()
        nodeScope.Broadcast(TestBroadcast("post-dispose"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(received.contains(TestBroadcast("post-dispose")))
    }

    @Test
    fun `trigger after dispose still emits`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        nodeScope.dispose()
        nodeScope.Trigger(TestImpulse("post-dispose"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("post-dispose")), received)
    }

    // ── Two NodeScopes communicating via switchboard ────────────────────

    @Test
    fun `one nodeScope broadcasts state that another listens to`() = testScope.runTest {
        val otherStateHolder = SimpleMutableState(TestState())
        val otherScope = NodeScope(
            ContextScope("other", switchBoard),
            otherStateHolder,
            testScope,
        )

        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collectLatest { broadcast ->
                otherScope.update { it.copy(name = broadcast.value) }
            }
        }

        nodeScope.Broadcast(TestBroadcast("from-first"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals("from-first", otherScope.state.name)
    }

    @Test
    fun `one nodeScope triggers impulse that another reacts to`() = testScope.runTest {
        val otherStateHolder = SimpleMutableState(TestState())
        val otherScope = NodeScope(
            ContextScope("other", switchBoard),
            otherStateHolder,
            testScope,
        )

        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { impulse ->
                otherScope.update { it.copy(name = impulse.message) }
            }
        }

        nodeScope.Trigger(TestImpulse("ping"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals("ping", otherScope.state.name)
    }

    // ── Concurrent stress ───────────────────────────────────────────────

    @Test
    fun `concurrent coroutine updates all apply`() = testScope.runTest {
        val jobs = (1..100).map {
            launch { nodeScope.update { it.copy(count = it.count + 1) } }
        }
        jobs.joinAll()

        assertEquals(100, nodeScope.state.count)
    }

    @Test
    fun `interleaved updates and reads are consistent`() = testScope.runTest {
        val snapshots = mutableListOf<TestState>()

        val jobs = (1..50).map { _ ->
            launch {
                nodeScope.update { it.copy(count = it.count + 1) }
                snapshots.add(nodeScope.state)
            }
        }
        jobs.joinAll()

        assertTrue(snapshots.all { it.count >= 1 })
        assertEquals(50, nodeScope.state.count)
    }

    @Test
    fun `flood broadcasts are all delivered to listener`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        val expected = (1..500).map { TestBroadcast("msg-$it") }
        expected.forEach { nodeScope.Broadcast(it) }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(expected, received)
    }

    @Test
    fun `concurrent broadcasts from multiple scopes interleave correctly`() = testScope.runTest {
        val scope2 = NodeScope(
            ContextScope("other", switchBoard),
            SimpleMutableState(TestState()),
            testScope,
        )

        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        val jobs = (1..50).flatMap { i ->
            listOf(
                launch { nodeScope.Broadcast(TestBroadcast("a-$i")) },
                launch { scope2.Broadcast(TestBroadcast("b-$i")) },
            )
        }
        jobs.joinAll()
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(100, received.size)
        assertEquals(50, received.count { it.value.startsWith("a-") })
        assertEquals(50, received.count { it.value.startsWith("b-") })
    }

    @Test
    fun `triggers from multiple scopes all arrive`() = testScope.runTest {
        val scope2 = NodeScope(
            ContextScope("other", switchBoard),
            SimpleMutableState(TestState()),
            testScope,
        )

        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        val jobs = (1..50).flatMap { i ->
            listOf(
                launch { nodeScope.Trigger(TestImpulse("a-$i")) },
                launch { scope2.Trigger(TestImpulse("b-$i")) },
            )
        }
        jobs.joinAll()
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(100, received.size)
    }

    // ── Chained reactions ───────────────────────────────────────────────

    @Test
    fun `broadcast triggers listener that triggers impulse`() = testScope.runTest {
        val impulses = mutableListOf<TestImpulse>()

        val impulseJob = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { impulses.add(it) }
        }
        val chainJob = launch {
            switchBoard.stateFlow(TestBroadcast::class).collectLatest { broadcast ->
                nodeScope.Trigger(TestImpulse("reacted-to-${broadcast.value}"))
            }
        }

        nodeScope.Broadcast(TestBroadcast("origin"))
        testScheduler.advanceUntilIdle()
        impulseJob.cancel(); chainJob.cancel()

        assertEquals(listOf(TestImpulse("reacted-to-origin")), impulses)
    }

    @Test
    fun `chained broadcasts propagate through multiple listeners`() = testScope.runTest {
        val finalValues = mutableListOf<OtherBroadcast>()

        val step1 = launch {
            switchBoard.stateFlow(TestBroadcast::class).collectLatest { b ->
                nodeScope.Broadcast(OtherBroadcast(b.value.length))
            }
        }
        val step2 = launch {
            switchBoard.stateFlow(OtherBroadcast::class).collect { finalValues.add(it) }
        }

        nodeScope.Broadcast(TestBroadcast("hello"))
        testScheduler.advanceUntilIdle()
        step1.cancel(); step2.cancel()

        assertEquals(listOf(OtherBroadcast(5)), finalValues)
    }

    @Test
    fun `listener that updates state based on impulse`() = testScope.runTest {
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { impulse ->
                nodeScope.update { it.copy(name = impulse.message, count = it.count + 1) }
            }
        }

        nodeScope.Trigger(TestImpulse("alpha"))
        nodeScope.Trigger(TestImpulse("beta"))
        nodeScope.Trigger(TestImpulse("gamma"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(3, nodeScope.state.count)
        assertEquals("gamma", nodeScope.state.name)
    }

    // ── Multiple switchboards are isolated ──────────────────────────────

    @Test
    fun `two switchboards do not leak broadcasts`() = testScope.runTest {
        val otherSwitchBoard = DefaultSwitchBoard(scope = CoroutineScope(testDispatcher + Job()))
        val otherScope = NodeScope(
            ContextScope("isolated", otherSwitchBoard),
            SimpleMutableState(TestState()),
            testScope,
        )

        val fromFirst = mutableListOf<TestBroadcast>()
        val fromSecond = mutableListOf<TestBroadcast>()

        val job1 = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { fromFirst.add(it) }
        }
        val job2 = launch {
            otherSwitchBoard.stateFlow(TestBroadcast::class).collect { fromSecond.add(it) }
        }

        nodeScope.Broadcast(TestBroadcast("first-only"))
        otherScope.Broadcast(TestBroadcast("second-only"))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        assertEquals(listOf(TestBroadcast("first-only")), fromFirst)
        assertEquals(listOf(TestBroadcast("second-only")), fromSecond)
    }

    @Test
    fun `two switchboards do not leak impulses`() = testScope.runTest {
        val otherSwitchBoard = DefaultSwitchBoard(scope = CoroutineScope(testDispatcher + Job()))
        val otherScope = NodeScope(
            ContextScope("isolated", otherSwitchBoard),
            SimpleMutableState(TestState()),
            testScope,
        )

        val fromFirst = mutableListOf<TestImpulse>()
        val fromSecond = mutableListOf<TestImpulse>()

        val job1 = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { fromFirst.add(it) }
        }
        val job2 = launch {
            otherSwitchBoard.impulseFlow(TestImpulse::class).collect { fromSecond.add(it) }
        }

        nodeScope.Trigger(TestImpulse("first-only"))
        otherScope.Trigger(TestImpulse("second-only"))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        assertEquals(listOf(TestImpulse("first-only")), fromFirst)
        assertEquals(listOf(TestImpulse("second-only")), fromSecond)
    }

    @Test
    fun `two switchboards request providers independently`() = testScope.runTest {
        val otherSwitchBoard = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + Job()),
            providerRegistry = testProviderRegistry(
                fetchResult = { TestResult("from-second-$it") },
            ),
        )

        val received1 = mutableListOf<DataState<TestResult>>()
        val received2 = mutableListOf<DataState<TestResult>>()

        val job1 = launch {
            switchBoard.handleRequest(
                FetchTestResult::class, TestResult::class, FetchTestResult(1),
            ).collect { received1.add(it) }
        }
        val job2 = launch {
            otherSwitchBoard.handleRequest(
                FetchTestResult::class, TestResult::class, FetchTestResult(2),
            ).collect { received2.add(it) }
        }
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        val success1 = received1.last() as DataState.Success
        val success2 = received2.last() as DataState.Success
        assertEquals(TestResult("result-for-1"), success1.data)
        assertEquals(TestResult("from-second-2"), success2.data)
    }

    // ── Type erasure edge case ──────────────────────────────────────────

    data class BoxedBroadcast<T>(val value: T)

    @Test
    fun `broadcasts with generic types collide due to type erasure`() = testScope.runTest {
        val receivedStrings = mutableListOf<BoxedBroadcast<String>>()
        val receivedInts = mutableListOf<BoxedBroadcast<Int>>()

        val job1 = launch {
            @Suppress("UNCHECKED_CAST")
            switchBoard.stateFlow(BoxedBroadcast::class).collect {
                if (it.value is String) receivedStrings.add(it as BoxedBroadcast<String>)
            }
        }

        val job2 = launch {
            @Suppress("UNCHECKED_CAST")
            switchBoard.stateFlow(BoxedBroadcast::class).collect {
                if (it.value is Int) receivedInts.add(it as BoxedBroadcast<Int>)
            }
        }

        nodeScope.Broadcast(BoxedBroadcast("hello"))
        nodeScope.Broadcast(BoxedBroadcast(42))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        assertEquals(listOf(BoxedBroadcast("hello")), receivedStrings)
        assertEquals(listOf(BoxedBroadcast(42)), receivedInts)
    }
}