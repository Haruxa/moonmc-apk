//! MoonMC: ввод с системной клавиатуры Android (IME) без синтетических `KeyEvent`.
//!
//! `crate::event_loop` складывает текст из Java (`nativeText`, `nativeKey`) в очередь
//! `Typed`, но пин a46ce689 её не разбирает. Забираем очередь раз в кадр и отдаём
//! прямо в `chat_key_code`, поэтому порядок символов сохраняется.
//!
//! В чате символы идут через `chat_key_code`. На титульных экранах (в том числе
//! Add/Edit Server в Multiplayer) текст и Backspace идут в `title_edit`, а Enter —
//! в `Title::confirm_key`. Add/Edit Server (поля `server.name` и `server.address`)
//! `confirm_key` не сохраняет: он понимает только Direct Connection. Поэтому, если
//! `confirm_key` вернул `Action::None`, Enter нажимает кнопку Done (id 50) тем же
//! `Title::click`, каким это делает мышь, — координаты берутся у кнопки, а не
//! считаются руками.
//!
//! Остальные поля (табличка, книга, командный блок и т.п.) не поддерживаются:
//! события явно диагностируются, а не выдаются за поддержку. Содержимое текста
//! нигде не логируется.
use super::{Game, Screen};
use horizon_client::event_loop::{Typed, take_typed};
use horizon_client::hud::HudBatch;
use horizon_client::title::{Action, Mode};
use winit::event_loop::ActiveEventLoop;
use winit::keyboard::KeyCode;

/// `gui.done` у Add/Edit Server: `Screen::buttons` пина, не ручная геометрия.
const DONE: u8 = 50;

impl Game<'_> {
    /// Разбирает очередь IME; вызывается из `about_to_wait` сразу после `touch_frame`.
    pub(super) fn moon_ime_frame(&mut self, event_loop: &ActiveEventLoop) {
        let typed = take_typed();
        if typed.is_empty() {
            return;
        }
        // Options — отдельный экран поверх титула: у него свой разбор клавиш.
        if self.title.is_some() && self.screen != Screen::Options {
            for input in typed {
                match input {
                    // `title_edit` ждёт `Option<&str>`: `Some(&String)` не совпадёт.
                    Typed::Text(text) => self.title_edit(Some(text.as_str()), false),
                    Typed::Backspace => self.title_edit(None, true),
                    Typed::Enter => self.moon_ime_title_enter(event_loop),
                }
            }
            return;
        }
        if self.screen != Screen::Chat {
            // События уже забраны из очереди — молча их не выбрасываем.
            let count = typed.len();
            match self.moon_ime_field() {
                Some(field) => log::warn!(
                    "MoonMC IME: {count} событие(й) не доставлено: поле {field} не поддерживается (только чат)"
                ),
                None => log::warn!(
                    "MoonMC IME: {count} событие(й) не доставлено: поле ввода не открыто (только чат)"
                ),
            }
            return;
        }
        for input in typed {
            match input {
                Typed::Text(text) => {
                    // F24 — «нет такой клавиши»: `TextField::key` её не знает и
                    // вставляет символ как typed text, как это делал KeyEvent.
                    for c in text.chars() {
                        let text = c.to_string();
                        self.chat_key_code(KeyCode::F24, Some(&text));
                    }
                }
                Typed::Backspace => self.chat_key_code(KeyCode::Backspace, None),
                Typed::Enter => self.chat_key_code(KeyCode::Enter, None),
            }
        }
    }

    /// Enter на титульном экране: `confirm_key`, а для Add/Edit Server — Done.
    /// Кнопка Done активна, только когда адрес разбирается, поэтому неверный
    /// адрес по-прежнему ничего не сохраняет.
    fn moon_ime_title_enter(&mut self, event_loop: &ActiveEventLoop) {
        let size = self
            .renderer
            .as_ref()
            .map(|r| [r.config.width, r.config.height]);
        let action = {
            let Some(title) = self.title.as_mut() else {
                return;
            };
            let mut action = title.confirm_key();
            if matches!(action, Action::None)
                && let Some(size) = size
            {
                let batch = HudBatch::new(size);
                let (w, h) = (batch.width.ceil(), batch.height.ceil());
                let done = match &title.mode {
                    Mode::Multiplayer(screen) => screen
                        .buttons(w, h)
                        .into_iter()
                        .find(|button| button.4 && button.5 == DONE),
                    _ => None,
                };
                if let Some((x, y, bw, _, _, _)) = done {
                    action = title
                        .click(x + bw / 2.0, y + 10.0, (batch.width, batch.height))
                        .0;
                }
            }
            action
        };
        self.save_title_options();
        if let Err(error) = self.title_action(event_loop, action) {
            log::warn!("MoonMC IME: Enter не выполнен: {error}");
        }
    }

    /// Открытое текстовое поле (для диагностики); чат разобран выше.
    fn moon_ime_field(&self) -> Option<&'static str> {
        if self.sign_edit.is_some() {
            return Some("sign");
        }
        if self.book.is_some() {
            return Some("book");
        }
        match self.screen {
            Screen::CommandBlock => Some("command block"),
            Screen::Options => Some("options"),
            Screen::Creative => Some("creative search"),
            _ => None,
        }
    }
}
