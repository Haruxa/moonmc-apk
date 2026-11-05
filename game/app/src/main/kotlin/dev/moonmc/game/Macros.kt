package dev.moonmc.game

import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * Макросы MoonMC: именованные последовательности шагов для чата и клавиш.
 *
 * Шаг — это `text` (отправить текст/команду в чат), `key` (клавиша GLFW, как в
 * `Native.key`) или `delay` (пауза). Циклов нет: макрос конечен, число шагов и
 * длина текста ограничены — случайный бесконечный автокликер из файла не выйдет.
 *
 * Хранилище — `/sdcard/MoonMC/macros.json` рядом с профилями и аккаунтами.
 * Запись атомарная: сначала `macros.json.tmp`, затем rename. Битая запись или
 * недоступный файл не роняют панель: чтение отдаёт пустой список и сообщение.
 */
internal data class MacroStep(
    val kind: Kind,
    val text: String = "",
    val code: Int = 0,
    val millis: Long = 0L,
) {
    enum class Kind(val tag: String) { Text("text"), Key("key"), Delay("delay") }
}

internal data class Macro(val name: String, val steps: List<MacroStep> = emptyList())

/** Итог чтения: список и понятное человеку сообщение (null — всё хорошо). */
internal data class MacroLoad(val macros: List<Macro> = emptyList(), val error: String? = null)

internal object Macros {
    /** Предел файла и одного макроса: защита от «случайного» мега-скрипта. */
    const val MAX_MACROS = 64
    const val MAX_STEPS = 32
    const val MAX_TEXT = 200
    const val MAX_DELAY = 60_000L
    const val NAME_MAX = 32

    /** Готовые команды: шаблон добавляет один шаг `text`. */
    val TEMPLATES = listOf("/spawn", "/home", "/tpa")

    private val dir = File("/sdcard/MoonMC")
    private val file = File(dir, "macros.json")
    private val tmp = File(dir, "macros.json.tmp")

    fun path(): String = file.path

    fun load(): MacroLoad = try {
        when {
            !file.exists() -> MacroLoad()
            else -> {
                val text = file.readText()
                if (text.isBlank()) {
                    MacroLoad(error = "macros.json пуст — начинаем с пустого списка")
                } else {
                    MacroLoad(parse(text))
                }
            }
        }
    } catch (t: Throwable) {
        MacroLoad(error = "макросы не прочитаны: ${reason(t)}")
    }

    /** Сохраняет список целиком; возвращает текст ошибки или null. */
    fun save(macros: List<Macro>): String? = try {
        dir.mkdirs()
        val array = org.json.JSONArray()
        macros.take(MAX_MACROS).forEach { macro ->
            val steps = org.json.JSONArray()
            macro.steps.take(MAX_STEPS).forEach { step -> steps.put(json(step)) }
            array.put(
                org.json.JSONObject()
                    .put("name", macro.name.take(NAME_MAX))
                    .put("steps", steps),
            )
        }
        val body = org.json.JSONObject()
            .put("version", 1)
            .put("macros", array)
            .toString(2)
        tmp.writeText(body)
        if (tmp.renameTo(file)) null
        // rename(2) на одном томе заменяет цель; сюда попадаем, только если файл
        // успели подменить/держат. Тогда цель убираем и пробуем ещё раз — без
        // этого сохранение молча терялось бы вместе с .tmp.
        else if (file.delete() && tmp.renameTo(file)) null
        else "макросы не сохранены: ${file.path} занят"
    } catch (t: Throwable) {
        "макросы не сохранены: ${reason(t)}"
    }

    private fun json(step: MacroStep): org.json.JSONObject = org.json.JSONObject().apply {
        put("type", step.kind.tag)
        when (step.kind) {
            MacroStep.Kind.Text -> put("text", step.text.take(MAX_TEXT))
            MacroStep.Kind.Key -> put("code", step.code)
            MacroStep.Kind.Delay -> put("ms", step.millis.coerceIn(0L, MAX_DELAY))
        }
    }

    private fun parse(text: String): List<Macro> {
        val root = org.json.JSONObject(text)
        val array = root.optJSONArray("macros") ?: org.json.JSONArray()
        val out = ArrayList<Macro>()
        for (i in 0 until array.length()) {
            if (out.size >= MAX_MACROS) break
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim().take(NAME_MAX)
            if (name.isEmpty()) continue
            val steps = ArrayList<MacroStep>()
            val saved = obj.optJSONArray("steps") ?: org.json.JSONArray()
            for (j in 0 until saved.length()) {
                if (steps.size >= MAX_STEPS) break
                val step = saved.optJSONObject(j) ?: continue
                stepOf(step)?.let { steps.add(it) }
            }
            out.add(Macro(name, steps))
        }
        return out
    }

    /** Неизвестный или испорченный шаг пропускается, а не валит весь файл. */
    private fun stepOf(obj: org.json.JSONObject): MacroStep? =
        when (obj.optString("type").lowercase()) {
            MacroStep.Kind.Text.tag ->
                obj.optString("text").take(MAX_TEXT).ifBlank { null }
                    ?.let { MacroStep(MacroStep.Kind.Text, text = it) }
            MacroStep.Kind.Key.tag ->
                obj.optInt("code", -1).takeIf { it in 0..400 }
                    ?.let { MacroStep(MacroStep.Kind.Key, code = it) }
            MacroStep.Kind.Delay.tag ->
                MacroStep(MacroStep.Kind.Delay, millis = obj.optLong("ms").coerceIn(0L, MAX_DELAY))
            else -> null
        }

    private fun reason(t: Throwable): String = t.message?.take(120) ?: t.javaClass.simpleName
}

/**
 * Выполнение макроса: один за раз, в фоновом потоке, с отменой.
 *
 * Клавиши идут через `Native.key`, текст — через `Native.text` (тот же путь, что
 * у системной клавиатуры: `Typed` → `moon_ime`). Поток макроса останавливается
 * кнопкой «Стоп», а нажатые им клавиши отпускаются — игрок не «залипает» в беге.
 */
internal object MacroRunner {
    /** «Открыть чат» в мире — клавиша T (GLFW 84). */
    private const val CHAT_KEY = 84

    /** Предел одного запуска: даже 32 шага с большими паузами не висят вечно. */
    private const val MAX_RUN_MS = 120_000L

    private val state = mutableStateOf<String?>(null)

    /** Название выполняемого макроса; null — сейчас ничего не идёт. */
    val running: String? get() = state.value

    @Volatile private var cancelled = false
    @Volatile private var worker: Thread? = null
    private val held = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

    /** Запуск; возвращает сообщение, если уже идёт другой макрос. */
    fun start(macro: Macro): String? {
        if (worker?.isAlive == true) return "уже выполняется: ${running ?: "макрос"}"
        if (macro.steps.isEmpty()) return "в макросе нет шагов"
        cancelled = false
        state.value = macro.name
        val thread = Thread({ execute(macro) }, "moonmc-macro")
        thread.isDaemon = true
        worker = thread
        thread.start()
        return null
    }

    fun stop() {
        cancelled = true
        worker?.interrupt()
        releaseHeld()
    }

    private fun execute(macro: Macro) {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        try {
            for (step in macro.steps) {
                if (cancelled || System.currentTimeMillis() > deadline) break
                when (step.kind) {
                    MacroStep.Kind.Delay -> pause(step.millis)
                    MacroStep.Kind.Key -> tap(step.code)
                    // Текст только в чат: T открывает чат, текст идёт как ввод игрока,
                    // Enter отправляет. Так команда «/spawn» работает без своих кодов.
                    MacroStep.Kind.Text -> send(step.text)
                }
            }
        } catch (_: InterruptedException) {
            // Отмена кнопкой «Стоп»: пауза проснулась, ключи отпустит finally.
        } catch (t: Throwable) {
            android.util.Log.w("MoonMC", "макрос «${macro.name}»: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            releaseHeld()
            worker = null
            state.value = null
        }
    }

    private fun pause(ms: Long) {
        if (ms <= 0) return
        val until = System.currentTimeMillis() + ms.coerceAtMost(Macros.MAX_DELAY)
        while (!cancelled) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return
            Thread.sleep(minOf(left, 50L))
        }
    }

    private fun tap(code: Int) {
        if (code !in 0..400) return
        press(code, true)
        pause(70)
        press(code, false)
    }

    private fun press(code: Int, down: Boolean) {
        Native.key(code, down)
        if (down) held.add(code) else held.remove(code)
    }

    private fun send(text: String) {
        val body = text.trim().take(Macros.MAX_TEXT)
        if (body.isEmpty()) return
        tap(CHAT_KEY)
        // Чат открывается в кадре движка: без паузы текст попал бы в мир.
        pause(320)
        Native.text(body)
        pause(90)
        Native.enter()
        pause(120)
    }

    private fun releaseHeld() {
        val keys = synchronized(held) { held.toList() }
        keys.forEach { Native.key(it, false) }
        held.clear()
    }
}
