package com.synapselib.arch.base.routing

import com.synapselib.arch.base.routing.producer.AmbiguousProducersException
import com.synapselib.arch.base.routing.producer.NeedProducer
import com.synapselib.arch.base.routing.producer.NoApplicableProducerException
import com.synapselib.arch.base.routing.producer.ProducerFailedToFetchException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject
import kotlin.reflect.KClass

open class RequestParams

interface Router {
    fun <Need : Any, T : RequestParams> route(
        needType: KClass<Need>,
        params: T
    ) : Flow<Need>
}

class DefaultRouter @Inject constructor(
    private val producers: Set<@JvmSuppressWildcards NeedProducer<*>>
) : Router {

    private val producerMap: Map<KClass<*>, Set<NeedProducer<*>>> =
        producers.groupBy { it.needType }
            .mapValues { (_, v) -> v.toSet() }

    override fun <Need : Any, T : RequestParams> route(
        needType: KClass<Need>,
        params: T
    ): Flow<Need> {
        val candidates = producerMap[needType]
            ?: throw NoApplicableProducerException(needType)

        val typedCandidates = candidates as Set<NeedProducer<Need>>

        val applicable = if (typedCandidates.size == 1) {
            val single = typedCandidates.single()
            if (!single.canHandle(params)) {
                throw NoApplicableProducerException(
                    needType,
                    "Single registered producer for ${needType.simpleName} returned canHandle=false"
                )
            }
            single
        } else {
            // Multiple producers — dedupe via canHandle
            val matching = typedCandidates.filter { it.canHandle(params) }
            when {
                matching.isEmpty() -> throw NoApplicableProducerException(needType)
                matching.size > 1 -> throw AmbiguousProducersException(needType, matching.size)
                else -> matching.single()
            }
        }

        return applicable.createFetcher(params)
            .catch { cause ->
                throw ProducerFailedToFetchException(needType, cause)
            }
    }
}