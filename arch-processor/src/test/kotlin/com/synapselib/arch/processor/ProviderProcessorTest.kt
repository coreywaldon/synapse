package com.synapselib.arch.processor

import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.synapselib.arch.base.provider.ProviderRegistryContribution
import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.ServiceLoader

/**
 * End-to-end tests for [ProviderProcessor]: each test runs the processor in-process
 * through KSP2 (kotlin-compile-testing), then compiles the generated code against the
 * real `arch` runtime and Hilt annotations from the test classpath.
 */
@OptIn(ExperimentalCompilerApi::class)
class ProviderProcessorTest {

    // ── Fixtures ────────────────────────────────────────────────────────

    private val model = SourceFile.kotlin(
        "Model.kt",
        """
        package com.example.feature

        import com.synapselib.arch.base.DataImpulse

        data class User(val name: String)
        data class FetchUser(val id: String) : DataImpulse<User>()
        data class FetchFriends(val id: String) : DataImpulse<List<User>>()
        """.trimIndent(),
    )

    private val providers = SourceFile.kotlin(
        "Providers.kt",
        """
        package com.example.feature

        import com.synapselib.arch.base.provider.Provider
        import com.synapselib.arch.base.provider.ProviderScope
        import com.synapselib.arch.base.provider.SynapseProvider
        import javax.inject.Inject
        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.flowOf

        @SynapseProvider
        class FetchUserProvider @Inject constructor() : Provider<FetchUser, User>() {
            override fun ProviderScope.produce(impulse: FetchUser): Flow<User> = flowOf(User(impulse.id))
        }

        @SynapseProvider
        class FetchFriendsProvider @Inject constructor() : Provider<FetchFriends, List<User>>() {
            override fun ProviderScope.produce(impulse: FetchFriends): Flow<List<User>> = flowOf(emptyList())
        }
        """.trimIndent(),
    )

    private fun compile(
        vararg sources: SourceFile,
        moduleName: String? = "feature-auth",
    ): Pair<KotlinCompilation, JvmCompilationResult> {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            messageOutputStream = System.out
            configureKsp {
                symbolProcessorProviders += ProviderProcessorProvider()
                if (moduleName != null) processorOptions[ProviderProcessorProvider.MODULE_NAME_OPTION] = moduleName
            }
        }
        return compilation to compilation.compile()
    }

    private fun KotlinCompilation.generatedFiles(): List<File> =
        kspSourcesDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    // ── Hilt generation ─────────────────────────────────────────────────

    @Test
    fun `generates a Hilt module that compiles and registers every provider`() {
        val (compilation, result) = compile(model, providers)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

        val generated = compilation.generatedFiles()
        assertEquals(listOf("SynapseProviderModule_FeatureAuth.kt"), generated.map { it.name })
        val source = generated.single().readText()
        assertTrue("@InstallIn(SingletonComponent::class)" in source, source)
        assertTrue("@IntoSet" in source, source)

        // Invoke the generated @Provides function and check the contribution's registry.
        val loader = result.classLoader
        val module = loader.loadClass("com.synapselib.arch.generated.SynapseProviderModule_FeatureAuth")
        val instance = module.getField("INSTANCE").get(null)
        val provide = module.methods.single { it.name == "provideContribution" }
        val userProvider = loader.loadClass("com.example.feature.FetchUserProvider")
        val friendsProvider = loader.loadClass("com.example.feature.FetchFriendsProvider")
        val args = provide.parameterTypes.indices.map { index ->
            // Parameters follow declaration order: FetchUserProvider, FetchFriendsProvider.
            val target = if (index == 0) userProvider else friendsProvider
            javax.inject.Provider { target.getDeclaredConstructor().newInstance() }
        }
        val contribution = provide.invoke(instance, *args.toTypedArray()) as ProviderRegistryContribution

        assertEquals(
            setOf("com.example.feature.FetchUser", "com.example.feature.FetchFriends"),
            contribution.registry.registeredImpulseTypes().map { it.qualifiedName }.toSet(),
        )
    }

    @Test
    fun `uses App as the module name when no option is given`() {
        val (compilation, result) = compile(model, providers, moduleName = null)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(
            listOf("SynapseProviderModule_App.kt"),
            compilation.generatedFiles().map { it.name },
        )
    }

    // ── Validation ──────────────────────────────────────────────────────

    @Test
    fun `reports duplicate providers for the same DataImpulse`() {
        val duplicate = SourceFile.kotlin(
            "Duplicate.kt",
            """
            package com.example.feature

            import com.synapselib.arch.base.provider.Provider
            import com.synapselib.arch.base.provider.ProviderScope
            import com.synapselib.arch.base.provider.SynapseProvider
            import javax.inject.Inject
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.flowOf

            @SynapseProvider
            class OtherFetchUserProvider @Inject constructor() : Provider<FetchUser, User>() {
                override fun ProviderScope.produce(impulse: FetchUser): Flow<User> = flowOf(User("x"))
            }
            """.trimIndent(),
        )

        val (_, result) = compile(model, providers, duplicate)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("Duplicate @SynapseProvider" in result.messages, result.messages)
    }

    @Test
    fun `reports an annotated class that does not extend Provider`() {
        val notAProvider = SourceFile.kotlin(
            "NotAProvider.kt",
            """
            package com.example.feature

            import com.synapselib.arch.base.provider.SynapseProvider

            @SynapseProvider
            class NotAProvider
            """.trimIndent(),
        )

        val (_, result) = compile(model, notAProvider)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("No Provider supertype found" in result.messages, result.messages)
    }

    @Test
    fun `reports an abstract provider`() {
        val abstractProvider = SourceFile.kotlin(
            "AbstractProvider.kt",
            """
            package com.example.feature

            import com.synapselib.arch.base.provider.Provider
            import com.synapselib.arch.base.provider.SynapseProvider

            @SynapseProvider
            abstract class AbstractUserProvider : Provider<FetchUser, User>()
            """.trimIndent(),
        )

        val (_, result) = compile(model, abstractProvider)

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertTrue("must not be abstract" in result.messages, result.messages)
    }

    // ── Packaging ───────────────────────────────────────────────────────

    @Test
    fun `processor is discoverable through the KSP service file`() {
        val discovered = ServiceLoader.load(SymbolProcessorProvider::class.java).map { it::class.java }
        assertTrue(ProviderProcessorProvider::class.java in discovered, discovered.toString())
    }

    @Test
    fun `module names are sanitized into PascalCase identifiers`() {
        assertEquals("FeatureLogin", ProviderProcessorProvider.sanitizeModuleName("feature-login"))
        assertEquals("FeatureAuthApi", ProviderProcessorProvider.sanitizeModuleName("feature_auth.api"))
        assertEquals("App", ProviderProcessorProvider.sanitizeModuleName("App"))
    }
}
