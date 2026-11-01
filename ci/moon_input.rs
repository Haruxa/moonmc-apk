//! MoonMC: мост из Compose-слоя (Zalith LayerController) в игровой ввод.
//!
//! Слой рисуется в отдельном окне, поэтому обычные касания до нативной поверхности
//! не доходят. Kotlin кладёт события в очередь (JNI), а игра разбирает её раз в кадр
//! и применяет как клавиши/кнопки/оси — так же, как это делает тач-слой порта.
use std::sync::{Mutex, MutexGuard, OnceLock};

use winit::keyboard::KeyCode;

#[derive(Clone, Copy, Debug)]
pub(super) enum Event {
    /// Клавиша в терминах GLFW (как у Zalith/InputMap): нажали/отпустили.
    Key(i32, bool),
    /// Кнопка мыши: 0 — левая (удар/использовать), 1 — правая.
    Mouse(i32, bool),
    /// Стик: x, y в -1..1 (x вправо, y вниз).
    Stick(f32, f32),
}

static QUEUE: OnceLock<Mutex<Vec<Event>>> = OnceLock::new();

fn queue() -> MutexGuard<'static, Vec<Event>> {
    QUEUE
        .get_or_init(|| Mutex::new(Vec::new()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

pub(super) fn push(event: Event) {
    let mut q = queue();
    if q.len() < 256 {
        q.push(event);
    }
}

pub(super) fn drain() -> Vec<Event> {
    std::mem::take(&mut *queue())
}

/// GLFW-код (Zalith/InputMap) → клавиша winit.
pub(super) fn key_of(glfw: i32) -> Option<KeyCode> {
    Some(match glfw {
        32 => KeyCode::Space,
        39 => KeyCode::Quote,
        44 => KeyCode::Comma,
        45 => KeyCode::Minus,
        46 => KeyCode::Period,
        47 => KeyCode::Slash,
        48..=57 => match glfw {
            48 => KeyCode::Digit0,
            49 => KeyCode::Digit1,
            50 => KeyCode::Digit2,
            51 => KeyCode::Digit3,
            52 => KeyCode::Digit4,
            53 => KeyCode::Digit5,
            54 => KeyCode::Digit6,
            55 => KeyCode::Digit7,
            56 => KeyCode::Digit8,
            _ => KeyCode::Digit9,
        },
        65..=90 => match glfw {
            65 => KeyCode::KeyA,
            66 => KeyCode::KeyB,
            67 => KeyCode::KeyC,
            68 => KeyCode::KeyD,
            69 => KeyCode::KeyE,
            70 => KeyCode::KeyF,
            71 => KeyCode::KeyG,
            72 => KeyCode::KeyH,
            73 => KeyCode::KeyI,
            74 => KeyCode::KeyJ,
            75 => KeyCode::KeyK,
            76 => KeyCode::KeyL,
            77 => KeyCode::KeyM,
            78 => KeyCode::KeyN,
            79 => KeyCode::KeyO,
            80 => KeyCode::KeyP,
            81 => KeyCode::KeyQ,
            82 => KeyCode::KeyR,
            83 => KeyCode::KeyS,
            84 => KeyCode::KeyT,
            85 => KeyCode::KeyU,
            86 => KeyCode::KeyV,
            87 => KeyCode::KeyW,
            88 => KeyCode::KeyX,
            89 => KeyCode::KeyY,
            _ => KeyCode::KeyZ,
        },
        256 => KeyCode::Escape,
        257 => KeyCode::Enter,
        258 => KeyCode::Tab,
        259 => KeyCode::Backspace,
        262 => KeyCode::ArrowRight,
        263 => KeyCode::ArrowLeft,
        264 => KeyCode::ArrowDown,
        265 => KeyCode::ArrowUp,
        280 => KeyCode::CapsLock,
        290..=298 => match glfw {
            290 => KeyCode::F1,
            291 => KeyCode::F2,
            292 => KeyCode::F3,
            293 => KeyCode::F4,
            294 => KeyCode::F5,
            295 => KeyCode::F6,
            296 => KeyCode::F7,
            297 => KeyCode::F8,
            _ => KeyCode::F9,
        },
        340 => KeyCode::ShiftLeft,
        341 => KeyCode::ControlLeft,
        342 => KeyCode::AltLeft,
        344 => KeyCode::ShiftRight,
        345 => KeyCode::ControlRight,
        _ => return None,
    })
}
