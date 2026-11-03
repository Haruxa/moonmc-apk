package dev.moonmc.game

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

    /** Панель профилей открыта: клавиатуру в игре не трогаем (в ней ввод ника). */
    @Volatile
    private var panelOpenNow = false

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

    /** Отчёт по профилям управления — рядом с остальными отчётами. */
    internal fun reportControls(activity: Activity, text: String) {
        report(activity, "moonmc-controls.txt", text)
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
            MoonTheme(activity) { Layer(activity) }
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

    /**
     * Игра просит клавиатуру (чат, поиск). Окно слоя при этом не прячем: закрытие и
     * открытие диалога сбрасывало ввод (фокус уходил из игры посреди набора). Само окно
     * глушит applyKeyboard по настоящей видимости IME. Зовёт Java через рефлексию.
     */
    @JvmStatic
    fun gameKeyboard(show: Boolean) {
        if (panelOpenNow) return
        imeShown = show
    }

    /** Клавиатура занимает низ экрана, но слой остаётся: его Esc нужен, чтобы выйти
     * из чата. Плашек ESC/ввод над клавиатурой больше нет — кнопка в самой клавиатуре
     * отправляет сообщение и закрывает её. */
    private var keyboardHidden = false

    /** Клавиатура реально на экране. Флаг движка для этого не годится: IME убирают
     * своей кнопкой, движок об этом не узнаёт. */
    private fun imeVisible(activity: Activity): Boolean = runCatching {
        val decor = activity.window.decorView
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
        } else {
            val frame = android.graphics.Rect()
            decor.getWindowVisibleDisplayFrame(frame)
            decor.height - frame.bottom > decor.height / 4
        }
    }.getOrDefault(false)

    /** Клавиатура поднялась: слой остаётся на месте и нажимается (Esc из него). */
    private fun applyKeyboard(up: Boolean, activity: Activity) {
        if (up == keyboardHidden) return
        keyboardHidden = up
        runCatching {
            android.util.Log.i("MoonOverlay", "клавиатура: " + (if (up) "показана" else "скрыта"))
        }
    }

    /** Панель открыта: окно получает фокус (иначе поле ника не набрать), закрыта — обратно. */
    private fun panelFocus(open: Boolean) {
        panelOpenNow = open
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

    /** Рамки виджетов слоя (джойстик, кнопки) в пикселях: их касания наши. */
    @Volatile
    internal var widgetBounds: List<androidx.compose.ui.geometry.Rect> = emptyList()

    /** Открыт редактор раскладки: игра не получает касаний, шестерня и панель скрыты. */
    @Volatile
    internal var editorOpen = false

    /** Номер правки профиля: смена профиля заставляет слой перечитать файл. */
    internal val layoutRevision = androidx.compose.runtime.mutableIntStateOf(0)

    internal fun reloadLayout() {
        layoutRevision.intValue = layoutRevision.intValue + 1
    }

    /** Что не удалось перенести из чужого профиля — пишется в отчёт импорта. */
    @Volatile
    internal var importNotes: List<String> = emptyList()

    /** Последние записанные в отчёт рамки: файл перезаписываем только на смене. */
    private var boundsText = ""

    /** Пальцы, которые взял наш слой (джойстик, кнопки): их ведёт Compose, а не игра. */
    internal val widgetPointers = mutableSetOf<Int>()

    /** Палец лёг на наш интерфейс: такое касание игре не пересылаем. */
    internal fun hitUi(x: Float, y: Float): Boolean {
        // Редактор раскладки забирает все касания: под ним идёт игра.
        if (editorOpen) return true
        for (rect in widgetBounds) {
            if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                return true
            }
        }
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

    /** Material 3 Expressive: цвета из системы (Android 12+), иначе фирменная схема Moon. */
    @Composable
    internal fun MoonTheme(context: android.content.Context, content: @Composable () -> Unit) {
        val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
            runCatching { dynamicDarkColorScheme(context) }.getOrNull()
        } else {
            null
        } ?: darkColorScheme(
            primary = C(0xFF9BE8BE),
            onPrimary = C(0xFF0B2019),
            primaryContainer = C(0xFF1E3A2E),
            onPrimaryContainer = C(0xFFC9F5DC),
            secondary = C(0xFF87B8A0),
            surface = C(0xFF0B120F),
            onSurface = C(0xFFE8F1EC),
            surfaceVariant = C(0xFF26332D),
            onSurfaceVariant = C(0xFFB9C8CE),
            surfaceContainer = C(0xFF151A18),
            surfaceContainerHigh = C(0xFF1A201D),
            surfaceContainerLowest = C(0xFF070B09),
            outline = C(0xFF4A5A53),
        )
        MaterialTheme(colorScheme = scheme, content = content)
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
        // Профиль выбирается в панели: «default» — встроенная раскладка из assets.
        return Controls.load(activity, Controls.current())
    }

    @Composable
    private fun Layer(activity: Activity) {
        // Смена профиля в панели пересобирает слой с новым файлом.
        val revision = layoutRevision.intValue
        val layout = remember(revision) { readLayout(activity) }
        val density = LocalDensity.current
        var opened by remember { mutableStateOf(false) }
        var tab by remember { mutableIntStateOf(0) }
        var inMenu by remember { mutableStateOf(false) }
        var gear by remember { mutableStateOf(readGear(activity)) }
        // Профиль в редакторе: своя копия раскладки, «Отмена» её выбрасывает.
        var editing by remember { mutableStateOf<ObservableControlLayout?>(null) }
        var editingName by remember { mutableStateOf("") }
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
                applyKeyboard(imeVisible(activity) && !openedNow, activity)
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
        LaunchedEffect(opened, editing) { panelFocus(opened || editing != null) }

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
            val screen = IntSize(screenW.toInt(), screenH.toInt())
            // В меню касания — движка (его же свайп/тап), рамки виджетов там не нужны:
            // спрятанные кнопки иначе забирали бы тапы по пунктам под собой.
            val world = !inMenu
            // Рамки виджетов слоя читаем в композиции, а не в SideEffect: размеры
            // известны после раскладки, и такое чтение пересобирает блок, когда они готовы.
            val bounds = ArrayList<androidx.compose.ui.geometry.Rect>(8)
            if (world) {
                layout?.layers?.value?.forEach { layer ->
                    layer.normalButtons.value.forEach { bounds.add(it.renderBounds(screen)) }
                    layer.joystickButtons.value.forEach { bounds.add(it.renderBounds(screen)) }
                }
            }
            // Нулевые — ещё не разложенные виджеты: такой палец остаётся игре.
            val shown = bounds.filter { it.width > 0f && it.height > 0f }
            SideEffect {
                gearAt = gear
                gearSide = gearSize
                panelOpen = opened
                screenPx = screenW
                screenPy = screenH
                panelPx = panelW
                panelPy = panelH
                panelEndGap = endGap
                widgetBounds = shown
                editorOpen = editing != null
                // Факты для разбора жалоб на касания: пишем один раз на смену рамок.
                val text = shown.joinToString("\n") {
                    "${it.left.roundToInt()},${it.top.roundToInt()}," +
                        "${it.width.roundToInt()},${it.height.roundToInt()}"
                }
                if (text != boundsText) {
                    boundsText = text
                    if (text.isNotEmpty()) report(activity, "moonmc-widgets.txt", text)
                }
            }
            LaunchedEffect(screenW, screenH) {
                if (gear.x < 0f) {
                    gear = Offset(screenW - gearSize - 24f, screenH * 0.32f)
                }
            }
            val editor = editing
            if (editor != null) {
                // Редактор: своя копия профиля, сохранение — в файл и перечитать слой.
                ControlEditorScreen(
                    layout = editor,
                    name = editingName,
                    onCancel = { editing = null },
                    onSave = {
                        Controls.save(activity, editingName, editor)
                        editing = null
                        reloadLayout()
                    },
                )
            } else {
            ControlBoxLayout(
                observedLayout = layout,
                eventHandler = handler,
                // Захват курсора у них = «идёт игра»: от этого зависит видимость
                // слоёв с visibilityType in_game. В меню игровые кнопки скрыты.
                isCursorGrabbing = !inMenu,
                checkOccupiedPointers = { false },
                // Взятый виджетом палец — наш с первого касания (джойстик тоже).
                onOccupiedPointer = { widgetPointers.add(it.value.toInt()) },
                onReleasePointer = { widgetPointers.remove(it.value.toInt()) },
            ) {
                // мир под контролами: их слой рисует только кнопки и джойстик
            }
            }

            // Шестерня и панель при открытом редакторе уступают ему весь экран.
            if (editor == null) {
            // Шестерня: круг, который можно тащить в любое место экрана
            Box(
                Modifier
                    .offset { IntOffset(gear.x.roundToInt(), gear.y.roundToInt()) }
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // Стартовая позиция и дельта узла: бокс едет под пальцем,
                            // и разница позиций в его координатах не накапливается.
                            val start = gear
                            var drag = Offset.Zero
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                drag += change.positionChange()
                                if (!moved && drag.getDistance() > 8f) moved = true
                                if (moved) {
                                    change.consume()
                                    gear = Offset(
                                        (start.x + drag.x).coerceIn(0f, (screenW - gearSize).coerceAtLeast(0f)),
                                        (start.y + drag.y).coerceIn(0f, (screenH - gearSize).coerceAtLeast(0f)),
                                    )
                                }
                                if (!change.pressed) {
                                    if (!moved) opened = !opened
                                    break
                                }
                            }
                            if (moved) writeGear(activity, gear)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
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
                    onOpenEditor = { name ->
                        val copy = Controls.load(activity, name)
                        if (copy == null) {
                            reportControls(activity, "профиль «" + name + "» не открылся")
                        } else {
                            editingName = name
                            editing = copy
                        }
                    },
                )
            }
            }
        }
    }

    /** Пока движок готовит ресурсы: луна Moon и что именно происходит. */
    @Composable
    private fun LoadingScreen() {
        val scheme = MaterialTheme.colorScheme
        // Дыхание знака и мерцание звёзд: expressive-движение, без тяжёлых эффектов.
        val breath = rememberInfiniteTransition(label = "moon")
        val scale by breath.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 1600, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "scale",
        )
        val twinkle by breath.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
            label = "twinkle",
        )
        Box(
            Modifier.fillMaxSize().background(scheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // Пятна-звёзды по экрану: те же точки, что и в оффлайн-сплаше.
                val dots = 26
                for (i in 0 until dots) {
                    val gx = ((i * 73) % 100) / 100f
                    val gy = ((i * 37) % 100) / 100f
                    drawCircle(
                        color = scheme.primary.copy(alpha = twinkle * 0.35f * (0.4f + gx)),
                        radius = 2.2f + 3f * gy,
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
                        .size(84.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(CircleShape)
                        .background(scheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("☾", color = scheme.onPrimaryContainer, fontSize = 40.sp)
                }
                Text(
                    "MoonMC",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                )
                CircularProgressIndicator(
                    color = scheme.primary,
                    trackColor = scheme.surfaceContainerHigh,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(38.dp),
                )
                Text(
                    "готовим ресурсы…",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
        onOpenEditor: (String) -> Unit,
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
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
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
                    TabIcon(Icons.Filled.Build, "Управление", tab == 2) { onTab(2) }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (tab) {
                                0 -> "Графика"
                                1 -> "Профили"
                                else -> "Управление"
                            },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    when (tab) {
                        0 -> GraphicsTab(activity)
                        1 -> ProfilesTab()
                        else -> ControlsTab(activity, onOpenEditor)
                    }
                }
            }
        }
    }

    /** Профили управления: выбор, создание и вход в редактор. */
    @Composable
    private fun ControlsTab(activity: Activity, onOpenEditor: (String) -> Unit) {
        var names by remember { mutableStateOf(Controls.names()) }
        var current by remember { mutableStateOf(Controls.current()) }
        var expanded by remember { mutableStateOf(false) }
        var input by remember { mutableStateOf("") }
        var note by remember { mutableStateOf<String?>(null) }
        var renaming by remember { mutableStateOf<String?>(null) }
        var pendingDelete by remember { mutableStateOf<String?>(null) }

        fun refresh() {
            names = Controls.names()
            current = Controls.current()
        }

        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val target = Controls.safeName(
                    (Controls.names().size + 1).toString() + " import"
                )
                val error = Controls.import(activity, uri, target)
                note = error ?: "Импортировано: $target"
                // что не удалось перенести из Pojav — сразу в отчёт
                if (importNotes.isNotEmpty()) {
                    reportControls(activity, "импорт: " + importNotes.joinToString(", "))
                }
                refresh()
                reloadLayout()
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Текущий профиль: тап раскрывает список.
            Surface(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Текущий профиль",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(current, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Icon(Icons.Filled.KeyboardArrowDown, "Список профилей")
                }
            }

            if (expanded) {
                LazyColumn(Modifier.height(168.dp)) {
                    items(names, key = { it }) { name ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                Controls.setCurrent(name)
                                current = name
                                expanded = false
                                reloadLayout()
                            }) {
                                Text(
                                    name,
                                    fontSize = 14.sp,
                                    color = if (name == current) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (name != Controls.BUILTIN) {
                                TextButton(onClick = { renaming = name }) { Text("Имя", fontSize = 11.sp) }
                                TextButton(onClick = { pendingDelete = name }) {
                                    Text("Удалить", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { onOpenEditor(current) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Открыть редактор") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(40) },
                    singleLine = true,
                    label = { Text("Введите название профиля", fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val name = Controls.safeName(input)
                    if (!Controls.create(activity, name)) {
                        note = "Имя занято или пустое"
                    } else {
                        input = ""
                        note = "Создан профиль: $name"
                        refresh()
                        reloadLayout()
                    }
                }) {
                    Icon(Icons.Filled.Add, "Создать профиль", tint = MaterialTheme.colorScheme.primary)
                }
            }

            OutlinedButton(
                onClick = { pick.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Импорт (Zalith / Pojav)") }

            note?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                "Профили лежат в /sdcard/MoonMC/controls — файл из Zalith можно просто скопировать сюда",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        renaming?.let { name ->
            var value by remember(name) { mutableStateOf(name) }
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Новое имя профиля") },
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.take(40) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (!Controls.rename(name, value)) {
                            note = "Имя занято или пустое"
                        }
                        renaming = null
                        refresh()
                    }) { Text("Переименовать") }
                },
                dismissButton = { TextButton(onClick = { renaming = null }) { Text("Отмена") } },
            )
        }

        pendingDelete?.let { name ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Удалить профиль «$name»?") },
                text = { Text("Файл профиля пропадёт безвозвратно.") },
                confirmButton = {
                    TextButton(onClick = {
                        if (!Controls.delete(name)) note = "Текущий профиль удалить нельзя"
                        pendingDelete = null
                        refresh()
                    }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
            )
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
            color = if (selected) MaterialTheme.colorScheme.primary else C(0x00000000),
            modifier = Modifier.size(44.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = description,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text("Лимит FPS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Icon(Icons.Filled.Add, "Добавить", tint = MaterialTheme.colorScheme.primary)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            color = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    profile.name,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPin) {
                    Icon(
                        Icons.Filled.Star,
                        "Закрепить",
                        tint = if (profile.pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Close,
                        "Удалить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp)
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

    /** Пальцы, отданные игре (камера/меню), по id. */
    private val gamePointers = mutableSetOf<Int>()

    /** Где палец нажал и сколько прошёл: длинный жест — не клик. */
    private val gameStart = mutableMapOf<Int, android.graphics.PointF>()
    private val gameMoved = mutableMapOf<Int, Float>()

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
                val index = event.actionIndex
                val id = event.getPointerId(index)
                val x = event.getX(index)
                val y = event.getY(index)
                Overlay.widgetTouched = false
                // Слой успевает взять палец внутри super (onOccupiedPointer): джойстик
                // и кнопки опознаются с первого касания.
                super.dispatchTouchEvent(event)
                val ui = Overlay.panelOpen || id in Overlay.widgetPointers ||
                    Overlay.widgetTouched || Overlay.hitUi(x, y)
                if (ui) {
                    gamePointers.remove(id)
                } else {
                    gamePointers.add(id)
                    gameStart[id] = android.graphics.PointF(x, y)
                    gameMoved[id] = 0f
                    // Меню движок разбирает сам: свайп — колесо, короткий тап — клик.
                    Overlay.forwardToGame(event, index = index)
                }
                return true
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                Overlay.forwardToGame(event, android.view.MotionEvent.ACTION_CANCEL)
                super.dispatchTouchEvent(event)
                gamePointers.clear()
                gameStart.clear()
                gameMoved.clear()
                return true
            }
            android.view.MotionEvent.ACTION_MOVE,
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_POINTER_UP -> {
                val ids = IntArray(event.pointerCount) { event.getPointerId(it) }
                val ui = ids.filter { it !in gamePointers }
                val game = ids.filter { it in gamePointers }
                for (id in game) {
                    val i = event.findPointerIndex(id)
                    val start = gameStart[id]
                    if (i < 0 || start == null) continue
                    val dx = event.getX(i) - start.x
                    val dy = event.getY(i) - start.y
                    gameMoved[id] = kotlin.math.hypot(dx, dy)
                }
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
                    val id = event.getPointerId(event.actionIndex)
                    gamePointers.remove(id)
                    gameStart.remove(id)
                    gameMoved.remove(id)
                }
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Касание игровых пальцев по одному: камера и тап дальше разбираются в движке. */
    private fun forwardGamePointer(event: android.view.MotionEvent, id: Int) {
        val index = event.findPointerIndex(id)
        if (index < 0) return
        val changed = event.actionIndex == index
        val slop = 24f * resources.displayMetrics.density
        val dragged = (gameMoved[id] ?: 0f) > slop
        val action = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_UP ->
                if (dragged) android.view.MotionEvent.ACTION_CANCEL
                else android.view.MotionEvent.ACTION_UP
            android.view.MotionEvent.ACTION_POINTER_UP ->
                if (!changed) android.view.MotionEvent.ACTION_MOVE
                else if (dragged) android.view.MotionEvent.ACTION_CANCEL
                else android.view.MotionEvent.ACTION_UP
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
