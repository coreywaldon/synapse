package com.synapselib.arch.base.internal

import com.synapselib.arch.base.InterceptPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Hook through which a [ChannelStore] runs a value through the downstream
 * stage of its channel.
 *
 * Returning [DROP] skips the value (e.g. because a downstream interceptor
 * failed); any other value is delivered to the downstream flow.
 */
internal fun interface DownstreamStage {
    suspend fun process(point: InterceptPoint, clazz: KClass<*>, value: Any): Any

    companion object {
        /** Sentinel returned by [process] to drop a value instead of delivering it. */
        val DROP: Any = Any()
    }
}

/**
 * Per-type storage for one [com.synapselib.arch.base.Channel] of the
 * SwitchBoard (state or reaction).
 *
 * Each data type gets exactly one [Lane], created on first use and shared by
 * all producers and consumers of that type. A lane owns:
 *
 * - the **raw** [MutableSharedFlow] that upstream-intercepted values are
 *   emitted into, and
 * - a **lazily created** downstream [SharedFlow] that applies the
 *   [downstreamPoint] stage once per value and is shared by all collectors.
 *
 * @param downstreamPoint     the intercept point applied on the consuming side.
 * @param replay              replay of both the raw and downstream flows.
 * @param extraBufferCapacity extra buffer capacity of the raw flow.
 * @param onBufferOverflow    overflow strategy of the raw flow.
 * @param scope               scope in which downstream sharing coroutines run.
 * @param started             supplies the sharing strategy at the moment a
 *                            downstream flow is first created.
 * @param stage               the downstream processing hook.
 */
internal class ChannelStore(
    private val downstreamPoint: InterceptPoint,
    private val replay: Int,
    private val extraBufferCapacity: Int,
    private val onBufferOverflow: BufferOverflow,
    private val scope: CoroutineScope,
    private val started: () -> SharingStarted,
    private val stage: DownstreamStage,
) {

    /** One channel slot for a single data type. */
    inner class Lane(private val clazz: KClass<*>) {

        /** Upstream side: values are emitted here after upstream interception. */
        val raw: MutableSharedFlow<Any> = MutableSharedFlow(replay, extraBufferCapacity, onBufferOverflow)

        /** Read-only view of [raw], allocated once. */
        val rawView: SharedFlow<Any> = raw.asSharedFlow()

        @Volatile
        private var downstream: SharedFlow<Any>? = null

        /**
         * Returns the downstream-intercepted flow, creating (and launching its
         * sharing coroutine) on first access. Exactly one downstream flow is
         * ever created per lane.
         */
        fun downstream(): SharedFlow<Any> {
            downstream?.let { return it }
            return synchronized(this) {
                downstream ?: raw
                    .transform { value ->
                        val out = stage.process(downstreamPoint, clazz, value)
                        if (out !== DownstreamStage.DROP) emit(out)
                    }
                    .shareIn(scope, started(), replay)
                    .also { downstream = it }
            }
        }
    }

    private val lanes = ConcurrentHashMap<KClass<*>, Lane>()

    /** Returns the lane for [clazz], creating it on first use. */
    fun lane(clazz: KClass<*>): Lane =
        lanes[clazz] ?: lanes.computeIfAbsent(clazz) { Lane(it) }

    /** Number of lanes created so far (diagnostics / tests). */
    val size: Int get() = lanes.size

    /** Drops all lanes. Flows already handed out keep working on their own. */
    fun clear() {
        lanes.clear()
    }
}
