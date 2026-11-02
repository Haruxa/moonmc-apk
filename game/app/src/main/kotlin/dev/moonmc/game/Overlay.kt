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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
    /** Файл-выключатель слоя управления (крайняя мера при крашах). */
    private const val LAYER_OFF = "moonmc-layer-off.txt"
    private var dialog: Dialog? = null
    /** Открыта ли сейчас системная клавиатура (кнопка IME переключает). */
    private var imeShown = false
    private var content: android.view.View? = null
    private var host: OverlayHost? = null

    @JvmStatic
    fun attach(activity: Activity) {
        activity.runOnUiThread {
            try {
                show(activity)
            } catch (t: Throwable) {
                // Слой не должен ронять игру: пишем причину рядом с игрой.
                report(activity, "moonmc-overlay.txt", t.stackTraceToString())
            }
        }
    }

    /**
     * Куда класть отчёты: папка "Загрузки" через MediaStore (доступна без разрешений
     * и видна другим приложениям — иначе краш нечем диагностировать), потом папка игры.
     */
    private fun report(activity: Activity, name: String, text: String) {
        runCatching {
            val resolver = activity.contentResolver
            val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            resolver.delete(
                downloads,
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(name),
            )
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
            }
            val uri = resolver.insert(downloads, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
        runCatching {
            val dir = File("/sdcard/MoonMC/horizon")
            dir.mkdirs()
            File(dir, name).writeText(text)
        }
    }

    private fun show(activity: Activity) {
        if (dialog?.isShowing == true) return
        // Выключатель слоя: если файл есть, играем без него (крайняя мера при крашах).
        if (gameDir(LAYER_OFF).exists() ||
            File(activity.getExternalFilesDir(null) ?: activity.filesDir, LAYER_OFF).exists()
        ) {
            step(activity, "layer disabled by file")
            return
        }
        step(activity, "start")
        imeShown = false
        val host = OverlayHost(activity)
        step(activity, "host ok")
        // Пишем софтверный слой: над Vulkan-поверхностью игры аппаратный рендер Compose
        // на части драйверов Mali роняет процесс без единой java-строки в логе.
        val view = ComposeView(activity)
        view.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        view.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Layer(activity) }
        }
        step(activity, "compose ok")
        content = view
        this.host = host
        host.addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val d = Dialog(activity, android.R.style.Theme_Material_NoActionBar_Fullscreen)
        d.setContentView(host)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Полупрозрачный формат окна: поверх Vulkan-поверхности игры аппаратное
            // сведение окна на части драйверов Mali валит процесс без java-следа.
            setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setDimAmount(0f)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        dialog = d
        step(activity, "dialog ok")
        d.show()
        step(activity, "shown")
    }

    /** Клавиатура Android: кнопка IME их раскладки. Открывает её сам движок
     * (event_loop::set_keyboard), иначе ввод в чат не доходит. */
    private fun toggleIme() {
        imeShown = !imeShown
        Native.keyboard(imeShown)
    }

    /** Клавиатура занимает низ экрана: слой на это время не рисуется и не ловит касания,
     * иначе его кнопки лежат поверх клавиатуры и печатать нельзя. */
    private fun applyKeyboard(up: Boolean) {
        host?.passthrough = up
        content?.visibility = if (up) android.view.View.GONE else android.view.View.VISIBLE
        dialog?.window?.let { window ->
            if (up) {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            }
        }
    }

    /** Шаги запуска слоя: видно, до какого места дошло перед крашем. */
    private fun step(activity: Activity, text: String) {
        report(activity, "moonmc-layer-step.txt", text)
    }

    private fun gameDir(name: String) = File("/sdcard/MoonMC/horizon", name)

    /** Палец текущего жеста попал в кнопку/джойстик слоя (такое касание игре не пересылаем). */
    @JvmStatic
    internal var widgetTouched = false

    /**
     * Касание мимо кнопок уходит в игру: окно оверлея перехватывает диспетчер ввода,
     * иначе меню и поворот камеры не работают. Событие идёт нашим JNI-мостом
     * (очередь moon_input) и попадает в ту же обработку касаний, что и настоящие.
     */
    internal fun forwardToGame(
        event: android.view.MotionEvent,
        action: Int = event.actionMasked,
        index: Int = event.actionIndex,
    ) {
        Native.touch(
            event.getX(index),
            event.getY(index),
            action,
            event.getPointerId(index),
        )
    }

    /** Раскладка: своя из папки игры, иначе встроенная из assets. */
    private fun readLayout(activity: Activity): ObservableControlLayout? {
        val text = runCatching {
            val own = gameDir(LAYOUT_DIR).let { dir ->
                dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name }?.firstOrNull()
            }
            own?.readText() ?: activity.assets.open("default_layout.json")
                .bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        val layout = runCatching { loadLayoutFromString(text) }
            .recoverCatching {
                // битая своя раскладка — берём встроенную
                loadLayoutFromString(
                    activity.assets.open("default_layout.json")
                        .bufferedReader().use { it.readText() },
                )
            }
            .getOrElse { error ->
                // слой не должен валить игру: пишем причину и работаем без него
                report(activity, "moonmc-overlay.txt", "layout: " + error.stackTraceToString())
                return null
            }
        return ObservableControlLayout(layout)
    }

    @Composable
    private fun Layer(activity: Activity) {
        val layout = remember { readLayout(activity) }
        var opened by remember { mutableStateOf(false) }

        // Клавиатуру может открыть и сам движок (поле ввода в меню): состояние берём у него.
        LaunchedEffect(Unit) {
            while (true) {
                applyKeyboard(runCatching { Native.keyboardShown() }.getOrDefault(imeShown))
                kotlinx.coroutines.delay(250)
            }
        }

        val handler = remember {
            EventHandler { event: ClickEvent, pressed: Boolean ->
                // Событие их слоя → GLFW-код → JNI в движок. Флаг: палец попал в кнопку,
                // такое касание игре не пересылаем.
                widgetTouched = true
                if (event.type == ClickEvent.Type.LauncherEvent) {
                    // Мышь и раскладку их слой помечает как launcher_event: код
                    // из ControlEventKeycode тут — номер кнопки мыши, не клавиша.
                    when {
                        event.key.endsWith("BUTTON_LEFT") || event.key.endsWith("BUTTON_1") ->
                            Native.mouse(0, pressed)
                        event.key.endsWith("BUTTON_RIGHT") || event.key.endsWith("BUTTON_2") ->
                            Native.mouse(1, pressed)
                        event.key.contains("switch_ime") && pressed -> toggleIme()
                        else -> Unit
                    }
                } else {
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
        }

        Box(Modifier.fillMaxSize()) {
            ControlBoxLayout(
                observedLayout = layout,
                eventHandler = handler,
                // Захват курсора у них = «идёт игра»: от этого зависит видимость
                // слоёв с visibilityType in_game (джойстик и Ctrl/Alt), а мы всегда в игре.
                isCursorGrabbing = true,
                checkOccupiedPointers = { false },
            ) {
                // мир под контролами: их слой рисует только кнопки и джойстик
            }

            // капелька с шестерёнкой: маленькая, по центру сверху — как в Zalith
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .size(36.dp),
                shape = RoundedCornerShape(
                    topStartPercent = 50,
                    topEndPercent = 50,
                    bottomEndPercent = 50,
                    bottomStartPercent = 12,
                ),
                color = C(0xF014211C),
                border = androidx.compose.foundation.BorderStroke(1.dp, C(0x339BE8BE)),
                shadowElevation = 8.dp,
                onClick = { opened = !opened },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = C(0xFF9BE8BE),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (opened) {
                SettingsPanel(
                    activity,
                    Modifier.align(Alignment.TopCenter),
                ) { opened = false }
            }
        }
    }

    @Composable
    private fun SettingsPanel(
        activity: Activity,
        modifier: Modifier = Modifier,
        onClose: () -> Unit,
    ) {
        val saved = remember { readPerf(activity) }
        var dynamicResolution by remember { mutableStateOf(saved["dynamic_resolution"] ?: "on") }
        var hdr by remember { mutableStateOf(saved["hdr"] ?: "off") }
        var fpsCap by remember { mutableStateOf(saved["fps_cap"] ?: "60") }
        val accounts = remember { readAccounts() }
        var current by remember { mutableStateOf(runCatching { Native.accountName() }.getOrDefault("")) }

        fun store() = writePerf(
            activity,
            mapOf(
                "dynamic_resolution" to dynamicResolution,
                "hdr" to hdr,
                "fps_cap" to fpsCap,
            ),
        )

        Surface(
            modifier = modifier
                .padding(top = 52.dp)
                .width(320.dp),
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
                Text("Аккаунт", color = C(0xFFB9C8CE), fontSize = 12.sp)
                Text(
                    if (current.isBlank()) "не выбран" else current,
                    color = C(0xFF9BE8BE),
                    fontSize = 13.sp,
                )
                if (accounts.isEmpty()) {
                    Text(
                        "Список в /sdcard/MoonMC/accounts.json: [\"ник\", …]",
                        color = C(0xFF8CA0A7),
                        fontSize = 11.sp,
                    )
                } else {
                    accounts.forEach { name ->
                        TextButton(onClick = {
                            Native.setAccount(name)
                            current = name
                        }) { Text(name, fontSize = 12.sp) }
                    }
                }
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

    /** Аккаунты из общего списка: строки или объекты с "name". */
    private fun readAccounts(): List<String> = runCatching {
        val file = File("/sdcard/MoonMC/accounts.json")
        if (!file.isFile) return@runCatching emptyList()
        val array = org.json.JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            when (val item = array.opt(i)) {
                is String -> item.ifBlank { null }
                is org.json.JSONObject -> item.optString("name").ifBlank { null }
                else -> null
            }
        }
    }.getOrDefault(emptyList())

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

    /** Жесты, отданные игре (камера/тап), по id пальцев. */
    private val gamePointers = mutableSetOf<Int>()

    /** Окно не должно ловить касания: открыта клавиатура. */
    var passthrough = false

    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        // Compose ищет владельцев по ViewTree-тегам (не по интерфейсам вьюхи):
        // без этого падает с "ViewTreeLifecycleOwner not found" на первом кадре.
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        setViewTreeViewModelStoreOwner(this)
        // Сначала attach, потом restore: иначе SavedStateRegistry бросает исключение.
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (passthrough) return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN,
            android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                // Судьба пальца решается один раз, на его нажатии: кнопка под пальцем
                // успевает зарегистрироваться в Compose, остальное уходит игре.
                Overlay.widgetTouched = false
                super.dispatchTouchEvent(event)
                val id = event.getPointerId(event.actionIndex)
                if (Overlay.widgetTouched) gamePointers.remove(id) else gamePointers.add(id)
                if (id in gamePointers) Overlay.forwardToGame(event, index = event.actionIndex)
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                Overlay.forwardToGame(event, android.view.MotionEvent.ACTION_CANCEL)
                super.dispatchTouchEvent(event)
                gamePointers.clear()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE,
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                val ids = IntArray(event.pointerCount) { event.getPointerId(it) }
                val ui = ids.filter { it !in gamePointers }
                val game = ids.filter { it in gamePointers }
                // Кнопки видят только свои пальцы: жест, ушедший камере,
                // их не нажимает и не обрывает поворот.
                if (ui.isNotEmpty()) super.dispatchTouchEvent(subset(event, ui))
                for (id in game) forwardGamePointer(event, id)
                if (event.actionMasked != android.view.MotionEvent.ACTION_MOVE) {
                    gamePointers.remove(event.getPointerId(event.actionIndex))
                }
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Касание игровых пальцев по одному: камера и тап д?льше разбираются в движке. */
    private fun forwardGamePointer(event: android.view.MotionEvent, id: Int) {
        val index = event.findPointerIndex(id)
        if (index < 0) return
        val changed = event.actionIndex == index
        val action = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_UP -> android.view.MotionEvent.ACTION_UP
            android.view.MotionEvent.ACTION_POINTER_UP ->
                if (changed) android.view.MotionEvent.ACTION_UP else android.view.MotionEvent.ACTION_MOVE
            else -> android.view.MotionEvent.ACTION_MOVE
        }
        Overlay.forwardToGame(event, action, index)
    }

    /** Событие только с указателями `ids` (остальные не должны его видеть). */
    private fun subset(
        event: android.view.MotionEvent,
        ids: List<Int>,
    ): android.view.MotionEvent {
        val props = Array(ids.size) { android.view.MotionEvent.PointerProperties() }
        val coords = Array(ids.size) { android.view.MotionEvent.PointerCoords() }
        ids.forEachIndexed { i, id ->
            val from = event.findPointerIndex(id)
            event.getPointerProperties(from, props[i])
            event.getPointerCoords(from, coords[i])
        }
        val changed = ids.indexOf(event.getPointerId(event.actionIndex))
        val shift = android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT
        val action = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_UP ->
                if (ids.size == 1) android.view.MotionEvent.ACTION_UP
                else if (changed >= 0) android.view.MotionEvent.ACTION_POINTER_UP or (changed shl shift)
                else android.view.MotionEvent.ACTION_MOVE
            android.view.MotionEvent.ACTION_POINTER_UP ->
                if (changed >= 0) android.view.MotionEvent.ACTION_POINTER_UP or (changed shl shift)
                else android.view.MotionEvent.ACTION_MOVE
            else -> android.view.MotionEvent.ACTION_MOVE
        }
        return android.view.MotionEvent.obtain(
            event.downTime, event.eventTime, action, ids.size, props, coords,
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags, event.source, event.flags,
        )
    }
}
