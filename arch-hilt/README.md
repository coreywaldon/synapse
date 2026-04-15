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

If two modules register a provider for the same `DataImpulse` type, the app will fail at startup with a clear error message.

---

## 📦 Installation

Add `arch-hilt` to your **app module** (the module that sets up the Hilt component):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.synapselib:arch-hilt:1.0.13")
}
```

Each **feature module** only needs `arch` and KSP:

```kotlin
// feature-xyz/build.gradle.kts
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.synapselib:arch:1.0.13")
    ksp("com.synapselib:arch:1.0.13")
}

ksp {
    arg("synapse.moduleName", "FeatureXyz") // unique name per module
}
```

That's it. No manual registry wiring — providers are discovered automatically across all modules.

---

## How It Works

### 1. KSP generates per-module contributions

Each module with `@SynapseProvider` classes generates a Hilt module like:

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

```kotlin
class DefaultSwitchBoard @Inject constructor(
    @SwitchBoardScope scope: CoroutineScope,
    providerRegistry: ProviderRegistry, // <-- the merged registry
    // ...
) : SwitchBoard
```

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

To replace a specific module's providers in tests:

```kotlin
@UninstallModules(SynapseProviderModule_FeatureAuth::class)
@HiltAndroidTest
class AuthTest {
    @BindValue
    val registry = ProviderRegistry.Builder()
        .register<AuthToken, FetchToken> { FakeTokenProvider() }
        .build()
}
```

---

## Koin

Koin does not have this problem — Koin modules are plain values composed manually in `startKoin`. This artifact is only needed for Hilt projects.

---

## 📄 License

Synapse Arch Hilt is available under the Mozilla Public License 2.0. See the [LICENSE](../LICENSE) file for more info.
