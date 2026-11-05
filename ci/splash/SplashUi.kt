package dev.moonmc.game

import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Знак лаунчера: drawable из ci/branding (тот же PNG, что и иконка приложения). */
private const val LOGO_RESOURCE = "splash_moon"

/** Сетка квадратиков: ширина в клетках, высота — по пропорциям самого знака. */
private const val LOGO_COLS = 14
private const val LOGO_MAX_ROWS = 48

/** Пока PNG не прочитан — та же рамка, что и у знака (заголовок не прыгает). */
private const val LOGO_FALLBACK_ROWS = 17

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

/**
 * Экран запуска MoonMC: знак лаунчера собирается по квадратикам как тетрис,
 * затем по собранным квадратикам змейкой проходит радуга, затем знак принимает
 * цвет системной схемы (Material You на Android 12+, иначе фирменная палитра Moon).
 *
 * Java-хост (`MoonSplash`) держит только диалог: содержимое и владельцы Compose живут
 * здесь, поэтому анимация отменяется вместе с диалогом и не оставляет вечных эффектов.
 */
object SplashUi {
    internal val currentStep = MutableStateFlow("Preparing the game…")

    /** Шаг подготовки пришёл из движка. */
    @JvmStatic
    fun step(text: String) {
        currentStep.value = text.ifBlank { currentStep.value }
    }

    /** Готовый View для диалога: сам ставит владельцев жизненного цикла для Compose. */
    @JvmStatic
    fun createHost(context: Context): View = SplashHost(context)
}

private class SplashHost(context: Context) :
    android.widget.FrameLayout(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        setViewTreeViewModelStoreOwner(this)
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        setBackgroundColor(0xFF070B09.toInt())

        val view = ComposeView(context)
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.setContent {
            // Обычный запуск тоже смотрит на системную тему: светлая или тёмная схема
            // Material You выбирается по ночному режиму, ниже API 31 — фирменная Moon.
            Overlay.MoonTheme(context, followSystem = true) { SplashScreen(context) }
        }
        addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}

@Composable
private fun SplashScreen(context: Context) {
    val scheme = MaterialTheme.colorScheme
    val step by SplashUi.currentStep.collectAsState()
    val glyph = rememberGlyph(context)

    // Мерцание звёзд — единственное бесконечное движение: оно живёт ровно столько,
    // сколько существует композиция, и отменяется вместе с диалогом.
    val twinkle by rememberInfiniteTransition(label = "splash").animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "twinkle",
    )

    Box(
        Modifier.fillMaxSize().background(scheme.surfaceContainerLowest),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dots = 34
            for (i in 0 until dots) {
                val gx = ((i * 73) % 100) / 100f
                val gy = ((i * 37) % 100) / 100f
                drawCircle(
                    color = scheme.primary.copy(alpha = twinkle * 0.30f * (0.4f + gx)),
                    radius = 2f + 3.5f * gy,
                    center = Offset(size.width * gx, size.height * gy),
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val cols = glyph?.cols ?: LOGO_COLS
            val rows = glyph?.rows ?: LOGO_FALLBACK_ROWS
            Box(
                Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(cols.toFloat() / rows)
                    .semantics { contentDescription = "MoonMC logo" },
                contentAlignment = Alignment.Center,
            ) {
                if (glyph == null) {
                    // Ресурса нет (или ещё читается): тот же знак, но текстом.
                    Text("☾", color = scheme.primary, fontSize = 48.sp)
                } else {
                    MoonLogo(glyph, Modifier.fillMaxSize())
                }
            }
            Text("MoonMC", color = scheme.onSurface, style = MaterialTheme.typography.headlineMedium)
            CircularProgressIndicator(
                color = scheme.primary,
                trackColor = scheme.surfaceContainerHigh,
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp),
            )
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "step",
            ) { text ->
                Text(
                    text,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    // Экранный диктор читает новый шаг подготовки сам.
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * Квадратики знака: заполненные клетки и их место на пути змейки.
 * Всё считается один раз из PNG, поэтому анимация детерминирована.
 */
private class MoonGlyph(
    val cols: Int,
    val rows: Int,
    /** true — в клетке есть знак; индекс = row * cols + col. */
    val filled: BooleanArray,
    /** Место клетки на змейке: 0 — первая, 1 — последняя. */
    val path: FloatArray,
    /** Собственный цвет знака (из PNG): им он собран до Material-тинта. */
    val ink: Color,
)

@Composable
private fun rememberGlyph(context: Context): MoonGlyph? {
    val glyph by produceState<MoonGlyph?>(initialValue = null, key1 = context) {
        // Чтение PNG — не на главном потоке: подготовка и рендер игры его не ждут.
        value = withContext(Dispatchers.Default) {
            runCatching { loadGlyph(context) }.getOrNull()
        }
    }
    return glyph
}

/** PNG -> сетка: обрезаем прозрачные поля, клетка — большинство непрозрачных точек. */
private fun loadGlyph(context: Context): MoonGlyph? {
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
private fun MoonLogo(glyph: MoonGlyph, modifier: Modifier = Modifier) {
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
