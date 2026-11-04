//! MoonMC: ввод с системной клавиатуры Android (IME) без синтетических `KeyEvent`.
//!
//! `crate::event_loop` складывает текст из Java (`nativeText`, `nativeKey`) в очередь
//! `Typed`, но пин a46ce689 её не разбирает. Забираем очередь раз в кадр и отдаём
//! прямо в `chat_key_code`, поэтому порядок символов сохраняется.
//!
//! Поддерживается только `Screen::Chat`. Для остальных полей (табличка, книга,
//! командный блок и т.п.) события явно диагностируются: не выдаём их за поддержку.
use super::{Game, Screen};
use horizon_client::event_loop::{Typed, take_typed};
use winit::keyboard::KeyCode;

impl Game<'_> {
    /// Разбирает очередь IME; вызывается из `about_to_wait` сразу после `touch_frame`.
    pub(super) fn moon_ime_frame(&mut self) {
        let typed = take_typed();
        if typed.is_empty() {
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
