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
 * MoonMC: экран подготовки и загрузки.
 *
 * Содержимое рисует Compose в стиле Material 3 Expressive (`dev.moonmc.game.SplashUi` из
 * Gradle-сборки): знак лаунчера собирается как тетрис, по нему идёт радуга, затем он
 * принимает цвет системной схемы. Ссылки на Kotlin — через рефлексию: в сборке aapt2 Java
 * компилируется без Kotlin-классов, и жёсткий импорт ломал javac. Без Compose остаётся
 * простой текстовый экран.
 */
public class MoonSplash extends Dialog {
    private static final String UI = "dev.moonmc.game.SplashUi";

    /** Обычный запуск: анимация доиграла — заставка уходит сама. */
    private static final long STARTUP_MS = 3300;

    private final TextView fallback;
    private final boolean startup;
    private final android.os.Handler handler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoFinish = this::finishQuietly;

    MoonSplash(Context context) {
        this(context, false);
    }

    MoonSplash(Context context, boolean startup) {
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
        // Заставка запуска — только картинка: касания и фокус остаются игре под ней.
        this.startup = startup && fallback == null;
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT);
            window.setDimAmount(0f);
            if (this.startup) {
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            }
        }
    }

    /**
     * Обычный запуск: знак собирается, пока грузится игра. Без Compose (сборка aapt2)
     * анимации нет — тогда экран не показываем вовсе, чтобы не держать пустую заставку.
     */
    void showStartup() {
        if (!startup) {
            return;
        }
        try {
            show();
        } catch (RuntimeException ignored) {
            return; // окно ещё не готово — игра покажет себя сама
        }
        handler.postDelayed(autoFinish, STARTUP_MS);
    }

    /** Шаг подготовки пришёл из движка: «Unpacking Java…», проценты и т.д. */
    void step(String text) {
        // Подготовка может идти минутами: автоскрытие запуска больше не наше.
        handler.removeCallbacks(autoFinish);
        if (call("step", new Class<?>[] { String.class }, new Object[] { text == null ? "" : text })) {
            return;
        }
        if (fallback != null) {
            fallback.setText("MoonMC\n\n" + (text == null ? "" : text));
        }
    }

    /** Закрыть без исключения, если окно уже ушло. */
    void finishQuietly() {
        handler.removeCallbacks(autoFinish);
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
