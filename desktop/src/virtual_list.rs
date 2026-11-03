//! Long lists that build only the rows intersecting the viewport.
use eframe::egui::{self, Id, Rect, UiBuilder, Vec2};
use std::{collections::HashMap, hash::Hash, ops::Range};

/// Strides closer than this are equal; egui rounds layout to 1/32 point.
const TOLERANCE: f32 = 0.05;

/// Measured row strides (height plus item spacing) of a list drawn inside an
/// enclosing scroll area. A frame builds only the rows intersecting the clip
/// rectangle and reserves the cached extent of the others, so its cost does
/// not grow with the length of the list.
///
/// Rows are re-measured whenever they are shown. After a layout change (width,
/// scale, style, language or display settings), rows that have not been shown
/// again use the stride measured for a shown row of the same caller-defined
/// kind, so resizing never lays out offscreen rows.
///
/// Rows that are not built are absent from keyboard navigation and the
/// accessibility tree. [`Self::eager_up_to`] keeps short lists fully built.
pub struct VirtualRows<K, L> {
    eager: usize,
    revision: Option<u64>,
    layout: Option<L>,
    generation: u64,
    keys: Vec<K>,
    /// Latest stride of each row and the layout generation it was measured
    /// in; generation 0 marks a row that was never shown.
    strides: Vec<(f32, u64)>,
    /// First stride measured for each kind in a layout generation. It stays
    /// fixed within the generation, so rows of one kind with different
    /// heights cannot make the list re-plan every frame.
    estimates: HashMap<u64, (f32, u64)>,
    latest: Option<f32>,
    offsets: Vec<f32>,
    dirty: bool,
}

/// Rows chosen by [`VirtualRows::plan`] for one frame.
pub struct Plan {
    top: f32,
    visible: Range<usize>,
    kinds: Vec<u64>,
    keep: Option<(usize, u64)>,
}

impl<K, L> Default for VirtualRows<K, L> {
    fn default() -> Self {
        Self {
            eager: 0,
            revision: None,
            layout: None,
            generation: 1,
            keys: Vec::new(),
            strides: Vec::new(),
            estimates: HashMap::new(),
            latest: None,
            offsets: vec![0.],
            dirty: true,
        }
    }
}

impl<K: Clone + Eq + Hash + std::fmt::Debug, L: PartialEq> VirtualRows<K, L> {
    /// Build every row of lists with at most `rows` rows.
    pub fn eager_up_to(rows: usize) -> Self {
        Self {
            eager: rows,
            ..Self::default()
        }
    }

    /// Synchronize the rows. `revision` must change whenever the keys or their
    /// order change; `keys` is only called then. Measurements follow their key.
    pub fn update(&mut self, revision: u64, layout: L, keys: impl FnOnce() -> Vec<K>) {
        if self.revision != Some(revision) {
            let previous: HashMap<K, (f32, u64)> =
                self.keys.drain(..).zip(self.strides.drain(..)).collect();
            self.keys = keys();
            self.strides = self
                .keys
                .iter()
                .map(|key| previous.get(key).copied().unwrap_or((0., 0)))
                .collect();
            self.revision = Some(revision);
            self.dirty = true;
        }
        if self.layout.as_ref() != Some(&layout) {
            self.layout = Some(layout);
            self.generation += 1;
            self.dirty = true;
        }
    }

    /// Choose the rows to build at the cursor of `ui`. `kind(index)` groups rows
    /// expected to share a height; it is only called for rows that are shown or
    /// whose estimate is recomputed. `keep` names a row whose widget state, such
    /// as an open menu, must survive while it is scrolled out of view.
    pub fn plan(&mut self, ui: &egui::Ui, keep: Option<&K>, kind: impl Fn(usize) -> u64) -> Plan {
        if self.dirty {
            let fallback = self
                .latest
                .unwrap_or_else(|| ui.spacing().interact_size.y + ui.spacing().item_spacing.y);
            self.offsets.clear();
            self.offsets.push(0.);
            let mut total = 0.;
            for (index, &(stride, generation)) in self.strides.iter().enumerate() {
                total += if generation == self.generation {
                    stride
                } else {
                    match self.estimates.get(&kind(index)) {
                        Some(&(estimate, current)) if current == self.generation => estimate,
                        _ if generation != 0 => stride,
                        Some(&(estimate, _)) => estimate,
                        None => fallback,
                    }
                };
                self.offsets.push(total);
            }
            self.dirty = false;
        }
        let top = ui.cursor().min.y;
        let clip = ui.clip_rect();
        let count = self.keys.len();
        let (first, last) = if count <= self.eager {
            (0, count)
        } else {
            let first = self.offsets[1..].partition_point(|end| *end <= clip.top() - top);
            let last = self.offsets[..count]
                .partition_point(|start| *start < clip.bottom() - top)
                .max(first);
            (first, last)
        };
        let keep = keep
            .and_then(|key| self.keys.iter().position(|candidate| candidate == key))
            .filter(|index| !(first..last).contains(index))
            .map(|index| (index, kind(index)));
        Plan {
            top,
            visible: first..last,
            kinds: (first..last).map(kind).collect(),
            keep,
        }
    }

    /// Reserve the whole list and build `row` for each planned row. Every row
    /// gets an id derived from `id` and its key, wherever it is placed. Call
    /// this right after [`Self::plan`], before adding anything else to `ui`.
    pub fn show(
        &mut self,
        ui: &mut egui::Ui,
        id: Id,
        plan: Plan,
        mut row: impl FnMut(&mut egui::Ui, usize),
    ) {
        // One scope with a fixed id: widgets after the list keep the same
        // automatic ids however many rows were built.
        let moved = ui
            .scope_builder(UiBuilder::new().id(id), |ui| {
                let count = self.keys.len();
                let spacing = ui.spacing().item_spacing.y;
                let columns = ui.available_rect_before_wrap().x_range();
                let Range { start, end } = plan.visible;
                // Skipped rows end with item spacing, like rows that were built.
                if start > 0 {
                    ui.allocate_space(Vec2::new(0., (self.offsets[start] - spacing).max(0.)));
                }
                let mut moved = false;
                for (index, kind) in (start..end).zip(plan.kinds) {
                    let before = ui.cursor().min.y;
                    ui.scope_builder(UiBuilder::new().id(id.with(&self.keys[index])), |ui| {
                        row(ui, index)
                    });
                    moved |= self.record(index, kind, ui.cursor().min.y - before);
                }
                if end < count {
                    let rest = self.offsets[count] - self.offsets[end] - spacing;
                    ui.allocate_space(Vec2::new(0., rest.max(0.)));
                }
                if let Some((index, kind)) = plan.keep {
                    // Built in place, outside the flow, so the list does not move.
                    let rect = Rect::from_x_y_ranges(
                        columns,
                        plan.top + self.offsets[index]..=plan.top + self.offsets[index + 1],
                    );
                    let mut kept = ui.new_child(
                        UiBuilder::new()
                            .id(id.with(&self.keys[index]))
                            .max_rect(rect),
                    );
                    row(&mut kept, index);
                    moved |= self.record(index, kind, kept.min_rect().height() + spacing);
                }
                moved
            })
            .inner;
        if moved {
            self.dirty = true;
            // Re-plan immediately rather than show rows at estimated offsets.
            ui.ctx().request_discard("virtual list rows changed height");
            if !ui.ctx().will_discard() {
                ui.ctx().request_repaint();
            }
        }
    }

    /// Store a measured stride. Returns whether the planned offsets were wrong.
    fn record(&mut self, index: usize, kind: u64, stride: f32) -> bool {
        let planned = self.offsets[index + 1] - self.offsets[index];
        self.strides[index] = (stride, self.generation);
        self.latest = Some(stride);
        let estimate = self.estimates.entry(kind).or_insert((stride, 0));
        let mut moved = (planned - stride).abs() > TOLERANCE;
        if estimate.1 != self.generation {
            // Unshown rows of this kind are re-estimated from this row.
            moved |= (estimate.0 - stride).abs() > TOLERANCE;
            *estimate = (stride, self.generation);
        }
        moved
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    const VIEWPORT: f32 = 300.;
    /// Rows that fit in the viewport at the smallest stride (20 + 8), plus
    /// the partially visible rows at both edges.
    const VISIBLE: usize = (VIEWPORT / 28.) as usize + 2;

    struct Rows<'a> {
        keys: &'a [u32],
        revision: u64,
        layout: f32,
        height: &'a dyn Fn(u32) -> f32,
        kind: &'a dyn Fn(u32) -> u64,
    }

    struct Frame {
        /// Rows built in the shown pass, with their widget ids.
        built: Vec<(u32, Id)>,
        /// Rows built in every pass of the frame, including discarded passes.
        work: usize,
        extent: f32,
    }

    /// Item spacing is 8 points, so a row's stride is its height plus 8.
    fn render(
        ctx: &egui::Context,
        list: &mut VirtualRows<u32, f32>,
        rows: &Rows<'_>,
        keep: Option<u32>,
        scroll: Option<f32>,
    ) -> Frame {
        let built = RefCell::new(Vec::new());
        let mut work = 0;
        let mut extent = 0.;
        let mut output = ctx.run_ui(
            egui::RawInput {
                screen_rect: Some(Rect::from_min_size(egui::Pos2::ZERO, Vec2::splat(400.))),
                ..Default::default()
            },
            |ui| {
                built.borrow_mut().clear();
                let mut area = egui::ScrollArea::vertical().max_height(VIEWPORT);
                if let Some(offset) = scroll {
                    area = area.vertical_scroll_offset(offset);
                }
                extent = area
                    .show(ui, |ui| {
                        ui.spacing_mut().item_spacing.y = 8.;
                        list.update(rows.revision, rows.layout, || rows.keys.to_vec());
                        let plan =
                            list.plan(ui, keep.as_ref(), |index| (rows.kind)(rows.keys[index]));
                        list.show(ui, Id::new("rows"), plan, |ui, index| {
                            let key = rows.keys[index];
                            built.borrow_mut().push((key, ui.id()));
                            work += 1;
                            let size = Vec2::new(100., (rows.height)(key));
                            ui.allocate_exact_size(size, egui::Sense::hover());
                        });
                    })
                    .content_size
                    .y;
            },
        );
        output.textures_delta.clear();
        Frame {
            built: built.into_inner(),
            work,
            extent,
        }
    }

    fn settle(
        ctx: &egui::Context,
        list: &mut VirtualRows<u32, f32>,
        rows: &Rows<'_>,
        scroll: Option<f32>,
    ) -> Frame {
        for _ in 0..3 {
            render(ctx, list, rows, None, scroll);
        }
        render(ctx, list, rows, None, scroll)
    }

    #[test]
    fn long_lists_build_visible_rows_and_reserve_the_exact_extent() {
        let ctx = egui::Context::default();
        let keys: Vec<u32> = (0..5000).collect();
        let rows = Rows {
            keys: &keys,
            revision: 0,
            layout: 0.,
            height: &|_| 20.,
            kind: &|_| 0,
        };
        let mut list = VirtualRows::default();
        let first = render(&ctx, &mut list, &rows, None, None);
        assert!(first.work <= 2 * VISIBLE, "{}", first.work);
        let frame = settle(&ctx, &mut list, &rows, None);
        assert_eq!(frame.built.first().map(|(key, _)| *key), Some(0));
        assert!(frame.work <= VISIBLE, "{}", frame.work);
        // The same extent as 5000 rows built one after another: 5000 strides
        // without the item spacing after the last row.
        assert!(
            (frame.extent - (5000. * 28. - 8.)).abs() < 0.5,
            "{}",
            frame.extent
        );

        let scrolled = settle(&ctx, &mut list, &rows, Some(28. * 4000.));
        assert_eq!(scrolled.built.first().map(|(key, _)| *key), Some(4000));
        assert!(scrolled.work <= VISIBLE);
        assert!((scrolled.extent - frame.extent).abs() < 0.5);
    }

    #[test]
    fn short_lists_can_stay_fully_built() {
        let ctx = egui::Context::default();
        let keys: Vec<u32> = (0..100).collect();
        let rows = Rows {
            keys: &keys,
            revision: 0,
            layout: 0.,
            height: &|_| 20.,
            kind: &|_| 0,
        };
        let mut list = VirtualRows::eager_up_to(100);
        let frame = settle(&ctx, &mut list, &rows, None);
        assert_eq!(frame.work, 100);
        assert!((frame.extent - (100. * 28. - 8.)).abs() < 0.5);
        let longer: Vec<u32> = (0..101).collect();
        let rows = Rows {
            keys: &longer,
            revision: 1,
            ..rows
        };
        assert!(settle(&ctx, &mut list, &rows, None).work <= VISIBLE);
    }

    #[test]
    fn layout_changes_estimate_unshown_rows_from_rows_of_the_same_kind() {
        let ctx = egui::Context::default();
        let keys: Vec<u32> = (0..2000).collect();
        let kind = |key: u32| u64::from(key % 2);
        let mut list = VirtualRows::default();
        let narrow = Rows {
            keys: &keys,
            revision: 0,
            layout: 0.,
            height: &|key| if key % 2 == 0 { 20. } else { 44. },
            kind: &kind,
        };
        let frame = settle(&ctx, &mut list, &narrow, None);
        assert!((frame.extent - (1000. * 28. + 1000. * 52. - 8.)).abs() < 0.5);
        // Rows of a kind change height together, as when a narrower window
        // wraps their actions. Only rows near the viewport are built again,
        // and the unshown rows take the new height of their kind.
        let wrapped = Rows {
            layout: 1.,
            height: &|key| if key % 2 == 0 { 30. } else { 60. },
            ..narrow
        };
        let resized = render(&ctx, &mut list, &wrapped, None, None);
        assert!(resized.work <= 2 * VISIBLE, "{}", resized.work);
        assert!((resized.extent - (1000. * 38. + 1000. * 68. - 8.)).abs() < 0.5);
        let frame = render(&ctx, &mut list, &wrapped, None, None);
        assert!(frame.work <= VISIBLE);
        assert!((frame.extent - resized.extent).abs() < 0.5);
    }

    #[test]
    fn measurements_follow_keys_and_kept_rows_keep_their_ids_offscreen() {
        let ctx = egui::Context::default();
        let keys: Vec<u32> = (0..500).collect();
        let tall = |key: u32| if key == 7 { 44. } else { 20. };
        let mut list = VirtualRows::default();
        let rows = Rows {
            keys: &keys,
            revision: 0,
            layout: 0.,
            height: &tall,
            kind: &|_| 0,
        };
        let frame = settle(&ctx, &mut list, &rows, None);
        let id = frame.built.iter().find(|(key, _)| *key == 7).unwrap().1;
        // Reversing the order keeps the measurement of key 7, even though
        // its kind estimates the other rows at 20 points.
        let reversed: Vec<u32> = keys.iter().rev().copied().collect();
        let rows = Rows {
            keys: &reversed,
            revision: 1,
            ..rows
        };
        let frame = settle(&ctx, &mut list, &rows, None);
        assert!(
            (frame.extent - (499. * 28. + 52. - 8.)).abs() < 0.5,
            "{}",
            frame.extent
        );
        // Key 7 is now near the end, outside the viewport, and is still built
        // once with the id it had in the flow, without moving the list.
        let kept = render(&ctx, &mut list, &rows, Some(7), None);
        assert_eq!(kept.built.last(), Some(&(7, id)));
        assert_eq!(kept.built.iter().filter(|(key, _)| *key == 7).count(), 1);
        assert!((kept.extent - frame.extent).abs() < 0.5);
    }
}
