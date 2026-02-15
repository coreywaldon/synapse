package com.synapselib.arch.base.routing.producer

import com.synapselib.arch.base.routing.RequestParams
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

interface NeedProducer<Need : Any> {
    val needType: KClass<Need>
    fun <T : RequestParams> canHandle(params: T): Boolean
    fun <T : RequestParams> createFetcher(params: T): Flow<Need>
}

class NoApplicableProducerException(
    needType: KClass<*>,
    message: String = "No applicable producer found for type ${needType.simpleName}"
) : RuntimeException(message)

class AmbiguousProducersException(
    needType: KClass<*>,
    producerCount: Int,
    message: String = "Ambiguous producers: $producerCount producers can handle type ${needType.simpleName}. Unable to resolve."
) : RuntimeException(message)

class ProducerFailedToFetchException(
    needType: KClass<*>,
    cause: Throwable,
    message: String = "Producer failed to fetch for type ${needType.simpleName}: ${cause.message}"
) : RuntimeException(message, cause)