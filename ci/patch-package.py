#!/usr/bin/env python3
"""MoonMC-CI: пакет приложения под наш бренд.

`cat.narezany.horizon` -> `dev.haruxa.moonmc`. Меняется везде, где имя пакета
зашито: манифест, Java-файлы (их каталог), JNI-символы нативной библиотеки
(`Java_cat_narezany_horizon_HorizonActivity_*`) и вспомогательные скрипты.
Имя библиотеки (`libhorizon`), ассеты и папка игры не трогаются.

Kotlin-пакет слоя (`dev.moonmc.game`) и его JNI/reflection
(`Java_dev_moonmc_game_Native_*`, `Class.forName("dev.moonmc.game.Overlay")`)
остаются как есть: переименован только applicationId APK.

Смена applicationId даёт новую private data dir (/data/data/dev.haruxa.moonmc),
поэтому JRE и ресурсы распакуются заново; общая папка игры /sdcard/MoonMC та же.

Скрипт идемпотентен и падает, если хоть одно упоминание старого пакета
осталось: собрать APK со старым пакетом и новым именем нельзя.
"""
import os
import pathlib
import re
import sys

OLD = "cat.narezany.horizon"
OLD_DIR = pathlib.Path("android/java/cat/narezany/horizon")
NEW = "dev.haruxa.moonmc"
NEW_DIR = pathlib.Path("android/java/dev/haruxa/moonmc")
# Прошлое имя бренда: такое дерево уже пропатчено и молча не чинится.
STALE = "dev.moonmc.moonmc"
STALE_DIR = pathlib.Path("android/java/dev/moonmc/moonmc")
MANIFEST = pathlib.Path("android/AndroidManifest.xml")
VERSION_CODE = "100"
VERSION_NAME = "1.0.0"
HELPERS = [
    pathlib.Path("android/bench.sh"),
    pathlib.Path("android/flicker-test.sh"),
    pathlib.Path("android/push-resources.sh"),
]
SCANNED = (".java", ".xml", ".sh")


def jni(package: str) -> str:
    """JNI-префикс так, как его выводит javac: точки -> подчёркивания."""
    return "Java_" + package.replace(".", "_")


OLD_JNI = jni(OLD)
NEW_JNI = jni(NEW)


def die(msg: str) -> None:
    print(f"patch-package: {msg}", file=sys.stderr)
    sys.exit(1)


def scanned_files():
    """Файлы, где имя пакета может быть зашито строкой."""
    for base, _dirs, files in os.walk("android"):
        for name in files:
            if name.endswith(SCANNED):
                yield pathlib.Path(base) / name
    yield from pathlib.Path("crates").rglob("*.rs")


def mentions(package: str, symbol: str) -> list[str]:
    found = []
    for path in scanned_files():
        text = path.read_text(errors="ignore")
        if package in text or symbol in text:
            found.append(str(path))
    return found


def guard_stale() -> None:
    """Дерево, пропатченное прошлым брендом, требует чистого снимка порта."""
    hits = [str(STALE_DIR)] if STALE_DIR.is_dir() else []
    hits += mentions(STALE, jni(STALE))
    if hits:
        die(f"дерево уже пропатчено прошлым пакетом {STALE}: {hits}; "
            f"нужен чистый снимок порта")


def move_java() -> int:
    if NEW_DIR.is_dir() and not OLD_DIR.is_dir():
        print(f"java: уже в {NEW_DIR}")
        return 0
    if not OLD_DIR.is_dir():
        die(f"нет каталога {OLD_DIR} (дерево уже пропатчено или порт другой)")
    NEW_DIR.mkdir(parents=True, exist_ok=True)
    moved = 0
    for path in sorted(OLD_DIR.glob("*.java")):
        text = path.read_text()
        text = text.replace(OLD_JNI, NEW_JNI)
        text = text.replace(f"package {OLD};", f"package {NEW};")
        text = text.replace(OLD, NEW)
        (NEW_DIR / path.name).write_text(text)
        path.unlink()
        moved += 1
    # пустые каталоги старого пакета
    directory = OLD_DIR
    while directory != pathlib.Path("android/java") and directory.is_dir():
        if any(directory.iterdir()):
            break
        directory.rmdir()
        directory = directory.parent
    print(f"java: {moved} файлов -> {NEW_DIR}")
    return moved


def patch_manifest() -> None:
    if not MANIFEST.is_file():
        die(f"нет {MANIFEST}")
    text = MANIFEST.read_text()
    text = text.replace(f'package="{OLD}"', f'package="{NEW}"')
    text = text.replace(f'android:name="{OLD}.HorizonActivity"',
                        f'android:name="{NEW}.HorizonActivity"')
    text = re.sub(r'android:versionCode="[^"]*"',
                  f'android:versionCode="{VERSION_CODE}"', text, count=1)
    text = re.sub(r'android:versionName="[^"]*"',
                  f'android:versionName="{VERSION_NAME}"', text, count=1)
    MANIFEST.write_text(text)
    package = re.search(r'package="([^"]*)"', text)
    code = re.search(r'android:versionCode="([^"]*)"', text)
    name = re.search(r'android:versionName="([^"]*)"', text)
    print(f"манифест: package={package.group(1) if package else '???'}, "
          f"versionCode={code.group(1) if code else '???'}, "
          f"versionName={name.group(1) if name else '???'}")
    if not package or package.group(1) != NEW:
        die("пакет в манифесте не сменился")
    if f'android:name="{NEW}.HorizonActivity"' not in text:
        die("activity в манифесте не переехала в новый пакет")


def patch_rust() -> int:
    changed = 0
    for path in sorted(pathlib.Path("crates").rglob("*.rs")):
        text = path.read_text()
        if OLD not in text and OLD_JNI not in text:
            continue
        count = text.count(OLD) + text.count(OLD_JNI)
        text = text.replace(OLD_JNI, NEW_JNI).replace(OLD, NEW)
        path.write_text(text)
        changed += count
        print(f"{path}: {count} упоминаний -> {NEW}")
    return changed


def patch_helpers() -> int:
    rows = 0
    for path in HELPERS:
        if not path.is_file():
            continue
        text = path.read_text()
        if OLD not in text:
            continue
        rows += text.count(OLD)
        path.write_text(text.replace(OLD, NEW))
        print(f"{path}: PKG -> {NEW}")
    return rows


def main() -> int:
    guard_stale()
    move_java()
    patch_manifest()
    patch_rust()
    patch_helpers()

    activity = NEW_DIR / "HorizonActivity.java"
    if not activity.is_file():
        die(f"после переноса нет {activity}")
    if OLD_DIR.is_dir():
        die(f"старый каталог {OLD_DIR} не удалён")

    leftovers = mentions(OLD, OLD_JNI)
    if leftovers:
        print(f"::error::остались упоминания {OLD} / {OLD_JNI}: {leftovers}",
              file=sys.stderr)
        return 1
    print(f"пакет приложения: {NEW} (JNI {NEW_JNI}_HorizonActivity_*, "
          f"versionName {VERSION_NAME})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
