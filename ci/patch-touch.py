#!/usr/bin/env python3
"""Touch layout profiles for MargyMC: geometry, labels and extra buttons from JSON.

Adds `crates/horizon-client/src/game/touch_layout.rs` (copied from ci/touch_layout.rs),
registers it as a module and teaches `game/touch.rs` to read a profile:

* `Key::place`   — the profile's centre/radius, else the stock place;
* `Key::label`   — the profile's label, else the stock one;
* `Key::shown`   — the profile's modes, else the stock rule;
* extra controls — drawn and dispatched (`Action::Key/Hold/Macro/Switch`).

Everything is anchored on exact source text and fails loudly if the pin moved.
"""
import pathlib
import sys

ROOT = pathlib.Path("src")
CLIENT = ROOT / "crates/horizon-client/src"
TOUCH = CLIENT / "game/touch.rs"
GAME = CLIENT / "game.rs"
MODULE = CLIENT / "game/touch_layout.rs"


def replace(text: str, old: str, new: str, what: str) -> str:
    if old not in text:
        sys.exit(f"patch-touch: не найден якорь ({what})")
    if text.count(old) > 1:
        sys.exit(f"patch-touch: якорь не уникален ({what})")
    return text.replace(old, new, 1)


def main() -> None:
    source = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "ci/touch_layout.rs")
    MODULE.write_text(source.read_text())

    # 1. Регистрация модуля рядом с `mod touch;`.
    mod = GAME.read_text()
    anchor = next(
        (line for line in mod.splitlines() if line.strip() in ("mod touch;", "pub mod touch;", "pub(super) mod touch;")),
        None,
    )
    if anchor is None:
        sys.exit("patch-touch: в game.rs нет `mod touch;`")
    if "mod touch_layout;" not in mod:
        indent = anchor[: len(anchor) - len(anchor.lstrip())]
        visibility = anchor.strip().split("mod ")[0]
        mod = mod.replace(anchor, f"{anchor}\n{indent}{visibility}mod touch_layout;", 1)
        GAME.write_text(mod)

    text = TOUCH.read_text()

    # 2. Импорт модуля.
    text = replace(
        text,
        "use super::{Game, Screen};",
        "use super::touch_layout::{self, Action};\nuse super::{Game, Screen};",
        "use super",
    )

    # 3. Роль «дополнительный контрол профиля».
    text = replace(
        text,
        "    Key(Key),\n    Hotbar,\n}",
        "    Key(Key),\n    /// An extra control of the touch profile, by its index.\n    Extra(usize),\n    Hotbar,\n}",
        "enum Role",
    )

    # 4. Идентификаторы кнопок и место из профиля.
    text = replace(
        text,
        "    /// Centre and radius in GUI units.\n    fn place(self, w: f32, h: f32) -> (f32, f32, f32) {\n        match self {",
        """    /// The button's id in a touch profile, and in the stock layout.
    fn name(self) -> &'static str {
        match self {
            Key::Jump => "jump",
            Key::Sneak => "sneak",
            Key::Sprint => "sprint",
            Key::Inventory => "inventory",
            Key::Pause => "pause",
            Key::View => "view",
            Key::Mode => "mode",
            Key::Debug => "debug",
            Key::Chat => "chat",
            Key::Lock => "lock",
            Key::Attack => "attack",
        }
    }
    /// Centre and radius in GUI units: the profile's, else the stock place.
    fn place(self, w: f32, h: f32) -> (f32, f32, f32) {
        if let Some(at) = touch_layout::geometry(self.name(), w, h) {
            return at;
        }
        match self {""",
        "fn place",
    )

    # 5. Подпись из профиля.
    text = replace(
        text,
        "    fn label(self, mode: Mode) -> &'static str {\n        match self {",
        """    fn label(self, mode: Mode) -> String {
        if let Some(text) = touch_layout::label(self.name()) {
            return text;
        }
        self.label_stock(mode).to_string()
    }
    /// The button's own label, when the profile names none.
    fn label_stock(self, mode: Mode) -> &'static str {
        match self {""",
        "fn label",
    )

    # 6. Видимость по режиму из профиля.
    text = replace(
        text,
        "    fn shown(self, mode: Mode) -> bool {\n        self != Key::Attack || mode == Mode::Aim\n    }",
        """    fn shown(self, mode: Mode) -> bool {
        if let Some(shown) = touch_layout::visible(self.name(), mode == Mode::Tap) {
            return shown;
        }
        self != Key::Attack || mode == Mode::Aim
    }""",
        "fn shown",
    )

    # 7. Хит-тест дополнительных контролов — раньше стоковых кнопок.
    text = replace(
        text,
        "    fn world_role(&self, at: (f32, f32), batch: &HudBatch) -> Role {\n        let (w, h) = (batch.width, batch.height);",
        """    fn world_role(&self, at: (f32, f32), batch: &HudBatch) -> Role {
        let (w, h) = (batch.width, batch.height);
        touch_layout::ensure_loaded();
        for (i, x, y, r) in touch_layout::extras(w, h, self.touch.mode == Mode::Tap) {
            if distance(at, (x, y)) <= r + 6.0 {
                return Role::Extra(i);
            }
        }""",
        "world_role",
    )

    # 8. Подпись теперь строка.
    text = replace(
        text,
        "            let label = key.label(mode);\n            let tw = HudBatch::text_width(atlas, label);\n            batch.text(atlas, label, x - tw / 2.0, y - 4.0, 0xE0FF_FFFF, true);",
        "            let label = key.label(mode);\n            let tw = HudBatch::text_width(atlas, &label);\n            batch.text(atlas, &label, x - tw / 2.0, y - 4.0, 0xE0FF_FFFF, true);",
        "подпись кнопки",
    )

    # 9. Отрисовка дополнительных контролов.
    text = replace(
        text,
        "        // Shift lock's aim: a ring at the centre.",
        """        for (i, x, y, r) in touch_layout::extras(w, h, mode == Mode::Tap) {
            disc(batch, atlas, (x, y), r, 0x40FF_FFFF);
            let label = touch_layout::text(i);
            if !label.is_empty() {
                let tw = HudBatch::text_width(atlas, &label);
                batch.text(atlas, &label, x - tw / 2.0, y - 4.0, 0xE0FF_FFFF, true);
            }
        }
        // Shift lock's aim: a ring at the centre.""",
        "отрисовка контролов",
    )

    # 10. Нажатие на дополнительный контрол.
    text = replace(
        text,
        "                    Role::Hotbar => self.touch_hotbar(at.0, &batch),",
        "                    Role::Extra(i) => self.touch_extra(i),\n                    Role::Hotbar => self.touch_hotbar(at.0, &batch),",
        "диспетчер Role::Extra",
    )

    # 11. Действия дополнительных контролов.
    text = replace(
        text,
        "    /// The pad's menu checks, plus the prompts over the world.\n    fn touch_in_menu(&self) -> bool {",
        """    /// An extra control of the touch profile: a key, a hold, a macro or a profile switch.
    fn touch_extra(&mut self, index: usize) {
        let Some(action) = touch_layout::action(index) else {
            return;
        };
        match action {
            Action::Key(name) => {
                if let Some(code) = touch_layout::keycode(&name) {
                    self.touch_key(code);
                }
            }
            Action::Hold(name) => {
                if let Some(code) = touch_layout::keycode(&name) {
                    self.touch_toggle(code);
                }
            }
            Action::Macro(keys) => {
                for name in keys {
                    if let Some(code) = touch_layout::keycode(&name) {
                        self.touch_key(code);
                    }
                }
            }
            Action::Switch(direction) => {
                if let Some(name) = touch_layout::switch(&direction) {
                    log::info!("Horizon: touch profile {name}");
                }
            }
            Action::Command(_) | Action::Builtin(_) => {}
        }
    }

    /// The pad's menu checks, plus the prompts over the world.
    fn touch_in_menu(&self) -> bool {""",
        "touch_extra",
    )

    TOUCH.write_text(text)
    print("patch-touch: профили управления вшиты")


if __name__ == "__main__":
    main()
