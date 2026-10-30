#!/usr/bin/env python3
"""Переносит папку игры на Android с приватных данных приложения на /sdcard.

Зачем: в stock MargyMC «папка игры» — `app.internal_data_path()`
(/data/data/cat.narezany.horizon/files), куда файловый менеджер попасть не может.
Моды (`horizon-mods/*.hmod`), миры, resourcepacks и настройки живут именно там.

Патч меняет выбор home в `crates/horizon-client/src/android.rs` (`android_main`):
сначала пробуем /sdcard/MargyMC/horizon (нужен MANAGE_EXTERNAL_STORAGE, который
приложение всё равно просит), при неудаче — прежний приватный путь.

Скрипт падает с ошибкой, если исходный блок не найден: молчаливо собирать
непатченный APK нельзя.
"""
import pathlib
import sys

TARGET = pathlib.Path("crates/horizon-client/src/android.rs")

ORIGINAL = """    let home = app
        .internal_data_path()
        .or_else(|| app.external_data_path())
        .unwrap_or_else(|| std::path::PathBuf::from("/data/local/tmp/horizon"));
"""

PATCHED = """    // MargyMC-CI: папка игры на внешнем носителе, чтобы моды, миры и настройки
    // были видны файловому менеджеру и попадали в бэкап. Нет доступа к /sdcard —
    // возвращаемся к приватной папке приложения, как в stock-сборке.
    let external = std::path::PathBuf::from("/sdcard/MargyMC/horizon");
    let external_usable = std::fs::create_dir_all(&external).is_ok() && {
        let probe = external.join(".horizon-write-probe");
        let ok = std::fs::write(&probe, b"probe").is_ok();
        let _ = std::fs::remove_file(&probe);
        ok
    };
    let home = if external_usable {
        external
    } else {
        app
            .internal_data_path()
            .or_else(|| app.external_data_path())
            .unwrap_or_else(|| std::path::PathBuf::from("/data/local/tmp/horizon"))
    };
"""


def main() -> int:
    text = TARGET.read_text()
    if PATCHED.splitlines()[0].strip() in text:
        print("патч уже наложен, пропускаю")
        return 0
    if ORIGINAL not in text:
        print("::error::не нашёл исходный блок выбора home в " + str(TARGET), file=sys.stderr)
        print("Ожидался блок:", file=sys.stderr)
        print(ORIGINAL, file=sys.stderr)
        return 1
    TARGET.write_text(text.replace(ORIGINAL, PATCHED, 1))
    print(f"пропатчен {TARGET}: home → /sdcard/MargyMC/horizon")
    return 0


if __name__ == "__main__":
    sys.exit(main())
