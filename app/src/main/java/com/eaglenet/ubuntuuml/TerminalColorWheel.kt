package com.eaglenet.ubuntuuml

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.min

@Composable
internal fun TerminalColorWheelDialog(
    title: String,
    initial: Color,
    onCancel: () -> Unit,
    onPick: (Color) -> Unit,
) {
    val hsv = remember(initial) { FloatArray(3).also { AndroidColor.colorToHSV(initial.toArgb(), it) } }
    var hue by remember(initial) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(hsv[1]) }
    var brightness by remember(initial) { mutableFloatStateOf(hsv[2]) }
    val selected = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            Column {
                fun chooseHue(pos: Offset, width: Float, height: Float) {
                    val angle = Math.toDegrees(atan2((pos.y - height / 2).toDouble(), (pos.x - width / 2).toDouble()))
                    hue = ((angle + 90 + 360) % 360).toFloat()
                }
                Canvas(
                    Modifier.fillMaxWidth().height(214.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { point -> chooseHue(point, size.width.toFloat(), size.height.toFloat()) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                chooseHue(change.position, size.width.toFloat(), size.height.toFloat())
                            }
                        }
                ) {
                    val width = min(size.width, size.height)
                    val topLeft = Offset((size.width - width) / 2f, (size.height - width) / 2f)
                    for (index in 0 until 360 step 3) {
                        drawArc(
                            Color(AndroidColor.HSVToColor(floatArrayOf(index.toFloat(), 1f, 1f))),
                            startAngle = index.toFloat() - 90,
                            sweepAngle = 3.5f,
                            useCenter = false,
                            topLeft = topLeft + Offset(14.dp.toPx(), 14.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(width - 28.dp.toPx(), width - 28.dp.toPx()),
                            style = Stroke(width = 20.dp.toPx()),
                        )
                    }
                    drawCircle(selected, radius = width * .25f)
                }
                Text("Saturation")
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
                Text("Brightness")
                Slider(value = brightness, onValueChange = { brightness = it }, valueRange = 0f..1f)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(32.dp).background(selected, CircleShape))
            }
        },
        confirmButton = { TextButton(onClick = { onPick(selected) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
