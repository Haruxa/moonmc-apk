//! Touch layout profiles (Android). The on-screen buttons' places, sizes, labels and extra
//! actions are read from `touch/profiles/*.json` in the game folder, so a layout can be
//! changed without rebuilding the game. `touch/active.txt` names the profile in use.
//!
//! A profile is a list of controls. An `id` that matches a stock button ("jump", "sneak",
//! "sprint", "inventory", "pause", "view", "mode", "debug", "chat", "lock", "attack") moves
//! or relabels that button. Any other control is an extra button, driven by its `action`:
//! `{"key": "e"}`, `{"hold": "shift"}`, `{"macro": ["a", "b"]}`, `{"switch": "next"}` or
//! `{"command": "/gamemode creative"}` (commands are queued as text into the chat).
//!
//! Without any profile the stock layout is used; the first run writes `default.json` with it.
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, OnceLock};

use winit::keyboard::KeyCode;

/// The profiles' folder in the game folder.
const DIR: &str = "touch/profiles";
/// The file naming the profile in use.
const ACTIVE: &str = "touch/active.txt";

/// What a control does when it is pressed.
#[derive(Clone, Debug)]
pub(super) enum Action {
    /// A stock button, by its id in the game (`jump`, `sneak`, …).
    Builtin(String),
    /// A key of the keyboard: pressed and released at once.
    Key(String),
    /// A key held down (and again let go) by a tap: sneak, sprint.
    Hold(String),
    /// Several keys in a row.
    Macro(Vec<String>),
    /// A line typed into the chat, commands included.
    Command(String),
    /// Another profile: `next` or `prev`.
    Switch(String),
}

/// One control of a profile.
#[derive(Clone, Debug)]
struct Entry {
    id: String,
    label: String,
    /// Centre, as a part of the GUI's width and height.
    x: f32,
    y: f32,
    /// Radius in GUI units.
    r: f32,
    /// The modes it shows in ("aim", "tap"); empty is both.
    modes: Vec<String>,
    /// A built-in pixel icon instead of the label (`jump`, `sneak`, `sprint`, …).
    icon: Option<String>,
    action: Action,
}

#[derive(Clone, Debug)]
struct Layout {
    file: PathBuf,
    #[allow(dead_code)]
    name: String,
    entries: Vec<Entry>,
}

static SLOT: OnceLock<Mutex<Option<Layout>>> = OnceLock::new();

fn slot() -> MutexGuard<'static, Option<Layout>> {
    SLOT.get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

/// The stock layout, written to `touch/profiles/default.json` when there is none.
const STOCK: &str = r#"{
  "schema": 1,
  "name": "Стандартный",
  "controls": [
    {"id": "jump", "label": "Jump", "x": 0.95, "y": 0.89, "r": 22},
    {"id": "sneak", "label": "Sneak", "x": 0.83, "y": 0.94, "r": 15},
    {"id": "sprint", "label": "Run", "x": 0.83, "y": 0.83, "r": 15},
    {"id": "inventory", "label": "...", "x": 0.65, "y": 0.97, "r": 10},
    {"id": "pause", "label": "II", "x": 0.97, "y": 0.04, "r": 11},
    {"id": "view", "label": "F5", "x": 0.91, "y": 0.04, "r": 11},
    {"id": "mode", "label": "Tap", "x": 0.85, "y": 0.04, "r": 11},
    {"id": "debug", "label": "F3", "x": 0.79, "y": 0.04, "r": 11},
    {"id": "chat", "label": "Chat", "x": 0.50, "y": 0.04, "r": 12},
    {"id": "lock", "label": "Lock", "x": 0.30, "y": 0.95, "r": 11},
    {"id": "attack", "label": "Hit", "x": 0.67, "y": 0.50, "r": 24, "modes": ["aim"]}
  ]
}"#;

/// Loads the profile in use; on no profile at all, writes the stock one first.
pub(super) fn ensure_loaded() {
    let mut slot = slot();
    if slot.is_some() {
        return;
    }
    if profiles().is_empty() {
        let _ = std::fs::create_dir_all(DIR);
        let path = Path::new(DIR).join("default.json");
        if std::fs::write(&path, STOCK).is_ok() && std::fs::write(ACTIVE, "default").is_err() {
            let _ = std::fs::create_dir_all("touch");
            let _ = std::fs::write(ACTIVE, "default");
        }
    }
    let files = profiles();
    let path = active_path(&files).or_else(|| files.first().cloned());
    if let Some(path) = path {
        *slot = parse(&path);
    }
}

fn profiles() -> Vec<PathBuf> {
    let mut out = Vec::new();
    if let Ok(dir) = std::fs::read_dir(DIR) {
        for entry in dir.flatten() {
            let path = entry.path();
            if path.extension().is_some_and(|e| e == "json") {
                out.push(path);
            }
        }
    }
    out.sort();
    out
}

fn active_path(files: &[PathBuf]) -> Option<PathBuf> {
    let name = std::fs::read_to_string(ACTIVE).ok()?;
    let name = name.trim().to_string();
    files
        .iter()
        .find(|p| p.file_stem().is_some_and(|s| s == name.as_str()))
        .cloned()
}

fn parse(path: &Path) -> Option<Layout> {
    let text = std::fs::read_to_string(path).ok()?;
    let value: serde_json::Value = serde_json::from_str(&text).ok()?;
    let name = value
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("profile")
        .to_string();
    let mut entries = Vec::new();
    for item in value.get("controls")?.as_array()? {
        let id = item.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let action = match item.get("action") {
            Some(action) => action_of(action).unwrap_or(Action::Key("space".to_string())),
            None => Action::Builtin(id.clone()),
        };
        entries.push(Entry {
            label: item
                .get("label")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            x: number(item, "x", 0.5),
            y: number(item, "y", 0.5),
            r: number(item, "r", 18.0),
            modes: item
                .get("modes")
                .and_then(|v| v.as_array())
                .map(|list| {
                    list.iter()
                        .filter_map(|v| v.as_str().map(str::to_string))
                        .collect()
                })
                .unwrap_or_default(),
            icon: item
                .get("icon")
                .and_then(|v| v.as_str())
                .map(str::to_string)
                .filter(|i| !i.is_empty()),
            id,
            action,
        });
    }
    Some(Layout {
        file: path.to_path_buf(),
        name,
        entries,
    })
}

fn number(item: &serde_json::Value, key: &str, default: f32) -> f32 {
    item.get(key)
        .and_then(|v| v.as_f64())
        .map(|v| v as f32)
        .unwrap_or(default)
}

fn action_of(value: &serde_json::Value) -> Option<Action> {
    let object = value.as_object()?;
    if let Some(list) = object.get("macro") {
        let keys = list
            .as_array()?
            .iter()
            .filter_map(|v| v.as_str().map(str::to_string))
            .collect();
        return Some(Action::Macro(keys));
    }
    let (key, inner) = object.iter().next()?;
    let text = inner.as_str()?.to_string();
    Some(match key.as_str() {
        "builtin" => Action::Builtin(text),
        "key" => Action::Key(text),
        "hold" => Action::Hold(text),
        "command" => Action::Command(text),
        "switch" => Action::Switch(text),
        _ => return None,
    })
}

/// A stock button's place from the profile, in GUI units.
pub(super) fn geometry(id: &str, w: f32, h: f32) -> Option<(f32, f32, f32)> {
    let slot = slot();
    let entry = slot.as_ref()?.entries.iter().find(|e| e.id == id)?;
    Some((entry.x * w, entry.y * h, entry.r))
}

/// A stock button's label from the profile, if the profile renamed it.
pub(super) fn label(id: &str) -> Option<String> {
    let slot = slot();
    let entry = slot.as_ref()?.entries.iter().find(|e| e.id == id)?;
    if entry.label.is_empty() {
        return None;
    }
    Some(entry.label.clone())
}

/// A stock button's icon from the profile (None: draw the label).
pub(super) fn icon(id: &str) -> Option<String> {
    let slot = slot();
    let entry = slot.as_ref()?.entries.iter().find(|e| e.id == id)?;
    entry.icon.clone()
}

/// An extra control's icon (None: draw the label).
pub(super) fn extra_icon(index: usize) -> Option<String> {
    let slot = slot();
    slot.as_ref()?.entries.get(index)?.icon.clone()
}

/// Draws a built-in pixel icon centred on (x, y) with the button's radius.
pub(super) fn draw_icon(
    batch: &mut super::hud::HudBatch,
    atlas: &super::hud::HudAtlas,
    name: &str,
    x: f32,
    y: f32,
    r: f32,
    color: u32,
) {
    let Some(rows) = bitmap(name) else {
        return;
    };
    let size = rows.len() as f32;
    let pixel = (r * 2.0 * 0.92 / size).max(1.0);
    let width = rows.first().map_or(0.0, |row| row.len() as f32) * pixel;
    let left = x - width / 2.0;
    let top = y - size * pixel / 2.0;
    for (row, line) in rows.iter().enumerate() {
        for (column, mark) in line.chars().enumerate() {
            if mark == '#' {
                batch.fill(
                    atlas,
                    left + column as f32 * pixel,
                    top + row as f32 * pixel,
                    pixel,
                    pixel,
                    color,
                );
            }
        }
    }
}

/// The icons, 12 × 12 pixels, as close to Bedrock's as drawing rectangles allows.
fn bitmap(name: &str) -> Option<&'static [&'static str]> {
    Some(match name {
        "jump" | "fly_up" => &[
            ".....##.....",
            "....####....",
            "...##.##....",
            "..##..##....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            "..########..",
            "..########..",
            "............",
        ],
        "sneak" | "fly_down" => &[
            "............",
            "..########..",
            "..########..",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            ".....##.....",
            "..##..##....",
            "...##.##....",
            "....####....",
            ".....##.....",
        ],
        "sprint" => &[
            "............",
            "..##....##..",
            "...##....##.",
            "....##....##",
            "....##....##",
            "...##....##.",
            "..##....##..",
            "............",
            "............",
            "............",
            "............",
            "............",
        ],
        "attack" => &[
            "........####",
            ".......####.",
            "......####..",
            ".....####...",
            "....####....",
            "...####.....",
            "..####......",
            ".####.......",
            "####........",
            ".##.........",
            ".#..........",
            "............",
        ],
        "inventory" => &[
            "..########..",
            "..#......#..",
            "..#.####.#..",
            "..########..",
            "..#.#..#.#..",
            "..#.#..#.#..",
            "..#.#..#.#..",
            "..########..",
            "............",
            "............",
            "............",
            "............",
        ],
        "chat" => &[
            ".##########.",
            ".#........#.",
            ".#.##.##..#.",
            ".#........#.",
            ".#.##.##..#.",
            ".#........#.",
            ".##########.",
            "...##.......",
            "..##........",
            "............",
            "............",
            "............",
        ],
        "pause" => &[
            "...##..##...",
            "...##..##...",
            "...##..##...",
            "...##..##...",
            "...##..##...",
            "...##..##...",
            "...##..##...",
            "...##..##...",
            "............",
            "............",
            "............",
            "............",
        ],
        "view" => &[
            "............",
            "...######...",
            "..#..##..#..",
            ".#..####..#.",
            ".#..####..#.",
            "..#..##..#..",
            "...######...",
            "............",
            "............",
            "............",
            "............",
            "............",
        ],
        "mode" => &[
            "....##......",
            "....##......",
            "....##......",
            "..######....",
            "..######....",
            "..######....",
            "..######....",
            "..######....",
            "............",
            "............",
            "............",
            "............",
        ],
        "lock" => &[
            "....####....",
            "...##..##...",
            "...##..##...",
            "..########..",
            "..#......#..",
            "..#..##..#..",
            "..#..##..#..",
            "..#......#..",
            "..########..",
            "............",
            "............",
            "............",
        ],
        "debug" => &[
            "............",
            "..######....",
            ".#..##..#...",
            ".#.####.#...",
            ".#.####.#...",
            ".#..##..#...",
            "..######....",
            "....##......",
            "............",
            "............",
            "............",
            "............",
        ],
        _ => return None,
    })
}

/// Whether the profile shows a stock button in this mode (None: the game decides).
pub(super) fn visible(id: &str, tap: bool) -> Option<bool> {
    let slot = slot();
    let entry = slot.as_ref()?.entries.iter().find(|e| e.id == id)?;
    if entry.modes.is_empty() {
        return None;
    }
    Some(shows(&entry.modes, tap))
}

fn shows(modes: &[String], tap: bool) -> bool {
    let mode = if tap { "tap" } else { "aim" };
    modes.iter().any(|m| m == mode)
}

/// The extra controls (those with an action) to draw, with their places in GUI units.
pub(super) fn extras(w: f32, h: f32, tap: bool) -> Vec<(usize, f32, f32, f32)> {
    let slot = slot();
    let Some(layout) = slot.as_ref() else {
        return Vec::new();
    };
    layout
        .entries
        .iter()
        .enumerate()
        .filter(|(_, e)| !matches!(e.action, Action::Builtin(_)))
        .filter(|(_, e)| e.modes.is_empty() || shows(&e.modes, tap))
        .map(|(i, e)| (i, e.x * w, e.y * h, e.r))
        .collect()
}

/// An extra control's label.
pub(super) fn text(index: usize) -> String {
    let slot = slot();
    let Some(layout) = slot.as_ref() else {
        return String::new();
    };
    layout
        .entries
        .get(index)
        .map(|e| e.label.clone())
        .unwrap_or_default()
}

/// An extra control's action.
pub(super) fn action(index: usize) -> Option<Action> {
    let slot = slot();
    let layout = slot.as_ref()?;
    Some(layout.entries.get(index)?.action.clone())
}

/// Switches to the next (or previous) profile and returns its name.
pub(super) fn switch(direction: &str) -> Option<String> {
    let files = profiles();
    if files.is_empty() {
        return None;
    }
    let mut slot = slot();
    let current = slot.as_ref().map(|l| l.file.clone());
    let index = current
        .and_then(|file| files.iter().position(|p| *p == file))
        .unwrap_or(0);
    let next = if direction == "prev" {
        (index + files.len() - 1) % files.len()
    } else {
        (index + 1) % files.len()
    };
    let path = files[next].clone();
    let name = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_default();
    let _ = std::fs::create_dir_all("touch");
    let _ = std::fs::write(ACTIVE, &name);
    *slot = parse(&path);
    Some(name)
}

/// A key by its name in a profile.
pub(super) fn keycode(name: &str) -> Option<KeyCode> {
    let name = name.to_ascii_lowercase();
    Some(match name.as_str() {
        "space" | "jump" => KeyCode::Space,
        "shift" | "sneak" => KeyCode::ShiftLeft,
        "ctrl" | "control" | "sprint" => KeyCode::ControlLeft,
        "alt" => KeyCode::AltLeft,
        "esc" | "escape" => KeyCode::Escape,
        "enter" | "return" => KeyCode::Enter,
        "tab" => KeyCode::Tab,
        "backspace" => KeyCode::Backspace,
        "up" => KeyCode::ArrowUp,
        "down" => KeyCode::ArrowDown,
        "left" => KeyCode::ArrowLeft,
        "right" => KeyCode::ArrowRight,
        "f1" => KeyCode::F1,
        "f2" => KeyCode::F2,
        "f3" => KeyCode::F3,
        "f4" => KeyCode::F4,
        "f5" => KeyCode::F5,
        "f6" => KeyCode::F6,
        "f7" => KeyCode::F7,
        "f8" => KeyCode::F8,
        "f9" => KeyCode::F9,
        "f10" => KeyCode::F10,
        "f11" => KeyCode::F11,
        "f12" => KeyCode::F12,
        "a" => KeyCode::KeyA,
        "b" => KeyCode::KeyB,
        "c" => KeyCode::KeyC,
        "d" => KeyCode::KeyD,
        "e" => KeyCode::KeyE,
        "f" => KeyCode::KeyF,
        "g" => KeyCode::KeyG,
        "h" => KeyCode::KeyH,
        "i" => KeyCode::KeyI,
        "j" => KeyCode::KeyJ,
        "k" => KeyCode::KeyK,
        "l" => KeyCode::KeyL,
        "m" => KeyCode::KeyM,
        "n" => KeyCode::KeyN,
        "o" => KeyCode::KeyO,
        "p" => KeyCode::KeyP,
        "q" => KeyCode::KeyQ,
        "r" => KeyCode::KeyR,
        "s" => KeyCode::KeyS,
        "t" => KeyCode::KeyT,
        "u" => KeyCode::KeyU,
        "v" => KeyCode::KeyV,
        "w" => KeyCode::KeyW,
        "x" => KeyCode::KeyX,
        "y" => KeyCode::KeyY,
        "z" => KeyCode::KeyZ,
        "0" => KeyCode::Digit0,
        "1" => KeyCode::Digit1,
        "2" => KeyCode::Digit2,
        "3" => KeyCode::Digit3,
        "4" => KeyCode::Digit4,
        "5" => KeyCode::Digit5,
        "6" => KeyCode::Digit6,
        "7" => KeyCode::Digit7,
        "8" => KeyCode::Digit8,
        "9" => KeyCode::Digit9,
        _ => return None,
    })
}
