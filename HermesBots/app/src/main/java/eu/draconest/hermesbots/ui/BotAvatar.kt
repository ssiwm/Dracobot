package eu.draconest.hermesbots.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blob avatar 1:1 z desktopowego Bot Mode (NousResearch/Hermes-Bot-Mode plugin.js):
 * - ksztalt deterministyczny z nazwy (hash*31), kolory z AVATAR_COLORS,
 * - ring probkowany wzorem: r = 16 + 1.7*sin(3a) + 0.7*cos(5a),
 * - oczy (elipsy + blyski), ciemne cialo -> jasne oczy.
 */

private val AVATAR_COLORS = listOf(
    0xFFF5F5F4, // white
    0xFF8D6748, // brown
    0xFFEF4444, // red
    0xFFF97316, // orange
    0xFF14B8A6, // teal
    0xFF38BDF8, // cyan
    0xFF3B40C8, // royal blue
    0xFF8B5CF6, // violet
    0xFFEC4899, // magenta
    0xFF9CA3AF  // silver
)

private val AVATAR_SHAPES = listOf("circle", "squircle", "pill", "triangle", "hexagon", "cloud", "drop")

private fun defaultShapeFor(name: String): String {
    var hash = 0L
    for (ch in name) hash = ((hash * 31) + ch.code) and 0xFFFFFFFFL
    return AVATAR_SHAPES[(hash % AVATAR_SHAPES.size).toInt()]
}

private fun colorFor(name: String): Color =
    Color(AVATAR_COLORS[abs(name.hashCode()) % AVATAR_COLORS.size])

private fun isDark(c: Color): Boolean =
    0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue < 110f / 255f

/** Ring twarzy w boxie 40x40 — port sampleFaceRing() z plugin.js. */
private fun faceRing(shape: String, steps: Int = 52): List<Offset> {
    if (shape == "cloud") {
        // uproszczony cloud: trzy puffki (jak stary GitHub path)
        val pts = mutableListOf<Offset>()
        for (i in 0 until steps) {
            val a = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
            pts.add(Offset(20f + 14f * cos(a), 21f + 11f * sin(a)))
        }
        return pts
    }
    if (shape == "drop") {
        val pts = mutableListOf<Offset>()
        for (i in 0 until steps) {
            val a = (i.toFloat() / steps) * (2f * Math.PI.toFloat())
            val rx = 13f + 5f * sin(a).let { it * abs(it) }
            pts.add(Offset(20f + rx * cos(a + Math.PI.toFloat()), 22f + 14f * sin(a)))
        }
        return pts
    }
    val pts = mutableListOf<Offset>()
    for (i in 0 until steps) {
        val a = (i.toFloat() / steps) * (2.0 * Math.PI).toFloat() - (Math.PI.toFloat() / 2)
        val c = cos(a); val s = sin(a)
        var rx = 16f; var ry = 16f
        when (shape) {
            "blob" -> { rx = 16f + 1.7f * sin(3 * a) + 0.7f * cos(5 * a); ry = rx }
            "squircle" -> {
                val d = Math.pow(abs(c.toDouble()), 5.0) + Math.pow(abs(s.toDouble()), 5.0)
                rx = (16.2f / Math.pow(d, 0.2)).toFloat(); ry = rx
            }
            "pill" -> {
                val d = Math.pow(abs(c.toDouble()), 8.0) +
                    Math.pow(abs((s / 0.72f).toDouble()), 8.0)
                rx = (16f / Math.pow(d, 0.125)).toFloat(); ry = rx
            }
            "triangle" -> {
                val twoPi = (2 * Math.PI).toFloat()
                val u = (a + Math.PI.toFloat() / 2 + twoPi) % twoPi
                val sector = (u / (twoPi / 3)) % 1f
                val v = 13.5f / maxOf(0.42f, cos((sector - 0.5f) * 1.9f))
                rx = v; ry = v
            }
            "hexagon" -> {
                val seg = (Math.PI / 3).toFloat()
                val hex = cos(seg / 2) / cos(a - seg * kotlin.math.floor((a / seg) + 0.5f).toInt().toFloat())
                rx = 16.2f * hex; ry = rx
            }
        }
        pts.add(Offset(20f + rx * c, 20f + ry * s))
    }
    return pts
}

/**
 * Twarz bota jak w desktopowym Bot Mode: kolorowe cialo (ksztalt z nazwy)
 * + dwoje oczu z blyskami. Statyczna klatka idle (bez animacji — MVP).
 */
@Composable
fun BotAvatar(name: String, sizeDp: Dp = 44.dp, working: Boolean = false, modifier: Modifier = Modifier) {
    val shape = remember(name) { defaultShapeFor(name) }
    val bodyColor = remember(name) { colorFor(name) }
    val eyeColor = remember(bodyColor) {
        if (isDark(bodyColor)) Color(232, 220, 195, 242) else Color(0, 0, 0, 217)
    }
    val glintColor = remember { Color(255, 255, 255, 217) }

    Canvas(modifier = modifier.size(sizeDp)) {
        val scale = size.width / 40f
        val ring = faceRing(shape)

        // cialo
        val body = Path().apply {
            ring.forEachIndexed { i, p ->
                val x = p.x * scale; val y = p.y * scale
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(body, bodyColor)

        // oczy (cy 17.2, cx 15.4/24.6; praca = lekko powiekszone)
        val eyeRy = if (working) 2.6f * scale else 2.3f * scale
        val eyeRx = 2.2f * scale
        val cy = (if (shape == "cloud") 22f else 17.2f) * scale
        val l = Offset(15.4f * scale, cy)
        val r = Offset(24.6f * scale, cy)
        drawCircle(eyeColor, eyeRx, l)
        drawCircle(eyeColor, eyeRx, r)
        // blyski
        drawCircle(glintColor, 0.65f * scale, Offset(l.x - 0.6f * scale, cy - 0.7f * scale))
        drawCircle(glintColor, 0.65f * scale, Offset(r.x - 0.6f * scale, cy - 0.7f * scale))

        // kropki pracy pod twarza
        if (working) {
            val dotY = 41.2f * scale
            drawCircle(bodyColor, 1.15f * scale, Offset(16.4f * scale, dotY))
            drawCircle(bodyColor, 1.15f * scale, Offset(20f * scale, dotY))
            drawCircle(bodyColor, 1.15f * scale, Offset(23.6f * scale, dotY))
        }
    }
}
