package dev.moonmc.game

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Экран первой подготовки MoonMC (`MoonSplash`): знак лаунчера, заголовок, спиннер и
 * текущий шаг движка. Знак собирается по квадратикам как тетрис, затем по нему идёт
 * радуга, затем он принимает цвет системной схемы — та же анимация (`MoonLogo` из
 * `MoonLogo.kt`), что и в `Overlay.LoadingScreen`.
 *
 * Обычный запуск этот экран не показывает: игру прикрывает только оверлей с его
 * `LoadingScreen`, отдельного интро нет.
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
            // Подготовка идёт до всякой игры: схема берётся по системной теме.
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
    val glyph = rememberMoonGlyph(context)

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
                    .width(LOGO_WIDTH)
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
