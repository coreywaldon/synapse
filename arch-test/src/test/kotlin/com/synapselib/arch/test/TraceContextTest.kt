package com.synapselib.arch.test

import com.synapselib.arch.base.Channel
import com.synapselib.arch.base.Direction
import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.TraceContext
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TraceContextTest {

    @get:Rule
    val synapse = SynapseTestRule()

    @Test
    fun coordinatorWithTagEmitsTraceOnTrigger() = synapse.runTest {
        var receivedTrace: TraceContext? = null
        synapse.switchBoard.setTraceListener { trace, _, _, _ ->
            receivedTrace = trace
        }

        synapse.Coordinator(tag = "AuthCoordinator") {
            launch { Trigger(Ping("traced")) }
        }

        assertNotNull(receivedTrace)
        assertEquals("AuthCoordinator", receivedTrace!!.emitterTag)
    }

    @Test
    fun coordinatorWithTagEmitsTraceOnBroadcast() = synapse.runTest {
        var receivedTrace: TraceContext? = null
        synapse.switchBoard.setTraceListener { trace, _, _, _ ->
            receivedTrace = trace
        }

        synapse.Coordinator(tag = "CartCoordinator") {
            launch { Broadcast(Counter(42)) }
        }

        assertNotNull(receivedTrace)
        assertEquals("CartCoordinator", receivedTrace!!.emitterTag)
    }

    @Test
    fun coordinatorWithoutTagDoesNotEmitTrace() = synapse.runTest {
        var receivedTrace: TraceContext? = null
        synapse.switchBoard.setTraceListener { trace, _, _, _ ->
            receivedTrace = trace
        }

        synapse.Coordinator {
            launch { Trigger(Ping("untraced")) }
        }

        assertNull(receivedTrace)
    }

    @Test
    fun traceListenerReceivesCorrectInterceptPoint() = synapse.runTest {
        var receivedPoint: InterceptPoint? = null
        synapse.switchBoard.setTraceListener { _, point, _, _ ->
            receivedPoint = point
        }

        synapse.Coordinator(tag = "Test") {
            launch { Trigger(Ping("check-point")) }
        }

        assertEquals(
            InterceptPoint(Channel.REACTION, Direction.UPSTREAM),
            receivedPoint,
        )
    }

    @Test
    fun traceDoesNotAppearInInterceptorData() = synapse.runTest {
        val ping = synapse.onImpulse<Ping>()

        synapse.Coordinator(tag = "TracedCoordinator") {
            launch { Trigger(Ping("raw-data")) }
        }

        val captured = ping.assertCaptured()
        assertEquals("raw-data", captured.value)
    }

    @Test
    fun globalLoggerStillFiresAlongsideTraceListener() = synapse.runTest {
        var loggerCalled = false
        var traceCalled = false

        synapse.switchBoard.setGlobalLogger { _, _, _ -> loggerCalled = true }
        synapse.switchBoard.setTraceListener { _, _, _, _ -> traceCalled = true }

        synapse.Coordinator(tag = "Both") {
            launch { Trigger(Ping("both")) }
        }

        assertEquals(true, loggerCalled)
        assertEquals(true, traceCalled)
    }

    @Test
    fun eachTraceHasUniqueId() = synapse.runTest {
        val traceIds = mutableListOf<String>()
        synapse.switchBoard.setTraceListener { trace, _, _, _ ->
            traceIds.add(trace.traceId)
        }

        synapse.Coordinator(tag = "Multi") {
            launch {
                Trigger(Ping("first"))
                Trigger(Pong("second"))
            }
        }

        assertEquals(2, traceIds.size)
        assertEquals(2, traceIds.distinct().size)
    }
}
