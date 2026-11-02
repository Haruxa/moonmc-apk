package cat.narezany.horizon;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import java.lang.reflect.Method;

/**
 * MoonMC: экран первой подготовки и загрузки.
 *
 * Содержимое рисует Compose в стиле Material 3 Expressive (`dev.moonmc.game.SplashUi` из
 * Gradle-сборки). Ссылки на Kotlin — через рефлексию: в сборке aapt2 Java компилируется без
 * Kotlin-классов, и жёсткий импорт ломал javac. Без Compose остаётся простой текстовый экран.
 */
public class MoonSplash extends Dialog {
    private static final String UI = "dev.moonmc.game.SplashUi";

    private final TextView fallback;

    MoonSplash(Context context) {
        super(context, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        setCancelable(false);
        View host = composeHost(context);
        if (host != null) {
            fallback = null;
            setContentView(host, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            fallback = new TextView(context);
            fallback.setGravity(Gravity.CENTER);
            fallback.setTextColor(Color.WHITE);
            fallback.setTextSize(16f);
            fallback.setBackgroundColor(0xFF070B09);
            fallback.setText("MoonMC\n\nPreparing the game…");
            setContentView(fallback, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0f);
        }
    }

    /** Шаг подготовки пришёл из движка: «Unpacking Java…», проценты и т.д. */
    void step(String text) {
        if (call("step", new Class<?>[] { String.class }, new Object[] { text == null ? "" : text })) {
            return;
        }
        if (fallback != null) {
            fallback.setText("MoonMC\n\n" + (text == null ? "" : text));
        }
    }

    /** Закрыть без исключения, если окно уже ушло. */
    void finishQuietly() {
        try {
            dismiss();
        } catch (RuntimeException ignored) {
            // окно уже закрыто жизненным циклом активности
        }
    }

    /** View экрана из Kotlin-модуля, если он есть в этой сборке. */
    private static View composeHost(Context context) {
        try {
            Class<?> ui = Class.forName(UI);
            Method create = ui.getMethod("createHost", Context.class);
            Object host = create.invoke(null, context);
            return host instanceof View ? (View) host : null;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static boolean call(String name, Class<?>[] types, Object[] args) {
        try {
            Method method = Class.forName(UI).getMethod(name, types);
            method.invoke(null, args);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }
}
