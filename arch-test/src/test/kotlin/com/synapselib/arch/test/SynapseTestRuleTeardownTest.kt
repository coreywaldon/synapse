package com.synapselib.arch.test

import com.synapselib.arch.base.DefaultSwitchBoard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Drives [SynapseTestRule.apply] directly to verify setup / teardown behaviour. */
class SynapseTestRuleTeardownTest {

    private fun SynapseTestRule.run(body: SynapseTestRule.() -> Unit) {
        apply(
            object : Statement() {
                override fun evaluate() = body()
            },
            Description.EMPTY,
        ).evaluate()
    }

    @Test
    fun ruleStartsBeforeAndClosesAfterTheTest() {
        val rule = SynapseTestRule()
        var board: DefaultSwitchBoard? = null
        var startedDuringTest = false

        rule.run {
            startedDuringTest = isStarted
            board = switchBoard
        }

        assertTrue(startedDuringTest)
        assertTrue(board!!.isClosed, "the rule must close its SwitchBoard on teardown")
        assertFalse(rule.isStarted)
    }

    @Test
    fun ruleClosesEvenWhenTheTestFails() {
        val rule = SynapseTestRule()
        var board: DefaultSwitchBoard? = null

        assertThrows<AssertionError> {
            rule.run {
                board = switchBoard
                throw AssertionError("boom")
            }
        }

        assertTrue(board!!.isClosed)
    }

    @Test
    fun teardownFailureIsSuppressedIntoTestFailure() {
        val rule = SynapseTestRule()

        val error = assertThrows<AssertionError> {
            rule.run {
                Coordinator { onDispose { error("teardown") } }
                throw AssertionError("test")
            }
        }

        assertEquals("test", error.message)
        assertEquals(listOf("teardown"), error.suppressed.map { it.message })
    }

    @Test
    fun teardownFailureAloneFailsTheTest() {
        val rule = SynapseTestRule()

        val error = assertThrows<IllegalStateException> {
            rule.run { Coordinator { onDispose { error("teardown") } } }
        }

        assertEquals("teardown", error.message)
    }
}
