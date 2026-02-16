package com.synapselib.arch.base.provider

import com.synapselib.arch.base.DataImpulse
import kotlinx.coroutines.flow.Flow
import kotlin.collections.iterator
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
 *             .register<UserProfile, FetchUserProfile>(UserProfile::class.java) {
 *                 FetchUserProfileProvider(api)
 *             }
 *             .build()
 * }
 *
 * // Test — only what you need
 * val registry = ProviderRegistry.Builder()
 *     .register<UserProfile, FetchUserProfile>(UserProfile::class.java) {
 *         FakeUserProfileProvider()
 *     }
 *     .build()
 *
 * val switchboard = DefaultSwitchBoard(scope, registry)
 * ```
 *
 * ## Thread Safety
 *
 * Once [build] is called, the resulting [ProviderRegistry] is effectively
 * immutable. All reads are lock-free concurrent map lookups.
 */
class ProviderRegistry private constructor(
    private val factories: Map<KClass<*>, RegisteredFactory<*, *>>,
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
     */
    internal class RegisteredFactory<I : DataImpulse<Need>, Need : Any>(
        val impulseClass: Class<I>,
        val needClass: Class<Need>,
        val factory: ProviderFactory<I, Need>,
    ) {
        /** Casts an untyped impulse to [I] via [Class.cast]. */
        fun castImpulse(impulse: Any): I = impulseClass.cast(impulse)

        /** Creates a new [Provider] instance. */
        fun createProvider(): Provider<I, Need> = factory.create()

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

    /** Returns the set of all registered [DataImpulse] types. */
    fun registeredImpulseTypes(): Set<KClass<*>> =
        factories.keys.toSet()

    /** Returns the total number of registered providers. */
    val size: Int get() = factories.size

    // ── Builder ─────────────────────────────────────────────────────────

    /**
     * Mutable builder for assembling a [ProviderRegistry].
     *
     * Designed for fluent construction:
     * ```kotlin
     * val registry = ProviderRegistry.Builder()
     *     .register<UserProfile, FetchUserProfile>(UserProfile::class.java) {
     *         FetchUserProfileProvider(api)
     *     }
     *     .register<ProductPage, SearchProducts>(ProductPage::class.java) {
     *         SearchProductsProvider(api)
     *     }
     *     .build()
     * ```
     */
    class Builder {

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
            check(!factories.containsKey(impulseType)) {
                "Duplicate provider registration for impulse type: " +
                        "${impulseType.simpleName}. Each DataImpulse type must " +
                        "have exactly one provider."
            }
            factories[impulseType] = RegisteredFactory(
                impulseClass = impulseType.java,
                needClass = needClass,
                factory = factory,
            )
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
         * @throws IllegalStateException if any impulse type in [other]
         *         is already registered in this builder.
         */
        fun mergeFrom(other: ProviderRegistry): Builder {
            for ((key, factory) in other.factories) {
                check(!factories.containsKey(key)) {
                    "Duplicate provider registration during merge for impulse " +
                            "type: ${key.simpleName}"
                }
                factories[key] = factory
            }
            return this
        }

        /**
         * Builds an immutable [ProviderRegistry] from the accumulated
         * registrations.
         *
         * @return the finalized registry.
         */
        fun build(): ProviderRegistry = ProviderRegistry(factories.toMap())
    }

    companion object {
        /** An empty registry with no providers. Useful as a default in tests. */
        val EMPTY = ProviderRegistry(emptyMap())
    }
}