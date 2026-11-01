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

ROOT = pathlib.Path(".")
CLIENT = ROOT / "crates/horizon-client/src"
TOUCH = CLIENT / "game/touch.rs"
GAME = CLIENT / "game.rs"
MODULE = CLIENT / "game/touch_layout.rs"
MENU = CLIENT / "game/touch_menu.rs"


ERRORS: list[str] = []


def replace(text: str, old: str, new: str, what: str) -> str:
    # Не выходим на первом промахе: за один прогон CI видно все несовпадения.
    if old not in text:
        ERRORS.append(f"{what}: не найден якорь")
        return text
    if text.count(old) > 1:
        ERRORS.append(f"{what}: якорь не уникален ({text.count(old)})")
        return text
    return text.replace(old, new, 1)


def main() -> None:
    source = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "ci/touch_layout.rs")
    MODULE.write_text(source.read_text())
    # мост из Compose-слоя: очередь событий и JNI-нативы
    for name in ("moon_input.rs", "moon_jni.rs"):
        src = pathlib.Path("ci") / name
        if src.is_file():
            (CLIENT / name).write_text(src.read_text())
    # Оверлей профилей убран: управление — только тач-слой порта (боевой режим).

    # 1. Регистрация модуля рядом с `mod touch;` (в новых пинах её нет — добавляем).
    mod = GAME.read_text()
    anchor = next(
        (line for line in mod.splitlines() if line.strip() in ("mod touch;", "pub mod touch;", "pub(super) mod touch;")),
        None,
    )
    if anchor is None:
        host = next(
            (line for line in mod.splitlines() if line.strip() in ("mod mods;", "pub mod mods;")),
            None,
        )
        if host is None:
            ERRORS.append("game.rs: некуда поставить `mod touch;`")
            anchor = ""
        else:
            mod = mod.replace(host, host + "\nmod touch;\nmod touch_camera;", 1)
            GAME.write_text(mod)
            mod = GAME.read_text()
            anchor = "mod touch;"
            print("game.rs: добавил `mod touch;`")
    if "mod touch_layout;" not in mod:
        indent = anchor[: len(anchor) - len(anchor.lstrip())]
        visibility = anchor.strip().split("mod ")[0]
        mod = mod.replace(anchor, f"{anchor}\n{indent}{visibility}mod touch_layout;", 1)
        GAME.write_text(mod)
    mod = GAME.read_text()
    if False:
        anchor_line = next(
            (line for line in mod.splitlines() if "mod touch_layout;" in line),
            None,
        )
        if anchor_line is None:
            ERRORS.append("game.rs: нет mod touch_layout;")
        else:
            indent = anchor_line[: len(anchor_line) - len(anchor_line.lstrip())]
            visibility = anchor_line.strip().split("mod ")[0]
            GAME.write_text(mod)

    # 0a. Поля и вызовы тач-слоя в game.rs: порт держит их в Game.
    # модули моста объявляются в корне крейта (android.rs)
    ROOT = CLIENT / "android.rs"
    if ROOT.is_file():
        root = ROOT.read_text()
        if "mod moon_input;" not in root:
            anchor = next((l for l in root.splitlines() if l.strip().startswith("mod ")), None)
            if anchor:
                root = root.replace(anchor, anchor + "\nmod moon_input;\nmod moon_jni;", 1)
                ROOT.write_text(root)
                print("android.rs: подключён мост moon_input/moon_jni")

    # разбор очереди в кадре тач-слоя
    text = TOUCH.read_text()
    if "moon_input::drain()" not in text:
        anchor = "    pub(super) fn touch_frame(&mut self, event_loop: &ActiveEventLoop) {"
        if anchor in text:
            body = anchor + """

        for event in crate::moon_input::drain() {
            use crate::moon_input::Event;
            match event {
                Event::Key(code, pressed) => {
                    if let Some(key) = crate::moon_input::key_of(code) {
                        if pressed {
                            self.keys.insert(key);
                        } else {
                            self.keys.remove(&key);
                        }
                    }
                }
                Event::Mouse(button, pressed) => {
                    if button < 0 {
                        self.selected = (-button - 100).clamp(0, 8) as usize;
                    } else if pressed {
                        if button == 0 {
                            self.touch.attack_pulse = true;
                        } else {
                            self.touch.use_pulse = true;
                        }
                    }
                }
                Event::Stick(x, y) => {
                    for (key, on) in [
                        (KeyCode::KeyW, y < -0.3),
                        (KeyCode::KeyS, y > 0.3),
                        (KeyCode::KeyA, x < -0.3),
                        (KeyCode::KeyD, x > 0.3),
                    ] {
                        if on {
                            self.keys.insert(key);
                        } else {
                            self.keys.remove(&key);
                        }
                    }
                }
            }
        }"""
            text = text.replace(anchor, body, 1)
            TOUCH.write_text(text)
            print("touch.rs: очередь моста разбирается в кадре")
        anchor = "    mods: mods::ModsState,"
        if anchor in gtext:
            gtext = gtext.replace(
                anchor,
                anchor
                + "\n    /// MoonMC: наэкранное управление и его камера.\n    touch: touch::Touch,"
                + "\n    touch_camera: touch_camera::Camera,",
                1,
            )
            GAME.write_text(gtext)
            print("game.rs: поля touch/touch_camera")
        else:
            ERRORS.append("game.rs: нет поля mods для вставки touch")
    gtext = GAME.read_text()
    if "touch: Default::default()," not in gtext:
        anchor = "            mods: Default::default(),"
        if anchor in gtext:
            gtext = gtext.replace(
                anchor,
                anchor + "\n            touch: Default::default(),\n            touch_camera: Default::default(),",
                1,
            )
            GAME.write_text(gtext)
            print("game.rs: инициализация touch")
        else:
            ERRORS.append("game.rs: нет инициализации mods")
    gtext = GAME.read_text()
    if "self.draw_touch(" not in gtext:
        anchor = "            self.draw_extras(&mut batch);"
        if anchor in gtext:
            gtext = gtext.replace(anchor, anchor + "\n            self.draw_touch(&mut batch);", 1)
            GAME.write_text(gtext)
            print("game.rs: рисуем тач-слой")
        else:
            ERRORS.append("game.rs: нет вызова draw_extras")
    gtext = GAME.read_text()
    if "self.touch_frame(event_loop);" not in gtext:
        anchor = "    fn about_to_wait(&mut self, event_loop: &ActiveEventLoop) {"
        if anchor in gtext:
            gtext = gtext.replace(anchor, anchor + "\n        self.touch_frame(event_loop);", 1)
            GAME.write_text(gtext)
            print("game.rs: кадр тач-слоя в about_to_wait")
        else:
            ERRORS.append("game.rs: нет about_to_wait")

    # 0. Касания и «назад»: при переносе порта на новый пин cherry-pick оставляет
    # window_event от движка (в нём нет WindowEvent::Touch), и тапы не доходят до игры.
    # Правим не тело window_event, а диспетчер App::Ready — он в том же модуле и стабилен.
    gtext = GAME.read_text()
    if "game.touch_event(event_loop, id, touch)" not in gtext:
        dispatch = "            App::Ready(game) => game.window_event(event_loop, id, event),"
        if dispatch not in gtext:
            ERRORS.append("game.rs: нет диспетчера App::Ready")
        else:
            gtext = gtext.replace(
                dispatch,
                """            App::Ready(game) => {
                // MoonMC: касания и системная «назад» — из порта, движок их не знает.
                if let WindowEvent::Touch(touch) = event {
                    game.touch_event(event_loop, id, touch);
                } else if let WindowEvent::KeyboardInput { event: key, .. } = &event
                    && key.logical_key
                        == winit::keyboard::Key::Named(winit::keyboard::NamedKey::BrowserBack)
                {
                    if key.state == ElementState::Pressed && !key.repeat {
                        game.touch_back();
                    }
                } else {
                    game.window_event(event_loop, id, event);
                }
            }""",
                1,
            )
            GAME.write_text(gtext)
            print("game.rs: касания и «назад» подключены в диспетчере")

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

    # 7c. Диагностика ввода: видно в logs/latest.log, что игра получила касание.
    text = replace(
        text,
        "        let inventory = matches!(self.screen, Screen::Inventory | Screen::Creative);",
        """        let inventory = matches!(self.screen, Screen::Inventory | Screen::Creative);
        log::info!(
            "touch: {:?} at ({:.0}, {:.0}) gui {}x{} scale {:.2} menu={} playing={} title={}",
            t.phase,
            at.0,
            at.1,
            batch.width,
            batch.height,
            batch.scale,
            self.touch_in_menu(),
            self.screen == Screen::Playing,
            self.title.is_some()
        );""",
        "touch диагностика",
    )

    # 8. Кнопка: иконка из профиля (пиксельная, рисуется кодом) или подпись.
    text = replace(
        text,
        "    fn label_stock(self, mode: Mode) -> &'static str {",
        """    /// The profile's id of this button.
    fn id(self) -> &'static str {
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

    fn label_stock(self, mode: Mode) -> &'static str {""",
        "Key::id",
    )
    text = replace(
        text,
        "            let label = key.label(mode);\n            let tw = HudBatch::text_width(atlas, label);\n            batch.text(atlas, label, x - tw / 2.0, y - 4.0, 0xE0FF_FFFF, true);",
        """            match touch_layout::icon(key.id()) {
                Some(icon) => {
                    touch_layout::draw_icon(batch, atlas, &icon, x, y, r, 0xE0FF_FFFF);
                }
                None => {
                    let label = key.label(mode);
                    let tw = HudBatch::text_width(atlas, &label);
                    batch.text(atlas, &label, x - tw / 2.0, y - 4.0, 0xE0FF_FFFF, true);
                }
            }""",
        "подпись кнопки",
    )

    # 9. Отрисовка дополнительных контролов.
    
    
    text = replace(
        text,
        "        // Shift lock's aim: a ring at the centre.",
        """        for (i, x, y, r) in touch_layout::extras(w, h, mode == Mode::Tap) {
            disc(batch, atlas, (x, y), r, 0x40FF_FFFF);
            match touch_layout::extra_icon(i) {
                Some(icon) => {
                    touch_layout::draw_icon(batch, atlas, &icon, x, y, r, 0xE0FF_FFFF);
                }
                None => {
                    let label = touch_layout::text(i);
                    if !label.is_empty() {
                        let tw = HudBatch::text_width(atlas, &label);
                        batch.text(atlas, &label, x - tw / 2.0, y - 4.0, 0xE0FF_FFFF, true);
                    }
                }
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

    

    # 12. Совместимость с новыми пинами: чего в них уже нет.
    text = TOUCH.read_text()
    # в 10-30 Creative не умеет snap()
    text = text.replace(
        "                        if scrolling && self.screen == Screen::Creative {\n"
        "                            self.creative_screen.snap(&self.pack.names);\n"
        "                        }\n",
        "",
    )
    # в 10-30 Pads.injected приватный: строку убираем
    text = text.replace("        self.pad.injected = state;\n", "")
    # в 10-30 нет held_uses_over_time(): тап-использование идёт обычным путём
    text = text.replace("} else if self.held_uses_over_time() {", "} else if false {")
    TOUCH.write_text(text)

    # Android-клипборд: движковый game/clipboard.rs ждёт эти два элемента
    clip = pathlib.Path("crates/horizon-client-input/src/clipboard.rs")
    if clip.is_file():
        body = clip.read_text()
        if "ALTGR_IS_CTRL_ALT" not in body:
            block = """/// Windows names AltGr Ctrl+Alt; everywhere else it is its own modifier.
pub const ALTGR_IS_CTRL_ALT: bool = cfg!(windows);

/// Whether this modifier state produces a character (`KeyboardHandler.charTyped`).
pub fn types_text(edit_modifier: bool, alt: bool, altgr_is_ctrl_alt: bool) -> bool {
    let altgr = altgr_is_ctrl_alt && alt;
    !(edit_modifier || (alt && !altgr))
}

"""
            # важно: до #[cfg(test)], иначе элементы окажутся внутри тестового модуля
            if "#[cfg(test)]" in body:
                body = body.replace("#[cfg(test)]", block + "#[cfg(test)]", 1)
            else:
                body = body + "\n" + block
            clip.write_text(body)
            print("clipboard: добавил types_text/ALTGR_IS_CTRL_ALT")
    if ERRORS:
        sys.exit("patch-touch: движок не совпал с якорями — " + "; ".join(ERRORS))
    print("patch-touch: профили управления вшиты")


if __name__ == "__main__":
    main()
