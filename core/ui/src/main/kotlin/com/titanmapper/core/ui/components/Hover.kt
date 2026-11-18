package com.titanmapper.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

/**
 * Desktop-style affordance: a thin outline while a mouse pointer hovers, and a thicker focus
 * ring when this element (or a child) has keyboard focus, so keyboard and mouse navigation are
 * both visible.
 */
fun Modifier.hoverHighlight(): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val shape = MaterialTheme.shapes.medium
    val base = this
        .onFocusChanged { focused = it.hasFocus }
        .hoverable(interactionSource)
    when {
        focused -> base.border(2.dp, color, shape)
        hovered -> base.border(1.dp, color.copy(alpha = 0.6f), shape)
        else -> base
    }
}
