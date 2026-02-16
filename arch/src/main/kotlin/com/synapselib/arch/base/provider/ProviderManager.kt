package com.synapselib.arch.base.provider

import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.core.typed.DataState
import com.synapselib.core.typed.TypedDataSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Internal engine that manages [Provider] lifecycle, job deduplication,
 * and concurrent request execution.
 *
 * This is **not** a public API — the [SwitchBoard] delegates to it internally.
 * External code interacts with the provider system exclusively through
 * [SwitchBoard.handleRequest].
 *
 * ## Separation of Concerns
 *
 * The [ProviderRegistry] owns the impulse → factory mappings (configuration).
 * This class owns the runtime lifecycle: job creation, deduplication,
 * concurrency limits, and disposal. The registry is immutable after
 * construction; this class is the only mutable, stateful component.
 *
 * ## Type Safety
 *
 * All internal storage erases generics (required by [ConcurrentHashMap]),
 * but every retrieval path goes through [Class.cast] or
 * [Class.isAssignableFrom] via [ProviderRegistry.RegisteredFactory]
 * and [ActiveJob]. The public [request] method contains zero unchecked
 * casts — all type transitions are runtime-verified.
 *
 * ## Job Deduplication
 *
 * Active jobs are keyed by the [DataImpulse] instance using structural
 * equality (`data class` equals/hashCode). When a request arrives:
 *
 * 1. If an identical impulse already has an active job, the caller receives
 *    the **same** [SharedFlow]`<DataState<T>>` — no duplicate work.
 * 2. If no matching job exists, a new [Provider] is instantiated via its
 *    registered [ProviderFactory], and a new job is launched.
 * 3. When a job completes (success or error) and no caching policy applies,
 *    it is removed from the active job table.
 *
 * ## Concurrency
 *
 * Each unique impulse runs in its own child coroutine under a [SupervisorJob],
 * so a failure in one provider does not cancel others. The [maxConcurrentJobs]
 * parameter limits total active jobs across all impulse types.
 *
 * @param registry          the immutable [ProviderRegistry] mapping impulse
 *                          types to their factories.
 * @param parentScope       the parent [CoroutineScope] (typically the
 *                          SwitchBoard's scope). Provider jobs are children
 *                          of this scope.
 * @param maxConcurrentJobs maximum number of simultaneous provider jobs.
 *                          0 means unbounded.
 */
class ProviderManager(
    private val registry: ProviderRegistry,
    parentScope: CoroutineScope,
    workerContext: CoroutineContext = Dispatchers.IO,
    private val maxConcurrentJobs: Int = 0,
) {

    private val scope = parentScope + SupervisorJob(parentScope.coroutineContext[Job]) + workerContext

    // ── Active Job Table ────────────────────────────────────────────────

    /**
     * Tracks currently active (in-flight or cached) provider jobs.
     *
     * Keyed by the [DataImpulse] instance itself (structural equality),
     * so `FetchUser(42)` and `FetchUser(42)` share an entry while
     * `FetchUser(42)` and `FetchUser(99)` are distinct.
     */
    private val activeJobs = ConcurrentHashMap<Any, ActiveJob<*>>()

    /**
     * Bundles the shared [DataState] flow with its coroutine [Job] and
     * the [Class] token needed for checked casts on retrieval.
     *
     * @param Need           the result type.
     * @property needClass    the [Class] token for [Need], used by
     *                        [typedFlow] to verify type compatibility.
     * @property sharedFlow   the mutable flow driving [DataState] transitions.
     *                        Uses [MutableSharedFlow] with replay=1 and buffer
     *                        so that every emission is delivered to active
     *                        collectors while new subscribers get the latest.
     * @property job          the coroutine job backing this provider execution.
     * @property lastSuccess  tracks the most recent successful value for
     *                        stale-data support in [DataState.Error].
     */
    private class ActiveJob<Need : Any>(
        val needClass: Class<Need>,
        val sharedFlow: SharedFlow<DataState<Need>>,
        val job: Job,
        val lastSuccess: MutableStateFlow<Need?> = MutableStateFlow(null),
    ) {
        /**
         * Returns a [SharedFlow]`<DataState<T>>` backed by this job's
         * internal flow, with every inner data value checked-cast through
         * [Class.cast] via [TypedDataSharedFlow].
         *
         * Uses [Class.isAssignableFrom] to verify type compatibility
         * up front, then delegates element-wise casting to
         * [TypedDataSharedFlow] — no unchecked casts anywhere in the chain.
         *
         * @param T             the caller's expected result type.
         * @param expectedClass the [KClass] token for [T].
         * @return the typed [SharedFlow].
         * @throws IllegalArgumentException if [expectedClass] is not
         *         compatible with [needClass].
         */
        fun <T : Any> typedFlow(expectedClass: KClass<T>): SharedFlow<DataState<T>> {
            require(expectedClass.java.isAssignableFrom(needClass)) {
                "Type mismatch: active job produces ${needClass.simpleName} " +
                        "but caller expects ${expectedClass.java.simpleName}"
            }
            return TypedDataSharedFlow(erasedFlow(), expectedClass)
        }

        /**
         * Returns the shared flow widened to `SharedFlow<DataState<*>>` for
         * use by [TypedDataSharedFlow]. This is a safe covariant widening —
         * `DataState<out T>` and `SharedFlow<out T>` are both covariant.
         */
        private fun erasedFlow(): SharedFlow<DataState<*>> = sharedFlow
    }

    // ── Request Dispatch ────────────────────────────────────────────────

    /**
     * Returns a [SharedFlow]`<DataState<Need>>` for the given [impulse].
     *
     * If an identical impulse is already being processed, the existing
     * flow is returned (deduplication). Otherwise, a new [Provider] is
     * created and a new job is launched.
     *
     * ## Cast Safety
     *
     * This method contains **no unchecked casts**. All type transitions
     * are handled by:
     * - [ProviderRegistry.RegisteredFactory]: uses [Class.cast] for
     *   impulse input.
     * - [ActiveJob.typedFlow]: uses [TypedDataSharedFlow] for
     *   checked element-wise output casting.
     *
     * @param Need        the expected result type.
     * @param I           the concrete [DataImpulse] subclass.
     * @param impulseType [KClass] token for [I] (used for factory lookup).
     * @param needType    [KClass] token for [Need] (used for checked casts
     *                    on the dedup path).
     * @param impulse     the data impulse instance.
     * @param switchboard the [SwitchBoard] to pass to the [ProviderScope].
     * @return a [SharedFlow] that emits [DataState] transitions for this request.
     * @throws NoProviderException if no factory is registered for [impulseType].
     */
    fun <Need : Any, I : DataImpulse<Need>> request(
        impulseType: KClass<I>,
        needType: KClass<Need>,
        impulse: I,
        switchboard: SwitchBoard,
    ): SharedFlow<DataState<Need>> {

        // ── Dedup check ─────────────────────────────────────────────
        val existing = activeJobs[impulse]
        if (existing != null && existing.job.isActive) {
            return existing.typedFlow(needType)
        }

        // ── Concurrency gate ────────────────────────────────────────
        if (maxConcurrentJobs > 0 && activeJobs.size >= maxConcurrentJobs) {
            val overflow = MutableSharedFlow<DataState<Need>>(
                replay = 1,
                extraBufferCapacity = 0,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            overflow.tryEmit(
                DataState.Error(
                    ProviderConcurrencyException(impulseType, maxConcurrentJobs)
                )
            )
            return overflow
        }

        // ── Resolve factory ─────────────────────────────────────────
        val registered = registry.resolve(impulseType)
            ?: throw NoProviderException(impulseType)

        // Verify type compatibility before launching.
        require(needType.java.isAssignableFrom(registered.needClass)) {
            "Type mismatch: factory for ${impulseType.simpleName} produces " +
                    "${registered.needClass.simpleName} but caller expects " +
                    "${needType.simpleName}"
        }

        // ── Launch provider ─────────────────────────────────────────
        val activeJob = launchProvider(registered, impulse, needType, switchboard)
        activeJobs[impulse] = activeJob

        return activeJob.typedFlow(needType)
    }

    /**
     * Creates an [ActiveJob] by instantiating a [Provider] from the
     * [RegisteredFactory][ProviderRegistry.RegisteredFactory], launching
     * its flow, and wrapping the lifecycle in [DataState] transitions.
     *
     * All type-erased operations happen inside [ProviderRegistry.RegisteredFactory] methods
     * (castImpulse, createProvider) which use [Class.cast] internally.
     */
    private fun <Need : Any> launchProvider(
        registered: ProviderRegistry.RegisteredFactory<*, *>,
        impulse: Any,
        needType: KClass<Need>,
        switchboard: SwitchBoard,
    ): ActiveJob<Need> {
        val needJavaClass = needType.java
        val lastSuccess = MutableStateFlow<Need?>(null)

        val sharedFlow = MutableSharedFlow<DataState<Need>>(
            replay = 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val job = scope.launch {
            sharedFlow.subscriptionCount.first { it > 0 }

            val providerScope = ProviderScope(switchboard, this)

            sharedFlow.emit(DataState.Loading)

            try {
                registered.produceErased(impulse, providerScope)
                    .collect { value ->
                        val typed = needJavaClass.cast(value)
                        lastSuccess.value = typed
                        sharedFlow.emit(DataState.Success(typed))
                    }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                sharedFlow.emit(DataState.Error(e, lastSuccess.value))
            } finally {
                activeJobs.remove(impulse)
            }
        }

        return ActiveJob(needJavaClass, sharedFlow, job, lastSuccess)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Cancels all active provider jobs and clears the job table.
     *
     * Called when the owning [SwitchBoard] is torn down.
     */
    fun dispose() {
        activeJobs.values.forEach { it.job.cancel() }
        activeJobs.clear()
    }

    /**
     * Returns the number of currently active (in-flight) provider jobs.
     * Useful for diagnostics and testing.
     */
    val activeJobCount: Int get() = activeJobs.count { it.value.job.isActive }
}

// ── Exceptions ──────────────────────────────────────────────────────────

/**
 * Thrown when a [DataImpulse] is dispatched but no [ProviderFactory] is
 * registered for its type.
 *
 * In a correctly configured project with KSP validation enabled, this
 * exception should never occur at runtime — the compiler would have
 * flagged the missing provider.
 */
class NoProviderException(
    impulseType: KClass<*>,
    message: String = "No provider registered for impulse type: ${impulseType.simpleName}. " +
            "Annotate a Provider implementation with @Provider for this impulse type.",
) : RuntimeException(message)

/**
 * Returned (as [DataState.Error]) when the [ProviderManager]'s concurrency
 * limit has been reached and a new request cannot be accepted.
 */
class ProviderConcurrencyException(
    impulseType: KClass<*>,
    limit: Int,
    message: String = "Provider concurrency limit ($limit) reached. " +
            "Cannot process impulse: ${impulseType.simpleName}",
) : RuntimeException(message)