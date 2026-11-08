package com.synapselib.arch.base

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Disposal, thread-safety and lifecycle-free coordinator tests for
 * [CoordinatorScope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorDisposalTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var switchBoard: DefaultSwitchBoard

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        switchBoard = DefaultSwitchBoard(
            scope = CoroutineScope(testDispatcher + SupervisorJob()),
            providerRegistry = testProviderRegistry(),
            workerContext = testDispatcher,
        )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val readCount = AtomicInteger(0)
    private val countingInterceptor = Interceptor.read<TestBroadcast> { readCount.incrementAndGet() }

    private suspend fun interceptorHits(): Int {
        readCount.set(0)
        switchBoard.broadcastState(TestBroadcast("probe"))
        return readCount.get()
    }

    // ── isDisposed / Intercept after dispose ───────────────────────────

    @Test
    fun `isDisposed flips on dispose`() {
        val coord = CoordinatorScope(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED))
        assertFalse(coord.isDisposed)
        coord.dispose()
        assertTrue(coord.isDisposed)
    }

    @Test
    fun `Intercept after dispose is unregistered immediately`() = testScope.runTest {
        val coord = CoordinatorScope(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED))
        coord.dispose()

        coord.Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)

        assertEquals(0, interceptorHits())
        assertTrue(coord.registrations.isEmpty())
    }

    @Test
    fun `Intercept registration handle unregisters and stops tracking`() = testScope.runTest {
        val coord = CoordinatorScope(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED))
        val registration = coord.Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)
        assertEquals(1, interceptorHits())
        assertEquals(1, coord.registrations.size)

        registration.unregister()

        assertEquals(0, interceptorHits())
        assertTrue(coord.registrations.isEmpty())
        coord.dispose()
    }

    @Test
    fun `dispose unregisters interceptors added through Intercept`() = testScope.runTest {
        val coord = Coordinator(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED)) {
            Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)
            Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)
        }
        assertEquals(2, interceptorHits())

        coord.dispose()
        assertEquals(0, interceptorHits())
    }

    @Test
    fun `lifecycle hooks registered after dispose are ignored`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val coord = CoordinatorScope(switchBoard, owner)
        coord.dispose()

        var fired = 0
        coord.onStart { fired++ }
        coord.onStop { fired++ }
        owner.moveTo(Lifecycle.State.CREATED)
        owner.moveTo(Lifecycle.State.RESUMED)

        assertEquals(0, fired)
        assertTrue(coord.lifecycleRegistrations.isEmpty())
    }

    // ── Owner already destroyed ────────────────────────────────────────

    @Test
    fun `coordinator on destroyed owner is disposed immediately`() = testScope.runTest {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        owner.moveTo(Lifecycle.State.DESTROYED)
        var blockRan = false
        var hookFired = 0
        val received = mutableListOf<TestImpulse>()

        val coord = Coordinator(switchBoard, owner) {
            blockRan = true
            Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)
            onStart { hookFired++ }
            ReactTo<TestImpulse> { received += it }
        }

        assertTrue(blockRan)
        assertTrue(coord.isDisposed)
        assertEquals(0, hookFired)
        assertEquals(0, interceptorHits())
        switchBoard.triggerImpulse(TestImpulse("ignored"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `builder block that throws disposes the coordinator`() = testScope.runTest {
        var captured: CoordinatorScope? = null
        assertThrows<IllegalStateException> {
            Coordinator(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED)) {
                captured = this
                Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)
                error("boom")
            }
        }
        assertTrue(captured!!.isDisposed)
        assertEquals(0, interceptorHits())
    }

    // ── onDispose ──────────────────────────────────────────────────────

    @Test
    fun `onDispose runs once on manual dispose`() {
        val coord = CoordinatorScope(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED))
        var calls = 0
        coord.onDispose { calls++ }

        coord.dispose()
        coord.dispose()
        assertEquals(1, calls)
    }

    @Test
    fun `onDispose runs on ON_DESTROY after onDestroy hooks`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val coord = CoordinatorScope(switchBoard, owner)
        val order = mutableListOf<String>()
        coord.onDispose { order += "dispose" }
        coord.onDestroy { order += "destroy" }

        owner.moveTo(Lifecycle.State.DESTROYED)
        assertEquals(listOf("destroy", "dispose"), order)
        assertTrue(coord.isDisposed)
    }

    @Test
    fun `onDispose after dispose runs immediately`() {
        val coord = CoordinatorScope(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED))
        coord.dispose()
        var calls = 0
        coord.onDispose { calls++ }
        assertEquals(1, calls)
    }

    // ── Thread safety ──────────────────────────────────────────────────

    @Test
    fun `concurrent Intercept and dispose never leak registrations`() = kotlinx.coroutines.runBlocking {
        repeat(50) {
            val coord = CoordinatorScope(switchBoard, CoroutineScope(SupervisorJob()))
            val threads = 4
            val perThread = 50
            val pool = Executors.newFixedThreadPool(threads + 1)
            val barrier = CyclicBarrier(threads + 1)
            val done = CountDownLatch(threads + 1)
            repeat(threads) {
                pool.execute {
                    barrier.await()
                    repeat(perThread) { coord.Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor) }
                    done.countDown()
                }
            }
            pool.execute {
                barrier.await()
                coord.dispose()
                done.countDown()
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            pool.shutdown()

            assertTrue(coord.registrations.isEmpty())
            assertEquals(0, interceptorHits(), "no interceptor may survive dispose")
        }
    }

    @Test
    fun `concurrent lifecycle hook registration is safe`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.CREATED)
        val coord = CoordinatorScope(switchBoard, owner)
        val fired = AtomicInteger(0)
        val threads = 8
        val perThread = 100
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                repeat(perThread) { coord.onStart { fired.incrementAndGet() } }
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(threads * perThread, coord.lifecycleRegistrations[Lifecycle.Event.ON_START]?.size)
        owner.moveTo(Lifecycle.State.STARTED)
        assertEquals(threads * perThread, fired.get())
        coord.dispose()
    }

    // ── Lifecycle-free coordinator ─────────────────────────────────────

    @Test
    fun `scope-bound coordinator has no lifecycle owner and works`() = testScope.runTest {
        val parent = CoroutineScope(testDispatcher + SupervisorJob())
        val received = mutableListOf<TestImpulse>()
        val coord = Coordinator(switchBoard, parent) {
            ReactTo<TestImpulse> { received += it }
        }

        assertNull(coord.lifecycleOwner)
        coord.Trigger(TestImpulse("hello"))
        assertEquals(listOf(TestImpulse("hello")), received)
        coord.dispose()
        parent.cancel()
    }

    @Test
    fun `scope-bound coordinator is disposed when its scope is cancelled`() = testScope.runTest {
        val parent = CoroutineScope(testDispatcher + SupervisorJob())
        var disposedCallbacks = 0
        val received = mutableListOf<TestImpulse>()
        val coord = Coordinator(switchBoard, parent) {
            Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)
            ReactTo<TestImpulse> { received += it }
            onDispose { disposedCallbacks++ }
        }
        assertEquals(1, interceptorHits())

        parent.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue(coord.isDisposed)
        assertEquals(1, disposedCallbacks)
        assertEquals(0, interceptorHits())
        switchBoard.triggerImpulse(TestImpulse("after"))
        assertTrue(received.isEmpty())
    }

    @Test
    fun `disposing a scope-bound coordinator does not cancel its scope`() = testScope.runTest {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(testDispatcher + parentJob)
        val coord = Coordinator(switchBoard, parent) { }

        coord.dispose()

        assertTrue(parentJob.isActive)
        assertTrue(parent.isActive)
        parent.cancel()
    }

    @Test
    fun `scope-bound coordinator uses a child job of the scope`() {
        val parentJob = SupervisorJob()
        val coord = CoordinatorScope(switchBoard, CoroutineScope(testDispatcher + parentJob))
        val childJob = coord.coroutineContext[Job]!!
        assertSame(parentJob, childJob.parent)
        coord.dispose()
        assertFalse(parentJob.children.any())
    }

    @Test
    fun `lifecycle hooks on a scope-bound coordinator throw`() {
        val coord = CoordinatorScope(switchBoard, CoroutineScope(testDispatcher + SupervisorJob()))
        assertThrows<IllegalStateException> { coord.onCreate { } }
        assertThrows<IllegalStateException> { coord.onStart { } }
        assertThrows<IllegalStateException> { coord.onResume { } }
        assertThrows<IllegalStateException> { coord.onPause { } }
        assertThrows<IllegalStateException> { coord.onStop { } }
        assertThrows<IllegalStateException> { coord.onDestroy { } }
        assertTrue(coord.lifecycleRegistrations.isEmpty())
        coord.dispose()
    }

    @Test
    fun `child coordinator bound to a parent coordinator is disposed with it`() = testScope.runTest {
        val parent = Coordinator(switchBoard, SteppedLifecycleOwner(Lifecycle.State.RESUMED)) { }
        val child = Coordinator(switchBoard, parent) {
            Intercept(InterceptPoint.STATE_UPSTREAM, countingInterceptor)
        }
        assertEquals(1, interceptorHits())

        parent.dispose()
        testScheduler.advanceUntilIdle()

        assertTrue(child.isDisposed)
        assertEquals(0, interceptorHits())
    }

    @Test
    fun `scope-bound coordinator handler jobs are children of the coordinator`() = testScope.runTest {
        val parent = CoroutineScope(testDispatcher + SupervisorJob())
        val coord = Coordinator(switchBoard, parent) { }
        val job = coord.launch { kotlinx.coroutines.awaitCancellation() }
        assertTrue(job.isActive)
        parent.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(coord.isDisposed)
    }
}
