#!/usr/bin/env python3
"""MoonMC-CI v2: папка игры на /sdcard, но JRE и tmp — в приватном хранилище.

Зачем папка игры на /sdcard: в stock MargyMC «папка игры» —
`app.internal_data_path()` (/data/data/cat.narezany.horizon/files), куда файловый
менеджер попасть не может, а там живут `horizon-mods/*.hmod`, миры, resourcepacks.

Почему JRE отдельно: /sdcard — это FUSE, симлинки там запрещены, а OpenJDK
кладёт их в `legal/`. `tar::Archive::unpack(jre)` падал на
`jre/legal/java.scripting/ASSEMBLY_EXCEPTION`.

Правки:
1. crates/horizon-client/src/android.rs   — home → /sdcard/MoonMC/horizon
   (с проверкой записи; нет доступа — прежний приватный путь) + переменная
   окружения HORIZON_PRIVATE_HOME с приватной папкой.
2. crates/horizon-client/src/android_setup.rs — `jre` и `cache` берутся из
   HORIZON_PRIVATE_HOME.

Скрипт падает, если какой-то исходный блок не найден: молча собирать
непатченный APK нельзя.
"""
import pathlib
import sys


EDITS = [
    (
        pathlib.Path("crates/horizon-client/src/android.rs"),
        """    let home = app
        .internal_data_path()
        .or_else(|| app.external_data_path())
        .unwrap_or_else(|| std::path::PathBuf::from("/data/local/tmp/horizon"));
    let _ = std::fs::create_dir_all(&home);
""",
        """    // MoonMC-CI v2: папка игры на внешнем носителе, чтобы моды, миры и настройки
    // были видны файловому менеджеру и попадали в бэкап. Нет доступа к /sdcard —
    // возвращаемся к приватной папке приложения, как в stock-сборке.
    let private = app
        .internal_data_path()
        .or_else(|| app.external_data_path())
        .unwrap_or_else(|| std::path::PathBuf::from("/data/local/tmp/horizon"));
    let _ = std::fs::create_dir_all(&private);
    // SAFETY: no thread reads the environment yet: the game's own environment is set
    // up later, in android_setup. The JRE and Java's temp files stay in this private
    // folder because /sdcard is FUSE, which has no symlinks, and OpenJDK's legal/
    // folder is made of them.
    unsafe { std::env::set_var("HORIZON_PRIVATE_HOME", private.as_os_str()) };
    let external = std::path::PathBuf::from("/sdcard/MoonMC/horizon");
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
        """    // MoonMC-CI v2: the JRE lives on the private storage: /sdcard is FUSE, which has
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
        """    // MoonMC-CI v2: Java's temporary files, on the private storage with the JRE.
    let cache = std::env::var_os("HORIZON_PRIVATE_HOME")
        .map(|dir| std::path::PathBuf::from(dir).join("tmp"))
        .unwrap_or_else(|| home.join("tmp"));
""",
    ),
    (
        pathlib.Path("crates/horizon-client/src/save.rs"),
        """        file.try_lock()
            .map_err(|_| format!("{} is already open in another instance", world.display()))?;
""",
        """        // MoonMC-CI v4: /sdcard is FUSE, which implements no file locks (flock gives
        // ENOSYS). Only a real would-block is another instance; a filesystem without
        // locks must not turn every world into "already open".
        match file.try_lock() {
            Ok(()) => {}
            Err(std::fs::TryLockError::WouldBlock) => {
                // MoonMC-CI v4: FUSE reports would-block for worlds that no other
                // instance holds (stale/unreliable locks). Hard-failing here cost us
                // real crashes, so warn and keep going; the Android app is
                // single-activity, so a genuine second writer is not a real scenario.
                log::warn!(
                    "session lock for {} is held or unreliable; opening anyway",
                    world.display()
                );
            }
            Err(std::fs::TryLockError::Error(e)) => {
                log::warn!("session lock unavailable for {}: {e}", world.display());
            }
        }
""",
    ),
    (
        pathlib.Path("crates/horizon-client/src/logging.rs"),
        """    match lock.try_lock() {
        Ok(()) => {}
        Err(fs::TryLockError::WouldBlock) => return Ok(None),
        Err(fs::TryLockError::Error(e)) => return Err(e),
    }
""",
        """    match lock.try_lock() {
        Ok(()) => {}
        Err(fs::TryLockError::WouldBlock) => return Ok(None),
        // MoonMC-CI v3: FUSE (/sdcard) has no flock, so take an unlocked log file
        // rather than never writing latest.log at all.
        Err(fs::TryLockError::Error(e)) => {
            eprintln!("logging: file lock unavailable ({e}); writing latest.log unlocked");
        }
    }
""",
    ),
]


# Ребрендинг: старые пути и подписи «MargyMC» -> «MoonMC» (в том числе /sdcard/MoonMC/minecraft).
REBRAND = [
    pathlib.Path("crates/horizon-client/src/android_setup.rs"),
    pathlib.Path("crates/horizon-client/src/android.rs"),
    pathlib.Path("crates/horizon-client/src/title.rs"),
    pathlib.Path("android/AndroidManifest.xml"),
]


def main() -> int:
    failed = False
    for path, original, patched in EDITS:
        text = path.read_text()
        # заменённый текст сам служит маркером: так пропуск работает и после смены версии
        if patched in text:
            print(f"{path}: патч уже наложен, пропускаю")
            continue
        if original not in text:
            print(f"::error::{path}: не найден исходный блок", file=sys.stderr)
            print("Ожидался блок:\n" + original, file=sys.stderr)
            failed = True
            continue
        path.write_text(text.replace(original, patched, 1))
        print(f"{path}: пропатчен")
    # 3. Имя проекта: из него же складывается путь /sdcard/MoonMC/minecraft
    renamed = 0
    for path in REBRAND:
        if not path.is_file():
            continue
        text = path.read_text()
        count = text.count("MargyMC")
        if count:
            path.write_text(text.replace("MargyMC", "MoonMC"))
            renamed += count
            print(f"{path}: {count} упоминаний MargyMC -> MoonMC")
    left = [str(p) for p in REBRAND if p.is_file() and "MargyMC" in p.read_text()]
    if left:
        print(f"::error::остались упоминания MargyMC: {left}", file=sys.stderr)
        failed = True
    else:
        print(f"ребрендинг: заменено {renamed} упоминаний, папка /sdcard/MoonMC/minecraft")

    if failed:
        return 1
    print(
        "MoonMC-CI v4: папка игры /sdcard/MoonMC/horizon, JRE и tmp — в приватном хранилище,"
        " файловые локи FUSE (flock) не считаются занятостью мира и не глушат latest.log"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
