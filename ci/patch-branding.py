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
            if (show) {
                input.reset();'''

OVERLAY = '''        // MoonMC: любая необработанная ошибка пишется рядом с игрой — иначе краш
        // на старте нечем диагностировать (лог Rust в этот момент ещё пуст).
        final Thread.UncaughtExceptionHandler previousHandler =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                java.io.StringWriter text = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(text));
                String body = thread.getName() + "\\n" + text;
                // Сначала своя внешняя папка: она доступна без разрешения на все файлы,
                // потом папка игры (если разрешение уже выдано).
                boolean written = false;
                java.io.File dir = getExternalFilesDir(null);
                if (dir != null) {
                    dir.mkdirs();
                    try (java.io.FileWriter out =
                             new java.io.FileWriter(new java.io.File(dir, "moonmc-crash.txt"))) {
                        out.write(body);
                        written = true;
                    }
                }
                java.io.File game = new java.io.File("/sdcard/MoonMC/horizon");
                if (game.mkdirs() || game.isDirectory()) {
                    try (java.io.FileWriter out =
                             new java.io.FileWriter(new java.io.File(game, "moonmc-crash.txt"))) {
                        if (!written) {
                            out.write(body);
                        }
                    } catch (Throwable ignoredCopy) {
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
    os.makedirs(os.path.dirname(colors), exist_ok=True)
    open(colors, "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        '    <color name="ic_launcher_background">#FFFFFF</color>\n'
        "</resources>\n"
    )

    # 3. Манифест: label и иконки
    xml = open(MANIFEST).read()
    xml = re.sub(r'android:label="[^"]*"', f'android:label="{LABEL}"', xml, count=1)
    if 'android:roundIcon' not in xml:
        xml = xml.replace(
            'android:icon="@mipmap/ic_launcher"',
            'android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher"',
            1,
        )
    open(MANIFEST, "w").write(xml)

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

    code = open(ACTIVITY).read()
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
            open(ACTIVITY, "w").write(code)
            print("экран: добавлен nativeLibraryDir()")

    if "dev.moonmc.game.Overlay" not in code:
        anchor = "        addContentView(input, new ViewGroup.LayoutParams(1, 1));"
        if anchor not in code:
            die("HorizonActivity.java: не нашёл onCreate для слоя управления")
        code = code.replace(anchor, anchor + "\n" + OVERLAY, 1)
        open(ACTIVITY, "w").write(code)
        print("экран: игровой слой подключён")
    if "MoonSplash" in code:
        print("экран загрузки: уже пропатчен")
    else:
        for old, new, what in (
            (OLD_STATUS, NEW_STATUS, "showStatus"),
            (OLD_DISMISS, NEW_DISMISS, "showChoice"),
            (OLD_RESTART, NEW_RESTART, "restart"),
            (OLD_KEYBOARD, NEW_KEYBOARD, "setKeyboard"),
        ):
            if old not in code:
                die(f"HorizonActivity.java: не нашёл блок {what}")
            code = code.replace(old, new, 1)
        code = code.replace('.setTitle("MargyMC")', '.setTitle("MoonMC")')
        # остатки — только в комментариях и подсказках: /sdcard/MargyMC и старые заголовки
        code = code.replace("MargyMC", "MoonMC")
        if "MargyMC" in code:
            die("HorizonActivity.java: остались упоминания MargyMC")
        open(ACTIVITY, "w").write(code)
        print("экран загрузки: AlertDialog MargyMC -> MoonSplash MoonMC")
    if not label or label.group(1) != LABEL:
        die("не удалось выставить android:label")
    return 0


if __name__ == "__main__":
    sys.exit(main())
