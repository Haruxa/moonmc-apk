/*
 * MoonMC — импорт чужих раскладок управления (GPL-3.0).
 *
 * Понимает два семейства файлов:
 *  • Zalith Launcher / LayerController — JSON с editorVersion и layers;
 *    разбор отдаётся штатному loadLayoutFromString (он же обновляет версии 1…12).
 *  • PojavLauncher — controlmap с mControlDataList / mJoystickDataList / mDrawerDataList.
 *
 * ZIP читается только в память: записей не больше 64, каждая не больше 4 МБ,
 * суммарно не больше 16 МБ. Пути записей проверяются, но на диск ничего не пишется,
 * поэтому zip-slip невозможен по построению.
 *
 * Выражения dynamicX/dynamicY разбирает маленький рекурсивный парсер (без eval):
 * числа, ${переменные}, px()/dp(), + - * / и скобки. Семантика сверена с Pojav
 * (ControlData.insertDynamicPos / fillConversionMap): x и y — левый верхний угол
 * в пикселях, ${width}/${height} и px() — уже в пикселях, ${margin} = 2 dp,
 * ${preferred_scale} = PREF_BUTTONSIZE (в файле — scaledAt), top/left = 0,
 * right/bottom = экран минус размер кнопки.
 */

package dev.moonmc.game

import androidx.compose.ui.graphics.Color
import com.movtery.inputmap.keycodes.ControlEventKeycode
import com.movtery.layer_controller.data.ButtonPosition
import com.movtery.layer_controller.data.ButtonShape
import com.movtery.layer_controller.data.ButtonSize
import com.movtery.layer_controller.data.ButtonStyle
import com.movtery.layer_controller.data.DefaultDirectionEvents
import com.movtery.layer_controller.data.DefaultLockEvents
import com.movtery.layer_controller.data.JoystickData
import com.movtery.layer_controller.data.JoystickTriggerMode
import com.movtery.layer_controller.data.NormalData
import com.movtery.layer_controller.data.TextAlignment
import com.movtery.layer_controller.data.VisibilityType
import com.movtery.layer_controller.data.lang.createTranslatable
import com.movtery.layer_controller.event.ClickEvent
import com.movtery.layer_controller.layout.EmptyControlLayout
import com.movtery.layer_controller.layout.createNewLayer
import com.movtery.layer_controller.layout.loadLayoutFromString
import com.movtery.layer_controller.observable.ObservableControlLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/**
 * Итог импорта: либо ошибка, либо готовая раскладка со счётчиками и предупреждениями.
 * Публичный тип: его возвращает публичный [Controls.import], и из него панель строит сообщение.
 */
data class ImportResult(
    val error: String? = null,
    val layout: ObservableControlLayout? = null,
    /** Имя профиля, под которым раскладка записана (после уникализации). */
    val profile: String? = null,
    /** Короткая сводка для панели: что и сколько перенесено. */
    val summary: String = "",
    /** Что не перенесено и почему — целиком уходит в moonmc-controls.txt. */
    val warnings: List<String> = emptyList(),
)

private const val MAX_JSON_BYTES = 4 * 1024 * 1024
private const val MAX_ZIP_BYTES = 8L * 1024 * 1024
private const val MAX_UNPACKED_BYTES = 16L * 1024 * 1024
private const val MAX_ENTRIES = 64
private const val MAX_EXPRESSION_CHARS = 512
private const val MAX_EXPRESSION_DEPTH = 32
private const val MAX_BUTTON_DP = 400f
private const val MIN_BUTTON_DP = 5f

/** Совпадает с EDITOR_VERSION библиотеки LayerController. */
private const val LAST_ZALITH_VERSION = 12

/** Служебная ошибка разбора: текст показывается пользователю как предупреждение. */
private class ImportError(message: String) : Exception(message)

/** Импорт раскладок из .json и .zip: только чтение, без распаковки на диск. */
internal object ControlImport {
    fun read(file: File, screenW: Int, screenH: Int, density: Float): ImportResult {
        val width = screenW.takeIf { it > 0 } ?: 1280
        val height = screenH.takeIf { it > 0 } ?: 720
        val dpi = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val name = file.name.lowercase()
        return when {
            name.endsWith(".zip") -> fromZip(file, width, height, dpi)
            else -> {
                if (file.length() > MAX_JSON_BYTES) {
                    return ImportResult(error = "Файл больше 4 МБ")
                }
                val bytes = try {
                    file.inputStream().buffered().use { readLimited(it, MAX_JSON_BYTES) }
                } catch (e: Exception) {
                    return ImportResult(error = "Файл не читается: " + (e.message ?: "ошибка"))
                }
                fromBytes(bytes, width, height, dpi)
            }
        }
    }

    /* ------------------------------- ZIP ------------------------------- */

    private fun fromZip(file: File, width: Int, height: Int, density: Float): ImportResult {
        if (file.length() > MAX_ZIP_BYTES) return ImportResult(error = "ZIP больше 8 МБ")
        var entries = 0
        var unpacked = 0L
        var best: ImportResult? = null
        var bestName = ""
        val rejected = ArrayList<String>(3)
        val oversized = ArrayList<String>(2)
        try {
            ZipInputStream(file.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) throw ImportError("в ZIP больше $MAX_ENTRIES записей")
                    val entryName = entry.name ?: ""
                    if (!entryNameOk(entryName)) throw ImportError("недопустимый путь в ZIP: «$entryName»")
                    if (entry.isDirectory) continue
                    val data = try {
                        readLimited(zip, MAX_JSON_BYTES)
                    } catch (e: ImportError) {
                        // Крупная посторонняя запись (картинка, видео) не должна отменять
                        // импорт лежащей рядом раскладки. Остаток дочитываем в общий
                        // бюджет, чтобы zip-бомба по-прежнему обрывалась.
                        unpacked += drain(zip, MAX_UNPACKED_BYTES - unpacked)
                        oversized.add(entryName)
                        continue
                    }
                    unpacked += data.size
                    if (unpacked > MAX_UNPACKED_BYTES) throw ImportError("ZIP распаковывается больше чем в 16 МБ")
                    if (!looksLikeJson(entryName, data)) continue
                    val one = fromBytes(data, width, height, density)
                    if (one.layout == null) {
                        rejected.add(entryName + " — " + (one.error ?: "не разобрано"))
                        continue
                    }
                    if (best == null || matchesArchiveName(entryName, file.name)) {
                        best = one
                        bestName = entryName
                    }
                }
            }
        } catch (e: Exception) {
            return ImportResult(error = "ZIP не читается: " + (e.message ?: "ошибка"))
        }
        val found = best ?: return ImportResult(
            error = "В ZIP нет валидной раскладки" +
                if (rejected.isEmpty()) "" else " (" + rejected.joinToString("; ") + ")"
        )
        val warnings = found.warnings +
            (if (oversized.isEmpty()) {
                emptyList()
            } else {
                listOf("в ZIP пропущены крупные записи: " + oversized.joinToString("; "))
            }) +
            (if (rejected.isEmpty()) {
                emptyList()
            } else {
                listOf("в ZIP пропущены: " + rejected.joinToString("; "))
            })
        return found.copy(
            warnings = warnings,
            summary = "из ZIP «$bestName»: " + found.summary,
        )
    }

    private fun entryNameOk(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains(':')) return false
        return name.split('/').none { it == ".." }
    }

    /**
     * Что в ZIP стоит пробовать разобрать: `.json` или текст, начинающийся с «{».
     * Чужие архивы иногда кладут controlmap вообще без расширения; бинарь не трогаем.
     */
    private fun looksLikeJson(name: String, data: ByteArray): Boolean {
        if (name.endsWith(".json", ignoreCase = true)) return true
        var at = 0
        // BOM UTF-8 не должен мешать распознаванию.
        if (data.size >= 3 &&
            (data[0].toInt() and 0xFF) == 0xEF &&
            (data[1].toInt() and 0xFF) == 0xBB &&
            (data[2].toInt() and 0xFF) == 0xBF
        ) {
            at = 3
        }
        while (at < data.size && (data[at] == ' '.code.toByte() || data[at] == '\n'.code.toByte() ||
                data[at] == '\r'.code.toByte() || data[at] == '\t'.code.toByte())
        ) {
            at++
        }
        return at < data.size && data[at] == '{'.code.toByte()
    }

    private fun matchesArchiveName(entryName: String, archiveName: String): Boolean {
        val base = archiveName.substringBeforeLast('.').lowercase()
        val entry = entryName.substringAfterLast('/').substringBeforeLast('.').lowercase()
        return base.isNotEmpty() && entry == base
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ImportError("запись больше " + (limit / 1024 / 1024) + " МБ")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Дочитывает запись, которую не стали держать в памяти, и считает байты:
     * превышение общего бюджета обрывает импорт, поэтому распаковка остаётся ограниченной.
     */
    private fun drain(input: InputStream, budget: Long): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > budget) throw ImportError("ZIP распаковывается больше чем в 16 МБ")
        }
    }

    /* ------------------------------ формат ----------------------------- */

    private fun fromBytes(bytes: ByteArray, screenW: Int, screenH: Int, density: Float): ImportResult {
        val text = decode(bytes).trim()
        if (text.isEmpty()) return ImportResult(error = "файл пустой")
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ImportResult(error = "не JSON: " + (e.message ?: "разбор"))
        }
        // Распознаём по структуре, а не по подстроке: так «editorVersion» в тексте
        // кнопки не превращается в раскладку Zalith.
        val version = root.opt("editorVersion")
        if (version is Number && root.optJSONArray("layers") != null) {
            return zalith(text, version.toInt())
        }
        val controls = root.optJSONArray("mControlDataList")
        if (controls != null) return pojav(root, controls, screenW, screenH, density)
        return ImportResult(error = "неизвестный формат: нет editorVersion/layers и mControlDataList")
    }

    /** UTF-8 с BOM и UTF-16LE/BE с BOM; остальное — UTF-8. */
    private fun decode(bytes: ByteArray): String {
        fun at(index: Int) = bytes[index].toInt() and 0xFF
        return when {
            bytes.size >= 3 && at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            bytes.size >= 2 && at(0) == 0xFF && at(1) == 0xFE ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 2 && at(0) == 0xFE && at(1) == 0xFF ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }
    }

    /* ------------------------------ Zalith ----------------------------- */

    private fun zalith(text: String, version: Int): ImportResult {
        if (version < 1 || version > LAST_ZALITH_VERSION) {
            return ImportResult(
                error = "Zalith: версия $version не поддерживается (1…$LAST_ZALITH_VERSION)"
            )
        }
        val layout = try {
            ObservableControlLayout(loadLayoutFromString(text))
        } catch (e: Exception) {
            return ImportResult(error = "Zalith: " + (e.message ?: "повреждённая раскладка"))
        }
        var layers = 0
        var buttons = 0
        var sticks = 0
        var texts = 0
        layout.layers.value.forEach { layer ->
            layers++
            buttons += layer.normalButtons.value.size
            sticks += layer.joystickButtons.value.size
            texts += layer.textBoxes.value.size
        }
        if (buttons + sticks + texts == 0) {
            return ImportResult(error = "Zalith: в раскладке нет элементов")
        }
        return ImportResult(
            layout = layout,
            summary = "Zalith v$version: слоёв $layers, кнопок $buttons, стиков $sticks, текстов $texts",
        )
    }

    /* ------------------------------- Pojav ----------------------------- */

    private fun pojav(
        root: JSONObject,
        controls: JSONArray,
        screenW: Int,
        screenH: Int,
        density: Float,
    ): ImportResult {
        val geometry = Geometry(screenW, screenH, density, scaleOf(root))
        val layout = ObservableControlLayout(EmptyControlLayout)
        val layer = layout.addLayer(createNewLayer("Pojav"))
        val warnings = ArrayList<String>(8)
        val styles = HashMap<String, String>(8)
        val seen = HashSet<String>(64)
        var buttons = 0
        var duplicates = 0
        var skipped = 0

        fun addButton(obj: JSONObject, fallbackName: String): Boolean {
            val name = displayName(obj, fallbackName)
            val codes = keyCodes(obj)
            val events = ArrayList<ClickEvent>(4)
            for (code in codes) {
                val event = eventOf(code)
                if (event == null) {
                    warnings.add("«$name»: " + (unsupported(code) ?: "код $code не найден в таблице клавиш"))
                    continue
                }
                if (event !in events) events.add(event)
            }
            if (events.isEmpty()) {
                skipped++
                return false
            }
            val width = clampDp(obj.optDouble("width", 50.0))
            val height = clampDp(obj.optDouble("height", 50.0))
            val position = try {
                position(obj, geometry, width, height)
            } catch (e: Exception) {
                skipped++
                warnings.add("«$name»: " + (e.message ?: "позиция не разобрана"))
                return false
            }
            val visibility = visibilityOf(obj)
            if (visibility == null) {
                skipped++
                warnings.add("«$name»: скрыта и в игре, и в меню")
                return false
            }
            val signature = name + "|" + codes.joinToString(",") + "|" +
                position.x + "," + position.y + "|" + width + "x" + height
            if (!seen.add(signature)) {
                duplicates++
                return false
            }
            layer.addNormalButton(
                NormalData(
                    text = createTranslatable(name),
                    uuid = Controls.newUuid(),
                    position = position,
                    buttonSize = sizeOf(width, height, geometry),
                    buttonStyle = styleOf(layout, styles, obj),
                    textAlignment = TextAlignment.Center,
                    textBold = false,
                    textItalic = false,
                    textUnderline = false,
                    visibilityType = visibility,
                    _clickEvents = events,
                    isSwipple = obj.optBoolean("isSwipeable", true),
                    isPenetrable = obj.optBoolean("passThruEnabled", false),
                    isToggleable = obj.optBoolean("isToggle", false),
                )
            )
            buttons++
            return true
        }

        for (i in 0 until controls.length()) {
            val obj = controls.optJSONObject(i) ?: continue
            // Стики в Pojav лежат отдельным списком; type встречается только у чужих сборок.
            if (obj.optString("type") == "joystick") {
                skipped++
                warnings.add("кнопка #" + (i + 1) + ": стик из mControlDataList пропущен")
                continue
            }
            addButton(obj, "Кнопка " + (i + 1))
        }

        var sticks = 0
        val joysticks = root.optJSONArray("mJoystickDataList")
        if (joysticks != null) {
            for (i in 0 until joysticks.length()) {
                val obj = joysticks.optJSONObject(i) ?: continue
                if (sticks > 0) {
                    warnings.add("стик #" + (i + 1) + ": поддержан только один стик")
                    continue
                }
                val name = displayName(obj, "Стик")
                val width = clampDp(obj.optDouble("width", 160.0))
                val height = clampDp(obj.optDouble("height", width.toDouble()))
                val position = try {
                    position(obj, geometry, width, height)
                } catch (e: Exception) {
                    warnings.add("«$name»: " + (e.message ?: "позиция не разобрана"))
                    continue
                }
                val visibility = visibilityOf(obj) ?: VisibilityType.ALWAYS
                val size = maxOf(width, height)
                layer.addJoystickButton(
                    JoystickData(
                        uuid = Controls.newUuid(),
                        position = position,
                        sizeType = ButtonSize.Type.Dp,
                        sizeDp = size,
                        sizePercentage = percentageOf(size, geometry),
                        visibilityType = visibility,
                        joystickStyleId = null,
                        deadZoneRatio = 0.12f,
                        lockThreshold = 0.3f,
                        canLock = obj.optBoolean("forwardLock", false),
                        triggerMode = if (obj.optBoolean("absolute", false)) {
                            JoystickTriggerMode.TOUCH
                        } else {
                            JoystickTriggerMode.DRAG
                        },
                        directionEvents = DefaultDirectionEvents,
                        lockEvents = DefaultLockEvents,
                    )
                )
                sticks++
            }
        }

        var drawerButtons = 0
        val drawers = root.optJSONArray("mDrawerDataList")
        if (drawers != null) {
            for (i in 0 until drawers.length()) {
                val drawer = drawers.optJSONObject(i) ?: continue
                val orientation = drawer.optString("orientation", "?")
                val contents = drawer.optJSONArray("buttonProperties")
                if (contents == null || contents.length() == 0) {
                    warnings.add("ящик #" + (i + 1) + ": пустой")
                    continue
                }
                // Кнопка-открывашка ящика сама событий не несёт: она только показывает список.
                // LayerController сворачивать ящик не умеет, поэтому её не переносим.
                if (!orientation.equals("FREE", ignoreCase = true)) {
                    warnings.add(
                        "ящик #" + (i + 1) + " (" + orientation + "): раскладка от ящика не поддерживается, " +
                            "кнопки пропущены"
                    )
                    continue
                }
                var made = 0
                for (j in 0 until contents.length()) {
                    val obj = contents.optJSONObject(j) ?: continue
                    if (addButton(obj, "Ящик " + (i + 1) + "/" + (j + 1))) made++
                }
                drawerButtons += made
                warnings.add(
                    "ящик #" + (i + 1) + " (FREE): сворачивание не поддерживается, " +
                        "кнопок стоит на своих местах: $made из " + contents.length()
                )
            }
        }

        if (buttons + sticks + drawerButtons == 0) {
            return ImportResult(
                error = "Pojav: не перенесено ни одной кнопки" +
                    if (warnings.isEmpty()) "" else " — " + warnings.take(3).joinToString("; ")
            )
        }
        val version = (root.opt("version") as? Number)?.toInt() ?: 0
        return ImportResult(
            layout = layout,
            summary = "Pojav" + (if (version > 0) " v$version" else "") +
                ": кнопок $buttons" +
                (if (drawerButtons > 0) " (из ящиков $drawerButtons)" else "") +
                ", стиков $sticks" +
                (if (duplicates > 0) ", дубликатов убрано $duplicates" else "") +
                (if (skipped > 0) ", пропущено $skipped" else ""),
            warnings = warnings,
        )
    }

    /* -------------------------- поля Pojav ----------------------------- */

    /** 0 — пустой слот в int[4]; -1/-3/-4 проверены по Pojav ControlData и нашему EventHandler. */
    private fun eventOf(code: Int): ClickEvent? = when (code) {
        -1 -> ClickEvent(ClickEvent.Type.LauncherEvent, "switch_ime")
        -3 -> ClickEvent(ClickEvent.Type.LauncherEvent, "GLFW_MOUSE_BUTTON_LEFT")
        -4 -> ClickEvent(ClickEvent.Type.LauncherEvent, "GLFW_MOUSE_BUTTON_RIGHT")
        else -> keyNames[code]?.let { ClickEvent(ClickEvent.Type.Key, it) }
    }

    private fun unsupported(code: Int): String? = when (code) {
        -2 -> "переключение управления (SPECIALBTN_TOGGLECTRL) не поддерживается"
        -5 -> "виртуальная мышь (SPECIALBTN_VIRTUALMOUSE) не поддерживается"
        -6 -> "средняя кнопка мыши (SPECIALBTN_MOUSEMID) не поддерживается"
        -7 -> "колесо вверх (SPECIALBTN_SCROLLUP) не поддерживается"
        -8 -> "колесо вниз (SPECIALBTN_SCROLLDOWN) не поддерживается"
        -9 -> "меню (SPECIALBTN_MENU) не поддерживается"
        else -> null
    }

    /** Номер GLFW-клавиши → имя события. Таблица берётся из самой библиотеки InputMap. */
    private val keyNames: Map<Int, String> by lazy {
        val map = HashMap<Int, String>(320)
        ControlEventKeycode::class.java.fields.forEach { field ->
            if (field.type != String::class.java) return@forEach
            val name = runCatching { field.get(null) as? String }.getOrNull() ?: return@forEach
            // Мышь в Pojav приходит отрицательными спецкодами, положительные — только клавиши.
            if (!name.startsWith("GLFW_KEY_")) return@forEach
            val code = runCatching { ControlEventKeycode.getKeycodeFromEvent(name) }
                .getOrNull()?.toInt() ?: return@forEach
            if (code > 0 && !map.containsKey(code)) map[code] = name
        }
        map
    }

    private fun keyCodes(obj: JSONObject): List<Int> {
        val out = ArrayList<Int>(4)
        // Старый Pojav хранил модификаторы отдельными флагами (LayoutConverter).
        if (obj.optBoolean("holdShift", false)) addCode(out, 340)
        if (obj.optBoolean("holdCtrl", false)) addCode(out, 341)
        if (obj.optBoolean("holdAlt", false)) addCode(out, 342)
        val list = obj.optJSONArray("keycodes")
        if (list != null) {
            for (i in 0 until list.length()) {
                when (val element = list.opt(i)) {
                    is Number -> addCode(out, element.toInt())
                    is JSONArray -> nestedCode(element)?.let { addCode(out, it) }
                }
            }
        }
        if (out.isEmpty()) addCode(out, obj.optInt("keycode", 0))
        return out
    }

    private fun nestedCode(pair: JSONArray): Int? {
        for (i in 0 until pair.length()) {
            val value = pair.opt(i)
            if (value is Number) {
                val code = value.toInt()
                if (code != 0) return code
            }
        }
        return null
    }

    private fun addCode(out: MutableList<Int>, code: Int) {
        if (code != 0 && !out.contains(code)) out.add(code)
    }

    private fun displayName(obj: JSONObject, fallback: String): String {
        val raw = obj.optString("name", "").trim()
        return raw.ifEmpty { fallback }.take(32)
    }

    private fun visibilityOf(obj: JSONObject): VisibilityType? {
        val game = obj.optBoolean("displayInGame", true)
        val menu = obj.optBoolean("displayInMenu", true)
        return when {
            game && menu -> VisibilityType.ALWAYS
            game -> VisibilityType.IN_GAME
            menu -> VisibilityType.IN_MENU
            else -> null
        }
    }

    private fun scaleOf(root: JSONObject): Double {
        val raw = root.optDouble("scaledAt", 100.0)
        return if (raw.isFinite() && raw in 10.0..1000.0) raw else 100.0
    }

    private fun clampDp(value: Double): Float {
        if (!value.isFinite()) return 50f
        return value.toFloat().coerceIn(MIN_BUTTON_DP, MAX_BUTTON_DP)
    }

    private fun sizeOf(widthDp: Float, heightDp: Float, geometry: Geometry): ButtonSize = ButtonSize(
        type = ButtonSize.Type.Dp,
        widthDp = widthDp,
        heightDp = heightDp,
        widthPercentage = percentageOf(widthDp, geometry),
        heightPercentage = percentageOf(heightDp, geometry),
        widthReference = ButtonSize.Reference.ScreenHeight,
        heightReference = ButtonSize.Reference.ScreenHeight,
    )

    private fun percentageOf(dp: Float, geometry: Geometry): Int =
        (dp.toDouble() * geometry.density / geometry.screenH.coerceAtLeast(1) * 10000.0)
            .roundToInt().coerceIn(100, 10000)

    /** Один стиль на одинаковый набор цвета/обводки/прозрачности, чтобы не плодить копии. */
    private fun styleOf(
        layout: ObservableControlLayout,
        cache: MutableMap<String, String>,
        obj: JSONObject,
    ): String {
        val background = obj.optInt("bgColor", 0x4D000000)
        val stroke = obj.optInt("strokeColor", -1)
        val strokeWidth = obj.optDouble("strokeWidth", 0.0).toFloat().coerceIn(0f, 50f).roundToInt()
        val radius = obj.optDouble("cornerRadius", 0.0).toFloat().coerceIn(0f, 100f)
        val alpha = obj.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f)
        val key = "$background|$stroke|$strokeWidth|$radius|$alpha"
        cache[key]?.let { return it }
        val shape = ButtonShape(radius)
        val config = ButtonStyle.StyleConfig(
            alpha = alpha,
            pressedAlpha = alpha,
            backgroundColor = Color(background),
            pressedBackgroundColor = Color(background),
            contentColor = Color.White,
            pressedContentColor = Color.White,
            fontSize = null,
            pressedFontSize = null,
            borderWidth = strokeWidth,
            pressedBorderWidth = strokeWidth,
            borderColor = Color(stroke),
            pressedBorderColor = Color(stroke),
            borderRadius = shape,
            pressedBorderRadius = shape,
        )
        val style = ButtonStyle(
            name = "Pojav " + (cache.size + 1),
            uuid = Controls.newUuid(),
            animateSwap = false,
            commonStyle = true,
            lightStyle = config,
            darkStyle = config,
        )
        layout.addStyle(style)
        cache[key] = style.uuid
        return style.uuid
    }

    /* ------------------------- позиция и выражения --------------------- */

    private class Geometry(
        val screenW: Int,
        val screenH: Int,
        val density: Float,
        val scale: Double,
    ) {
        fun vars(widthDp: Float, heightDp: Float): Map<String, Double> {
            val widthPx = widthDp.toDouble() * density
            val heightPx = heightDp.toDouble() * density
            val margin = 2.0 * density
            return mapOf(
                "screen_width" to screenW.toDouble(),
                "screen_height" to screenH.toDouble(),
                "width" to widthPx,
                "height" to heightPx,
                "right" to screenW - widthPx,
                "bottom" to screenH - heightPx,
                "top" to 0.0,
                "left" to 0.0,
                "margin" to margin,
                "preferred_scale" to scale,
            )
        }
    }

    private fun position(
        obj: JSONObject,
        geometry: Geometry,
        widthDp: Float,
        heightDp: Float,
    ): ButtonPosition {
        val vars = geometry.vars(widthDp, heightDp)
        val x = coordinate(obj, "dynamicX", "x", vars, geometry.density)
        val y = coordinate(obj, "dynamicY", "y", vars, geometry.density)
        val availableX = (geometry.screenW - widthDp.toDouble() * geometry.density).coerceAtLeast(1.0)
        val availableY = (geometry.screenH - heightDp.toDouble() * geometry.density).coerceAtLeast(1.0)
        return ButtonPosition(
            x = percent(x, availableX),
            y = percent(y, availableY),
        )
    }

    /** dynamicX/Y — выражение; x/y — уже пиксели (старый формат Pojav). */
    private fun coordinate(
        obj: JSONObject,
        dynamic: String,
        plain: String,
        vars: Map<String, Double>,
        density: Float,
    ): Double {
        val expression = obj.opt(dynamic) as? String
        if (expression != null && expression.isNotBlank()) {
            return Expression(expression, vars, density.toDouble()).value()
        }
        val value = obj.optDouble(plain, Double.NaN)
        if (value.isNaN()) throw ImportError("нет " + dynamic)
        return value
    }

    /** LayerController считает позицию как долю (экран − размер) — тем же и делим. */
    private fun percent(value: Double, available: Double): Int {
        if (!value.isFinite()) throw ImportError("позиция не число")
        return (value / available * 10000.0).roundToInt().coerceIn(0, 10000)
    }

    /** Ограниченный парсер арифметики Pojav: без eval, без доступа к чему-либо ещё. */
    private class Expression(
        private val text: String,
        private val vars: Map<String, Double>,
        private val density: Double,
    ) {
        private var at = 0
        private var depth = 0

        init {
            if (text.length > MAX_EXPRESSION_CHARS) throw ImportError("слишком длинное выражение")
        }

        fun value(): Double {
            val result = sum()
            skipSpace()
            if (at < text.length) throw ImportError("лишний символ «" + text[at] + "» в выражении")
            if (!result.isFinite()) throw ImportError("выражение не даёт число")
            return result
        }

        private fun sum(): Double {
            var value = product()
            while (true) {
                skipSpace()
                if (at >= text.length) return value
                when (text[at]) {
                    '+' -> { at++; value += product() }
                    '-' -> { at++; value -= product() }
                    else -> return value
                }
            }
        }

        private fun product(): Double {
            var value = unary()
            while (true) {
                skipSpace()
                if (at >= text.length) return value
                when (text[at]) {
                    '*' -> { at++; value *= unary() }
                    '/' -> {
                        at++
                        val right = unary()
                        if (right == 0.0) throw ImportError("деление на ноль")
                        value /= right
                    }
                    else -> return value
                }
            }
        }

        private fun unary(): Double {
            skipSpace()
            if (at < text.length) {
                when (text[at]) {
                    '+' -> { at++; return unary() }
                    '-' -> { at++; return -unary() }
                }
            }
            return primary()
        }

        private fun primary(): Double {
            skipSpace()
            if (at >= text.length) throw ImportError("оборванное выражение")
            val c = text[at]
            if (c == '(') {
                at++
                enter()
                val value = sum()
                skipSpace()
                if (at >= text.length || text[at] != ')') throw ImportError("нет закрывающей скобки")
                at++
                depth--
                return value
            }
            if (c == '$' && at + 1 < text.length && text[at + 1] == '{') {
                val end = text.indexOf('}', at + 2)
                if (end < 0) throw ImportError("нет закрывающей }")
                val name = text.substring(at + 2, end)
                val known = vars[name] ?: throw ImportError("неизвестная переменная \${$name}")
                at = end + 1
                return known
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter() || c == '_') return function()
            throw ImportError("символ «$c» в выражении")
        }

        private fun function(): Double {
            val start = at
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] == '_')) at++
            val name = text.substring(start, at)
            skipSpace()
            if (at >= text.length || text[at] != '(') throw ImportError("неизвестное имя «$name»")
            at++
            enter()
            val argument = sum()
            skipSpace()
            if (at >= text.length || text[at] != ')') throw ImportError("нет «)» у $name")
            at++
            depth--
            return when (name) {
                "px" -> argument * density
                "dp" -> if (density == 0.0) 0.0 else argument / density
                else -> throw ImportError("неизвестная функция «$name»")
            }
        }

        private fun number(): Double {
            val start = at
            var dots = 0
            while (at < text.length) {
                val c = text[at]
                if (c.isDigit()) {
                    at++
                    continue
                }
                if (c == '.') {
                    dots++
                    if (dots > 1) throw ImportError("в числе две точки")
                    at++
                    continue
                }
                break
            }
            if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
                at++
                if (at < text.length && (text[at] == '+' || text[at] == '-')) at++
                val digits = at
                while (at < text.length && text[at].isDigit()) at++
                if (at == digits) throw ImportError("плохой показатель степени")
            }
            val raw = text.substring(start, at)
            return raw.toDoubleOrNull() ?: throw ImportError("не число «$raw»")
        }

        private fun enter() {
            depth++
            if (depth > MAX_EXPRESSION_DEPTH) throw ImportError("слишком глубокая вложенность")
        }

        private fun skipSpace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }
    }
}
