//! MoonMC: JNI-точки входа из Compose-слоя управления (см. moon_input.rs).
use jni::objects::JClass;
use jni::sys::{jboolean, jfloat, jint};

/// Клавиша в терминах GLFW (как в InputMap у Zalith).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_moonmc_game_Native_key(
    _env: jni::JNIEnv,
    _class: JClass,
    code: jint,
    pressed: jboolean,
) {
    crate::moon_input::push(crate::moon_input::Event::Key(code, pressed != 0));
}

/// Кнопка мыши: 0 — левая, 1 — правая.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_moonmc_game_Native_mouse(
    _env: jni::JNIEnv,
    _class: JClass,
    button: jint,
    pressed: jboolean,
) {
    crate::moon_input::push(crate::moon_input::Event::Mouse(button, pressed != 0));
}

/// Стик джойстика: -1..1 по обеим осям.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_moonmc_game_Native_stick(
    _env: jni::JNIEnv,
    _class: JClass,
    x: jfloat,
    y: jfloat,
) {
    crate::moon_input::push(crate::moon_input::Event::Stick(x, y));
}

/// Хотбар: номер слота 0..8.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_moonmc_game_Native_hotbar(
    _env: jni::JNIEnv,
    _class: JClass,
    slot: jint,
) {
    crate::moon_input::push(crate::moon_input::Event::Mouse(-100 - slot, true));
}
