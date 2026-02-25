package com.synapselib.arch.base

/**
 * Base type for data requests that flow through the [SwitchBoard]'s
 * provider system.
 *
 * A [DataImpulse] is both the **request definition** and the **type-safe
 * link** to its result. The generic parameter [Need] declares what type
 * of data the caller expects back, enforced at compile time.
 *
 * Subclass as a `data class` so that structural equality drives
 * deduplication — two impulses with identical parameters share the
 * same provider job:
 *
 * ```kotlin
 * data class FetchUserProfile(val userId: Int) : DataImpulse<UserProfile>()
 * data class SearchProducts(val query: String, val page: Int) : DataImpulse<ProductPage>()
 * ```
 *
 * ## Compile-Time Safety
 *
 * Because [Need] is declared on the impulse itself, the framework can
 * enforce that:
 * - `Request(FetchUserProfile(42))` returns `StateFlow<DataState<UserProfile>>`
 *   (inferred, not manually specified).
 * - The [com.synapselib.arch.base.provider.SynapseProvider] handling `FetchUserProfile` must produce `UserProfile`.
 * - KSP can validate at compile time that every concrete [DataImpulse]
 *   subclass has exactly one registered [com.synapselib.arch.base.provider.SynapseProvider].
 *
 * ## Deduplication
 *
 * The provider system uses [equals]/[hashCode] to deduplicate active
 * requests. Two `FetchUserProfile(42)` impulses share the same in-flight
 * job and the same `DataState` flow. Two `FetchUserProfile(42)` and
 * `FetchUserProfile(99)` run concurrently as separate jobs.
 *
 * **Always implement as a `data class`** to get correct structural equality.
 *
 * @param Need the type of data this impulse requests. Must be a non-nullable
 *             [Any] subtype.
 */
abstract class DataImpulse<out Need : Any>