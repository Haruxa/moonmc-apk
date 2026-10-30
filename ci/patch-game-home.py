#!/usr/bin/env python3
"""MargyMC-CI v2: папка игры на /sdcard, но JRE и tmp — в приватном хранилище.

Зачем папка игры на /sdcard: в stock MargyMC «папка игры» —
`app.internal_data_path()` (/data/data/cat.narezany.horizon/files), куда файловый
менеджер попасть не может, а там живут `horizon-mods/*.hmod`, миры, resourcepacks.

Почему JRE отдельно: /sdcard — это FUSE, симлинки там запрещены, а OpenJDK
кладёт их в `legal/`. `tar::Archive::unpack(jre)` падал на
`jre/legal/java.scripting/ASSEMBLY_EXCEPTION`.

Правки:
1. crates/horizon-client/src/android.rs   — home → /sdcard/MargyMC/horizon
   (с проверкой записи; нет доступа — прежний приватный путь) + переменная
   окружения HORIZON_PRIVATE_HOME с приватной папкой.
2. crates/horizon-client/src/android_setup.rs — `jre` и `cache` берутся из
   HORIZON_PRIVATE_HOME.

Скрипт падает, если какой-то исходный блок не найден: молча собирать
непатченный APK нельзя.
"""
import pathlib
import sys

SDK_MARK = "MargyMC-CI v2"

EDITS = [
    (
        pathlib.Path("crates/horizon-client/src/android.rs"),
        """    let home = app
        .internal_data_path()
        .or_else(|| app.external_data_path())
        .unwrap_or_else(|| std::path::PathBuf::from("/data/local/tmp/horizon"));
    let _ = std::fs::create_dir_all(&home);
""",
        """    // MargyMC-CI v2: папка игры на внешнем носителе, чтобы моды, миры и настройки
    // были видны файловому менеджеру и попадали в бэкап. Нет доступа к /sdcard —
    // возвращаемся к приватной папке приложения, как в stock-сборке.
    let private = app
        .internal_data_path()
        .or_else(|| app.external_data_path())
        .unwrap_or_else(|| std::path::PathBuf::from("/data/local/tmp/horizon"));
    let _ = std::fs::create_dir_all(&private);
    // SAFETY: no other thread reads the environment yet (see HORIZON_HOME below).
    // The JRE and Java's temp files stay here: /sdcard is FUSE, which has no symlinks,
    // and OpenJDK's legal/ folder is made of them.
    unsafe { std::env::set_var("HORIZON_PRIVATE_HOME", private.as_os_str()) };
    let external = std::path::PathBuf::from("/sdcard/MargyMC/horizon");
    let external_usable = std::fs::create_dir_all(&external).is_ok() && {
        let probe = external.join(".horizon-write-probe");
        let ok = std::fs::write(&probe, b"probe").is_ok();
        let _ = std::fs::remove_file(&probe);
        ok
    };
    let home = if external_usable { external } else { private.clone() };
    let _ = std::fs::create_dir_all(&home);
""",
    ),
    (
        pathlib.Path("crates/horizon-client/src/android_setup.rs"),
        """    let jre = home.join("jre");
""",
        """    // MargyMC-CI v2: the JRE lives on the private storage: /sdcard is FUSE, which has
    // no symlinks, and OpenJDK's legal/ folder is made of them.
    let jre = std::env::var_os("HORIZON_PRIVATE_HOME")
        .map(|dir| std::path::PathBuf::from(dir).join("jre"))
        .unwrap_or_else(|| home.join("jre"));
""",
    ),
    (
        pathlib.Path("crates/horizon-client/src/android_setup.rs"),
        """    let cache = home.join("tmp");
""",
        """    // MargyMC-CI v2: Java's temporary files, on the private storage with the JRE.
    let cache = std::env::var_os("HORIZON_PRIVATE_HOME")
        .map(|dir| std::path::PathBuf::from(dir).join("tmp"))
        .unwrap_or_else(|| home.join("tmp"));
""",
    ),
]


def main() -> int:
    failed = False
    for path, original, patched in EDITS:
        text = path.read_text()
        if SDK_MARK in text and patched in text:
            print(f"{path}: патч уже наложен, пропускаю")
            continue
        if original not in text:
            print(f"::error::{path}: не найден исходный блок", file=sys.stderr)
            print("Ожидался блок:\n" + original, file=sys.stderr)
            failed = True
            continue
        path.write_text(text.replace(original, patched, 1))
        print(f"{path}: пропатчен")
    if failed:
        return 1
    print("MargyMC-CI v2: папка игры /sdcard/MargyMC/horizon, JRE и tmp — в приватном хранилище")
    return 0


if __name__ == "__main__":
    sys.exit(main())
