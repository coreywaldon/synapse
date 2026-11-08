package com.synapselib.arch.base

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No-op applier: these tests only exercise composition, effects and state. */
private class NoOpApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun onClear() = Unit
}

/** Counts [addInterceptor] calls made through it. */
private class CountingSwitchBoard(private val delegate: SwitchBoard) : SwitchBoard by delegate {
    val adds = AtomicInteger(0)
    override fun <T : Any> addInterceptor(
        point: InterceptPoint,
        clazz: kotlin.reflect.KClass<T>,
        interceptor: Interceptor<T>,
        priority: Int,
    ): Registration {
        adds.incrementAndGet()
        return delegate.addInterceptor(point, clazz, interceptor, priority)
    }
}

/** Drives a headless composition on the JVM. */
private class Harness(scope: CoroutineScope) {
    private val clock = BroadcastFrameClock()
    private val recomposer = Recomposer(scope.coroutineContext + clock)
    private val loop = scope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    private val composition = Composition(NoOpApplier(), recomposer)

    fun setContent(content: @Composable () -> Unit) = composition.setContent(content)

    /** Applies pending snapshot writes and runs one frame of recomposition. */
    fun recompose() {
        Snapshot.sendApplyNotifications()
        clock.sendFrame(0L)
    }

    fun dispose() {
        composition.dispose()
        recomposer.cancel()
        loop.cancel()
    }
}

/**
 * Tests for [NodeScope.Intercept] (composable, registered once per key) and
 * [NodeScope.addInterceptor] (imperative, tracked for disposal).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeInterceptTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var switchBoard: DefaultSwitchBoard
    private lateinit var counting: CountingSwitchBoard
    private lateinit var nodeScope: NodeScope<String, TestState>

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        switchBoard = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            providerRegistry = testProviderRegistry(),
            workerContext = testDispatcher,
        )
        counting = CountingSwitchBoard(switchBoard)
        nodeScope = NodeScope(
            ContextScope("ctx", counting, CoroutineScope(testDispatcher + SupervisorJob())),
            SimpleMutableState(TestState()),
            testScope,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Broadcasts once and returns the transformed value seen by a listener. */
    private suspend fun probe(): String {
        var seen = ""
        val interceptorRead = Interceptor.read<TestBroadcast> { seen = it.value }
        val reg = switchBoard.addInterceptor(InterceptPoint.STATE_UPSTREAM, interceptorRead, priority = Int.MAX_VALUE)
        switchBoard.broadcastState(TestBroadcast(""))
        reg.unregister()
        return seen
    }

    private fun appender(suffix: String) = Interceptor.transform<TestBroadcast> { it.copy(value = it.value + suffix) }

    // ── Composable Intercept ───────────────────────────────────────────

    @Test
    fun `Intercept registers once across recompositions`() = testScope.runTest {
        val harness = Harness(this)
        val tick: MutableState<Int> = mutableStateOf(0)

        harness.setContent {
            tick.value // read to recompose on change
            nodeScope.Intercept(InterceptPoint.STATE_UPSTREAM, appender("x"))
        }
        repeat(5) {
            tick.value++
            harness.recompose()
        }

        assertEquals("x", probe(), "exactly one interceptor despite 5 recompositions")
        assertEquals(1, counting.adds.get())
        harness.dispose()
    }

    @Test
    fun `Intercept always invokes the latest interceptor lambda`() = testScope.runTest {
        val harness = Harness(this)
        val suffix = mutableStateOf("a")

        harness.setContent {
            val current = suffix.value
            nodeScope.Intercept(InterceptPoint.STATE_UPSTREAM, appender(current))
        }
        assertEquals("a", probe())

        suffix.value = "b"
        harness.recompose()
        assertEquals("b", probe())
        harness.dispose()
    }

    @Test
    fun `Intercept re-registers when key changes`() = testScope.runTest {
        val harness = Harness(this)
        val key = mutableStateOf(1)

        harness.setContent {
            nodeScope.Intercept(
                InterceptPoint.STATE_UPSTREAM,
                appender("k"),
                key = key.value,
            )
        }
        assertEquals("k", probe())
        assertEquals(1, counting.adds.get())

        key.value = 2
        harness.recompose()
        assertEquals("k", probe(), "old registration removed, new one added")
        assertEquals(2, counting.adds.get())
        harness.dispose()
    }

    @Test
    fun `Intercept is removed when it leaves the composition`() = testScope.runTest {
        val harness = Harness(this)
        val show = mutableStateOf(true)

        harness.setContent {
            if (show.value) nodeScope.Intercept(InterceptPoint.STATE_UPSTREAM, appender("x"))
        }
        assertEquals("x", probe())

        show.value = false
        harness.recompose()
        assertEquals("", probe())
        harness.dispose()
    }

    @Test
    fun `Intercept inside a real Node is unregistered when the node is disposed`() = testScope.runTest {
        val harness = Harness(this)
        val show = mutableStateOf(true)
        val tick = mutableStateOf(0)

        harness.setContent {
            CompositionLocalProvider(LocalSwitchBoard provides switchBoard) {
                if (show.value) {
                    CreateContext("ctx") {
                        Node(initialState = TestState()) {
                            tick.value
                            Intercept(InterceptPoint.STATE_UPSTREAM, appender("n"))
                        }
                    }
                }
            }
        }
        repeat(3) {
            tick.value++
            harness.recompose()
        }
        assertEquals("n", probe())

        show.value = false
        harness.recompose()
        assertEquals("", probe())
        harness.dispose()
    }

    // ── Imperative addInterceptor ──────────────────────────────────────

    @Test
    fun `addInterceptor is tracked and removed on dispose`() = testScope.runTest {
        nodeScope.addInterceptor(InterceptPoint.STATE_UPSTREAM, appender("1"))
        nodeScope.addInterceptor(InterceptPoint.STATE_UPSTREAM, appender("2"))
        assertEquals("12", probe())
        assertEquals(2, nodeScope.registrations.size)

        nodeScope.dispose()

        assertTrue(nodeScope.isDisposed)
        assertEquals("", probe())
        assertTrue(nodeScope.registrations.isEmpty())
    }

    @Test
    fun `addInterceptor handle unregisters and stops tracking`() = testScope.runTest {
        val reg = nodeScope.addInterceptor(InterceptPoint.STATE_UPSTREAM, appender("1"))
        reg.unregister()
        assertEquals("", probe())
        assertTrue(nodeScope.registrations.isEmpty())
    }

    @Test
    fun `addInterceptor after dispose is unregistered immediately`() = testScope.runTest {
        nodeScope.dispose()
        nodeScope.addInterceptor(InterceptPoint.STATE_UPSTREAM, appender("late"))
        assertEquals("", probe())
        assertTrue(nodeScope.registrations.isEmpty())
    }

    @Test
    fun `isDisposed is false before dispose`() {
        assertFalse(nodeScope.isDisposed)
    }

    @Test
    fun `concurrent addInterceptor and dispose never leak`() = kotlinx.coroutines.runBlocking {
        repeat(50) {
            val node = NodeScope(
                ContextScope("ctx", switchBoard, CoroutineScope(SupervisorJob())),
                SimpleMutableState(TestState()),
                CoroutineScope(SupervisorJob()),
            )
            val threads = 4
            val pool = Executors.newFixedThreadPool(threads + 1)
            val barrier = CyclicBarrier(threads + 1)
            val done = CountDownLatch(threads + 1)
            repeat(threads) {
                pool.execute {
                    barrier.await()
                    repeat(50) { node.addInterceptor(InterceptPoint.STATE_UPSTREAM, appender("!")) }
                    done.countDown()
                }
            }
            pool.execute {
                barrier.await()
                node.dispose()
                done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            pool.shutdown()

            assertTrue(node.registrations.isEmpty())
            assertEquals("", probe())
        }
    }
}
