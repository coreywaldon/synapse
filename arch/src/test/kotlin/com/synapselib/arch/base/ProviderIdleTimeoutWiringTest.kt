@file:OptIn(ExperimentalCoroutinesApi::class)

package com.synapselib.arch.base

import com.synapselib.arch.base.provider.Provider
import com.synapselib.arch.base.provider.ProviderRegistry
import com.synapselib.arch.base.provider.ProviderScope
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [SwitchBoardConfig.providerIdleTimeout] is wired through
 * [DefaultSwitchBoard] into the provider runtime's idle teardown.
 */
class ProviderIdleTimeoutWiringTest {

    data class Item(val value: Int)
    data class WatchItems(val key: String) : DataImpulse<Item>()

    private class Probe {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
    }

    private fun registry(probe: Probe): ProviderRegistry = ProviderRegistry.Builder()
        .register<Item, WatchItems> {
            object : Provider<WatchItems, Item>() {
                override fun ProviderScope.produce(impulse: WatchItems): Flow<Item> = flow {
                    probe.started.incrementAndGet()
                    try {
                        emit(Item(1))
                        awaitCancellation()
                    } finally {
                        probe.cancelled.incrementAndGet()
                    }
                }
            }
        }
        .build()

    private fun TestScope.board(probe: Probe, configure: SwitchBoardConfig.Builder.() -> Unit): Pair<DefaultSwitchBoard, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val config = SwitchBoardConfig {
            workerContext = dispatcher
            configure()
        }
        return DefaultSwitchBoard(scope, registry(probe), config) to scope
    }

    /** Collects one request until it has seen a Success, then cancels the collector. */
    private fun TestScope.collectBriefly(board: DefaultSwitchBoard) {
        val collector = launch {
            board.handleRequest(WatchItems::class, Item::class, WatchItems("a")).collect { }
        }
        runCurrent()
        collector.cancel()
        runCurrent()
    }

    // ── Config ──────────────────────────────────────────────────────────

    @Test
    fun `providerIdleTimeout defaults to stopTimeout`() {
        assertEquals(3.seconds, SwitchBoardConfig().providerIdleTimeout)
        assertEquals(7.seconds, SwitchBoardConfig { stopTimeout = 7.seconds }.providerIdleTimeout)
    }

    @Test
    fun `providerIdleTimeout can be overridden and round-trips through copy`() {
        val config = SwitchBoardConfig {
            stopTimeout = 7.seconds
            providerIdleTimeout = 1.seconds
        }
        assertEquals(1.seconds, config.providerIdleTimeout)
        assertEquals(config, config.copy { })
        // An explicit override survives a stopTimeout change; the derived default does not.
        assertEquals(1.seconds, config.copy { stopTimeout = 9.seconds }.providerIdleTimeout)
        assertEquals(9.seconds, SwitchBoardConfig().copy { stopTimeout = 9.seconds }.providerIdleTimeout)
    }

    @Test
    fun `negative providerIdleTimeout is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SwitchBoardConfig { providerIdleTimeout = (-1).milliseconds }
        }
    }

    // ── Wiring ──────────────────────────────────────────────────────────

    @Test
    fun `streaming provider is torn down after the configured idle timeout`() = runTest {
        val probe = Probe()
        val (board, scope) = board(probe) { providerIdleTimeout = 500.milliseconds }

        collectBriefly(board)
        assertEquals(1, probe.started.get())
        assertEquals(0, probe.cancelled.get())

        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, probe.cancelled.get(), "still inside the idle window")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, probe.cancelled.get(), "idle timeout elapsed — provider cancelled")

        // A new request starts a fresh provider instead of reusing the torn-down one.
        collectBriefly(board)
        assertEquals(2, probe.started.get())

        board.close()
        scope.cancel()
    }

    @Test
    fun `infinite providerIdleTimeout keeps streaming providers alive`() = runTest {
        val probe = Probe()
        val (board, scope) = board(probe) { providerIdleTimeout = Duration.INFINITE }

        collectBriefly(board)
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(0, probe.cancelled.get())

        // Resubscribing dedups onto the still-running provider.
        val states = mutableListOf<DataState<Item>>()
        val collector = launch {
            board.handleRequest(WatchItems::class, Item::class, WatchItems("a")).collect { states += it }
        }
        runCurrent()
        assertEquals(1, probe.started.get())
        assertEquals(DataState.Success(Item(1)), states.last())

        collector.cancel()
        board.close()
        runCurrent()
        assertEquals(1, probe.cancelled.get(), "close() tears the provider down")
        scope.cancel()
    }
}
