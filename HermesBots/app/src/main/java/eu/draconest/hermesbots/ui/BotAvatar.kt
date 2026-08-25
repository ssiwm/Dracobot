package eu.draconest.hermesbots.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Blob avatar 1:1 z desktopowego Bot Mode (NousResearch/Hermes-Bot-Mode plugin.js):
 * - ksztalt deterministyczny z nazwy (hash*31), kolory z AVATAR_COLORS,
 * - ring probkowany wzorem sampleFaceRing(),
 * - oczy (elipsy + blyski), ciemne cialo -> jasne oczy.
 *
 * ANIMACJA (port facePose() z plugin.js, rAF -> produceState @ 60fps):
 * - idle:  delikatny sway (turn ±1.5°, roll ±1.2°) + mruganie co ~3.2 s,
 * - work:  chylaca sie i kołysząca twarz (turn -11±8°, tilt ±8°, roll ±4.2°),
 *          wędrujące spojrzenie (gazeX/Y sinusami), mruganie co ~1.45 s
 *          i trzy pulsujace kropki "myślenia" pod twarza (fale przesunięte o 0.7 rad).
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

/** Pozа twarzy — port facePose(mood, t). */
private data class FacePose(
    val turn: Float,   // obrót głowy w osi Y (symulowany przez ściśnięcie X)
    val tilt: Float,   // pochylenie przód/tył (ściśnięcie Y)
    val roll: Float,   // przechył boczny (obrót 2D)
    val gazeX: Float,
    val gazeY: Float,
    val blink: Boolean,
    val d0: Float = 0f, // kropki pracy
    val d1: Float = 0f,
    val d2: Float = 0f
)

private fun facePose(working: Boolean, t: Float): FacePose {
    if (working) {
        return FacePose(
            turn = -11f + sin(t * 0.48f) * 8f,
            tilt = sin(t * 0.42f) * 8f + sin(t * 1.1f) * 1.6f,
            roll = sin(t * 0.75f) * 4.2f,
            gazeX = sin(t * 0.55f) * 3.6f,
            gazeY = -1.6f + sin(t * 0.38f) * 2f,
            blink = t % 1.45f > 1.26f,
            d0 = 0.2f + 0.8f * maxOf(0f, sin(t * 2.6f)),
            d1 = 0.2f + 0.8f * maxOf(0f, sin(t * 2.6f - 0.7f)),
            d2 = 0.2f + 0.8f * maxOf(0f, sin(t * 2.6f - 1.4f))
        )
    }
    return FacePose(
        turn = sin(t * 0.5f) * 1.5f,
        tilt = sin(t * 0.27f),
        roll = sin(t * 0.85f) * 1.2f,
        gazeX = 0f,
        gazeY = 0f,
        blink = t % 3.2f > 3.02f
    )
}

/** Ring twarzy w boxie 40x40 — port sampleFaceRing() z plugin.js. */
private fun faceRing(shape: String, steps: Int = 52): List<Offset> {
    if (shape == "cloud") {
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

/** projectFacePoint(): roll w 2D + symulacja obrotu 3D (sciśnięcie osi wg turn/tilt). */
private fun projectPoint(x: Float, y: Float, pose: FacePose): Offset {
    val dx = x - 20f
    val dy = y - 20f
    val r = Math.toRadians(pose.roll.toDouble())
    val xr = (dx * cos(r) - dy * sin(r)).toFloat()
    val yr = (dx * sin(r) + dy * cos(r)).toFloat()
    val sx = 0.74f + 0.26f * abs(cos(Math.toRadians(pose.turn.toDouble()))).toFloat()
    val sy = 0.80f + 0.20f * abs(cos(Math.toRadians(pose.tilt.toDouble()))).toFloat()
    return Offset(20f + xr * sx, 20f + yr * sy)
}

/**
 * Żywa twarz bota: idle oddycha i mruga; podczas pracy ("myślenia") kolyje się,
 * rozglada i pulsuje trzema kropkami — jak na desktopowym Bot Mode.
 */
@Composable
fun BotAvatar(name: String, sizeDp: Dp = 44.dp, working: Boolean = false, modifier: Modifier = Modifier) {
    val shape = remember(name) { defaultShapeFor(name) }
    val bodyColor = remember(name) { colorFor(name) }
    val eyeColor = remember(bodyColor) {
        if (isDark(bodyColor)) Color(232, 220, 195, 242) else Color(0, 0, 0, 217)
    }
    val glintColor = remember { Color(255, 255, 255, 217) }

    // zegar animacji — t sekund od startu kompozycji (jak performance.now()/1000 w pliginie)
    val t by produceState(0f, working) {
        val start = System.nanoTime()
        while (true) {
            value = ((System.nanoTime() - start) / 1_000_000_000f)
            kotlinx.coroutines.delay(33) // ~30 fps wystarcza dla malutkich avatarów
        }
    }

    val ring = remember(shape) { faceRing(shape) }

    Canvas(modifier = modifier.size(sizeDp)) {
        val scale = size.width / 40f
        val pose = facePose(working, t)

        // cialo (projekcja z roll/turn/tilt)
        val body = Path().apply {
            ring.forEachIndexed { i, p ->
                val q = projectPoint(p.x, p.y, pose)
                val x = q.x * scale; val y = q.y * scale
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(body, bodyColor)

        // oczy + gaze
        val eyeBaseY = (if (shape == "cloud") 22f else 17.2f) + pose.gazeY
        val cy = eyeBaseY * scale
        val l = Offset((15.4f + pose.gazeX) * scale, cy)
        val r = Offset((24.6f + pose.gazeX) * scale, cy)
        if (!pose.blink) {
            val eyeRy = (if (working) 2.6f else 2.3f) * scale
            drawOval(eyeColor, topLeft = Offset(l.x - 2.2f * scale, cy - eyeRy), size = androidx.compose.ui.geometry.Size(4.4f * scale, 2 * eyeRy))
            drawOval(eyeColor, topLeft = Offset(r.x - 2.2f * scale, cy - eyeRy), size = androidx.compose.ui.geometry.Size(4.4f * scale, 2 * eyeRy))
            drawCircle(glintColor, 0.65f * scale, Offset(l.x - 0.6f * scale, cy - 0.7f * scale))
            drawCircle(glintColor, 0.65f * scale, Offset(r.x - 0.6f * scale, cy - 0.7f * scale))
        } else {
            // powieki zamkniete: poziome kreseciki (port data-hb-shut)
            drawLine(eyeColor, Offset(l.x - 2.6f * scale, cy), Offset(l.x + 2.6f * scale, cy), strokeWidth = 2f * scale)
            drawLine(eyeColor, Offset(r.x - 2.6f * scale, cy), Offset(r.x + 2.6f * scale, cy), strokeWidth = 2f * scale)
        }

        // kropki myślenia pod twarza (tylko working)
        if (working) {
            val dotY = 41.2f * scale
            drawCircle(bodyColor.copy(alpha = pose.d0.coerceIn(0f, 1f)), 1.15f * scale, Offset(16.4f * scale, dotY))
            drawCircle(bodyColor.copy(alpha = pose.d1.coerceIn(0f, 1f)), 1.15f * scale, Offset(20f * scale, dotY))
            drawCircle(bodyColor.copy(alpha = pose.d2.coerceIn(0f, 1f)), 1.15f * scale, Offset(23.6f * scale, dotY))
        }
    }
}
