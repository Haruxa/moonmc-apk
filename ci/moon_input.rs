//! MoonMC: мост из Compose-слоя (Zalith LayerController) в игровой ввод.
//!
//! Слой рисуется в отдельном окне, поэтому обычные касания до нативной поверхности
//! не доходят. Kotlin кладёт события в очередь (JNI), а игра разбирает её раз в кадр
//! и применяет как клавиши/кнопки/оси — так же, как это делает тач-слой порта.
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

use winit::keyboard::KeyCode;

#[derive(Clone, Debug)]
pub(super) enum Event {
    /// Клавиша в терминах GLFW (как у Zalith/InputMap): нажали/отпустили.
    Key(i32, bool),
    /// Кнопка мыши: 0 — левая (удар/использовать), 1 — правая.
    Mouse(i32, bool),
    /// Стик: x, y в -1..1 (x вправо, y вниз).
    Stick(f32, f32),
    /// Касание мимо кнопок слоя (клик меню, поворот камеры):
    /// физические пиксели x, y; action = MotionEvent.actionMasked; id = палец.
    Touch(f32, f32, i32, i32),
    /// Системная клавиатура Android: показать/скрыть (кнопка IME слоя).
    Keyboard(bool),
    /// Вошедший аккаунт (или None — вышли): имя игрока для движка.
    Account(Option<String>),
    /// «Ввод» слоя над клавиатурой: то же, что Enter системной клавиатуры в чате.
    Submit,
}

static QUEUE: OnceLock<Mutex<VecDeque<Event>>> = OnceLock::new();

fn queue() -> MutexGuard<'static, VecDeque<Event>> {
    QUEUE
        .get_or_init(|| Mutex::new(VecDeque::new()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

pub(super) fn push(event: Event) {
    let mut q = queue();
    if q.len() < 256 {
        q.push_back(event);
    }
}

pub(super) fn drain() -> Vec<Event> {
    std::mem::take(&mut *queue()).into()
}

/// Клавиатура сейчас на экране: слой управления на это время прячется.
static KEYBOARD: OnceLock<AtomicBool> = OnceLock::new();

pub(super) fn set_keyboard_up(on: bool) {
    KEYBOARD.get_or_init(|| AtomicBool::new(false)).store(on, Ordering::Relaxed);
}

pub(super) fn keyboard_up() -> bool {
    KEYBOARD.get_or_init(|| AtomicBool::new(false)).load(Ordering::Relaxed)
}

/// Имя игрока, которое язык держит сейчас (его же показывает панель аккаунта).
static ACCOUNT: OnceLock<Mutex<String>> = OnceLock::new();

pub(super) fn set_account(name: &str) {
    *ACCOUNT.get_or_init(|| Mutex::new(String::new())).lock().unwrap_or_else(|e| e.into_inner()) =
        name.to_owned();
}

pub(super) fn account() -> String {
    ACCOUNT
        .get_or_init(|| Mutex::new(String::new()))
        .lock()
        .map(|n| n.clone())
        .unwrap_or_default()
}

/// Открыт ли экран/меню игры: тогда игровые кнопки слоя скрыты.
static IN_MENU: OnceLock<AtomicBool> = OnceLock::new();

pub(super) fn set_in_menu(on: bool) {
    IN_MENU.get_or_init(|| AtomicBool::new(false)).store(on, Ordering::Relaxed);
}

pub(super) fn in_menu() -> bool {
    IN_MENU.get_or_init(|| AtomicBool::new(false)).load(Ordering::Relaxed)
}

/// Движок загрузил ресурсы и меню живое: до этого слой показывает экран загрузки.
static READY: OnceLock<AtomicBool> = OnceLock::new();

pub(super) fn set_ready(on: bool) {
    READY.get_or_init(|| AtomicBool::new(false)).store(on, Ordering::Relaxed);
}

pub(super) fn ready() -> bool {
    READY.get_or_init(|| AtomicBool::new(false)).load(Ordering::Relaxed)
}

/// Жест пальца в мире: камера и тап, без старого тач-слоя порта.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(super) enum Step {
    /// Поворот камеры на смещение пальца (физические пиксели).
    Look(f32, f32),
    /// Короткий тап: удар/использовать.
    Tap,
    /// Ничего делать не нужно.
    None,
}

/// Те же пороги, что у тач-слоя порта (`TAP`/`STILL` в game/touch.rs).
const TAP: Duration = Duration::from_millis(250);
const STILL: f32 = 6.0;

struct Finger {
    id: i32,
    start: (f32, f32),
    last: (f32, f32),
    down_at: Instant,
    moved: f32,
}

static FINGER: OnceLock<Mutex<Option<Finger>>> = OnceLock::new();

fn finger() -> MutexGuard<'static, Option<Finger>> {
    FINGER
        .get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

fn distance(a: (f32, f32), b: (f32, f32)) -> f32 {
    ((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt()
}

/// Разбор касания для мира: первый палец крутит камеру, его короткий тап — удар.
/// Остальные пальцы игнорируются, чтобы камера не дёргалась.
pub(super) fn finger_step(x: f32, y: f32, action: i32, id: i32) -> Step {
    let mut slot = finger();
    match action {
        // ACTION_DOWN / ACTION_POINTER_DOWN: жест ведёт первый палец
        // (MotionEvent: DOWN=0, MOVE=2, UP=1, CANCEL=3, POINTER_DOWN=5, POINTER_UP=6).
        0 | 5 => {
            if slot.is_some() {
                return Step::None;
            }
            *slot = Some(Finger {
                id,
                start: (x, y),
                last: (x, y),
                down_at: Instant::now(),
                moved: 0.0,
            });
            Step::None
        }
        // ACTION_MOVE
        2 => {
            let Some(f) = slot.as_mut() else {
                return Step::None;
            };
            if f.id != id {
                return Step::None;
            }
            let (dx, dy) = (x - f.last.0, y - f.last.1);
            f.last = (x, y);
            f.moved = f.moved.max(distance(f.start, (x, y)));
            if dx == 0.0 && dy == 0.0 {
                Step::None
            } else {
                Step::Look(dx, dy)
            }
        }
        // ACTION_UP / ACTION_POINTER_UP
        1 | 6 => {
            let Some(f) = slot.take() else {
                return Step::None;
            };
            if f.id == id && f.down_at.elapsed() <= TAP && f.moved < STILL {
                Step::Tap
            } else {
                Step::None
            }
        }
        // ACTION_CANCEL и указатель/прочее: палец забываем.
        _ => {
            if slot.as_ref().is_some_and(|f| f.id == id) {
                *slot = None;
            }
            Step::None
        }
    }
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

#[cfg(test)]
// shortcut: модуль android-only (mod android::moon_input), поэтому `cargo test` эти
// тесты не гоняет — жест проверяется на устройстве; тесты для будущего хоста.
mod tests {
    use super::*;

    #[test]
    fn short_tap_is_attack() {
        assert_eq!(finger_step(10.0, 10.0, 0, 0), Step::None);
        assert_eq!(finger_step(10.0, 10.0, 1, 0), Step::Tap);
    }

    #[test]
    fn swipe_looks_and_is_not_a_tap() {
        assert_eq!(finger_step(10.0, 10.0, 0, 0), Step::None);
        assert_eq!(finger_step(60.0, 10.0, 2, 0), Step::Look(50.0, 0.0));
        assert_eq!(finger_step(60.0, 10.0, 1, 0), Step::None);
    }

    #[test]
    fn cancel_forgets_the_finger() {
        assert_eq!(finger_step(10.0, 10.0, 0, 0), Step::None);
        assert_eq!(finger_step(10.0, 10.0, 3, 0), Step::None);
        assert_eq!(finger_step(10.0, 10.0, 1, 0), Step::None);
    }

    #[test]
    fn second_finger_is_ignored() {
        assert_eq!(finger_step(10.0, 10.0, 0, 0), Step::None);
        assert_eq!(finger_step(80.0, 80.0, 5, 1), Step::None);
        assert_eq!(finger_step(60.0, 10.0, 2, 0), Step::Look(50.0, 0.0));
        assert_eq!(finger_step(80.0, 80.0, 6, 1), Step::None);
    }
}
