/*
 * MoonMC — игровой слой (GPL-3.0).
 *
 * Профили управления: JSON-файлы LayerController в /sdcard/MoonMC/controls.
 * Формат тот же, что у Zalith Launcher 2, поэтому чужой профиль достаточно
 * положить в папку (или импортировать) и выбрать его в панели.
 */

package dev.moonmc.game

import android.app.Activity
import android.net.Uri
import com.movtery.inputmap.keycodes.ControlEventKeycode
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.ButtonSize
import com.movtery.layer_controller.data.CenterPosition
import com.movtery.layer_controller.data.DefaultDirectionEvents
import com.movtery.layer_controller.data.DefaultLockEvents
import com.movtery.layer_controller.data.DefaultSize
import com.movtery.layer_controller.data.JoystickData
import com.movtery.layer_controller.data.JoystickTriggerMode
import com.movtery.layer_controller.data.NormalData
import com.movtery.layer_controller.data.TextAlignment
import com.movtery.layer_controller.data.TextData
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.data.lang.createTranslatable
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.layout.ControlLayout
import com.movtery.layer_controller.layout.EmptyControlLayout
import com.movtery.layer_controller.layout.createNewLayer
import com.movtery.layer_controller.layout.loadLayoutFromString
import com.movtery.layer_controller.layout.saveLayoutToString
import com.movtery.layer_controller.observable.ObservableControlLayout
import org.json.JSONObject
import java.io.File

/** Профили управления: список, выбор, создание, импорт Zalith/Pojav. */
object Controls {
    /** Встроенный профиль: его нельзя удалить, он же основа нового. */
    const val BUILTIN = "default"

    private const val CURRENT = "current.txt"
    private const val FOLDER = "/sdcard/MoonMC/controls"

    /** Где раньше лежала своя раскладка — переносим её в профили при первом запуске. */
    private const val LEGACY = "/sdcard/MoonMC/horizon/touch/zalith"

    private fun dir() = File(FOLDER).apply { mkdirs() }

    /** Имя файла: только буквы, цифры, пробел, точка, дефис, подчёркивание. */
    fun safeName(raw: String): String {
        val clean = raw.trim().map { c ->
            if (c.isLetterOrDigit() || c == ' ' || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trim().trim('.').take(40)
        return clean.ifEmpty { "profile" }
    }

    private fun file(name: String) = File(dir(), safeName(name) + ".json")

    /** Профили: встроенный первым, дальше файлы по алфавиту. */
    fun names(): List<String> {
        val files = dir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            .orEmpty()
        return listOf(BUILTIN) + files.filter { it != BUILTIN }
    }

    fun current(): String {
        val name = runCatching { File(dir(), CURRENT).readText().trim() }.getOrDefault("")
        return if (name.isNotEmpty() && (name == BUILTIN || file(name).isFile)) name else BUILTIN
    }

    fun setCurrent(name: String) {
        runCatching { File(dir(), CURRENT).writeText(safeName(name)) }
    }

    /** Встроенная раскладка из assets: она же образец для новых профилей. */
    private fun builtin(activity: Activity): String =
        activity.assets.open("default_layout.json").bufferedReader().use { it.readText() }

    fun load(activity: Activity, name: String): ObservableControlLayout? {
        val text = runCatching {
            if (name == BUILTIN || !file(name).isFile) builtin(activity) else file(name).readText()
        }.getOrElse { error ->
            Overlay.reportControls(activity, "чтение «" + name + "»: " + error.stackTraceToString())
            return null
        }
        return runCatching { ObservableControlLayout(loadLayoutFromString(text)) }
            .getOrElse { error ->
                Overlay.reportControls(activity, "разбор «" + name + "»: " + error.stackTraceToString())
                // Битая своя раскладка не должна оставить игру без управления.
                runCatching { ObservableControlLayout(loadLayoutFromString(builtin(activity))) }.getOrNull()
            }
    }

    /** Сохранить профиль. Встроенный не перезаписываем — рядом появляется копия. */
    fun save(activity: Activity, name: String, layout: ObservableControlLayout): String? {
        val target = if (name == BUILTIN) BUILTIN + " copy" else name
        return runCatching {
            val text = saveLayoutToString(layout.pack())
            val tmp = File(dir(), safeName(target) + ".json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file(target))) {
                file(target).writeText(text)
                tmp.delete()
            }
            null
        }.getOrElse { error ->
            Overlay.reportControls(activity, "запись «" + name + "»: " + error.stackTraceToString())
            error.message ?: "не записалось"
        }
    }

    /** Новый профиль копией встроенного; false — имя занято. */
    fun create(activity: Activity, name: String): Boolean {
        val clean = safeName(name)
        if (clean == BUILTIN || file(clean).exists()) return false
        return runCatching {
            file(clean).writeText(builtin(activity))
            setCurrent(clean)
            true
        }.getOrDefault(false)
    }

    fun duplicate(name: String, asName: String): Boolean {
        val clean = safeName(asName)
        val from = file(name)
        if (clean == BUILTIN || !from.isFile || file(clean).exists()) return false
        return runCatching {
            file(clean).writeText(from.readText())
            true
        }.getOrDefault(false)
    }

    fun rename(from: String, to: String): Boolean {
        val clean = safeName(to)
        val source = file(from)
        if (from == BUILTIN || clean == BUILTIN || !source.isFile || file(clean).exists()) return false
        return runCatching {
            val done = source.renameTo(file(clean))
            if (done && current() == from) setCurrent(clean)
            done
        }.getOrDefault(false)
    }

    fun delete(name: String): Boolean {
        if (name == BUILTIN || name == current()) return false
        return runCatching { file(name).delete() }.getOrDefault(false)
    }

    /** Разовый перенос старой раскладки из папки игры в профили. */
    fun migrateLegacy() {
        runCatching {
            val old = File(LEGACY).listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: return
            old.forEach { source ->
                val target = file(source.name.removeSuffix(".json"))
                if (!target.exists()) target.writeText(source.readText())
            }
        }
    }

    /** Импорт чужого профиля: Zalith/LayerController JSON или Pojav controlmap. */
    fun import(activity: Activity, uri: Uri, name: String): String? {
        val text = runCatching {
            activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return "Файл не читается"
        if (text.isBlank()) return "Файл пустой"

        val layout = when {
            text.contains("\"editorVersion\"") -> runCatching {
                ObservableControlLayout(loadLayoutFromString(text))
            }.getOrNull() ?: return "Раскладка Zalith повреждена"
            text.contains("mControlDataList") -> runCatching { fromPojav(text) }.getOrNull()
                ?: return "Профиль Pojav повреждён"
            else -> return "Неизвестный формат"
        }
        val clean = safeName(name)
        val target = if (clean == BUILTIN) BUILTIN + " import" else clean
        return runCatching {
            file(target).writeText(saveLayoutToString(layout.pack()))
            setCurrent(target)
            null
        }.getOrElse { "Не записалось: " + it.message }
    }

    /* ----------------------------- Pojav ------------------------------ */

    /** Код клавиши → имя события GLFW: таблица из самой библиотеки InputMap. */
    private val codes: Map<Int, String> by lazy {
        val map = HashMap<Int, String>(256)
        runCatching {
            ControlEventKeycode::class.java.fields.forEach { field ->
                if (field.type != String::class.java) return@forEach
                val name = runCatching { field.get(null) as? String }.getOrNull() ?: return@forEach
                val code = runCatching { ControlEventKeycode.getKeycodeFromEvent(name) }.getOrNull()
                    ?: return@forEach
                map[code.toInt()] = name
            }
        }
        map
    }

    /** Событие из кода Pojav: мышь — launcher_event, остальное — клавиша. */
    fun event(code: Int): ClickEvent? {
        val name = codes[code] ?: return null
        val type = if (name.startsWith("GLFW_MOUSE_BUTTON")) {
            ClickEvent.Type.LauncherEvent
        } else {
            ClickEvent.Type.Key
        }
        return ClickEvent(type, name)
    }

    /** Позиция Pojav (доли экрана или уже пиксели) → 0..10000. */
    private fun position(obj: JSONObject): ButtonPosition {
        val x = obj.optDouble("dynamicX", obj.optDouble("x", 0.0))
        val y = obj.optDouble("dynamicY", obj.optDouble("y", 0.0))
        fun toPercentage(value: Double, axis: Double): Int {
            val fraction = if (value <= 1.0) value else value / axis.coerceAtLeast(1.0)
            return (fraction.coerceIn(0.0, 1.0) * 10000.0).toInt()
        }
        return ButtonPosition(toPercentage(x, 1920.0), toPercentage(y, 1080.0))
    }

    /** Кнопка Pojav в терминах LayerController. */
    private fun button(obj: JSONObject, index: Int): NormalData? {
        val keys = obj.optJSONArray("keycodes") ?: return null
        val events = ArrayList<ClickEvent>(2)
        for (i in 0 until keys.length()) {
            val pair = keys.optJSONArray(i) ?: continue
            val code = if (pair.length() > 1) {
                pair.optInt(1, Int.MIN_VALUE)
            } else {
                pair.optInt(0, Int.MIN_VALUE)
            }
            event(code)?.let { if (it !in events) events.add(it) }
        }
        if (events.isEmpty()) return null
        val name = obj.optString("name", "").ifBlank { "Кнопка " + (index + 1) }
        return NormalData(
            text = createTranslatable(name),
            uuid = newUuid(),
            position = position(obj),
            buttonSize = sizeOf(obj),
            buttonStyle = null,
            textAlignment = TextAlignment.Center,
            textBold = false,
            textItalic = false,
            textUnderline = false,
            visibilityType = VisibilityType.ALWAYS,
            _clickEvents = events,
            isSwipple = true,
            isPenetrable = false,
            isToggleable = false,
        )
    }

    /** Размер Pojav (dp) в наших единицах. */
    private fun sizeOf(obj: JSONObject): ButtonSize {
        val width = obj.optDouble("width", 50.0).toFloat()
        val height = obj.optDouble("height", 50.0).toFloat()
        val w = width.coerceIn(20f, 160f)
        val h = height.coerceIn(20f, 160f)
        return ButtonSize(
            type = ButtonSize.Type.Dp,
            widthDp = w,
            heightDp = h,
            widthPercentage = (w * 90f).toInt().coerceIn(1000, 6000),
            heightPercentage = (h * 90f).toInt().coerceIn(1000, 6000),
            widthReference = ButtonSize.Reference.ScreenHeight,
            heightReference = ButtonSize.Reference.ScreenHeight,
        )
    }

    /** Весь controlmap Pojav → одна раскладка с кнопками и стиком. */
    private fun fromPojav(text: String): ObservableControlLayout {
        val root = JSONObject(text)
        val list = root.optJSONArray("mControlDataList")
            ?: throw IllegalArgumentException("mControlDataList")
        // Пустая раскладка библиотеки: дальше её наполняем тем же API, что редактор.
        val layout = ObservableControlLayout(EmptyControlLayout)
        val layer = layout.addLayer(createNewLayer("Pojav"))
        var joystick: JoystickData? = null
        val skipped = ArrayList<String>(4)
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            when (obj.optString("type", "")) {
                "button" -> {
                    val made = button(obj, i)
                    if (made == null) skipped.add("кнопка #" + (i + 1)) else layer.addNormalButton(made)
                }
                "joystick" -> if (joystick == null) {
                    joystick = JoystickData(
                        uuid = newUuid(),
                        position = position(obj),
                        sizeType = ButtonSize.Type.Percentage,
                        sizeDp = 160f,
                        sizePercentage = 2600,
                        visibilityType = VisibilityType.ALWAYS,
                        joystickStyleId = null,
                        deadZoneRatio = 0.12f,
                        lockThreshold = 0.3f,
                        canLock = true,
                        triggerMode = JoystickTriggerMode.DRAG,
                        directionEvents = DefaultDirectionEvents,
                        lockEvents = DefaultLockEvents,
                    )
                } else {
                    skipped.add("второй стик")
                }
                else -> skipped.add(obj.optString("type", "?"))
            }
        }
        joystick?.let { layer.addJoystickButton(it) }
        Overlay.importNotes = skipped
        return layout
    }

    /** Случайный uuid виджета в формате библиотеки. */
    fun newUuid(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(18)

    /** Центр экрана — куда попадает новый виджет. */
    fun newPosition(): ButtonPosition = CenterPosition

    /** Размер нового виджета по умолчанию. */
    fun newSize(): ButtonSize = DefaultSize

    /** Новый виджет-кнопка: клавиша не назначена, текст пустой. */
    fun newButton(): NormalData = NormalData(
        text = createTranslatable(""),
        uuid = newUuid(),
        position = newPosition(),
        buttonSize = newSize(),
        buttonStyle = null,
        textAlignment = TextAlignment.Center,
        textBold = false,
        textItalic = false,
        textUnderline = false,
        visibilityType = VisibilityType.ALWAYS,
        _clickEvents = emptyList(),
        isSwipple = true,
        isPenetrable = false,
        isToggleable = false,
    )

    /** Новый стик: заводские WASD и DRAG, как в Zalith. */
    fun newJoystick(): JoystickData = JoystickData(
        uuid = newUuid(),
        position = newPosition(),
        sizeType = ButtonSize.Type.Percentage,
        sizeDp = 160f,
        sizePercentage = 2600,
        visibilityType = VisibilityType.ALWAYS,
        joystickStyleId = null,
        deadZoneRatio = 0.12f,
        lockThreshold = 0.3f,
        canLock = true,
        triggerMode = JoystickTriggerMode.DRAG,
        directionEvents = DefaultDirectionEvents,
        lockEvents = DefaultLockEvents,
    )

    /** Новый текст-виджет. */
    fun newText(label: String): TextData = TextData(
        text = createTranslatable(label),
        uuid = newUuid(),
        position = newPosition(),
        buttonSize = newSize(),
        buttonStyle = null,
        visibilityType = VisibilityType.ALWAYS,
    )
}
