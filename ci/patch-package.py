#!/usr/bin/env python3
"""MoonMC-CI: пакет приложения под наш бренд.

`cat.narezany.horizon` -> `dev.moonmc.moonmc`. Меняется везде, где имя пакета
зашито: манифест, Java-файлы (их каталог), JNI-символы нативной библиотеки
(`Java_cat_narezany_horizon_HorizonActivity_*`) и вспомогательные скрипты.
Имя библиотеки (`libhorizon`), ассеты и папка игры не трогаются.

Скрипт идемпотентен и падает, если хоть одно упоминание осталось: собрать APK
со старым пакетом и новым именем нельзя.
"""
import os
import pathlib
import re
import shutil
import sys

OLD = "cat.narezany.horizon"
OLD_DIR = pathlib.Path("android/java/cat/narezany/horizon")
NEW = "dev.moonmc.moonmc"
NEW_DIR = pathlib.Path("android/java/dev/moonmc/moonmc")
MANIFEST = pathlib.Path("android/AndroidManifest.xml")
VERSION_CODE = "100"
VERSION_NAME = "1.0.0"
HELPERS = [
    pathlib.Path("android/bench.sh"),
    pathlib.Path("android/flicker-test.sh"),
    pathlib.Path("android/push-resources.sh"),
]


def die(msg: str) -> None:
    print(f"patch-package: {msg}", file=sys.stderr)
    sys.exit(1)


def move_java() -> int:
    if NEW_DIR.is_dir() and not OLD_DIR.is_dir():
        return 0
    if not OLD_DIR.is_dir():
        die(f"нет каталога {OLD_DIR}")
    NEW_DIR.mkdir(parents=True, exist_ok=True)
    moved = 0
    for path in sorted(OLD_DIR.glob("*.java")):
        text = path.read_text()
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


def patch_rust() -> int:
    old_symbols = "Java_" + OLD.replace(".", "_")
    new_symbols = "Java_" + NEW.replace(".", "_")
    changed = 0
    for path in sorted(pathlib.Path("crates").rglob("*.rs")):
        text = path.read_text()
        if OLD not in text and old_symbols not in text:
            continue
        count = text.count(OLD) + text.count(old_symbols)
        text = text.replace(old_symbols, new_symbols).replace(OLD, NEW)
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
    move_java()
    patch_manifest()
    patch_rust()
    patch_helpers()

    leftovers = []
    for base, _dirs, files in os.walk("android"):
        for name in files:
            if not name.endswith((".java", ".xml", ".sh")):
                continue
            path = pathlib.Path(base) / name
            if OLD in path.read_text(errors="ignore"):
                leftovers.append(str(path))
    for path in pathlib.Path("crates").rglob("*.rs"):
        if OLD in path.read_text(errors="ignore"):
            leftovers.append(str(path))
    if leftovers:
        print(f"::error::остались упоминания {OLD}: {leftovers}", file=sys.stderr)
        return 1
    print(f"пакет приложения: {NEW} (versionName {VERSION_NAME})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
