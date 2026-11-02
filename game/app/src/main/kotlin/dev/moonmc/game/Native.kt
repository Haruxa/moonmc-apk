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

    private external fun keyDown(code: Int, pressed: Boolean)
    private external fun mouseDown(button: Int, pressed: Boolean)
    private external fun stickMove(x: Float, y: Float)
    private external fun hotbarSlot(slot: Int)
    private external fun touchEvent(x: Float, y: Float, action: Int, id: Int)
    private external fun keyboardDown(on: Boolean)
}
