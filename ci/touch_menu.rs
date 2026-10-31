//! The touch controls' own overlay: a small "profiles" button and the panel it opens.
//!
//! It is drawn by the touch layer, not by the game's menus, and works in the world **and**
//! in menus, so a layout can be switched or edited without leaving the screen you are on.
//! Everything is a rectangle and a line of text: no textures, no font beyond the game's.
use std::sync::{Mutex, MutexGuard, OnceLock};

use horizon_client::hud::{HudAtlas, HudBatch};

use super::touch_layout;

/// What a tap on the overlay asked for.
#[derive(Clone, Debug, PartialEq)]
pub(super) enum Act {
    Toggle,
    Close,
    Activate(String),
    Mode,
    Bedrock,
    Page(i32),
}

#[derive(Default)]
struct Ui {
    open: bool,
    page: usize,
    /// Список профилей и активный — читаются с диска только при открытии/смене,
    /// а не каждый кадр.
    names: Vec<String>,
    active: String,
}

/// Re-reads the profiles from the game folder (after a switch or a write).
pub(super) fn refresh() {
    let mut ui = state();
    ui.names = touch_layout::names();
    ui.active = touch_layout::active();
    ui.page = ui.page.min(ui.names.len().saturating_sub(1) / ROWS);
}

fn state() -> MutexGuard<'static, Ui> {
    static UI: OnceLock<Mutex<Ui>> = OnceLock::new();
    UI.get_or_init(|| Mutex::new(Ui::default()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

const TEXT: u32 = 0xF0FF_FFFF;
const DIM: u32 = 0xA0FF_FFFF;
const PANEL: u32 = 0xE61014_18;
const EDGE: u32 = 0x50FF_FFFF;
const MINT: u32 = 0xFF9B_E8BE;
const PRESSED: u32 = 0x40FF_FFFF;
/// Rows of the profile list on one page.
const ROWS: usize = 5;

/// Where everything is, in GUI units: shared by drawing and by hit tests.
struct Rects {
    button: (f32, f32, f32), // x, y, size
    panel: (f32, f32, f32, f32),
    mode: (f32, f32, f32, f32),
    prev: (f32, f32, f32, f32),
    next: (f32, f32, f32, f32),
    bedrock: (f32, f32, f32, f32),
    close: (f32, f32, f32, f32),
    rows: Vec<(f32, f32, f32, f32)>,
}

fn rects(w: f32, h: f32) -> Rects {
    let size = 34.0_f32;
    let button = (14.0, 14.0, size);
    let pw = (w * 0.46).clamp(300.0, 470.0);
    let px = 14.0;
    let py = 14.0;
    let head = 66.0;
    let row = 34.0;
    let rows_h = row * ROWS as f32;
    let foot = 40.0;
    let ph = (head + rows_h + foot).min(h - 2.0 * py);
    let panel = (px, py, pw, ph);
    let mut rows = Vec::with_capacity(ROWS);
    for i in 0..ROWS {
        rows.push((px + 8.0, py + head + row * i as f32, pw - 16.0, row - 4.0));
    }
    let fw = (pw - 40.0) / 4.0;
    let fy = py + ph - foot + 4.0;
    Rects {
        button,
        panel,
        mode: (px + 8.0, py + 30.0, fw, 26.0),
        prev: (px + 8.0 + fw + 8.0, fy, fw, 26.0),
        next: (px + 8.0 + 2.0 * (fw + 8.0), fy, fw, 26.0),
        bedrock: (px + 8.0 + 3.0 * (fw + 8.0), fy, fw, 26.0),
        close: (px + pw - 34.0, py + 6.0, 28.0, 22.0),
        rows,
    }
}

fn inside((x, y, w, h): (f32, f32, f32, f32), at: (f32, f32)) -> bool {
    (x..x + w).contains(&at.0) && (y..y + h).contains(&at.1)
}

fn block(batch: &mut HudBatch, atlas: &HudAtlas, r: (f32, f32, f32, f32), color: u32) {
    batch.fill(atlas, r.0, r.1, r.2, r.3, color);
}

fn label(batch: &mut HudBatch, atlas: &HudAtlas, text: &str, x: f32, y: f32, color: u32) {
    batch.text(atlas, text, x, y, color, true);
}

/// Draws the button (always) and the panel (when open). Works in menus too.
pub(super) fn draw(batch: &mut HudBatch, atlas: &HudAtlas, w: f32, h: f32, tap_mode: bool) {
    let r = rects(w, h);
    let open = state().open;
    if !open {
        let (bx, by, size) = r.button;
        block(batch, atlas, (bx, by, size, size), PANEL);
        block(batch, atlas, (bx, by, size, 1.5), EDGE);
        // «≡»: три полоски вместо шрифта с глифами
        for i in 0..3 {
            block(
                batch,
                atlas,
                (bx + 8.0, by + 9.0 + 7.0 * i as f32, size - 16.0, 3.0),
                TEXT,
            );
        }
        return;
    }

    let (px, py, pw, ph) = r.panel;
    block(batch, atlas, (px, py, pw, ph), PANEL);
    block(batch, atlas, (px, py, pw, 1.5), EDGE);
    label(batch, atlas, "PROFILES", px + 8.0, py + 10.0, MINT);
    label(
        batch,
        atlas,
        if tap_mode { "mode: Tap" } else { "mode: Aim" },
        px + pw - 110.0,
        py + 10.0,
        DIM,
    );

    // кнопка режима
    block(batch, atlas, r.mode, PRESSED);
    label(
        batch,
        atlas,
        if tap_mode { "Switch to Aim" } else { "Switch to Tap" },
        r.mode.0 + 6.0,
        r.mode.1 + 8.0,
        TEXT,
    );
    // закрыть
    block(batch, atlas, r.close, PRESSED);
    label(batch, atlas, "X", r.close.0 + 10.0, r.close.1 + 6.0, TEXT);

    let (all, active, page) = {
        let ui = state();
        (ui.names.clone(), ui.active.clone(), ui.page)
    };
    let start = page * ROWS;
    for (i, slot) in r.rows.iter().enumerate() {
        let Some(name) = all.get(start + i) else {
            break;
        };
        let is_active = *name == active;
        block(
            batch,
            atlas,
            *slot,
            if is_active { 0x3022_C08C } else { PRESSED },
        );
        let color = if is_active { MINT } else { TEXT };
        let text = if is_active {
            format!("* {name}")
        } else {
            name.clone()
        };
        label(batch, atlas, &text, slot.0 + 8.0, slot.1 + 9.0, color);
    }
    if all.len() > ROWS {
        label(
            batch,
            atlas,
            &format!("{}/{}", page + 1, all.len().div_ceil(ROWS)),
            px + 8.0,
            py + ph - 34.0 + 2.0,
            DIM,
        );
    }

    block(batch, atlas, r.prev, PRESSED);
    label(batch, atlas, "<", r.prev.0 + 10.0, r.prev.1 + 6.0, TEXT);
    block(batch, atlas, r.next, PRESSED);
    label(batch, atlas, ">", r.next.0 + 10.0, r.next.1 + 6.0, TEXT);
    block(batch, atlas, r.bedrock, PRESSED);
    label(batch, atlas, "BEDROCK", r.bedrock.0 + 6.0, r.bedrock.1 + 6.0, TEXT);
}

/// The tap was on the overlay: what it means (None: the touch is the game's).
pub(super) fn tap(at: (f32, f32), w: f32, h: f32) -> Option<Act> {
    let r = rects(w, h);
    let mut ui = state();
    if !ui.open {
        let (bx, by, size) = r.button;
        if inside((bx, by, size, size), at) {
            ui.open = true;
            ui.names = touch_layout::names();
            ui.active = touch_layout::active();
            return Some(Act::Toggle);
        }
        return None;
    }
    if inside(r.close, at) {
        ui.open = false;
        return Some(Act::Close);
    }
    if inside(r.mode, at) {
        return Some(Act::Mode);
    }
    if inside(r.prev, at) {
        ui.page = ui.page.saturating_sub(1);
        return Some(Act::Page(-1));
    }
    if inside(r.next, at) {
        let last = ui.names.len().saturating_sub(1) / ROWS;
        ui.page = (ui.page + 1).min(last);
        return Some(Act::Page(1));
    }
    if inside(r.bedrock, at) {
        return Some(Act::Bedrock);
    }
    let page = ui.page;
    for (i, slot) in r.rows.iter().enumerate() {
        if inside(*slot, at) {
            if let Some(name) = ui.names.get(page * ROWS + i) {
                let name = name.clone();
                ui.open = false;
                return Some(Act::Activate(name));
            }
        }
    }
    // внутри панели, но мимо кнопок: тап не для игры
    if inside(r.panel, at) {
        return Some(Act::Page(0));
    }
    // мимо панели: закрыть и не пускать тап в мир
    ui.open = false;
    Some(Act::Page(0))
}
