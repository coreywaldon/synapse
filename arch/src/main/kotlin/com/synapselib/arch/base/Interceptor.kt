package com.synapselib.arch.base

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * A composable interceptor that can observe, transform, or block data
 * flowing through a [Chain].
 *
 * Interceptors form a pipeline (similar to OkHttp interceptors or middleware stacks):
 * each interceptor receives the current data and a [Chain] handle that delegates
 * to the next interceptor in line. The **end of the chain is the dispatch**: a
 * value is only emitted / routed / delivered if a [Chain.proceed] call reaches
 * the end of the chain. An interceptor may:
 *
 * - **Pass through**: call `chain.proceed(data)` unchanged (observation / logging).
 * - **Transform**: modify data before or after calling `chain.proceed`. The
 *   value that is dispatched is the one the pipeline **returns**, so
 *   post-processing the result of `proceed` changes what is dispatched.
 * - **Substitute**: call `chain.proceed(substitute)` to dispatch a different
 *   value instead of the one received.
 * - **Block**: return *without* calling `chain.proceed`. The remaining
 *   interceptors are skipped, the end of the chain is never reached and the
 *   value is **not dispatched**; the value this interceptor returns is
 *   discarded. On a [SwitchBoard] this means: a blocked state / reaction is
 *   never emitted, a blocked request never reaches its provider, and a blocked
 *   downstream value is not delivered (see [SwitchBoard.addInterceptor]).
 *
 * An interceptor may also call `chain.proceed` more than once (retry): the
 * value is dispatched **once**, as the pipeline's final return value, as long
 * as at least one `proceed` call reached the end of the chain. `proceed` must
 * be called before [intercept] returns — a call made later (e.g. from a
 * coroutine launched by the interceptor) does not count.
 *
 * This is a **functional (SAM) interface**, so it can be implemented with a lambda:
 * ```kotlin
 * val logger = Interceptor<Request> { data, chain ->
 *     println("Before: $data")
 *     val result = chain.proceed(data)
 *     println("After: $result")
 *     result
 * }
 * ```
 *
 * For common patterns, prefer the factory methods in the [Companion]:
 * [read], [transform], and [full].
 *
 * @param T the type of data flowing through the interceptor chain.
 */
fun interface Interceptor<T> {

    /**
     * Intercepts the given [data] and optionally delegates to the next interceptor
     * via [chain].
     *
     * @param data  the current value flowing through the pipeline.
     * @param chain handle to invoke the next interceptor; call [Chain.proceed] to
     *              continue the chain, or omit the call to **block** the value
     *              (it is then not dispatched).
     * @return the (possibly transformed) result to be returned to the caller or
     *         the upstream interceptor. When the value is blocked (no `proceed`
     *         call reached the end of the chain), the returned value is
     *         discarded by the dispatching pipeline; it is still returned to an
     *         upstream interceptor that called `proceed`.
     */
    suspend fun intercept(data: T, chain: Chain<T>): T

    /**
     * Represents the downstream portion of an interceptor pipeline.
     *
     * Calling [proceed] hands data to the next interceptor in the chain. If
     * there are no more interceptors, [proceed] has reached the **end of the
     * chain** (the terminal): the pipeline records that the value may be
     * dispatched, and the terminal returns data as-is (identity) so that
     * upstream interceptors can still post-process it.
     *
     * If no `proceed` call of an execution reaches the terminal (because some
     * interceptor did not call it), the value is **blocked** — see
     * [InterceptorPipeline.applyInterceptorsOrNull].
     *
     * @param T the type of data flowing through the chain.
     */
    fun interface Chain<T> {

        /**
         * Forwards [data] to the next interceptor in the pipeline and returns the
         * result produced by the remainder of the chain.
         *
         * May be called several times (retry) or not at all (block).
         *
         * @param data the value to pass downstream.
         * @return the result after all downstream interceptors have executed.
         *         If a later interceptor blocked the value, this is whatever
         *         that interceptor returned (the value stays blocked unless
         *         another `proceed` call of the same execution reaches the end
         *         of the chain).
         */
        suspend fun proceed(data: T): T
    }

    companion object {
        /**
         * Creates a **read-only** (side effect) interceptor.
         *
         * The [block] receives the current data for inspection (e.g. logging,
         * metrics, analytics) but cannot modify it. The interceptor always
         * proceeds to the next link in the chain with the original data.
         *
         * ```kotlin
         * registry.add(Interceptor.read<Request> { request ->
         *     logger.info("Processing ${request.id}")
         * })
         * ```
         *
         * @param block a suspending function invoked with the current data.
         * @return an [Interceptor] that observes data without modifying it.
         */
        fun <T> read(block: suspend (T) -> Unit): Interceptor<T> = Interceptor { data, chain ->
            block(data)
            chain.proceed(data)
        }

        /**
         * Creates a **transforming** interceptor.
         *
         * The [block] receives the current data and returns a new (or the same)
         * value that will be forwarded to the next interceptor via `chain.proceed`.
         *
         * ```kotlin
         * registry.add(Interceptor.transform<Request> { request ->
         *     request.copy(timestamp = Clock.System.now())
         * })
         * ```
         *
         * @param block a suspending function that maps input data to output data.
         * @return an [Interceptor] that transforms data before it continues
         *         down the chain.
         */
        fun <T> transform(block: suspend (T) -> T): Interceptor<T> = Interceptor { data, chain ->
            chain.proceed(block(data))
        }

        /**
         * Creates an interceptor with **full control** over the chain.
         *
         * The [block] receives both the current data and a `proceed` function.
         * This allows the interceptor to:
         * - Modify data before *and* after proceeding.
         * - Substitute a different value: `proceed(substitute)`.
         * - Call `proceed` multiple times (retry); the value is still
         *   dispatched once, as the final return value.
         * - Skip `proceed` entirely to **block** the value: it is not
         *   dispatched, and whatever [block] returns is discarded.
         *
         * ```kotlin
         * // Retry
         * registry.add(Interceptor.full<Request> { request, proceed ->
         *     try {
         *         proceed(request)
         *     } catch (e: Exception) {
         *         proceed(request.copy(retryCount = request.retryCount + 1))
         *     }
         * })
         *
         * // Gate: requests only reach their provider with a valid session
         * switchBoard.addInterceptor<RequiresAuth>(
         *     InterceptPoint.REQUEST_UPSTREAM,
         *     Interceptor.full { impulse, proceed ->
         *         if (session.isValid()) proceed(impulse)
         *         else { switchBoard.triggerImpulse(NavigateToLogin); impulse } // blocked
         *     },
         * )
         * ```
         *
         * @param block a suspending function that receives the data and a
         *              `proceed` callback for invoking downstream interceptors.
         * @return an [Interceptor] with arbitrary control over the pipeline.
         */
        fun <T> full(block: suspend (data: T, proceed: suspend (T) -> T) -> T): Interceptor<T> =
            Interceptor { data, chain ->
                block(data) { chain.proceed(it) }
            }
    }
}

/**
 * A handle returned when registering an interceptor, allowing the caller to
 * later remove it from the registry.
 *
 * Handles returned by the library ([InterceptorRegistry.add],
 * [SwitchBoard.addInterceptor], [SwitchBoard.addObserver], …) are
 * idempotent: calling [unregister] more than once is a safe no-op. Custom
 * implementations should follow the same contract.
 */
fun interface Registration {

    /**
     * Removes the associated interceptor from the registry.
     *
     * After this call the interceptor will no longer participate in future
     * pipeline executions. In-flight executions that already captured a
     * snapshot of the chain are unaffected.
     */
    fun unregister()
}

/**
 * A type-safe pipeline that applies all registered [Interceptor]s for a given
 * data type, in priority order.
 *
 * ## Outcome of an execution
 *
 * An execution either **reaches the end of the chain** (some
 * [Interceptor.Chain.proceed] call got past the last interceptor) — the value
 * is then *dispatched*, as the value the pipeline returns — or it does not,
 * because an interceptor returned without calling `proceed` — the value is
 * then *blocked*. [applyInterceptorsOrNull] reports this outcome; it is what
 * [DefaultSwitchBoard] uses to decide whether to emit, route or deliver a
 * value. [applyInterceptors] predates it and returns the chain's result
 * regardless of the outcome.
 *
 * Implementations are expected to be safe for concurrent use.
 *
 * @see InterceptorRegistry for the default implementation.
 */
interface InterceptorPipeline {

    /**
     * Applies all interceptors registered for [clazz] to [data], returning the
     * final result after the full chain has executed.
     *
     * This returns the value the chain **returned**, whether or not the end of
     * the chain was reached: if an interceptor blocked the value (did not call
     * `proceed`), the value that interceptor returned is returned here. Use
     * [applyInterceptorsOrNull] to tell a blocked value apart.
     *
     * If no interceptors are registered for the given type, [data] is returned
     * unchanged.
     *
     * @param T     the data type, which must be a non-nullable [Any] subtype.
     * @param clazz the [KClass] token used to look up registered interceptors.
     * @param data  the initial value to pass into the interceptor chain.
     * @return the result produced by the interceptor chain.
     */
    suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T

    /**
     * Applies all interceptors registered for [clazz] to [data] and reports
     * the **outcome**: the value to dispatch, or `null` if the value was
     * blocked.
     *
     * - If at least one [Interceptor.Chain.proceed] call reached the end of
     *   the chain, the value is dispatched and this returns the value the
     *   chain **returned** — exactly what [applyInterceptors] returns, so
     *   transformations, post-processing after `proceed` and retries apply as
     *   usual.
     * - If no `proceed` call reached the end of the chain (some interceptor
     *   returned without calling it), the value is **blocked**: this returns
     *   `null`, and the value the interceptor returned is discarded.
     *
     * If no interceptors are registered for the given type, [data] is returned
     * unchanged (nothing can block it).
     *
     * The default implementation cannot observe the chain, so it never
     * blocks: it returns [applyInterceptors]' result. [InterceptorRegistry]
     * overrides it; third-party implementations should override it too if
     * their interceptors can block.
     *
     * ```kotlin
     * val toDispatch: MyEvent? = pipeline.applyInterceptorsOrNull(MyEvent::class, event)
     * if (toDispatch != null) emit(toDispatch) // else: blocked by an interceptor
     * ```
     *
     * @param T     the data type, which must be a non-nullable [Any] subtype.
     * @param clazz the [KClass] token used to look up registered interceptors.
     * @param data  the initial value to pass into the interceptor chain.
     * @return the value to dispatch, or `null` if an interceptor blocked it.
     * @since 1.1.0
     */
    suspend fun <T : Any> applyInterceptorsOrNull(clazz: KClass<out T>, data: T): T? =
        applyInterceptors(clazz, data)
}

/**
 * Reified convenience overload for [InterceptorPipeline.applyInterceptors] that
 * infers the [KClass] token from the type parameter.
 *
 * ```kotlin
 * val processed: MyRequest = pipeline.applyInterceptors(myRequest)
 * ```
 *
 * @param T    the data type (inferred).
 * @param data the initial value to pass into the interceptor chain.
 * @return the result produced by the interceptor chain.
 */
suspend inline fun <reified T : Any> InterceptorPipeline.applyInterceptors(data: T): T =
    applyInterceptors(T::class, data)

/**
 * Reified convenience overload for [InterceptorPipeline.applyInterceptorsOrNull]
 * that infers the [KClass] token from the type parameter.
 *
 * ```kotlin
 * val toDispatch: MyRequest? = pipeline.applyInterceptorsOrNull(myRequest)
 * ```
 *
 * @param T    the data type (inferred).
 * @param data the initial value to pass into the interceptor chain.
 * @return the value to dispatch, or `null` if an interceptor blocked it.
 * @since 1.1.0
 */
suspend inline fun <reified T : Any> InterceptorPipeline.applyInterceptorsOrNull(data: T): T? =
    applyInterceptorsOrNull(T::class, data)

/**
 * Thread-safe, priority-ordered registry of [Interceptor]s keyed by data type.
 *
 * Interceptors are grouped by their target [KClass] and executed in
 * **ascending priority** order (lowest value runs first). Interceptors with
 * equal priority are executed in **FIFO insertion order**. An interceptor
 * registered for a supertype (e.g. `Any::class` or a sealed parent) also
 * applies to all of its subtypes, merged into the same priority order.
 *
 * ## Blocking
 *
 * Each execution tracks whether a `proceed` call reached the end of the chain.
 * [applyInterceptorsOrNull] returns `null` when none did (the value was
 * blocked); [applyInterceptors] returns the chain's result either way. The
 * tracking state lives in a per-call execution record, never in the shared,
 * cached pipeline, so concurrent executions of the same pipeline are fully
 * independent.
 *
 * ## Performance
 *
 * The resolved, sorted pipeline for each target type is cached, so a
 * steady-state call performs one map lookup. When **no** interceptor applies
 * to the type, a call allocates nothing at all. Otherwise it allocates one
 * small per-call execution record plus one chain link per interceptor
 * actually entered (links are reused when `proceed` is called several times).
 * The cache is invalidated by a version stamp that only advances on effective
 * registrations and removals.
 *
 * ## Thread Safety
 *
 * All public methods are safe to call from any thread or coroutine.
 * Registration and un-registration may happen concurrently with pipeline
 * execution. Note, however, that an in-flight [applyInterceptors] call sees a
 * snapshot of the interceptor set taken when the call starts; changes that
 * occur *during* execution will only be visible to subsequent calls.
 *
 * ## Usage
 *
 * ```kotlin
 * val registry = InterceptorRegistry()
 *
 * // Register interceptors (optionally with priority)
 * val reg = registry.add(Interceptor.read<MyEvent> { println(it) }, priority = 10)
 * registry.add(Interceptor.transform<MyEvent> { it.copy(processed = true) }, priority = 0)
 *
 * // Execute the pipeline
 * val result = registry.applyInterceptors(MyEvent("hello"))
 *
 * // … or find out whether an interceptor blocked the value
 * val toDispatch: MyEvent? = registry.applyInterceptorsOrNull(MyEvent("hello"))
 *
 * // Later, remove an interceptor
 * reg.unregister()
 * ```
 */
class InterceptorRegistry : InterceptorPipeline {

    /**
     * Type-safe wrapper that pairs a [Class] token with the original [Interceptor].
     *
     * All internal casts go through [Class.cast], providing a checked,
     * runtime-verified cast rather than an unchecked suppression. This
     * preserves type safety even though the backing map erases generics.
     *
     * @param T              the interceptor's data type.
     * @property priority       numeric priority; lower values execute first.
     * @property insertionOrder globally unique, monotonically increasing counter
     *                          used to break priority ties in FIFO order.
     * @property javaClass      the **boxed** [Class] token for runtime type
     *                          checking/casting ([KClass.javaObjectType], so that
     *                          primitive types such as `Int` work).
     * @property interceptor    the underlying [Interceptor] instance.
     */
    private class Entry<T : Any>(
        val priority: Int,
        val insertionOrder: Long,
        val javaClass: Class<T>,
        val interceptor: Interceptor<T>,
    ) {
        /**
         * Invokes this entry's [interceptor] with an untyped [data] value,
         * performing a checked cast on the input. [next] is the link for the
         * remainder of the pipeline; it checked-casts the downstream result
         * back to [T] itself.
         *
         * @throws ClassCastException if [data] is not assignable to [T].
         */
        suspend fun interceptUntyped(data: Any, next: Link<*>): Any {
            @Suppress("UNCHECKED_CAST") // `next` was built by linkTo(), so its result class is javaClass
            val typedNext = next as Link<T>
            return interceptor.intercept(javaClass.cast(data), typedNext)
        }

        /** Creates the link handed to this entry: it resumes [execution] at [nextIndex]. */
        fun linkTo(execution: Execution, nextIndex: Int): Link<T> =
            Link(execution, nextIndex, javaClass)
    }

    /**
     * The per-call state of **one** pipeline execution.
     *
     * Holds the immutable, shared snapshot [entries] and records whether any
     * `proceed` call of this execution reached the end of the chain. A new
     * record is created for every call, so concurrent executions of the same
     * cached pipeline never share mutable state.
     *
     * @property entries the sorted interceptor snapshot this execution runs.
     */
    private class Execution(val entries: Array<Entry<*>>) {

        /**
         * `true` once a `proceed` call reached the terminal link. Written by
         * whichever coroutine reaches the terminal (an interceptor may call
         * `proceed` from another thread), read after the head link returns.
         */
        @Volatile
        var terminalReached: Boolean = false

        /** Runs the chain from the first interceptor; returns the head's result. */
        suspend fun run(targetClass: Class<*>, data: Any): Any {
            @Suppress("UNCHECKED_CAST") // targetClass is the boxed class of `data`'s type token
            val head = Link(this, 0, targetClass as Class<Any>)
            return head.proceed(data)
        }
    }

    /**
     * Index-based chain link over an immutable snapshot array, in the style of
     * OkHttp's `RealInterceptorChain`.
     *
     * A link is described by its [execution] (the shared snapshot plus the
     * per-call outcome), the [index] of the entry it runs next, and the
     * [resultClass] its holder expects back. The link for `index + 1` is
     * created on the first [proceed] and reused afterwards, so calling
     * [proceed] several times (retry) allocates nothing more, and not calling
     * it at all (block) never allocates the rest of the chain.
     *
     * The link at `index == entries.size` is the **terminal**: it marks the
     * execution as having reached the end of the chain (the value may be
     * dispatched) and returns its input unchanged.
     *
     * @param T the data type expected by the interceptor that holds this link.
     */
    private class Link<T : Any>(
        private val execution: Execution,
        private val index: Int,
        private val resultClass: Class<T>,
    ) : Interceptor.Chain<T> {

        /**
         * Lazily created link for `index + 1`. Racing `proceed` calls (an
         * interceptor calling it concurrently) may each create one; they are
         * equivalent, and [Link]'s other fields are final, so publication is
         * safe.
         */
        private var next: Link<*>? = null

        override suspend fun proceed(data: T): T {
            val entries = execution.entries
            if (index == entries.size) {
                // Terminal link: the end of the chain was reached -> dispatch.
                execution.terminalReached = true
                return resultClass.cast(data)
            }
            val entry = entries[index]
            val link = next ?: entry.linkTo(execution, index + 1).also { next = it }
            return resultClass.cast(entry.interceptUntyped(data, link))
        }
    }

    /**
     * Comparator: lowest priority first, ties broken by insertion order (FIFO).
     *
     * Because [Entry.insertionOrder] is globally unique, no two distinct entries
     * are ever considered "equal" — a requirement for correct
     * [ConcurrentSkipListSet] behavior (which uses comparator equality in lieu
     * of `equals`/`hashCode`).
     */
    private val entryComparator = Comparator<Entry<*>> { a, b ->
        val cmp = a.priority.compareTo(b.priority)
        if (cmp != 0) cmp else a.insertionOrder.compareTo(b.insertionOrder)
    }

    /** Monotonically increasing counter that ensures unique insertion ordering. */
    private val insertionCounter = AtomicLong(0)

    /**
     * Global version stamp, incremented on every *effective* add/remove. Used
     * to cheaply detect whether the [resolvedCache] entries are still valid
     * without clearing the entire cache or taking a lock.
     */
    private val version = AtomicLong(0)

    /** Current [version] stamp; exposed for tests and diagnostics only. */
    internal val currentVersion: Long get() = version.get()

    /**
     * The primary data structure: a map from data type ([KClass]) to a sorted
     * set of [Entry] instances ordered by [entryComparator].
     *
     * Both the outer map and the inner sets are concurrent, allowing
     * lock-free reads and writes from multiple threads.
     */
    private val map = ConcurrentHashMap<KClass<*>, ConcurrentSkipListSet<Entry<*>>>()

    /**
     * Cached, pre-resolved pipelines keyed by target [KClass].
     *
     * Each value is a [Snapshot]: the [version] at which it was computed and
     * the flattened, sorted entries that matched the key type via
     * [Class.isAssignableFrom]. On a cache hit with a matching version the hot
     * path is a single map lookup + long comparison, with no allocation.
     *
     * Entries become stale (but never incorrect) when [version] advances;
     * stale entries are lazily recomputed on the next access.
     */
    private val resolvedCache = ConcurrentHashMap<KClass<*>, Snapshot>()

    /**
     * An immutable, resolved pipeline tagged with the [version] at which it
     * was computed. Shared by all concurrent executions; it holds no
     * per-call state.
     *
     * @property snapshotVersion the registry version this snapshot reflects.
     * @property entries         the sorted entries, or `null` when no
     *                           interceptor applies (identity pipeline).
     * @property targetClass     the boxed class of the snapshot's key type.
     */
    private class Snapshot(
        val snapshotVersion: Long,
        val entries: Array<Entry<*>>?,
        val targetClass: Class<*>,
    )

    /**
     * [Registration] handle for one entry. [unregister] is truly idempotent:
     * only the first call removes the entry and advances [version]; later
     * calls neither touch the set nor invalidate the resolved caches.
     */
    private inner class EntryRegistration(
        private val set: ConcurrentSkipListSet<Entry<*>>,
        private val entry: Entry<*>,
    ) : Registration {
        private val active = AtomicBoolean(true)

        override fun unregister() {
            if (active.compareAndSet(true, false)) {
                set.remove(entry)
                version.incrementAndGet()
            }
        }
    }

    /**
     * Registers an [interceptor] for the given data type [clazz].
     *
     * The interceptor also applies to every **subtype** of [clazz] (e.g. an
     * interceptor registered for `Any::class` sees every value).
     *
     * Lower [priority] values execute first. Interceptors with the same
     * priority run in the order they were registered (FIFO).
     *
     * @param T           the data type the interceptor operates on.
     * @param clazz       the [KClass] token identifying the data type.
     * @param interceptor the interceptor to register.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return a [Registration] handle — call [Registration.unregister] to remove
     *         the interceptor from future pipeline executions. Unregistering is
     *         idempotent.
     */
    fun <T : Any> add(
        clazz: KClass<T>,
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration {
        val set = map.getOrPut(clazz) { ConcurrentSkipListSet(entryComparator) }
        val entry = Entry(priority, insertionCounter.getAndIncrement(), clazz.javaObjectType, interceptor)
        set.add(entry)
        version.incrementAndGet()
        return EntryRegistration(set, entry)
    }

    /**
     * Reified convenience overload that infers the [KClass] token from the
     * type parameter [T].
     *
     * @param T           the data type the interceptor operates on (inferred).
     * @param interceptor the interceptor to register.
     * @param priority    execution priority; lower values run first. Defaults to `0`.
     * @return a [Registration] handle.
     * @see add
     */
    inline fun <reified T : Any> add(
        interceptor: Interceptor<T>,
        priority: Int = 0,
    ): Registration = add(T::class, interceptor, priority)

    /**
     * Returns the resolved [Snapshot] applicable to [clazz]. Results are
     * cached and only recomputed when the global [version] has advanced since
     * the last snapshot.
     *
     * @param clazz the target data type.
     * @return a (possibly shared) immutable snapshot.
     */
    private fun resolve(clazz: KClass<*>): Snapshot {
        val currentVersion = version.get()

        // Fast path: cache hit with current version — no allocation at all.
        val cached = resolvedCache[clazz]
        if (cached != null && cached.snapshotVersion == currentVersion) {
            return cached
        }

        // Slow path: recompute. Multiple threads may race here; that's fine —
        // they'll produce equivalent results and the last write wins harmlessly
        // (a snapshot tagged with an outdated version is simply recomputed).
        val targetJavaClass = clazz.javaObjectType

        // Single-pass collection: iterate map entries, accumulate matching
        // entries into a mutable list, then sort once.
        val result = ArrayList<Entry<*>>()
        for ((key, set) in map) {
            if (key.javaObjectType.isAssignableFrom(targetJavaClass)) {
                // ConcurrentSkipListSet iteration is weakly-consistent and
                // already in comparator order, but entries from *different*
                // sets must be merged, so we still need a final sort.
                result.addAll(set)
            }
        }

        val snapshot = if (result.isEmpty()) {
            Snapshot(currentVersion, entries = null, targetJavaClass)
        } else {
            result.sortWith(entryComparator)
            Snapshot(currentVersion, entries = result.toTypedArray(), targetJavaClass)
        }

        resolvedCache[clazz] = snapshot
        return snapshot
    }

    /**
     * Runs all registered interceptors for [clazz] in priority order
     * (lowest first), threading [data] through the chain, and returns the
     * chain's result — **regardless** of whether the end of the chain was
     * reached. If an interceptor blocked the value (did not call `proceed`),
     * the value it returned is returned here; use [applyInterceptorsOrNull] to
     * detect that.
     *
     * The snapshot is captured once at the start of the call: registrations
     * or removals that happen *during* execution only affect subsequent calls.
     *
     * If no interceptors are registered for the given type, [data] is returned
     * unchanged (without allocating).
     *
     * @param T     the data type.
     * @param clazz the [KClass] token used to look up interceptors.
     * @param data  the initial value entering the chain.
     * @return the final value after all interceptors have executed.
     * @throws ClassCastException if an interceptor returns a value that is not
     *         assignable to [T].
     */
    override suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T {
        val snapshot = resolve(clazz)
        val entries = snapshot.entries ?: return data
        return clazz.javaObjectType.cast(Execution(entries).run(snapshot.targetClass, data))
    }

    /**
     * Runs all registered interceptors for [clazz] in priority order
     * (lowest first) and returns the value to dispatch, or `null` if the value
     * was **blocked** — i.e. no `proceed` call of this execution reached the
     * end of the chain. See [InterceptorPipeline.applyInterceptorsOrNull].
     *
     * When the value is dispatched, the result is exactly what
     * [applyInterceptors] would have returned. The outcome is tracked in a
     * per-call record, so concurrent calls never affect each other's outcome.
     *
     * If no interceptors are registered for the given type, [data] is returned
     * unchanged (without allocating).
     *
     * @param T     the data type.
     * @param clazz the [KClass] token used to look up interceptors.
     * @param data  the initial value entering the chain.
     * @return the value to dispatch, or `null` if an interceptor blocked it.
     * @throws ClassCastException if an interceptor returns a value that is not
     *         assignable to [T].
     * @since 1.1.0
     */
    override suspend fun <T : Any> applyInterceptorsOrNull(clazz: KClass<out T>, data: T): T? {
        val snapshot = resolve(clazz)
        val entries = snapshot.entries ?: return data
        val execution = Execution(entries)
        val result = execution.run(snapshot.targetClass, data)
        return if (execution.terminalReached) clazz.javaObjectType.cast(result) else null
    }
}
