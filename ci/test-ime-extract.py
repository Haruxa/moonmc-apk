#!/usr/bin/env python3
"""MoonMC: проверки IME для extract/fullscreen-поля клавиатуры — без телефона.

Что проверяем и почему:

1. Семантика AOSP `TextView.setExtractedText` (frameworks/base
   core/java/android/widget/TextView.java). При `partialStartOffset >= 0` снимок
   считается ЧАСТИЧНОЙ заменой диапазона `[partialStartOffset, partialEndOffset)`
   и вставляется внутрь буфера extract-редактора; при `-1` заменяет текст целиком
   (`content.replace(0, len, text)`). У свежего `ExtractedText` оба поля = 0,
   поэтому срез со своим `extracted()` давал «как делакак дел…».
2. Сценарий composing → commit → backspace на модели поля: extract-редактор
   (он текст сам не набирает, см. `InputMethodService.onUpdateExtractedText` →
   `ExtractEditText.setExtractedText`) совпадает с буфером поля на каждом шаге,
   не пуст во время набора и не дублирует текст. Модель берёт partial-поля из
   реального пропатченного `HorizonActivity.java`, а не из константы теста.
3. `ci/patch-branding.py` на временной копии `ci/port/port.tgz`: якоря на месте,
   второй прогон не меняет ни байта, а дерево с прошлым (v37) `report()`
   мигрирует на исправленный.

Реальный IME (Gboard/клавиатура Samsung) на телефоне эта проверка НЕ заменяет:
только она покажет, что fullscreen-поле действительно рисует текст.

Запуск: python3 ci/test-ime-extract.py [--keep]
"""
import contextlib
import hashlib
import importlib.util
import io
import os
import pathlib
import re
import shutil
import sys
import tarfile
import tempfile
from typing import Any

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
TGZ = HERE / "port" / "port.tgz"
PATCHER = HERE / "patch-branding.py"
ACTIVITY_REL = pathlib.Path("android/java/cat/narezany/horizon/HorizonActivity.java")

FAILS: list[str] = []


def check(condition: bool, what: str, detail: str = "") -> None:
    if condition:
        print(f"  ok   {what}")
    else:
        print(f"  FAIL {what}{': ' + detail if detail else ''}")
        FAILS.append(what)


# --- AOSP: TextView.setExtractedText ------------------------------------------
def set_extracted_text(local: str, snapshot: dict) -> str:
    """Точная модель AOSP TextView.setExtractedText для Editable поля клавиатуры."""
    text = snapshot["text"]
    start, end = 0, len(local)
    if snapshot["partialStartOffset"] >= 0:
        start = min(snapshot["partialStartOffset"], len(local))
        end = min(snapshot["partialEndOffset"], len(local))
        if start > end:
            start, end = end, start
    if local[start:end] == text:
        return local
    return local[:start] + text + local[end:]


def snapshot(text: str, partial_start: int, partial_end: int) -> dict:
    return {
        "text": text,
        "startOffset": 0,
        "partialStartOffset": partial_start,
        "partialEndOffset": partial_end,
        "selectionStart": len(text),
        "selectionEnd": len(text),
    }


def test_aosp_semantics() -> None:
    print("1. AOSP TextView.setExtractedText: partial-поля решают судьбу снимка")

    # Как в срезе: partialStartOffset/partialEndOffset остались 0.
    old = ""
    for typed in ("к", "ка", "как"):
        old = set_extracted_text(old, snapshot(typed, 0, 0))
    check(old == "каккак", "снимок с partial 0/0 склеивает текст (причина дублей)", repr(old))

    # Как после патча: это весь текст, а не частичная замена.
    new = ""
    for typed in ("к", "ка", "как"):
        new = set_extracted_text(new, snapshot(typed, -1, -1))
    check(new == "как", "снимок с partial -1/-1 заменяет текст целиком", repr(new))

    # Повтор того же снимка ничего не добавляет — идемпотентность.
    check(
        set_extracted_text(new, snapshot("как", -1, -1)) == "как",
        "повторный снимок не меняет текст",
    )


# --- модель поля и клавиатуры -------------------------------------------------
class ExtractView:
    """extract/fullscreen-редактор клавиатуры: текст берёт только из снимков."""

    def __init__(self) -> None:
        self.buffer = ""

    def on_update_extracted_text(self, snap: dict) -> None:
        self.buffer = set_extracted_text(self.buffer, snap)


class Field:
    """Модель HorizonActivity.Connection после патча (Editable + sync() + report())."""

    def __init__(self, partial: tuple[int, int]) -> None:
        self.partial = partial
        self.editable = ""
        self.composing: tuple[int, int] | None = None
        self.sent = ""
        self.monitor: int | None = None
        self.last_extracted: str | None = None
        self.last_selection = (-1, -1, -1, -1)
        self.events: list[tuple[str, str]] = []
        self.extract = ExtractView()
        self.snapshots = 0
        self.selections = 0

    # report() из патча
    def report(self) -> None:
        now = self.editable
        caret = len(now)
        comp = self.composing if self.composing is not None else (-1, -1)
        text_changed = now != self.last_extracted
        signature = (caret, caret, comp[0], comp[1])
        if text_changed or signature != self.last_selection:
            self.last_selection = signature
            self.selections += 1
        if text_changed and self.monitor is not None:
            self.last_extracted = now
            self.snapshots += 1
            self.extract.on_update_extracted_text(
                snapshot(now, self.partial[0], self.partial[1])
            )

    # sync() из среза: backspace до общего начала, затем хвост как nativeText
    def sync(self) -> None:
        now = self.editable
        common = 0
        limit = min(len(now), len(self.sent))
        while common < limit and now[common] == self.sent[common]:
            common += 1
        for _ in range(len(self.sent) - common):
            self.events.append(("key", "DEL"))
        if common < len(now):
            self.events.append(("text", now[common:]))
        self.sent = now
        self.report()

    # BaseInputConnection: setComposingText / commitText / backspace
    def compose(self, word: str) -> None:
        base = self.editable[: self.composing[0]] if self.composing else self.editable
        self.editable = base + word
        self.composing = (len(base), len(self.editable))
        self.sync()

    def commit(self, word: str) -> None:
        base = self.editable[: self.composing[0]] if self.composing else self.editable
        self.editable = base + word
        self.composing = None
        self.sync()

    def backspace(self, count: int = 1) -> None:
        if not self.editable:
            self.events.append(("key", "DEL"))
            return
        self.editable = self.editable[: max(0, len(self.editable) - count)]
        self.composing = None
        self.sync()

    # что игра собрала из nativeText/nativeKey
    def game_text(self) -> str:
        out = ""
        for kind, value in self.events:
            out = out[:-1] if kind == "key" else out + value
        return out


def test_composing_commit_backspace(partial: tuple[int, int]) -> None:
    print("2. Сценарий composing → commit → backspace → отправка")

    field = Field(partial)
    field.monitor = 1  # клавиатура уже попросила getExtractedText(MONITOR)
    steps = [
        ("набор «к»", lambda f: f.compose("к")),
        ("набор «ка»", lambda f: f.compose("ка")),
        ("набор «как»", lambda f: f.compose("как")),
        ("commit «как»", lambda f: f.commit("как")),
        ("набор « дела»", lambda f: f.compose(" дела")),
        ("commit « дела»", lambda f: f.commit(" дела")),
        ("backspace", lambda f: f.backspace()),
        ("backspace", lambda f: f.backspace()),
        ("backspace", lambda f: f.backspace()),
        ("ещё backspace", lambda f: f.backspace()),
    ]
    for name, action in steps:
        action(field)
        check(
            field.extract.buffer == field.editable,
            f"{name}: extract-редактор показывает текст поля",
            f"{field.extract.buffer!r} != {field.editable!r}",
        )
        if field.editable:
            check(field.extract.buffer != "", f"{name}: поле не пустое во время набора")

    check(
        field.game_text() == field.editable,
        "игра получила тот же текст (один путь nativeText/backspace)",
        f"{field.game_text()!r} != {field.editable!r}",
    )
    check(
        "каккак" not in field.extract.buffer,
        "в extract-редакторе нет склейки текста с самим собой",
        repr(field.extract.buffer),
    )

    # Одинаковое состояние не переотправляем, а без monitor снимков нет вовсе.
    before = field.snapshots
    field.report()
    field.report()
    check(field.snapshots == before, "report() без изменений не шлёт снимок повторно")

    silent = Field(partial)
    silent.compose("тест")
    check(silent.snapshots == 0, "без monitor снимок не уходит (некому)")
    silent.monitor = 2
    silent.sync()
    check(silent.snapshots == 1, "как только extract-режим включился — снимок ушёл")
    check(silent.extract.buffer == "тест", "снимок при позднем monitor содержит текст")


# --- патчер на временной копии port.tgz ---------------------------------------
def load_patcher() -> Any:
    """Свежий импорт патчера: ROOT берётся из текущего каталога при импорте."""
    spec = importlib.util.spec_from_file_location("patch_branding_under_test", PATCHER)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"не загрузить патчер {PATCHER}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_patcher(port: pathlib.Path) -> tuple[Any, str]:
    cwd = os.getcwd()
    os.chdir(port)
    out = io.StringIO()
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            module = load_patcher()
            module.main()
    finally:
        os.chdir(cwd)
    return module, out.getvalue()


def make_tree() -> pathlib.Path:
    root = pathlib.Path(tempfile.mkdtemp(prefix="moonmc-ime-", dir=pathlib.Path.home()))
    port = root / "port"
    port.mkdir()
    with tarfile.open(TGZ) as tar:
        tar.extractall(port, filter="data")
    # Патчер ищет ci/branding и ci/splash рядом с android/ (в CI ci копируется в дерево).
    (port / "ci").symlink_to(HERE.resolve(), target_is_directory=True)
    return root


def digest(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_partial(java: str) -> tuple[int, int]:
    """Какие partial-поля реально ставит пропатченный extracted()."""
    start = re.search(r"text\.partialStartOffset\s*=\s*(-?\d+);", java)
    end = re.search(r"text\.partialEndOffset\s*=\s*(-?\d+);", java)
    if not start or not end:
        raise AssertionError("в extracted() нет partialStartOffset/partialEndOffset")
    return int(start.group(1)), int(end.group(1))


def test_patch(temp_root: pathlib.Path) -> str:
    print("3. Патчер на временной копии port.tgz (якоря, идемпотентность, миграция)")
    port = temp_root / "port"
    activity = port / ACTIVITY_REL
    original = activity.read_text()

    module, out = run_patcher(port)
    once = activity.read_text()
    print("  патч: " + out.strip().replace("\n", "\n        "))

    check("partialStartOffset" in once, "патч ставит partialStartOffset в снимок")
    check(parse_partial(once) == (-1, -1), "снимок помечен как весь текст (-1/-1)")
    check(
        once.count("text.partialStartOffset = -1;") == 1
        and once.count("text.partialEndOffset = -1;") == 1,
        "partial-поля выставлены ровно один раз",
    )
    check("lastExtracted" in once, "report() помнит отправленный снимок")
    check(
        once.count("imm.updateExtractedText(view, monitor, extracted());") == 1,
        "updateExtractedText шлётся из одного места (report)",
    )
    check(
        "if (textChanged && monitor >= 0)" in once,
        "снимок уходит только на изменение текста и при monitor",
    )
    check(
        once.count("nativeText(") == original.count("nativeText("),
        "новых путей nativeText не появилось",
        f"{once.count('nativeText(')} != {original.count('nativeText(')}",
    )
    check(
        "sync();\n                    nativeKey(KeyEvent.KEYCODE_ENTER);" in once,
        "отправка по-прежнему sync() → Enter",
    )
    check("deleteSurroundingTextInCodePoints" in once, "backspace через кодпоинты на месте")
    check(
        "hideLater();" in once and "performEditorAction" in once,
        "отложенное закрытие IME не потеряно",
    )
    check(
        "MoonMC" in once and "Log.w(\"MoonIME\", \"hide" in once,
        "логи остались только про ошибки IME",
    )
    report_body = once.split("private void report()")[1].split("private ExtractedText")[0]
    check("Log." not in report_body, "в report() нет логирования текста")

    _, out2 = run_patcher(port)
    check(activity.read_text() == once, "повторный прогон не меняет ни байта")
    check("уже пропатчен" in out2, "повторный прогон распознаёт пропатченное дерево")
    _, out3 = run_patcher(port)
    check(activity.read_text() == once, "третий прогон тоже без изменений")

    # Миграция: дерево с прошлым (v37) report() — без снимка вовсе.
    migrated = port.with_name(port.name + "-migrate")
    shutil.copytree(port, migrated, symlinks=True, ignore=shutil.ignore_patterns("ci"))
    (migrated / "ci").symlink_to(HERE.resolve(), target_is_directory=True)
    migrated_activity = migrated / ACTIVITY_REL
    # Как выглядело дерево после v37: report() без снимка и extracted() из среза.
    old_tree = once.replace(module.NEW_REPORT_BODY, module.PREV_REPORT_BODY, 1)
    old_tree = old_tree.replace(module.NEW_EXTRACTED, module.OLD_EXTRACTED, 1)
    check(
        old_tree != once and "partialStartOffset" not in old_tree,
        "тестовое дерево получило report() и extracted() версии v37",
    )
    migrated_activity.write_text(old_tree)
    _, out4 = run_patcher(migrated)
    fixed = migrated_activity.read_text()
    check("lastExtracted" in fixed, "дерево v37 мигрировало на снимок с -1/-1")
    check(fixed == once, "после миграции дерево совпадает со свежим патчем")

    return once


def test_anchor_guard(temp_root: pathlib.Path) -> None:
    print("4. Якоря: патчер падает, а не угадывает")
    broken_root = pathlib.Path(tempfile.mkdtemp(prefix="moonmc-ime-broken-", dir=temp_root))
    port = broken_root / "port"
    port.mkdir()
    with tarfile.open(TGZ) as tar:
        tar.extractall(port, filter="data")
    (port / "ci").symlink_to(HERE.resolve(), target_is_directory=True)
    activity = port / ACTIVITY_REL
    activity.write_text(activity.read_text().replace("private void report() {", "private void reportX() {"))
    failed = False
    try:
        run_patcher(port)
    except SystemExit as error:
        failed = error.code != 0
    check(failed, "отсутствующий якорь валит патч (SystemExit)")


def main() -> int:
    keep = "--keep" in sys.argv
    if not TGZ.is_file():
        sys.exit(f"нет {TGZ}")
    test_aosp_semantics()

    temp_root = make_tree()
    try:
        patched = test_patch(temp_root)
        partial = parse_partial(patched)
        check(partial == (-1, -1), "модель сценария взяла partial-поля из патча", repr(partial))
        test_composing_commit_backspace(partial)
        test_anchor_guard(temp_root)
    finally:
        if not keep:
            shutil.rmtree(temp_root, ignore_errors=True)
        else:
            print(f"\nвременное дерево: {temp_root}")

    print()
    if FAILS:
        print(f"ПРОВАЛЕНО: {len(FAILS)} — " + "; ".join(FAILS))
        print("Реальную клавиатуру на телефоне это не заменяет: нужен ручной прогон.")
        return 1
    print("Все проверки прошли. Реальную клавиатуру на телефоне нужно проверить руками.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
