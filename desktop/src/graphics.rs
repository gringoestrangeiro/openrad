//! Start the UI with progressively more compatible graphics backends.
use std::{cell::Cell, sync::Arc};

use eframe::{egui_wgpu, wgpu};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, clap::ValueEnum)]
pub enum RendererPreference {
    #[default]
    Auto,
    Opengl,
    Wgpu,
    Software,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Attempt {
    Opengl,
    Hardware,
    Software,
}

impl RendererPreference {
    fn attempts(self) -> &'static [Attempt] {
        match self {
            Self::Auto => automatic_attempts(cfg!(windows)),
            Self::Opengl => &[Attempt::Opengl],
            Self::Wgpu => &[Attempt::Hardware],
            Self::Software => &[Attempt::Software],
        }
    }
}

fn automatic_attempts(windows: bool) -> &'static [Attempt] {
    if windows {
        // A WGL context can initialize successfully through Mesa's D3D12
        // translation layer, then fail natively during the first paint.
        // Prefer native DX12 and WARP before entering that OpenGL path.
        &[Attempt::Hardware, Attempt::Software, Attempt::Opengl]
    } else {
        &[Attempt::Opengl, Attempt::Hardware, Attempt::Software]
    }
}

fn backend() -> wgpu::Backends {
    // WARP is the Windows system's Direct3D CPU adapter. Do not require
    // OpenGL, Vulkan drivers, ANGLE, DXC, or a separately installed runtime.
    #[cfg(windows)]
    return wgpu::Backends::DX12;
    #[cfg(target_os = "macos")]
    return wgpu::Backends::METAL;
    #[cfg(not(any(windows, target_os = "macos")))]
    return wgpu::Backends::VULKAN;
}

fn adapter_rank(device: wgpu::DeviceType, software: bool) -> Option<u8> {
    if software {
        return (device == wgpu::DeviceType::Cpu).then_some(0);
    }
    match device {
        wgpu::DeviceType::DiscreteGpu => Some(0),
        wgpu::DeviceType::IntegratedGpu => Some(1),
        wgpu::DeviceType::VirtualGpu => Some(2),
        wgpu::DeviceType::Other => Some(3),
        wgpu::DeviceType::Cpu => None,
    }
}

fn wgpu_setup(software: bool) -> egui_wgpu::WgpuSetupCreateNew {
    let mut setup = egui_wgpu::WgpuSetupCreateNew::without_display_handle();
    setup.instance_descriptor.backends = backend();
    // Release startup must also work without the optional Windows
    // Graphics Tools debug layer. Avoid environment-dependent compiler
    // selection and Agility SDK/DXC prerequisites.
    setup.instance_descriptor.flags = wgpu::InstanceFlags::empty();
    setup.instance_descriptor.backend_options = wgpu::BackendOptions::default();
    setup
        .instance_descriptor
        .backend_options
        .dx12
        .shader_compiler = wgpu::Dx12Compiler::Fxc;
    setup.native_adapter_selector = Some(Arc::new(move |adapters, surface| {
        crate::startup_log::event(format_args!(
            "Enumerating graphics adapters; software={software}; count={}",
            adapters.len()
        ));
        for adapter in adapters {
            let info = adapter.get_info();
            crate::startup_log::event(format_args!(
                "Graphics candidate: {info:?}; supports_surface={}",
                surface.is_none_or(|surface| adapter.is_surface_supported(surface))
            ));
        }
        let selected: Result<wgpu::Adapter, String> = adapters
            .iter()
            .filter(|adapter| surface.is_none_or(|surface| adapter.is_surface_supported(surface)))
            .filter_map(|adapter| {
                adapter_rank(adapter.get_info().device_type, software).map(|rank| (rank, adapter))
            })
            .min_by_key(|(rank, _)| *rank)
            .map(|(_, adapter)| adapter.clone())
            .ok_or_else(|| {
                if software {
                    "No compatible system software graphics adapter is available".into()
                } else {
                    "No compatible hardware graphics adapter is available".into()
                }
            });
        if let Ok(adapter) = &selected {
            crate::startup_log::event(format_args!(
                "Selected graphics adapter: {:?}",
                adapter.get_info()
            ));
        }
        selected
    }));
    setup.device_descriptor = Arc::new(|adapter| wgpu::DeviceDescriptor {
        label: Some("OpenRad desktop"),
        required_limits: wgpu::Limits::downlevel_defaults().using_resolution(adapter.limits()),
        ..Default::default()
    });
    setup
}

#[cfg(all(test, target_os = "linux"))]
#[path = "graphics/software_test.rs"]
pub(crate) mod software_test;

fn native_options(attempt: Attempt) -> eframe::NativeOptions {
    let mut native = eframe::NativeOptions {
        viewport: eframe::egui::ViewportBuilder::default()
            .with_inner_size([1120., 800.])
            .with_min_inner_size([780., 540.])
            .with_app_id("org.openrad.desktop"),
        run_and_return: true,
        renderer: eframe::Renderer::Glow,
        ..Default::default()
    };
    if attempt != Attempt::Opengl {
        native.renderer = eframe::Renderer::Wgpu;
        native.wgpu_options.wgpu_setup =
            egui_wgpu::WgpuSetup::CreateNew(wgpu_setup(attempt == Attempt::Software));
    }
    native
}

fn retry_startup<T, E: std::fmt::Display>(
    preference: RendererPreference,
    mut start: impl FnMut(Attempt) -> (Result<T, E>, bool),
) -> anyhow::Result<T> {
    let mut failures = Vec::new();
    for &attempt in preference.attempts() {
        crate::startup_log::event(format_args!("Starting graphics attempt: {attempt:?}"));
        let (result, app_created) = start(attempt);
        match result {
            Ok(value) => {
                crate::startup_log::event(format_args!("Graphics loop returned successfully; attempt={attempt:?}; app_created={app_created}"));
                return Ok(value);
            }
            Err(error) => {
                crate::startup_log::event(format_args!("Graphics attempt failed; attempt={attempt:?}; app_created={app_created}; error={error}"));
                failures.push(format!("{attempt:?}: {error}"));
                // Only retry graphics initialization. Once the application
                // exists it owns the profile lock/backend and may be connected.
                // Never create a second VPN engine after a runtime failure.
                if app_created {
                    break;
                }
            }
        }
    }
    anyhow::bail!(
        "Native window could not be started: {}",
        failures.join("; ")
    )
}

pub fn run(
    preference: RendererPreference,
    mut create_app: impl FnMut(&eframe::CreationContext<'_>) -> Box<dyn eframe::App>,
) -> anyhow::Result<()> {
    retry_startup(preference, |attempt| {
        let app_created = Cell::new(false);
        // eframe's run-and-return API reuses its winit event loop, including
        // after initialization errors. The creator runs only after the painter
        // and surface have been initialized successfully.
        let result = eframe::run_native(
            "OpenRad",
            native_options(attempt),
            Box::new(|cc| {
                crate::startup_log::event(format_args!(
                    "Graphics painter initialized; attempt={attempt:?}; invoking App creator"
                ));
                if let Some(state) = &cc.wgpu_render_state {
                    crate::startup_log::event(format_args!(
                        "Initialized WGPU adapter: {:?}",
                        state.adapter.get_info()
                    ));
                }
                app_created.set(true);
                let app = create_app(cc);
                crate::startup_log::event(format_args!(
                    "App creator completed; attempt={attempt:?}"
                ));
                Ok(app)
            }),
        );
        (result, app_created.get())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_opengl_and_hardware_automatically_use_software() {
        let mut attempted = Vec::new();
        let result = retry_startup(RendererPreference::Auto, |attempt| {
            attempted.push(attempt);
            if attempt == Attempt::Software {
                (Ok(42), true)
            } else {
                (Err("graphics unavailable"), false)
            }
        });
        assert_eq!(result.unwrap(), 42);
        let order = RendererPreference::Auto.attempts();
        let software = order
            .iter()
            .position(|attempt| *attempt == Attempt::Software)
            .unwrap();
        assert_eq!(attempted, order[..=software]);
    }

    #[test]
    fn working_opengl_starts_only_one_application() {
        let mut attempted = Vec::new();
        retry_startup(RendererPreference::Opengl, |attempt| {
            attempted.push(attempt);
            (Ok::<_, &str>(()), true)
        })
        .unwrap();
        assert_eq!(attempted, [Attempt::Opengl]);
    }

    #[test]
    fn windows_auto_tries_native_renderers_before_opengl_translation() {
        assert_eq!(
            automatic_attempts(true),
            [Attempt::Hardware, Attempt::Software, Attempt::Opengl]
        );
        assert_eq!(
            automatic_attempts(false),
            [Attempt::Opengl, Attempt::Hardware, Attempt::Software]
        );
    }

    #[test]
    fn application_runtime_failure_does_not_restart_the_vpn() {
        let mut attempted = Vec::new();
        let result = retry_startup::<(), _>(RendererPreference::Auto, |attempt| {
            attempted.push(attempt);
            (Err("device lost after app creation"), true)
        });
        assert!(result.is_err());
        assert_eq!(attempted, [RendererPreference::Auto.attempts()[0]]);
    }

    #[test]
    fn explicit_renderer_selection_does_not_use_other_backends() {
        for (preference, expected) in [
            (RendererPreference::Opengl, Attempt::Opengl),
            (RendererPreference::Wgpu, Attempt::Hardware),
            (RendererPreference::Software, Attempt::Software),
        ] {
            let mut attempted = Vec::new();
            let result = retry_startup::<(), _>(preference, |attempt| {
                attempted.push(attempt);
                (Err("unavailable"), false)
            });
            assert!(result.is_err());
            assert_eq!(attempted, [expected]);
        }
    }

    #[test]
    fn software_requires_a_cpu_adapter_and_hardware_excludes_it() {
        assert_eq!(adapter_rank(wgpu::DeviceType::Cpu, true), Some(0));
        assert_eq!(adapter_rank(wgpu::DeviceType::Cpu, false), None);
        for device in [
            wgpu::DeviceType::DiscreteGpu,
            wgpu::DeviceType::IntegratedGpu,
            wgpu::DeviceType::VirtualGpu,
            wgpu::DeviceType::Other,
        ] {
            assert!(adapter_rank(device, true).is_none());
            assert!(adapter_rank(device, false).is_some());
        }
    }

    #[test]
    fn software_configuration_does_not_require_extra_shader_dlls() {
        let setup = wgpu_setup(true);
        assert_eq!(setup.instance_descriptor.backends, backend());
        assert!(!setup
            .instance_descriptor
            .backends
            .contains(wgpu::Backends::GL));
        assert!(setup.instance_descriptor.flags.is_empty());
        assert!(matches!(
            setup
                .instance_descriptor
                .backend_options
                .dx12
                .shader_compiler,
            wgpu::Dx12Compiler::Fxc
        ));
        assert!(setup
            .instance_descriptor
            .backend_options
            .dx12
            .agility_sdk
            .is_none());
    }
}
