package com.synapselib.arch.base

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KClass

/**
 * A composable interceptor that can observe, transform, or short-circuit data
 * flowing through a [Chain].
 *
 * Interceptors form a pipeline (similar to OkHttp interceptors or middleware stacks):
 * each interceptor receives the current data and a [Chain] handle that delegates
 * to the next interceptor in line. An interceptor may:
 *
 * - **Pass through**: call `chain.proceed(data)` unchanged (observation / logging).
 * - **Transform**: modify data before or after calling `chain.proceed`.
 * - **Short-circuit**: return a value *without* calling `chain.proceed`, skipping
 *   all downstream interceptors.
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
     *              continue the chain, or omit the call to short-circuit.
     * @return the (possibly transformed) result to be returned to the caller or
     *         the upstream interceptor.
     */
    suspend fun intercept(data: T, chain: Chain<T>): T

    /**
     * Represents the downstream portion of an interceptor pipeline.
     *
     * Calling [proceed] hands data to the next interceptor in the chain. If
     * there are no more interceptors, the terminal handler simply returns data
     * as-is (identity).
     *
     * @param T the type of data flowing through the chain.
     */
    fun interface Chain<T> {

        /**
         * Forwards [data] to the next interceptor in the pipeline and returns the
         * result produced by the remainder of the chain.
         *
         * @param data the value to pass downstream.
         * @return the result after all downstream interceptors have executed.
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
         * - Call `proceed` multiple times (retry).
         * - Skip `proceed` entirely (short-circuit).
         *
         * ```kotlin
         * registry.add(Interceptor.full<Request> { request, proceed ->
         *     try {
         *         proceed(request)
         *     } catch (e: Exception) {
         *         proceed(request.copy(retryCount = request.retryCount + 1))
         *     }
         * })
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
 * Implementations are expected to be safe for concurrent use.
 *
 * @see InterceptorRegistry for the default implementation.
 */
interface InterceptorPipeline {

    /**
     * Applies all interceptors registered for [clazz] to [data], returning the
     * final result after the full chain has executed.
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
 * Thread-safe, priority-ordered registry of [Interceptor]s keyed by data type.
 *
 * Interceptors are grouped by their target [KClass] and executed in
 * **ascending priority** order (lowest value runs first). Interceptors with
 * equal priority are executed in **FIFO insertion order**. An interceptor
 * registered for a supertype (e.g. `Any::class` or a sealed parent) also
 * applies to all of its subtypes, merged into the same priority order.
 *
 * ## Performance
 *
 * The resolved pipeline for each target type is cached together with its
 * pre-built, immutable chain links, so steady-state [applyInterceptors] calls
 * perform one map lookup and allocate no chain objects. The cache is
 * invalidated by a version stamp that only advances on effective
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
         * performing a checked cast on the input. [next] is the pre-built
         * link for the remainder of the pipeline; it checked-casts the
         * downstream result back to [T] itself.
         *
         * @throws ClassCastException if [data] is not assignable to [T].
         */
        suspend fun interceptUntyped(data: Any, next: Link<*>): Any {
            @Suppress("UNCHECKED_CAST") // `next` was built by linkTo(), so its result class is javaClass
            val typedNext = next as Link<T>
            return interceptor.intercept(javaClass.cast(data), typedNext)
        }

        /** Creates the link handed to this entry: it resumes the pipeline at [nextIndex]. */
        fun linkTo(entries: Array<Entry<*>>, nextIndex: Int, next: Link<*>?): Link<T> =
            Link(entries, nextIndex, javaClass, next)
    }

    /**
     * Index-based chain link over an immutable snapshot array, in the style of
     * OkHttp's `RealInterceptorChain`.
     *
     * A link is fully described by the snapshot [entries], the [index] of the
     * entry it runs next, and the [resultClass] its holder expects back. Since
     * that is all immutable, the `entries.size + 1` links of a snapshot are
     * **pre-built once** when the snapshot is resolved: executing the pipeline
     * allocates no chain objects at all, and calling [proceed] several times
     * (retry) or not at all (short-circuit) is naturally supported.
     *
     * @param T the data type expected by the interceptor that holds this link.
     * @property next the link for `index + 1`, or `null` for the terminal link.
     */
    private class Link<T : Any>(
        private val entries: Array<Entry<*>>,
        private val index: Int,
        private val resultClass: Class<T>,
        private val next: Link<*>?,
    ) : Interceptor.Chain<T> {

        override suspend fun proceed(data: T): T {
            // Terminal link: identity.
            if (index == entries.size) return resultClass.cast(data)
            return resultClass.cast(entries[index].interceptUntyped(data, next!!))
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
     * the head of the pre-built chain over the flattened, sorted entries that
     * matched the key type via [Class.isAssignableFrom]. On a cache hit with a
     * matching version the hot path is a single map lookup + long comparison,
     * with no allocation.
     *
     * Entries become stale (but never incorrect) when [version] advances;
     * stale entries are lazily recomputed on the next access.
     */
    private val resolvedCache = ConcurrentHashMap<KClass<*>, Snapshot>()

    /**
     * An immutable, resolved pipeline tagged with the [version] at which it
     * was computed.
     *
     * @property snapshotVersion the registry version this snapshot reflects.
     * @property head            the first chain link, or `null` when no
     *                           interceptor applies (identity pipeline).
     */
    private class Snapshot(
        val snapshotVersion: Long,
        val head: Link<*>?,
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
            Snapshot(currentVersion, head = null)
        } else {
            result.sortWith(entryComparator)
            Snapshot(currentVersion, head = buildChain(result.toTypedArray(), targetJavaClass))
        }

        resolvedCache[clazz] = snapshot
        return snapshot
    }

    /**
     * Pre-builds the `entries.size + 1` immutable links over [entries], from
     * the terminal (identity) link back to the head. Link `i` is held by
     * `entries[i - 1]` (or by the caller, for the head) and casts its result to
     * that holder's type.
     *
     * @return the head link.
     */
    private fun buildChain(entries: Array<Entry<*>>, targetJavaClass: Class<*>): Link<*> {
        var next: Link<*>? = null
        for (i in entries.size downTo 1) {
            next = entries[i - 1].linkTo(entries, i, next)
        }
        return Link(entries, 0, targetJavaClass, next)
    }

    /**
     * Runs all registered interceptors for [clazz] in priority order
     * (lowest first), threading [data] through the chain.
     *
     * The chain links are pre-built per snapshot, so a call allocates no chain
     * objects. The snapshot is captured once at the start of the call:
     * registrations or removals that happen *during* execution only affect
     * subsequent calls.
     *
     * If no interceptors are registered for the given type, [data] is returned
     * unchanged.
     *
     * @param T     the data type.
     * @param clazz the [KClass] token used to look up interceptors.
     * @param data  the initial value entering the chain.
     * @return the final value after all interceptors have executed.
     * @throws ClassCastException if an interceptor returns a value that is not
     *         assignable to [T].
     */
    override suspend fun <T : Any> applyInterceptors(clazz: KClass<out T>, data: T): T {
        val head = resolve(clazz).head ?: return data
        @Suppress("UNCHECKED_CAST") // the head link's result class is clazz.javaObjectType
        val typedHead = head as Link<Any>
        return clazz.javaObjectType.cast(typedHead.proceed(data))
    }
}
