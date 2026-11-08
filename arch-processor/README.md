# Synapse Arch Processor

![Maven Central](https://img.shields.io/maven-central/v/com.synapselib/arch-processor)

The [KSP](https://kotlinlang.org/docs/ksp-overview.html) processor for
[Synapse Arch](../arch/README.md)'s `@SynapseProvider` annotation. For every Gradle module it
generates a DI module that registers all of that module's providers in a `ProviderRegistry`:

- **Hilt** (when `dagger.hilt.InstallIn` is on the classpath): `SynapseProviderModule_{ModuleName}`, which
  contributes a `ProviderRegistryContribution` via `@IntoSet`. Merge them in the app module with
  [`arch-hilt`](../arch-hilt/README.md).
- **Koin** (otherwise, when Koin is on the classpath): a `synapseProviderModule_{ModuleName}` Koin module.

The processor is a compile-only artifact: it is only ever added to the `ksp` configuration, so
nothing from it (or from KSP) reaches your app's runtime classpath.

## 📦 Installation

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
    arg("synapse.moduleName", "FeatureXyz") // unique per module; defaults to "App"
}
```

The `synapse.moduleName` value is turned into a PascalCase suffix (`feature-xyz` → `FeatureXyz`),
so each module's generated class has a unique name.

> **Migrating from 1.0.x:** the processor used to ship inside the `arch` artifact. Replace
> `ksp("com.synapselib:arch:<version>")` with `ksp("com.synapselib:arch-processor:<version>")`.
> Keep `implementation("com.synapselib:arch:<version>")` as it is.

## Compile-time checks

The processor fails the build when a `@SynapseProvider` class:

- is not a concrete class (interface, object, enum or `abstract` class),
- does not extend `com.synapselib.arch.base.provider.Provider<I, Need>`,
- has unresolvable or star-projected type arguments,
- handles the same `DataImpulse` type as another provider in the module,

and it reports an error when the `DataImpulse` is not a `data class`. It warns when a provider has
no `@Inject` constructor.

## 📄 License

Synapse Arch Processor is available under the Mozilla Public License 2.0. See the [LICENSE](../LICENSE) file for more info.
