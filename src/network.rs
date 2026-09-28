//! One state machine is shared by the CLI and the long-lived desktop engine.
use crate::{
    crypto::{self, ShClient},
    protocol::*,
};
use anyhow::{ensure, Result};
use num_bigint::BigUint;
use std::{collections::BTreeSet, fmt};
use zeroize::Zeroizing;

#[derive(Clone)]
pub struct NetworkPassword(Zeroizing<String>);
impl fmt::Debug for NetworkPassword {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("NetworkPassword([redacted])")
    }
}
impl NetworkPassword {
    pub fn new(value: String) -> Result<Self> {
        let value = Zeroizing::new(value);
        ensure!(
            (6..=256).contains(&value.encode_utf16().count()),
            "network password must contain 6–256 characters"
        );
        ensure!(
            !value.chars().any(char::is_control),
            "network password contains a control character"
        );
        Ok(Self(value))
    }
    /// The official client passes the UTF-8 conversion buffer, including its
    /// terminating NUL, to both the join proof and the creation verifier.
    fn wire_bytes(&self) -> Zeroizing<Vec<u8>> {
        let mut bytes = Zeroizing::new(self.0.as_bytes().to_vec());
        bytes.push(0);
        bytes
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MemberAction {
    Kick,
    GrantAdmin,
    RevokeAdmin,
}
impl MemberAction {
    pub fn code(self) -> u32 {
        match self {
            Self::Kick => 4,
            Self::GrantAdmin => 8,
            Self::RevokeAdmin => 9,
        }
    }
    pub fn label(self) -> &'static str {
        match self {
            Self::Kick => "Remove member",
            Self::GrantAdmin => "Grant admin",
            Self::RevokeAdmin => "Revoke admin",
        }
    }
}
#[derive(Clone, Debug)]
pub enum NetworkRequest {
    Join {
        name: String,
        password: Option<NetworkPassword>,
    },
    Create {
        name: String,
        password: NetworkPassword,
    },
    Leave {
        network: String,
    },
    Delete {
        network: String,
    },
    Member {
        network: String,
        member: u64,
        action: MemberAction,
    },
}
impl NetworkRequest {
    pub fn public_join(name: String) -> Self {
        Self::Join {
            name,
            password: None,
        }
    }
}

pub fn validate_name(name: &str) -> Result<()> {
    ensure!(
        (1..=255).contains(&name.encode_utf16().count()) && !name.trim().is_empty(),
        "network name must contain 1–255 characters"
    );
    ensure!(
        !name.chars().any(char::is_control),
        "network name contains a control character"
    );
    Ok(())
}
/// Native CRC64, initial/final all-ones, over exact UTF-16LE name bytes.
pub fn network_identity(name: &str) -> u64 {
    let mut crc = u64::MAX;
    for byte in name.encode_utf16().flat_map(u16::to_le_bytes) {
        crc ^= (byte as u64) << 56;
        for _ in 0..8 {
            crc = (crc << 1)
                ^ if crc & (1 << 63) != 0 {
                    0x42f0e1eba9ea3693
                } else {
                    0
                };
        }
    }
    !crc
}
/// LE32 salt length + salt + minimal big-endian SH verifier.
pub fn network_verifier(
    name: &str,
    password: &NetworkPassword,
    salt: &[u8; 32],
) -> Result<Vec<u8>> {
    validate_name(name)?;
    let mut input = Zeroizing::new(crypto::identity_bytes(network_identity(name)));
    input.push(b':');
    input.extend_from_slice(&password.wire_bytes());
    let x = BigUint::from_bytes_be(&crypto::hash(
        &[salt.to_vec(), crypto::hash(&input)].concat(),
    ));
    let n = BigUint::parse_bytes(crypto::PRIME.as_bytes(), 16).expect("compiled SH prime");
    let verifier = BigUint::from(5u32).modpow(&x, &n).to_bytes_be();
    Ok([32u32.to_le_bytes().as_slice(), salt.as_slice(), &verifier].concat())
}
fn management(action: u32, id: u64, context: u32, fields: Vec<u8>) -> Result<Vec<u8>> {
    ensure!(
        id != 0 && context != 0,
        "invalid network request correlation"
    );
    Ok([
        u32v(CLIENT_OP, 52),
        tlv(
            0x1319,
            &[
                u32v(0x0100030c, action),
                u64v(0x02000340, id),
                u32v(0x0100034a, context),
                fields,
            ]
            .concat(),
        ),
    ]
    .concat())
}
fn guid(network: &str) -> Result<Vec<u8>> {
    let id = hex::decode(network)?;
    ensure!(
        id.len() == 16 && id.iter().any(|b| *b != 0),
        "invalid network ID"
    );
    Ok(tlv(0x0d000309, &id))
}
fn continuation(blob: &[u8], sequence: u32) -> Vec<u8> {
    [
        u32v(CLIENT_OP, 39),
        tlv(
            0x131c,
            &[tlv(0x0a00030e, blob), u32v(0x010003be, sequence)].concat(),
        ),
    ]
    .concat()
}

pub struct OperationResult {
    pub message: String,
    pub error: bool,
    pub pending_approval: bool,
}
#[derive(Default)]
pub struct Progress {
    pub send: Option<Vec<u8>>,
    pub complete: Option<OperationResult>,
}
impl Progress {
    fn done(message: String, error: bool) -> Self {
        Self {
            send: None,
            complete: Some(OperationResult {
                message,
                error,
                pending_approval: false,
            }),
        }
    }
}
enum Purpose {
    Join(String),
    Create(String),
    Leave(String),
    Delete(String),
    Member(String, u64, MemberAction),
}
struct JoinAuth {
    sh: ShClient,
    step: u8,
}
pub struct NetworkOperation {
    purpose: Purpose,
    id: u64,
    sequence: u32,
    auth: Option<JoinAuth>,
    joined: Option<Network>,
    approvals: BTreeSet<String>,
}
impl NetworkOperation {
    pub fn start(request: NetworkRequest, id: u64, sequence: u32) -> Result<(Self, Vec<u8>)> {
        ensure!(
            id != 0 && sequence != 0,
            "invalid network request correlation"
        );
        let mut auth = None;
        let (purpose, bytes) = match request {
            NetworkRequest::Join { name, password } => {
                validate_name(&name)?;
                let bytes = if let Some(password) = password {
                    let mut sh = ShClient::new(network_identity(&name), &password.wire_bytes())?;
                    let hello = sh.start()?;
                    let packet = [
                        u32v(CLIENT_OP, 39),
                        tlv(
                            0x131c,
                            &[
                                textv(0x03000306, &name)?,
                                u32v(0x0b00033f, 0),
                                tlv(0x0a00030e, &hello),
                                u64v(0x02000340, id),
                                u32v(0x010003be, sequence),
                            ]
                            .concat(),
                        ),
                    ]
                    .concat();
                    auth = Some(JoinAuth { sh, step: 2 });
                    packet
                } else {
                    join(&name, id, sequence)?
                };
                (Purpose::Join(name), bytes)
            }
            NetworkRequest::Create { name, password } => {
                let salt: [u8; 32] = crypto::random(32).try_into().expect("salt size");
                let verifier = Zeroizing::new(network_verifier(&name, &password, &salt)?);
                let bytes = management(
                    1,
                    id,
                    sequence,
                    tlv(
                        0x1314,
                        &[
                            u32v(0x0b000308, 0),
                            textv(0x03000306, &name)?,
                            tlv(0x0a000307, &verifier),
                        ]
                        .concat(),
                    ),
                )?;
                (Purpose::Create(name), bytes)
            }
            NetworkRequest::Leave { network } => {
                let bytes = management(3, id, sequence, guid(&network)?)?;
                (Purpose::Leave(network), bytes)
            }
            NetworkRequest::Delete { network } => {
                let bytes = management(7, id, sequence, guid(&network)?)?;
                (Purpose::Delete(network), bytes)
            }
            NetworkRequest::Member {
                network,
                member,
                action,
            } => {
                ensure!(member != 0, "invalid member ID");
                let bytes = management(
                    action.code(),
                    id,
                    sequence,
                    [guid(&network)?, u64v(0x020001e1, member)].concat(),
                )?;
                (Purpose::Member(network, member, action), bytes)
            }
        };
        Ok((
            Self {
                purpose,
                id,
                sequence,
                auth,
                joined: None,
                approvals: BTreeSet::new(),
            },
            bytes,
        ))
    }
    fn action(&self) -> u32 {
        match self.purpose {
            Purpose::Create(_) => 1,
            Purpose::Join(_) => 2,
            Purpose::Leave(_) => 3,
            Purpose::Delete(_) => 7,
            Purpose::Member(_, _, a) => a.code(),
        }
    }
    /// Consume a single authenticated control packet. Unrelated packets do not complete this operation.
    pub fn handle(&mut self, data: &[u8], membership: &mut Membership) -> Result<Progress> {
        let operation = op(data)?;
        let outer = records(data)?;
        if operation == 40 && matches!(self.purpose, Purpose::Join(_)) {
            let f = records(field(&outer, 0x131c)?)?;
            if let Some(value) = optional(&f, 0x02000340)? {
                if int64(value)? != self.id {
                    return Ok(Progress::default());
                }
            }
            if let Some(value) = optional(&f, 0x010003be)? {
                if int32(value)? != self.sequence {
                    return Ok(Progress::default());
                }
            }
            if let Some(value) = optional(&f, 0x0100034a)? {
                ensure!(int32(value)? == 0, "unexpected join context");
            }
            let status = int64(field(&f, 0x02000303)?)?;
            if status != 0 {
                return Ok(Progress::done(format!("Network join refused (server status {status:#x}); check the network name and password"), true));
            }
            let auth = self
                .auth
                .as_mut()
                .ok_or_else(|| anyhow::anyhow!("password required for this network"))?;
            let blob = field(&f, 0x0a00030e)?;
            if blob == crypto::sh_record(0x10000000, &0u32.to_be_bytes()) {
                return Ok(Progress::done(
                    "Network password authentication failed; check the password".into(),
                    true,
                ));
            }
            let next = match auth.step {
                2 => Some(auth.sh.parameters(blob)?),
                4 => Some(auth.sh.challenge(blob)?),
                6 => {
                    auth.sh.confirm(blob)?;
                    None
                }
                _ => anyhow::bail!("unexpected network authentication message"),
            };
            auth.step += 2;
            return Ok(Progress {
                send: next.map(|b| continuation(&b, self.sequence)),
                complete: None,
            });
        }
        if operation == 42 && matches!(self.purpose, Purpose::Join(_)) {
            let f = records(field(&outer, 0x131f)?)?;
            for r in f.iter().filter(|r| r.tag == 0x131e) {
                let f = records(r.value)?;
                // JoinApproved lists existing members of the approved network,
                // not necessarily our own RID. The op37 response separately
                // binds the network name and GUID to this request.
                let id = field(&f, 0x0d000309)?;
                ensure!(id.len() == 16, "invalid approved network ID");
                self.approvals.insert(hex::encode(id));
            }
        }
        if operation == 37 {
            let f = records(field(&outer, 0x131a)?)?;
            if int64(field(&f, 0x02000340)?)? != self.id {
                return Ok(Progress::default());
            }
            ensure!(
                int32(field(&f, 0x0100030c)?)? == self.action(),
                "network response action mismatch"
            );
            if !matches!(self.purpose, Purpose::Join(_)) {
                ensure!(
                    int32(field(&f, 0x0100034a)?)? == self.sequence,
                    "network response context mismatch"
                );
            }
            if let Some(code) = optional(&f, 0x010001d2)? {
                let code = int32(code)?;
                let hint = if matches!(self.purpose, Purpose::Leave(_)) && code == 19 {
                    "; grant admin to another member before leaving, or delete the network"
                } else {
                    ""
                };
                return Ok(Progress::done(
                    format!("Network operation refused (server error {code}){hint}"),
                    true,
                ));
            }
            match &self.purpose {
                Purpose::Join(name) => {
                    ensure!(
                        self.auth.as_ref().is_none_or(|a| a.step == 8),
                        "private join completed before server password proof"
                    );
                    let root = field(&f, 0x1316)?;
                    let mut returned = Membership::default();
                    returned.snapshot(&tlv(0x1316, root))?;
                    self.joined = returned
                        .networks
                        .values()
                        .find(|n| &n.name == name)
                        .cloned();
                    ensure!(self.joined.is_some(), "join response network name mismatch");
                    membership.snapshot(&tlv(0x1316, root))?;
                    let n = self.joined.as_ref().expect("validated network");
                    if membership.role(&n.network_id, membership.own_rid) == Some(0) {
                        let mut progress = Progress::done(format!("Requested membership in {name}; waiting for administrator approval"), false);
                        progress.complete.as_mut().unwrap().pending_approval = true;
                        return Ok(progress);
                    }
                }
                Purpose::Create(name) => {
                    let network = field(&f, 0x1315)?;
                    let mut returned = Membership::default();
                    let snapshot = tlv(0x1316, &tlv(0x1315, network));
                    returned.snapshot(&snapshot)?;
                    ensure!(
                        returned.networks.values().any(|n| &n.name == name),
                        "create response network name mismatch"
                    );
                    membership.snapshot(&snapshot)?;
                    return Ok(Progress::done(format!("Created {name}"), false));
                }
                Purpose::Leave(network)
                | Purpose::Delete(network)
                | Purpose::Member(network, _, _) => {
                    ensure!(
                        hex::encode(field(&f, 0x0d000309)?) == *network,
                        "network response ID mismatch"
                    );
                    if let Purpose::Member(_, member, action) = self.purpose {
                        ensure!(
                            int64(field(&f, 0x020001e1)?)? == member,
                            "network response member mismatch"
                        );
                        match action {
                            MemberAction::Kick => membership.remove_member(network, member),
                            MemberAction::GrantAdmin | MemberAction::RevokeAdmin => {
                                membership.roles.entry(network.clone()).or_default().insert(
                                    member,
                                    if action == MemberAction::GrantAdmin {
                                        2
                                    } else {
                                        1
                                    },
                                );
                            }
                        }
                        return Ok(Progress::done(
                            format!("{} completed", action.label()),
                            false,
                        ));
                    }
                    membership.remove_network(network);
                    return Ok(Progress::done(
                        if matches!(self.purpose, Purpose::Delete(_)) {
                            "Deleted network"
                        } else {
                            "Left network"
                        }
                        .into(),
                        false,
                    ));
                }
            }
        }
        if let Some(n) = &self.joined {
            if self.approvals.contains(&n.network_id) {
                return Ok(Progress::done(format!("Joined {}", n.name), false));
            }
        }
        Ok(Progress::default())
    }
}
