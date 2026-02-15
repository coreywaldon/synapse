package com.synapselib.arch.base.routing.producer.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration

@AutoService(SymbolProcessorProvider::class)
class ProducerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment) =
        ProducerProcessor(environment.codeGenerator, environment.logger)
}

class ProducerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("com.synapselib.arch.base.routing.producer.ksp.Producer")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        if (symbols.isEmpty()) return emptyList()

        val producers = symbols.map { it.qualifiedName!!.asString() }

        generateHiltModule(producers)
        generateKoinModule(producers)

        return emptyList()
    }

    private fun generateHiltModule(producers: List<String>) {
        val file = codeGenerator.createNewFile(
            Dependencies.ALL_FILES, "com.synapselib.arch.generated", "GeneratedProducerModule"
        )
        file.writer().use { w ->
            w.write("""
                package com.synapselib.arch.generated

                import dagger.Module
                import dagger.Provides
                import dagger.hilt.InstallIn
                import dagger.hilt.components.SingletonComponent
                import dagger.multibindings.ElementsIntoSet
                import com.example.Producer
                import javax.inject.Inject

                @Module
                @InstallIn(SingletonComponent::class)
                object GeneratedProducerModule {
                    @Provides
                    @ElementsIntoSet
                    fun provideProducers(
                        ${producers.mapIndexed { i, fqn -> "p$i: $fqn" }.joinToString(",\n                        ")}
                    ): Set<@JvmSuppressWildcards Producer<*>> = setOf(
                        ${producers.indices.joinToString(", ") { "p$it" }}
                    )
                }
            """.trimIndent())
        }
    }

    private fun generateKoinModule(producers: List<String>) {
        val file = codeGenerator.createNewFile(
            Dependencies.ALL_FILES, "com.synapselib.arch.generated", "GeneratedKoinProducerModule"
        )
        file.writer().use { w ->
            w.write("""
                package com.synapselib.arch.generated

                import org.koin.dsl.module
                import com.example.Producer

                val generatedProducerModule = module {
                    ${producers.joinToString("\n                    ") { "single { $it() }" }}
                    single<Set<Producer<*>>> {
                        setOf(${producers.joinToString(", ") { "get<$it>()" }})
                    }
                }
            """.trimIndent())
        }
    }
}