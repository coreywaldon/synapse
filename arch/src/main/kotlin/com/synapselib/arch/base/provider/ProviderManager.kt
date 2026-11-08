package com.synapselib.arch.base.provider

import com.synapselib.arch.base.DataImpulse
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.core.typed.DataState
import com.synapselib.core.typed.TypedDataSharedFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Internal engine that manages [Provider] lifecycle, job deduplication,
 * idle teardown and concurrent request execution.
 *
 * This is **not** a public API — the [SwitchBoard] delegates to it internally.
 * External code interacts with the provider system exclusively through
 * [SwitchBoard.handleRequest].
 *
 * ## Separation of Concerns
 *
 * The [ProviderRegistry] owns the impulse → factory mappings (configuration).
 * This class owns the runtime lifecycle: job creation, deduplication,
 * reference counting, idle teardown, concurrency limits, and disposal. The
 * registry is immutable after construction; this class is the only mutable,
 * stateful component.
 *
 * ## Type Safety
 *
 * All internal storage erases generics (required by [ConcurrentHashMap]),
 * but every retrieval path is runtime-verified:
 * - each value a provider emits is checked-cast through the **registered**
 *   `Need` class token before it enters the shared flow;
 * - every caller-facing flow is a [TypedDataSharedFlow] that checked-casts
 *   inner [DataState] payloads to the caller's `needType`, after an up-front
 *   [Class.isAssignableFrom] compatibility check.
 *
 * Because the shared job is typed by the *registered* `Need` (not by
 * whichever caller happened to arrive first), callers may request the same
 * impulse with any super-type of the provider's result type — e.g. `Any`
 * and `UserProfile` — and still share one job.
 *
 * ## Job Deduplication
 *
 * Active jobs are keyed by the [DataImpulse] instance using structural
 * equality (`data class` equals/hashCode). When a request arrives:
 *
 * 1. If an identical impulse already has a live job, the caller receives a
 *    view of the **same** shared `DataState` flow — no duplicate work.
 * 2. Otherwise, a new job is created and inserted **atomically**
 *    ([ConcurrentHashMap.compute]); concurrent equal requests always resolve
 *    to exactly one job. The job is created `LAZY` and only started after
 *    insertion, so the [ProviderFactory] is never invoked for a request that
 *    lost the race.
 * 3. When a job completes (success or error) it is removed from the active
 *    job table — but only if the table still maps the impulse to *that* job
 *    (`remove(key, value)`), so a finishing job never evicts a newer one.
 *
 * ## Subscriber Lifecycle & Idle Teardown
 *
 * Every flow returned by [request] reference-counts its collectors. A job
 * waits for its first collector to subscribe before invoking the provider,
 * so the collector is guaranteed to observe [DataState.Loading]. When the
 * collector count drops to zero (or never rises above zero), an idle timer
 * of [idleTimeoutMillis] starts:
 *
 * - if a collector (re)subscribes within the window, the timer is discarded
 *   and the job keeps running — the provider is **not** restarted;
 * - if the window elapses with no collectors, the job is cancelled, the
 *   provider's flow and [ProviderScope] are cancelled, and the entry is
 *   removed.
 *
 * Collecting a flow whose job has already been torn down transparently
 * attaches to a fresh job for the same impulse, so a returned flow never
 * silently hangs on a dead job. That fresh job is created with the collector
 * already registered, so no idle timer can tear it down before the collector
 * subscribes; the hand-off is retried a bounded number of times and then
 * fails with [IllegalStateException] instead of recursing. One-shot jobs that
 * complete normally are not torn down; a late collector still receives the
 * replayed terminal state.
 *
 * With `idleTimeoutMillis = 0` a job is torn down as soon as its last
 * collector leaves, and never while a collector is attached. A request that
 * is never collected is discarded at the worker's next opportunity (inline on
 * an unconfined dispatcher); if it is collected after that, the collector
 * simply attaches to a fresh job. The provider itself is never created for a
 * job that was discarded before its first collector.
 *
 * All timers run on the worker dispatcher, so they honour
 * `kotlinx-coroutines-test` virtual time when a test dispatcher is supplied.
 *
 * ## Termination
 *
 * A flow returned by [request] never completes normally (it is a
 * [SharedFlow]). Once this manager is [dispose]d — or its parent scope is
 * cancelled — **every** collection of such a flow fails with
 * [IllegalStateException]: collectors that are already running are
 * terminated (rather than left suspended forever), and new collectors fail
 * immediately, exactly as a new [request] does. This applies equally to
 * flows of streaming jobs and of one-shot jobs that already completed.
 * Until then, a one-shot job completing normally never terminates its
 * collectors; they keep the replayed terminal state.
 *
 * Because [SharedFlow.collect] cannot return, the termination is an
 * exception: an internal [IllegalStateException] subtype
 * ([ProviderManagerClosedException]), never a `CancellationException`, so a
 * running collector is not mistaken for a cancelled one. `DefaultSwitchBoard`
 * catches it in `handleRequest`, so on the board a running request stream
 * simply completes when the board is closed.
 *
 * ## Concurrency
 *
 * Each unique impulse runs in its own child coroutine under a [SupervisorJob],
 * so a failure in one provider never cancels another. Inside a job, the
 * provider runs in a nested `coroutineScope`: a coroutine the provider
 * `launch`es from its [ProviderScope] that fails is reported to that
 * provider's collectors as [DataState.Error] rather than escaping to the
 * parent scope's exception handler.
 *
 * The [maxConcurrentJobs] parameter limits the number of **live** jobs across
 * all impulse types. Permits are taken atomically when a job is created and
 * released when it completes, so the limit holds under concurrent requests.
 *
 * @param registry          the immutable [ProviderRegistry] mapping impulse
 *                          types to their factories.
 * @param parentScope       the parent [CoroutineScope] (typically the
 *                          SwitchBoard's scope). Provider jobs are children
 *                          of this scope.
 * @param workerContext     the [CoroutineContext] (usually a dispatcher)
 *                          providers and idle timers run on.
 * @param maxConcurrentJobs maximum number of simultaneous live provider jobs.
 *                          0 (or any non-positive value) means unbounded.
 * @param idleTimeoutMillis how long a job may have no collectors before it is
 *                          cancelled. Applies both to the wait for the first
 *                          collector and to the period after the last one
 *                          leaves. Must be `>= 0`; [Long.MAX_VALUE] disables
 *                          idle teardown. Omit it (use the four-argument
 *                          constructor) for [DEFAULT_IDLE_TIMEOUT_MILLIS].
 */
class ProviderManager(
    private val registry: ProviderRegistry,
    parentScope: CoroutineScope,
    workerContext: CoroutineContext = Dispatchers.IO,
    private val maxConcurrentJobs: Int = 0,
    // Deliberately required: a default here would replace the JVM signatures
    // of the 1.0 constructor below and break binary compatibility.
    private val idleTimeoutMillis: Long,
) {

    /**
     * Creates a manager with the default idle timeout
     * ([DEFAULT_IDLE_TIMEOUT_MILLIS]).
     *
     * This is the 1.0 constructor, kept with its exact JVM signatures
     * (`<init>(ProviderRegistry, CoroutineScope, CoroutineContext, int)` and
     * its synthetic defaults variant) so code compiled against 1.0 keeps
     * linking. Pass `idleTimeoutMillis` (by name) to choose another timeout.
     *
     * @param registry          the immutable [ProviderRegistry].
     * @param parentScope       the parent [CoroutineScope] of every provider job.
     * @param workerContext     the [CoroutineContext] providers and timers run on.
     * @param maxConcurrentJobs maximum number of simultaneous live provider
     *                          jobs; non-positive means unbounded.
     */
    constructor(
        registry: ProviderRegistry,
        parentScope: CoroutineScope,
        workerContext: CoroutineContext = Dispatchers.IO,
        maxConcurrentJobs: Int = 0,
    ) : this(registry, parentScope, workerContext, maxConcurrentJobs, DEFAULT_IDLE_TIMEOUT_MILLIS)

    init {
        require(idleTimeoutMillis >= 0) {
            "idleTimeoutMillis must be >= 0 (use Long.MAX_VALUE to disable idle teardown), was $idleTimeoutMillis"
        }
    }

    /** Parent of every provider job and idle timer. Cancelled by [dispose]. */
    private val supervisor: Job = SupervisorJob(parentScope.coroutineContext[Job])

    /**
     * The scope provider jobs run in. The [supervisor] is added last so that a
     * [Job] accidentally carried by [workerContext] can never replace it.
     */
    private val scope = CoroutineScope(parentScope.coroutineContext + workerContext + supervisor)

    private val disposed = AtomicBoolean(false)

    // ── Active Job Table ────────────────────────────────────────────────

    /**
     * Tracks currently active (in-flight) provider jobs.
     *
     * Keyed by the [DataImpulse] instance itself (structural equality),
     * so `FetchUser(42)` and `FetchUser(42)` share an entry while
     * `FetchUser(42)` and `FetchUser(99)` are distinct.
     */
    private val activeJobs = ConcurrentHashMap<Any, ActiveJob>()

    /**
     * Number of jobs that have been created and not yet completed.
     * Doubles as the permit counter for [maxConcurrentJobs].
     */
    private val liveJobs = AtomicInteger(0)

    // ── Request Dispatch ────────────────────────────────────────────────

    /**
     * Returns a [SharedFlow]`<DataState<Need>>` for the given [impulse].
     *
     * If an identical impulse is already being processed, a view of the
     * existing flow is returned (deduplication). Otherwise, a new [Provider]
     * job is created. The provider itself is not invoked until the returned
     * flow is first collected; if it is never collected, the job is discarded
     * after [idleTimeoutMillis].
     *
     * ## Cast Safety
     *
     * This method contains **no unchecked casts**. All type transitions
     * are handled by:
     * - [ProviderRegistry.RegisteredFactory.produceErased]: uses [Class.cast]
     *   for impulse input.
     * - The registered `Need` class token: checked-casts each produced value.
     * - [TypedDataSharedFlow]: checked element-wise output casting to [Need].
     *
     * @param Need        the expected result type.
     * @param I           the concrete [DataImpulse] subclass.
     * @param impulseType [KClass] token for [I] (used for factory lookup).
     * @param needType    [KClass] token for [Need] (used for checked casts).
     * @param impulse     the data impulse instance.
     * @param switchboard the [SwitchBoard] to pass to the [ProviderScope].
     * @return a [SharedFlow] that emits [DataState] transitions for this request.
     *         When [maxConcurrentJobs] is exceeded, the flow instead replays a
     *         single [DataState.Error] carrying a [ProviderConcurrencyException].
     * @throws NoProviderException if no factory is registered for [impulseType].
     * @throws IllegalArgumentException if [needType] is not a super-type of the
     *         registered provider's result type.
     * @throws IllegalStateException if this manager has been [dispose]d or its
     *         parent scope has been cancelled.
     */
    fun <Need : Any, I : DataImpulse<Need>> request(
        impulseType: KClass<I>,
        needType: KClass<Need>,
        impulse: I,
        switchboard: SwitchBoard,
    ): SharedFlow<DataState<Need>> {
        val lease = obtain(impulseType, needType, impulse, switchboard, attach = false)
        return RefCountedFlow(lease) {
            obtain(impulseType, needType, impulse, switchboard, attach = true)
        }
    }

    /**
     * Resolves [impulse] to a live job (deduplicating or creating one) and
     * returns a typed [Lease] on it.
     *
     * With [attach] `false` (a plain [request]) no collector is registered: a
     * newly created job arms its "never collected" idle timer. With [attach]
     * `true` (a collector re-attaching after its job died) a collector is
     * registered on the returned job **before** anything can tear it down — a
     * new job is created with one subscriber and no timer — so the caller can
     * subscribe without racing the idle timer, whatever the timeout and
     * dispatcher. The caller must later [ActiveJob.release] it.
     *
     * @return a [Lease]; its [Lease.entry] is `null` when the concurrency limit
     *         rejected the request, and [Lease.flow] then replays the error.
     * @throws IllegalStateException if this manager is disposed or its parent
     *         scope is cancelled, or if no live job could be attached to after
     *         [MAX_REATTACH_ATTEMPTS] attempts.
     */
    private fun <Need : Any, I : DataImpulse<Need>> obtain(
        impulseType: KClass<I>,
        needType: KClass<Need>,
        impulse: I,
        switchboard: SwitchBoard,
        attach: Boolean,
    ): Lease<Need> {
        repeat(MAX_REATTACH_ATTEMPTS) {
            checkUsable(impulseType)

            // ── Fast path: dedup onto a live job ────────────────────
            val existing = activeJobs[impulse]
            if (existing != null && existing.isLive) {
                val lease = existing.lease(impulseType, needType, impulse)
                if (!attach || existing.acquire()) return lease
                return@repeat // torn down in between — retry
            }

            // ── Resolve factory & verify types ──────────────────────
            val registered = registry.resolve(impulseType)
                ?: throw NoProviderException(impulseType)
            val producedClass = registered.needClass.kotlin.javaObjectType
            require(needType.javaObjectType.isAssignableFrom(producedClass)) {
                "Type mismatch for ${impulseType.qualifiedName}: its registered provider produces " +
                        "${producedClass.name}, but the caller requested ${needType.qualifiedName}. " +
                        "The requested Need type must be the provider's result type or a super-type of it."
            }

            // ── Atomic lookup-or-create ─────────────────────────────
            // The candidate is created LAZY (no dispatch, factory not invoked) so
            // that losing the race below costs only an allocation. When
            // attaching, it is born with its collector already registered.
            val candidate = ActiveJob(
                impulse, impulseType, producedClass, registered, switchboard,
                initialSubscribers = if (attach) 1 else 0,
            )
            var winner: ActiveJob? = null
            activeJobs.compute(impulse) { _, current ->
                when {
                    current != null && current.isLive -> {
                        winner = current
                        current
                    }
                    tryAcquirePermit() -> {
                        winner = candidate
                        candidate
                    }
                    else -> current // over the limit — leave the table untouched
                }
            }

            val resolved = winner
            if (resolved !== candidate) {
                candidate.job.cancel() // never started — provider never created
            }
            if (resolved == null) {
                val overflow = MutableSharedFlow<DataState<Need>>(
                    replay = 1,
                    extraBufferCapacity = 0,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                overflow.tryEmit(DataState.Error(ProviderConcurrencyException(impulseType, maxConcurrentJobs)))
                return Lease(null, overflow)
            }
            if (resolved === candidate) {
                // Registered after insertion so the handler can never run inside
                // the map's bin lock. If the job already completed (parent scope
                // cancelled), the handler runs immediately.
                candidate.job.invokeOnCompletion { cause -> candidate.onCompleted(cause) }
                candidate.job.start()
                if (!attach) candidate.scheduleIdleCheck()
                return candidate.lease(impulseType, needType, impulse)
            }
            // Deduplicated onto a job that won the race.
            val lease = resolved.lease(impulseType, needType, impulse)
            if (!attach || resolved.acquire()) return lease
        }
        throw IllegalStateException(
            "Could not attach to a live provider job for $impulse after $MAX_REATTACH_ATTEMPTS attempts; " +
                    "its jobs kept being torn down before a collector could subscribe."
        )
    }

    /** @throws ProviderManagerClosedException if this manager can no longer serve requests. */
    private fun checkUsable(impulseType: KClass<*>) {
        if (disposed.get()) {
            throw ProviderManagerClosedException(
                this,
                "ProviderManager has been disposed; cannot process ${impulseType.simpleName}.",
            )
        }
        if (!supervisor.isActive) {
            throw ProviderManagerClosedException(
                this,
                "ProviderManager's parent scope has been cancelled; cannot process ${impulseType.simpleName}.",
            )
        }
    }

    private fun tryAcquirePermit(): Boolean {
        if (maxConcurrentJobs <= 0) {
            liveJobs.incrementAndGet()
            return true
        }
        while (true) {
            val current = liveJobs.get()
            if (current >= maxConcurrentJobs) return false
            if (liveJobs.compareAndSet(current, current + 1)) return true
        }
    }

    // ── Active Job ──────────────────────────────────────────────────────

    /**
     * One deduplicated provider execution: the shared [DataState] flow, the
     * coroutine [Job] driving it, and the collector reference count used for
     * idle teardown.
     *
     * @property key         the impulse this job serves (the table key).
     * @property impulseType the impulse's [KClass], for diagnostics.
     * @property needClass   the boxed class of the **registered** result type;
     *                       every produced value is checked-cast through it.
     */
    private inner class ActiveJob(
        val key: Any,
        val impulseType: KClass<*>,
        val needClass: Class<*>,
        registered: ProviderRegistry.RegisteredFactory<*, *>,
        switchboard: SwitchBoard,
        initialSubscribers: Int,
    ) {
        /**
         * The flow driving [DataState] transitions. `replay = 1` so late
         * collectors observe the latest state; the extra buffer with
         * [BufferOverflow.DROP_OLDEST] means emission never suspends the
         * provider on a slow collector.
         */
        val sharedFlow = MutableSharedFlow<DataState<Any>>(
            replay = 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val job: Job = scope.launch(start = CoroutineStart.LAZY) {
            execute(registered, switchboard)
        }

        private val lock = Any()
        /** Registered collectors; starts at 1 for a job created on a collector's behalf. */
        private var subscribers = initialSubscribers

        /** Bumped on every subscribe / idle-timer schedule; stale timers compare against it. */
        private var idleEpoch = 0L
        private var idleTimer: Job? = null

        /** `true` once this job has been torn down abnormally (idle, dispose, cancellation). */
        @Volatile
        private var dead = false

        /** Whether new requests may dedup onto this job. */
        val isLive: Boolean get() = !dead && !job.isCancelled && !job.isCompleted

        private suspend fun execute(
            registered: ProviderRegistry.RegisteredFactory<*, *>,
            switchboard: SwitchBoard,
        ) {
            // Gate on the first real subscription so the collector is
            // guaranteed to observe Loading. If none ever arrives, the idle
            // timer cancels us.
            sharedFlow.subscriptionCount.first { it > 0 }
            sharedFlow.emit(DataState.Loading)

            var lastSuccess: Any? = null
            try {
                coroutineScope {
                    val providerScope = ProviderScope(switchboard, this)
                    registered.produceErased(key, providerScope).collect { value ->
                        val typed: Any = needClass.cast(value)
                        lastSuccess = typed
                        sharedFlow.emit(DataState.Success(typed))
                    }
                }
            } catch (ce: CancellationException) {
                // Our own cancellation (idle / dispose / parent) must propagate.
                // A CancellationException thrown by the provider while we are
                // still active (e.g. a TimeoutCancellationException from its
                // own withTimeout) is a provider failure like any other.
                if (!currentCoroutineContext().isActive) throw ce
                sharedFlow.emit(DataState.Error(ce, lastSuccess))
            } catch (e: Throwable) {
                sharedFlow.emit(DataState.Error(e, lastSuccess))
            }
        }

        /**
         * Registers a collector. Returns `false` if this job has already been
         * torn down, in which case the caller must re-request.
         */
        fun acquire(): Boolean {
            val timer: Job?
            synchronized(lock) {
                if (dead || job.isCancelled) return false
                subscribers++
                idleEpoch++
                timer = idleTimer
                idleTimer = null
            }
            timer?.cancel()
            return true
        }

        /** Unregisters a collector, starting the idle timer when none remain. */
        fun release() {
            synchronized(lock) { subscribers-- }
            scheduleIdleCheck()
        }

        /** Starts an idle timer if this job currently has no collectors. */
        fun scheduleIdleCheck() {
            if (idleTimeoutMillis == Long.MAX_VALUE) return
            val epoch: Long
            synchronized(lock) {
                if (subscribers > 0 || dead || job.isCompleted) return
                epoch = ++idleEpoch
            }
            // Launched outside the lock: with an unconfined dispatcher the
            // timer may run inline.
            val timer = scope.launch {
                delay(idleTimeoutMillis)
                expireIfIdle(epoch)
            }
            val stale = synchronized(lock) {
                if (idleEpoch == epoch && !dead) {
                    idleTimer = timer
                    false
                } else {
                    true
                }
            }
            if (stale) timer.cancel()
        }

        private fun expireIfIdle(epoch: Long) {
            val expire = synchronized(lock) {
                if (epoch == idleEpoch && subscribers == 0 && !dead && !job.isCompleted) {
                    dead = true
                    idleTimer = null
                    true
                } else {
                    false
                }
            }
            if (expire) job.cancel(CancellationException("Provider for $key idle for ${idleTimeoutMillis}ms"))
        }

        /** Marks this job dead (if not already) and cancels it. */
        fun kill(cause: CancellationException) {
            synchronized(lock) { dead = true }
            job.cancel(cause)
        }

        fun onCompleted(cause: Throwable?) {
            val timer: Job?
            synchronized(lock) {
                if (cause != null) dead = true
                timer = idleTimer
                idleTimer = null
            }
            timer?.cancel()
            activeJobs.remove(key, this)
            liveJobs.decrementAndGet()
        }

        /**
         * Suspends until a collector of this job must stop: the job was torn
         * down abnormally (idle, [dispose], parent cancellation), or — for a
         * job that completed normally — this manager was disposed or its
         * parent scope cancelled. Never returns while the job is running, nor
         * after normal completion while the manager is alive.
         */
        suspend fun awaitTermination() {
            job.join()
            if (!job.isCancelled) supervisor.join()
        }

        /**
         * Returns a [Lease] on this job whose flow is checked-cast to
         * [needType]. Does not register a collector.
         *
         * @throws IllegalArgumentException if [needType] is not a super-type
         *         of [needClass].
         */
        fun <Need : Any, I : DataImpulse<Need>> lease(
            impulseType: KClass<I>,
            needType: KClass<Need>,
            impulse: I,
        ): Lease<Need> {
            require(needType.javaObjectType.isAssignableFrom(needClass)) {
                "Type mismatch for ${impulseType.qualifiedName}: the in-flight provider job for $impulse " +
                        "produces ${needClass.name}, but the caller requested ${needType.qualifiedName}. " +
                        "The requested Need type must be the provider's result type or a super-type of it."
            }
            return Lease(this, TypedDataSharedFlow(sharedFlow, needType))
        }
    }

    /**
     * A typed handle on one job's flow.
     *
     * @property entry the job, or `null` for a request the concurrency limit
     *                 rejected (then [flow] replays the error and is not
     *                 reference-counted).
     * @property flow  the typed [DataState] flow to collect.
     */
    private class Lease<T : Any>(
        val entry: ProviderManager.ActiveJob?,
        val flow: SharedFlow<DataState<T>>,
    )

    /** Internal signal: the job a collector was attached to has terminated. */
    private class JobTerminatedSignal : RuntimeException("provider job terminated", null, false, false)

    /**
     * Caller-facing [SharedFlow] that reference-counts its collectors on the
     * owning [ActiveJob].
     *
     * - If the job has been torn down before collection starts, the collector
     *   is re-attached to a fresh job — pre-registered, so it cannot be torn
     *   down again before the collector subscribes ([obtain] with
     *   `attach = true`). This is a bounded loop, never recursion.
     * - While collecting, a watcher races the collection against
     *   [ActiveJob.awaitTermination]. When the job dies abnormally (or the
     *   manager goes away), the collector re-attaches the same way; once the
     *   manager is disposed or its scope cancelled, re-attaching throws
     *   [IllegalStateException], which terminates the collector instead of
     *   leaving it suspended on a shared flow that will never emit again.
     *
     * @param initial  the lease [request] obtained.
     * @param reAttach obtains a fresh, already-acquired lease for the same
     *                 impulse; throws [IllegalStateException] when the manager
     *                 is no longer usable.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private inner class RefCountedFlow<T : Any>(
        private val initial: Lease<T>,
        private val reAttach: () -> Lease<T>,
    ) : SharedFlow<DataState<T>> {

        override val replayCache: List<DataState<T>> get() = initial.flow.replayCache

        override suspend fun collect(collector: FlowCollector<DataState<T>>): Nothing {
            var lease = initial
            var attached = lease.entry?.acquire() ?: true
            var reattachments = 0
            while (true) {
                if (attached) {
                    val entry = lease.entry
                    try {
                        collectUntilTerminated(lease, collector)
                    } finally {
                        entry?.release()
                    }
                }
                check(++reattachments <= MAX_REATTACH_ATTEMPTS) {
                    "A collector of ${lease.entry?.key ?: "a provider request"} was detached from its provider " +
                            "job $MAX_REATTACH_ATTEMPTS times; giving up."
                }
                lease = reAttach() // already acquired (or a non-counted overflow lease)
                attached = true
            }
        }

        /**
         * Collects [lease]'s flow until its job terminates (see
         * [ActiveJob.awaitTermination]), and only then returns normally. A
         * rejected (overflow) lease terminates only with the manager.
         */
        private suspend fun collectUntilTerminated(lease: Lease<T>, collector: FlowCollector<DataState<T>>) {
            val entry = lease.entry
            try {
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        if (entry != null) entry.awaitTermination() else supervisor.join()
                        throw JobTerminatedSignal()
                    }
                    lease.flow.collect(collector)
                }
            } catch (signal: JobTerminatedSignal) {
                // Fall through: the caller re-attaches (or fails if disposed).
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Cancels all active provider jobs and idle timers, and clears the job
     * table. Idempotent.
     *
     * After disposal, [request] throws [IllegalStateException], and every
     * collection of a flow previously returned by [request] fails the same
     * way: collectors that are already running are terminated (they do not
     * hang), and new collectors fail immediately. See "Termination" in the
     * class docs.
     *
     * Called when the owning [SwitchBoard] is torn down.
     */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val cause = CancellationException("ProviderManager disposed")
        activeJobs.values.forEach { it.kill(cause) }
        activeJobs.clear()
        supervisor.cancel(cause)
    }

    /** `true` once [dispose] has been called. */
    val isDisposed: Boolean get() = disposed.get()

    /**
     * Returns the number of live provider jobs: created and not yet
     * completed (including jobs still waiting for their first collector or
     * finishing cancellation). This is the quantity [maxConcurrentJobs]
     * bounds. Useful for diagnostics and testing.
     */
    val activeJobCount: Int get() = liveJobs.get()

    companion object {
        /** Default for [idleTimeoutMillis]; matches the SwitchBoard's default stop timeout. */
        const val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 3_000L

        /**
         * How many times one collection may be re-attached to a fresh job (or
         * one lookup retried) before it fails with [IllegalStateException].
         * In practice a hand-off succeeds on the first re-attach, because the
         * fresh job is created with its collector already registered.
         */
        private const val MAX_REATTACH_ATTEMPTS: Int = 8
    }
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
            "Annotate a Provider implementation with @SynapseProvider for this impulse type.",
) : RuntimeException(message)

/**
 * The [IllegalStateException] a [ProviderManager] throws once it can no longer
 * serve requests — after [ProviderManager.dispose] or once its parent scope is
 * cancelled — both from [ProviderManager.request] and to collectors of the
 * flows it returned.
 *
 * Internal so that the owning [SwitchBoard] can tell "my provider runtime is
 * gone" apart from any other [IllegalStateException] (for example one thrown
 * by a downstream collector) and end its request streams normally.
 *
 * @property owner the manager that threw it.
 */
internal class ProviderManagerClosedException(
    val owner: ProviderManager,
    message: String,
) : IllegalStateException(message)

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
