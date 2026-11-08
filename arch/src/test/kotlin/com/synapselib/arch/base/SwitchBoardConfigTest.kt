@file:OptIn(ExperimentalCoroutinesApi::class)

package com.synapselib.arch.base

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SwitchBoardConfigTest {

    data class Counter(val n: Int)
    data class Ping(val n: Int)

    // ── Defaults & builder ──────────────────────────────────────────────

    @Test
    fun `defaults match the historical hardcoded values`() {
        val config = SwitchBoardConfig()
        assertEquals(3.seconds, config.stopTimeout)
        assertEquals(3.seconds, config.replayExpiration)
        assertEquals(SharingStarted.WhileSubscribed(3_000, 3_000), config.sharingStarted)
        assertEquals(64, config.reactionBufferCapacity)
        assertEquals(BufferOverflow.DROP_OLDEST, config.reactionOverflow)
        assertEquals(0, config.maxConcurrentJobs)
        assertEquals(Dispatchers.IO, config.workerContext)
        assertEquals(SwitchBoardConfig.DEFAULT, config)
    }

    @Test
    fun `builder values are applied`() {
        val dispatcher = UnconfinedTestDispatcher()
        val config = SwitchBoardConfig {
            stopTimeout = 1.seconds
            replayExpiration = 2.seconds
            reactionBufferCapacity = 8
            reactionOverflow = BufferOverflow.SUSPEND
            maxConcurrentJobs = 4
            workerContext = dispatcher
        }
        assertEquals(1.seconds, config.stopTimeout)
        assertEquals(2.seconds, config.replayExpiration)
        assertEquals(SharingStarted.WhileSubscribed(1.seconds, 2.seconds), config.sharingStarted)
        assertEquals(8, config.reactionBufferCapacity)
        assertEquals(BufferOverflow.SUSPEND, config.reactionOverflow)
        assertEquals(4, config.maxConcurrentJobs)
        assertSame(dispatcher, config.workerContext)
    }

    @Test
    fun `eagerSharing overrides the derived strategy`() {
        val config = SwitchBoardConfig { eagerSharing() }
        assertSame(SharingStarted.Eagerly, config.sharingStarted)
    }

    @Test
    fun `custom sharingStarted wins over timeouts`() {
        val config = SwitchBoardConfig {
            stopTimeout = 10.seconds
            sharingStarted = SharingStarted.Lazily
        }
        assertSame(SharingStarted.Lazily, config.sharingStarted)
    }

    @Test
    fun `copy derives a new config and leaves the original untouched`() {
        val base = SwitchBoardConfig { reactionBufferCapacity = 16 }
        val derived = base.copy { eagerSharing() }

        assertEquals(16, derived.reactionBufferCapacity)
        assertSame(SharingStarted.Eagerly, derived.sharingStarted)
        assertNotEquals(base, derived)
        assertEquals(SharingStarted.WhileSubscribed(3_000, 3_000), base.sharingStarted)
        assertEquals(base, base.copy { })
        assertEquals(base.hashCode(), base.copy { }.hashCode())
    }

    @Test
    fun `newBuilder round-trips a derived sharing strategy without pinning it`() {
        // The default strategy is derived from the timeouts; changing a timeout
        // on a copy must re-derive it rather than keep the old WhileSubscribed.
        val derived = SwitchBoardConfig().copy { stopTimeout = 5.seconds }
        assertEquals(SharingStarted.WhileSubscribed(5.seconds, 3.seconds), derived.sharingStarted)
    }

    // ── Validation ──────────────────────────────────────────────────────

    @Test
    fun `negative timeouts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SwitchBoardConfig { stopTimeout = (-1).milliseconds } }
        assertThrows(IllegalArgumentException::class.java) { SwitchBoardConfig { replayExpiration = (-1).seconds } }
    }

    @Test
    fun `negative reaction buffer capacity is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SwitchBoardConfig { reactionBufferCapacity = -1 } }
    }

    @Test
    fun `zero reaction buffer requires SUSPEND overflow`() {
        assertThrows(IllegalArgumentException::class.java) { SwitchBoardConfig { reactionBufferCapacity = 0 } }
        val ok = SwitchBoardConfig {
            reactionBufferCapacity = 0
            reactionOverflow = BufferOverflow.SUSPEND
        }
        assertEquals(0, ok.reactionBufferCapacity)
    }

    @Test
    fun `negative maxConcurrentJobs is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SwitchBoardConfig { maxConcurrentJobs = -1 } }
    }

    // ── Constructors ────────────────────────────────────────────────────

    @Test
    fun `primary constructor defaults to the default config`() {
        val board = DefaultSwitchBoard(CoroutineScope(SupervisorJob()), testProviderRegistry())
        assertEquals(SwitchBoardConfig.DEFAULT, board.config)
        board.close()
    }

    @Test
    fun `legacy positional constructor maps loose params onto the config`() {
        val dispatcher = UnconfinedTestDispatcher()
        val board = DefaultSwitchBoard(CoroutineScope(SupervisorJob()), testProviderRegistry(), dispatcher, 1_000L, 2_000L)

        assertSame(dispatcher, board.config.workerContext)
        assertEquals(1.seconds, board.config.stopTimeout)
        assertEquals(2.seconds, board.config.replayExpiration)
        assertEquals(SharingStarted.WhileSubscribed(1_000, 2_000), board.config.sharingStarted)
        assertEquals(SwitchBoardConfig.DEFAULT_REACTION_BUFFER_CAPACITY, board.config.reactionBufferCapacity)
        board.close()
    }

    @Test
    fun `legacy named constructor still works`() {
        val dispatcher = UnconfinedTestDispatcher()
        val board = DefaultSwitchBoard(
            scope = CoroutineScope(SupervisorJob()),
            providerRegistry = testProviderRegistry(),
            workerContext = dispatcher,
        )
        assertSame(dispatcher, board.config.workerContext)
        assertEquals(3.seconds, board.config.stopTimeout)
        board.close()
    }

    @Test
    fun `legacy constructor rejects negative timeouts like WhileSubscribed did`() {
        assertThrows(IllegalArgumentException::class.java) {
            DefaultSwitchBoard(CoroutineScope(SupervisorJob()), testProviderRegistry(), Dispatchers.IO, -1L, 0L)
        }
    }

    // ── Behavior ────────────────────────────────────────────────────────

    @Test
    fun `eager config starts downstream sharing without subscribers`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
            testProviderRegistry(),
            SwitchBoardConfig { eagerSharing() },
        )
        val downstreamCalls = AtomicInteger(0)
        board.addInterceptor(InterceptPoint.STATE_DOWNSTREAM, Interceptor.read<Counter> { downstreamCalls.incrementAndGet() })

        val flow = board.stateFlow(Counter::class) // never collected
        board.broadcastState(Counter::class, Counter(1))
        advanceUntilIdle()

        assertEquals(1, downstreamCalls.get())
        assertEquals(listOf(Counter(1)), flow.replayCache)
        board.close()
    }

    @Test
    fun `default config does not run downstream stage without subscribers`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
            testProviderRegistry(),
        )
        val downstreamCalls = AtomicInteger(0)
        board.addInterceptor(InterceptPoint.STATE_DOWNSTREAM, Interceptor.read<Counter> { downstreamCalls.incrementAndGet() })

        val flow = board.stateFlow(Counter::class)
        board.broadcastState(Counter::class, Counter(1))
        advanceUntilIdle()

        assertEquals(0, downstreamCalls.get())
        assertEquals(emptyList<Counter>(), flow.replayCache)
        board.close()
    }

    /** Emits 0..4 into a reaction channel whose only collector blocks on the first value. */
    private suspend fun kotlinx.coroutines.test.TestScope.receivedWithBlockedCollector(
        config: SwitchBoardConfig,
    ): List<Int> {
        val board = DefaultSwitchBoard(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
            testProviderRegistry(),
            config,
        )
        val gate = CompletableDeferred<Unit>()
        val received = CopyOnWriteArrayList<Int>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            board.getRawImpulseFlow(Ping::class).collect {
                received += (it as Ping).n
                gate.await()
            }
        }
        board.triggerImpulse(Ping::class, Ping(0))
        advanceUntilIdle() // collector now holds 0 and is blocked
        for (i in 1..4) board.triggerImpulse(Ping::class, Ping(i))
        gate.complete(Unit)
        advanceUntilIdle()
        job.cancel()
        board.close()
        return received
    }

    @Test
    fun `reaction buffer capacity and overflow policy are honored`() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(listOf(0, 1, 2, 3, 4), receivedWithBlockedCollector(SwitchBoardConfig()))
        assertEquals(listOf(0, 3, 4), receivedWithBlockedCollector(SwitchBoardConfig { reactionBufferCapacity = 2 }))
        assertEquals(
            listOf(0, 1, 2),
            receivedWithBlockedCollector(SwitchBoardConfig {
                reactionBufferCapacity = 2
                reactionOverflow = BufferOverflow.DROP_LATEST
            }),
        )
    }

    // ── Deprecated setEagerSharing ──────────────────────────────────────

    @Suppress("DEPRECATION")
    @Test
    fun `setEagerSharing before first flow still makes sharing eager`() = runTest(UnconfinedTestDispatcher()) {
        val board = DefaultSwitchBoard(
            CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob()),
            testProviderRegistry(),
        )
        board.setEagerSharing()

        val flow = board.stateFlow(Counter::class)
        board.broadcastState(Counter::class, Counter(7))
        advanceUntilIdle()

        assertEquals(listOf(Counter(7)), flow.replayCache)
        board.close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `setEagerSharing after first flow fails loudly`() {
        val board = DefaultSwitchBoard(CoroutineScope(SupervisorJob()), testProviderRegistry())
        board.stateFlow(Counter::class)
        assertThrows(IllegalStateException::class.java) { board.setEagerSharing() }
        board.close()
    }

    @Suppress("DEPRECATION")
    @Test
    fun `setEagerSharing is not affected by broadcasts or raw flows`() {
        // Only downstream (shared) flows capture the strategy.
        val board = DefaultSwitchBoard(CoroutineScope(SupervisorJob()), testProviderRegistry())
        board.getRawStateFlow(Counter::class)
        board.getRawImpulseFlow(Ping::class)
        board.setEagerSharing()
        board.close()
    }
}
