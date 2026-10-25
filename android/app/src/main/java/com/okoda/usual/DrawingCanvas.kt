package com.okoda.usual

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path as NativePath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

private data class DrawStroke(val points: List<Offset>, val color: Int, val width: Float)

@Composable
fun DrawingCanvas(onPreview: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<DrawStroke>() }
    var color by remember { mutableStateOf(0xff4c3a69.toInt()) }
    var pen by remember { mutableStateOf(8f) }
    var eraser by remember { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var rendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column {
        Row {
            listOf("Ink" to 0xff4c3a69.toInt(), "Red" to 0xffbe4b60.toInt(), "Blue" to 0xff397eb0.toInt(), "Green" to 0xff47756e.toInt()).forEach { (name, value) ->
                TextButton(onClick = { color = value; eraser = false }) { Text(name) }
            }
        }
        Text(if (eraser) "Eraser" else "Pen")
        Slider(value = pen, onValueChange = { pen = it }, valueRange = 2f..32f)
        Row {
            TextButton(onClick = { eraser = !eraser }) { Text(if (eraser) "Use pen" else "Eraser") }
            TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Undo") }
            TextButton(onClick = { strokes.clear() }) { Text("Clear") }
        }
        Canvas(Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f).align(Alignment.CenterHorizontally).semantics { contentDescription = "Drawing canvas" }.background(Color.White).onSizeChanged { size = it }
            .pointerInput(color, pen, eraser, size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (strokes.size >= 500) return@awaitEachGesture
                    fun normalized(point: Offset) = Offset((point.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f), (point.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                    val index = strokes.size
                    strokes.add(DrawStroke(listOf(normalized(down.position)), if (eraser) android.graphics.Color.WHITE else color, pen / size.width.coerceAtLeast(1)))
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed && (strokes.getOrNull(index)?.points?.size ?: 0) < 2048) {
                            strokes[index] = strokes[index].copy(points = strokes[index].points + normalized(change.position))
                        }
                        change.consume()
                    } while (change.pressed)
                }
            }) {
            for (stroke in strokes) {
                if (stroke.points.size == 1) drawCircle(Color(stroke.color), stroke.width * this.size.width / 2, Offset(stroke.points[0].x * this.size.width, stroke.points[0].y * this.size.height))
                else {
                    val path = Path()
                    stroke.points.forEachIndexed { index, point ->
                        if (index == 0) path.moveTo(point.x * this.size.width, point.y * this.size.height)
                        else path.lineTo(point.x * this.size.width, point.y * this.size.height)
                    }
                    drawPath(path, Color(stroke.color), style = Stroke(stroke.width * this.size.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        error?.let { Text(it) }
        Button(enabled = !rendering && strokes.isNotEmpty(), onClick = {
            val snapshot = strokes.toList()
            rendering = true
            scope.launch {
                try {
                    val file = withContext(Dispatchers.Default) {
                        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
                        val canvas = NativeCanvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        for (stroke in snapshot) {
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = stroke.color; strokeWidth = stroke.width * 1024; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
                            val path = NativePath()
                            stroke.points.forEachIndexed { index, point -> if (index == 0) path.moveTo(point.x * 1024, point.y * 1024) else path.lineTo(point.x * 1024, point.y * 1024) }
                            if (stroke.points.size == 1) { paint.style = Paint.Style.FILL; canvas.drawCircle(stroke.points[0].x * 1024, stroke.points[0].y * 1024, paint.strokeWidth / 2, paint) }
                            else canvas.drawPath(path, paint)
                        }
                        File(context.cacheDir, "drawing-" + UUID.randomUUID() + ".png").also { output -> output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
                    }
                    onPreview(file)
                } catch (_: Exception) { error = "Couldn’t create the preview. Please retry." }
                finally { rendering = false }
            }
        }) { Text("Preview drawing") }
    }
}
