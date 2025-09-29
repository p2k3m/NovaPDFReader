package com.novapdf.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PointSnapshot
import com.novapdf.reader.model.toOffset
import kotlinx.coroutines.launch

@Composable
fun AnnotationOverlay(
    modifier: Modifier = Modifier,
    pageIndex: Int,
    annotations: List<AnnotationCommand.Stroke>,
    onStrokeComplete: (List<Offset>) -> Unit,
    strokeColor: Color = Color(0xFFFF4081),
    strokeWidth: Float = 4f,
    enabled: Boolean = true,
    contentDescription: String? = null
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val currentStroke = remember { mutableStateOf<List<Offset>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    val updatedOnComplete = rememberUpdatedState(onStrokeComplete)

    LaunchedEffect(annotations, pageIndex) {
        strokes.clear()
        annotations.filter { it.pageIndex == pageIndex }.forEach { stroke ->
            strokes += stroke.points.map(PointSnapshot::toOffset)
        }
    }

    val drawingModifier = if (enabled) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                currentStroke.value = listOf(down.position)
                drag(down.id) { change ->
                    currentStroke.value = currentStroke.value + change.position
                }
                val completedStroke = currentStroke.value
                currentStroke.value = emptyList()
                if (completedStroke.size > 1) {
                    coroutineScope.launch {
                        updatedOnComplete.value.invoke(completedStroke)
                    }
                    strokes += completedStroke
                }
            }
        }
    } else {
        Modifier
    }

    val semanticsModifier = if (!contentDescription.isNullOrBlank()) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .then(drawingModifier)
            .then(semanticsModifier)
    ) {
        strokes.forEach { stroke ->
            drawStrokePath(stroke, strokeColor, strokeWidth)
        }
        val liveStroke = currentStroke.value
        if (liveStroke.size > 1) {
            drawStrokePath(liveStroke, strokeColor.copy(alpha = 0.6f), strokeWidth)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokePath(
    points: List<Offset>,
    color: Color,
    width: Float
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { point ->
            lineTo(point.x, point.y)
        }
    }
    drawPath(path = path, color = color, style = Stroke(width))
}
