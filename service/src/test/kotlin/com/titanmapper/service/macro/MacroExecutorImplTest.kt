package com.titanmapper.service.macro

import com.google.common.truth.Truth.assertThat
import com.titanmapper.core.data.repository.EventLogRepository
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.KeyCode
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MacroStep
import com.titanmapper.core.model.MappedAction
import com.titanmapper.service.engine.DispatchPhase
import com.titanmapper.service.engine.OutputDispatchSink
import com.titanmapper.service.pipeline.ForegroundAppTracker
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MacroExecutorImplTest {

    private fun fakeSink(log: MutableList<MappedAction>) = object : OutputDispatchSink {
        override val tier = com.titanmapper.core.model.CaptureTier.ROOT
        override fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean {
            log += action
            return true
        }
        override fun supports(action: MappedAction) = true
    }

    @Test
    fun `loop with fixed count repeats its body that many times`() = runTest {
        val eventLog = mockk<EventLogRepository>(relaxed = true)
        val executor = MacroExecutorImpl(ForegroundAppTracker(), eventLog, this)
        val dispatched = mutableListOf<MappedAction>()
        executor.activeSink = fakeSink(dispatched)

        val macro = MacroDefinition(
            id = "m1", name = "Triple tap",
            steps = listOf(
                MacroStep.LoopStart(3),
                MacroStep.PressKey(KeyCode.A),
                MacroStep.ReleaseKey(KeyCode.A),
                MacroStep.LoopEnd
            )
        )
        executor.run(macro)
        advanceUntilIdle()

        assertThat(dispatched.count { it is MappedAction.KeyInjection && it.action == KeyAction.DOWN }).isEqualTo(3)
    }

    @Test
    fun `conditional runs the else branch when the condition is false`() = runTest {
        val eventLog = mockk<EventLogRepository>(relaxed = true)
        val executor = MacroExecutorImpl(ForegroundAppTracker(), eventLog, this)
        val dispatched = mutableListOf<MappedAction>()
        executor.activeSink = fakeSink(dispatched)
        executor.toggleStateProvider = { false }

        val macro = MacroDefinition(
            id = "m2", name = "Conditional",
            steps = listOf(
                MacroStep.ConditionalIf(
                    condition = com.titanmapper.core.model.MacroCondition.ToggleIsActive("someBinding"),
                    thenSteps = listOf(MacroStep.PressKey(KeyCode.A)),
                    elseSteps = listOf(MacroStep.PressKey(KeyCode.B))
                )
            )
        )
        executor.run(macro)
        advanceUntilIdle()

        val pressed = dispatched.filterIsInstance<MappedAction.KeyInjection>().single()
        assertThat(pressed.key).isEqualTo(KeyCode.B)
    }

    @Test
    fun `cancel stops a running macro`() = runTest {
        val eventLog = mockk<EventLogRepository>(relaxed = true)
        val executor = MacroExecutorImpl(ForegroundAppTracker(), eventLog, this)
        val dispatched = mutableListOf<MappedAction>()
        executor.activeSink = fakeSink(dispatched)

        val macro = MacroDefinition(
            id = "m3", name = "Infinite",
            steps = listOf(MacroStep.LoopStart(null), MacroStep.Delay(10), MacroStep.LoopEnd)
        )
        executor.run(macro)
        assertThat(executor.running.value).contains("m3")
        executor.cancel("m3")
        advanceUntilIdle()
        assertThat(executor.running.value).doesNotContain("m3")
    }
}
