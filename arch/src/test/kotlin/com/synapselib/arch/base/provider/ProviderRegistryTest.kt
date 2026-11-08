package com.synapselib.arch.base.provider

import com.synapselib.arch.base.DataImpulse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderRegistryTest {

    data class Result(val value: String)
    data class FetchA(val id: Int) : DataImpulse<Result>()
    data class FetchB(val id: Int) : DataImpulse<Result>()

    class ProviderA : Provider<FetchA, Result>() {
        override fun ProviderScope.produce(impulse: FetchA): Flow<Result> = flowOf(Result("a"))
    }

    class ProviderB : Provider<FetchB, Result>() {
        override fun ProviderScope.produce(impulse: FetchB): Flow<Result> = flowOf(Result("b"))
    }

    private fun registryA(name: String? = null) =
        ProviderRegistry.Builder(name).register<Result, FetchA> { ProviderA() }.build()

    private fun registryB(name: String? = null) =
        ProviderRegistry.Builder(name).register<Result, FetchB> { ProviderB() }.build()

    @Test
    fun `EMPTY has no providers`() {
        assertEquals(0, ProviderRegistry.EMPTY.size)
        assertTrue(ProviderRegistry.EMPTY.registeredImpulseTypes().isEmpty())
        assertNull(ProviderRegistry.EMPTY.name)
    }

    @Test
    fun `registeredImpulseTypes returns a snapshot of the registered types`() {
        val registry = registryA() + registryB()
        assertEquals(setOf(FetchA::class, FetchB::class), registry.registeredImpulseTypes())
    }

    @Test
    fun `plus composes two registries`() {
        val merged = registryA() + registryB()
        assertEquals(2, merged.size)
        assertTrue(merged.hasProvider(FetchA::class))
        assertTrue(merged.hasProvider(FetchB::class))
    }

    @Test
    fun `of composes any number of registries`() {
        val merged = ProviderRegistry.of(registryA(), ProviderRegistry.EMPTY, registryB())
        assertEquals(2, merged.size)
        assertEquals(0, ProviderRegistry.of().size)
    }

    @Test
    fun `composition leaves the source registries untouched`() {
        val a = registryA()
        a + registryB()
        assertEquals(1, a.size)
        assertFalse(a.hasProvider(FetchB::class))
    }

    @Test
    fun `merge duplicate error names both conflicting registries`() {
        val error = assertThrows(IllegalStateException::class.java) {
            ProviderRegistry.of(registryA("feature-one"), registryA("feature-two"))
        }
        val message = error.message.orEmpty()
        assertTrue(message.contains(FetchA::class.qualifiedName!!), message)
        assertTrue(message.contains("feature-one"), message)
        assertTrue(message.contains("feature-two"), message)
    }

    @Test
    fun `plus rejects duplicates`() {
        assertThrows(IllegalStateException::class.java) { registryA() + registryA() }
    }

    @Test
    fun `duplicate registration within one builder names both factories`() {
        val first = ProviderFactory<FetchA, Result> { ProviderA() }
        val second = ProviderFactory<FetchA, Result> { ProviderA() }
        val error = assertThrows(IllegalStateException::class.java) {
            ProviderRegistry.Builder("feature-x")
                .register(FetchA::class, Result::class.java, first)
                .register(FetchA::class, Result::class.java, second)
        }
        val message = error.message.orEmpty()
        assertTrue(message.contains(first.javaClass.name), message)
        assertTrue(message.contains(second.javaClass.name), message)
        assertTrue(message.contains("feature-x"), message)
    }

    @Test
    fun `builder name is exposed on the registry and its contribution`() {
        val registry = registryA("feature-cart")
        assertEquals("feature-cart", registry.name)
        val contribution = ProviderRegistryContribution(registry)
        assertSame(registry, contribution.registry)
        assertEquals("feature-cart", contribution.name)
    }

    @Test
    fun `merged factories keep their original origin`() {
        val merged = ProviderRegistry.of(registryA("feature-one"), registryB("feature-two"))
        val error = assertThrows(IllegalStateException::class.java) {
            ProviderRegistry.Builder("app").mergeFrom(merged).mergeFrom(registryA("feature-three"))
        }
        val message = error.message.orEmpty()
        assertTrue(message.contains("feature-one"), message)
        assertTrue(message.contains("feature-three"), message)
    }
}
