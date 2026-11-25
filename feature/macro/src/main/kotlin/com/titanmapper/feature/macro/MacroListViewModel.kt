package com.titanmapper.feature.macro

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titanmapper.core.common.DataResult
import com.titanmapper.core.data.repository.MacroRepository
import com.titanmapper.core.data.transfer.ProfileTransfer
import com.titanmapper.core.model.MacroDefinition
import com.titanmapper.service.api.MacroRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class MacroListUiState(
    val macros: List<MacroDefinition> = emptyList(),
    val running: Set<String> = emptySet(),
    val message: String? = null
)

sealed interface MacroListUiEvent {
    data object CreateMacro : MacroListUiEvent
    data class Duplicate(val id: String) : MacroListUiEvent
    data class Delete(val id: String) : MacroListUiEvent
    data class Run(val id: String) : MacroListUiEvent
    data class Cancel(val id: String) : MacroListUiEvent
    data class ExportOne(val id: String, val writeText: suspend (String) -> Unit) : MacroListUiEvent
    data class ImportFrom(val text: String) : MacroListUiEvent
    data object DismissMessage : MacroListUiEvent
}

@HiltViewModel
class MacroListViewModel @Inject constructor(
    private val repository: MacroRepository,
    private val runner: MacroRunner,
    private val transfer: ProfileTransfer
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    /**
     * Set once the newly-created macro has actually been persisted, so the screen can react to it
     * with a [StateFlow] collector instead of racing it. This used to be a plain `var` set at the
     * end of the `CreateMacro` coroutine, read from a `LaunchedEffect` that was triggered by a
     * separate boolean flipped synchronously on click -- since `repository.save()` is a suspend DB
     * write, that `LaunchedEffect` ran and read the still-null `var` before the save ever
     * completed, so tapping "New macro" silently created the macro but never navigated to its
     * editor. A `StateFlow` fixes this: the screen now collects it directly, so Compose reacts
     * exactly when the value actually changes, however long the save takes.
     */
    private val _navigateToMacroId = MutableStateFlow<String?>(null)
    val navigateToMacroId: StateFlow<String?> get() = _navigateToMacroId

    val uiState: StateFlow<MacroListUiState> = combine(repository.observeMacros(), runner.running, message) { macros, running, msg ->
        MacroListUiState(macros, running, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MacroListUiState())

    fun onNavigationHandled() {
        _navigateToMacroId.value = null
    }

    fun onEvent(event: MacroListUiEvent) {
        when (event) {
            MacroListUiEvent.CreateMacro -> viewModelScope.launch {
                val macro = MacroDefinition(id = UUID.randomUUID().toString(), name = "New macro", steps = emptyList())
                repository.save(macro)
                _navigateToMacroId.value = macro.id
            }
            is MacroListUiEvent.Duplicate -> viewModelScope.launch {
                val source = repository.get(event.id) ?: return@launch
                repository.save(source.copy(id = UUID.randomUUID().toString(), name = "${source.name} (copy)"))
            }
            is MacroListUiEvent.Delete -> viewModelScope.launch { repository.delete(event.id) }
            is MacroListUiEvent.Run -> viewModelScope.launch { repository.get(event.id)?.let(runner::run) }
            is MacroListUiEvent.Cancel -> runner.cancel(event.id)
            is MacroListUiEvent.ExportOne -> viewModelScope.launch {
                val macro = repository.get(event.id) ?: return@launch
                val export = com.titanmapper.core.model.TitanExport(createdAtMillis = System.currentTimeMillis(), macros = listOf(macro))
                event.writeText(transfer.encode(export))
            }
            is MacroListUiEvent.ImportFrom -> viewModelScope.launch {
                when (val decoded = transfer.decode(event.text)) {
                    is DataResult.Error -> message.value = decoded.message
                    is DataResult.Success -> when (val imported = transfer.import(decoded.value)) {
                        is DataResult.Error -> message.value = imported.message
                        is DataResult.Success -> message.value = "Imported ${imported.value.macros} macro(s)"
                    }
                }
            }
            MacroListUiEvent.DismissMessage -> message.value = null
        }
    }
}
