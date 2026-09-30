//! Private-network forms; credentials stay in memory and are cleared on dismissal.
use eframe::egui::{self, Color32, RichText};
use openrad::{
    i18n::Language,
    network::{validate_name, MemberAction, NetworkPassword, NetworkRequest},
};
use zeroize::Zeroizing;

fn show_modal(ctx: &egui::Context, id: &str, contents: impl FnOnce(&mut egui::Ui)) {
    egui::Modal::new(egui::Id::new(id)).show(ctx, |ui| {
        ui.set_width(380.0_f32.min((ctx.content_rect().width() - 48.).max(240.)));
        egui::ScrollArea::vertical()
            .max_height((ctx.content_rect().height() - 64.).max(120.))
            .show(ui, contents);
    });
}

#[derive(Clone, Copy, PartialEq)]
pub enum Mode {
    Join,
    Create,
}
pub enum FormEvent {
    Submit(NetworkRequest),
    Close,
}
pub struct NetworkForm {
    pub mode: Mode,
    name: String,
    password: Zeroizing<String>,
    confirmation: Zeroizing<String>,
    show_password: bool,
    error: Option<String>,
}
impl NetworkForm {
    pub fn new(mode: Mode) -> Self {
        Self {
            mode,
            name: String::new(),
            password: Zeroizing::new(String::new()),
            confirmation: Zeroizing::new(String::new()),
            show_password: false,
            error: None,
        }
    }
    fn request(&self) -> anyhow::Result<NetworkRequest> {
        validate_name(&self.name)?;
        if self.mode == Mode::Create {
            anyhow::ensure!(
                *self.password == *self.confirmation,
                "Passwords do not match"
            );
        }
        let password = NetworkPassword::new(self.password.to_string())?;
        Ok(match self.mode {
            Mode::Create => NetworkRequest::Create {
                name: self.name.clone(),
                password,
            },
            Mode::Join => NetworkRequest::Join {
                name: self.name.clone(),
                password: Some(password),
            },
        })
    }
    pub fn show(
        &mut self,
        ctx: &egui::Context,
        enabled: bool,
        language: Language,
    ) -> Option<FormEvent> {
        let mut event = None;
        show_modal(ctx, "private-network-form", |ui| {
            ui.heading(if self.mode == Mode::Create {
                language.text("Create a private network")
            } else {
                language.text("Join a private network")
            });
            ui.add_space(10.);
            ui.label(if self.mode == Mode::Create {
                language.text(
                    "Share the exact network name and password with the people you want to join.",
                )
            } else {
                language.text(
                    "Enter the exact network name and the password shared by its administrator.",
                )
            });
            ui.add_space(12.);
            let label = ui.label(language.text("Network name"));
            ui.add(
                egui::TextEdit::singleline(&mut self.name)
                    .desired_width(f32::INFINITY)
                    .char_limit(255),
            )
            .labelled_by(label.id);
            ui.add_space(8.);
            let label = ui.label(language.text("Password"));
            ui.add(
                egui::TextEdit::singleline(&mut *self.password)
                    .password(!self.show_password)
                    .desired_width(f32::INFINITY)
                    .char_limit(256),
            )
            .labelled_by(label.id);
            if self.mode == Mode::Create {
                ui.add_space(8.);
                let label = ui.label(language.text("Confirm password"));
                ui.add(
                    egui::TextEdit::singleline(&mut *self.confirmation)
                        .password(!self.show_password)
                        .desired_width(f32::INFINITY)
                        .char_limit(256),
                )
                .labelled_by(label.id);
            }
            ui.checkbox(&mut self.show_password, language.text("Show password"));
            ui.small(
                language
                    .text("Use at least 6 characters. The password is not saved in your profile."),
            );
            if let Some(error) = &self.error {
                ui.label(RichText::new(language.message(error)).color(Color32::LIGHT_RED));
            }
            if !enabled {
                ui.label(
                    language
                        .text("Connect your device and wait for the current operation to finish."),
                );
            }
            ui.add_space(12.);
            ui.horizontal(|ui| {
                if ui
                    .add_enabled(
                        enabled,
                        egui::Button::new(if self.mode == Mode::Create {
                            language.text("Create network")
                        } else {
                            language.text("Join network")
                        }),
                    )
                    .clicked()
                {
                    match self.request() {
                        Ok(request) => event = Some(FormEvent::Submit(request)),
                        Err(error) => self.error = Some(error.to_string()),
                    }
                }
                if ui.button(language.text("Cancel")).clicked() {
                    event = Some(FormEvent::Close);
                }
            });
        });
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            event = Some(FormEvent::Close);
        }
        event
    }
}
pub struct MemberConfirmation {
    pub network: String,
    pub network_name: String,
    pub member: u64,
    pub member_name: String,
    pub action: MemberAction,
}
impl MemberConfirmation {
    pub fn allowed(&self, snapshot: &openrad::runtime::Snapshot, own: u64) -> bool {
        let roles = snapshot.roles.get(&self.network);
        let role = roles.and_then(|r| r.get(&self.member)).copied();
        own != self.member
            && roles.and_then(|r| r.get(&own)) == Some(&2)
            && snapshot
                .peers
                .get(&self.member)
                .is_some_and(|p| p.peer.network_ids.contains(&self.network))
            && match self.action {
                MemberAction::GrantAdmin => role == Some(1),
                MemberAction::RevokeAdmin => role == Some(2),
                MemberAction::Kick => true,
            }
    }
    pub fn show(
        &self,
        ctx: &egui::Context,
        enabled: bool,
        language: Language,
    ) -> Option<FormEvent> {
        let mut event = None;
        show_modal(ctx, "member-management-confirmation", |ui| {
            ui.heading(language.text(self.action.label()));
            ui.add_space(10.);
            ui.label(format!("{} · {}", self.member_name, self.member));
            ui.label(language.message(&format!("Network: {}", self.network_name)));
            ui.add_space(10.);
            ui.label(match self.action {
                MemberAction::Kick => language.text("Remove this member from this network? They can rejoin if they know its password."),
                MemberAction::GrantAdmin => language.text("Give this member permission to manage this network and its members?"),
                MemberAction::RevokeAdmin => language.text("Remove this member's administration permissions? They will remain a network member."),
            });
            if !enabled {
                ui.label(language.text("This member or your administration permissions have changed. Close this dialog to refresh your selection."));
            }
            ui.add_space(12.);
            ui.horizontal(|ui| {
                if ui
                    .add_enabled(
                        enabled,
                        egui::Button::new(language.text(self.action.label())),
                    )
                    .clicked()
                {
                    event = Some(FormEvent::Submit(NetworkRequest::Member {
                        network: self.network.clone(),
                        member: self.member,
                        action: self.action,
                    }));
                }
                if ui.button(language.text("Cancel")).clicked() {
                    event = Some(FormEvent::Close);
                }
            });
        });
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            event = Some(FormEvent::Close);
        }
        event
    }
}
pub struct DeleteConfirmation {
    pub network: String,
    pub name: String,
}
impl DeleteConfirmation {
    pub fn allowed(&self, snapshot: &openrad::runtime::Snapshot, own: u64) -> bool {
        snapshot
            .networks
            .iter()
            .any(|n| n.network_id == self.network)
            && snapshot
                .roles
                .get(&self.network)
                .and_then(|roles| roles.get(&own))
                == Some(&2)
    }
    pub fn show(
        &self,
        ctx: &egui::Context,
        enabled: bool,
        language: Language,
    ) -> Option<FormEvent> {
        let mut event = None;
        show_modal(ctx, "delete-network-confirmation", |ui| {
            ui.heading(language.text("Delete network?"));
            ui.label(RichText::new(&self.name).strong());
            ui.add_space(10.);
            ui.label(language.text("This permanently deletes the network and removes every member. This cannot be undone."));
            if !enabled {
                ui.label(
                    language.text("Connect and check that you still administer this network."),
                );
            }
            ui.add_space(12.);
            ui.horizontal(|ui| {
                if ui.button(language.text("Cancel")).clicked() {
                    event = Some(FormEvent::Close);
                }
                if ui
                    .add_enabled(
                        enabled,
                        egui::Button::new(
                            RichText::new(language.text("Delete for everyone"))
                                .color(Color32::LIGHT_RED),
                        ),
                    )
                    .clicked()
                {
                    event = Some(FormEvent::Submit(NetworkRequest::Delete {
                        network: self.network.clone(),
                    }));
                }
            });
        });
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) {
            event = Some(FormEvent::Close);
        }
        event
    }
}
pub fn role_label(role: Option<u32>, language: Language) -> &'static str {
    match role {
        Some(0) => language.text("Pending approval"),
        Some(1) => language.text("Member"),
        Some(2) => language.text("Admin"),
        _ => language.text("Role unavailable"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn form_requires_matching_passwords_and_keeps_exact_name() {
        let mut form = NetworkForm::new(Mode::Create);
        form.name = "Example 雪".into();
        *form.password = "test password".into();
        assert!(form.request().is_err());
        *form.confirmation = "test password".into();
        let request = form.request().unwrap();
        assert!(matches!(&request, NetworkRequest::Create { name, .. } if name == "Example 雪"));
        assert!(!format!("{request:?}").contains("test password"));
        form.mode = Mode::Join;
        form.confirmation.clear();
        assert!(matches!(
            form.request().unwrap(),
            NetworkRequest::Join {
                password: Some(_),
                ..
            }
        ));
    }
}

#[cfg(test)]
mod interaction_tests {
    use super::*;
    use eframe::egui::{accesskit::Role, Event, PointerButton, Pos2, RawInput, Rect, Vec2};
    use openrad::{
        protocol::{Network, Peer},
        runtime::{PeerState, PeerView, Snapshot},
    };

    fn frame(
        ctx: &egui::Context,
        events: Vec<Event>,
        show: &mut impl FnMut(&egui::Context) -> Option<FormEvent>,
    ) -> (egui::FullOutput, Option<FormEvent>) {
        let mut event = None;
        let mut output = ctx.run_ui(
            RawInput {
                screen_rect: Some(Rect::from_min_size(Pos2::ZERO, Vec2::new(780., 540.))),
                events,
                ..Default::default()
            },
            |_| {
                if let Some(next) = show(ctx) {
                    event = Some(next);
                }
            },
        );
        output.textures_delta.clear();
        (output, event)
    }
    fn click(
        label: &str,
        show: &mut impl FnMut(&egui::Context) -> Option<FormEvent>,
    ) -> Option<FormEvent> {
        let ctx = egui::Context::default();
        crate::app::configure(&ctx);
        ctx.enable_accesskit();
        frame(&ctx, vec![], show);
        let (out, event) = frame(&ctx, vec![], show);
        assert!(event.is_none());
        let tree = out.platform_output.accesskit_update.unwrap();
        let node = tree
            .nodes
            .iter()
            .map(|(_, node)| node)
            .find(|node| node.role() == Role::Button && node.label() == Some(label))
            .expect("visible button");
        let bounds = node.bounds().unwrap();
        assert!(
            bounds.x0 >= 0. && bounds.y0 >= 0. && bounds.x1 <= 780. && bounds.y1 <= 540.,
            "button must fit the minimum window"
        );
        let position = Pos2::new(
            ((bounds.x0 + bounds.x1) / 2.) as f32,
            ((bounds.y0 + bounds.y1) / 2.) as f32,
        );
        frame(
            &ctx,
            vec![
                Event::PointerMoved(position),
                Event::PointerButton {
                    pos: position,
                    button: PointerButton::Primary,
                    pressed: true,
                    modifiers: Default::default(),
                },
            ],
            show,
        );
        frame(
            &ctx,
            vec![Event::PointerButton {
                pos: position,
                button: PointerButton::Primary,
                pressed: false,
                modifiers: Default::default(),
            }],
            show,
        )
        .1
    }
    #[test]
    fn private_form_buttons_submit_shared_requests_and_respect_disabled_state() {
        for language in Language::ALL {
            for mode in [Mode::Create, Mode::Join] {
                let mut form = NetworkForm::new(mode);
                form.name = "Synthetic network".into();
                *form.password = "synthetic password".into();
                *form.confirmation = "synthetic password".into();
                let button = if mode == Mode::Create {
                    "Create network"
                } else {
                    "Join network"
                };
                assert!(click(language.text(button), &mut |ctx| form
                    .show(ctx, false, language))
                .is_none());
                let event = click(language.text(button), &mut |ctx| {
                    form.show(ctx, true, language)
                })
                .unwrap();
                assert!(matches!(
                    (mode, event),
                    (
                        Mode::Create,
                        FormEvent::Submit(NetworkRequest::Create { .. })
                    ) | (
                        Mode::Join,
                        FormEvent::Submit(NetworkRequest::Join {
                            password: Some(_),
                            ..
                        })
                    )
                ));
                assert!(matches!(
                    click(language.text("Cancel"), &mut |ctx| form
                        .show(ctx, true, language)),
                    Some(FormEvent::Close)
                ));
            }
        }
    }

    fn snapshot() -> Snapshot {
        let mut snapshot = Snapshot::default();
        snapshot.networks.push(Network {
            name: "Test".into(),
            network_id: "network".into(),
        });
        snapshot
            .roles
            .insert("network".into(), [(1, 2), (2, 1)].into_iter().collect());
        snapshot.peers.insert(
            2,
            PeerView {
                peer: Peer {
                    rid: 2,
                    name: "Test member".into(),
                    vip: "26.0.0.2".parse().unwrap(),
                    server: None,
                    state: 0,
                    network_ids: ["network".into()].into_iter().collect(),
                },
                status: PeerState::Offline,
                detail: String::new(),
                transport: None,
            },
        );
        snapshot
    }
    #[test]
    fn member_dialog_confirms_exact_member_and_blocks_stale_permissions() {
        for language in Language::ALL {
            let mut state = snapshot();
            for action in [
                MemberAction::Kick,
                MemberAction::GrantAdmin,
                MemberAction::RevokeAdmin,
            ] {
                state.roles.get_mut("network").unwrap().insert(
                    2,
                    if action == MemberAction::RevokeAdmin {
                        2
                    } else {
                        1
                    },
                );
                let dialog = MemberConfirmation {
                    network: "network".into(),
                    network_name: "Test".into(),
                    member: 2,
                    member_name: "Test member".into(),
                    action,
                };
                assert!(dialog.allowed(&state, 1));
                assert!(!dialog.allowed(&state, 2));
                assert!(click(language.text(action.label()), &mut |ctx| dialog
                    .show(ctx, false, language))
                .is_none());
                assert!(
                    matches!(click(language.text(action.label()), &mut |ctx| dialog.show(ctx, true, language)), Some(FormEvent::Submit(NetworkRequest::Member { member: 2, action: actual, .. })) if actual == action)
                );
                state.roles.get_mut("network").unwrap().insert(1, 1);
                assert!(!dialog.allowed(&state, 1));
                state.roles.get_mut("network").unwrap().insert(1, 2);
            }
        }
    }

    #[test]
    fn delete_dialog_requires_current_admin_and_explicit_confirmation() {
        for language in Language::ALL {
            let mut state = snapshot();
            let dialog = DeleteConfirmation {
                network: "network".into(),
                name: "Test".into(),
            };
            assert!(dialog.allowed(&state, 1));
            assert!(!dialog.allowed(&state, 2));
            assert!(
                click(language.text("Delete for everyone"), &mut |ctx| dialog
                    .show(ctx, false, language))
                .is_none()
            );
            assert!(matches!(
                click(language.text("Cancel"), &mut |ctx| dialog
                    .show(ctx, true, language)),
                Some(FormEvent::Close)
            ));
            assert!(
                matches!(click(language.text("Delete for everyone"), &mut |ctx| dialog.show(ctx, true, language)), Some(FormEvent::Submit(NetworkRequest::Delete { network })) if network == "network")
            );
            state.networks.clear();
            assert!(!dialog.allowed(&state, 1));
        }
    }
}
