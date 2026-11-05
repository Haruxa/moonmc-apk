#!/usr/bin/env python3
"""MoonMC: проверки экрана запуска и анимации знака — без телефона и SDK.

Что проверяем и почему:

1. Знак — ровно та иконка, что просили (`/sdcard/Download/иконки/Лаунчер.png`):
   PNG в `ci/branding/drawable-nodpi/splash_moon.png` побайтно совпадает с ней,
   прозрачный фон на месте, и из неё получается сетка с полумесяцем, а не
   случайное пятно. Иначе анимация «собирает» не тот логотип.
2. PNG доходит до `android/res` в обеих сборках: patch-branding копирует
   `ci/branding/**` в `android/res/**` (Gradle-шаг CI копирует этот res в
   `game/app/src/main/res`), а `SplashUi.kt` ищет drawable по имени, без R.
3. Kotlin/Java-тексты: экран не теряет ни шаг подготовки, ни `createHost`, ни
   текстовый fallback для aapt2-сборки; анимация одноразовая (LaunchedEffect,
   без бесконечных переходов вокруг знака) и без новых зависимостей.
4. Патчер на копии `ci/port/port.tgz` (как в CI): заставка подключается и к
   обычному запуску, подготовка (`showStatus`) не ломается, повторный прогон
   не меняет ни байта.

Компиляцию Kotlin/Java не заменяет: kotlinc/javac и Android SDK в Termux нет.

Запуск: python3 ci/test-splash.py [--keep]
"""
import hashlib
import pathlib
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import zlib

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
TGZ = HERE / "port" / "port.tgz"
BRANDING = HERE / "patch-branding.py"
ICON = pathlib.Path("/sdcard/Download/иконки/Лаунчер.png")
LOGO = HERE / "branding" / "drawable-nodpi" / "splash_moon.png"
SPLASH_KT = HERE / "splash" / "SplashUi.kt"
SPLASH_JAVA = HERE / "splash" / "MoonSplash.java"
OVERLAY = REPO / "game/app/src/main/kotlin/dev/moonmc/game/Overlay.kt"

LOGO_NAME = "splash_moon"
COLS = 14
ROWS = 17
ALPHA_CUT = 96

FAILS: list[str] = []


def check(condition: bool, what: str, detail: str = "") -> None:
    if condition:
        print(f"  ok   {what}")
    else:
        print(f"  FAIL {what}{': ' + detail if detail else ''}")
        FAILS.append(what)


def file_digest(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


# --- мини-декодер PNG (нужен только RGBA8, без интерлейса) -------------------

def read_png(path: pathlib.Path):
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise AssertionError(f"{path}: не PNG")
    pos = 8
    idat = b""
    width = height = 0
    bit_depth = color_type = -1
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _comp, _filt, interlace = struct.unpack(
                ">IIBBBBB", chunk)
            if interlace:
                raise AssertionError(f"{path}: изображение черезстрочное")
        elif kind == b"IDAT":
            idat += chunk
        pos += 12 + length
        if kind == b"IEND":
            break
    if color_type != 6 or bit_depth != 8:
        raise AssertionError(f"{path}: ждали RGBA8, получили type={color_type} bd={bit_depth}")
    if width <= 0 or height <= 0:
        raise AssertionError(f"{path}: нет размера в IHDR")
    raw = zlib.decompress(idat)
    channels = 4
    stride = width * channels
    out = bytearray(height * stride)
    prev = bytearray(stride)
    offset = 0
    for y in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                up = prev[i]
                corner = prev[i - channels] if i >= channels else 0
                pa, pb, pc = abs(up - corner), abs(left - corner), abs(left + up - 2 * corner)
                pred = left if (pa <= pb and pa <= pc) else (up if pb <= pc else corner)
                line[i] = (line[i] + pred) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return width, height, out


def alpha_at(out: bytearray, width: int, x: int, y: int) -> int:
    return out[(y * width + x) * 4 + 3]


def grid(out: bytearray, width: int, height: int):
    """Та же сетка, что строит SplashUi.loadGlyph: обрезка полей + большинство в клетке."""
    left, top, right, bottom = width, height, -1, -1
    for y in range(height):
        row = y * width
        for x in range(width):
            if out[(row + x) * 4 + 3] >= ALPHA_CUT:
                left = min(left, x)
                right = max(right, x)
                top = min(top, y)
                bottom = max(bottom, y)
    if right < left:
        raise AssertionError("в PNG нет непрозрачных пикселей")
    art_w = right - left + 1
    art_h = bottom - top + 1
    rows = max(1, round(COLS * art_h / art_w))
    filled = [[False] * COLS for _ in range(rows)]
    for row in range(rows):
        y0 = top + art_h * row // rows
        y1 = top + art_h * (row + 1) // rows
        for col in range(COLS):
            x0 = left + art_w * col // COLS
            x1 = left + art_w * (col + 1) // COLS
            step_x = max(1, (x1 - x0) // 4)
            step_y = max(1, (y1 - y0) // 4)
            hit = total = 0
            for y in range(y0, y1, step_y):
                for x in range(x0, x1, step_x):
                    total += 1
                    if alpha_at(out, width, x, y) >= ALPHA_CUT:
                        hit += 1
            filled[row][col] = total > 0 and hit * 2 >= total
    return rows, filled


# --- код --------------------------------------------------------------------

def balanced(text: str) -> tuple[bool, str]:
    """Баланс скобок вне строк и комментариев (грубая, но полезная проверка)."""
    pairs = {")": "(", "]": "[", "}": "{"}
    stack: list[str] = []
    i = 0
    while i < len(text):
        ch = text[i]
        two = text[i:i + 2]
        if two == "//":
            i = text.find("\n", i)
            if i < 0:
                return not stack, "незакрытый комментарий в конце"
            continue
        if two == "/*":
            i = text.find("*/", i + 2)
            if i < 0:
                return False, "незакрытый блочный комментарий"
            i += 2
            continue
        if two == '"""':
            i = text.find('"""', i + 3)
            if i < 0:
                return False, "незакрытая тройная кавычка"
            i += 3
            continue
        if ch == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if ch == "'" and text[i:i + 2] != "'\\":
            i += 1
            while i < len(text) and text[i] != "'":
                i += 2 if text[i] == "\\" else 1
            i += 1
            continue
        if ch in "([{":
            stack.append(ch)
        elif ch in ")]}" and (not stack or stack.pop() != pairs[ch]):
            return False, f"лишняя {ch}"
        i += 1
    return not stack, "незакрытые: " + "".join(stack) if stack else ""


def test_icon() -> None:
    print("1. Знак — та самая иконка лаунчера")
    check(LOGO.is_file(), f"{LOGO.relative_to(REPO)} на месте")
    if not LOGO.is_file():
        return
    if ICON.is_file():
        check(file_digest(LOGO) == file_digest(ICON),
              "splash_moon.png побайтно равен /sdcard/Download/иконки/Лаунчер.png")
    else:
        print("  skip исходник на /sdcard недоступен — сравнивать не с чем")
    width, height, out = read_png(LOGO)
    check((width, height) != (0, 0), f"PNG {width}x{height}")
    check(any(alpha_at(out, width, 0, 0) < ALPHA_CUT for _ in [0]), "угол прозрачный")
    rows, filled = grid(out, width, height)
    count = sum(1 for row in filled for cell in row if cell)
    check(rows == ROWS, f"сетка {COLS}x{rows} (пропорции знака)")
    check(90 <= count <= 140, f"клеток {count}", str(count))
    # Полумесяц: правый верхний угол пуст, нижняя половина шире верхней.
    top_right = sum(sum(1 for cell in filled[r][COLS * 2 // 3:] if cell)
                    for r in range(rows // 3))
    check(top_right <= 2, f"правый верхний угол пуст ({top_right})")
    check(any(filled[0]) and any(filled[rows - 1]), "знак занимает всю высоту сетки")
    gaps = [r for r in range(1, rows - 1)
            if not any(filled[r]) and any(filled[r - 1]) and any(filled[r + 1])]
    check(bool(gaps), "горизонтальный разрыв полумесяца сохранён", str(gaps))


def test_kotlin() -> None:
    print("2. Kotlin-экран: шаги, знак, одноразовая анимация")
    text = SPLASH_KT.read_text()
    ok, why = balanced(text)
    check(ok, "скобки/кавычки сбалансированы", why)
    check(f'LOGO_RESOURCE = "{LOGO_NAME}"' in text, f"drawable по имени {LOGO_NAME}")
    check("getIdentifier(LOGO_RESOURCE" in text, "ресурс ищется без R (обе сборки)")
    check(text.count("fun step(text: String)") == 1, "SplashUi.step на месте")
    check("fun createHost(context: Context): View" in text, "SplashUi.createHost на месте")
    check("currentStep.collectAsState()" in text, "шаг подготовки показывается")
    check("LaunchedEffect(glyph)" in text, "анимация знака — один прогон на композицию")
    check("RememberInfiniteTransition" not in text or "rememberInfiniteTransition(label = \"splash\")" in text,
          "бесконечный переход только у звёзд")
    logo_block = text[text.find("private fun MoonLogo"):]
    for marker in ("Animatable", "lerp(glyph.ink, target, tint.value)",
                   "rainbow[rainbowIndex]", "drawRect("):
        check(marker in logo_block, f"знак: {marker}")
    check("BitmapFactory.decodeResource" in logo_block or
          "BitmapFactory.decodeResource" in text, "PNG читается через BitmapFactory")
    check("bitmap.recycle()" in text, "bitmap освобождается")
    check("contentDescription = \"MoonMC logo\"" in text, "у знака есть описание")
    check("liveRegion = LiveRegionMode.Polite" in text, "шаг объявляется диктором")
    imports = set(re.findall(r"^import ([a-zA-Z0-9_.]+)$", text, re.M))
    foreign = sorted(i for i in imports
                     if not i.startswith(("android.", "androidx.", "kotlin.", "kotlinx.")))
    check(not foreign, "без новых зависимостей", str(foreign))
    check("androidx.compose.material3" in text, "тема Material 3")


def test_java_and_theme() -> None:
    print("3. Java-хост и тема: fallback + dynamic light/dark")
    java = SPLASH_JAVA.read_text()
    ok, why = balanced(java)
    check(ok, "MoonSplash.java: скобки сбалансированы", why)
    check("class MoonSplash extends Dialog" in java, "диалог на месте")
    check("fallback.setText(\"MoonMC\\n\\n\"" in java, "текстовый fallback для aapt2")
    check("void step(String text)" in java, "шаг подготовки проходит и в fallback")
    check("void showStartup()" in java, "заставка обычного запуска")
    check("FLAG_NOT_TOUCHABLE" in java and "FLAG_NOT_FOCUSABLE" in java,
          "заставка запуска не перехватывает касания")
    check("handler.removeCallbacks(autoFinish)" in java, "автоскрытие отменяется подготовкой")
    check("void finishQuietly()" in java, "закрытие без исключения")
    check("getMethod(\"createHost\", Context.class)" in java, "рефлексия на SplashUi сохранена")

    overlay = OVERLAY.read_text()
    check("followSystem: Boolean = false" in overlay, "MoonTheme: режим по системе")
    check("dynamicLightColorScheme(context)" in overlay, "MoonTheme: dynamicLight")
    check("dynamicDarkColorScheme(context)" in overlay, "MoonTheme: dynamicDark")
    check("darkColorScheme(" in overlay, "MoonTheme: фирменный fallback")
    check("Overlay.MoonTheme(context, followSystem = true)" in SPLASH_KT.read_text(),
          "экран запуска берёт тему по системе")


def run(script: pathlib.Path, cwd: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(script)], cwd=cwd,
                          capture_output=True, text=True, check=False)


def digest(base: pathlib.Path) -> str:
    sha = hashlib.sha256()
    for path in sorted(p for p in base.rglob("*") if not p.is_symlink()):
        if path.is_file():
            sha.update(str(path.relative_to(base)).encode())
            sha.update(path.read_bytes())
    return sha.hexdigest()


def test_patch(root: pathlib.Path) -> None:
    print("4. Патчер: подключение к обычному запуску и идемпотентность")
    port = root / "main" / "port"
    port.mkdir(parents=True)
    with tarfile.open(TGZ) as tar:
        tar.extractall(port, filter="data")
    (port / "ci").symlink_to(HERE.resolve(), target_is_directory=True)
    result = run(BRANDING, port)
    check(result.returncode == 0, "patch-branding.py прошёл",
          (result.stdout + result.stderr)[-600:])
    activity_path = port / "android/java/cat/narezany/horizon/HorizonActivity.java"
    if not activity_path.is_file():
        check(False, "HorizonActivity.java найден")
        return
    activity = activity_path.read_text()
    ok, why = balanced(activity)
    check(ok, "HorizonActivity.java: скобки сбалансированы", why)
    check("private void showStartupSplash()" in activity, "метод заставки добавлен")
    check("getWindow().getDecorView().post(this::showStartupSplash);" in activity,
          "заставка показывается при обычном запуске")
    check("new MoonSplash(this, true)" in activity, "заставка запуска — с автоскрытием")
    check("splash == null || !splash.isShowing()" in activity,
          "ушедшая заставка не считается показанной")
    check("public void showStatus(final String text)" in activity, "шаг подготовки на месте")
    check(activity.count("showStartupSplash") == 2,
          "заставка запуска: метод и один вызов", str(activity.count("showStartupSplash")))
    check("MoonSplash splash;" in activity, "поле диалога на месте")

    splash_java = port / "android/java/cat/narezany/horizon/MoonSplash.java"
    check(splash_java.is_file(), "MoonSplash.java скопирован в android/java")
    if splash_java.is_file():
        check("void showStartup()" in splash_java.read_text(), "showStartup в дереве")
    kt_copy = root / "main" / "game/app/src/main/kotlin/dev/moonmc/game/SplashUi.kt"
    check(kt_copy.is_file() and "MoonLogo" in kt_copy.read_text(),
          "SplashUi.kt скопирован в Gradle-модуль")
    logo = port / "android/res/drawable-nodpi" / f"{LOGO_NAME}.png"
    check(logo.is_file(), f"android/res/drawable-nodpi/{LOGO_NAME}.png")
    if logo.is_file():
        check(file_digest(logo) == file_digest(LOGO), "ресурс — тот же PNG без пересжатия")

    before = digest(port)
    again = run(BRANDING, port)
    check(again.returncode == 0, "повторный прогон без ошибок",
          (again.stdout + again.stderr)[-400:])
    check(digest(port) == before, "повторный прогон не меняет дерево")


def main() -> int:
    keep = "--keep" in sys.argv
    test_icon()
    test_kotlin()
    test_java_and_theme()
    root = pathlib.Path(tempfile.mkdtemp(prefix="moonmc-splash-", dir=pathlib.Path.home()))
    try:
        test_patch(root)
    finally:
        if keep:
            print(f"оставлено: {root}")
        else:
            shutil.rmtree(root, ignore_errors=True)
    if FAILS:
        print(f"\nПРОВАЛЕНО: {len(FAILS)} — {FAILS}")
        return 1
    print("\nвсе проверки прошли")
    return 0


if __name__ == "__main__":
    sys.exit(main())
