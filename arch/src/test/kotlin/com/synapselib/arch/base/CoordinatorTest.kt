package com.synapselib.arch.base

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorScopeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var switchBoard: DefaultSwitchBoard
    private lateinit var coordinatorScope: CoordinatorScope

    @BeforeEach
    fun setup() {
        val testDispatcher = UnconfinedTestDispatcher()
        val boardScope = CoroutineScope(testDispatcher + SupervisorJob())
        switchBoard = DefaultSwitchBoard(
            scope = boardScope,
            providerRegistry = testProviderRegistry(),
            workerContext = testDispatcher
        )
        coordinatorScope = CoordinatorScope(switchBoard, CoroutineScope(testDispatcher + Job()))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Coordinator factory ─────────────────────────────────────────

    @Test
    fun `Coordinator factory runs block and returns scope`() {
        var blockRan = false
        val scope = Coordinator(switchBoard, CoroutineScope(testDispatcher + Job())) {
            blockRan = true
        }
        assertTrue(blockRan)
        assertEquals(switchBoard, scope.switchboard)
    }

    // ══════════════════════════════════════════════════════════════════
    // Broadcast / stateFlow
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `broadcast is received by listener`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        coordinatorScope.Broadcast(TestBroadcast("a"))
        coordinatorScope.Broadcast(TestBroadcast("b"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestBroadcast("a"), TestBroadcast("b")), received)
    }

    @Test
    fun `broadcast replays latest value to new listener`() = testScope.runTest {
        coordinatorScope.Broadcast(TestBroadcast("first"))
        coordinatorScope.Broadcast(TestBroadcast("second"))

        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestBroadcast("second")), received)
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

        coordinatorScope.Broadcast(TestBroadcast("hello"))
        coordinatorScope.Broadcast(OtherBroadcast(42))
        coordinatorScope.Broadcast(TestBroadcast("world"))
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

        coordinatorScope.Broadcast(TestBroadcast("shared"))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel(); job3.cancel()

        val expected = listOf(TestBroadcast("shared"))
        assertEquals(expected, r1)
        assertEquals(expected, r2)
        assertEquals(expected, r3)
    }

    // ══════════════════════════════════════════════════════════════════
    // Trigger / impulseFlow
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `triggered impulse is received by listener`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        coordinatorScope.Trigger(TestImpulse("fire"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("fire")), received)
    }

    @Test
    fun `impulse does not replay to late listener`() = testScope.runTest {
        coordinatorScope.Trigger(TestImpulse("old"))

        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `rapid triggers are all delivered in order`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        val messages = (1..20).map { TestImpulse("msg-$it") }
        messages.forEach { coordinatorScope.Trigger(it) }
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

        coordinatorScope.Trigger(AnotherImpulse(1))
        coordinatorScope.Trigger(TestImpulse("yes"))
        coordinatorScope.Trigger(AnotherImpulse(2))
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

        coordinatorScope.Trigger(TestImpulse("shared"))
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

        coordinatorScope.Trigger(TestImpulse("before"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        coordinatorScope.Trigger(TestImpulse("after"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(TestImpulse("before")), received)
    }

    // ══════════════════════════════════════════════════════════════════
    // Request path (DataImpulse → Provider → DataState)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `request delivers DataState Success via flow`() = testScope.runTest {
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

    // ── Flow access overloads (ListenFor, ReactTo, Request) ─────────

    @Test
    fun `ListenFor flow returns stateFlow`() = testScope.runTest {
        coordinatorScope.Broadcast(TestBroadcast("via-flow"))

        val flow = coordinatorScope.ListenFor<TestBroadcast>()
        val result = flow.first()

        assertEquals(TestBroadcast("via-flow"), result)
    }

    @Test
    fun `ReactTo flow returns impulseFlow`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val flow = coordinatorScope.ReactTo<TestImpulse>()

        val job = launch { flow.collect { received.add(it) } }

        coordinatorScope.Trigger(TestImpulse("flow-impulse"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("flow-impulse")), received)
    }

    @Test
    fun `Request flow returns DataState flow`() = testScope.runTest {
        val stateFlow = coordinatorScope.Request(FetchTestResult(id = 1))
        val received = mutableListOf<DataState<TestResult>>()

        val job = launch { stateFlow.collect { received.add(it) } }
        testScheduler.advanceUntilIdle()
        job.cancel()

        val terminal = received.last()
        assertTrue(terminal is DataState.Success)
        assertEquals(TestResult("result-for-1"), (terminal as DataState.Success).data)
    }

    // ── Handler overloads (ListenFor, ReactTo, Request with callback) ──

    @Test
    fun `ListenFor handler receives broadcasts`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()

        val job = coordinatorScope.ListenFor<TestBroadcast> { received.add(it) }

        coordinatorScope.Broadcast(TestBroadcast("handler-a"))
        coordinatorScope.Broadcast(TestBroadcast("handler-b"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        // collectLatest may drop intermediate values, but latest should be present
        assertTrue(received.isNotEmpty())
        assertTrue(received.contains(TestBroadcast("handler-b")))
    }

    @Test
    fun `ReactTo handler receives impulses`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()

        val job = coordinatorScope.ReactTo<TestImpulse> { received.add(it) }

        coordinatorScope.Trigger(TestImpulse("handler-impulse"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("handler-impulse")), received)
    }

    @Test
    fun `Request handler receives DataState transitions`() = testScope.runTest {
        val received = mutableListOf<DataState<TestResult>>()

        val job = coordinatorScope.Request(FetchTestResult(id = 1)) { state ->
            received.add(state)
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(received.any { it is DataState.Loading }, "Should have received Loading")
        assertTrue(received.any { it is DataState.Success }, "Should have received Success")
        val success = received.filterIsInstance<DataState.Success<TestResult>>().first()
        assertEquals(TestResult("result-for-1"), success.data)
    }

    @Test
    fun `handler overload has CoordinatorScope as receiver`() = testScope.runTest {
        var capturedSwitchboard: SwitchBoard? = null

        val job = coordinatorScope.ReactTo<TestImpulse> {
            capturedSwitchboard = this.switchboard
        }

        coordinatorScope.Trigger(TestImpulse("check-receiver"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(switchBoard, capturedSwitchboard)
    }

    @Test
    fun `Request handler has CoordinatorScope as receiver`() = testScope.runTest {
        var capturedSwitchboard: SwitchBoard? = null

        val job = coordinatorScope.Request(FetchTestResult(id = 1)) { state ->
            if (state is DataState.Success) {
                capturedSwitchboard = this.switchboard
            }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(switchBoard, capturedSwitchboard)
    }

    // ══════════════════════════════════════════════════════════════════
    // dispose
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `dispose unregisters all interceptors`() = runTest {
        val registry = InterceptorRegistry()
        val counter = AtomicInteger(0)

        val reg1 = registry.add(Interceptor.read<TestBroadcast> { counter.incrementAndGet() })
        val reg2 = registry.add(Interceptor.read<TestBroadcast> { counter.incrementAndGet() })
        coordinatorScope.registrations.addAll(listOf(reg1, reg2))

        registry.applyInterceptors(TestBroadcast("before"))
        assertEquals(2, counter.get())

        coordinatorScope.dispose()
        counter.set(0)

        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, counter.get())
    }

    @Test
    fun `dispose clears registrations list`() {
        val registry = InterceptorRegistry()
        coordinatorScope.registrations.add(registry.add(Interceptor.read<TestBroadcast> { }))
        coordinatorScope.registrations.add(registry.add(Interceptor.read<TestBroadcast> { }))

        coordinatorScope.dispose()

        assertTrue(coordinatorScope.registrations.isEmpty())
    }

    @Test
    fun `dispose with no registrations does not throw`() {
        coordinatorScope.dispose()
        assertTrue(coordinatorScope.registrations.isEmpty())
    }

    @Test
    fun `dispose is idempotent`() = runTest {
        val registry = InterceptorRegistry()
        val counter = AtomicInteger(0)
        coordinatorScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { counter.incrementAndGet() })
        )

        coordinatorScope.dispose()
        coordinatorScope.dispose()

        assertTrue(coordinatorScope.registrations.isEmpty())
        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, counter.get())
    }

    @Test
    fun `dispose only affects own registrations`() = runTest {
        val registry = InterceptorRegistry()
        val ownCounter = AtomicInteger(0)
        val otherCounter = AtomicInteger(0)

        coordinatorScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { ownCounter.incrementAndGet() })
        )
        registry.add(Interceptor.read<TestBroadcast> { otherCounter.incrementAndGet() })

        coordinatorScope.dispose()

        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, ownCounter.get())
        assertEquals(1, otherCounter.get())
    }

    @Test
    fun `dispose cancels coroutine job and child jobs`() = testScope.runTest {
        val scope = CoroutineScope(testDispatcher + Job())
        val coord = CoordinatorScope(switchBoard, scope)

        val received = mutableListOf<TestImpulse>()
        coord.ReactTo<TestImpulse> { received.add(it) }

        coord.Trigger(TestImpulse("before-dispose"))
        testScheduler.advanceUntilIdle()

        coord.dispose()

        // After dispose, the backing job is cancelled so new triggers won't be received
        // by the handler (though the switchboard itself still works)
        val externalReceived = mutableListOf<TestImpulse>()
        val externalJob = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { externalReceived.add(it) }
        }
        coord.Trigger(TestImpulse("after-dispose"))
        testScheduler.advanceUntilIdle()
        externalJob.cancel()

        assertEquals(listOf(TestImpulse("before-dispose")), received)
        // External listener still works — switchboard is not affected
        assertEquals(listOf(TestImpulse("after-dispose")), externalReceived)
    }

    // ══════════════════════════════════════════════════════════════════
    // Broadcast edge cases
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `broadcast same value twice delivers both`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        coordinatorScope.Broadcast(TestBroadcast("same"))
        coordinatorScope.Broadcast(TestBroadcast("same"))
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

        coordinatorScope.Broadcast(TestBroadcast("before"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        coordinatorScope.Broadcast(TestBroadcast("after"))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(TestBroadcast("before")), received)
    }

    @Test
    fun `late listener only gets latest broadcast not full history`() = testScope.runTest {
        coordinatorScope.Broadcast(TestBroadcast("one"))
        coordinatorScope.Broadcast(TestBroadcast("two"))
        coordinatorScope.Broadcast(TestBroadcast("three"))

        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestBroadcast("three")), received)
    }

    // ══════════════════════════════════════════════════════════════════
    // Trigger edge cases
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `trigger with no listeners does not throw`() = testScope.runTest {
        coordinatorScope.Trigger(TestImpulse("nobody listening"))
    }

    @Test
    fun `listener added after trigger and before next trigger only gets second`() = testScope.runTest {
        coordinatorScope.Trigger(TestImpulse("missed"))
        testScheduler.advanceUntilIdle()

        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()

        coordinatorScope.Trigger(TestImpulse("caught"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("caught")), received)
    }

    // ══════════════════════════════════════════════════════════════════
    // Request edge cases
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `request flow emits correctly typed DataState`() = testScope.runTest {
        val stateFlow = switchBoard.handleRequest(
            FetchTestResult::class, TestResult::class, FetchTestResult(id = 1),
        )
        val received = mutableListOf<DataState<TestResult>>()

        val job = launch { stateFlow.collect { received.add(it) } }
        testScheduler.advanceUntilIdle()
        job.cancel()

        val success = received.filterIsInstance<DataState.Success<TestResult>>().first()
        assertEquals("result-for-1", success.data.data)
    }

    @Test
    fun `request emits Loading then Success for one-shot provider`() = testScope.runTest {
        val stateFlow = switchBoard.handleRequest(
            FetchTestResult::class, TestResult::class, FetchTestResult(id = 1),
        )
        val received = mutableListOf<DataState<TestResult>>()

        val job = launch { stateFlow.collect { received.add(it) } }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(received.any { it is DataState.Loading })
        assertTrue(received.any { it is DataState.Success })
        val loadIdx = received.indexOfFirst { it is DataState.Loading }
        val successIdx = received.indexOfFirst { it is DataState.Success }
        assertTrue(loadIdx < successIdx)
    }

    // ══════════════════════════════════════════════════════════════════
    // dispose edge cases
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `dispose mid-stream stops interceptor from affecting subsequent applies`() = runTest {
        val registry = InterceptorRegistry()
        val values = mutableListOf<String>()

        coordinatorScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { values.add(it.value) })
        )

        registry.applyInterceptors(TestBroadcast("first"))
        assertEquals(listOf("first"), values)

        coordinatorScope.dispose()

        registry.applyInterceptors(TestBroadcast("second"))
        assertEquals(listOf("first"), values)
    }

    @Test
    fun `dispose of one coordinatorScope does not affect another`() = runTest {
        val registry = InterceptorRegistry()
        val counter1 = AtomicInteger(0)
        val counter2 = AtomicInteger(0)

        val otherScope = CoordinatorScope(switchBoard, CoroutineScope(testDispatcher + Job()))

        coordinatorScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { counter1.incrementAndGet() })
        )
        otherScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { counter2.incrementAndGet() })
        )

        coordinatorScope.dispose()

        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, counter1.get())
        assertEquals(1, counter2.get())
    }

    // ══════════════════════════════════════════════════════════════════
    // Two CoordinatorScopes communicating via switchboard
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `one coordinator broadcasts state that another listens to`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collectLatest { received.add(it) }
        }

        coordinatorScope.Broadcast(TestBroadcast("from-first"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestBroadcast("from-first")), received)
    }

    @Test
    fun `one coordinator triggers impulse that another reacts to`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()

        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        coordinatorScope.Trigger(TestImpulse("ping"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("ping")), received)
    }

    // ══════════════════════════════════════════════════════════════════
    // Broadcast under pressure
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `flood broadcasts are all delivered to listener`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        val expected = (1..500).map { TestBroadcast("msg-$it") }
        expected.forEach { coordinatorScope.Broadcast(it) }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(expected, received)
    }

    @Test
    fun `broadcast replay only holds latest even after hundreds of emissions`() = testScope.runTest {
        (1..500).forEach { coordinatorScope.Broadcast(TestBroadcast("v$it")) }

        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestBroadcast("v500")), received)
    }

    @Test
    fun `concurrent broadcasts from multiple scopes interleave correctly`() = testScope.runTest {
        val scope2 = CoordinatorScope(switchBoard, CoroutineScope(testDispatcher + Job()))

        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        val jobs = (1..50).flatMap { i ->
            listOf(
                launch { coordinatorScope.Broadcast(TestBroadcast("a-$i")) },
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

    // ══════════════════════════════════════════════════════════════════
    // Impulse under pressure
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `flood triggers are all delivered in order`() = testScope.runTest {
        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        val expected = (1..500).map { TestImpulse("t-$it") }
        expected.forEach { coordinatorScope.Trigger(it) }
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(expected, received)
    }

    @Test
    fun `triggers from multiple scopes all arrive`() = testScope.runTest {
        val scope2 = CoordinatorScope(switchBoard, CoroutineScope(testDispatcher + Job()))

        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        val jobs = (1..50).flatMap { i ->
            listOf(
                launch { coordinatorScope.Trigger(TestImpulse("a-$i")) },
                launch { scope2.Trigger(TestImpulse("b-$i")) },
            )
        }
        jobs.joinAll()
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(100, received.size)
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle: dispose during active operations
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `dispose while listener is active stops delivery`() = runTest {
        val registry = InterceptorRegistry()
        val values = mutableListOf<String>()

        coordinatorScope.registrations.add(
            registry.add(Interceptor.read<TestBroadcast> { values.add(it.value) })
        )

        registry.applyInterceptors(TestBroadcast("1"))
        registry.applyInterceptors(TestBroadcast("2"))
        coordinatorScope.dispose()
        registry.applyInterceptors(TestBroadcast("3"))
        registry.applyInterceptors(TestBroadcast("4"))

        assertEquals(listOf("1", "2"), values)
    }

    @Test
    fun `broadcast after dispose still emits on switchboard`() = testScope.runTest {
        val received = mutableListOf<TestBroadcast>()
        val job = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { received.add(it) }
        }

        coordinatorScope.dispose()
        // Broadcast is a suspend on switchboard directly, so it still works
        // even though the coordinator's job is cancelled
        switchBoard.broadcastState(TestBroadcast("post-dispose"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertTrue(received.contains(TestBroadcast("post-dispose")))
    }

    // ══════════════════════════════════════════════════════════════════
    // Chained reactions
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `broadcast triggers listener that triggers impulse`() = testScope.runTest {
        val impulses = mutableListOf<TestImpulse>()

        val impulseJob = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { impulses.add(it) }
        }
        val chainJob = launch {
            switchBoard.stateFlow(TestBroadcast::class).collectLatest { broadcast ->
                coordinatorScope.Trigger(TestImpulse("reacted-to-${broadcast.value}"))
            }
        }

        coordinatorScope.Broadcast(TestBroadcast("origin"))
        testScheduler.advanceUntilIdle()
        impulseJob.cancel(); chainJob.cancel()

        assertEquals(listOf(TestImpulse("reacted-to-origin")), impulses)
    }

    @Test
    fun `chained broadcasts propagate through multiple listeners`() = testScope.runTest {
        val finalValues = mutableListOf<OtherBroadcast>()

        val step1 = launch {
            switchBoard.stateFlow(TestBroadcast::class).collectLatest { b ->
                coordinatorScope.Broadcast(OtherBroadcast(b.value.length))
            }
        }
        val step2 = launch {
            switchBoard.stateFlow(OtherBroadcast::class).collect { finalValues.add(it) }
        }

        coordinatorScope.Broadcast(TestBroadcast("hello"))
        testScheduler.advanceUntilIdle()
        step1.cancel(); step2.cancel()

        assertEquals(listOf(OtherBroadcast(5)), finalValues)
    }

    // ══════════════════════════════════════════════════════════════════
    // Interceptor stress
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `many interceptors all fire on single apply`() = runTest {
        val registry = InterceptorRegistry()
        val counter = AtomicInteger(0)

        val regs = (1..100).map {
            registry.add(Interceptor.read<TestBroadcast> { counter.incrementAndGet() })
        }
        coordinatorScope.registrations.addAll(regs)

        registry.applyInterceptors(TestBroadcast("go"))
        assertEquals(100, counter.get())
    }

    @Test
    fun `dispose with many registrations cleans all`() = runTest {
        val registry = InterceptorRegistry()
        val counter = AtomicInteger(0)

        val regs = (1..100).map {
            registry.add(Interceptor.read<TestBroadcast> { counter.incrementAndGet() })
        }
        coordinatorScope.registrations.addAll(regs)

        coordinatorScope.dispose()
        registry.applyInterceptors(TestBroadcast("after"))
        assertEquals(0, counter.get())
        assertTrue(coordinatorScope.registrations.isEmpty())
    }

    @Test
    fun `partial dispose only removes owned registrations from large set`() = runTest {
        val registry = InterceptorRegistry()
        val ownCounter = AtomicInteger(0)
        val foreignCounter = AtomicInteger(0)

        val ownRegs = (1..50).map {
            registry.add(Interceptor.read<TestBroadcast> { ownCounter.incrementAndGet() })
        }
        (1..50).forEach { _ ->
            registry.add(Interceptor.read<TestBroadcast> { foreignCounter.incrementAndGet() })
        }
        coordinatorScope.registrations.addAll(ownRegs)

        coordinatorScope.dispose()
        registry.applyInterceptors(TestBroadcast("check"))

        assertEquals(0, ownCounter.get())
        assertEquals(50, foreignCounter.get())
    }

    // ══════════════════════════════════════════════════════════════════
    // Multiple switchboards are isolated
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `two switchboards do not leak broadcasts`() = testScope.runTest {
        val otherSwitchBoard = DefaultSwitchBoard(scope = CoroutineScope(testDispatcher + Job()))
        val otherCoord = CoordinatorScope(otherSwitchBoard, CoroutineScope(testDispatcher + Job()))

        val fromFirst = mutableListOf<TestBroadcast>()
        val fromSecond = mutableListOf<TestBroadcast>()

        val job1 = launch {
            switchBoard.stateFlow(TestBroadcast::class).collect { fromFirst.add(it) }
        }
        val job2 = launch {
            otherSwitchBoard.stateFlow(TestBroadcast::class).collect { fromSecond.add(it) }
        }

        coordinatorScope.Broadcast(TestBroadcast("first-only"))
        otherCoord.Broadcast(TestBroadcast("second-only"))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        assertEquals(listOf(TestBroadcast("first-only")), fromFirst)
        assertEquals(listOf(TestBroadcast("second-only")), fromSecond)
    }

    @Test
    fun `two switchboards do not leak impulses`() = testScope.runTest {
        val otherSwitchBoard = DefaultSwitchBoard(scope = CoroutineScope(testDispatcher + Job()))
        val otherCoord = CoordinatorScope(otherSwitchBoard, CoroutineScope(testDispatcher + Job()))

        val fromFirst = mutableListOf<TestImpulse>()
        val fromSecond = mutableListOf<TestImpulse>()

        val job1 = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { fromFirst.add(it) }
        }
        val job2 = launch {
            otherSwitchBoard.impulseFlow(TestImpulse::class).collect { fromSecond.add(it) }
        }

        coordinatorScope.Trigger(TestImpulse("first-only"))
        otherCoord.Trigger(TestImpulse("second-only"))
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

    // ══════════════════════════════════════════════════════════════════
    // Generic type erasure
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `broadcasts with generic types collide due to type erasure`() = testScope.runTest {
        val receivedStrings = mutableListOf<NodeScopeTest.BoxedBroadcast<String>>()
        val receivedInts = mutableListOf<NodeScopeTest.BoxedBroadcast<Int>>()

        val job1 = launch {
            @Suppress("UNCHECKED_CAST")
            switchBoard.stateFlow(NodeScopeTest.BoxedBroadcast::class).collect {
                if (it.value is String) receivedStrings.add(it as NodeScopeTest.BoxedBroadcast<String>)
            }
        }

        val job2 = launch {
            @Suppress("UNCHECKED_CAST")
            switchBoard.stateFlow(NodeScopeTest.BoxedBroadcast::class).collect {
                if (it.value is Int) receivedInts.add(it as NodeScopeTest.BoxedBroadcast<Int>)
            }
        }

        coordinatorScope.Broadcast(NodeScopeTest.BoxedBroadcast("hello"))
        coordinatorScope.Broadcast(NodeScopeTest.BoxedBroadcast(42))
        testScheduler.advanceUntilIdle()
        job1.cancel(); job2.cancel()

        assertEquals(listOf(NodeScopeTest.BoxedBroadcast("hello")), receivedStrings)
        assertEquals(listOf(NodeScopeTest.BoxedBroadcast(42)), receivedInts)
    }

    // ══════════════════════════════════════════════════════════════════
    // Cross-communication: Coordinator ↔ Node
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `node trigger is received by coordinator listener`() = testScope.runTest {
        val stateHolder = SimpleMutableState(TestState())
        val contextScope = ContextScope("ctx", switchBoard)
        val nodeScope = NodeScope(contextScope, stateHolder, testScope)

        val received = mutableListOf<TestImpulse>()
        val job = launch {
            switchBoard.impulseFlow(TestImpulse::class).collect { received.add(it) }
        }

        nodeScope.Trigger(TestImpulse("from-node"))
        testScheduler.advanceUntilIdle()
        job.cancel()

        assertEquals(listOf(TestImpulse("from-node")), received)
    }
}