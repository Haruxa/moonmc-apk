package cat.narezany.horizon;

import java.lang.reflect.Method;

/**
 * MoonMC: мост «игра просит клавиатуру» → окно слоя управления.
 *
 * Клавиатуру открывает движок (`event_loop::set_keyboard`), но окно оверлея висит поверх
 * IME и съедает нажатия. Здесь зовём Kotlin-часть по имени класса: в сборке aapt2 Java
 * компилируется без Kotlin, поэтому жёсткий импорт не годится.
 */
final class OverlayBridge {
    private static final String OVERLAY = "dev.moonmc.game.Overlay";

    private OverlayBridge() {
    }

    /** true — клавиатура показана, окно слоя прячем; false — возвращаем. */
    static void gameKeyboard(boolean show) {
        try {
            Method method = Class.forName(OVERLAY).getMethod("gameKeyboard", boolean.class);
            method.invoke(null, show);
        } catch (Throwable missing) {
            // сборка без Compose-слоя: клавиатурой занимается движок
        }
    }
}
