#!/usr/bin/env python3
"""MoonMC: IME чата для пина, где `event_loop::take_typed` никто не разбирает.

Ставит `crates/horizon-client/src/game/moon_ime.rs` (из ci/moon_ime.rs), объявляет
`mod moon_ime;` рядом с `mod chat;` и вставляет вызов `self.moon_ime_frame(event_loop);`
в `about_to_wait` сразу после `self.touch_frame(event_loop);`.

Порядок в workflow: сначала patch-touch.py (он добавляет кадр тач-слоя), затем
этот патчер. Без кадра тач-слоя якорь не найден — патчер падает, а не угадывает.
Повторный прогон не меняет ни байта. Вызов без `event_loop` из прежней версии
(`self.moon_ime_frame();`) заменяется на актуальный, а не дублируется.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(".")
CLIENT = ROOT / "crates/horizon-client/src"
GAME = CLIENT / "game.rs"
MODULE = CLIENT / "game/moon_ime.rs"

CALL = "self.moon_ime_frame(event_loop);"
ABOUT = "    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {"

MOD_RE = re.compile(r"^[ \t]*(?:pub(?:\([^)]*\))?[ \t]+)?mod moon_ime;[ \t]*$", re.MULTILINE)
CHAT_MOD_RE = re.compile(
    r"^([ \t]*)((?:pub(?:\([^)]*\))?[ \t]+)?)mod chat;[ \t]*$", re.MULTILINE
)
TOUCH_RE = re.compile(r"^([ \t]*)self\.touch_frame\(event_loop\);[ \t]*$", re.MULTILINE)
CALL_RE = re.compile(r"^[ \t]*" + re.escape(CALL) + r"[ \t]*$", re.MULTILINE)
LEGACY = "self.moon_ime_frame();"
LEGACY_RE = re.compile(r"^([ \t]*)" + re.escape(LEGACY) + r"[ \t]*$", re.MULTILINE)
TOUCH = "self.touch_frame(event_loop);"

ERRORS: list[str] = []


def register_module(text: str) -> str:
    """`mod moon_ime;` рядом с `mod chat;`, ровно один раз."""
    found = MOD_RE.findall(text)
    if len(found) > 1:
        ERRORS.append(f"game.rs: `mod moon_ime;` объявлен {len(found)} раз")
        return text
    if found:
        return text
    anchors = list(CHAT_MOD_RE.finditer(text))
    if len(anchors) != 1:
        ERRORS.append("game.rs: не нашёл единственный `mod chat;` для `mod moon_ime;`")
        return text
    m = anchors[0]
    line = f"{m.group(1)}{m.group(2)}mod moon_ime;"
    return text[: m.end()] + "\n" + line + text[m.end() :]


def previous_line(text: str, pos: int) -> str:
    """Строка перед строкой, начинающейся с `pos`."""
    line_start = text.rfind("\n", 0, pos) + 1
    return text[:line_start].rstrip("\n").rsplit("\n", 1)[-1].strip()


def insert_call(text: str) -> str:
    """Вызов обработчика сразу после `touch_frame` в `about_to_wait`."""
    calls = list(CALL_RE.finditer(text))
    legacy = list(LEGACY_RE.finditer(text))
    total = len(calls) + len(legacy)
    if total > 1:
        ERRORS.append(f"game.rs: вызов moon_ime_frame встречается {total} раз")
        return text
    if calls:
        if previous_line(text, calls[0].start()) != TOUCH:
            ERRORS.append(f"game.rs: `{CALL}` стоит не сразу после `{TOUCH}`")
        return text
    if legacy:
        # Вызов прежнего патчера: меняем на вариант с event_loop на том же месте,
        # чтобы не получить два разбора очереди в одном кадре.
        if previous_line(text, legacy[0].start()) != TOUCH:
            ERRORS.append(
                f"game.rs: `{LEGACY}` стоит не сразу после `{TOUCH}` — миграция не угадывает"
            )
            return text
        indent = legacy[0].group(1)
        return text[: legacy[0].start()] + indent + CALL + text[legacy[0].end() :]
    touches = list(TOUCH_RE.finditer(text))
    if len(touches) != 1:
        ERRORS.append(
            f"game.rs: вызов `{TOUCH}` встречается {len(touches)} раз — сначала прогони patch-touch.py"
        )
        return text
    t = touches[0]
    # Это должно быть тело `about_to_wait`, а не любая другая функция.
    fn_start = text.rfind("\n    fn ", 0, t.start())
    fn_line_end = text.find("\n", fn_start + 1)
    fn_line = text[fn_start + 1 : fn_line_end]
    if fn_line != ABOUT:
        ERRORS.append(
            "game.rs: touch_frame стоит не в about_to_wait — "
            f"ближайшая функция: `{fn_line.strip()}`"
        )
        return text
    indent = t.group(1)
    return text[: t.end()] + "\n" + indent + CALL + text[t.end() :]


def main() -> None:
    source = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "ci/moon_ime.rs")
    if not source.is_file():
        sys.exit(f"patch-ime: нет шаблона {source}")
    body = source.read_text()
    if "fn moon_ime_frame" not in body:
        sys.exit(f"patch-ime: {source} не похож на модуль IME (нет fn moon_ime_frame)")
    if not GAME.is_file():
        sys.exit(f"patch-ime: нет {GAME} — запускать из корня дерева пина")

    original = GAME.read_text()
    text = insert_call(register_module(original))

    if ERRORS:
        # Дерево не трогаем: сначала пусть совпадут все якоря.
        sys.exit("patch-ime: движок не совпал с якорями — " + "; ".join(ERRORS))

    if not MODULE.is_file() or MODULE.read_text() != body:
        MODULE.write_text(body)
        print("game/moon_ime.rs: установлен")
    if text != original:
        GAME.write_text(text)
        if LEGACY_RE.search(original) and not LEGACY_RE.search(text):
            print("game.rs: moon_ime_frame(); -> moon_ime_frame(event_loop);")
        else:
            print("game.rs: mod moon_ime и вызов moon_ime_frame после touch_frame")
    print("patch-ime: IME чата подключён")


if __name__ == "__main__":
    main()
