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
                input.reset();'''

# MoonMC: одно соединение на всё время работы. Новое на каждый restartInput давало
# клавиатуре вторую «копию» набранного, и удалённые символы возвращались при наборе.
OLD_CONNECTION = '''            connection = new Connection(this);
            return connection;'''

NEW_CONNECTION = '''            if (connection == null) {
                connection = new Connection(this);
            }
            return connection;'''

# Кнопка в самой клавиатуре — «отправить», а не безликая «готово».
OLD_OPTIONS = '''            info.imeOptions = EditorInfo.IME_ACTION_DONE;'''

NEW_OPTIONS = '''            info.imeOptions = EditorInfo.IME_ACTION_SEND;'''

# MoonMC: своё соединение с клавиатурой. Раньше весь текст шёл через getEditable(),
# а у обычного View его нет: sync() падал до нажатия Enter, поэтому кнопка
# «отправить» ничего не делала, а буквы множились. Теперь источник истины —
# собственный буфер, а getEditable() не используется вовсе.
NEW_CONNECTION_CLASS = '''    static final class Connection extends BaseInputConnection {
        private final View view;
        /** MoonMC: буфер набранного — источник истины вместо getEditable(). */
        private final StringBuilder typing = new StringBuilder();
        /** Текст в процессе автозамены: ещё не подтверждён. */
        private final StringBuilder composing = new StringBuilder();
        /** Что уже отдано игре. */
        private String sent = "";
        /** The keyboard's full-screen editor follows the text (getExtractedText's monitor). */
        private int monitor = -1;

        Connection(View view) {
            super(view, true);
            this.view = view;
        }

        /** Текст, который должен быть в поле: подтверждённое + автозамена. */
        private String current() {
            return typing.toString() + composing;
        }

        /** The field took the text (Enter) or changed: the keyboard starts over. */
        void reset() {
            typing.setLength(0);
            composing.setLength(0);
            sent = "";
            monitor = -1;
            try {
                InputMethodManager imm = (InputMethodManager) view.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.restartInput(view);
            } catch (Throwable error) {
                log("reset", error);
            }
        }

        /** Диагностика ввода: рядом с игрой, плюс строка в лог. */
        private void log(String what, Throwable error) {
            String line = what + ": " + error + System.lineSeparator();
            try {
                java.io.File dir = new java.io.File("/sdcard/MoonMC/horizon");
                dir.mkdirs();
                java.io.File file = new java.io.File(dir, "moonmc-ime.txt");
                if (file.length() > 32768) {
                    file.delete();
                }
                java.io.FileWriter writer = new java.io.FileWriter(file, true);
                writer.write(line);
                writer.close();
            } catch (Throwable ignored) {
            }
            android.util.Log.w("MoonIME", line);
        }

        private ExtractedText extracted() {
            String now = current();
            ExtractedText text = new ExtractedText();
            text.text = now;
            text.startOffset = 0;
            text.selectionStart = now.length();
            text.selectionEnd = now.length();
            return text;
        }

        /** The keyboard's view of the editor: the selection as it is. */
        private void report() {
            try {
                InputMethodManager imm = (InputMethodManager) view.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
                int length = current().length();
                imm.updateSelection(view, length, length, -1, -1);
                if (monitor >= 0) {
                    imm.updateExtractedText(view, monitor, extracted());
                }
            } catch (Throwable error) {
                log("report", error);
            }
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            if ((flags & GET_EXTRACTED_TEXT_MONITOR) != 0 && request != null) {
                monitor = request.token;
            }
            return extracted();
        }

        /** Отдать игре разницу: стереть лишнее, дописать новое. Из буфера, не из Editable. */
        private void sync() {
            try {
                String now = current();
                int common = 0;
                int max = Math.min(now.length(), sent.length());
                while (common < max && now.charAt(common) == sent.charAt(common)) {
                    common++;
                }
                int deleted = sent.codePointCount(common, sent.length());
                for (int i = 0; i < deleted; i++) {
                    nativeKey(KeyEvent.KEYCODE_DEL);
                }
                if (common < now.length()) {
                    nativeText(now.substring(common));
                }
                sent = now;
                report();
            } catch (Throwable error) {
                log("sync", error);
            }
        }

        /** Отправка: набранное в игру, Enter — сообщение ушло, клавиатура закрылась. */
        private void submit() {
            try {
                sync();
                nativeKey(KeyEvent.KEYCODE_ENTER);
            } catch (Throwable error) {
                log("submit", error);
            }
            reset();
            hideKeyboard();
        }

        private void hideKeyboard() {
            try {
                InputMethodManager imm = (InputMethodManager) view.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
                view.clearFocus();
            } catch (Throwable error) {
                log("hide", error);
            }
            OverlayBridge.gameKeyboard(false);
        }

        /** Убрать последний символ из буфера; false — стирать нечего. */
        private boolean backspace() {
            if (composing.length() > 0) {
                composing.setLength(composing.length() - 1);
                return true;
            }
            if (typing.length() > 0) {
                typing.setLength(typing.length() - 1);
                return true;
            }
            return false;
        }

        @Override
        public boolean commitText(CharSequence text, int position) {
            composing.setLength(0);
            if (text != null) {
                typing.append(text);
            }
            sync();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int position) {
            composing.setLength(0);
            if (text != null) {
                composing.append(text);
            }
            sync();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            typing.append(composing);
            composing.setLength(0);
            sync();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int before, int after) {
            for (int i = 0; i < before; i++) {
                backspace();
            }
            sync();
            return before + after > 0;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int code = event.getKeyCode();
                if (code == KeyEvent.KEYCODE_DEL) {
                    if (!backspace()) {
                        nativeKey(KeyEvent.KEYCODE_DEL);
                    }
                    sync();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_ENTER) {
                    submit();
                    return true;
                }
                int unicode = event.getUnicodeChar();
                if (unicode != 0) {
                    typing.append(new String(Character.toChars(unicode)));
                    sync();
                    return true;
                }
            }
            return event.getAction() == KeyEvent.ACTION_UP || super.sendKeyEvent(event);
        }

        @Override
        public boolean performEditorAction(int action) {
            submit();
            return true;
        }
'''

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
    if "MoonSplash" in code:
        print("экран загрузки: уже пропатчен")
    else:
        for old, new, what in (
            (OLD_STATUS, NEW_STATUS, "showStatus"),
            (OLD_DISMISS, NEW_DISMISS, "showChoice"),
            (OLD_RESTART, NEW_RESTART, "restart"),
            (OLD_KEYBOARD, NEW_KEYBOARD, "setKeyboard"),
            (OLD_CONNECTION, NEW_CONNECTION, "onCreateInputConnection"),
            (OLD_OPTIONS, NEW_OPTIONS, "imeOptions"),
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

    # 5. Клавиатура: своё соединение с буфером набранного (отправка, backspace, без дублей).
    if "MoonMC: буфер набранного" not in code:
        marker = "    static final class Connection extends BaseInputConnection {"
        index = code.find(marker)
        if index < 0:
            die("HorizonActivity.java: не нашёл class Connection")
        # class Connection — последнее в файле: хвост заменяем целиком.
        code = code[:index] + NEW_CONNECTION_CLASS + "\n    }\n}\n"
        pathlib.Path(ACTIVITY).write_text(code, encoding="utf-8")
        print("экран: клавиатура на своём буфере (отправка и backspace)")
    if not label or label.group(1) != LABEL:
        die("не удалось выставить android:label")
    return 0


if __name__ == "__main__":
    sys.exit(main())
