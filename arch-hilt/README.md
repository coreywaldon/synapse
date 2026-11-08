# Synapse Arch Hilt

![Maven Central](https://img.shields.io/maven-central/v/com.synapselib/arch-hilt)
![Kotlin](https://img.shields.io/badge/kotlin-2.2.21-blue.svg?logo=kotlin)
![License](https://img.shields.io/badge/License-MPL%202.0-brightgreen.svg)
[![Build Status](https://github.com/coreywaldon/synapse/actions/workflows/publish.yml/badge.svg)](https://github.com/coreywaldon/synapse/actions/workflows/publish.yml)

**Hilt integration for multi-module Synapse projects.**

This artifact provides `SynapseProviderAggregatorModule`, a Hilt module that automatically collects `@SynapseProvider` contributions from all Gradle modules and merges them into a single `ProviderRegistry` singleton.

---

## The Problem

Without this artifact, each Gradle module's KSP-generated code would attempt to provide its own `ProviderRegistry` into Hilt's `SingletonComponent`. With multiple modules, Hilt fails at compile time with a duplicate binding error.

## The Solution

Each module's generated code contributes a `ProviderRegistryContribution` via `@IntoSet` multibinding. The `SynapseProviderAggregatorModule` collects the full `Set<ProviderRegistryContribution>` and merges them into one `ProviderRegistry`.

If two modules register a provider for the same `DataImpulse` type, the app fails at startup with an `IllegalStateException` that names both conflicting provider factories.

---

## 📦 Installation

Add `arch-hilt` to your **app module** (the module that sets up the Hilt component):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.synapselib:arch-hilt:1.1.0")
}
```

Each **feature module** only needs `arch` plus the KSP processor (`arch-processor`):

```kotlin
// feature-xyz/build.gradle.kts
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.synapselib:arch:1.1.0")
    ksp("com.synapselib:arch-processor:1.1.0")
}

ksp {
    arg("synapse.moduleName", "FeatureXyz") // unique name per module
}
```

That's it. No manual registry wiring — providers are discovered automatically across all modules.

> **Migrating from 1.0.x:** the processor is no longer inside the `arch` artifact. Replace
> `ksp("com.synapselib:arch:<version>")` with `ksp("com.synapselib:arch-processor:<version>")`
> in every feature module.

---

## How It Works

### 1. KSP generates per-module contributions

When Hilt is on the module's compile classpath, [`arch-processor`](../arch-processor/README.md) generates a Hilt module for each module with `@SynapseProvider` classes, like:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SynapseProviderModule_FeatureXyz {
    @Provides @IntoSet
    fun provideContribution(
        myProvider: javax.inject.Provider<MyProvider>,
    ): ProviderRegistryContribution {
        val registry = ProviderRegistry.Builder()
            .register(
                impulseType = MyImpulse::class,
                needClass = MyResult::class.java,
                factory = ProviderFactory { myProvider.get() },
            )
            .build()
        return ProviderRegistryContribution(registry)
    }
}
```

### 2. The aggregator merges them

`SynapseProviderAggregatorModule` (provided by this artifact) collects all contributions:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SynapseProviderAggregatorModule {
    @Provides @Singleton
    fun provideRegistry(
        contributions: Set<@JvmSuppressWildcards ProviderRegistryContribution>,
    ): ProviderRegistry {
        val builder = ProviderRegistry.Builder()
        for (contribution in contributions) {
            builder.mergeFrom(contribution.registry)
        }
        return builder.build()
    }
}
```

### 3. The merged registry is injected normally

Inject `ProviderRegistry` wherever you build the board:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object SwitchBoardModule {
    @Provides @Singleton
    fun provideSwitchBoard(
        registry: ProviderRegistry, // <-- the merged registry
    ): DefaultSwitchBoard = DefaultSwitchBoard(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        registry,
        SwitchBoardConfig { maxConcurrentJobs = 8 },
    )
}
```

`DefaultSwitchBoard` also has an `@Inject` constructor that takes the merged registry. To use it, bind `@SwitchBoardScope CoroutineScope`, `@SwitchBoardWorkerContext CoroutineContext`, `@SwitchBoardStopTimeout Long` and `@SwitchBoardReplayExpiration Long`. Dagger ignores Kotlin default arguments, so all four need a binding.

---

## Multi-Module Example

```
feature-auth/
├── providers/FetchTokenProvider.kt       // @SynapseProvider
└── build.gradle.kts                      // ksp arg: "FeatureAuth"

feature-checkout/
├── providers/FetchAddressesProvider.kt   // @SynapseProvider
└── build.gradle.kts                      // ksp arg: "FeatureCheckout"

app/
├── build.gradle.kts                      // depends on arch-hilt + both features
└── MainApplication.kt                    // @HiltAndroidApp — no manual wiring
```

Both `FetchTokenProvider` and `FetchAddressesProvider` are automatically available in the merged `ProviderRegistry`.

---

## Testing

To replace a specific module's providers in tests, uninstall its generated module and contribute fakes into the same set:

```kotlin
@UninstallModules(SynapseProviderModule_FeatureAuth::class)
@HiltAndroidTest
class AuthTest {
    @BindValueIntoSet
    @JvmField
    val fakeAuthProviders = ProviderRegistryContribution(
        ProviderRegistry.Builder(name = "fake-auth")
            .register<AuthToken, FetchToken> { FakeTokenProvider() }
            .build()
    )
}
```

To replace the whole registry instead, uninstall `SynapseProviderAggregatorModule::class` as well and `@BindValue` a `ProviderRegistry`. Otherwise Hilt reports a duplicate `ProviderRegistry` binding.

---

## Koin

This artifact is only needed for Hilt projects. Without Hilt, `arch-processor` generates a Koin module per Gradle module (`synapseProviderModule_{ModuleName}`), and you load it in `startKoin`. Note that each generated Koin module declares its own `single<ProviderRegistry>`. There is no Koin aggregator yet, so loading several of them gives you conflicting `ProviderRegistry` definitions, not a merged registry. In a multi-module Koin app, declare the registry yourself, e.g. one `ProviderRegistry.Builder(name = ...)` per feature combined with `ProviderRegistry.of(...)`.

---

## 📄 License

Synapse Arch Hilt is available under the Mozilla Public License 2.0. See the [LICENSE](../LICENSE) file for more info.
