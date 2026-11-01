//! The touch controls' own overlay: a small "gear" pill at the top and the panel it opens.
//!
//! Drawn by the touch layer, not by the game's menus, so it works in the world **and** in
//! menus. Material-3-Expressive in spirit: tonal containers, big corner radii, one accent
//! colour, a scrolling list. No textures and no fonts beyond the game's own.
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
    Scroll(i32),
}

#[derive(Default)]
struct Ui {
    open: bool,
    /// First profile shown in the list.
    scroll: usize,
    names: Vec<String>,
    active: String,
}

fn state() -> MutexGuard<'static, Ui> {
    static UI: OnceLock<Mutex<Ui>> = OnceLock::new();
    UI.get_or_init(|| Mutex::new(Ui::default()))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

const TEXT: u32 = 0xF2FF_FFFF;
const DIM: u32 = 0x9CFF_FFFF;
const PANEL: u32 = 0xF01114_18;
const FILL: u32 = 0x24FF_FFFF;
const MINT: u32 = 0xFF9B_E8BE;
const MINT_DIM: u32 = 0x3322_C08C;
const INK: u32 = 0xFF0B12_0E;
/// Rows of the list on screen at once.
const ROWS: usize = 6;

/// Re-reads the profiles from the game folder (after a switch or a write).
pub(super) fn refresh() {
    let mut ui = state();
    ui.names = touch_layout::names();
    ui.active = touch_layout::active();
    ui.scroll = ui.scroll.min(ui.names.len().saturating_sub(ROWS));
}

/* --------------------------------- фигуры --------------------------------- */

/// A rounded rectangle out of solid rectangles: enough for the expressive look.
fn round_rect(
    batch: &mut HudBatch,
    atlas: &HudAtlas,
    (x, y, w, h): (f32, f32, f32, f32),
    radius: f32,
    color: u32,
) {
    let steps = 4;
    let radius = radius.min(w / 2.0).min(h / 2.0);
    for i in 0..steps {
        let t = (i as f32 + 0.5) / steps as f32;
        // сколько срезать по бокам на этом слое
        let inset = radius * (1.0 - (1.0 - t * t).sqrt());
        batch.fill(atlas, x + inset, y + radius * t, w - 2.0 * inset, h - 2.0 * radius * t, color);
    }
    batch.fill(atlas, x, y + radius, w, h - 2.0 * radius, color);
}

fn label(batch: &mut HudBatch, atlas: &HudAtlas, text: &str, x: f32, y: f32, color: u32) {
    batch.text(atlas, text, x, y, color, true);
}

fn label_centred(batch: &mut HudBatch, atlas: &HudAtlas, text: &str, cx: f32, y: f32, color: u32) {
    let w = HudBatch::text_width(atlas, text);
    batch.text(atlas, text, cx - w / 2.0, y, color, true);
}

/// A gear: a ring of four teeth around a hole.
fn gear(batch: &mut HudBatch, atlas: &HudAtlas, cx: f32, cy: f32, size: f32, color: u32) {
    let tooth = size * 0.26;
    for (dx, dy) in [(0.0, -0.42), (0.0, 0.42), (-0.42, 0.0), (0.42, 0.0)] {
        round_rect(
            batch,
            atlas,
            (cx + dx * size - tooth / 2.0, cy + dy * size - tooth / 2.0, tooth, tooth),
            tooth * 0.35,
            color,
        );
    }
    round_rect(
        batch,
        atlas,
        (cx - size * 0.30, cy - size * 0.30, size * 0.60, size * 0.60),
        size * 0.18,
        color,
    );
    round_rect(
        batch,
        atlas,
        (cx - size * 0.12, cy - size * 0.12, size * 0.24, size * 0.24),
        size * 0.07,
        INK,
    );
}

/* ------------------------------- раскладка -------------------------------- */

struct Rects {
    pill: (f32, f32, f32, f32),
    panel: (f32, f32, f32, f32),
    mode: (f32, f32, f32, f32),
    up: (f32, f32, f32, f32),
    down: (f32, f32, f32, f32),
    bedrock: (f32, f32, f32, f32),
    close: (f32, f32, f32, f32),
    rows: Vec<(f32, f32, f32, f32)>,
}

fn rects(w: f32, h: f32) -> Rects {
    // кнопка: маленькая «капелька» сверху слева
    let pw = 52.0;
    let ph = 34.0;
    let pill = (14.0, 12.0, pw, ph);

    let width = (w * 0.42).clamp(280.0, 400.0);
    let row = 32.0;
    let header = 62.0;
    let footer = 38.0;
    let height = (header + row * ROWS as f32 + footer).min(h - 90.0);
    let panel = (14.0, 54.0, width, height);

    let mut rows = Vec::with_capacity(ROWS);
    for i in 0..ROWS {
        rows.push((panel.0 + 10.0, panel.1 + header + row * i as f32, width - 20.0, row - 4.0));
    }
    let btn = 26.0;
    let fy = panel.1 + height - footer + 6.0;
    Rects {
        pill,
        panel,
        mode: (panel.0 + 10.0, panel.1 + 30.0, width - 20.0, 24.0),
        up: (panel.0 + width - 2.0 * (btn + 6.0) - 6.0, fy, btn, 22.0),
        down: (panel.0 + width - (btn + 6.0) - 6.0, fy, btn, 22.0),
        bedrock: (panel.0 + 10.0, fy, (width - 30.0) * 0.45, 22.0),
        close: (panel.0 + 10.0 + (width - 30.0) * 0.47, fy, (width - 30.0) * 0.30, 22.0),
        rows,
    }
}

fn inside((x, y, w, h): (f32, f32, f32, f32), at: (f32, f32)) -> bool {
    (x..x + w).contains(&at.0) && (y..y + h).contains(&at.1)
}

/* -------------------------------- отрисовка ------------------------------- */

pub(super) fn draw(batch: &mut HudBatch, atlas: &HudAtlas, w: f32, h: f32, tap_mode: bool) {
    let r = rects(w, h);
    let (open, names, active, scroll) = {
        let ui = state();
        (ui.open, ui.names.clone(), ui.active.clone(), ui.scroll)
    };

    // кнопка-капелька с шестерёнкой
    round_rect(batch, atlas, r.pill, 16.0, PANEL);
    gear(
        batch,
        atlas,
        r.pill.0 + r.pill.2 / 2.0,
        r.pill.1 + r.pill.3 / 2.0,
        r.pill.3 * 0.62,
        if open { MINT } else { TEXT },
    );
    if !open {
        return;
    }

    round_rect(batch, atlas, r.panel, 20.0, PANEL);
    label(batch, atlas, "Controls", r.panel.0 + 12.0, r.panel.1 + 10.0, MINT);

    // сегмент режима
    round_rect(batch, atlas, r.mode, 12.0, FILL);
    label_centred(
        batch,
        atlas,
        if tap_mode { "Tap mode — tap to switch" } else { "Aim mode — tap to switch" },
        r.mode.0 + r.mode.2 / 2.0,
        r.mode.1 + 6.0,
        TEXT,
    );

    for (i, slot) in r.rows.iter().enumerate() {
        let Some(name) = names.get(scroll + i) else { break };
        let is_active = *name == active;
        round_rect(
            batch,
            atlas,
            *slot,
            if is_active { 12.0 } else { 12.0 },
            if is_active { MINT_DIM } else { FILL },
        );
        let text = if is_active { format!("* {name}") } else { name.clone() };
        label(
            batch,
            atlas,
            &text,
            slot.0 + 10.0,
            slot.1 + 8.0,
            if is_active { MINT } else { TEXT },
        );
    }
    if names.is_empty() {
        label(batch, atlas, "no profiles", r.rows[0].0 + 10.0, r.rows[0].1 + 8.0, DIM);
    }

    round_rect(batch, atlas, r.bedrock, 10.0, FILL);
    label(batch, atlas, "Bedrock", r.bedrock.0 + 8.0, r.bedrock.1 + 6.0, TEXT);
    round_rect(batch, atlas, r.close, 10.0, FILL);
    label(batch, atlas, "Close", r.close.0 + 8.0, r.close.1 + 6.0, TEXT);
    round_rect(batch, atlas, r.up, 10.0, FILL);
    label_centred(batch, atlas, "^", r.up.0 + r.up.2 / 2.0, r.up.1 + 5.0, TEXT);
    round_rect(batch, atlas, r.down, 10.0, FILL);
    label_centred(batch, atlas, "v", r.down.0 + r.down.2 / 2.0, r.down.1 + 5.0, TEXT);
}

/* --------------------------------- тапы ---------------------------------- */

pub(super) fn tap(at: (f32, f32), w: f32, h: f32) -> Option<Act> {
    let r = rects(w, h);
    let mut ui = state();
    if inside(r.pill, at) {
        ui.open = !ui.open;
        if ui.open {
            ui.names = touch_layout::names();
            ui.active = touch_layout::active();
        }
        return Some(Act::Toggle);
    }
    if !ui.open {
        return None;
    }
    if inside(r.close, at) {
        ui.open = false;
        return Some(Act::Close);
    }
    if inside(r.mode, at) {
        return Some(Act::Mode);
    }
    if inside(r.up, at) {
        ui.scroll = ui.scroll.saturating_sub(1);
        return Some(Act::Scroll(-1));
    }
    if inside(r.down, at) {
        let last = ui.names.len().saturating_sub(ROWS);
        ui.scroll = (ui.scroll + 1).min(last);
        return Some(Act::Scroll(1));
    }
    if inside(r.bedrock, at) {
        return Some(Act::Bedrock);
    }
    let scroll = ui.scroll;
    for (i, slot) in r.rows.iter().enumerate() {
        if inside(*slot, at) {
            if let Some(name) = ui.names.get(scroll + i) {
                let name = name.clone();
                ui.open = false;
                return Some(Act::Activate(name));
            }
        }
    }
    // внутри панели мимо кнопок: тап не для игры
    if inside(r.panel, at) {
        return Some(Act::Scroll(0));
    }
    ui.open = false;
    Some(Act::Close)
}
