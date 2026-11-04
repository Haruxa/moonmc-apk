package cat.narezany.horizon;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * MoonMC: экран игры без системной полосы навигации (immersive).
 *
 * Прячем только навигацию: IME в списке типов нет, поэтому клавиатуру по-прежнему
 * открывает и закрывает {@code setKeyboard(show)} — сама она не скрывается.
 * Диалоги и клавиатура возвращают полосу, поэтому {@code apply} зовут и повторно
 * при возврате фокуса окну.
 */
final class MoonImmersive {
    private MoonImmersive() {
    }

    /** Спрятать панель навигации; обычные касания её не поднимают, только свайп. */
    static void apply(Window window) {
        if (window == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.navigationBars());
                return;
            }
        }
        // API 26-29: те же правила флагами; IMMERSIVE_STICKY не даёт полосе
        // всплыть от обычного касания — только от свайпа.
        window.getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
