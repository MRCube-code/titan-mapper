package com.titanmapper.feature.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titanmapper.core.model.OverlayControl
import com.titanmapper.core.model.OverlayControlType
import com.titanmapper.core.model.ScreenOrientation
import com.titanmapper.core.ui.components.LabeledSlider
import com.titanmapper.core.ui.theme.TitanSpacing
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayEditorRoute(
    profileId: String,
    onBack: () -> Unit,
    viewModel: OverlayEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddControl by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Overlay Editor") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { viewModel.onEvent(OverlayEditorUiEvent.SaveLayout) }) {
                        Icon(Icons.Filled.Save, "Save")
                    }
                    IconButton(onClick = { showAddControl = true }) {
                        Icon(Icons.Filled.Add, "Add control")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Orientation tabs
            Row(modifier = Modifier.padding(horizontal = TitanSpacing.l), horizontalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                FilterChip(
                    selected = state.activeOrientation == ScreenOrientation.PORTRAIT,
                    onClick = { viewModel.onEvent(OverlayEditorUiEvent.SwitchOrientation(ScreenOrientation.PORTRAIT)) },
                    label = { Text("Portrait") }
                )
                FilterChip(
                    selected = state.activeOrientation == ScreenOrientation.LANDSCAPE,
                    onClick = { viewModel.onEvent(OverlayEditorUiEvent.SwitchOrientation(ScreenOrientation.LANDSCAPE)) },
                    label = { Text("Landscape") }
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Snap", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(TitanSpacing.xs))
                    Switch(
                        checked = state.activeLayout.snapToGrid,
                        onCheckedChange = { viewModel.onEvent(OverlayEditorUiEvent.SetSnapToGrid(it)) }
                    )
                }
            }

            // Canvas editor
            Box(modifier = Modifier.weight(1f).padding(TitanSpacing.l)) {
                OverlayCanvas(
                    layout = state.activeLayout,
                    selectedId = state.selectedControlId,
                    onSelect = { viewModel.onEvent(OverlayEditorUiEvent.SelectControl(it)) },
                    onMove = { id, x, y -> viewModel.onEvent(OverlayEditorUiEvent.MoveControl(id, x, y)) }
                )
            }

            // Properties panel for selected control
            state.selectedControl?.let { control ->
                ControlPropertiesPanel(
                    control = control,
                    onAlphaChange = { viewModel.onEvent(OverlayEditorUiEvent.SetAlpha(control.id, it)) },
                    onDelete = { viewModel.onEvent(OverlayEditorUiEvent.DeleteControl(control.id)) }
                )
            }
        }
    }

    if (showAddControl) {
        AddControlDialog(
            onDismiss = { showAddControl = false },
            onAdd = { type, label ->
                viewModel.onEvent(OverlayEditorUiEvent.AddControl(type, label))
                showAddControl = false
            }
        )
    }

    state.saveError?.let { err ->
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(OverlayEditorUiEvent.DismissError) },
            title = { Text("Save error") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { viewModel.onEvent(OverlayEditorUiEvent.DismissError) }) { Text("OK") } }
        )
    }
}

@Composable
private fun OverlayCanvas(
    layout: com.titanmapper.core.model.OverlayLayout,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onMove: (String, Float, Float) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        val canvasWidthPx = constraints.maxWidth.toFloat()
        val canvasHeightPx = constraints.maxHeight.toFloat()

        // Draw grid
        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cols = layout.gridColumns
            val rows = (cols * (canvasHeightPx / canvasWidthPx)).roundToInt()
            val stepX = canvasWidthPx / cols
            val stepY = canvasHeightPx / rows
            for (i in 1 until cols) drawLine(gridColor, Offset(i * stepX, 0f), Offset(i * stepX, canvasHeightPx), 0.5f)
            for (j in 1 until rows) drawLine(gridColor, Offset(0f, j * stepY), Offset(canvasWidthPx, j * stepY), 0.5f)
        }

        // Render controls
        layout.controls.forEach { control ->
            val selected = control.id == selectedId
            val widthPx = (control.width * canvasWidthPx).roundToInt()
            val heightPx = (control.height * canvasHeightPx).roundToInt()
            val leftPx = (control.centerX * canvasWidthPx - widthPx / 2f).roundToInt()
            val topPx = (control.centerY * canvasHeightPx - heightPx / 2f).roundToInt()

            Box(
                modifier = Modifier
                    .offset { IntOffset(leftPx, topPx) }
                    .size(with(LocalDensity.current) { widthPx.toDp() }, with(LocalDensity.current) { heightPx.toDp() })
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = control.alpha * 0.5f),
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(4.dp)
                    )
                    .pointerInput(control.id) {
                        detectDragGestures(
                            onDragStart = { onSelect(control.id) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val newCx = (control.centerX + dragAmount.x / canvasWidthPx).coerceIn(0f, 1f)
                                val newCy = (control.centerY + dragAmount.y / canvasHeightPx).coerceIn(0f, 1f)
                                onMove(control.id, newCx, newCy)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    control.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

@Composable
private fun ControlPropertiesPanel(control: OverlayControl, onAlphaChange: (Float) -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(TitanSpacing.l)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(control.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(control.type.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete control") }
        }
        LabeledSlider(
            label = "Opacity",
            value = control.alpha,
            onValueChange = onAlphaChange,
            valueRange = 0.1f..1f,
            valueText = "${(control.alpha * 100).roundToInt()}%"
        )
        Text(
            "Position: (${(control.centerX * 100).roundToInt()}%, ${(control.centerY * 100).roundToInt()}%)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddControlDialog(onDismiss: () -> Unit, onAdd: (OverlayControlType, String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add control") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(TitanSpacing.s)) {
                OverlayControlType.entries.forEach { type ->
                    FilterChip(
                        selected = false,
                        onClick = { onAdd(type, type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        label = { Text(type.name) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
