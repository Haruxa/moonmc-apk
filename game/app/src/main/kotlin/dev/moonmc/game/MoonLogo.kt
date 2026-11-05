package dev.moonmc.game

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Знак MoonMC: та же анимация, что и на экране подготовки, но как обычный Compose-элемент.
 * Её показывает `Overlay.LoadingScreen` — отдельного окна/интро нет.
 *
 * 1) квадратики знака падают на места (тетрис), 2) по ним бежит радужная змейка,
 * 3) знак принимает цвет системной схемы (Material You). Всё считается один раз из PNG,
 * поэтому прогон детерминирован, а картинка после анимации ничего не стоит.
 */

/** Знак лаунчера: drawable из ci/branding (тот же PNG, что и иконка приложения). */
internal const val LOGO_RESOURCE = "splash_moon"

/** Сетка квадратиков: ширина в клетках, высота — по пропорциям самого знака. */
internal const val LOGO_COLS = 14
private const val LOGO_MAX_ROWS = 48

/** Пока PNG не прочитан — та же рамка, что и у знака (заголовок не прыгает). */
internal const val LOGO_FALLBACK_ROWS = 17

private const val ALPHA_CUT = 96

/** Тайминги: тетрис -> радужная змейка -> цвет системной схемы. */
private const val REVEAL_MS = 1150
private const val SNAKE_MS = 1100
private const val TINT_MS = 450

/** Клетка появляется не мгновенно: за этот кусок прогресса она «падает». */
private const val REVEAL_WINDOW = 0.30f

/** Длина радужного хвоста змейки в единицах пути. */
private const val SNAKE_TAIL = 0.55f
private const val RAINBOW_STEPS = 48

/** Насколько высоко над своим местом клетка начинает падение (в клетках). */
private const val FALL_CELLS = 2.2f

/** Ширина знака на экране: он остаётся тем же по размеру при любой высоте сетки. */
internal val LOGO_WIDTH = 132.dp

/**
 * Квадратики знака: заполненные клетки и их место на пути змейки.
 * Всё считается один раз из PNG, поэтому анимация детерминирована.
 */
internal class MoonGlyph(
    val cols: Int,
    val rows: Int,
    /** true — в клетке есть знак; индекс = row * cols + col. */
    val filled: BooleanArray,
    /** Место клетки на змейке: 0 — первая, 1 — последняя. */
    val path: FloatArray,
    /** Собственный цвет знака (из PNG): им он собран до Material-тинта. */
    val ink: Color,
)

/** Сетка знака из PNG: читается в фоне, до готовности возвращается null. */
@Composable
internal fun rememberMoonGlyph(context: Context): MoonGlyph? {
    val glyph by produceState<MoonGlyph?>(initialValue = null, key1 = context) {
        // Чтение PNG — не на главном потоке: подготовка и рендер игры его не ждут.
        value = withContext(Dispatchers.Default) {
            runCatching { loadMoonGlyph(context) }.getOrNull()
        }
    }
    return glyph
}

/** PNG -> сетка: обрезаем прозрачные поля, клетка — большинство непрозрачных точек. */
private fun loadMoonGlyph(context: Context): MoonGlyph? {
    val resources: Resources = context.resources
    val id = resources.getIdentifier(LOGO_RESOURCE, "drawable", context.packageName)
    if (id == 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = 4 }
    val bitmap = BitmapFactory.decodeResource(resources, id, options) ?: return null
    try {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 4 || height < 4) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var left = width
        var top = height
        var right = -1
        var bottom = -1
        var red = 0L
        var green = 0L
        var blue = 0L
        var opaque = 0L
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val argb = pixels[row + x]
                if ((argb ushr 24) < ALPHA_CUT) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                red += (argb shr 16) and 0xFF
                green += (argb shr 8) and 0xFF
                blue += argb and 0xFF
                opaque++
            }
        }
        if (opaque == 0L || right < left || bottom < top) return null

        val artWidth = right - left + 1
        val artHeight = bottom - top + 1
        val cols = LOGO_COLS
        val rows = (cols * artHeight.toFloat() / artWidth).roundToInt().coerceIn(1, LOGO_MAX_ROWS)
        val filled = BooleanArray(rows * cols)
        for (row in 0 until rows) {
            val y0 = top + artHeight * row / rows
            val y1 = top + artHeight * (row + 1) / rows
            for (col in 0 until cols) {
                val x0 = left + artWidth * col / cols
                val x1 = left + artWidth * (col + 1) / cols
                val stepX = max(1, (x1 - x0) / 4)
                val stepY = max(1, (y1 - y0) / 4)
                var hit = 0
                var total = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        total++
                        if ((pixels[y * width + x] ushr 24) >= ALPHA_CUT) hit++
                        x += stepX
                    }
                    y += stepY
                }
                filled[row * cols + col] = total > 0 && hit * 2 >= total
            }
        }

        // Змейка: снизу вверх, в каждой строке — поперёк, направление чередуется.
        val order = IntArray(filled.size)
        var next = 0
        var backwards = false
        for (row in rows - 1 downTo 0) {
            val line: IntProgression = if (backwards) (cols - 1 downTo 0) else (0 until cols)
            for (col in line) {
                val index = row * cols + col
                if (filled[index]) order[index] = next++
            }
            backwards = !backwards
        }
        if (next == 0) return null
        val path = FloatArray(filled.size)
        for (index in filled.indices) {
            if (filled[index]) {
                path[index] = if (next > 1) order[index] / (next - 1f) else 0f
            }
        }

        val ink = Color(
            android.graphics.Color.argb(
                255,
                (red / opaque).toInt().coerceIn(0, 255),
                (green / opaque).toInt().coerceIn(0, 255),
                (blue / opaque).toInt().coerceIn(0, 255),
            ),
        )
        return MoonGlyph(cols = cols, rows = rows, filled = filled, path = path, ink = ink)
    } finally {
        bitmap.recycle()
    }
}

/**
 * Сам знак: один Canvas и ни одной картинки в памяти.
 * 1) клетки падают на свои места, 2) по ним бежит радужная змейка, 3) знак
 * принимает цвет системной схемы. Все шаги — один прогон, без вечных анимаций.
 */
@Composable
internal fun MoonLogo(glyph: MoonGlyph, modifier: Modifier = Modifier) {
    val target = MaterialTheme.colorScheme.primary
    val drop = remember(glyph) { Animatable(0f) }
    val snake = remember(glyph) { Animatable(0f) }
    val tint = remember(glyph) { Animatable(0f) }
    val rainbow = remember {
        List(RAINBOW_STEPS) { step ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf(step * 360f / RAINBOW_STEPS, 0.62f, 1f)))
        }
    }

    LaunchedEffect(glyph) {
        drop.snapTo(0f)
        snake.snapTo(0f)
        tint.snapTo(0f)
        // Сборка: квадратики встают по очереди снизу вверх.
        drop.animateTo(1f, tween(REVEAL_MS, easing = LinearOutSlowInEasing))
        // Радужная змейка по уже собранному знаку.
        snake.animateTo(1f + SNAKE_TAIL, tween(SNAKE_MS, easing = LinearEasing))
        // Материал: цвет системной схемы и лёгкий expressive-«вдох».
        tint.animateTo(1f, tween(TINT_MS, easing = FastOutSlowInEasing))
    }

    Canvas(
        modifier.graphicsLayer {
            val pop = 1f + 0.04f * sin(tint.value * Math.PI.toFloat())
            scaleX = pop
            scaleY = pop
        },
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val cellWidth = size.width / glyph.cols
        val cellHeight = size.height / glyph.rows
        val ink = lerp(glyph.ink, target, tint.value)
        val appearSpan = drop.value * (1f + REVEAL_WINDOW)
        for (row in 0 until glyph.rows) {
            for (col in 0 until glyph.cols) {
                val index = row * glyph.cols + col
                if (!glyph.filled[index]) continue
                val place = glyph.path[index]
                val appear = ((appearSpan - place) / REVEAL_WINDOW).coerceIn(0f, 1f)
                if (appear <= 0f) continue
                val tail = snake.value - place
                val band = if (tail <= 0f || tail >= SNAKE_TAIL) 0f else 1f - tail / SNAKE_TAIL
                val color = if (band <= 0f) {
                    ink
                } else {
                    val rainbowIndex = (tail / SNAKE_TAIL * RAINBOW_STEPS)
                        .toInt()
                        .coerceIn(0, RAINBOW_STEPS - 1)
                    lerp(ink, rainbow[rainbowIndex], band)
                }
                drawRect(
                    color = color.copy(alpha = appear),
                    topLeft = Offset(
                        x = col * cellWidth,
                        y = (row - (1f - appear) * FALL_CELLS) * cellHeight,
                    ),
                    // +0.5px закрывает щели между клетками от округления.
                    size = Size(cellWidth + 0.5f, cellHeight + 0.5f),
                )
            }
        }
    }
}
