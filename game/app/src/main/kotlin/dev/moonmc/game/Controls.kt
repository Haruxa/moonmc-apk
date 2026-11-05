/*
 * MoonMC — игровой слой (GPL-3.0).
 *
 * Профили управления: JSON-файлы LayerController в /sdcard/MoonMC/controls.
 * Формат тот же, что у Zalith Launcher 2, поэтому чужой профиль достаточно
 * положить в папку (или импортировать) и выбрать его в панели.
 */

package dev.moonmc.game

import android.app.Activity
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
import com.movtery.layer_controller.layout.loadLayoutFromString
import com.movtery.layer_controller.layout.saveLayoutToString
import com.movtery.layer_controller.observable.ObservableControlLayout
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

    /** Профили: встроенный первым, дальше файлы по алфавиту. Нет доступа — только встроенный. */
    fun names(): List<String> {
        val files = runCatching {
            dir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.map { it.name.removeSuffix(".json") }
                ?.sorted()
                .orEmpty()
        }.getOrDefault(emptyList())
        return listOf(BUILTIN) + files.filter { it != BUILTIN }
    }

    fun current(): String {
        val name = runCatching { File(dir(), CURRENT).readText().trim() }.getOrDefault("")
        if (name.isEmpty()) return BUILTIN
        val exists = runCatching { name == BUILTIN || file(name).isFile }.getOrDefault(false)
        return if (exists) name else BUILTIN
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

    /**
     * Импорт чужого профиля: Zalith/LayerController JSON или Pojav controlmap,
     * в том числе из ZIP. Пустой или битый файл не записывается и не выбирается;
     * существующее имя не перезаписывается — берётся свободное с суффиксом.
     */
    fun import(activity: Activity, source: File, name: String): ImportResult {
        val metrics = activity.resources.displayMetrics
        val read = ControlImport.read(source, metrics.widthPixels, metrics.heightPixels, metrics.density)
        val layout = read.layout
        if (read.error != null || layout == null) {
            // Битый импорт ничего не пишет и не делает текущим.
            Overlay.importNotes = read.warnings
            return read
        }
        val target = uniqueName(name)
        val text = runCatching { saveLayoutToString(layout.pack()) }.getOrElse { error ->
            Overlay.reportControls(activity, "импорт «" + source.name + "»: " + error.stackTraceToString())
            return ImportResult(error = "Раскладка не сохраняется: " + (error.message ?: "ошибка"))
        }
        if (file(target).exists()) {
            return ImportResult(error = "Имя «" + target + "» уже занято")
        }
        val writeError = runCatching {
            val tmp = File(dir(), target + ".json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file(target))) {
                tmp.delete()
                throw IllegalStateException("не удалось создать файл профиля")
            }
            null
        }.getOrElse { error ->
            Overlay.reportControls(activity, "импорт «" + source.name + "»: " + error.stackTraceToString())
            error.message ?: "не записалось"
        }
        if (writeError != null) return ImportResult(error = "Не записалось: " + writeError)
        setCurrent(target)
        Overlay.importNotes = read.warnings
        return ImportResult(
            layout = layout,
            profile = target,
            summary = read.summary,
            warnings = read.warnings,
        )
    }

    /**
     * Свободное имя профиля: занятые имена не перезаписываем, добавляем суффикс.
     * Встроенный профиль тоже не трогаем — импорт ложится рядом с суффиксом.
     */
    private fun uniqueName(raw: String): String {
        val base = safeName(raw)
        if (base != BUILTIN && !file(base).exists()) return base
        for (n in 2..99) {
            val candidate = safeName(base.take(34) + "-" + n)
            if (candidate != BUILTIN && !file(candidate).exists()) return candidate
        }
        return safeName(base.take(30) + "-" + (System.currentTimeMillis() % 100000))
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
