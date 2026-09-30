//! Opt-in native Linux check of real egui rasterization through a CPU adapter.
use super::*;
use eframe::egui;

#[test]
#[ignore = "requires a Vulkan CPU driver; performs real offscreen software rendering"]
fn cpu_renderer_draws_clipped_geometry_and_font_text_without_opengl() {
    let setup = wgpu_setup(true);
    let instance = wgpu::Instance::new(setup.instance_descriptor);
    let adapters = pollster::block_on(instance.enumerate_adapters(backend()));
    let adapter = (setup.native_adapter_selector.as_ref().unwrap())(&adapters, None).unwrap();
    assert_eq!(adapter.get_info().device_type, wgpu::DeviceType::Cpu);
    assert_eq!(adapter.get_info().backend, wgpu::Backend::Vulkan);
    eprintln!("Linux CPU rendering adapter: {}", adapter.get_info().name);
    let (device, queue) =
        pollster::block_on(adapter.request_device(&(setup.device_descriptor)(&adapter))).unwrap();

    let ctx = egui::Context::default();
    let mut output = ctx.run_ui(
        egui::RawInput {
            screen_rect: Some(egui::Rect::from_min_size(
                egui::Pos2::ZERO,
                egui::vec2(256., 128.),
            )),
            ..Default::default()
        },
        |ui| {
            let ctx = ui.ctx();
            let painter = ctx.layer_painter(egui::LayerId::background());
            painter
                .with_clip_rect(egui::Rect::from_min_max(
                    egui::pos2(32., 32.),
                    egui::pos2(104., 104.),
                ))
                .rect_filled(
                    egui::Rect::from_min_max(egui::pos2(16., 16.), egui::pos2(120., 120.)),
                    0.,
                    egui::Color32::from_rgb(220, 40, 60),
                );
            painter.text(
                egui::pos2(136., 40.),
                egui::Align2::LEFT_TOP,
                "OpenRad",
                egui::FontId::proportional(20.),
                egui::Color32::WHITE,
            );
        },
    );
    let paint_jobs = ctx.tessellate(output.shapes, output.pixels_per_point);
    let mut renderer = egui_wgpu::Renderer::new(
        &device,
        wgpu::TextureFormat::Rgba8Unorm,
        egui_wgpu::RendererOptions {
            dithering: false,
            ..Default::default()
        },
    );
    for (id, deltas) in &output.textures_delta.set {
        for delta in deltas {
            renderer.update_texture(&device, &queue, *id, delta);
        }
    }
    output.textures_delta.clear();
    let target = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("CPU-rendered UI regression"),
        size: wgpu::Extent3d {
            width: 256,
            height: 128,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let screen = egui_wgpu::ScreenDescriptor {
        size_in_pixels: [256, 128],
        pixels_per_point: output.pixels_per_point,
    };
    let mut encoder = device.create_command_encoder(&Default::default());
    let callbacks = renderer.update_buffers(&device, &queue, &mut encoder, &paint_jobs, &screen);
    assert!(callbacks.is_empty());
    let view = target.create_view(&Default::default());
    {
        let mut pass = encoder
            .begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("egui CPU render"),
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    resolve_target: None,
                    depth_slice: None,
                    ops: wgpu::Operations {
                        load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                        store: wgpu::StoreOp::Store,
                    },
                })],
                ..Default::default()
            })
            .forget_lifetime();
        renderer.render(&mut pass, &paint_jobs, &screen);
    }
    let buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("UI pixel readback"),
        size: 256 * 128 * 4,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    encoder.copy_texture_to_buffer(
        target.as_image_copy(),
        wgpu::TexelCopyBufferInfo {
            buffer: &buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(256 * 4),
                rows_per_image: None,
            },
        },
        target.size(),
    );
    let submission = queue.submit([encoder.finish()]);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer
        .slice(..)
        .map_async(wgpu::MapMode::Read, move |result| {
            tx.send(result).unwrap();
        });
    device
        .poll(wgpu::PollType::Wait {
            submission_index: Some(submission),
            timeout: Some(std::time::Duration::from_secs(30)),
        })
        .unwrap();
    rx.recv_timeout(std::time::Duration::from_secs(1))
        .unwrap()
        .unwrap();
    let pixels = buffer.slice(..).get_mapped_range().unwrap();
    let pixel = |x: usize, y: usize| &pixels[(y * 256 + x) * 4..(y * 256 + x + 1) * 4];
    assert_eq!(pixel(64, 64), [220, 40, 60, 255]);
    assert_eq!(pixel(24, 64), [0, 0, 0, 255], "clipping must hide geometry");
    assert_eq!(pixel(112, 64), [0, 0, 0, 255]);
    let text_pixels = (40..68)
        .flat_map(|y| (136..240).map(move |x| (x, y)))
        .filter(|&(x, y)| pixel(x, y)[0] > 100)
        .count();
    assert!(text_pixels > 100, "font atlas must produce visible text");
    drop(pixels);
    buffer.unmap();
}
