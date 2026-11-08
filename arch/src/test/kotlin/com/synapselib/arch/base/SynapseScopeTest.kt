package com.synapselib.arch.base

import androidx.lifecycle.Lifecycle
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A reusable helper written once against the common scope type. */
private suspend fun SynapseScope.announce(value: String) {
    Broadcast(TestBroadcast(value))
    Trigger(TestImpulse(value))
}

private data class Traced(val trace: TraceContext, val point: InterceptPoint, val data: Any)

/**
 * Tests for the shared [SynapseScope] verbs and for [TraceContext] propagation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SynapseScopeTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var switchBoard: DefaultSwitchBoard
    private val traces = CopyOnWriteArrayList<Traced>()

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        switchBoard = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            providerRegistry = testProviderRegistry(),
            workerContext = testDispatcher,
        )
        switchBoard.setTraceListener { trace, point, _, data -> traces += Traced(trace, point, data) }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun contextScope(tag: String? = null) =
        ContextScope("ctx", switchBoard, CoroutineScope(testDispatcher + SupervisorJob()), tag)

    private fun nodeScope(tag: String? = null) =
        NodeScope(contextScope(tag), SimpleMutableState(TestState()), testScope)

    private fun providerScope(tag: String? = null) =
        ProviderScope(switchBoard, CoroutineScope(testDispatcher + SupervisorJob()), tag)

    private fun coordinator(tag: String? = null) =
        CoordinatorScope(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED), tag)

    // ── Shared verbs ───────────────────────────────────────────────────

    @Test
    fun `every DSL scope is a SynapseScope sharing the same verbs`() = testScope.runTest {
        val scopes: List<SynapseScope> = listOf(contextScope(), nodeScope(), providerScope(), coordinator())
        val states = mutableListOf<TestBroadcast>()
        val impulses = mutableListOf<TestImpulse>()
        val jobs = listOf(
            launch { switchBoard.stateFlow(TestBroadcast::class).collect { states += it } },
            launch { switchBoard.impulseFlow(TestImpulse::class).collect { impulses += it } },
        )

        scopes.forEachIndexed { i, scope ->
            assertSame(switchBoard, scope.switchboard)
            scope.announce("s$i")
        }
        jobs.forEach { it.cancel() }

        assertEquals((0..3).map { TestBroadcast("s$it") }, states)
        assertEquals((0..3).map { TestImpulse("s$it") }, impulses)
    }

    @Test
    fun `flow verbs are available on context and provider scopes`() = testScope.runTest {
        val ctx = contextScope()
        val provider = providerScope()

        ctx.Broadcast(TestBroadcast("latest"))
        assertEquals(TestBroadcast("latest"), provider.ListenFor<TestBroadcast>().first())

        val reacted = mutableListOf<TestImpulse>()
        val job = launch { ctx.ReactTo<TestImpulse>().collect { reacted += it } }
        provider.Trigger(TestImpulse("x"))
        job.cancel()
        assertEquals(listOf(TestImpulse("x")), reacted)

        val result = ctx.Request(FetchTestResult(7)).first { it is DataState.Success }
        assertEquals(DataState.Success(TestResult("result-for-7")), result)
    }

    @Test
    fun `node scope inherits switchboard and tag from its context`() {
        val node = nodeScope(tag = "Screen")
        assertSame(switchBoard, node.switchboard)
        assertEquals("Screen", node.tag)
    }

    // ── Tagging ────────────────────────────────────────────────────────

    @Test
    fun `tagged scopes of every kind trace Broadcast and Trigger`() = testScope.runTest {
        val scopes = listOf(contextScope("ctx"), nodeScope("node"), providerScope("provider"), coordinator("coord"))
        scopes.forEach { it.announce(it.tag!!) }

        val upstream = traces.filter { it.point.direction == Direction.UPSTREAM }
        assertEquals(
            listOf("ctx", "ctx", "node", "node", "provider", "provider", "coord", "coord"),
            upstream.map { it.trace.emitterTag },
        )
        assertTrue(upstream.all { it.trace.parentTraceId == null }, "root emissions have no parent")
        assertEquals(upstream.size, upstream.map { it.trace.traceId }.toSet().size, "one trace point per emission")
    }

    @Test
    fun `untagged scopes create no TraceContext`() = testScope.runTest {
        listOf(contextScope(), nodeScope(), providerScope(), coordinator()).forEach { it.announce("x") }
        assertTrue(traces.isEmpty())
    }

    @Test
    fun `tagged Request traces the request pipeline`() = testScope.runTest {
        val coord = coordinator("Fetcher")
        coord.Request(FetchTestResult(1)).first { it is DataState.Success }

        val requestTraces = traces.filter { it.point.channel == Channel.REQUEST }
        assertEquals(
            listOf(InterceptPoint.REQUEST_UPSTREAM, InterceptPoint.REQUEST_DOWNSTREAM),
            requestTraces.map { it.point },
        )
        assertTrue(requestTraces.all { it.trace.emitterTag == "Fetcher" })
        assertEquals(1, requestTraces.map { it.trace.traceId }.toSet().size, "one trace per collection")
    }

    @Test
    fun `tagged Request handler overload is traced`() = testScope.runTest {
        val coord = coordinator("Fetcher")
        val results = mutableListOf<DataState<TestResult>>()
        coord.Request(FetchTestResult(2)) { results += it }
        testScheduler.advanceUntilIdle()

        assertEquals(DataState.Success(TestResult("result-for-2")), results.last())
        assertTrue(traces.any { it.point == InterceptPoint.REQUEST_UPSTREAM && it.trace.emitterTag == "Fetcher" })
        coord.dispose()
    }

    @Test
    fun `untagged Request is not traced`() = testScope.runTest {
        coordinator().Request(FetchTestResult(1)).first { it is DataState.Success }
        assertTrue(traces.isEmpty())
    }

    // ── Parent propagation ─────────────────────────────────────────────

    @Test
    fun `tagged emissions inside an ambient trace link to it`() = testScope.runTest {
        val parent = TraceContext(emitterTag = "Parent")
        val coord = coordinator("Child")

        withContext(parent) {
            coord.Broadcast(TestBroadcast("a"))
            coord.Trigger(TestImpulse("b"))
        }

        val upstream = traces.filter { it.point.direction == Direction.UPSTREAM }
        assertEquals(2, upstream.size)
        assertTrue(upstream.all { it.trace.parentTraceId == parent.traceId })
        assertTrue(upstream.all { it.trace.emitterTag == "Child" })
        assertNotEquals(upstream[0].trace.traceId, upstream[1].trace.traceId)
    }

    @Test
    fun `untagged scope inside an ambient trace reports that trace unchanged`() = testScope.runTest {
        val parent = TraceContext(emitterTag = "Parent")
        withContext(parent) { contextScope().announce("x") }

        val upstream = traces.filter { it.point.direction == Direction.UPSTREAM }
        assertEquals(2, upstream.size)
        assertTrue(upstream.all { it.trace === parent })
    }

    @Test
    fun `nested tagged scopes build a causal chain`() = testScope.runTest {
        val outer = coordinator("Outer")
        val inner = providerScope("Inner")

        withTrace("Root") {
            val root = currentTraceContext()!!
            outer.Broadcast(TestBroadcast("outer"))
            withTrace("Step") {
                val step = currentTraceContext()!!
                assertEquals(root.traceId, step.parentTraceId)
                inner.Trigger(TestImpulse("inner"))
                val emitted = traces.last { it.point == InterceptPoint.REACTION_UPSTREAM }.trace
                assertEquals(step.traceId, emitted.parentTraceId)
                assertEquals("Inner", emitted.emitterTag)
            }
            val outerTrace = traces.first { it.point == InterceptPoint.STATE_UPSTREAM }.trace
            assertEquals(root.traceId, outerTrace.parentTraceId)
            assertEquals("Outer", outerTrace.emitterTag)
        }
    }

    @Test
    fun `tagged Request inside an ambient trace links to it`() = testScope.runTest {
        val parent = TraceContext(emitterTag = "Screen")
        withContext(parent) { coordinator("Fetcher").Request(FetchTestResult(3)).first { it is DataState.Success } }

        val upstream = traces.single { it.point == InterceptPoint.REQUEST_UPSTREAM }.trace
        assertEquals(parent.traceId, upstream.parentTraceId)
    }

    @Test
    fun `listener handlers do not inherit the trace of the event they handle`() = testScope.runTest {
        // Documented limitation: SharedFlows carry no trace metadata, so the hop
        // through the SwitchBoard starts a new root.
        val emitter = coordinator("Emitter")
        val reactor = coordinator("Reactor")
        reactor.ReactTo<TestImpulse> { launch { Broadcast(TestBroadcast("reaction")) } }

        emitter.Trigger(TestImpulse("go"))
        testScheduler.advanceUntilIdle()

        val reaction = traces.single { it.point == InterceptPoint.STATE_UPSTREAM }.trace
        assertEquals("Reactor", reaction.emitterTag)
        assertNull(reaction.parentTraceId)
        reactor.dispose()
    }

    @Test
    fun `untagged emission without ambient trace does not touch the trace listener`() = testScope.runTest {
        contextScope().Broadcast(TestBroadcast("fast"))
        assertTrue(traces.isEmpty())
    }

    @Test
    fun `provider scope tag traces emissions made from produce`() = testScope.runTest {
        val registry = ProviderRegistry.Builder()
            .register<TestResult, FetchTestResult> {
                object : Provider<FetchTestResult, TestResult>() {
                    override fun ProviderScope.produce(impulse: FetchTestResult): Flow<TestResult> = flow {
                        // ProviderManager constructs an untagged scope; a provider
                        // can still trace its own work explicitly.
                        withTrace("Provider") { Trigger(TestImpulse("from-provider")) }
                        emit(TestResult("ok"))
                    }
                }
            }
            .build()
        val board = DefaultSwitchBoard(CoroutineScope(testDispatcher + SupervisorJob()), registry, testDispatcher)
        val local = CopyOnWriteArrayList<TraceContext>()
        board.setTraceListener { trace, point, _, _ -> if (point == InterceptPoint.REACTION_UPSTREAM) local += trace }

        board.handleRequest(FetchTestResult(1)).first { it is DataState.Success }

        assertEquals(listOf("Provider"), local.map { it.emitterTag })
        assertNull(local.single().parentTraceId, "requester traces do not cross into providers")
    }

    // ── TraceContext helpers ───────────────────────────────────────────

    @Test
    fun `child links to parent and inherits tag by default`() {
        val parent = TraceContext(emitterTag = "P")
        val child = parent.child()
        assertEquals(parent.traceId, child.parentTraceId)
        assertEquals("P", child.emitterTag)
        assertEquals("Q", parent.child("Q").emitterTag)
        assertNotEquals(parent.traceId, child.traceId)
    }

    @Test
    fun `childOf null creates a root trace`() {
        val root = TraceContext.childOf(null, "R")
        assertNull(root.parentTraceId)
        assertEquals("R", root.emitterTag)
    }

    @Test
    fun `withTrace installs a linked trace for its block`() = testScope.runTest {
        assertNull(currentTraceContext())
        withTrace("A") {
            val a = currentTraceContext()!!
            assertNull(a.parentTraceId)
            withTrace("B") {
                assertEquals(a.traceId, currentTraceContext()!!.parentTraceId)
            }
            assertSame(a, currentTraceContext())
        }
        assertNull(currentTraceContext())
    }
}
