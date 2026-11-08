package com.synapselib.arch.test

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The rule's lifecycle owner with the "hooks replay for reached states" Coordinator semantics. */
class LifecycleDrivingTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun ownerStartsResumed() {
        assertEquals(Lifecycle.State.RESUMED, synapse.lifecycleState)
        assertEquals(Lifecycle.State.RESUMED, synapse.lifecycleOwner.lifecycle.currentState)
    }

    @Test
    fun upHooksFireInOrderOnResumedOwner() {
        val events = mutableListOf<String>()

        synapse.Coordinator {
            onResume { events += "resume" }
            onCreate { events += "create" }
            onStart { events += "start" }
            onPause { events += "pause" }
        }

        assertEquals(listOf("create", "start", "resume"), events)
    }

    @Test
    fun hooksCanLaunchSwitchBoardWork() = synapse.runTest {
        val counter = synapse.onState<Counter>()

        synapse.Coordinator {
            onStart { launch { Broadcast(Counter(1)) } }
        }

        assertEquals(Counter(1), counter.assertCaptured())
    }

    @Test
    fun moveToDispatchesDownAndUpEvents() {
        val events = mutableListOf<String>()
        synapse.Coordinator {
            onStart { events += "start" }
            onResume { events += "resume" }
            onPause { events += "pause" }
            onStop { events += "stop" }
        }
        events.clear()

        synapse.moveTo(Lifecycle.State.CREATED)
        assertEquals(listOf("pause", "stop"), events)
        assertEquals(Lifecycle.State.CREATED, synapse.lifecycleState)

        synapse.moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("pause", "stop", "start", "resume"), events)
    }

    @Test
    fun moveToDestroyedDisposesCoordinator() {
        var destroyed = false
        var disposed = false
        val coordinator = synapse.Coordinator {
            onDestroy { destroyed = true }
            onDispose { disposed = true }
        }

        synapse.moveTo(Lifecycle.State.DESTROYED)

        assertTrue(destroyed)
        assertTrue(disposed)
        assertTrue(coordinator.isDisposed)
    }

    @Test
    fun leavingDestroyedIsRejected() {
        synapse.moveTo(Lifecycle.State.DESTROYED)
        assertThrows(IllegalStateException::class.java) { synapse.moveTo(Lifecycle.State.RESUMED) }
    }

    @Test
    fun customOwnerCoordinatorIsTrackedAndDisposable() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.STARTED
        val events = mutableListOf<String>()

        val coordinator = synapse.Coordinator(owner, tag = "Custom") {
            onStart { events += "start" }
            onResume { events += "resume" }
        }

        assertEquals(listOf("start"), events)
        assertTrue(coordinator.lifecycleOwner === owner)
        assertFalse(coordinator.isDisposed)
    }

    @Test
    fun lifecycleFreeCoordinatorUsesHarnessScope() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        val coordinator = synapse.Coordinator(synapse.coroutineScope, tag = "Background") {
            ReactTo<Pong> { launch { Trigger(Ping(it.value)) } }
        }

        synapse.Trigger(Pong("bg"))

        assertEquals("bg", ping.assertCaptured().value)
        assertEquals(null, coordinator.lifecycleOwner)
        assertThrows(IllegalStateException::class.java) { coordinator.onStart { } }
    }
}

/** Starting from a non-resumed owner and driving it forward. */
class InitialLifecycleStateTest {

    @get:Rule
    val synapse = SynapseTestRule { initialLifecycleState = Lifecycle.State.CREATED }

    @Test
    fun hooksFireWhenTheTestDrivesTheOwnerForward() {
        val events = mutableListOf<String>()
        synapse.Coordinator {
            onCreate { events += "create" }
            onStart { events += "start" }
            onResume { events += "resume" }
        }
        assertEquals(listOf("create"), events)

        synapse.moveTo(Lifecycle.State.STARTED)
        assertEquals(listOf("create", "start"), events)

        synapse.moveTo(Lifecycle.State.RESUMED)
        assertEquals(listOf("create", "start", "resume"), events)
    }
}
