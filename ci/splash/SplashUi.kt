package dev.moonmc.game

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
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
 * Экран первой подготовки в стиле Material 3 Expressive: цвета из системной палитры
 * (Android 12+), иначе фирменная схема Moon из [Overlay.MoonTheme].
 *
 * Java-хост (`MoonSplash`) держит только диалог: содержимое и владельцы Compose живут здесь.
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
            Overlay.MoonTheme(context) { SplashScreen() }
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
private fun SplashScreen() {
    val scheme = MaterialTheme.colorScheme
    val step by SplashUi.currentStep.collectAsState()

    // Дыхание знака и мерцание звёзд — expressive-движение без тяжёлых эффектов.
    val motion = rememberInfiniteTransition(label = "splash")
    val breath by motion.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(1800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val twinkle by motion.animateFloat(
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
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = breath
                        scaleY = breath
                    }
                    .clip(CircleShape)
                    .background(scheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("☾", color = scheme.onPrimaryContainer, fontSize = 46.sp)
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
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            Text(
                "One-time setup — next launches go straight to the game",
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
    }
}
