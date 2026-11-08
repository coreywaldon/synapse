package com.synapselib.arch.test

import com.synapselib.arch.base.InterceptPoint
import com.synapselib.arch.base.Interceptor
import com.synapselib.arch.base.addInterceptor
import com.synapselib.core.typed.DataState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Captures on the REQUEST channel: impulses upstream, results downstream. */
class CaptureRequestTest {

    @get:Rule
    val synapse = SynapseTestRule {
        provide<List<String>, FetchItems> { listOf("item-${it.query}") }
        provideFlow<List<String>, FetchMulti> {
            flow {
                emit(listOf("first"))
                emit(listOf("first", "second"))
            }
        }
    }

    @Test
    fun onRequestCapturesTheRequestImpulse() = synapse.runTest {
        val fetch = synapse.onRequest<FetchItems>()
        fetch.assertNotCaptured()

        synapse.Request(FetchItems("shoes")).first { it is DataState.Success }

        assertEquals(FetchItems("shoes"), fetch.assertCaptured())
    }

    @Test
    fun onRequestSeesUpstreamInterceptedImpulse() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.REQUEST_UPSTREAM,
            interceptor = Interceptor.transform<FetchItems> { it.copy(query = it.query.trim()) },
        )
        val fetch = synapse.onRequest<FetchItems>()

        val result = synapse.Request(FetchItems("  hats ")).first { it is DataState.Success }

        assertEquals(FetchItems("hats"), fetch.assertCaptured())
        assertEquals(DataState.Success(listOf("item-hats")), result)
    }

    @Test
    fun onAllRequestsRecordsEachCollection() = synapse.runTest {
        val fetches = synapse.onAllRequests<FetchItems>()

        synapse.Request(FetchItems("a")).first { it is DataState.Success }
        synapse.Request(FetchItems("b")).first { it is DataState.Success }

        assertEquals(listOf("a", "b"), fetches.values.map { it.query })
    }

    @Test
    fun onRequestOnlyMatchesItsImpulseType() = synapse.runTest {
        val items = synapse.onRequest<FetchItems>()
        val multi = synapse.onRequest<FetchMulti>()

        synapse.Request(FetchMulti("m")).first { it is DataState.Success }

        items.assertNotCaptured()
        assertEquals(FetchMulti("m"), multi.assertCaptured())
    }

    @Test
    fun onRequestResultCapturesDeliveredData() = synapse.runTest {
        val result = synapse.onRequestResult<List<String>>()

        synapse.Request(FetchItems("x")).first { it is DataState.Success }

        assertEquals(listOf("item-x"), result.assertCaptured())
    }

    @Test
    fun onAllRequestResultsRecordsEveryEmissionInOrder() = synapse.runTest {
        val results = synapse.onAllRequestResults<List<String>>()

        synapse.Request(FetchMulti("q"))
            .takeWhile { !(it is DataState.Success && it.data.size == 2) }
            .collect()

        assertEquals(listOf(listOf("first"), listOf("first", "second")), results.values)
    }

    @Test
    fun onRequestResultSeesDownstreamInterceptedData() = synapse.runTest {
        synapse.switchBoard.addInterceptor(
            point = InterceptPoint.REQUEST_DOWNSTREAM,
            interceptor = Interceptor.transform<List<String>> { list -> list.map { it.uppercase() } },
        )
        val result = synapse.onRequestResult<List<String>>()

        synapse.Request(FetchItems("x")).first { it is DataState.Success }

        assertEquals(listOf("ITEM-X"), result.assertCaptured())
    }

    @Test
    fun failedRequestRecordsImpulseButNoResult() = synapse.runTest {
        val fetch = synapse.onRequest<FetchNothing>()
        val result = synapse.onRequestResult<String>()

        synapse.Request(FetchNothing(9)).first { it is DataState.Error }

        assertEquals(9, fetch.assertCaptured().id)
        result.assertNotCaptured()
    }
}
