package dev.moonmc.game

/**
 * Мост в движок: те же JNI-точки объявлены в Rust (`ci/moon_jni.rs`).
 * Коды клавиш — GLFW, как в InputMap у Zalith.
 */
object Native {
    @Volatile
    private var loaded = false

    private fun ensure() {
        if (!loaded) {
            runCatching { System.loadLibrary("horizon") }
            loaded = true
        }
    }

    fun key(code: Int, pressed: Boolean) {
        ensure()
        runCatching { keyDown(code, pressed) }
    }

    fun mouse(button: Int, pressed: Boolean) {
        ensure()
        runCatching { mouseDown(button, pressed) }
    }

    fun stick(x: Float, y: Float) {
        ensure()
        runCatching { stickMove(x, y) }
    }

    fun hotbar(slot: Int) {
        ensure()
        runCatching { hotbarSlot(slot) }
    }

    /** Касание экрана: action — MotionEvent.actionMasked, id — номер пальца. */
    fun touch(x: Float, y: Float, action: Int, id: Int) {
        ensure()
        runCatching { touchEvent(x, y, action, id) }
    }

    /** Системная клавиатура Android: кнопка IME слоя. */
    fun keyboard(on: Boolean) {
        ensure()
        runCatching { keyboardDown(on) }
    }

    /** Открыта ли клавиатура сейчас: её открывает и сам движок, в полях ввода. */
    fun keyboardShown(): Boolean {
        ensure()
        return runCatching { keyboardIsShown() }.getOrDefault(false)
    }

    /** Имя текущего аккаунта ("" — не вошли). */
    fun accountName(): String {
        ensure()
        return runCatching { accountNameNative() }.getOrDefault("")
    }

    /** Сменить аккаунт: имя становится игроком сразу, без перезапуска. */
    fun setAccount(name: String) {
        ensure()
        runCatching { setAccountNative(name) }
    }

    /** Открыт ли экран/меню игры: тогда игровые кнопки слоя скрыты. */
    fun inMenu(): Boolean {
        ensure()
        return runCatching { inMenuNative() }.getOrDefault(false)
    }

    /** Движок догрузил ресурсы: до этого показываем экран загрузки. */
    fun ready(): Boolean {
        ensure()
        return runCatching { readyNative() }.getOrDefault(false)
    }

    /** Enter в чате: отправить набранное, как кнопка «ввод» на клавиатуре. */
    fun enter() {
        ensure()
        runCatching { enterNative() }
    }

    /** Текст макроса: та же очередь, что у системной клавиатуры (`moon_ime`). */
    fun text(value: String) {
        if (value.isEmpty()) return
        ensure()
        runCatching { textNative(value) }
    }

    private external fun keyDown(code: Int, pressed: Boolean)
    private external fun mouseDown(button: Int, pressed: Boolean)
    private external fun stickMove(x: Float, y: Float)
    private external fun hotbarSlot(slot: Int)
    private external fun touchEvent(x: Float, y: Float, action: Int, id: Int)
    private external fun keyboardDown(on: Boolean)
    private external fun keyboardIsShown(): Boolean
    private external fun accountNameNative(): String
    private external fun setAccountNative(name: String)
    private external fun inMenuNative(): Boolean
    private external fun readyNative(): Boolean
    private external fun enterNative()
    private external fun textNative(value: String)
}
