package org.benesv.history.ui

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter

/**
 * A custom Painter that draws the Browser History icon.
 * This allows it to be used in Image() or Icon() components.
 */
class BrowserHistoryIconPainter(
    private val backgroundColor: Color = Color.Transparent,
    private val iconColor: Color = Color.White
) : Painter() {

    // Define a default intrinsic size (square aspect ratio)
    // This allows the layout to know the preferred size if not specified.
    override val intrinsicSize: Size = Size(256f, 256f)

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        // Scale stroke relative to the size of the container
        val strokeWidth = w * 0.08f

        // 1. Draw Background (Squircle) - only if not transparent
        if (backgroundColor.alpha != 0f) {
            drawRoundRect(
                color = backgroundColor,
                cornerRadius = CornerRadius(w * 0.22f, h * 0.22f),
                size = size
            )
        }

        // 2. Draw the "C" Arc
        val cPadding = w * 0.25f
        val cSize = w - (cPadding * 2)

        drawArc(
            color = iconColor,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(cPadding, cPadding),
            size = Size(cSize, cSize),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // 3. Draw the Clock Icon (Nested)
        val clockRadius = w * 0.15f
        val clockCenter = Offset(w * 0.72f, h * 0.5f)

        // Clock Circle
        drawCircle(
            color = iconColor,
            center = clockCenter,
            radius = clockRadius,
            style = Stroke(width = strokeWidth * 0.6f)
        )

        // Clock Hands (9 o'clock and 12 o'clock)
        // Vertical Hand
        drawLine(
            color = iconColor,
            start = clockCenter,
            end = Offset(clockCenter.x, clockCenter.y - (clockRadius * 0.5f)),
            strokeWidth = strokeWidth * 0.6f,
            cap = StrokeCap.Round
        )
        // Horizontal Hand
        drawLine(
            color = iconColor,
            start = clockCenter,
            end = Offset(clockCenter.x - (clockRadius * 0.5f), clockCenter.y),
            strokeWidth = strokeWidth * 0.6f,
            cap = StrokeCap.Round
        )

        // 4. Arrow Tip (Visual detail for "History")
        val arrowTipX = clockCenter.x - clockRadius
        val arrowTipY = clockCenter.y
        val arrowSize = strokeWidth * 0.5f

        // Upper part of arrow chevron
        drawLine(
            color = iconColor,
            start = Offset(arrowTipX + arrowSize, arrowTipY - arrowSize),
            end = Offset(arrowTipX - (arrowSize * 0.2f), arrowTipY),
            strokeWidth = strokeWidth * 0.5f,
            cap = StrokeCap.Round
        )
        // Lower part of arrow chevron
        drawLine(
            color = iconColor,
            start = Offset(arrowTipX + arrowSize, arrowTipY + arrowSize),
            end = Offset(arrowTipX - (arrowSize * 0.2f), arrowTipY),
            strokeWidth = strokeWidth * 0.5f,
            cap = StrokeCap.Round
        )
    }
}

@Preview
@Composable
fun rememberBrowserHistoryPainter(): Painter {
    return remember { BrowserHistoryIconPainter() }
}
