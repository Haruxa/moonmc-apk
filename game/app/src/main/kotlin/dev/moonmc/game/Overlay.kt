package dev.moonmc.game

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as C
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movtery.layer_controller.ControlBoxLayout
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.event.EventHandler
import com.movtery.layer_controller.layout.loadLayoutFromString
import com.movtery.layer_controller.observable.ObservableControlLayout
import java.io.File

/**
 * Игровой слой MoonMC: контролы из Zalith Launcher 2 (`LayerController`) и панель
 * настроек под шестернёй.
 *
 * Окно прозрачное и лежит поверх нативной поверхности (обычные view под SurfaceView
 * не видны, окно-диалог — видно). Раскладка читается из `touch/zalith/layout.json`
 * в папке игры; если файла нет — берётся встроенная `assets/default_layout.json`.
 * Настройки производительности пишутся в `moonmc/perf.txt`.
 */
object Overlay {
    private const val PERF = "moonmc/perf.txt"
    private const val LAYOUT_DIR = "touch/zalith"
    private var dialog: Dialog? = null

    @JvmStatic
    fun attach(activity: Activity) {
        activity.runOnUiThread {
            if (dialog?.isShowing == true) return@runOnUiThread
            val host = OverlayHost(activity)
            host.addView(
                ComposeView(activity).apply {
                    setContent {
                        MaterialTheme(colorScheme = darkColorScheme()) { Layer(activity) }
                    }
                },
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            val d = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
            d.setContentView(host)
            d.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
                setDimAmount(0f)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            dialog = d
            d.show()
        }
    }

    private fun gameDir(name: String) = File("/sdcard/MoonMC/horizon", name)

    /** Раскладка: своя из папки игры, иначе встроенная из assets. */
    private fun readLayout(activity: Activity): ObservableControlLayout {
        val text = runCatching {
            val own = gameDir(LAYOUT_DIR).let { dir ->
                dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.firstOrNull()
            }
            own?.readText() ?: activity.assets.open("default_layout.json")
                .bufferedReader().use { it.readText() }
        }.getOrElse { "{}" }
        val layout = runCatching { loadLayoutFromString(text) }.getOrElse {
            loadLayoutFromString(activity.assets.open("default_layout.json")
                .bufferedReader().use { it.readText() })
        }
        return ObservableControlLayout(layout)
    }

    @Composable
    private fun Layer(activity: Activity) {
        val layout = remember { readLayout(activity) }
        var opened by remember { mutableStateOf(false) }

        val handler = remember {
            EventHandler { event: ClickEvent, pressed: Boolean ->
                // Событие их слоя → GLFW-код → JNI в движок.
                val code = runCatching {
                    com.movtery.inputmap.keycodes.ControlEventKeycode.getKeycodeFromEvent(event.key)
                }.getOrNull()
                if (code != null) {
                    Native.key(code.toInt(), pressed)
                } else if (pressed && !event.key.isNullOrBlank()) {
                    android.util.Log.i("MoonOverlay", "неизвестный control: ${event.key}")
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            ControlBoxLayout(
                observedLayout = layout,
                eventHandler = handler,
                isCursorGrabbing = false,
                checkOccupiedPointers = { false },
            ) {
                // мир под контролами: их слой рисует только кнопки и джойстик
            }

            // шестерня: панель настроек
            Surface(
                modifier = Modifier.padding(12.dp).size(44.dp),
                shape = RoundedCornerShape(16.dp),
                color = C(0xE6101418),
                onClick = { opened = !opened },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = C(0xFF9BE8BE))
                }
            }

            if (opened) {
                SettingsPanel(activity) { opened = false }
            }
        }
    }

    @Composable
    private fun SettingsPanel(activity: Activity, onClose: () -> Unit) {
        val saved = remember { readPerf(activity) }
        var dynamicResolution by remember { mutableStateOf(saved["dynamic_resolution"] ?: "on") }
        var hdr by remember { mutableStateOf(saved["hdr"] ?: "off") }
        var fpsCap by remember { mutableStateOf(saved["fps_cap"] ?: "60") }

        fun store() = writePerf(
            activity,
            mapOf(
                "dynamic_resolution" to dynamicResolution,
                "hdr" to hdr,
                "fps_cap" to fpsCap,
            ),
        )

        Surface(
            modifier = Modifier.padding(top = 64.dp, start = 12.dp).width(320.dp),
            shape = RoundedCornerShape(24.dp),
            color = C(0xF0111418),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Настройки", color = C(0xFF9BE8BE), fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Закрыть", fontSize = 12.sp) }
                }
                Text("Производительность", color = C(0xFFB9C8CE), fontSize = 12.sp)
                Toggle("Динамическое разрешение", dynamicResolution == "on") {
                    dynamicResolution = if (it) "on" else "off"; store()
                }
                Toggle("HDR-вывод", hdr == "on") { hdr = if (it) "on" else "off"; store() }
                Text("Лимит FPS", color = C(0xFFB9C8CE), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("30", "60", "90", "0").forEach { value ->
                        FilterChip(
                            selected = fpsCap == value,
                            onClick = { fpsCap = value; store() },
                            label = { Text(if (value == "0") "∞" else value, fontSize = 12.sp) },
                        )
                    }
                }
                Text(
                    "Управление — из Zalith Launcher 2: раскладка touch/zalith/layout.json",
                    color = C(0xFF8CA0A7),
                    fontSize = 11.sp,
                )
            }
        }
    }

    @Composable
    private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = C(0xFFE8F1EC), fontSize = 13.sp)
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    private fun readPerf(activity: Activity): Map<String, String> = runCatching {
        gameDir(PERF).readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writePerf(activity: Activity, values: Map<String, String>) {
        runCatching {
            val f = gameDir(PERF)
            f.parentFile?.mkdirs()
            f.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }
    }
}

/**
 * Контейнер для Compose внутри NativeActivity: она не является LifecycleOwner,
 * поэтому ComposeView сам по себе падает с "ViewTreeLifecycleOwner not found".
 */
private class OverlayHost(context: android.content.Context) :
    android.widget.FrameLayout(context),
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner,
    androidx.lifecycle.ViewModelStoreOwner {

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateController = androidx.savedstate.SavedStateRegistryController.create(this)
    override val viewModelStore = androidx.lifecycle.ViewModelStore()

    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }
}
