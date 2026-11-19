package com.titanmapper.feature.macro

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.repository.MacroRepository
import com.titanmapper.core.model.KeyCode
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.core.model.MacroStep
import com.titanmapper.core.model.MacroValidator
import com.titanmapper.core.model.MouseButton
import com.titanmapper.service.api.MacroRecorder
import com.titanmapper.service.api.RecorderState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MacroEditorUiState(
    val macro: MacroDefinition? = null,
    val recorder: RecorderState = RecorderState(),
    val validationErrors: List<String> = emptyList(),
    val saveError: String? = null,
    val isDirty: Boolean = false
)

sealed interface MacroEditorUiEvent {
    data class Rename(val name: String) : MacroEditorUiEvent
    data class SetGlobal(val global: Boolean) : MacroEditorUiEvent
    /** Replace the entire flat step list (after user drag-reorder). */
    data class ReorderSteps(val steps: List<MacroStep>) : MacroEditorUiEvent
    data class AddStep(val step: MacroStep) : MacroEditorUiEvent
    data class RemoveStep(val index: Int) : MacroEditorUiEvent
    data class UpdateStep(val index: Int, val step: MacroStep) : MacroEditorUiEvent
    data object StartRecording : MacroEditorUiEvent
    data object StopRecording : MacroEditorUiEvent
    data object Save : MacroEditorUiEvent
    data object DismissError : MacroEditorUiEvent
}

@HiltViewModel
class MacroEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MacroRepository,
    private val recorder: MacroRecorder
) : ViewModel() {

    private val macroId: String = checkNotNull(savedStateHandle["macroId"])
    private val localMacro = MutableStateFlow<MacroDefinition?>(null)
    private val saveError = MutableStateFlow<String?>(null)
    private val isDirty = MutableStateFlow(false)

    val uiState: StateFlow<MacroEditorUiState> = combine(
        localMacro, recorder.state, saveError, isDirty
    ) { macro, recState, error, dirty ->
        MacroEditorUiState(
            macro = macro,
            recorder = recState,
            validationErrors = macro?.let { MacroValidator.validate(it) } ?: emptyList(),
            saveError = error,
            isDirty = dirty
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MacroEditorUiState())

    init {
        viewModelScope.launch {
            localMacro.value = repository.get(macroId)
        }
    }

    fun onEvent(event: MacroEditorUiEvent) {
        when (event) {
            is MacroEditorUiEvent.Rename -> mutateMacro { it.copy(name = event.name) }
            is MacroEditorUiEvent.SetGlobal -> mutateMacro { it.copy(isGlobal = event.global) }
            is MacroEditorUiEvent.ReorderSteps -> mutateMacro { it.copy(steps = event.steps) }
            is MacroEditorUiEvent.AddStep -> mutateMacro { it.copy(steps = it.steps + event.step) }
            is MacroEditorUiEvent.RemoveStep -> mutateMacro { m ->
                m.copy(steps = m.steps.toMutableList().also { it.removeAt(event.index) })
            }
            is MacroEditorUiEvent.UpdateStep -> mutateMacro { m ->
                m.copy(steps = m.steps.toMutableList().also { it[event.index] = event.step })
            }
            MacroEditorUiEvent.StartRecording -> recorder.start()
            MacroEditorUiEvent.StopRecording -> viewModelScope.launch {
                val recorded = recorder.stop()
                if (recorded.isNotEmpty()) {
                    mutateMacro { it.copy(steps = it.steps + recorded) }
                }
            }
            MacroEditorUiEvent.Save -> save()
            MacroEditorUiEvent.DismissError -> saveError.value = null
        }
    }

    private fun mutateMacro(transform: (MacroDefinition) -> MacroDefinition) {
        val current = localMacro.value ?: return
        localMacro.value = transform(current)
        isDirty.value = true
    }

    private fun save() {
        val macro = localMacro.value ?: return
        viewModelScope.launch {
            when (val result = repository.save(macro)) {
                is DataResult.Success -> isDirty.value = false
                is DataResult.Error -> saveError.value = result.message
            }
        }
    }
}
