package dev.moonmc.game

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as C
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

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
    private const val GEAR = "moonmc/gear.txt"
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
    private var keyboardHidden = false

    private fun applyKeyboard(up: Boolean) {
        if (up == keyboardHidden) return
        keyboardHidden = up
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

    /** Панель открыта: окно получает фокус (иначе поле ника не набрать), закрыта — обратно. */
    private fun panelFocus(open: Boolean) {
        runCatching {
            dialog?.window?.let { window ->
                if (open) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
            }
        }
    }

    /** Шестерня и панель в пикселях: их касания наши, а не игры. */
    @Volatile
    internal var gearAt = Offset(-1f, -1f)

    @Volatile
    internal var gearSide = 0f

    @Volatile
    internal var panelOpen = false

    @Volatile
    internal var screenPx = 0f

    @Volatile
    internal var screenPy = 0f

    @Volatile
    internal var panelPx = 0f

    @Volatile
    internal var panelPy = 0f

    @Volatile
    internal var panelEndGap = 0f

    /** Палец лёг на наш интерфейс: такое касание игре не пересылаем. */
    internal fun hitUi(x: Float, y: Float): Boolean {
        if (gearSide > 0f && gearAt.x >= 0f &&
            x >= gearAt.x && x <= gearAt.x + gearSide &&
            y >= gearAt.y && y <= gearAt.y + gearSide
        ) {
            return true
        }
        if (!panelOpen || panelPx <= 0f) return false
        val left = screenPx - panelPx - panelEndGap
        val top = (screenPy - panelPy) / 2f
        return x >= left && x <= screenPx - panelEndGap && y >= top && y <= top + panelPy
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
        val density = LocalDensity.current
        var opened by remember { mutableStateOf(false) }
        var tab by remember { mutableIntStateOf(0) }
        var inMenu by remember { mutableStateOf(false) }
        var gear by remember { mutableStateOf(readGear(activity)) }
        val gearSize = with(density) { 48.dp.toPx() }
        val panelW = with(density) { 380.dp.toPx() }
        val panelH = with(density) { 440.dp.toPx() }
        val endGap = with(density) { 8.dp.toPx() }
        val openedNow by rememberUpdatedState(opened)
        // Пока движок готовит ресурсы, вместо слоя — экран загрузки.
        var ready by remember { mutableStateOf(false) }
        val startedAt = remember { android.os.SystemClock.elapsedRealtime() }

        // Клавиатуру, меню и готовность движка берём у него самого.
        // Панель открыта — клавиатура нужна для нашего поля, слой не прячем.
        LaunchedEffect(Unit) {
            while (true) {
                val keyboard = runCatching { Native.keyboardShown() }.getOrDefault(false)
                applyKeyboard(keyboard && !openedNow)
                val menu = runCatching { Native.inMenu() }.getOrDefault(false)
                if (menu != inMenu) inMenu = menu
                if (!ready) {
                    // Страховка: старый .so без readyNative не должен прятать слой навсегда.
                    val loaded = runCatching { Native.ready() }.getOrDefault(false)
                    if (loaded || android.os.SystemClock.elapsedRealtime() - startedAt > 8_000) {
                        ready = true
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
        LaunchedEffect(opened) { panelFocus(opened) }

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

        // Движок ещё грузит ресурсы: показываем свой экран вместо мёртвых кнопок.
        if (!ready) {
            LoadingScreen()
            return
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenW = constraints.maxWidth.toFloat()
            val screenH = constraints.maxHeight.toFloat()
            SideEffect {
                gearAt = gear
                gearSide = gearSize
                panelOpen = opened
                screenPx = screenW
                screenPy = screenH
                panelPx = panelW
                panelPy = panelH
                panelEndGap = endGap
            }
            LaunchedEffect(screenW, screenH) {
                if (gear.x < 0f) {
                    gear = Offset(screenW - gearSize - 24f, screenH * 0.32f)
                }
            }
            ControlBoxLayout(
                observedLayout = layout,
                eventHandler = handler,
                // Захват курсора у них = «идёт игра»: от этого зависит видимость
                // слоёв с visibilityType in_game. В меню игровые кнопки скрыты.
                isCursorGrabbing = !inMenu,
                checkOccupiedPointers = { false },
            ) {
                // мир под контролами: их слой рисует только кнопки и джойстик
            }

            // Шестерня: круг, который можно тащить в любое место экрана
            Box(
                Modifier
                    .offset { IntOffset(gear.x.roundToInt(), gear.y.roundToInt()) }
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(C(0xE6151A18))
                    .border(1.dp, C(0x339BE8BE), CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var moved = 0f
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val delta = change.position - last
                                last = change.position
                                moved += delta.getDistance()
                                if (moved > 8f) {
                                    change.consume()
                                    gear = Offset(
                                        (gear.x + delta.x).coerceIn(0f, (screenW - gearSize).coerceAtLeast(0f)),
                                        (gear.y + delta.y).coerceIn(0f, (screenH - gearSize).coerceAtLeast(0f)),
                                    )
                                }
                                if (!change.pressed) {
                                    if (moved <= 8f) opened = !opened
                                    break
                                }
                            }
                            if (moved > 8f) writeGear(activity, gear)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = C(0xFF9BE8BE),
                    modifier = Modifier.size(24.dp),
                )
            }

            AnimatedVisibility(
                visible = opened,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            ) {
                Panel(
                    activity = activity,
                    tab = tab,
                    onTab = { tab = it },
                    onClose = { opened = false },
                )
            }
        }
    }

    /** Пока движок готовит ресурсы: луна Moon и что именно происходит. */
    @Composable
    private fun LoadingScreen() {
        Box(
            Modifier.fillMaxSize().background(C(0xFF0B120F)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("MoonMC", color = C(0xFF9BE8BE), fontSize = 30.sp)
                CircularProgressIndicator(
                    color = C(0xFF9BE8BE),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(38.dp),
                )
                Text("готовим ресурсы…", color = C(0xFFB9C8CE), fontSize = 13.sp)
            }
        }
    }

    /** Панель справа: вдоль левого края вкладки, справа — содержимое. */
    @Composable
    private fun Panel(
        activity: Activity,
        tab: Int,
        onTab: (Int) -> Unit,
        onClose: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier.width(380.dp).height(440.dp),
            shape = RoundedCornerShape(
                topStart = 32.dp,
                bottomStart = 32.dp,
                topEnd = 16.dp,
                bottomEnd = 16.dp,
            ),
            color = C(0xF5101513),
            tonalElevation = 6.dp,
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxHeight().width(64.dp).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TabIcon(Icons.Filled.Settings, "Графика", tab == 0) { onTab(0) }
                    TabIcon(Icons.Filled.Person, "Профили", tab == 1) { onTab(1) }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (tab == 0) "Графика" else "Профили",
                            color = C(0xFF9BE8BE),
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, "Закрыть", tint = C(0xFF8CA0A7))
                        }
                    }
                    if (tab == 0) GraphicsTab(activity) else ProfilesTab()
                }
            }
        }
    }

    @Composable
    private fun TabIcon(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        description: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (selected) C(0xFF9BE8BE) else C(0x00000000),
            modifier = Modifier.size(44.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = description,
                    tint = if (selected) C(0xFF11201A) else C(0xFFB9C8CE),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }

    @Composable
    private fun GraphicsTab(activity: Activity) {
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

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

    @Composable
    private fun ProfilesTab() {
        var profiles by remember { mutableStateOf(readProfiles()) }
        var input by remember { mutableStateOf("") }
        var current by remember { mutableStateOf(runCatching { Native.accountName() }.getOrDefault("")) }
        var pendingDelete by remember { mutableStateOf<String?>(null) }

        fun store(next: Profiles) {
            profiles = next
            writeProfiles(next)
        }

        fun add() {
            val name = input.trim().take(16).trim()
            if (name.isEmpty() || profiles.list.any { it.name == name }) return
            store(profiles.copy(list = profiles.list + Profile(name)))
            input = ""
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(16) },
            singleLine = true,
            placeholder = { Text("ник", fontSize = 13.sp) },
            leadingIcon = {
                IconButton(onClick = { add() }) {
                    Icon(Icons.Filled.Add, "Добавить", tint = C(0xFF9BE8BE))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(profiles.list.sortedByDescending { if (it.pinned) 1 else 0 }, key = { it.name }) { profile ->
                AccountRow(
                    profile = profile,
                    active = profile.name == profiles.active,
                    onPick = {
                        Native.setAccount(profile.name)
                        current = profile.name
                        store(profiles.copy(active = profile.name))
                    },
                    onPin = {
                        store(
                            profiles.copy(
                                list = profiles.list.map {
                                    if (it.name == profile.name) it.copy(pinned = !it.pinned) else it
                                },
                            ),
                        )
                    },
                    onDelete = { pendingDelete = profile.name },
                )
            }
        }
        if (profiles.list.isEmpty()) {
            Text(
                "Введи ник и нажми + — аккаунт появится здесь",
                color = C(0xFF8CA0A7),
                fontSize = 11.sp,
            )
        }
        pendingDelete?.let { name ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Удалить аккаунт?") },
                text = { Text("«$name» будет удалён из списка" + if (name == current) ", игрок останется текущим до следующего выбора." else ".") },
                confirmButton = {
                    TextButton(onClick = {
                        store(
                            profiles.copy(
                                active = if (profiles.active == name) "" else profiles.active,
                                list = profiles.list.filterNot { it.name == name },
                            ),
                        )
                        pendingDelete = null
                    }) { Text("Удалить") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Отмена") }
                },
            )
        }
    }

    @Composable
    private fun AccountRow(
        profile: Profile,
        active: Boolean,
        onPick: () -> Unit,
        onPin: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Surface(
            onClick = onPick,
            shape = RoundedCornerShape(18.dp),
            color = if (active) C(0xFF1E3A2E) else C(0xFF1A201D),
            tonalElevation = 1.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    profile.name,
                    color = if (active) C(0xFF9BE8BE) else C(0xFFE8F1EC),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPin) {
                    Icon(
                        Icons.Filled.Star,
                        "Закрепить",
                        tint = if (profile.pinned) C(0xFF9BE8BE) else C(0x55E8F1EC),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Close,
                        "Удалить",
                        tint = C(0xFF8CA0A7),
                        modifier = Modifier.size(20.dp),
                    )
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

    /** Аккаунт из списка: ник и закреплён ли он. */
    internal data class Profile(val name: String, val pinned: Boolean = false)

    /** Список профилей и выбранный. */
    internal data class Profiles(val active: String = "", val list: List<Profile> = emptyList())

    private fun profilesFile() = File("/sdcard/MoonMC/accounts.json")

    private fun profileOf(item: Any?): Profile? = when (item) {
        is String -> item.ifBlank { null }?.let { Profile(it) }
        is org.json.JSONObject -> item.optString("name").ifBlank { null }
            ?.let { Profile(it, item.optBoolean("pinned")) }
        else -> null
    }

    /** Старый формат `["ник", …]` и новый `{"active", "accounts":[…])}` читаются оба. */
    private fun readProfiles(): Profiles = runCatching {
        val file = profilesFile()
        if (!file.isFile) return@runCatching Profiles()
        val text = file.readText().trim()
        if (text.startsWith("[")) {
            val array = org.json.JSONArray(text)
            Profiles(list = (0 until array.length()).mapNotNull { profileOf(array.opt(it)) })
        } else {
            val obj = org.json.JSONObject(text)
            val array = obj.optJSONArray("accounts") ?: org.json.JSONArray()
            Profiles(
                active = obj.optString("active"),
                list = (0 until array.length()).mapNotNull { profileOf(array.opt(it)) },
            )
        }
    }.getOrDefault(Profiles())

    private fun writeProfiles(profiles: Profiles) {
        runCatching {
            val array = org.json.JSONArray()
            profiles.list.forEach { p ->
                array.put(org.json.JSONObject().put("name", p.name).put("pinned", p.pinned))
            }
            val obj = org.json.JSONObject().put("active", profiles.active).put("accounts", array)
            val file = profilesFile()
            file.parentFile?.mkdirs()
            file.writeText(obj.toString())
        }
    }

    /** Позиция шестерни; (−1, −1) — ещё не ставили (уйдёт в правый край). */
    private fun readGear(activity: Activity): Offset = runCatching {
        val parts = gameDir(GEAR).readText().trim().split(' ')
        Offset(parts[0].toFloat(), parts[1].toFloat())
    }.getOrDefault(Offset(-1f, -1f))

    private fun writeGear(activity: Activity, at: Offset) {
        runCatching {
            val file = gameDir(GEAR)
            file.parentFile?.mkdirs()
            file.writeText("${at.x.roundToInt()} ${at.y.roundToInt()}")
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
                // Судьба пальца решается один раз, на его нажатии: кнопка/наша панель
                // под пальцем — это UI, остальное уходит игре.
                Overlay.widgetTouched = false
                super.dispatchTouchEvent(event)
                val index = event.actionIndex
                val id = event.getPointerId(index)
                // Панель открыта — касания не уходят в игру вообще.
                val ui = Overlay.widgetTouched || Overlay.panelOpen ||
                    Overlay.hitUi(event.getX(index), event.getY(index))
                if (ui) gamePointers.remove(id) else gamePointers.add(id)
                if (!ui) Overlay.forwardToGame(event, index = index)
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
                when {
                    game.isEmpty() -> super.dispatchTouchEvent(event)
                    ui.isEmpty() -> for (id in game) forwardGamePointer(event, id)
                    else -> {
                        super.dispatchTouchEvent(subset(event, ui))
                        for (id in game) forwardGamePointer(event, id)
                    }
                }
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
