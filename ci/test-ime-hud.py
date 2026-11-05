#!/usr/bin/env python3
"""MoonMC: проверки возврата HUD после закрытия IME — без телефона.

Что проверяем и почему:

1. Решение «прятать ли слой» принимается по ФАКТИЧЕСКОЙ видимости IME (инсеты
   окна), а не по просьбам. Системная кнопка «Назад» прячет клавиатуру в оконном
   менеджере: `HorizonActivity.setKeyboard(false)` при этом не зовётся, значит
   `OverlayBridge.gameKeyboard(false)` молчит, `imeShown` остаётся true, а флаг
   движка (`moon_input::KEYBOARD`, он же `Native.keyboardShown()`) становится
   true и сам никогда не сбрасывается — он меняется только по `Event::Keyboard`,
   то есть по кнопке IME слоя.
2. Модель ниже повторяет `Overlay.applyKeyboard`/`gameKeyboard`/`toggleIme`
   один в один: константы (шаг опроса, число пропусков, грейсы) читаются из
   самой `Overlay.kt`, поэтому тест падает, если код и модель разойдутся.
3. Проверки исходника: в решении о скрытии нет `Native.keyboardShown()`,
   `forgetIme()` сбрасывает все поля, а явное закрытие (отправка) сначала
   возвращает слой и только потом верит гаснущим инсетам.

Реальный IME (Gboard/клавиатура Samsung) и системная «Назад» на телефоне этой
проверкой НЕ заменяются: она проверяет только переходы состояния.

Запуск: python3 ci/test-ime-hud.py
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
OVERLAY = REPO / "game/app/src/main/kotlin/dev/moonmc/game/Overlay.kt"

FAILS: list[str] = []


def check(condition: bool, what: str, detail: str = "") -> None:
    if condition:
        print(f"  ok   {what}")
    else:
        print(f"  FAIL {what}{': ' + detail if detail else ''}")
        FAILS.append(what)


def body_of(src: str, signature: str) -> str:
    """Тело функции по сигнатуре: баланс фигурных скобок, без вложенных сюрпризов."""
    start = src.index(signature)
    brace = src.index("{", start + len(signature))
    depth = 0
    for index in range(brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[brace + 1:index]
    raise ValueError(f"нет закрывающей скобки у {signature}")


def int_const(src: str, name: str) -> int:
    match = re.search(rf"const val {name} = ([\d_]+)L?", src)
    if not match:
        raise ValueError(f"нет константы {name}")
    return int(match.group(1).replace("_", ""))


class Hud:
    """Точная модель полей и переходов Overlay для клавиатуры."""

    def __init__(self, gone_polls: int, open_grace: int, close_grace: int, screen: int):
        self.gone_polls = gone_polls
        self.open_grace = open_grace
        self.close_grace = close_grace
        self.screen = screen
        self.hidden = False
        # Поля Overlay.
        self.requested = False   # imeShown
        self.visible = False     # imeVisible
        self.gone = 0            # imeGone
        self.opening_until = 0   # imeOpeningUntil
        self.closing_until = 0   # imeClosingUntil

    def forget(self) -> None:
        self.requested = False
        self.visible = False
        self.gone = 0
        self.opening_until = 0
        self.closing_until = 0

    def show(self, now: int) -> bool:
        """gameKeyboard(true) / toggleIme(открыть)."""
        self.requested = True
        self.gone = 0
        self.closing_until = 0
        self.opening_until = now + self.open_grace
        self.hidden = self.visible or self.requested
        return self.hidden

    def close(self, now: int) -> bool:
        """gameKeyboard(false): отправка, Esc, движок сам закрыл поле."""
        self.forget()
        self.closing_until = now + self.close_grace
        self.hidden = False
        return self.hidden

    def poll(self, ime: int, own_input: bool, now: int) -> bool:
        """applyKeyboard(activity, ownInput) раз в шаг опроса."""
        if own_input:
            self.forget()
            self.hidden = False
            return self.hidden
        visible = ime > self.screen // 8 and now >= self.closing_until
        if visible:
            self.visible = True
            self.gone = 0
        elif self.visible or (self.requested and now >= self.opening_until):
            self.gone += 1
            if self.gone >= self.gone_polls:
                self.forget()
        self.hidden = self.visible or self.requested
        return self.hidden


def main() -> int:
    src = OVERLAY.read_text()
    delay = re.search(r"kotlinx\.coroutines\.delay\((\d+)\)", src)
    if not delay:
        print("FAIL нет шага опроса клавиатуры (kotlinx.coroutines.delay) в Overlay.kt")
        return 1
    poll = int(delay.group(1))
    gone_polls = int_const(src, "IME_GONE_POLLS")
    open_grace = int_const(src, "IME_OPEN_GRACE_MS")
    close_grace = int_const(src, "IME_CLOSE_GRACE_MS")
    screen, ime = 2400, 900  # порог screen/8 = 300, клавиатура заведомо выше

    print("== исходник: решение по фактической видимости, без флага движка")
    apply_body = body_of(src, "private fun applyKeyboard(")
    check("keyboardShown" not in apply_body, "applyKeyboard не спрашивает Native.keyboardShown()")
    check("Native.keyboardShown() }" not in src, "в Overlay.kt нет вызова Native.keyboardShown()")
    check("imeGone++" in apply_body, "applyKeyboard считает пропуски инсетов")
    check("imeGone >= IME_GONE_POLLS" in apply_body, "порог закрытия — IME_GONE_POLLS")
    check("imeVisible || imeShown" in apply_body, "скрытие только по видимости/просьбе")
    forget_body = body_of(src, "private fun forgetIme(")
    for field in ("imeShown = false", "imeVisible = false", "imeGone = 0",
                  "imeOpeningUntil = 0L", "imeClosingUntil = 0L"):
        check(field in forget_body, f"forgetIme сбрасывает «{field}»")
    close_body = body_of(src, "fun gameKeyboard(")
    close_part = close_body[close_body.index("if (show)") :]
    check("imeGone = 0" in close_part and "imeOpeningUntil" in close_part,
          "просьба открыть заново снимает счётчик и ждёт инсеты")
    check("forgetIme()" in close_part and "setLayerHidden(false)" in close_part,
          "закрытие поля сразу возвращает слой")
    tail = close_part[close_part.index("return"):]
    check(tail.index("forgetIme()") < tail.index("imeClosingUntil = nowMs()"),
          "явное закрытие сильнее гаснущих инсетов")
    toggle_body = body_of(src, "private fun toggleIme(")
    check("imeShown = !imeVisible" in toggle_body, "кнопка IME смотрит на фактическую видимость")
    loop = body_of(src, "LaunchedEffect(Unit)")
    check("applyKeyboard(activity, openedNow || editorOpen)" in loop,
          "опрос зовёт applyKeyboard с ownInput панели/редактора")

    print(f"== модель: шаг {poll} мс, пропусков {gone_polls}, "
          f"открытие {open_grace} мс, закрытие {close_grace} мс")
    check(poll == 250, "шаг опроса клавиатуры — 250 мс", str(poll))

    # 1. open → (ранние нулевые инсеты) → visible → Back → reopen.
    hud = Hud(gone_polls, open_grace, close_grace, screen)
    now = 0
    hud.show(now)
    check(hud.hidden, "после просьбы открыть слой скрыт сразу")
    now += poll
    check(hud.poll(0, False, now), "ранние нулевые инсеты — ещё открытие")
    now += poll
    check(hud.poll(0, False, now), "второй нулевой опрос внутри грейса — не закрытие")
    now += poll
    check(hud.poll(ime, False, now), "клавиатура видна — слой скрыт")
    now += poll
    check(hud.poll(ime, False, now), "клавиатура всё ещё видна")
    # Системная «Назад»: инсеты пропали, движок setKeyboard(false) не звал.
    now += poll
    check(hud.poll(0, False, now), "первый пропуск — гистерезис, слой ещё скрыт")
    now += poll
    check(not hud.poll(0, False, now), "после Back слой возвращён")
    check(not hud.requested and not hud.visible, "просьба и видимость сброшены (флаг движка не залип)")
    now += poll
    check(not hud.poll(0, False, now), "слой остаётся видимым")
    # Повторное открытие: ложного возврата при открытии быть не должно.
    now += poll
    hud.show(now)
    check(hud.hidden, "повторная просьба открыть снова прячет слой")
    now += poll
    check(hud.poll(0, False, now), "нулевые инсеты при повторном открытии — не возврат HUD")
    now += poll
    check(hud.poll(ime, False, now), "повторно открытая клавиатура — слой скрыт")
    now += poll
    check(hud.poll(0, False, now), "ещё один Back: первый пропуск")
    now += poll
    check(not hud.poll(0, False, now), "ещё один Back: слой возвращён")

    # 2. Инсетов не было вовсе (флаг движка остался true): возврат по грейсу.
    hud = Hud(gone_polls, open_grace, close_grace, screen)
    hud.show(0)
    hidden_at_grace = None
    hidden = True
    for now in range(poll, open_grace + poll * (gone_polls + 2), poll):
        hidden = hud.poll(0, False, now)
        if now < open_grace and hidden is False:
            check(False, "внутри грейса слой не вернулся", f"t={now}")
        if hidden_at_grace is None and now >= open_grace:
            hidden_at_grace = hidden
    check(hidden_at_grace is True, "на границе грейса слой ещё скрыт")
    check(hidden is False, "без инсетов HUD возвращается сам (устаревший флаг не залипает)")
    check(not hud.requested, "устаревшая просьба сброшена")

    # 3. Отправка: явное закрытие возвращает слой, гаснущие инсеты его не прячут.
    hud = Hud(gone_polls, open_grace, close_grace, screen)
    hud.show(0)
    hud.poll(ime, False, poll)
    check(hud.hidden, "перед отправкой слой скрыт клавиатурой")
    check(not hud.close(poll * 2), "отправка сразу возвращает слой")
    check(not hud.poll(ime, False, poll * 3), "гаснущие инсеты в пределах грейса не прячут слой")
    check(not hud.poll(ime, False, poll * 4), "и на следующем опросе тоже")
    check(hud.poll(ime, False, poll * 6), "после грейса реально видимая клавиатура снова прячет")
    hud.poll(0, False, poll * 7)
    check(not hud.poll(0, False, poll * 8), "и её закрытие снова возвращает слой")

    # 4. ownInput: своя клавиатура панели профилей/редактора слой не прячет.
    hud = Hud(gone_polls, open_grace, close_grace, screen)
    check(not hud.poll(ime, True, poll), "своя клавиатура панели не прячет слой")
    check(not hud.requested and not hud.visible, "своя клавиатура не оставляет чужого состояния")
    check(not hud.poll(0, False, poll * 2), "панель закрыта — слой на месте")
    hud.show(poll * 3)
    check(hud.hidden, "после панели обычная просьба открыть снова прячет слой")

    print()
    if FAILS:
        print(f"ПРОВАЛЫ ({len(FAILS)}): " + "; ".join(FAILS))
        return 1
    print("все проверки прошли (реальный IME на телефоне этим не заменяется)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
