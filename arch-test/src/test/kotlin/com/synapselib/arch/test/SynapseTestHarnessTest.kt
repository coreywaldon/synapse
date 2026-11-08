package com.synapselib.arch.test

import androidx.lifecycle.Lifecycle
import com.synapselib.arch.base.Channel
import com.synapselib.arch.base.Coordinator
import com.synapselib.arch.base.Direction
import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.TraceContext
import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Framework-agnostic use of [SynapseTestHarness]: start / close semantics. */
@OptIn(ExperimentalCoroutinesApi::class)
class SynapseTestHarnessTest {

    // ── Start ───────────────────────────────────────────────────────────

    @Test
    fun accessBeforeStartFailsWithDescriptiveMessage() {
        val harness = SynapseTestHarness()
        assertFalse(harness.isStarted)
        val error = assertThrows<IllegalStateException> { harness.switchBoard }
        assertTrue(error.message!!.contains("start()"), error.message)
        assertThrows<IllegalStateException> { harness.lifecycleOwner }
        assertThrows<IllegalStateException> { harness.coroutineScope }
    }

    @Test
    fun startTwiceFails() {
        val harness = SynapseTestHarness().start()
        try {
            assertThrows<IllegalStateException> { harness.start() }
        } finally {
            harness.close()
        }
    }

    @Test
    fun startInstallsTestDispatcherAsMain() {
        SynapseTestHarness().start().use { harness ->
            var ranOnMain = false
            harness.coroutineScope.launch(Dispatchers.Main) { ranOnMain = true }
            assertTrue(ranOnMain)
            assertTrue(harness.isStarted)
        }
    }

    @Test
    fun configureRunsLazilyAtEveryStart() {
        var runs = 0
        val harness = SynapseTestHarness { runs++ }
        assertEquals(0, runs)
        harness.start().close()
        harness.start().close()
        assertEquals(2, runs)
    }

    // ── Close ───────────────────────────────────────────────────────────

    @Test
    fun closeClosesBoardAndCancelsScope() {
        val harness = SynapseTestHarness().start()
        val board = harness.switchBoard
        val scope = harness.coroutineScope

        harness.close()

        assertTrue(board.isClosed)
        assertFalse(scope.isActive)
        assertFalse(harness.isStarted)
    }

    @Test
    fun closeIsIdempotentAndSafeBeforeStart() {
        val harness = SynapseTestHarness()
        harness.close()
        harness.start()
        harness.close()
        harness.close()
        assertFalse(harness.isStarted)
    }

    @Test
    fun closeCancelsRunningProviderJobs() {
        var providerCancelled = false
        val harness = SynapseTestHarness {
            provideFlow<List<String>, FetchItems> {
                flow {
                    try {
                        emit(listOf("a"))
                        awaitCancellation()
                    } finally {
                        providerCancelled = true
                    }
                }
            }
        }.start()
        val received = CopyOnWriteArrayList<DataState<List<String>>>()
        harness.coroutineScope.launch { harness.Request(FetchItems("q")).collect { received += it } }
        harness.testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(received.contains(DataState.Success(listOf("a"))), "received: $received")
        assertFalse(providerCancelled)

        harness.close()
        harness.testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(providerCancelled)
    }

    @Test
    fun closeDisposesHelperCoordinatorsAndRunsOnDispose() {
        val harness = SynapseTestHarness().start()
        var disposed = 0
        val lifecycleBound = harness.Coordinator { onDispose { disposed++ } }
        val lifecycleFree = harness.Coordinator(harness.coroutineScope) { onDispose { disposed++ } }

        harness.close()

        assertTrue(lifecycleBound.isDisposed)
        assertTrue(lifecycleFree.isDisposed)
        assertEquals(2, disposed)
    }

    @Test
    fun closeDestroysLifecycleSoUserCreatedCoordinatorsAreDisposed() {
        val harness = SynapseTestHarness().start()
        var destroyed = false
        // Created with the top-level builder, NOT the tracked helper.
        val coordinator = Coordinator(harness.switchBoard, harness.lifecycleOwner) {
            onDestroy { destroyed = true }
        }
        val owner = harness.lifecycleOwner

        harness.close()

        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        assertTrue(destroyed)
        assertTrue(coordinator.isDisposed)
    }

    @Test
    fun closeFromInitializedLifecycleDoesNotFail() {
        val harness = SynapseTestHarness { initialLifecycleState = Lifecycle.State.INITIALIZED }.start()
        val owner = harness.lifecycleOwner
        harness.close()
        assertEquals(Lifecycle.State.INITIALIZED, owner.lifecycle.currentState)
    }

    @Test
    fun closeRunsEveryStepAndRethrowsFirstFailure() {
        val harness = SynapseTestHarness().start()
        val board = harness.switchBoard
        harness.Coordinator { onDispose { error("first") } }
        harness.Coordinator { onDispose { error("second") } }

        val error = assertThrows<IllegalStateException> { harness.close() }

        assertEquals("first", error.message)
        assertEquals(listOf("second"), error.suppressed.map { it.message })
        assertTrue(board.isClosed, "board must be closed even when a coordinator fails to dispose")
        assertFalse(harness.isStarted)
    }

    // ── Restart ─────────────────────────────────────────────────────────

    @Test
    fun restartCreatesFreshBoardAndDetachesOldCaptures() {
        val harness = SynapseTestHarness()
        harness.start()
        val firstBoard = harness.switchBoard
        val firstOwner = harness.lifecycleOwner
        val oldCapture = harness.onAllImpulses<Ping>()
        harness.runTest { harness.Trigger(Ping("first")) }
        harness.close()

        harness.start()
        try {
            assertNotSame(firstBoard, harness.switchBoard)
            assertNotSame(firstOwner, harness.lifecycleOwner)
            assertEquals(Lifecycle.State.RESUMED, harness.lifecycleState)
            harness.runTest { harness.Trigger(Ping("second")) }
            // Old capture kept its value but no longer records.
            assertEquals(listOf("first"), oldCapture.values.map { it.value })
        } finally {
            harness.close()
        }
    }

    @Test
    fun capturesCreatedBeforeStartRecordOnceStarted() {
        val harness = SynapseTestHarness()
        val ping = harness.onImpulse<Ping>()
        harness.start().use {
            harness.runTest { harness.Trigger(Ping("early")) }
            assertEquals("early", ping.assertCaptured().value)
        }
    }

    // ── Configuration ───────────────────────────────────────────────────

    @Test
    fun constructorTimeoutsMapToSwitchBoardConfig() {
        SynapseTestHarness(stateTimeout = 1_234, impulseTimeout = 567).start().use { harness ->
            val config = harness.switchBoardConfig
            assertEquals(1_234.milliseconds, config.stopTimeout)
            assertEquals(567.milliseconds, config.replayExpiration)
            assertEquals(1_234.milliseconds, config.providerIdleTimeout)
            assertSame(harness.testDispatcher, config.workerContext)
        }
    }

    @Test
    fun switchBoardCustomizersApplyAfterDefaultsInOrder() {
        val harness = SynapseTestHarness(stateTimeout = 1_000) {
            switchBoard {
                stopTimeout = 5.seconds
                maxConcurrentJobs = 3
            }
            switchBoard { maxConcurrentJobs = 4 }
        }
        harness.start().use {
            assertEquals(5.seconds, harness.switchBoardConfig.stopTimeout)
            assertEquals(4, harness.switchBoardConfig.maxConcurrentJobs)
        }
    }

    @Test
    fun invalidSwitchBoardConfigLeavesHarnessStopped() {
        val harness = SynapseTestHarness { switchBoard { maxConcurrentJobs = -1 } }
        assertThrows<IllegalArgumentException> { harness.start() }
        assertFalse(harness.isStarted)
    }

    @Test
    fun destroyedInitialLifecycleStateIsRejected() {
        val harness = SynapseTestHarness { initialLifecycleState = Lifecycle.State.DESTROYED }
        assertThrows<IllegalArgumentException> { harness.start() }
        assertFalse(harness.isStarted)
    }

    @Test
    fun includeMergesExistingRegistry() {
        val production = ProviderRegistry.Builder("production")
            .register<String, FetchNothing> {
                object : Provider<FetchNothing, String>() {
                    override fun ProviderScope.produce(impulse: FetchNothing): Flow<String> =
                        flowOf("real-${impulse.id}")
                }
            }
            .build()
        val harness = SynapseTestHarness {
            include(production)
            provide<List<String>, FetchItems> { listOf(it.query) }
        }
        harness.start().use {
            harness.runTest {
                val result = harness.Request(FetchNothing(7)).first { it is DataState.Success }
                assertEquals(DataState.Success("real-7"), result)
                val items = harness.Request(FetchItems("stub")).first { it is DataState.Success }
                assertEquals(DataState.Success(listOf("stub")), items)
            }
            assertTrue(harness.switchBoard.toString().isNotEmpty())
        }
    }

    @Test
    fun duplicateProviderFailsStart() {
        val harness = SynapseTestHarness {
            provide<List<String>, FetchItems> { emptyList() }
            provide<List<String>, FetchItems> { emptyList() }
        }
        assertThrows<IllegalStateException> { harness.start() }
        assertFalse(harness.isStarted)
    }

    @Test
    fun emitterTagTracesHarnessEmissions() {
        val harness = SynapseTestHarness { emitterTag = "Test" }
        harness.start().use {
            val traces = CopyOnWriteArrayList<TraceContext?>()
            harness.switchBoard.addObserver { point, _, _, trace ->
                if (point == InterceptPoint(Channel.REACTION, Direction.UPSTREAM)) traces += trace
            }
            assertEquals("Test", harness.tag)
            harness.runTest { harness.Trigger(Ping()) }
            assertEquals(listOf("Test"), traces.map { it?.emitterTag })
        }
    }

    @Test
    fun untaggedHarnessEmitsWithoutTrace() {
        SynapseTestHarness().start().use { harness ->
            val traces = CopyOnWriteArrayList<Any>()
            harness.switchBoard.addObserver { _, _, _, trace -> traces += (trace ?: "none") }
            assertNull(harness.tag)
            harness.runTest { harness.Broadcast(Counter(1)) }
            assertEquals(listOf<Any>("none"), traces)
        }
    }
}
