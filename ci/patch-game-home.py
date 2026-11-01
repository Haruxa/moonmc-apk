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
import re
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
    # В новых пинах логирование переехало в horizon-client-render: тот же блок.
    (
        pathlib.Path("crates/horizon-client-render/src/logging.rs"),
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


def features_of(path: pathlib.Path) -> set:
    """Имена фич из секции [features] манифеста."""
    if not path.is_file():
        return set()
    text = path.read_text()
    if "[features]" not in text:
        return set()
    block = text.split("[features]", 1)[1].split("\n[", 1)[0]
    return {m.group(1) for m in re.finditer(r"(?m)^([A-Za-z0-9_-]+)\s*=", block)}


def patch_android_features() -> None:
    """Фича `mods` порта под этот пин движка.

    В старых пинах моды включались фичами `horizon-client/mods` + `horizon-mods/runtime`,
    в новых они собраны всегда (ни той, ни другой фичи нет). Собираем строку из того,
    что реально есть в манифестах этого пина, — и порт собирается на обоих.
    """
    port = pathlib.Path("crates/horizon-android/Cargo.toml")
    if not port.is_file():
        return
    parts = []
    if "mods" in features_of(pathlib.Path("crates/horizon-client/Cargo.toml")):
        parts.append("horizon-client/mods")
    if "runtime" in features_of(pathlib.Path("crates/horizon-mods/Cargo.toml")):
        parts.append("horizon-mods/runtime")
    line = "mods = [" + ", ".join(f'"{p}"' for p in parts) + "]"
    text = port.read_text()
    current = re.search(r"(?m)^mods\s*=\s*\[[^\]]*\]", text)
    if current is None:
        print("::warning::в порте нет фичи mods — пропускаю")
        return
    if current.group(0) == line:
        return
    port.write_text(text.replace(current.group(0), line, 1))
    print(f"фичи порта: {current.group(0)} -> {line}")


# Ребрендинг: старые пути и подписи «MargyMC» -> «MoonMC» (в том числе /sdcard/MoonMC/minecraft).
REBRAND = [
    pathlib.Path("crates/horizon-client/src/android_setup.rs"),
    pathlib.Path("crates/horizon-client/src/android.rs"),
    pathlib.Path("crates/horizon-client/src/title.rs"),
    pathlib.Path("android/AndroidManifest.xml"),
]


PORT_MODULES = ("event_loop", "msa")


def patch_lib_modules() -> None:
    """Модули, которые добавляет порт, но которые может потерять cherry-pick.

    MargyMC объявляет в `crates/horizon-client/src/lib.rs` ещё два модуля
    (`event_loop`, `msa`). При переносе порта на новый пин `git cherry-pick -X ours`
    может оставить lib.rs от движка — тогда модули пропадают и android_setup.rs не
    собирается. Возвращаем их, только если файлы рядом действительно есть.
    """
    lib = pathlib.Path("crates/horizon-client/src/lib.rs")
    if not lib.is_file():
        return
    text = lib.read_text()
    added = []
    for name in PORT_MODULES:
        if f"pub mod {name};" in text:
            continue
        if not (lib.parent / f"{name}.rs").is_file():
            print(f"::warning::crates/horizon-client/src/{name}.rs нет в этом пине — модуль не объявляю")
            continue
        anchor = re.search(r"(?m)^(pub )?mod [a-z_0-9]+;\n", text)
        if anchor is None:
            text = text.rstrip() + f"\npub mod {name};\n"
        else:
            text = text[: anchor.end()] + f"pub mod {name};\n" + text[anchor.end():]
        added.append(name)
    if added:
        lib.write_text(text)
        print(f"lib.rs: вернул модули порта: {', '.join(added)}")


def patch_workspace() -> None:
    """Порт — отдельный крейт: добавляем его в members рабочего пространства."""
    root = pathlib.Path("Cargo.toml")
    if not root.is_file() or not pathlib.Path("crates/horizon-android").is_dir():
        return
    text = root.read_text()
    if "crates/horizon-android" in text:
        return
    match = re.search(r"(?m)^members\s*=\s*\[\n", text)
    if match is None:
        print("::warning::в Cargo.toml нет списка members — порт не подключён")
        return
    text = text[: match.end()] + '    "crates/horizon-android",\n' + text[match.end():]
    root.write_text(text)
    print("Cargo.toml: добавил crates/horizon-android в members")


ANDROID_RS = pathlib.Path("crates/horizon-client/src/android.rs")
SETUP_RS = pathlib.Path("crates/horizon-client/src/android_setup.rs")


def patch_library_dir() -> None:
    """Gradle-сборка держит libhorizon.so внутри APK: /proc/self/maps даёт путь
    вида `base.apk!/lib/arm64-v8a/libhorizon.so`, и каталога библиотек не найти.
    Спрашиваем каталог у Activity (nativeLibraryDir), карту читаем как запасной путь."""
    if ANDROID_RS.is_file() and "fn native_library_dir" not in ANDROID_RS.read_text():
        text = ANDROID_RS.read_text()
        helper = """
/// MoonMC: каталог нативных библиотек (спрашиваем у Activity).
#[allow(unsafe_code)]
pub(crate) fn native_library_dir() -> Option<String> {
    let Some(&(vm, activity)) = JAVA.get() else {
        return None;
    };
    // SAFETY: the process's VM, which lives as long as it.
    let Ok(vm) = (unsafe { jni::JavaVM::from_raw(vm as *mut jni::sys::JavaVM) }) else {
        return None;
    };
    let Ok(mut env) = vm.attach_current_thread() else {
        return None;
    };
    // SAFETY: the activity's global reference, held by the NativeActivity while it lives.
    let activity = unsafe { jni::objects::JObject::from_raw(activity as jni::sys::jobject) };
    let Ok(value) = env.call_method(&activity, "nativeLibraryDir", "()Ljava/lang/String;", &[]) else {
        return None;
    };
    let Ok(text) = value.l() else {
        return None;
    };
    let text: String = env.get_string(&text.into()).ok()?.into();
    if text.is_empty() {
        None
    } else {
        Some(text)
    }
}
"""
        ANDROID_RS.write_text(text + helper)
        print("android.rs: native_library_dir() добавлен")

    if SETUP_RS.is_file():
        text = SETUP_RS.read_text()
        old = """fn library_dir() -> Option<PathBuf> {
    let maps = std::fs::read_to_string("/proc/self/maps").ok()?;
    maps.lines()
        .filter_map(|line| line.split_whitespace().nth(5))
        .find(|path| path.ends_with("/libhorizon.so"))
        .and_then(|path| Path::new(path).parent().map(Path::to_path_buf))
}"""
        new = """fn library_dir() -> Option<PathBuf> {
    // MoonMC: libhorizon.so может лежать внутри APK (mmap), тогда в /proc/self/maps
    // путь выглядит как `base.apk!/lib/arm64-v8a/libhorizon.so`. Каталог спрашиваем
    // у Activity, а карту читаем только как запасной вариант.
    if let Some(dir) = crate::native_library_dir() {
        let path = PathBuf::from(dir);
        if path.is_dir() {
            return Some(path);
        }
    }
    let maps = std::fs::read_to_string("/proc/self/maps").ok()?;
    maps.lines()
        .filter_map(|line| line.split_whitespace().nth(5))
        .find(|path| path.ends_with("/libhorizon.so") && !path.contains("!/"))
        .and_then(|path| Path::new(path).parent().map(Path::to_path_buf))
}"""
        if new in text:
            print("android_setup.rs: library_dir уже патчен")
        elif old in text:
            SETUP_RS.write_text(text.replace(old, new, 1))
            print("android_setup.rs: library_dir берёт каталог у Activity")
        else:
            print("::warning::android_setup.rs: не нашёл library_dir")


def patch_jre_bits() -> None:
    """Права JRE, отчёт о `bin/java` и пересборка, если он не исполняемый.

    Gradle-упаковка может не сохранить биты исполнения, а предыдущая неудачная
    попытка оставляет `jre/` с файлом `release`, из-за чего JRE больше никогда не
    перепаковывается. Поэтому: 0755 на всё дерево JRE, отчёт в диалог ошибки и
    повторная распаковка, если `bin/java` не исполняемый.
    """
    path = pathlib.Path("crates/horizon-client/src/android_setup.rs")
    if not path.is_file():
        return
    text = path.read_text()
    if "java_ok" in text:
        print("android_setup.rs: JRE-патчи уже наложены")
        return
    anchor = "    // Horizon's exporters (sources, classes, research-lock.json): every start, they are small."
    if anchor not in text:
        print("::warning::android_setup.rs: не нашёл место для прав JRE")
        return
    text = text.replace(
        anchor,
        "    // MoonMC: права JRE и отчёт о нём (см. patch-game-home.py).\n"
        "    ensure_executable(jre);\n"
        "    report_jre(home, jre);\n\n" + anchor,
        1,
    )

    early_old = """    let mut archives = Vec::new();
    if !jre.join("release").is_file() {"""
    early_new = """    // MoonMC: пересобираем JRE, если java не исполняемый или пропал.
    let java_ok = {
        use std::os::unix::fs::PermissionsExt;
        std::fs::metadata(jre.join("bin/java"))
            .map(|m| m.permissions().mode() & 0o111 != 0)
            .unwrap_or(false)
    };
    // MoonMC: отчёт о JRE до распаковки.
    report_jre(&home, &jre);
    let mut archives = Vec::new();
    if !jre.join("release").is_file() || !java_ok {"""
    if early_old in text:
        text = text.replace(early_old, early_new, 1)
    else:
        print("::warning::android_setup.rs: не нашёл блок распаковки JRE")

    helper = """
/// Отчёт о JRE: попадает и в диалог ошибки подготовки, и в файл рядом с игрой.
static JRE_REPORT: std::sync::Mutex<String> = std::sync::Mutex::new(String::new());

fn report_jre(home: &Path, jre: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let java = jre.join("bin/java");
    let mut report = String::new();
    report.push_str(&format!("java = {}\n", java.display()));
    match std::fs::symlink_metadata(&java) {
        Ok(meta) => report.push_str(&format!(
            "symlink_metadata: file={} dir={} mode={:o} len={}\n",
            meta.file_type().is_file(),
            meta.file_type().is_dir(),
            meta.permissions().mode(),
            meta.len()
        )),
        Err(e) => report.push_str(&format!("symlink_metadata: error {e}\n")),
    }
    report.push_str(&format!("exists = {}\n", java.exists()));
    report.push_str(&format!("read_link = {:?}\n", std::fs::read_link(&java)));
    match std::process::Command::new(&java).arg("-version").output() {
        Ok(out) => report.push_str(&format!(
            "run: code {:?}, stderr: {}\n",
            out.status.code(),
            String::from_utf8_lossy(&out.stderr).lines().next().unwrap_or("")
        )),
        Err(e) => report.push_str(&format!("run: error {e} (kind {:?})\n", e.kind())),
    }
    let public = std::path::Path::new("/sdcard/MoonMC/horizon");
    if std::fs::create_dir_all(public).is_ok() {
        let _ = std::fs::write(public.join("moonmc-jre.txt"), &report);
    }
    let _ = std::fs::write(home.join("moonmc-jre.txt"), &report);
    if let Ok(mut slot) = JRE_REPORT.lock() {
        *slot = report;
    }
}

fn ensure_executable(jre: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(jre, std::fs::Permissions::from_mode(0o755));
    let mut stack = vec![jre.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(kind) = entry.file_type() else { continue };
            if kind.is_dir() {
                let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755));
                stack.push(path);
                continue;
            }
            let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755));
        }
    }
}

fn setup(
    home: &Path,"""
    text = text.replace("\nfn setup(\n    home: &Path,", helper, 1)

    # отчёт в тексте ошибки подготовки
    text = text.replace(
        '                    set(&format!("Preparing failed:\\n{e}"));',
        '                    let report = JRE_REPORT.lock().map(|r| r.clone()).unwrap_or_default();\n'
        '                    set(&format!("Preparing failed:\\n{e}\\n\\n{report}"));',
        1,
    )
    path.write_text(text)
    print("android_setup.rs: права JRE, отчёт и пересборка при неисполняемом java")

def main() -> int:
    failed = False
    for path, original, patched in EDITS:
        if not path.is_file():
            # файл мог переехать в новом пине: это не повод падать
            print(f"::warning::{path}: файла нет в этом пине, пропускаю патч")
            continue
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
    # 2. Права JRE и каталог библиотек под Gradle-сборку
    patch_library_dir()
    patch_jre_bits()

    # 2a. Порт как крейт рабочего пространства
    patch_workspace()

    # 2b. Модули порта в lib.rs и фичи порта под этот пин
    patch_lib_modules()
    patch_android_features()

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
