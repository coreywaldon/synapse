package com.synapselib.arch.base

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A [LifecycleOwner] that can be driven to any state, one event at a time. */
internal class SteppedLifecycleOwner(target: Lifecycle.State = Lifecycle.State.INITIALIZED) : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    init {
        moveTo(target)
    }

    fun moveTo(target: Lifecycle.State) {
        while (registry.currentState != target) {
            val current = registry.currentState
            val event = if (current < target) {
                Lifecycle.Event.upFrom(current)
            } else {
                Lifecycle.Event.downFrom(current)
            } ?: error("cannot move from $current to $target")
            registry.handleLifecycleEvent(event)
        }
    }
}

/**
 * Regression tests for lifecycle hooks registered on an owner that is already
 * past the hook's event — the builder used to attach its observer *before*
 * running the block, so `onCreate {}` / `onStart {}` / `onResume {}` hooks
 * declared in the block silently never fired for an already-resumed owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatorLifecycleTest {

    private val testDispatcher = UnconfinedTestDispatcher()
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

    // ── Builder on an already-advanced owner ───────────────────────────

    @Test
    fun `builder hooks fire when owner is already resumed`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val fired = mutableListOf<String>()

        val coord = Coordinator(switchBoard, owner) {
            onCreate { fired += "create" }
            onStart { fired += "start" }
            onResume { fired += "resume" }
        }

        assertEquals(listOf("create", "start", "resume"), fired)
        coord.dispose()
    }

    @Test
    fun `builder replays hooks in lifecycle order regardless of registration order`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val fired = mutableListOf<String>()

        val coord = Coordinator(switchBoard, owner) {
            onResume { fired += "resume" }
            onStart { fired += "start" }
            onCreate { fired += "create" }
        }

        assertEquals(listOf("create", "start", "resume"), fired)
        coord.dispose()
    }

    @Test
    fun `builder hooks do not fire twice for the replayed events`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        var starts = 0

        val coord = Coordinator(switchBoard, owner) {
            onStart { starts++ }
        }
        assertEquals(1, starts)

        // A later stop/start cycle fires it again — exactly once more.
        owner.moveTo(Lifecycle.State.CREATED)
        owner.moveTo(Lifecycle.State.RESUMED)
        assertEquals(2, starts)
        coord.dispose()
    }

    @Test
    fun `builder on started owner replays create and start but not resume`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.STARTED)
        val fired = mutableListOf<String>()

        val coord = Coordinator(switchBoard, owner) {
            onCreate { fired += "create" }
            onStart { fired += "start" }
            onResume { fired += "resume" }
        }
        assertEquals(listOf("create", "start"), fired)

        owner.moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("create", "start", "resume"), fired)
        coord.dispose()
    }

    @Test
    fun `builder does not replay down events`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val fired = mutableListOf<String>()

        val coord = Coordinator(switchBoard, owner) {
            onPause { fired += "pause" }
            onStop { fired += "stop" }
        }
        assertEquals(emptyList<String>(), fired)

        owner.moveTo(Lifecycle.State.CREATED)
        assertEquals(listOf("pause", "stop"), fired)
        coord.dispose()
    }

    // ── Hooks registered after construction ────────────────────────────

    @Test
    fun `hook registered later on resumed owner fires immediately`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val coord = CoordinatorScope(switchBoard, owner)
        var started = 0
        var resumed = 0

        coord.onStart { started++ }
        coord.onResume { resumed++ }

        assertEquals(1, started)
        assertEquals(1, resumed)
        coord.dispose()
    }

    @Test
    fun `hook registered later after stop waits for the next start`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val coord = CoordinatorScope(switchBoard, owner)
        owner.moveTo(Lifecycle.State.CREATED)

        var started = 0
        coord.onStart { started++ }
        assertEquals(0, started)

        owner.moveTo(Lifecycle.State.STARTED)
        assertEquals(1, started)
        coord.dispose()
    }

    @Test
    fun `hook registered from inside a hook during dispatch fires exactly once`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.CREATED)
        val coord = CoordinatorScope(switchBoard, owner)
        var inner = 0

        coord.onStart {
            coord.onStart { inner++ }
        }
        owner.moveTo(Lifecycle.State.STARTED)

        // Registered while ON_START was being dispatched: replayed once, not
        // also picked up by the in-flight dispatch.
        assertEquals(1, inner)
        coord.dispose()
    }

    @Test
    fun `direct construction on resumed owner still fires future events`() {
        val owner = SteppedLifecycleOwner(Lifecycle.State.RESUMED)
        val coord = CoordinatorScope(switchBoard, owner)
        val fired = mutableListOf<String>()
        coord.onPause { fired += "pause" }
        coord.onDestroy { fired += "destroy" }

        owner.moveTo(Lifecycle.State.DESTROYED)
        assertEquals(listOf("pause", "destroy"), fired)
    }
}
