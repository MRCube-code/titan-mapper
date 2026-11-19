package com.titanmapper.service.engine

import com.google.common.truth.Truth.assertThat
import com.titanmapper.core.model.ActionMode
import com.titanmapper.core.model.Binding
import com.titanmapper.core.model.CaptureTier
import com.titanmapper.core.model.DeviceClass
import com.titanmapper.core.model.DeviceFingerprint
import com.titanmapper.core.model.InputTrigger
import com.titanmapper.core.model.KeyAction
import com.titanmapper.core.model.KeyCode
import com.titanmapper.core.model.MappedAction
import com.titanmapper.core.model.MappingProfile
import com.titanmapper.core.model.ModifierKey
import com.titanmapper.core.model.RawInputEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MappingEngineTest {

    private val device = DeviceFingerprint("dev1", 1, 1, "Test Keyboard", DeviceClass.KEYBOARD)
    private val dispatched = mutableListOf<Pair<MappedAction, DispatchPhase>>()

    private val sink = object : OutputDispatchSink {
        override val tier = CaptureTier.STANDARD
        override fun dispatch(action: MappedAction, phase: DispatchPhase): Boolean {
            dispatched += action to phase
            return true
        }
        override fun supports(action: MappedAction) = true
    }

    @Before
    fun setUp() {
        dispatched.clear()
    }

    private fun keyEvent(code: KeyCode, action: KeyAction, nanos: Long = System.nanoTime()) =
        RawInputEvent.Key(device, nanos, CaptureTier.STANDARD, code, action)

    private fun profile(vararg bindings: Binding) = MappingProfile(id = "p1", name = "Test", bindings = bindings.toList())

    @Test
    fun `tap mode fires once on down and nothing on up`() = runTest {
        val binding = Binding("b1", InputTrigger.SingleKey(KeyCode.A), MappedAction.KeyInjection(KeyCode.B, KeyAction.DOWN), ActionMode.TAP)
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(binding))

        engine.handle(keyEvent(KeyCode.A, KeyAction.DOWN))
        engine.handle(keyEvent(KeyCode.A, KeyAction.UP))

        assertThat(dispatched).hasSize(1)
        assertThat(dispatched[0].second).isEqualTo(DispatchPhase.ONCE)
    }

    @Test
    fun `hold mode fires down then up`() = runTest {
        val binding = Binding("b1", InputTrigger.SingleKey(KeyCode.W), MappedAction.KeyInjection(KeyCode.W, KeyAction.DOWN), ActionMode.HOLD)
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(binding))

        engine.handle(keyEvent(KeyCode.W, KeyAction.DOWN))
        assertThat(dispatched).hasSize(1)
        assertThat(dispatched[0].second).isEqualTo(DispatchPhase.DOWN)

        engine.handle(keyEvent(KeyCode.W, KeyAction.UP))
        assertThat(dispatched).hasSize(2)
        assertThat(dispatched[1].second).isEqualTo(DispatchPhase.UP)
    }

    @Test
    fun `toggle mode alternates on and off without needing key-up`() = runTest {
        val binding = Binding("b1", InputTrigger.SingleKey(KeyCode.CAPS_LOCK), MappedAction.KeyInjection(KeyCode.CAPS_LOCK, KeyAction.DOWN), ActionMode.TOGGLE)
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(binding))

        engine.handle(keyEvent(KeyCode.CAPS_LOCK, KeyAction.DOWN))
        engine.handle(keyEvent(KeyCode.CAPS_LOCK, KeyAction.UP))
        assertThat(dispatched.last().second).isEqualTo(DispatchPhase.DOWN)

        engine.handle(keyEvent(KeyCode.CAPS_LOCK, KeyAction.DOWN))
        engine.handle(keyEvent(KeyCode.CAPS_LOCK, KeyAction.UP))
        assertThat(dispatched.last().second).isEqualTo(DispatchPhase.UP)
    }

    @Test
    fun `repeat mode fires on down and again on each interval while held`() = runTest {
        val binding = Binding(
            "b1", InputTrigger.SingleKey(KeyCode.SPACE),
            MappedAction.KeyInjection(KeyCode.SPACE, KeyAction.DOWN), ActionMode.REPEAT, repeatIntervalMillis = 50
        )
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(binding))

        engine.handle(keyEvent(KeyCode.SPACE, KeyAction.DOWN))
        assertThat(dispatched).hasSize(1) // immediate fire

        advanceTimeBy(170) // ~3 more intervals
        assertThat(dispatched.size).isAtLeast(3)

        engine.handle(keyEvent(KeyCode.SPACE, KeyAction.UP))
        val countAfterUp = dispatched.size
        advanceTimeBy(200)
        assertThat(dispatched.size).isEqualTo(countAfterUp) // repeat job was cancelled on key-up
    }

    @Test
    fun `combo with more modifiers takes precedence over fewer`() = runTest {
        val shiftA = Binding("shiftA", InputTrigger.Combo(setOf(ModifierKey.SHIFT), KeyCode.A), MappedAction.KeyInjection(KeyCode.DIGIT_1, KeyAction.DOWN), ActionMode.TAP)
        val ctrlShiftA = Binding("ctrlShiftA", InputTrigger.Combo(setOf(ModifierKey.SHIFT, ModifierKey.CTRL), KeyCode.A), MappedAction.KeyInjection(KeyCode.DIGIT_2, KeyAction.DOWN), ActionMode.TAP)
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(shiftA, ctrlShiftA))

        engine.handle(keyEvent(KeyCode.SHIFT_LEFT, KeyAction.DOWN))
        engine.handle(keyEvent(KeyCode.CTRL_LEFT, KeyAction.DOWN))
        engine.handle(keyEvent(KeyCode.A, KeyAction.DOWN))

        assertThat((dispatched.single().first as MappedAction.KeyInjection).key).isEqualTo(KeyCode.DIGIT_2)
    }

    @Test
    fun `auto-repeat key-down from the OS does not retrigger a tap binding`() = runTest {
        val binding = Binding("b1", InputTrigger.SingleKey(KeyCode.A), MappedAction.KeyInjection(KeyCode.A, KeyAction.DOWN), ActionMode.TAP)
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(binding))

        engine.handle(keyEvent(KeyCode.A, KeyAction.DOWN))
        engine.handle(keyEvent(KeyCode.A, KeyAction.DOWN)) // OS auto-repeat, same key, no UP in between
        engine.handle(keyEvent(KeyCode.A, KeyAction.DOWN))

        assertThat(dispatched).hasSize(1)
    }

    @Test
    fun `releaseAll releases held and toggled bindings and cancels repeats`() = runTest {
        val hold = Binding("hold", InputTrigger.SingleKey(KeyCode.W), MappedAction.KeyInjection(KeyCode.W, KeyAction.DOWN), ActionMode.HOLD)
        val toggle = Binding("toggle", InputTrigger.SingleKey(KeyCode.CAPS_LOCK), MappedAction.KeyInjection(KeyCode.CAPS_LOCK, KeyAction.DOWN), ActionMode.TOGGLE)
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(hold, toggle))

        engine.handle(keyEvent(KeyCode.W, KeyAction.DOWN)) // held, never released
        engine.handle(keyEvent(KeyCode.CAPS_LOCK, KeyAction.DOWN))
        engine.handle(keyEvent(KeyCode.CAPS_LOCK, KeyAction.UP)) // toggled on, stays on

        dispatched.clear()
        engine.releaseAll()

        assertThat(dispatched).hasSize(2)
        assertThat(dispatched.all { it.second == DispatchPhase.UP }).isTrue()
    }

    @Test
    fun `sequence trigger fires only after the full ordered chord within the time window`() = runTest {
        val binding = Binding("seq", InputTrigger.Sequence(listOf(KeyCode.G, KeyCode.G), maxGapMillis = 400), MappedAction.KeyInjection(KeyCode.ESCAPE, KeyAction.DOWN), ActionMode.TAP)
        val engine = MappingEngine(sink, this)
        engine.setProfile(profile(binding))

        engine.handle(keyEvent(KeyCode.G, KeyAction.DOWN, nanos = 0))
        engine.handle(keyEvent(KeyCode.G, KeyAction.UP, nanos = 10_000_000))
        assertThat(dispatched).isEmpty()

        engine.handle(keyEvent(KeyCode.G, KeyAction.DOWN, nanos = 20_000_000))
        assertThat(dispatched).hasSize(1)
    }
}
