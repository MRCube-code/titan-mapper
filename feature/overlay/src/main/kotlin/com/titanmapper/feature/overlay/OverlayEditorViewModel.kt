package com.titanmapper.feature.overlay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.repository.OverlayRepository
import com.titanmapper.core.model.GridSnap
import com.titanmapper.core.model.OverlayControl
import com.titanmapper.core.model.OverlayControlType
import com.titanmapper.core.model.OverlayLayout
import com.titanmapper.core.model.ScreenOrientation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class OverlayEditorUiState(
    val portrait: OverlayLayout = OverlayLayout("", ScreenOrientation.PORTRAIT),
    val landscape: OverlayLayout = OverlayLayout("", ScreenOrientation.LANDSCAPE),
    val activeOrientation: ScreenOrientation = ScreenOrientation.PORTRAIT,
    val selectedControlId: String? = null,
    val saveError: String? = null
) {
    val activeLayout: OverlayLayout get() = if (activeOrientation == ScreenOrientation.PORTRAIT) portrait else landscape
    val selectedControl: OverlayControl? get() = activeLayout.controls.find { it.id == selectedControlId }
}

sealed interface OverlayEditorUiEvent {
    data class SwitchOrientation(val orientation: ScreenOrientation) : OverlayEditorUiEvent
    data class SelectControl(val id: String?) : OverlayEditorUiEvent
    data class MoveControl(val id: String, val centerX: Float, val centerY: Float) : OverlayEditorUiEvent
    data class ResizeControl(val id: String, val width: Float, val height: Float) : OverlayEditorUiEvent
    data class SetAlpha(val id: String, val alpha: Float) : OverlayEditorUiEvent
    data class AddControl(val type: OverlayControlType, val label: String) : OverlayEditorUiEvent
    data class DeleteControl(val id: String) : OverlayEditorUiEvent
    data class SetSnapToGrid(val snap: Boolean) : OverlayEditorUiEvent
    data object SaveLayout : OverlayEditorUiEvent
    data object DismissError : OverlayEditorUiEvent
}

@HiltViewModel
class OverlayEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OverlayRepository
) : ViewModel() {

    private val profileId: String = checkNotNull(savedStateHandle["profileId"])
    private val activeOrientation = MutableStateFlow(ScreenOrientation.PORTRAIT)
    private val selectedId = MutableStateFlow<String?>(null)
    private val saveError = MutableStateFlow<String?>(null)

    // We keep local mutable copies of both orientations so edits are instant (no round-trip to DB).
    private val portraitLayout = MutableStateFlow(OverlayLayout.empty(profileId, ScreenOrientation.PORTRAIT))
    private val landscapeLayout = MutableStateFlow(OverlayLayout.empty(profileId, ScreenOrientation.LANDSCAPE))

    val uiState: StateFlow<OverlayEditorUiState> = combine(
        portraitLayout, landscapeLayout, activeOrientation, selectedId, saveError
    ) { portrait, landscape, orientation, selectedId, error ->
        OverlayEditorUiState(portrait, landscape, orientation, selectedId, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverlayEditorUiState())

    init {
        // Load both orientations from DB once on creation.
        viewModelScope.launch {
            repository.observe(profileId, ScreenOrientation.PORTRAIT).collect { portraitLayout.value = it }
        }
        viewModelScope.launch {
            repository.observe(profileId, ScreenOrientation.LANDSCAPE).collect { landscapeLayout.value = it }
        }
    }

    fun onEvent(event: OverlayEditorUiEvent) {
        when (event) {
            is OverlayEditorUiEvent.SwitchOrientation -> {
                activeOrientation.value = event.orientation
                selectedId.value = null
            }
            is OverlayEditorUiEvent.SelectControl -> selectedId.value = event.id
            is OverlayEditorUiEvent.MoveControl -> mutateActive { layout ->
                layout.copy(controls = layout.controls.map { c ->
                    if (c.id != event.id) c
                    else maybeSnap(c.copy(centerX = event.centerX, centerY = event.centerY), layout)
                })
            }
            is OverlayEditorUiEvent.ResizeControl -> mutateActive { layout ->
                layout.copy(controls = layout.controls.map { c ->
                    if (c.id != event.id) c
                    else maybeSnap(c.copy(width = event.width, height = event.height), layout)
                })
            }
            is OverlayEditorUiEvent.SetAlpha -> mutateActive { layout ->
                layout.copy(controls = layout.controls.map { c ->
                    if (c.id != event.id) c else c.copy(alpha = event.alpha.coerceIn(0.1f, 1f))
                })
            }
            is OverlayEditorUiEvent.AddControl -> {
                val newControl = OverlayControl(
                    id = UUID.randomUUID().toString(),
                    type = event.type,
                    label = event.label,
                    centerX = 0.5f,
                    centerY = 0.5f
                ).clamped()
                mutateActive { it.copy(controls = it.controls + newControl) }
                selectedId.value = newControl.id
            }
            is OverlayEditorUiEvent.DeleteControl -> {
                mutateActive { it.copy(controls = it.controls.filterNot { c -> c.id == event.id }) }
                if (selectedId.value == event.id) selectedId.value = null
            }
            is OverlayEditorUiEvent.SetSnapToGrid -> mutateActive { it.copy(snapToGrid = event.snap) }
            OverlayEditorUiEvent.SaveLayout -> save()
            OverlayEditorUiEvent.DismissError -> saveError.value = null
        }
    }

    private fun mutateActive(transform: (OverlayLayout) -> OverlayLayout) {
        if (activeOrientation.value == ScreenOrientation.PORTRAIT) {
            portraitLayout.value = transform(portraitLayout.value)
        } else {
            landscapeLayout.value = transform(landscapeLayout.value)
        }
    }

    private fun maybeSnap(control: OverlayControl, layout: OverlayLayout): OverlayControl =
        if (layout.snapToGrid) GridSnap.snapControl(control, layout.gridColumns) else control.clamped()

    private fun save() {
        viewModelScope.launch {
            val p = repository.save(portraitLayout.value)
            val l = repository.save(landscapeLayout.value)
            val err = listOf(p, l).filterIsInstance<DataResult.Error>().firstOrNull()
            saveError.value = err?.message
        }
    }
}
