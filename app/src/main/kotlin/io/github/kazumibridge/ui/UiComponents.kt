package io.github.kazumibridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable internal fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Normal)
        subtitle?.let {
            Text(it, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

internal fun megabytes(bytes: Long) = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024))

internal object BridgeIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).apply(block).build()

    val Home = icon("Home") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 10.5f); lineTo(12f, 3f); lineTo(21f, 10.5f)
            moveTo(5f, 9f); lineTo(5f, 20f); lineTo(9f, 20f); lineTo(9f, 14f)
            lineTo(15f, 14f); lineTo(15f, 20f); lineTo(19f, 20f); lineTo(19f, 9f)
        }
    }
    val Goggles = icon("Goggles") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 6f); lineTo(21f, 6f); lineTo(20f, 17f); lineTo(15f, 17f)
            lineTo(13f, 14f); lineTo(11f, 14f); lineTo(9f, 17f); lineTo(4f, 17f); close()
            moveTo(1f, 10f); lineTo(3f, 10f); moveTo(21f, 10f); lineTo(23f, 10f)
        }
    }
    val Settings = icon("Settings") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.65f, strokeLineJoin = StrokeJoin.Round) {
            // Eight identical teeth around a concentric bore.
            repeat(8) { tooth ->
                val offsets = listOf(-22.5 to 7.25, -12.0 to 7.25, -9.0 to 9.5, 9.0 to 9.5, 12.0 to 7.25)
                offsets.forEachIndexed { point, (offset, radius) ->
                    val angle = Math.toRadians(tooth * 45.0 - 90.0 + offset)
                    val x = (12.0 + cos(angle) * radius).toFloat()
                    val y = (12.0 + sin(angle) * radius).toFloat()
                    if (tooth == 0 && point == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            moveTo(15.5f, 12f)
            curveTo(15.5f, 14f, 14f, 15.5f, 12f, 15.5f)
            curveTo(10f, 15.5f, 8.5f, 14f, 8.5f, 12f)
            curveTo(8.5f, 10f, 10f, 8.5f, 12f, 8.5f)
            curveTo(14f, 8.5f, 15.5f, 10f, 15.5f, 12f); close()
        }
    }
    val Info = icon("Info") {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
            moveTo(21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
            moveTo(12f, 11f); lineTo(12f, 17f)
            moveTo(12f, 7f); lineTo(12f, 7.2f)
        }
    }
}
