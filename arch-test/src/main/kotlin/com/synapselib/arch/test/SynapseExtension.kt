package com.synapselib.arch.test

import com.synapselib.arch.base.DefaultSwitchBoard
import com.synapselib.arch.base.SwitchBoard
import com.synapselib.arch.base.SynapseScope
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * A JUnit 5 (Jupiter) extension around [SynapseTestHarness]: it
 * [starts][SynapseTestHarness.start] the harness before each test and
 * [closes][SynapseTestHarness.close] it after each test — the Jupiter
 * counterpart of [SynapseTestRule].
 *
 * `junit-jupiter-api` is a **compile-only** dependency of `arch-test`: JUnit 4
 * users never load this class and need nothing extra, and JUnit 5 users already
 * have the API on their test classpath.
 *
 * ## Field style (recommended)
 *
 * ```kotlin
 * class AuthCoordinatorTest {
 *     @JvmField
 *     @RegisterExtension
 *     val synapse = SynapseExtension {
 *         provide<AuthToken, FetchCachedToken> { null }
 *     }
 *
 *     @Test
 *     fun login() = synapse.runTest {
 *         val session = synapse.onState<SessionState>()
 *         synapse.Trigger(LoginRequested("user@test.com", "pw"))
 *         session.assertCaptured()
 *     }
 * }
 * ```
 *
 * With Jupiter's default per-method test-instance lifecycle every test gets
 * its own extension instance.
 *
 * ## Parameter style
 *
 * ```kotlin
 * @ExtendWith(SynapseExtension::class)
 * class PingTest {
 *     @Test
 *     fun ping(synapse: SynapseTestHarness) = synapse.runTest {
 *         val ping = synapse.onImpulse<Ping>()
 *         synapse.Trigger(Ping())
 *         ping.assertCaptured()
 *     }
 * }
 * ```
 *
 * The extension resolves parameters of type [SynapseExtension],
 * [SynapseTestHarness], [SynapseScope], [SwitchBoard] and [DefaultSwitchBoard]
 * in test and lifecycle methods. The harness types may also be injected into
 * the test class constructor, which is resolved *before* `beforeEach` — so
 * only touch the board inside tests, and do not request [SwitchBoard] /
 * [DefaultSwitchBoard] there (no board exists yet). Declarative registration
 * (`@ExtendWith`) cannot take a `configure` block — use the field style for
 * providers.
 *
 * An `@ExtendWith` extension instance is shared by every test of the class,
 * which is fine for sequential execution (the harness restarts per test) but
 * not for concurrent execution of tests within the class — like
 * [SynapseTestRule], the harness swaps the global `Dispatchers.Main`.
 *
 * @param stateTimeout   see [SynapseTestHarness] (`SwitchBoardConfig.stopTimeout`, ms).
 * @param impulseTimeout see [SynapseTestHarness] (`SwitchBoardConfig.replayExpiration`, ms).
 * @param configure      optional DSL block run before each test; see [SynapseTestConfig].
 */
class SynapseExtension(
    stateTimeout: Long = 3_000,
    impulseTimeout: Long = 3_000,
    configure: (SynapseTestConfig.() -> Unit)? = null,
) : SynapseTestHarness(stateTimeout, impulseTimeout, configure),
    BeforeEachCallback,
    AfterEachCallback,
    ParameterResolver {

    override fun beforeEach(context: ExtensionContext) {
        start()
    }

    override fun afterEach(context: ExtensionContext) {
        close()
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean =
        parameterContext.parameter.type in RESOLVABLE_TYPES

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any =
        when (parameterContext.parameter.type) {
            SwitchBoard::class.java, DefaultSwitchBoard::class.java -> switchBoard
            else -> this
        }

    private companion object {
        val RESOLVABLE_TYPES: Set<Class<*>> = setOf(
            SynapseExtension::class.java,
            SynapseTestHarness::class.java,
            SynapseScope::class.java,
            SwitchBoard::class.java,
            DefaultSwitchBoard::class.java,
        )
    }
}
