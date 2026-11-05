#!/usr/bin/env python3
"""MoonMC: проверки смены applicationId (`ci/patch-package.py`) — без телефона.

Что проверяем и почему:

1. Репозиторий: `game/app/build.gradle.kts` (namespace + applicationId),
   README и шаг workflow называют ровно тот пакет, который ставит патчер.
   Иначе APK соберётся с одним id, а документация/подпись скажут другое.
2. Kotlin-пакет `dev.moonmc.game` НЕ переименовывается: его JNI-символы
   (`Java_dev_moonmc_game_Native_*` из `ci/patch-touch.py`) и reflection
   (`Class.forName("dev.moonmc.game.Overlay")`) должны остаться прежними.
3. Патчер на временной копии `ci/port/port.tgz` (после `patch-branding.py`,
   как в CI): манифест, каталог и `package` Java-файлов, JNI-символы в
   `crates/horizon-client/src/android.rs`, `PKG` в вспомогательных скриптах.
   JNI-префикс выводится из имени пакета, а не берётся из константы теста.
4. Идемпотентность: второй прогон не меняет ни байта и завершается с кодом 0.
5. Дерево, пропатченное прошлым брендом (`dev.moonmc.moonmc`), валит патчер
   громко, а не собирается «наполовину».

Реальный `adb install` эта проверка НЕ заменяет: новая applicationId — это
новая private data dir и повторная распаковка JRE.

Запуск: python3 ci/test-package.py [--keep]
"""
import hashlib
import pathlib
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
TGZ = HERE / "port" / "port.tgz"
BRANDING = HERE / "patch-branding.py"
PACKAGE = HERE / "patch-package.py"
GRADLE = REPO / "game/app/build.gradle.kts"
README = REPO / "README.md"
WORKFLOW = REPO / ".github/workflows/apk.yml"
ACTIVITY_REL = pathlib.Path("android/java/cat/narezany/horizon/HorizonActivity.java")
ANDROID_RS_REL = pathlib.Path("crates/horizon-client/src/android.rs")
KOTLIN_PKG = "dev.moonmc.game"

FAILS: list[str] = []


def check(condition: bool, what: str, detail: str = "") -> None:
    if condition:
        print(f"  ok   {what}")
    else:
        print(f"  FAIL {what}{': ' + detail if detail else ''}")
        FAILS.append(what)


def package_constants() -> tuple[str, str]:
    """NEW и путь нового каталога — из самого патчера, без дублей в тесте."""
    text = PACKAGE.read_text()
    new = re.search(r'^NEW = "([^"]+)"', text, re.M)
    new_dir = re.search(r'^NEW_DIR = pathlib\.Path\("([^"]+)"\)', text, re.M)
    if not new or not new_dir:
        raise AssertionError("в patch-package.py нет NEW/NEW_DIR")
    return new.group(1), new_dir.group(1)


def jni(package: str) -> str:
    return "Java_" + package.replace(".", "_")


def run(script: pathlib.Path, cwd: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(script)],
        cwd=cwd, capture_output=True, text=True, check=False,
    )


def make_tree(root: pathlib.Path) -> pathlib.Path:
    port = root / "port"
    port.mkdir(parents=True)
    with tarfile.open(TGZ) as tar:
        tar.extractall(port, filter="data")
    # В CI `ci/` лежит в дереве рядом с android/ — здесь тем же путём.
    (port / "ci").symlink_to(HERE.resolve(), target_is_directory=True)
    return port


def digest(base: pathlib.Path) -> str:
    """Отпечаток дерева порта (симлинк ci не обходим: это наш репозиторий)."""
    sha = hashlib.sha256()
    for path in sorted(p for p in base.rglob("*") if not p.is_symlink()):
        if path.is_file():
            sha.update(str(path.relative_to(base)).encode())
            sha.update(path.read_bytes())
    return sha.hexdigest()


def test_repo(new: str) -> None:
    print("1. Репозиторий: Gradle, README, workflow — один и тот же пакет")
    gradle = GRADLE.read_text()
    check(re.search(rf'namespace = "{re.escape(new)}"', gradle) is not None,
          "build.gradle.kts: namespace = applicationId")
    check(re.search(rf'applicationId = "{re.escape(new)}"', gradle) is not None,
          f"applicationId {new}")
    check(f"package-{new}-" in README.read_text(), "README: бейдж пакета")
    check(new in WORKFLOW.read_text(), "workflow: имя шага с пакетом")
    stale = "dev.moonmc.moonmc"
    for path in (GRADLE, README, WORKFLOW):
        check(stale not in path.read_text(), f"{path.name}: без прошлого бренда")


def test_kotlin_untouched() -> None:
    print("2. Kotlin-пакет слоя не переименован (его JNI/reflection — как были)")
    kotlin = REPO / "game/app/src/main/kotlin/dev/moonmc/game"
    packages = set()
    for path in kotlin.glob("*.kt"):
        found = re.search(r"^package\s+(\S+)$", path.read_text(), re.M)
        packages.add(found.group(1) if found else f"<нет package: {path.name}>")
    check(packages == {KOTLIN_PKG}, f"package {KOTLIN_PKG}", str(sorted(packages)))
    touch = (HERE / "patch-touch.py").read_text()
    check(f"{jni(KOTLIN_PKG)}_Native_" in touch,
          "patch-touch.py: JNI слоя по-прежнему dev_moonmc_game")
    check(f"{jni('dev.haruxa.moonmc')}_" not in touch,
          "patch-touch.py: символов нового applicationId нет")
    branding = BRANDING.read_text()
    check(f'Class.forName("{KOTLIN_PKG}.Overlay")' in branding,
          "patch-branding.py: reflection на dev.moonmc.game.Overlay")


def test_patch(port: pathlib.Path, new: str) -> None:
    print("3. Патчи на копии port.tgz: манифест, java, JNI, helpers")
    before = digest(port)
    brand = run(BRANDING, port)
    check(brand.returncode == 0, "patch-branding.py прошёл", brand.stderr[-400:])
    patched = run(PACKAGE, port)
    check(patched.returncode == 0, "patch-package.py прошёл",
          (patched.stdout + patched.stderr)[-600:])
    print("     " + patched.stdout.strip().replace("\n", "\n     "))
    check(digest(port) != before, "дерево изменилось")

    manifest = (port / "android/AndroidManifest.xml").read_text()
    check(f'package="{new}"' in manifest, f"манифест: package={new}")
    check(f'android:name="{new}.HorizonActivity"' in manifest,
          "манифест: activity в новом пакете")

    java_dir = port / "android/java" / new.replace(".", "/")
    java_files = sorted(java_dir.glob("*.java"))
    names = sorted(p.name for p in java_files)
    check(len(java_files) >= 4, "java: HorizonActivity + экран загрузки + мост",
          str(names))
    check("HorizonActivity.java" in names, "java: HorizonActivity на месте")
    bad_package = [p.name for p in java_files
                   if f"package {new};" not in p.read_text()]
    check(not bad_package, f"java: package {new} во всех файлах", str(bad_package))
    check(not (port / ACTIVITY_REL.parent).exists(), "java: каталог старого пакета удалён")
    check(not (port / "android/java/dev/moonmc").exists(),
          "java: каталога прошлого бренда нет")

    rust = (port / ANDROID_RS_REL).read_text()
    old_jni, new_jni = jni("cat.narezany.horizon"), jni(new)
    found = sorted(set(re.findall(rf"{new_jni}_HorizonActivity_\w+", rust)))
    check(len(found) == 5, f"JNI: {len(found)} символов {new_jni}_HorizonActivity_*",
          str(found))
    check(old_jni not in rust, "JNI: старого префикса нет")

    for name in ("bench.sh", "flicker-test.sh", "push-resources.sh"):
        check(f"PKG={new}" in (port / "android" / name).read_text(),
              f"{name}: PKG={new}")

    leftovers = [str(p.relative_to(port)) for p in list((port / "android").rglob("*"))
                 + list((port / "crates").rglob("*.rs"))
                 if p.is_file() and p.suffix in (".java", ".xml", ".sh", ".rs")
                 and "cat.narezany.horizon" in p.read_text(errors="ignore")]
    check(not leftovers, "leftover gate: старый пакет не остался", str(leftovers))


def test_idempotent(port: pathlib.Path) -> None:
    print("4. Идемпотентность: второй прогон не меняет байт")
    before = digest(port)
    again = run(PACKAGE, port)
    check(again.returncode == 0, "patch-package.py повторно прошёл",
          (again.stdout + again.stderr)[-400:])
    check(digest(port) == before, "дерево не изменилось")


def test_stale(root: pathlib.Path) -> None:
    print("5. Дерево прошлого бренда валит патчер, а не собирается")
    port = make_tree(root / "stale")
    old_dir = port / ACTIVITY_REL.parent
    stale_dir = port / "android/java/dev/moonmc/moonmc"
    stale_dir.mkdir(parents=True, exist_ok=True)
    activity = stale_dir / "HorizonActivity.java"
    try:
        activity.write_text((old_dir / "HorizonActivity.java").read_text().replace(
            "cat.narezany.horizon", "dev.moonmc.moonmc"))
    except OSError as error:
        check(False, "дерево прошлого бренда: копия", str(error))
        return
    result = run(PACKAGE, port)
    check(result.returncode != 0, "код возврата не 0")
    check("dev.moonmc.moonmc" in (result.stdout + result.stderr),
          "сообщение называет прошлый пакет")


def main() -> int:
    keep = "--keep" in sys.argv
    new, new_dir = package_constants()
    print(f"пакет в патчере: {new} ({new_dir})")
    test_repo(new)
    test_kotlin_untouched()
    root = pathlib.Path(tempfile.mkdtemp(prefix="moonmc-pkg-", dir=pathlib.Path.home()))
    try:
        port = make_tree(root / "main")
        test_patch(port, new)
        test_idempotent(port)
        test_stale(root)
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
