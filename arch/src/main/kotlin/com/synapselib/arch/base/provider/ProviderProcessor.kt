package com.synapselib.arch.base.provider

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate
import kotlin.collections.iterator

/**
 * KSP processor for the `@SynapseProvider` annotation.
 *
 * ## Processing Pipeline
 *
 * 1. **Discovery**: finds all classes annotated with `@SynapseProvider`.
 * 2. **Validation**: enforces structural rules at compile time.
 * 3. **Extraction**: resolves the `DataImpulse` (I) and result (Need)
 *    type arguments from each provider's `Provider<I, Need>` supertype.
 * 4. **Duplicate detection**: ensures no two providers handle the same
 *    `DataImpulse` type — reports a compiler error if they do.
 * 5. **Code generation**: emits a Hilt `@Module` and a Koin module that
 *    build a [ProviderRegistry] from the discovered providers.
 *
 * ## Compile-Time Errors
 *
 * The processor reports errors (not warnings) for:
 * - `@SynapseProvider` on a non-class declaration (interface, object, enum).
 * - `@SynapseProvider` on an abstract class.
 * - Annotated class does not extend `com.synapselib.arch.base.provider.Provider`.
 * - Type arguments on the `SynapseProvider` supertype cannot be resolved.
 * - Two or more `@SynapseProvider` classes handle the same `DataImpulse` type.
 * - Annotated class has no `@Inject`-annotated constructor (warning).
 */
class ProviderProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val moduleName: String,
) : SymbolProcessor {

    companion object {
        /** Fully qualified name of the `@[SynapseProvider]` annotation. */
        const val ANNOTATION_FQN = "com.synapselib.arch.base.provider.SynapseProvider"

        /** Fully qualified name of the `Provider` base class. */
        const val PROVIDER_BASE_FQN = "com.synapselib.arch.base.provider.Provider"

        /** Package for all generated code. */
        const val GENERATED_PACKAGE = "com.synapselib.arch.generated"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(ANNOTATION_FQN).toList()

        // Defer symbols that haven't been fully resolved yet.
        val (valid, deferred) = symbols.partition { it.validate() }

        val annotatedClasses = valid.filterIsInstance<KSClassDeclaration>()
        if (annotatedClasses.isEmpty()) return deferred

        // ── Phase 1: Validate & Extract ─────────────────────────────
        val entries = mutableListOf<ProviderEntry>()
        var hasErrors = false

        for (classDecl in annotatedClasses) {
            val entry = validateAndExtract(classDecl)
            if (entry != null) {
                entries.add(entry)
            } else {
                hasErrors = true
            }
        }

        // ── Phase 2: Detect Duplicates ──────────────────────────────
        val duplicateGroups = entries
            .groupBy { it.impulseQualifiedName }
            .filter { it.value.size > 1 }

        for ((impulseFqn, duplicates) in duplicateGroups) {
            hasErrors = true
            val providerNames = duplicates.joinToString { it.providerSimpleName }
            for (entry in duplicates) {
                logger.error(
                    "Duplicate @SynapseProvider for DataImpulse '$impulseFqn': " +
                            "[$providerNames]. Each DataImpulse type must have " +
                            "exactly one provider.",
                    entry.declaration,
                )
            }
        }

        if (hasErrors) return deferred

        // ── Phase 3: Generate Code ──────────────────────────────────
        val sourceFiles = annotatedClasses.mapNotNull { it.containingFile }
        val dependencies = Dependencies(aggregating = true, *sourceFiles.toTypedArray())

        val hasHilt = resolver.getClassDeclarationByName("dagger.hilt.InstallIn") != null

        // Check if Koin is on the app's classpath
        val hasKoin = resolver.getClassDeclarationByName("org.koin.core.module.Module") != null

        when {
            hasHilt -> generateHiltModule(entries ,dependencies)
            hasKoin -> generateKoinModule(entries ,dependencies)
            else -> logger.error("No supported DI framework found!")
        }

        return deferred
    }

    // ════════════════════════════════════════════════════════════════════
    // Validation & Extraction
    // ════════════════════════════════════════════════════════════════════

    /**
     * Parsed metadata for a single `@SynapseProvider`-annotated class.
     *
     * All qualified names are resolved during validation, so code
     * generation can work purely with strings.
     */
    private data class ProviderEntry(
        /** The annotated class declaration (for error reporting). */
        val declaration: KSClassDeclaration,

        /** Fully qualified name of the annotated provider class. */
        val providerQualifiedName: String,

        /** Simple name of the annotated provider class. */
        val providerSimpleName: String,

        /** Fully qualified name of the DataImpulse type argument (I). */
        val impulseQualifiedName: String,

        /** Simple name of the DataImpulse type argument (I). */
        val impulseSimpleName: String,

        /**
         * Fully qualified name of the Need type argument's **raw** type.
         *
         * For `List<UserProfile>`, this is `kotlin.collections.List`.
         * For `UserProfile`, this is `com.example.UserProfile`.
         * Used to generate the `Class<Need>` token (`List::class.java`).
         */
        val needRawQualifiedName: String,

        /**
         * The full type representation of Need, including generics,
         * for use in KDoc and diagnostic messages.
         */
        val needDisplayName: String,

        /**
         * Whether the Need type is parameterized (e.g. `List<UserProfile>`).
         * If true, the generated `Class` token requires a cast.
         */
        val needIsParameterized: Boolean,
        /** All fully qualified names required by this provider's signature. */
        val allRequiredImports: Set<String>,
    )

    /**
     * Validates a single `@Provider`-annotated class declaration and
     * extracts its [ProviderEntry] metadata.
     *
     * @return the entry on success, or `null` if validation failed
     *         (errors are reported to [logger]).
     */
    private fun validateAndExtract(classDecl: KSClassDeclaration): ProviderEntry? {
        val qualifiedName = classDecl.qualifiedName?.asString()

        // ── Must be a concrete class ────────────────────────────────
        if (classDecl.classKind != ClassKind.CLASS) {
            logger.error(
                "@SynapseProvider must annotate a class, not a " +
                        "${classDecl.classKind.name.lowercase()}.",
                classDecl,
            )
            return null
        }

        if (classDecl.isAbstract()) {
            logger.error(
                "@SynapseProvider class '$qualifiedName' must not be abstract. " +
                        "Providers are instantiated on demand by the framework.",
                classDecl,
            )
            return null
        }

        // ── Must extend Provider<I, Need> ───────────────────────────
        val providerSupertype = classDecl.superTypes
            .map { it.resolve() }
            .firstOrNull { supertype ->
                supertype.declaration.qualifiedName?.asString() == PROVIDER_BASE_FQN
            }

        if (providerSupertype == null) {
            logger.error(
                "@SynapseProvider class '$qualifiedName' must extend " +
                        "'com.synapselib.arch.base.provider.Provider<I, Need>'. " +
                        "No Provider supertype found.",
                classDecl,
            )
            return null
        }

        // ── Extract type arguments ──────────────────────────────────
        val typeArgs = providerSupertype.arguments
        if (typeArgs.size != 2) {
            logger.error(
                "@SynapseProvider class '$qualifiedName': expected 2 type arguments " +
                        "on Provider<I, Need>, found ${typeArgs.size}.",
                classDecl,
            )
            return null
        }

        val impulseType = typeArgs[0].resolveType(classDecl, "I (DataImpulse)") ?: return null
        val needType = typeArgs[1].resolveType(classDecl, "Need") ?: return null

        val impulseFqn = impulseType.declaration.qualifiedName?.asString()
        val needRawFqn = needType.declaration.qualifiedName?.asString()

        if (impulseFqn == null || needRawFqn == null) {
            logger.error(
                "@SynapseProvider class '$qualifiedName': could not resolve " +
                        "qualified names for type arguments.",
                classDecl,
            )
            return null
        }

        // ── Warn if no @Inject constructor ──────────────────────────
        val hasInjectConstructor = classDecl.primaryConstructor?.let { ctor ->
            ctor.annotations.any { ann ->
                val annFqn = ann.annotationType.resolve().declaration.qualifiedName?.asString()
                annFqn == "javax.inject.Inject" || annFqn == "jakarta.inject.Inject"
            }
        } ?: false

        if (!hasInjectConstructor) {
            logger.warn(
                "@SynapseProvider class '$qualifiedName' has no @Inject constructor. " +
                        "Hilt will not be able to provide its dependencies. " +
                        "If using Koin, ensure the class is declared in a Koin module.",
                classDecl,
            )
        }

        val requiredImports = mutableSetOf<String>()
        requiredImports.add(qualifiedName!!) // The provider class itself
        collectImports(impulseType, requiredImports)
        collectImports(needType, requiredImports)

        return ProviderEntry(
            declaration = classDecl,
            providerQualifiedName = qualifiedName,
            providerSimpleName = classDecl.simpleName.asString(),
            impulseQualifiedName = impulseFqn,
            impulseSimpleName = impulseType.declaration.simpleName.asString(),
            needRawQualifiedName = needRawFqn,
            needDisplayName = needType.toDisplayString(),
            needIsParameterized = needType.arguments.isNotEmpty(),
            allRequiredImports = requiredImports, // Pass it in here
        )
    }

    /**
     * Resolves a [KSTypeArgument] to a concrete [KSType], reporting an
     * error on the [owner] if resolution fails.
     */
    private fun KSTypeArgument.resolveType(
        owner: KSClassDeclaration,
        paramName: String,
    ): KSType? {
        if (variance == Variance.STAR) {
            logger.error(
                "@SynapseProvider class '${owner.qualifiedName?.asString()}': " +
                        "type argument $paramName must be a concrete type, " +
                        "not a star projection (*).",
                owner,
            )
            return null
        }

        val resolved = type?.resolve()
        if (resolved == null || resolved.isError) {
            logger.error(
                "@SynapseProvider class '${owner.qualifiedName?.asString()}': " +
                        "could not resolve type argument $paramName. " +
                        "Ensure the type is imported and available.",
                owner,
            )
            return null
        }

        return resolved
    }

    /**
     * Produces a human-readable type string including generic parameters.
     * (e.g. `List<UserProfile>`, `Map<String, Int>`, `UserProfile`.)
     */
    private fun KSType.toDisplayString(): String {
        val base = declaration.simpleName.asString()
        if (arguments.isEmpty()) return base
        val args = arguments.joinToString(", ") { arg ->
            arg.type?.resolve()?.toDisplayString() ?: "?"
        }
        return "$base<$args>"
    }

    // ════════════════════════════════════════════════════════════════════
    // Code Generation — Hilt
    // ════════════════════════════════════════════════════════════════════

    private fun generateHiltModule(entries: List<ProviderEntry>, dependencies: Dependencies) {
        val className = "SynapseProviderModule_$moduleName"

        val file = codeGenerator.createNewFile(
            dependencies, GENERATED_PACKAGE, className
        )

        file.writer().use { w ->
            w.write(buildString {
                appendLine("package $GENERATED_PACKAGE")
                appendLine()
                appendLine("import com.synapselib.arch.base.provider.ProviderFactory")
                appendLine("import com.synapselib.arch.base.provider.ProviderRegistry")
                appendLine("import dagger.Module")
                appendLine("import dagger.Provides")
                appendLine("import dagger.hilt.InstallIn")
                appendLine("import dagger.hilt.components.SingletonComponent")
                appendLine("import javax.inject.Singleton")
                appendLine()

                // Import each provider and impulse class.
                val imports = mutableSetOf<String>()
                for (entry in entries) {
                    imports.addAll(entry.allRequiredImports)
                }
                for (import in imports.sorted()) {
                    appendLine("import $import")
                }

                appendLine()
                appendLine("/**")
                appendLine(" * Auto-generated Hilt module that provides a [ProviderRegistry]")
                appendLine(" * assembled from all `@SynapseProvider`-annotated classes.")
                appendLine(" *")
                appendLine(" * Generated by SynapseLib KSP — do not edit.")
                appendLine(" *")
                appendLine(" * ## Registered Providers")
                appendLine(" *")
                for (entry in entries) {
                    appendLine(" * - `${entry.providerSimpleName}`: " +
                            "${entry.impulseSimpleName} → ${entry.needDisplayName}")
                }
                appendLine(" *")
                appendLine(" * ## Testing")
                appendLine(" *")
                appendLine(" * To replace this module in tests:")
                appendLine(" * ```kotlin")
                appendLine(" * @UninstallModules(SynapseProviderModule::class)")
                appendLine(" * @HiltAndroidTest")
                appendLine(" * class MyTest {")
                appendLine(" *     @BindValue")
                appendLine(" *     val registry = ProviderRegistry.Builder()")
                appendLine(" *         .register<Need, Impulse> { FakeProvider() }")
                appendLine(" *         .build()")
                appendLine(" * }")
                appendLine(" * ```")
                appendLine(" */")
                appendLine("@Module")
                appendLine("@InstallIn(SingletonComponent::class)")
                appendLine("object $className {")
                appendLine()
                appendLine("    @Provides")
                appendLine("    @Singleton")

                // If any Need type is parameterized (e.g., List<UserProfile>),
                // the generated Class token requires a JVM erasure cast.
                // This is safe and standard for generated code.
                val hasParameterizedNeed = entries.any { it.needIsParameterized }
                if (hasParameterizedNeed) {
                    appendLine("    @Suppress(\"UNCHECKED_CAST\") // JVM erasure: parameterized Class<T> tokens")
                }

                // Function signature with javax.inject.Provider<T> params.
                // Using javax.inject.Provider gives Hilt a lazy factory —
                // each .get() call creates a new instance (cold-start).
                append("    fun provideRegistry(")
                appendLine()
                for ((index, entry) in entries.withIndex()) {
                    val paramName = entry.providerSimpleName.replaceFirstChar { it.lowercase() }
                    val trailing = if (index < entries.lastIndex) "," else ""
                    appendLine("        $paramName: javax.inject.Provider<${entry.providerSimpleName}>$trailing")
                }
                appendLine("    ): ProviderRegistry {")
                appendLine("        return ProviderRegistry.Builder()")

                for (entry in entries) {
                    val paramName = entry.providerSimpleName.replaceFirstChar { it.lowercase() }
                    val needClassExpr = needClassExpression(entry)
                    appendLine("            .register(")
                    appendLine("                impulseType = ${entry.impulseSimpleName}::class,")
                    appendLine("                needClass = $needClassExpr,")
                    appendLine("                factory = ProviderFactory { $paramName.get() },")
                    appendLine("            )")
                }

                appendLine("            .build()")
                appendLine("    }")
                appendLine("}")
            })
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Code Generation — Koin
    // ════════════════════════════════════════════════════════════════════

    private fun generateKoinModule(entries: List<ProviderEntry>, dependencies: Dependencies) {
        val fileName = "SynapseProviderKoinModule_$moduleName"
        val valName = "synapseProviderModule_$moduleName".replaceFirstChar { it.lowercase() }
        val file = codeGenerator.createNewFile(
            dependencies, GENERATED_PACKAGE, fileName
        )
        file.writer().use { w ->
            w.write(buildString {
                appendLine("package $GENERATED_PACKAGE")
                appendLine()
                appendLine("import com.synapselib.arch.base.provider.ProviderFactory")
                appendLine("import com.synapselib.arch.base.provider.ProviderRegistry")
                appendLine("import org.koin.dsl.module")
                appendLine()

                val imports = mutableSetOf<String>()
                for (entry in entries) {
                    imports.addAll(entry.allRequiredImports)
                }
                for (import in imports.sorted()) {
                    appendLine("import $import")
                }

                appendLine()
                appendLine("/**")
                appendLine(" * Auto-generated Koin module that provides a [ProviderRegistry]")
                appendLine(" * assembled from all `@SynapseProvider`-annotated classes.")
                appendLine(" *")
                appendLine(" * Generated by SynapseLib KSP — do not edit.")
                appendLine(" *")
                appendLine(" * ## Usage")
                appendLine(" *")
                appendLine(" * ```kotlin")
                appendLine(" * startKoin {")
                appendLine(" *     modules(synapseProviderModule)")
                appendLine(" * }")
                appendLine(" * ```")
                appendLine(" *")
                appendLine(" * ## Testing")
                appendLine(" *")
                appendLine(" * Override with a test module:")
                appendLine(" * ```kotlin")
                appendLine(" * loadKoinModules(module {")
                appendLine(" *     single<ProviderRegistry> {")
                appendLine(" *         ProviderRegistry.Builder()")
                appendLine(" *             .register<Need, Impulse> { FakeProvider() }")
                appendLine(" *             .build()")
                appendLine(" *     }")
                appendLine(" * })")
                appendLine(" * ```")
                appendLine(" */")

                val hasParameterizedNeed = entries.any { it.needIsParameterized }
                if (hasParameterizedNeed) {
                    appendLine("@Suppress(\"UNCHECKED_CAST\") // JVM erasure: parameterized Class<T> tokens")
                }
                appendLine("val $valName = module {")
                appendLine()

                // Declare each provider as a Koin factory (new instance per get()).
                for (entry in entries) {
                    appendLine("    factory { get<${entry.providerSimpleName}>() }")
                }

                appendLine()
                appendLine("    single<ProviderRegistry> {")
                appendLine("        ProviderRegistry.Builder()")

                for (entry in entries) {
                    val needClassExpr = needClassExpression(entry)
                    appendLine("            .register(")
                    appendLine("                impulseType = ${entry.impulseSimpleName}::class,")
                    appendLine("                needClass = $needClassExpr,")
                    appendLine("                factory = ProviderFactory { get<${entry.providerSimpleName}>() },")
                    appendLine("            )")
                }

                appendLine("            .build()")
                appendLine("    }")
                appendLine("}")
            })
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Utilities
    // ════════════════════════════════════════════════════════════════════

    /**
     * Generates the Kotlin expression for the `Class<Need>` token.
     *
     * For non-parameterized types (e.g. `UserProfile`):
     * ```
     * UserProfile::class.java
     * ```
     *
     * For parameterized types (e.g. `List<UserProfile>`), the JVM erases
     * the parameter, so the token is the raw class with a narrowing cast
     * that the compiler can verify structurally:
     * ```
     * @Suppress("UNCHECKED_CAST")
     * List::class.java as Class<List<UserProfile>>
     * ```
     *
     * This cast is safe because `List::class.java` *is* the `Class` for
     * all `List<*>` variants at runtime — JVM erasure guarantees it.
     */
    private fun needClassExpression(entry: ProviderEntry): String {
        val rawSimple = entry.needRawQualifiedName.substringAfterLast('.')
        return if (!entry.needIsParameterized) {
            "${rawSimple}::class.java"
        } else {
            // For parameterized Need types, we must cast the raw Class
            // to the parameterized form. This is the standard JVM erasure
            // bridge — safe at runtime, requires suppression at compile time.
            "${rawSimple}::class.java as Class<${entry.needDisplayName}>"
        }
    }

    /**
     * Recursively extracts the fully qualified name of a type and all its
     * generic arguments, adding them to the provided set.
     */
    private fun collectImports(type: KSType, out: MutableSet<String>) {
        val fqn = type.declaration.qualifiedName?.asString()
        // Only add valid types with packages (ignore primitives/generics without packages)
        if (fqn != null && fqn.contains(".")) {
            out.add(fqn)
        }

        for (arg in type.arguments) {
            val resolvedArg = arg.type?.resolve()
            if (resolvedArg != null && !resolvedArg.isError) {
                collectImports(resolvedArg, out)
            }
        }
    }
}