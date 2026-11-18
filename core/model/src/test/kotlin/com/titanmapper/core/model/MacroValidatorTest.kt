package com.titanmapper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroValidatorTest {

    private fun macro(steps: List<MacroStep>) =
        MacroDefinition(id = "test", name = "Test macro", steps = steps)

    @Test
    fun `valid empty macro passes`() {
        assertTrue(MacroValidator.validate(macro(emptyList())).isEmpty())
    }

    @Test
    fun `blank name is an error`() {
        val errors = MacroValidator.validate(macro(emptyList()).copy(name = "  "))
        assertTrue(errors.any { "empty" in it.lowercase() })
    }

    @Test
    fun `name longer than max is an error`() {
        val longName = "A".repeat(MacroValidator.MAX_NAME_LENGTH + 1)
        val errors = MacroValidator.validate(macro(emptyList()).copy(name = longName))
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `matched loop passes`() {
        val steps = listOf(MacroStep.LoopStart(3), MacroStep.Delay(10), MacroStep.LoopEnd)
        assertTrue(MacroValidator.validate(macro(steps)).isEmpty())
    }

    @Test
    fun `unmatched loop start is an error`() {
        val steps = listOf(MacroStep.LoopStart(2))
        val errors = MacroValidator.validate(macro(steps))
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `unmatched loop end is an error`() {
        val steps = listOf(MacroStep.LoopEnd)
        val errors = MacroValidator.validate(macro(steps))
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `negative delay is an error`() {
        val errors = MacroValidator.validate(macro(listOf(MacroStep.Delay(-1))))
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `delay exceeding max is an error`() {
        val errors = MacroValidator.validate(macro(listOf(MacroStep.Delay(MacroValidator.MAX_DELAY_MILLIS + 1))))
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `countSteps counts flat list`() {
        val steps = listOf(MacroStep.PressKey(KeyCode.A), MacroStep.Delay(100), MacroStep.ReleaseKey(KeyCode.A))
        assertEquals(3, MacroValidator.countSteps(steps))
    }

    @Test
    fun `countSteps includes nested conditional branches`() {
        val nested = MacroStep.ConditionalIf(
            condition = MacroCondition.ForegroundAppIs("com.example"),
            thenSteps = listOf(MacroStep.PressKey(KeyCode.A), MacroStep.ReleaseKey(KeyCode.A)),
            elseSteps = listOf(MacroStep.Delay(50))
        )
        // The ConditionalIf itself + 2 then + 1 else = 4 total
        assertEquals(4, MacroValidator.countSteps(listOf(nested)))
    }

    @Test
    fun `loop count out of range is an error`() {
        val errors = MacroValidator.validate(macro(listOf(
            MacroStep.LoopStart(MacroValidator.MAX_LOOP_COUNT + 1),
            MacroStep.LoopEnd
        )))
        assertTrue(errors.isNotEmpty())
    }
}
