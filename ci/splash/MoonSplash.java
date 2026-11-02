package cat.narezany.horizon;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.ViewGroup;
import android.view.Window;

import dev.moonmc.game.SplashUi;

/**
 * MoonMC: экран первой подготовки и загрузки.
 *
 * Содержимое — Compose в стиле Material 3 Expressive (см. {@link SplashUi}): здесь только
 * диалог во весь экран и передача шагов подготовки из движка.
 */
public class MoonSplash extends Dialog {
    MoonSplash(Context context) {
        super(context, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        setCancelable(false);
        setContentView(SplashUi.createHost(context), new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManagerParams.MATCH_PARENT, WindowManagerParams.MATCH_PARENT);
            window.setDimAmount(0f);
        }
    }

    /** Шаг подготовки пришёл из движка: «Unpacking Java…», проценты и т.д. */
    void step(String text) {
        SplashUi.step(text);
    }

    /** Закрыть без анимации и без исключения, если окно уже ушло. */
    void finishQuietly() {
        try {
            dismiss();
        } catch (RuntimeException ignored) {
            // окно уже закрыто жизненным циклом активности
        }
    }

    /** Константы WindowManager.LayoutParams без лишнего импорта в этом файле. */
    private static final class WindowManagerParams {
        static final int MATCH_PARENT = android.view.WindowManager.LayoutParams.MATCH_PARENT;
    }
}
