//! A short renderer-only window; no VPN backend, profile or credential access.
#[path = "../src/graphics.rs"]
mod graphics;
mod startup_log {
    pub use openrad::early_log::event;
    pub fn init() {
        openrad::early_log::init("graphics-smoke");
    }
}

use std::{cell::Cell, rc::Rc};

use clap::Parser;

#[derive(Parser)]
struct Args {
    #[arg(long, value_enum, default_value_t = graphics::RendererPreference::Auto)]
    renderer: graphics::RendererPreference,
}

struct SmokeApp {
    frames: usize,
}

impl eframe::App for SmokeApp {
    fn ui(&mut self, ui: &mut eframe::egui::Ui, _frame: &mut eframe::Frame) {
        eframe::egui::CentralPanel::default().show(ui, |ui| {
            ui.heading("OpenRad graphics smoke check");
            ui.label("Synthetic UI; no VPN or credentials.");
        });
        self.frames += 1;
        if self.frames >= 3 {
            ui.ctx()
                .send_viewport_cmd(eframe::egui::ViewportCommand::Close);
        }
        ui.ctx().request_repaint();
    }
}

fn main() -> anyhow::Result<()> {
    startup_log::init();
    let args = Args::parse();
    let created = Rc::new(Cell::new(0));
    graphics::run(args.renderer, |cc| {
        created.set(created.get() + 1);
        if let Some(state) = &cc.wgpu_render_state {
            println!("WGPU adapter: {:?}", state.adapter.get_info());
        } else {
            println!("OpenGL painter initialized");
        }
        Box::new(SmokeApp { frames: 0 })
    })?;
    anyhow::ensure!(created.get() == 1, "expected exactly one application");
    println!("Three frames completed; application created exactly once");
    Ok(())
}
