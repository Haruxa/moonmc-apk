#!/usr/bin/env python3
"""MoonMC-CI: имя приложения, иконка и monochrome-слой для Material You.

Меняет только брендинг: android:label, android:icon/roundIcon, res/mipmap-*,
res/drawable-*/ic_launcher_foreground.png (+ monochrome) и цвет фона
adaptive-иконки. package (cat.narezany.horizon) и lib_name не трогает —
иначе сломается загрузка нативной библиотеки.

Иконки лежат готовыми в ci/branding/ (генератор: moon-launcher/tools/gen_icons.py
--layout flat). Экран первой загрузки — ci/splash/MoonSplash.java (рисуется кодом).
"""
import os
import pathlib
import re
import shutil
import sys

ROOT = os.getcwd()
ANDROID = os.path.join(ROOT, "android")
BRAND = os.path.join(ROOT, "ci", "branding")
SPLASH = os.path.join(ROOT, "ci", "splash")
MANIFEST = os.path.join(ANDROID, "AndroidManifest.xml")
ACTIVITY = os.path.join(ANDROID, "java", "cat", "narezany", "horizon", "HorizonActivity.java")
LABEL = "MoonMC"

# Экран первой загрузки: старый AlertDialog «MargyMC» заменяется на MoonSplash.
OLD_STATUS = '''    private android.app.AlertDialog status;

    /** Called by the first start's preparing: its step, in a dialog over the black window. */
    public void showStatus(final String text) {
        runOnUiThread(() -> {
            if (status == null) {
                status = new android.app.AlertDialog.Builder(this)
                    .setTitle("MargyMC")
                    .setCancelable(false)
                    .setMessage(text)
                    .create();
                status.show();
            } else {
                status.setMessage(text);
            }
        });
    }
'''

NEW_STATUS = '''    private MoonSplash splash;
    /** Идёт подготовка ресурсов: свёрнутое приложение должно вернуть экран загрузки. */
    private boolean preparing;

    /** Called by the first start's preparing: its step on the MoonMC screen. */
    public void showStatus(final String text) {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) {
                return;
            }
            preparing = true;
            if (splash == null) {
                splash = new MoonSplash(this);
                splash.show();
            }
            splash.step(text);
        });
    }

    /** Свёрнутое приложение теряет поверхность: пока идёт подготовка, возвращаем экран. */
    @Override
    protected void onResume() {
        super.onResume();
        if (preparing && splash == null && !isFinishing()) {
            splash = new MoonSplash(this);
            splash.show();
            splash.step("Preparing the game…");
        }
    }
'''

OLD_DISMISS = '''            if (status != null) {
                status.dismiss();
                status = null;
            }
'''

NEW_DISMISS = '''            if (splash != null) {
                splash.finishQuietly();
                splash = null;
            }
'''

OLD_KEYBOARD = '''            if (show) {
                input.reset();'''

NEW_KEYBOARD = '''            if (splash != null) {
                // игра просит клавиатуру — значит уже загрузилась, экран больше не нужен
                splash.finishQuietly();
                splash = null;
                preparing = false;
            }
            // MoonMC: окно слоя не трогаем — по видимости IME его прячет Kotlin-часть.
            OverlayBridge.gameKeyboard(show);
            if (show) {
                // input.reset() отменяет отложенное закрытие IME: поле открыли заново.
                input.reset();'''

OLD_HIDE_BRANCH = '''            } else {
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                input.clearFocus();
                input.setFocusable(false);
            }'''

NEW_HIDE_BRANCH = '''            } else {
                // MoonMC: игра сама закрыла чат — отложенное закрытие уже не нужно.
                input.cancelHide();
                imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
                input.clearFocus();
                input.setFocusable(false);
            }'''

# Отложенное закрытие IME живёт в самом поле: так его можно отменить, если поле
# открыли заново (reset) или игра сама закрыла чат.
OLD_RESET = '''        void reset() {
            if (connection != null) {
                connection.reset();
            }
        }'''

NEW_RESET = '''        /** MoonMC: задержка перед закрытием IME — Enter должен дойти до игры. */
        private static final long HIDE_DELAY = 300;
        private final android.os.Handler hideHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
        /** MoonMC: поколение закрытия: reset/повторное открытие гасит старый вызов. */
        private int hideToken;
        /** MoonMC: закрытие IME отменяемое: поле могут открыть заново за эти 300 мс. */
        private final Runnable hideRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    hideNow();
                } catch (Throwable error) {
                    android.util.Log.w("MoonIME", "hide runnable: " + error);
                }
            }
        };

        void reset() {
            cancelHide();
            if (connection != null) {
                connection.reset();
            }
        }

        /** MoonMC: отложить закрытие IME; повторный вызов переносит срок. */
        void hideLater() {
            cancelHide();
            final int token = ++hideToken;
            hideHandler.postDelayed(() -> {
                // Поле открыли заново или закрыли сами — этот срок уже не наш.
                if (token == hideToken) {
                    hideRunnable.run();
                }
            }, HIDE_DELAY);
        }

        /** MoonMC: отменить отложенное закрытие (поле открыли заново). */
        void cancelHide() {
            hideToken++;
            hideHandler.removeCallbacksAndMessages(null);
        }

        /** MoonMC: закрыть IME, если поле всё ещё на экране. Фокус у другого ввода
         *  (диалог имени, поиск) или у игры — IME не трогаем, но слой возвращаем
         *  всегда: иначе HUD оставался скрытым после отправки сообщения. */
        private void hideNow() {
            if (isAttachedToWindow() && hasFocus()) {
                try {
                    InputMethodManager imm = (InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.hideSoftInputFromWindow(getWindowToken(), 0);
                } catch (Throwable error) {
                    android.util.Log.w("MoonIME", "hide: " + error);
                }
            }
            try {
                OverlayBridge.gameKeyboard(false);
            } catch (Throwable error) {
                android.util.Log.w("MoonIME", "hide overlay: " + error);
            }
        }'''

# Кнопка в самой клавиатуре — «отправить», а не безликая «готово».
OLD_OPTIONS = '''            info.imeOptions = EditorInfo.IME_ACTION_DONE;'''

NEW_OPTIONS = '''            info.imeOptions = EditorInfo.IME_ACTION_SEND;'''

# MoonMC: Enter/«отправить» — сначала набранное в игру, потом сам Enter.
# Клавиатуру гасим отложенно (hideLater): если скрыть сразу, Enter не успевает
# дойти до игры — она видела только закрытие IME и сообщение терялось.
OLD_ENTER_KEY = '''                if (code == KeyEvent.KEYCODE_ENTER) {
                    nativeKey(KeyEvent.KEYCODE_ENTER);
                    reset();
                    return true;
                }'''

NEW_ENTER_KEY = '''                if (code == KeyEvent.KEYCODE_ENTER) {
                    sync();
                    nativeKey(KeyEvent.KEYCODE_ENTER);
                    reset();
                    hideLater();
                    return true;
                }'''

OLD_EDITOR_ACTION = '''        public boolean performEditorAction(int action) {
            nativeKey(KeyEvent.KEYCODE_ENTER);
            reset();
            return true;
        }'''

NEW_EDITOR_ACTION = '''        public boolean performEditorAction(int action) {
            sync();
            nativeKey(KeyEvent.KEYCODE_ENTER);
            reset();
            hideLater();
            return true;
        }'''

# Backspace: Gboard, Samsung и другие клавиатуры на API 24+ зовут
# deleteSurroundingTextInCodePoints, а не deleteSurroundingText. База правит только свой
# буфер (getEditable()) и возвращает true — без sync() игра об удалении не узнавала.
# Проверено по AOSP BaseInputConnection: оба метода работают с getEditable() и не зовут
# никаких колбэков сами.
OLD_DELETE_CODEPOINTS = '''        @Override
        public boolean sendKeyEvent(KeyEvent event) {'''

NEW_DELETE_CODEPOINTS = '''        @Override
        public boolean deleteSurroundingTextInCodePoints(int before, int after) {
            if (before > 0 && getEditable().length() == 0) {
                for (int i = 0; i < before; i++) {
                    nativeKey(KeyEvent.KEYCODE_DEL);
                }
                return true;
            }
            boolean done = super.deleteSurroundingTextInCodePoints(before, after);
            sync();
            return done;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {'''

# Вставляем перед extracted(): у него нет javadoc, а у report() он идёт вплотную к
# сигнатуре — иначе описание «The keyboard's view of the editor» осталось бы висеть
# над hideLater, а сам report() оказался бы без своего комментария.
OLD_REPORT = '''        private ExtractedText extracted() {'''

NEW_REPORT = '''        /** MoonMC: закрыть клавиатуру чуть позже — Enter должен дойти до игры.
         *  Отсчёт живёт в поле ввода (TextView), поэтому повторное открытие чата
         *  отменяет его, а фокус у ввода не отбираем: игра сама зовёт
         *  setKeyboard(false), когда чат закрыт. */
        private void hideLater() {
            if (view instanceof TextView) {
                ((TextView) view).hideLater();
            }
        }

        private ExtractedText extracted() {'''

# Визуальные дубли: полный снимок текста со словом, которое клавиатура рисует сама
# (extract/fullscreen-редактор в горизонтали), заставлял её рисовать это слово дважды.
# Текст набрала сама клавиатура → ей хватает выделения; полный снимок уходит только
# как ответ на getExtractedText. Проверено на v37: отправка снимка после commitText
# (когда составного слова уже нет) всё равно копила «как делакак дел…».
OLD_REPORT_BODY = '''        /** The keyboard's view of the editor: the selection and the word being composed as
         *  they are, so its own editor shows the text once (not "gag" for "ga"). */
        private void report() {
            InputMethodManager imm = (InputMethodManager) view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
            android.text.Editable editable = getEditable();
            int start = android.text.Selection.getSelectionStart(editable);
            int end = android.text.Selection.getSelectionEnd(editable);
            imm.updateSelection(view, start, end, getComposingSpanStart(editable),
                getComposingSpanEnd(editable));
            if (monitor >= 0) {
                imm.updateExtractedText(view, monitor, extracted());
            }
        }'''

NEW_REPORT_BODY = '''        /** MoonMC: что уже отдано клавиатуре: одинаковое состояние не шлём дважды. */
        private int lastStart = -1;
        private int lastEnd = -1;
        private int lastComposingStart = -1;
        private int lastComposingEnd = -1;

        /** The keyboard's view of the editor: the selection and the word being composed as
         *  they are, so its own editor shows the text once (not "gag" for "ga"). */
        private void report() {
            InputMethodManager imm = (InputMethodManager) view.getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
            android.text.Editable editable = getEditable();
            int start = android.text.Selection.getSelectionStart(editable);
            int end = android.text.Selection.getSelectionEnd(editable);
            int composingStart = getComposingSpanStart(editable);
            int composingEnd = getComposingSpanEnd(editable);
            // Полный снимок назад клавиатуре не отдаём: текст набрала она сама, и её
            // full-screen-бар приписывал присланное к своему — «как делакак дел…».
            // Ей довольно выделения (и границ слова в наборе); полный текст уходит
            // только в ответ на getExtractedText — когда она сама его просит.
            if (start != lastStart || end != lastEnd
                || composingStart != lastComposingStart || composingEnd != lastComposingEnd) {
                lastStart = start;
                lastEnd = end;
                lastComposingStart = composingStart;
                lastComposingEnd = composingEnd;
                imm.updateSelection(view, start, end, composingStart, composingEnd);
            }
        }'''

# Ввод не трогаем: в нарезке источник истины — Editable самого BaseInputConnection.
# Проверено по AOSP (frameworks/base BaseInputConnection.java): getEditable() для
# обычного View никогда не null — это внутренний «фальшивый» Editable, а
# sendCurrentText() при fullEditor=true (порт зовёт super(view, true)) сразу выходит,
# поэтому буфер не очищается и sync() видит набранное. Свой буфер, отказ от
# getEditable() и переиспользование одного Connection ломали именно эту схему.
# Патчим только то, чего в нарезке нет: backspace через
# deleteSurroundingTextInCodePoints, sync() перед Enter, действие SEND, отложенное
# отменяемое закрытие IME.

OVERLAY = '''        // MoonMC: любая необработанная ошибка пишется рядом с игрой — иначе краш
        // на старте нечем диагностировать (лог Rust в этот момент ещё пуст).
        final Thread.UncaughtExceptionHandler previousHandler =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.io.StringWriter text = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(text));
                String body = thread.getName() + "\\n" + text;
                // Папка "Загрузки" через MediaStore: доступна без разрешений и видна
                // другим приложениям (внешняя папка приложения для них закрыта).
                try {
                    android.content.ContentResolver resolver = getContentResolver();
                    android.net.Uri downloads =
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                    resolver.delete(downloads,
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[] {"moonmc-crash.txt"});
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        "moonmc-crash.txt");
                    values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download");
                    android.net.Uri uri = resolver.insert(downloads, values);
                    if (uri != null) {
                        java.io.OutputStream out = resolver.openOutputStream(uri);
                        if (out != null) {
                            out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            out.close();
                        }
                    }
                } catch (Throwable ignoredMedia) {
                    // нет доступа к MediaStore — пробуем остальное
                }
                // Папка игры — когда разрешение на файлы уже выдано.
                boolean written = false;
                java.io.File game = new java.io.File("/sdcard/MoonMC/horizon");
                if (game.mkdirs() || game.isDirectory()) {
                    try (java.io.FileWriter out =
                             new java.io.FileWriter(new java.io.File(game, "moonmc-crash.txt"))) {
                        out.write(body);
                        written = true;
                    } catch (Throwable ignoredCopy) {
                    }
                }
                if (!written) {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        dir.mkdirs();
                        try (java.io.FileWriter out =
                                 new java.io.FileWriter(new java.io.File(dir, "moonmc-crash.txt"))) {
                            out.write(body);
                        }
                    }
                }
            } catch (Throwable ignoredWrite) {
                // писать некуда — молчим
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error);
            }
        });

        // MoonMC: игровой Compose-слой (настройки и управление). Через рефлексию,
        // чтобы сборка без него оставалась рабочей. Файл moonmc-layer-off.txt
        // в папке игры полностью выключает слой — запасной выход при крашах.
        boolean layerOff = new java.io.File(
            getExternalFilesDir(null), "moonmc-layer-off.txt").exists()
            || new java.io.File("/sdcard/MoonMC/horizon/moonmc-layer-off.txt").exists();
        if (!layerOff) try {
            Class<?> layer = Class.forName("dev.moonmc.game.Overlay");
            java.lang.reflect.Method attach =
                layer.getMethod("attach", android.app.Activity.class);
            Object target = java.lang.reflect.Modifier.isStatic(attach.getModifiers())
                ? null
                : layer.getField("INSTANCE").get(null);
            attach.invoke(target, this);
        } catch (Throwable ignored) {
            // слоя нет — играем без него
        }'''

OLD_RESTART = '''    public void restart() {
        runOnUiThread(() -> {
'''

NEW_RESTART = '''    public void restart() {
        runOnUiThread(() -> {
            preparing = false;
            if (splash != null) {
                splash.finishQuietly();
                splash = null;
            }
'''

# Панель навигации возвращается, когда окно теряло фокус (диалоги, клавиатура).
IMMERSIVE_FOCUS = '''    /** MoonMC: после диалогов и клавиатуры полоса навигации всплывает — прячем снова. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            MoonImmersive.apply(getWindow());
        }
    }

'''


def die(msg: str) -> None:
    print(f"patch-branding: {msg}", file=sys.stderr)
    sys.exit(1)


def main() -> int:
    if not os.path.isfile(MANIFEST):
        die(f"нет {MANIFEST} — запускайте из корня дерева MargyMC")
    if not os.path.isdir(BRAND):
        die(f"нет каталога {BRAND}")

    # 1. Иконки: ci/branding/** -> android/res/**
    copied = 0
    for base, _dirs, files in os.walk(BRAND):
        for name in files:
            if not name.endswith((".png", ".xml")):
                continue  # мусор от неудачной загрузки (JSON-листинги каталогов)
            src = os.path.join(base, name)
            if not os.path.isfile(src):
                continue
            rel = os.path.relpath(src, BRAND)
            dst = os.path.join(ANDROID, "res", rel)
            if os.path.isdir(dst):
                continue
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            copied += 1
    print(f"иконки: {copied} файлов -> android/res")

    # 2. Фон adaptive-иконки (белый, как в исходнике) и подпись
    colors = os.path.join(ANDROID, "res", "values", "colors.xml")
    try:
        os.makedirs(os.path.dirname(colors), exist_ok=True)
    except OSError as error:
        die(f"не могу создать каталог ресурсов: {error}")
    pathlib.Path(colors).write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <color name="ic_launcher_background">#FFFFFF</color>\n'
        "</resources>\n"
    )

    # 3. Манифест: label и иконки
    xml = pathlib.Path(MANIFEST).read_text()
    xml = re.sub(r'android:label="[^"]*"', f'android:label="{LABEL}"', xml, count=1)
    if 'android:roundIcon' not in xml:
        xml = xml.replace(
            'android:icon="@mipmap/ic_launcher"',
            'android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher"',
            1,
        )
    pathlib.Path(MANIFEST).write_text(xml)

    label = re.search(r'android:label="([^"]*)"', xml)
    print(f"манифест: label={label.group(1) if label else '???'}, иконка @mipmap/ic_launcher")

    # 4. Экран первой загрузки: файлы ci/splash -> android/java, тело метода в Activity
    if os.path.isdir(SPLASH):
        java_dir = os.path.dirname(ACTIVITY)
        os.makedirs(java_dir, exist_ok=True)
        for name in sorted(os.listdir(SPLASH)):
            if name.endswith(".java"):
                shutil.copyfile(os.path.join(SPLASH, name), os.path.join(java_dir, name))
                print(f"экран загрузки: {name} -> android/java")
        # Содержимое экрана — Compose: Kotlin-файл лежит в Gradle-модуле рядом с оверлеем.
        # Патчер идёт из `src/`, а Gradle-проект лежит в корне репозитория.
        repo_root = os.path.dirname(ROOT)
        kotlin_dir = os.path.join(
            repo_root, "game", "app", "src", "main", "kotlin", "dev", "moonmc", "game"
        )
        try:
            os.makedirs(kotlin_dir, exist_ok=True)
            for name in sorted(os.listdir(SPLASH)):
                if name.endswith(".kt"):
                    shutil.copyfile(os.path.join(SPLASH, name), os.path.join(kotlin_dir, name))
                    print(f"экран загрузки: {name} -> game/app/src/main/kotlin")
        except OSError as error:
            print(f"::warning::экран загрузки: не скопировал Kotlin-часть: {error}")

    code = pathlib.Path(ACTIVITY).read_text()
    if "nativeLibraryDir" not in code:
        anchor = "    /** Called once preparing is done: the app starts again, now with its resources. */"
        method = """    /** MoonMC: папка нативных библиотек (в Gradle-сборке libs лежат внутри APK). */
    public String nativeLibraryDir() {
        String dir = getApplicationInfo().nativeLibraryDir;
        return dir == null ? "" : dir;
    }

"""
        if anchor in code:
            code = code.replace(anchor, method + anchor, 1)
            pathlib.Path(ACTIVITY).write_text(code, encoding="utf-8")
            print("экран: добавлен nativeLibraryDir()")

    if "dev.moonmc.game.Overlay" not in code:
        anchor = "        addContentView(input, new ViewGroup.LayoutParams(1, 1));"
        if anchor not in code:
            die("HorizonActivity.java: не нашёл onCreate для слоя управления")
        code = code.replace(anchor, anchor + "\n" + OVERLAY, 1)
        pathlib.Path(ACTIVITY).write_text(code)
        print("экран: игровой слой подключён")

    # Вырез камеры: игра и слой занимают его тоже, чёрной полосы быть не должно.
    if "LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES" not in code:
        anchor = "        addContentView(input, new ViewGroup.LayoutParams(1, 1));"
        if anchor in code:
            cutout = (
                "\n        // MoonMC: вырез камеры не должен отнимать у игры часть экрана.\n"
                "        if (android.os.Build.VERSION.SDK_INT >= 28) {\n"
                "            android.view.WindowManager.LayoutParams cutout =\n"
                "                getWindow().getAttributes();\n"
                "            cutout.layoutInDisplayCutoutMode =\n"
                "                android.view.WindowManager.LayoutParams"
                ".LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;\n"
                "            getWindow().setAttributes(cutout);\n"
                "        }"
            )
            code = code.replace(anchor, anchor + cutout, 1)
            pathlib.Path(ACTIVITY).write_text(code, encoding="utf-8")
            print("экран: вырез камеры проигнорирован (SHORT_EDGES)")
        else:
            print("::warning::HorizonActivity.java: не нашёл onCreate для выреза")
    # Минимальный immersive: полоса навигации не занимает экран игры. Клавиатуру
    # (IME) не трогаем — immersive её не скрывает, setKeyboard работает как раньше.
    if "MoonImmersive" not in code:
        on_create = "        super.onCreate(state);\n"
        on_focus_anchor = (
            "    /** Called by the game when a text field gains or loses focus. */"
        )
        if on_create not in code or on_focus_anchor not in code:
            die("HorizonActivity.java: не нашёл onCreate для immersive")
        code = code.replace(
            on_create,
            on_create
            + "        // MoonMC: системная полоса навигации не занимает экран.\n"
            + "        MoonImmersive.apply(getWindow());\n",
            1,
        )
        code = code.replace(on_focus_anchor, IMMERSIVE_FOCUS + on_focus_anchor, 1)
        pathlib.Path(ACTIVITY).write_text(code, encoding="utf-8")
        print("экран: полоса навигации спрятана (immersive)")

    if "MoonSplash" in code:
        print("экран загрузки: уже пропатчен")
    else:
        for old, new, what in (
            (OLD_STATUS, NEW_STATUS, "showStatus"),
            (OLD_DISMISS, NEW_DISMISS, "showChoice"),
            (OLD_RESTART, NEW_RESTART, "restart"),
            (OLD_KEYBOARD, NEW_KEYBOARD, "setKeyboard"),
            (OLD_HIDE_BRANCH, NEW_HIDE_BRANCH, "setKeyboard(hide)"),
            (OLD_RESET, NEW_RESET, "TextView.reset"),
            (OLD_OPTIONS, NEW_OPTIONS, "imeOptions"),
            (OLD_REPORT, NEW_REPORT, "hideLater"),
            (OLD_ENTER_KEY, NEW_ENTER_KEY, "sendKeyEvent(ENTER)"),
            (OLD_EDITOR_ACTION, NEW_EDITOR_ACTION, "performEditorAction"),
        ):
            if old not in code:
                die(f"HorizonActivity.java: не нашёл блок {what}")
            code = code.replace(old, new, 1)
        code = code.replace('.setTitle("MargyMC")', '.setTitle("MoonMC")')
        # остатки — только в комментариях и подсказках: /sdcard/MargyMC и старые заголовки
        code = code.replace("MargyMC", "MoonMC")
        if "MargyMC" in code:
            die("HorizonActivity.java: остались упоминания MargyMC")
        pathlib.Path(ACTIVITY).write_text(code)
        print("экран загрузки: AlertDialog MargyMC -> MoonSplash MoonMC")

    # 5. Клавиатура: отправка с отложенным и отменяемым закрытием (ввод — как в нарезке).
    if "hideLater" not in code:
        for old, new, what in (
            (OLD_HIDE_BRANCH, NEW_HIDE_BRANCH, "setKeyboard(hide)"),
            (OLD_RESET, NEW_RESET, "TextView.reset"),
            (OLD_REPORT, NEW_REPORT, "hideLater"),
            (OLD_ENTER_KEY, NEW_ENTER_KEY, "sendKeyEvent(ENTER)"),
            (OLD_EDITOR_ACTION, NEW_EDITOR_ACTION, "performEditorAction"),
        ):
            if old not in code:
                die(f"HorizonActivity.java: не нашёл блок {what}")
            code = code.replace(old, new, 1)
        pathlib.Path(ACTIVITY).write_text(code, encoding="utf-8")
        print("экран: отправка синхронизирует текст и закрывает IME с задержкой")

    # 6. Backspace: клавиатуры на API 24+ шлют deleteSurroundingTextInCodePoints,
    # который в нарезке не переопределён — удаление оставалось в буфере клавиатуры.
    if "deleteSurroundingTextInCodePoints" not in code:
        if OLD_DELETE_CODEPOINTS not in code:
            die("HorizonActivity.java: не нашёл sendKeyEvent для backspace")
        code = code.replace(OLD_DELETE_CODEPOINTS, NEW_DELETE_CODEPOINTS, 1)
        pathlib.Path(ACTIVITY).write_text(code, encoding="utf-8")
        print("экран: backspace идёт через deleteSurroundingTextInCodePoints")

    # 7. Отчёт клавиатуре: полный снимок со словом в наборе больше не дублирует его
    # в extract/fullscreen-редакторе. Буфер, sync() и backspace остаются как в нарезке.
    if "MoonMC: что уже отдано" not in code:
        if OLD_REPORT_BODY not in code:
            die("HorizonActivity.java: не нашёл report() для отчёта клавиатуре")
        code = code.replace(OLD_REPORT_BODY, NEW_REPORT_BODY, 1)
        pathlib.Path(ACTIVITY).write_text(code, encoding="utf-8")
        print("экран: отчёт клавиатуре без дублей составного текста")
    if not label or label.group(1) != LABEL:
        die("не удалось выставить android:label")
    return 0


if __name__ == "__main__":
    sys.exit(main())
