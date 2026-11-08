package com.synapselib.arch.base.provider

import com.synapselib.arch.base.DataImpulse
import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KClass

/**
 * An immutable-at-runtime registry that maps [com.synapselib.arch.base.DataImpulse] types to their
 * [ProviderFactory] instances.
 *
 * ## Role
 *
 * This is the **only** place where impulse → provider relationships are
 * declared. The [com.synapselib.arch.base.SwitchBoard] and [ProviderManager] consume this registry
 * as a dependency — they never accept ad-hoc registrations.
 *
 * ## Construction
 *
 * Use the [Builder] to assemble a registry, then call [build] to produce
 * an immutable [ProviderRegistry]. In production, KSP generates a Hilt/Koin
 * module that builds and provides this as a singleton. In tests, construct
 * one directly with only the providers under test:
 *
 * ```kotlin
 * // Production — KSP-generated
 * @Module
 * @InstallIn(SingletonComponent::class)
 * object GeneratedProviderModule {
 *     @Provides @Singleton
 *     fun provideRegistry(api: UserApi): ProviderRegistry =
 *         ProviderRegistry.Builder()
 *             .register<UserProfile, FetchUserProfile> {
 *                 FetchUserProfileProvider(api)
 *             }
 *             .build()
 * }
 *
 * // Test — only what you need
 * val registry = ProviderRegistry.Builder()
 *     .register<UserProfile, FetchUserProfile> {
 *         FakeUserProfileProvider()
 *     }
 *     .build()
 *
 * val switchboard = DefaultSwitchBoard(scope, registry)
 * ```
 *
 * ## Composition
 *
 * Registries compose with [plus] / [of] (or [Builder.mergeFrom]). Duplicate
 * impulse types are rejected; the error names both conflicting registrations
 * — including the [name] of the registry each came from, when one was given
 * to its [Builder]:
 *
 * ```kotlin
 * val registry = ProviderRegistry.of(userRegistry, cartRegistry)
 * val alsoRegistry = userRegistry + cartRegistry
 * ```
 *
 * ## Thread Safety
 *
 * Once [build] is called, the resulting [ProviderRegistry] is effectively
 * immutable. All reads are lock-free map lookups.
 *
 * @property name optional human-readable origin of this registry (e.g. the
 *                Gradle module that contributed it). Used only in
 *                diagnostics.
 */
class ProviderRegistry private constructor(
    private val factories: Map<KClass<*>, RegisteredFactory<*, *>>,
    val name: String?,
) {

    /**
     * Type-safe wrapper pairing [Class] tokens with a [ProviderFactory].
     *
     * Encapsulates all type-erased operations internally so that callers
     * never interact with erased generics directly.
     *
     * @param I              the [com.synapselib.arch.base.DataImpulse] subclass.
     * @param Need           the result type the provider produces.
     * @property impulseClass the [Class] token for [I].
     * @property needClass    the [Class] token for [Need].
     * @property factory      the underlying [ProviderFactory].
     * @property origin       the [name] of the registry this factory was
     *                        first registered in, if any. Preserved across
     *                        merges for duplicate diagnostics.
     */
    internal class RegisteredFactory<I : DataImpulse<Need>, Need : Any>(
        val impulseClass: Class<I>,
        val needClass: Class<Need>,
        val factory: ProviderFactory<I, Need>,
        val origin: String?,
    ) {
        /**
         * Instantiates the [Provider] and invokes [Provider.produce] with
         * a checked-cast impulse, returning the flow widened to `Flow<Any>`.
         *
         * All type-erased operations stay inside this method where `I` and
         * `Need` are known — callers receive `Flow<Any>` and cast each
         * element through their own `needClass` on collection.
         *
         * @param impulse       the raw impulse (cast to [I] via [impulseClass]).
         * @param providerScope the [ProviderScope] passed to [Provider.produce].
         * @return the provider's output flow, widened to `Flow<Any>`.
         * @throws ClassCastException if [impulse] is not assignable to [I].
         */
        fun produceErased(
            impulse: Any,
            providerScope: ProviderScope,
        ): Flow<Any> {
            val typedImpulse: I = impulseClass.cast(impulse)
            val provider: Provider<I, Need> = factory.create()
            // Flow<Need> widens to Flow<Any> — safe covariant collection.
            return with(provider) { providerScope.produce(typedImpulse) }
        }

        /** Human-readable description for diagnostics: factory class plus origin. */
        fun describe(): String {
            val where = origin?.let { " from registry '$it'" } ?: " from an unnamed registry"
            return "${factory.javaClass.name}$where"
        }
    }

    /**
     * Looks up the [RegisteredFactory] for the given [impulseType].
     *
     * @param impulseType the [KClass] token for the [DataImpulse] subclass.
     * @return the registered factory, or `null` if none is registered.
     */
    internal fun resolve(impulseType: KClass<*>): RegisteredFactory<*, *>? =
        factories[impulseType]

    /**
     * Returns `true` if a provider is registered for the given [impulseType].
     *
     * Useful for diagnostics and test assertions:
     * ```kotlin
     * assertTrue(registry.hasProvider(FetchUserProfile::class))
     * ```
     */
    fun hasProvider(impulseType: KClass<*>): Boolean =
        factories.containsKey(impulseType)

    /** Returns a snapshot of the set of all registered [DataImpulse] types. */
    fun registeredImpulseTypes(): Set<KClass<*>> =
        factories.keys.toSet()

    /** Returns the total number of registered providers. */
    val size: Int get() = factories.size

    /**
     * Returns a new registry containing the registrations of both `this` and
     * [other].
     *
     * @throws IllegalStateException if both registries register the same
     *         impulse type. The message names both registrations.
     */
    operator fun plus(other: ProviderRegistry): ProviderRegistry =
        Builder().mergeFrom(this).mergeFrom(other).build()

    override fun toString(): String =
        "ProviderRegistry(${name?.let { "name=$it, " } ?: ""}" +
                "impulses=${factories.keys.map { it.simpleName }})"

    // ── Builder ─────────────────────────────────────────────────────────

    /**
     * Mutable builder for assembling a [ProviderRegistry].
     *
     * Designed for fluent construction:
     * ```kotlin
     * val registry = ProviderRegistry.Builder()
     *     .register<UserProfile, FetchUserProfile> {
     *         FetchUserProfileProvider(api)
     *     }
     *     .register<ProductPage, SearchProducts> {
     *         SearchProductsProvider(api)
     *     }
     *     .build()
     * ```
     *
     * @param name optional origin label (e.g. the contributing Gradle module)
     *             stamped on the built registry and on every factory
     *             registered directly through this builder. Appears in
     *             duplicate-registration errors.
     */
    class Builder(private val name: String? = null) {

        private val factories = mutableMapOf<KClass<*>, RegisteredFactory<*, *>>()

        /**
         * Registers a [ProviderFactory] for a specific [DataImpulse] type.
         *
         * @param Need        the result type.
         * @param I           the [DataImpulse] subclass.
         * @param impulseType the [KClass] token for [I].
         * @param needClass   the [Class] token for [Need].
         * @param factory     the factory that creates [Provider] instances.
         * @return this builder for chaining.
         * @throws IllegalStateException if a factory is already registered
         *         for [impulseType].
         */
        fun <Need : Any, I : DataImpulse<Need>> register(
            impulseType: KClass<I>,
            needClass: Class<Need>,
            factory: ProviderFactory<I, Need>,
        ): Builder {
            val incoming = RegisteredFactory(
                impulseClass = impulseType.java,
                needClass = needClass,
                factory = factory,
                origin = name,
            )
            put(impulseType, incoming, context = "registration")
            return this
        }

        /**
         * Reified convenience overload that infers [KClass] and [Class]
         * tokens from the type parameters.
         *
         * ```kotlin
         * builder.register<UserProfile, FetchUserProfile> {
         *     FetchUserProfileProvider(api)
         * }
         * ```
         */
        inline fun <reified Need : Any, reified I : DataImpulse<Need>> register(
            factory: ProviderFactory<I, Need>,
        ): Builder = register(I::class, Need::class.java, factory)

        /**
         * Reified convenience with a lambda factory.
         *
         * ```kotlin
         * builder.register<UserProfile, FetchUserProfile> {
         *     FetchUserProfileProvider(api)
         * }
         * ```
         */
        inline fun <reified Need : Any, reified I : DataImpulse<Need>> register(
            crossinline create: () -> Provider<I, Need>,
        ): Builder = register(I::class, Need::class.java) { create() }

        /**
         * Merges all registrations from [other] into this builder.
         *
         * Useful for composing registries from multiple modules:
         * ```kotlin
         * val registry = ProviderRegistry.Builder()
         *     .mergeFrom(featureARegistrations)
         *     .mergeFrom(featureBRegistrations)
         *     .build()
         * ```
         *
         * Merged factories keep the origin of the registry they were first
         * registered in.
         *
         * @throws IllegalStateException if any impulse type in [other]
         *         is already registered in this builder. The message names
         *         both conflicting registrations.
         */
        fun mergeFrom(other: ProviderRegistry): Builder {
            for ((key, factory) in other.factories) {
                put(key, factory, context = "merge")
            }
            return this
        }

        private fun put(impulseType: KClass<*>, incoming: RegisteredFactory<*, *>, context: String) {
            val existing = factories[impulseType]
            check(existing == null) {
                "Duplicate provider $context for impulse type ${impulseType.qualifiedName ?: impulseType}: " +
                        "already registered by ${existing!!.describe()}; conflicting registration by " +
                        "${incoming.describe()}. Each DataImpulse type must have exactly one provider."
            }
            factories[impulseType] = incoming
        }

        /**
         * Builds an immutable [ProviderRegistry] from the accumulated
         * registrations.
         *
         * @return the finalized registry.
         */
        fun build(): ProviderRegistry = ProviderRegistry(factories.toMap(), name)
    }

    companion object {
        /** An empty registry with no providers. Useful as a default in tests. */
        val EMPTY: ProviderRegistry = ProviderRegistry(emptyMap(), name = null)

        /**
         * Composes [registries] into one.
         *
         * ```kotlin
         * val registry = ProviderRegistry.of(userRegistry, cartRegistry)
         * ```
         *
         * @throws IllegalStateException if two registries register the same
         *         impulse type. The message names both registrations.
         */
        fun of(vararg registries: ProviderRegistry): ProviderRegistry {
            val builder = Builder()
            registries.forEach { builder.mergeFrom(it) }
            return builder.build()
        }
    }
}
