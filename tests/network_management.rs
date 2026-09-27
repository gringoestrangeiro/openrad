use num_bigint::BigUint;
use openrad::{
    crypto::{self, ShServer},
    network::{
        network_identity, network_verifier, validate_name, MemberAction, NetworkOperation,
        NetworkPassword, NetworkRequest,
    },
    protocol::*,
};
use serde_json::Value;
use std::net::Ipv4Addr;

const NAME: &str = "Synthetic private network";
const GUID: &str = "000102030405060708090a0b0c0d0e0f";
const OTHER: &str = "101112131415161718191a1b1c1d1e1f";
const OWN: u64 = 42;
const MEMBER: u64 = 84;
fn password() -> NetworkPassword {
    NetworkPassword::new("synthetic password".into()).unwrap()
}
fn id(guid: &str) -> Vec<u8> {
    tlv(0x0d000309, &hex::decode(guid).unwrap())
}
fn packet(op: u32, tag: u32, body: &[u8]) -> Vec<u8> {
    [u32v(SERVER_OP, op), tlv(tag, body)].concat()
}
fn response(action: u32, request: u64, context: Option<u32>, fields: &[u8]) -> Vec<u8> {
    let mut body = [u32v(0x0100030c, action), u64v(0x02000340, request)].concat();
    if let Some(context) = context {
        body.extend(u32v(0x0100034a, context));
    }
    body.extend(fields);
    packet(37, 0x131a, &body)
}
fn network(guid: &str, name: &str, role: u32) -> Vec<u8> {
    tlv(
        0x1315,
        &[
            id(guid),
            textv(0x03000306, name).unwrap(),
            u32v(0x0100030a, role),
        ]
        .concat(),
    )
}
fn membership() -> Membership {
    let mut m = Membership {
        own_rid: OWN,
        ..Default::default()
    };
    m.snapshot(&tlv(
        0x1316,
        &[network(GUID, NAME, 2), network(OTHER, "Other network", 1)].concat(),
    ))
    .unwrap();
    m.peers.insert(
        MEMBER,
        Peer {
            rid: MEMBER,
            name: "Existing member".into(),
            vip: Ipv4Addr::new(26, 0, 0, 2),
            server: Some("192.0.2.1".into()),
            state: 1,
            network_ids: [GUID.into(), OTHER.into()].into_iter().collect(),
        },
    );
    for guid in [GUID, OTHER] {
        m.roles.entry(guid.into()).or_default().insert(MEMBER, 1);
    }
    m
}
fn approval(guid: &str) -> Vec<u8> {
    // The real approval lists existing members, including the administrator.
    packet(
        42,
        0x131f,
        &tlv(0x131e, &[id(guid), u64v(0x020001e1, MEMBER)].concat()),
    )
}
fn auth_packet(blob: &[u8], sequence: u32) -> Vec<u8> {
    packet(
        40,
        0x131c,
        &[
            u64v(0x02000303, 0),
            u64v(0x02000340, 101),
            u32v(0x010003be, sequence),
            tlv(0x0a00030e, blob),
        ]
        .concat(),
    )
}
fn blob(packet: &[u8]) -> Vec<u8> {
    field(
        &records(field(&records(packet).unwrap(), 0x131c).unwrap()).unwrap(),
        0x0a00030e,
    )
    .unwrap()
    .to_vec()
}
fn start_private() -> (NetworkOperation, Vec<u8>, Membership, ShServer) {
    let (op, initial) = NetworkOperation::start(
        NetworkRequest::Join {
            name: NAME.into(),
            password: Some(password()),
        },
        101,
        7,
    )
    .unwrap();
    let server = ShServer::with_private(
        network_identity(NAME),
        b"synthetic password",
        vec![3; 16],
        BigUint::from(789123u32),
    )
    .unwrap();
    (
        op,
        initial,
        Membership {
            own_rid: OWN,
            ..Default::default()
        },
        server,
    )
}
fn authenticate(
    op: &mut NetworkOperation,
    initial: &[u8],
    m: &mut Membership,
    server: &mut ShServer,
) -> Vec<u8> {
    let parameters = server.hello(&blob(initial)).unwrap();
    // A delayed message for a different sequence cannot advance the handshake.
    assert!(op
        .handle(&auth_packet(&parameters, 6), m)
        .unwrap()
        .send
        .is_none());
    let public = op
        .handle(&auth_packet(&parameters, 7), m)
        .unwrap()
        .send
        .unwrap();
    let public_fields = records(&public).unwrap();
    let continuation = records(field(&public_fields, 0x131c).unwrap()).unwrap();
    assert!(optional(&continuation, 0x03000306).unwrap().is_none());
    assert!(optional(&continuation, 0x02000340).unwrap().is_none());
    let challenge = server.public(&blob(&public)).unwrap();
    let proof = op
        .handle(&auth_packet(&challenge, 7), m)
        .unwrap()
        .send
        .unwrap();
    server.proof(&blob(&proof)).unwrap().0
}

#[test]
fn crc_and_create_verifier_match_independent_unicode_vector() {
    let v: Value = serde_json::from_str(include_str!("fixtures/network-verifier.json")).unwrap();
    let name = v["name"].as_str().unwrap();
    let p = NetworkPassword::new(v["password"].as_str().unwrap().into()).unwrap();
    let salt: [u8; 32] = hex::decode(v["salt"].as_str().unwrap())
        .unwrap()
        .try_into()
        .unwrap();
    assert_eq!(network_identity(name), v["identity"].as_u64().unwrap());
    assert_eq!(
        hex::encode(network_verifier(name, &p, &salt).unwrap()),
        v["verifier"].as_str().unwrap()
    );
    assert_ne!(
        network_identity(name),
        network_identity(&format!("{name} "))
    );
}
#[test]
fn validation_counts_utf16_and_redacts_passwords() {
    for name in ["", " \t ", "a\nb", &"🌐".repeat(128)] {
        assert!(validate_name(name).is_err());
    }
    assert!(validate_name(&"🌐".repeat(127)).is_ok());
    assert!(NetworkPassword::new("short".into()).is_err());
    assert!(NetworkPassword::new("hidden\npassword".into()).is_err());
    let request = NetworkRequest::Create {
        name: NAME.into(),
        password: password(),
    };
    assert!(!format!("{request:?}").contains("synthetic password"));
}
#[test]
fn creation_and_member_requests_use_verified_wire_fields() {
    let (_, create) = NetworkOperation::start(
        NetworkRequest::Create {
            name: NAME.into(),
            password: password(),
        },
        101,
        7,
    )
    .unwrap();
    let r = records(&create).unwrap();
    assert_eq!(int32(field(&r, CLIENT_OP).unwrap()).unwrap(), 52);
    let f = records(field(&r, 0x1319).unwrap()).unwrap();
    assert_eq!(int32(field(&f, 0x0100030c).unwrap()).unwrap(), 1);
    assert_eq!(int64(field(&f, 0x02000340).unwrap()).unwrap(), 101);
    assert_eq!(int32(field(&f, 0x0100034a).unwrap()).unwrap(), 7);
    let props = records(field(&f, 0x1314).unwrap()).unwrap();
    assert_eq!(text(field(&props, 0x03000306).unwrap()).unwrap(), NAME);
    assert_eq!(field(&props, 0x0b000308).unwrap(), 0u32.to_be_bytes());
    let verifier = field(&props, 0x0a000307).unwrap();
    assert_eq!(&verifier[..4], 32u32.to_le_bytes());
    assert_eq!(
        verifier,
        network_verifier(NAME, &password(), verifier[4..36].try_into().unwrap()).unwrap()
    );
    for (action, code) in [
        (MemberAction::Kick, 4),
        (MemberAction::GrantAdmin, 8),
        (MemberAction::RevokeAdmin, 9),
    ] {
        let (_, bytes) = NetworkOperation::start(
            NetworkRequest::Member {
                network: GUID.into(),
                member: MEMBER,
                action,
            },
            101,
            7,
        )
        .unwrap();
        let expected = [
            u32v(CLIENT_OP, 52),
            tlv(
                0x1319,
                &[
                    u32v(0x0100030c, code),
                    u64v(0x02000340, 101),
                    u32v(0x0100034a, 7),
                    id(GUID),
                    u64v(0x020001e1, MEMBER),
                ]
                .concat(),
            ),
        ]
        .concat();
        assert_eq!(bytes, expected);
    }
    let (_, bytes) = NetworkOperation::start(
        NetworkRequest::Leave {
            network: GUID.into(),
        },
        101,
        7,
    )
    .unwrap();
    assert_eq!(bytes, leave(GUID, 101, 7).unwrap());
    assert!(NetworkOperation::start(
        NetworkRequest::Leave {
            network: "bad".into()
        },
        101,
        7
    )
    .is_err());
}
#[test]
fn private_join_needs_mutual_proof_and_correlated_membership_and_approval() {
    for approval_first in [false, true] {
        let (mut op, initial, mut m, mut server) = start_private();
        let fields = records(&initial).unwrap();
        let f = records(field(&fields, 0x131c).unwrap()).unwrap();
        assert_eq!(field(&f, 0x0b00033f).unwrap(), 0u32.to_be_bytes());
        let confirmation = authenticate(&mut op, &initial, &mut m, &mut server);
        assert!(op
            .handle(&auth_packet(&confirmation, 7), &mut m)
            .unwrap()
            .complete
            .is_none());
        let answer = response(2, 101, None, &tlv(0x1316, &network(GUID, NAME, 1)));
        assert!(op
            .handle(&approval(OTHER), &mut m)
            .unwrap()
            .complete
            .is_none());
        let (first, last) = if approval_first {
            (approval(GUID), answer)
        } else {
            (answer, approval(GUID))
        };
        assert!(op.handle(&first, &mut m).unwrap().complete.is_none());
        let result = op.handle(&last, &mut m).unwrap().complete.unwrap();
        assert!(!result.error && !result.pending_approval);
        assert_eq!(m.role(GUID, OWN), Some(1));
    }
}
#[test]
fn private_join_rejects_unverified_or_forged_server_proof() {
    let (mut op, initial, mut m, mut server) = start_private();
    let answer = response(2, 101, None, &tlv(0x1316, &network(GUID, NAME, 1)));
    assert!(op.handle(&answer, &mut m).is_err());
    assert!(m.networks.is_empty());
    let mut confirmation = authenticate(&mut op, &initial, &mut m, &mut server);
    *confirmation.last_mut().unwrap() ^= 1;
    assert!(op.handle(&auth_packet(&confirmation, 7), &mut m).is_err());
    assert!(op.handle(&answer, &mut m).is_err());
    assert!(m.networks.is_empty());
}
#[test]
fn private_password_abort_is_a_refusal_and_cannot_add_membership() {
    let (mut op, _, mut m, _) = start_private();
    let abort = crypto::sh_record(0x10000000, &0u32.to_be_bytes());
    let result = op
        .handle(&auth_packet(&abort, 7), &mut m)
        .unwrap()
        .complete
        .unwrap();
    assert!(result.error && result.message.contains("password"));
    assert!(m.networks.is_empty());
}
#[test]
fn public_join_preserves_legacy_wire_and_approval_contract() {
    let (mut op, initial) =
        NetworkOperation::start(NetworkRequest::public_join(NAME.into()), 101, 7).unwrap();
    assert_eq!(initial, join(NAME, 101, 7).unwrap());
    let mut m = Membership {
        own_rid: OWN,
        ..Default::default()
    };
    let answer = response(2, 101, None, &tlv(0x1316, &network(GUID, NAME, 1)));
    assert!(op.handle(&answer, &mut m).unwrap().complete.is_none());
    assert!(
        !op.handle(&approval(GUID), &mut m)
            .unwrap()
            .complete
            .unwrap()
            .error
    );
}
#[test]
fn awaiting_approval_is_not_reported_as_joined() {
    let (mut op, _) =
        NetworkOperation::start(NetworkRequest::public_join(NAME.into()), 101, 7).unwrap();
    let mut m = Membership {
        own_rid: OWN,
        ..Default::default()
    };
    let answer = response(2, 101, None, &tlv(0x1316, &network(GUID, NAME, 0)));
    let result = op.handle(&answer, &mut m).unwrap().complete.unwrap();
    assert!(!result.error && result.pending_approval);
}
#[test]
fn member_acknowledgements_cannot_mutate_the_wrong_target() {
    for (action, request, context, guid, rid) in [
        (4, 102, 7, GUID, MEMBER),
        (8, 101, 7, GUID, MEMBER),
        (4, 101, 9, GUID, MEMBER),
        (4, 101, 7, OTHER, MEMBER),
        (4, 101, 7, GUID, OWN),
    ] {
        let (mut op, _) = NetworkOperation::start(
            NetworkRequest::Member {
                network: GUID.into(),
                member: MEMBER,
                action: MemberAction::Kick,
            },
            101,
            7,
        )
        .unwrap();
        let mut m = membership();
        let before = serde_json::to_value(&m).unwrap();
        let result = op.handle(
            &response(
                action,
                request,
                Some(context),
                &[id(guid), u64v(0x020001e1, rid)].concat(),
            ),
            &mut m,
        );
        if request != 101 {
            assert!(result.unwrap().complete.is_none());
        } else {
            assert!(result.is_err());
        }
        assert_eq!(before, serde_json::to_value(&m).unwrap());
    }
}
#[test]
fn refusal_including_zero_cannot_change_membership() {
    for code in [0, 20] {
        let (mut op, _) = NetworkOperation::start(
            NetworkRequest::Create {
                name: NAME.into(),
                password: password(),
            },
            101,
            7,
        )
        .unwrap();
        let mut m = Membership::default();
        assert!(
            op.handle(&response(1, 101, Some(7), &u32v(0x010001d2, code)), &mut m)
                .unwrap()
                .complete
                .unwrap()
                .error
        );
        assert!(m.networks.is_empty());
    }
}
#[test]
fn create_success_validates_name_and_captures_creator_role() {
    let (mut op, _) = NetworkOperation::start(
        NetworkRequest::Create {
            name: NAME.into(),
            password: password(),
        },
        101,
        7,
    )
    .unwrap();
    let mut m = Membership {
        own_rid: OWN,
        ..Default::default()
    };
    assert!(op
        .handle(
            &response(1, 101, Some(7), &network(GUID, "Wrong name", 2)),
            &mut m
        )
        .is_err());
    assert!(m.networks.is_empty());
    assert!(
        !op.handle(&response(1, 101, Some(7), &network(GUID, NAME, 2)), &mut m)
            .unwrap()
            .complete
            .unwrap()
            .error
    );
    assert_eq!(m.role(GUID, OWN), Some(2));
}
#[test]
fn management_updates_roles_and_kick_preserves_other_shared_networks() {
    let mut m = membership();
    for (action, role) in [
        (MemberAction::GrantAdmin, Some(2)),
        (MemberAction::RevokeAdmin, Some(1)),
        (MemberAction::Kick, None),
    ] {
        let (mut op, _) = NetworkOperation::start(
            NetworkRequest::Member {
                network: GUID.into(),
                member: MEMBER,
                action,
            },
            101,
            7,
        )
        .unwrap();
        let ack = response(
            action.code(),
            101,
            Some(7),
            &[id(GUID), u64v(0x020001e1, MEMBER)].concat(),
        );
        assert!(!op.handle(&ack, &mut m).unwrap().complete.unwrap().error);
        assert_eq!(m.role(GUID, MEMBER), role);
    }
    assert_eq!(
        m.peers[&MEMBER].network_ids,
        [OTHER.into()].into_iter().collect()
    );
    assert_eq!(m.role(OTHER, MEMBER), Some(1));
}
#[test]
fn membership_pushes_apply_role_changes_self_kick_and_deletion() {
    let mut m = membership();
    let changed = tlv(
        0x1318,
        &[id(GUID), u64v(0x020001e1, MEMBER), u32v(0x0100030a, 2)].concat(),
    );
    m.changes(&packet(41, 0x131f, &changed)).unwrap();
    assert_eq!(m.role(GUID, MEMBER), Some(2));
    let self_role = tlv(
        0x1323,
        &[
            id(GUID),
            textv(0x03000306, NAME).unwrap(),
            u32v(0x0100030a, 1),
        ]
        .concat(),
    );
    m.changes(&packet(41, 0x131f, &self_role)).unwrap();
    assert_eq!(m.role(GUID, OWN), Some(1));
    let removed = tlv(0x131d, &[id(GUID), u64v(0x020001e1, OWN)].concat());
    m.changes(&packet(41, 0x131f, &removed)).unwrap();
    assert!(!m.networks.contains_key(GUID) && !m.roles.contains_key(GUID));
    assert_eq!(
        m.peers[&MEMBER].network_ids,
        [OTHER.into()].into_iter().collect()
    );
    m.changes(&packet(41, 0x131f, &tlv(0x132e, &id(OTHER))))
        .unwrap();
    assert!(m.networks.is_empty() && m.peers.is_empty() && m.roles.is_empty());
}

#[test]
fn member_events_accept_optional_source_rid_without_changing_the_subject() {
    let mut m = membership();
    let own_role = m.role(GUID, OWN);
    let status = tlv(
        0x1318,
        &[
            id(GUID),
            u64v(0x020001e1, MEMBER),
            u64v(0x020001e1, OWN),
            u32v(0x0100030a, 2),
        ]
        .concat(),
    );
    m.changes(&packet(41, 0x131f, &status)).unwrap();
    assert_eq!(m.role(GUID, MEMBER), Some(2));
    assert_eq!(m.role(GUID, OWN), own_role);
    let removed = tlv(
        0x131d,
        &[id(GUID), u64v(0x020001e1, MEMBER), u64v(0x020001e1, OWN)].concat(),
    );
    m.changes(&packet(41, 0x131f, &removed)).unwrap();
    assert!(m.networks.contains_key(GUID));
    assert_eq!(m.role(GUID, OWN), own_role);
    assert_eq!(m.role(GUID, MEMBER), None);
    assert_eq!(
        m.peers[&MEMBER].network_ids,
        [OTHER.into()].into_iter().collect()
    );

    let self_removed = tlv(
        0x131d,
        &[id(GUID), u64v(0x020001e1, OWN), u64v(0x020001e1, MEMBER)].concat(),
    );
    m.changes(&packet(41, 0x131f, &self_removed)).unwrap();
    assert!(!m.networks.contains_key(GUID));
    assert!(m.networks.contains_key(OTHER));
}

#[test]
fn member_events_still_reject_ambiguous_or_malformed_identifiers() {
    for tag in [0x1318, 0x131d] {
        for extra in [
            [u64v(0x020001e1, OWN), u64v(0x020001e1, 999)].concat(),
            tlv(0x020001e1, &[0; 7]),
        ] {
            let mut m = membership();
            let before = serde_json::to_value(&m).unwrap();
            let change = tlv(
                tag,
                &[
                    id(GUID),
                    u64v(0x020001e1, MEMBER),
                    extra,
                    u32v(0x0100030a, 2),
                ]
                .concat(),
            );
            assert!(m.changes(&packet(41, 0x131f, &change)).is_err());
            assert_eq!(serde_json::to_value(m).unwrap(), before);
        }
    }
    // A repeated RID in a presence update is still ambiguous, not a source RID.
    let change = tlv(
        0x1366,
        &[
            u64v(0x020001e1, MEMBER),
            u64v(0x020001e1, OWN),
            u32v(0x010003a0, 1),
        ]
        .concat(),
    );
    assert!(membership().changes(&packet(41, 0x131f, &change)).is_err());
}
#[test]
fn pending_members_do_not_get_forwarding_until_a_shared_membership_is_approved() {
    let mut m = membership();
    m.roles.get_mut(GUID).unwrap().insert(MEMBER, 0);
    m.roles.get_mut(OTHER).unwrap().insert(OWN, 0);
    assert!(m.eligible(OWN, &[]).unwrap().is_empty());
    m.roles.get_mut(OTHER).unwrap().insert(OWN, 1);
    assert_eq!(m.eligible(OWN, &[]).unwrap().len(), 1);
    assert!(m.eligible(OWN, &[NAME.into()]).unwrap().is_empty());
}

#[test]
fn delete_is_correlated_and_only_removes_the_target_network() {
    let (mut op, bytes) = NetworkOperation::start(
        NetworkRequest::Delete {
            network: GUID.into(),
        },
        101,
        7,
    )
    .unwrap();
    assert_eq!(
        bytes,
        [
            u32v(CLIENT_OP, 52),
            tlv(
                0x1319,
                &[
                    u32v(0x0100030c, 7),
                    u64v(0x02000340, 101),
                    u32v(0x0100034a, 7),
                    id(GUID)
                ]
                .concat()
            )
        ]
        .concat()
    );
    let mut m = membership();
    assert!(op
        .handle(&response(7, 101, Some(7), &id(OTHER)), &mut m)
        .is_err());
    assert!(m.networks.contains_key(GUID));
    assert!(
        !op.handle(&response(7, 101, Some(7), &id(GUID)), &mut m)
            .unwrap()
            .complete
            .unwrap()
            .error
    );
    assert!(!m.networks.contains_key(GUID) && !m.roles.contains_key(GUID));
    assert_eq!(
        m.peers[&MEMBER].network_ids,
        [OTHER.into()].into_iter().collect()
    );
}
