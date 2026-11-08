package com.synapselib.arch.test

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * A JUnit 4 [TestRule] around [SynapseTestHarness]: it [starts][SynapseTestHarness.start]
 * the harness before each test and [closes][SynapseTestHarness.close] it
 * afterwards, so every test gets a fresh [com.synapselib.arch.base.DefaultSwitchBoard],
 * lifecycle owner and capture set, and nothing leaks between tests.
 *
 * All functionality — `switchBoard`, `Trigger` / `Broadcast` / `ListenFor` /
 * `ReactTo` / `Request`, the `on…` capture helpers, `Coordinator { }`,
 * `moveTo(state)` and `runTest { }` — is inherited from [SynapseTestHarness].
 *
 * ## Basic usage
 *
 * ```kotlin
 * @get:Rule
 * val synapse = SynapseTestRule()
 *
 * @Test
 * fun loginTriggersAuthSuccess() = synapse.runTest {
 *     val success = synapse.onImpulse<AuthSuccess>()
 *     synapse.Trigger(LoginRequested("user@test.com", "pass"))
 *     success.assertCaptured()
 * }
 * ```
 *
 * ## With providers and board configuration
 *
 * ```kotlin
 * @get:Rule
 * val synapse = SynapseTestRule {
 *     provide<List<Address>, FetchAddresses> { testAddresses }
 *     switchBoard { maxConcurrentJobs = 1 }
 * }
 * ```
 *
 * If both the test and the teardown fail, the test's exception is reported
 * with the teardown failure attached as suppressed.
 *
 * @param stateTimeout   see [SynapseTestHarness] (`SwitchBoardConfig.stopTimeout`, ms).
 * @param impulseTimeout see [SynapseTestHarness] (`SwitchBoardConfig.replayExpiration`, ms).
 * @param configure      optional DSL block run before each test; see [SynapseTestConfig].
 */
class SynapseTestRule(
    stateTimeout: Long = 3_000,
    impulseTimeout: Long = 3_000,
    configure: (SynapseTestConfig.() -> Unit)? = null,
) : SynapseTestHarness(stateTimeout, impulseTimeout, configure), TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                start()
                var testFailure: Throwable? = null
                try {
                    base.evaluate()
                } catch (t: Throwable) {
                    testFailure = t
                    throw t
                } finally {
                    try {
                        close()
                    } catch (teardownFailure: Throwable) {
                        if (testFailure == null) throw teardownFailure
                        testFailure.addSuppressed(teardownFailure)
                    }
                }
            }
        }
}
