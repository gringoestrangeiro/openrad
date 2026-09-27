//! Bounded TLVs, network memberships and peer control.
use anyhow::{anyhow, ensure, Result};
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    net::Ipv4Addr,
    path::Path,
};
pub const SERVER_OP: u32 = 0x01000320;
pub const CLIENT_OP: u32 = 0x0100031f;

#[derive(Clone, Copy)]
pub struct Record<'a> {
    pub tag: u32,
    pub value: &'a [u8],
}
pub fn records(data: &[u8]) -> Result<Vec<Record<'_>>> {
    ensure!(data.len() <= 4 * 1024 * 1024, "TLV payload exceeds limit");
    let mut out = vec![];
    let mut at = 0;
    while at < data.len() {
        ensure!(
            data.len() - at >= 8 && out.len() < 16384,
            "invalid TLV header/count"
        );
        let len = u32::from_be_bytes(data[at..at + 4].try_into()?) as usize;
        let tag = u32::from_be_bytes(data[at + 4..at + 8].try_into()?);
        at += 8;
        ensure!(len <= data.len() - at, "truncated TLV body");
        out.push(Record {
            tag,
            value: &data[at..at + len],
        });
        at += len;
    }
    Ok(out)
}
pub fn optional<'a>(r: &[Record<'a>], tag: u32) -> Result<Option<&'a [u8]>> {
    let mut values = r.iter().filter(|r| r.tag == tag);
    let first = values.next().map(|r| r.value);
    ensure!(values.next().is_none(), "duplicate singleton {tag:#x}");
    Ok(first)
}
pub fn field<'a>(r: &[Record<'a>], tag: u32) -> Result<&'a [u8]> {
    optional(r, tag)?.ok_or_else(|| anyhow!("missing field {tag:#x}"))
}
pub fn int32(b: &[u8]) -> Result<u32> {
    ensure!(b.len() == 4, "invalid u32 width");
    Ok(u32::from_be_bytes(b.try_into()?))
}
pub fn int64(b: &[u8]) -> Result<u64> {
    ensure!(b.len() == 8, "invalid u64 width");
    Ok(u64::from_be_bytes(b.try_into()?))
}
fn utf16(b: &[u8]) -> Result<Vec<u16>> {
    ensure!(
        (2..=8192).contains(&b.len()) && b.len().is_multiple_of(2) && b.ends_with(&[0, 1]),
        "invalid UTF16 text"
    );
    Ok(b[..b.len() - 2]
        .as_chunks::<2>()
        .0
        .iter()
        .map(|b| u16::from_be_bytes([b[0], b[1]]))
        .collect())
}
pub fn text(b: &[u8]) -> Result<String> {
    let s = String::from_utf16(&utf16(b)?)?;
    ensure!(!s.contains('\0'), "embedded NUL in text");
    Ok(s)
}
/// Names chosen by other users are decoded leniently: one member's truncated
/// emoji must not make the whole membership message, and the session, fail.
pub fn display_text(b: &[u8]) -> Result<String> {
    Ok(String::from_utf16_lossy(&utf16(b)?).replace('\0', "\u{fffd}"))
}
pub fn tlv(tag: u32, b: &[u8]) -> Vec<u8> {
    [
        (b.len() as u32).to_be_bytes().to_vec(),
        tag.to_be_bytes().to_vec(),
        b.to_vec(),
    ]
    .concat()
}
pub fn u32v(tag: u32, v: u32) -> Vec<u8> {
    tlv(tag, &v.to_be_bytes())
}
pub fn u64v(tag: u32, v: u64) -> Vec<u8> {
    tlv(tag, &v.to_be_bytes())
}
pub fn textv(tag: u32, s: &str) -> Result<Vec<u8>> {
    ensure!(
        s.chars().all(|c| c as u32 >= 32),
        "control character in text"
    );
    let mut b: Vec<u8> = s.encode_utf16().flat_map(u16::to_be_bytes).collect();
    ensure!(b.len() <= 512, "text too long");
    b.extend([0, 1]);
    Ok(tlv(tag, &b))
}
pub fn op(data: &[u8]) -> Result<u32> {
    int32(field(&records(data)?, SERVER_OP)?)
}

/// PushInfo (6), root 0x1236, repeated client addresses (0x127d).
/// Only consume these records after control-session authentication and CID matching.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct TcpCandidate {
    pub endpoint: std::net::SocketAddr,
    /// Opaque native 0x0b000360 flag; it is not proof of LAN reachability.
    pub server_flag: bool,
}
pub fn tcp_candidates(data: &[u8], connection_id: u64) -> Result<Vec<TcpCandidate>> {
    let (candidates, exclusions) = direct_candidates(data, connection_id, 6, 0x1236)?;
    ensure!(
        exclusions.is_empty(),
        "invalid candidate: {}",
        exclusions.join("; ")
    );
    Ok(candidates)
}
pub fn direct_candidates(
    data: &[u8],
    connection_id: u64,
    operation: u32,
    root: u32,
) -> Result<(Vec<TcpCandidate>, Vec<String>)> {
    let outer = records(data)?;
    ensure!(op(data)? == operation, "unexpected candidate operation");
    ensure!(
        int64(field(&outer, 0x020001c1)?)? == connection_id,
        "candidate correlation mismatch"
    );
    let Some(body) = optional(&outer, root)? else {
        return Ok((vec![], vec![]));
    };
    let fields = records(body)?;
    let mut candidates = vec![];
    let mut exclusions = vec![];
    for (index, r) in fields.iter().filter(|r| r.tag == 0x127d).enumerate() {
        ensure!(index < 32, "direct candidate limit");
        let parsed = (|| -> Result<TcpCandidate> {
            let f = records(r.value)?;
            let ip: std::net::IpAddr = text(field(&f, 0x030001c2)?)?.parse()?;
            let port = int32(field(&f, 0x010001c3)?)?;
            ensure!((1..=65535).contains(&port), "invalid candidate port");
            ensure!(
                !ip.is_unspecified()
                    && !ip.is_multicast()
                    && !matches!(ip, std::net::IpAddr::V4(v) if v.is_broadcast())
                    && !matches!(ip, std::net::IpAddr::V6(v) if v.is_unicast_link_local()),
                "invalid candidate address"
            );
            let server_flag = optional(&f, 0x0b000360)?
                .map(int32)
                .transpose()?
                .unwrap_or(0)
                != 0;
            Ok(TcpCandidate {
                endpoint: std::net::SocketAddr::new(ip, port as u16),
                server_flag,
            })
        })();
        match parsed {
            Ok(candidate) if !candidates.contains(&candidate) => candidates.push(candidate),
            Ok(_) => {}
            Err(e) => exclusions.push(format!("candidate {index}: {e}")),
        }
    }
    Ok((candidates, exclusions))
}
/// The outgoing-only role advertises no listener addresses.
pub fn request_tcp_candidates(connection_id: u64) -> Vec<u8> {
    [u32v(CLIENT_OP, 2), u64v(0x020001c1, connection_id)].concat()
}
pub fn advertise_tcp(cid: u64, endpoints: &[std::net::SocketAddr]) -> Result<Vec<u8>> {
    ensure!(
        !endpoints.is_empty() && endpoints.len() <= 32,
        "TCP advertisement bounds"
    );
    let mut body = vec![];
    for endpoint in endpoints {
        body.extend(tlv(
            0x127d,
            &[
                textv(0x030001c2, &endpoint.ip().to_string())?,
                u32v(0x010001c3, endpoint.port() as u32),
            ]
            .concat(),
        ));
    }
    Ok([request_tcp_candidates(cid), tlv(0x1230, &body)].concat())
}
/// Direct UDP candidate advertisement (28).
pub fn advertise_udp(
    connection_id: u64,
    endpoints: &[std::net::SocketAddr],
    nonce: u16,
) -> Result<Vec<u8>> {
    ensure!(
        !endpoints.is_empty() && endpoints.len() <= 32,
        "UDP advertisement bounds"
    );
    let mut body = vec![];
    for endpoint in endpoints {
        body.extend(tlv(
            0x127d,
            &[
                textv(0x030001c2, &endpoint.ip().to_string())?,
                u32v(0x010001c3, endpoint.port() as u32),
            ]
            .concat(),
        ));
    }
    if nonce != 0 {
        body.extend(u32v(0x0100020a, nonce as u32));
    }
    Ok([
        u32v(CLIENT_OP, 28),
        u64v(0x020001c1, connection_id),
        tlv(0x127b, &body),
    ]
    .concat())
}

/// Native UES list: authenticated LoginComplete root 0x1340, UTF-8 tag
/// 0x0e00036c.
/// Root 0x1263 / 0x03000237 is the connection-server configuration, not UES.
pub fn ues_hosts(data: &[u8]) -> Result<Vec<std::net::Ipv4Addr>> {
    ensure!(op(data)? == 21, "expected LoginComplete");
    let fields = records(data)?;
    let Some(body) = optional(&fields, 0x1340)? else {
        return Ok(vec![]);
    };
    let mut hosts = vec![];
    for (index, r) in records(body)?
        .into_iter()
        .filter(|r| r.tag == 0x0e00036c)
        .enumerate()
    {
        ensure!(index < 32, "UES host count limit");
        ensure!(
            (2..=16).contains(&r.value.len()) && r.value.last() == Some(&0),
            "invalid UES host text"
        );
        let ip: std::net::Ipv4Addr = std::str::from_utf8(&r.value[..r.value.len() - 1])?.parse()?;
        ensure!(
            !ip.is_unspecified() && !ip.is_multicast() && !ip.is_broadcast() && !ip.is_loopback(),
            "invalid UES host"
        );
        if !hosts.contains(&ip) {
            hosts.push(ip);
        }
    }
    Ok(hosts)
}
/// Cone mapping verified at two native UES servers; no predicted ports are sent.
pub fn advertise_mapped_udp(cid: u64, endpoint: std::net::SocketAddr) -> Result<Vec<u8>> {
    advertise_mapped_udp_with_nonce(cid, endpoint, 0)
}
pub fn advertise_mapped_udp_with_nonce(
    cid: u64,
    endpoint: std::net::SocketAddr,
    nonce: u16,
) -> Result<Vec<u8>> {
    Ok([
        u32v(CLIENT_OP, 3),
        u64v(0x020001c1, cid),
        tlv(
            0x1231,
            &[
                textv(0x030001c4, &endpoint.ip().to_string())?,
                u32v(0x010001c5, endpoint.port() as u32),
                u32v(0x0b0001dc, 0),
                u32v(0x0b0001db, 0),
                if nonce == 0 {
                    vec![]
                } else {
                    u32v(0x0100020a, nonce as u32)
                },
            ]
            .concat(),
        ),
    ]
    .concat())
}
pub fn mapped_udp_candidate(data: &[u8], cid: u64) -> Result<(TcpCandidate, u16)> {
    let f = records(data)?;
    ensure!(
        op(data)? == 7 && int64(field(&f, 0x020001c1)?)? == cid,
        "mapped candidate correlation"
    );
    let f = records(field(&f, 0x1237)?)?;
    let ip: std::net::Ipv4Addr = text(field(&f, 0x030001c4)?)?.parse()?;
    let port = int32(field(&f, 0x010001c5)?)?;
    let nonce = int32(field(&f, 0x0100020a)?)?;
    ensure!(
        !ip.is_unspecified()
            && !ip.is_broadcast()
            && !ip.is_multicast()
            && (1..=65535).contains(&port)
            && (1..=65535).contains(&nonce),
        "invalid mapped UDP candidate"
    );
    Ok((
        TcpCandidate {
            endpoint: std::net::SocketAddr::new(ip.into(), port as u16),
            server_flag: false,
        },
        nonce as u16,
    ))
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Identity {
    pub format: String,
    pub rid: u64,
    pub vip: Ipv4Addr,
    pub node_name: String,
    pub address: String,
    credential: String,
    pub server_address: String,
}
impl Identity {
    pub fn bootstrap(name: &str, host: &str) -> Result<Self> {
        use rand::Rng;
        host.parse::<Ipv4Addr>()?;
        ensure!(!name.is_empty(), "node name required");
        textv(0x03000304, name)?;
        Ok(Self {
            format: "openrad-identity-v1".into(),
            rid: rand::thread_rng().gen_range(1..=1000),
            vip: Ipv4Addr::UNSPECIFIED,
            node_name: name.into(),
            address: String::new(),
            // Public bootstrap value used only before registration issues a unique credential.
            credential: hex::encode(b"rpassword"),
            server_address: host.into(),
        })
    }
    pub fn registered(data: &[u8]) -> Result<Self> {
        ensure!(op(data)? == 8, "expected Registered");
        let r = records(data)?;
        let f = records(field(&r, 0x1233)?)?;
        let identity = Self {
            format: "openrad-identity-v1".into(),
            rid: int64(field(&f, 0x020001e1)?)?,
            vip: Ipv4Addr::from(int32(field(&f, 0x01000305)?)?),
            node_name: text(field(&f, 0x03000304)?)?,
            address: hex::encode(field(&f, 0x09000305)?),
            credential: hex::encode(field(&f, 0x0a0001c8)?),
            server_address: text(field(&f, 0x030001e2)?)?,
        };
        ensure!(
            identity.rid > 0
                && identity.vip != Ipv4Addr::UNSPECIFIED
                && identity.vip != Ipv4Addr::BROADCAST,
            "invalid assigned identity"
        );
        ensure!(
            identity.address.len() == 32 && (6..=4096).contains(&identity.password()?.len()),
            "invalid assigned credential/address"
        );
        identity.server_address.parse::<Ipv4Addr>()?;
        Ok(identity)
    }
    pub fn save(&self, reports: &crate::output::ReportDirectory) -> Result<()> {
        reports.json("identity.json",&serde_json::json!({"format":self.format,"rid":self.rid,
            "vip":self.vip,"node_name":self.node_name,"address":self.address,"credential":self.credential,
            "server_address":self.server_address,"legacy_credential":""}))
    }
    pub fn load(path: &Path) -> Result<Self> {
        ensure!(
            std::fs::metadata(path)?.len() <= 65536,
            "identity file too large"
        );
        Self::from_secret(&std::fs::read(path)?)
    }
    /// Parse a credential-store record without ever formatting its contents.
    pub fn from_secret(bytes: &[u8]) -> Result<Self> {
        ensure!(bytes.len() <= 65536, "identity record too large");
        let identity: Self =
            serde_json::from_slice(bytes).map_err(|_| anyhow!("invalid identity file"))?;
        ensure!(
            identity.format == "openrad-identity-v1" && identity.rid != 0,
            "unsupported identity"
        );
        ensure!(
            identity.vip != Ipv4Addr::UNSPECIFIED && identity.vip != Ipv4Addr::BROADCAST,
            "invalid VIP"
        );
        ensure!(
            hex::decode(&identity.address)?.len() == 16,
            "invalid companion address"
        );
        ensure!(
            (6..=4096).contains(&identity.password()?.len()),
            "invalid credential length"
        );
        identity.server_address.parse::<Ipv4Addr>()?;
        ensure!(!identity.node_name.is_empty(), "empty node name");
        Ok(identity)
    }
    pub fn password(&self) -> Result<Vec<u8>> {
        hex::decode(&self.credential).map_err(|_| anyhow!("invalid credential encoding"))
    }
}

pub fn login(name: &str, latency: u32, purpose: u32, peer: Option<u64>) -> Result<Vec<u8>> {
    ensure!(
        !name.is_empty() && [3, 4, 5].contains(&purpose),
        "invalid login profile"
    );
    let mut body = [
        u32v(CLIENT_OP, 20),
        u32v(0x010003e0, 11),
        tlv(
            0x122f,
            &[
                u32v(0x0100023b, 31),
                u32v(0x0100025a, 0),
                textv(0x03000304, name)?,
            ]
            .concat(),
        ),
        tlv(
            0x1286,
            &[u32v(0x010001ec, 8), textv(0x030001ed, "2.1.4951.1")?].concat(),
        ),
    ]
    .concat();
    if purpose == 4 {
        let values = [
            (0x01000337, 10),
            (0x01000338, 0),
            (0x01000339, 19045),
            (0x0100033a, 0),
            (0x0b00033b, 0),
            (0x0b00039f, 1),
            (0x010003c8, 0),
            (0x010003c9, 34404),
            (0x0b00033c, 1),
            (0x010003dc, 1),
        ];
        body.extend(tlv(
            0x1331,
            &values
                .into_iter()
                .flat_map(|(t, v)| u32v(t, v))
                .collect::<Vec<_>>(),
        ));
    }
    if purpose != 3 && latency > 0 {
        body.extend(u32v(0x01000343, latency));
    }
    if purpose == 5 {
        body.extend(u64v(
            0x0200030b,
            peer.ok_or_else(|| anyhow!("peer RID required"))?,
        ));
        body.extend(u32v(0x01000377, 0));
    }
    Ok(body)
}
pub fn public_list(query: &str, id: u64, cursor: u64) -> Result<Vec<u8>> {
    Ok([
        u32v(CLIENT_OP, 43),
        tlv(
            0x1332,
            &[
                u64v(0x02000340, id),
                u64v(0x0200030f, cursor),
                textv(0x03000306, query)?,
            ]
            .concat(),
        ),
    ]
    .concat())
}
pub fn join(name: &str, id: u64, seq: u32) -> Result<Vec<u8>> {
    ensure!(!name.is_empty() && seq > 0, "invalid join request");
    Ok([
        u32v(CLIENT_OP, 39),
        tlv(
            0x131c,
            &[
                textv(0x03000306, name)?,
                u32v(0x0b00033f, 1),
                u64v(0x02000340, id),
                u32v(0x010003be, seq),
            ]
            .concat(),
        ),
    ]
    .concat())
}
pub fn request_relay(cid: u64) -> Vec<u8> {
    [u32v(CLIENT_OP, 22), u64v(0x020001c1, cid)].concat()
}
pub enum JoinResult<'a> {
    Membership(&'a [u8]),
    Refused(u32),
}
/// Public JOIN (operation 39) does not echo ManageNetwork2's sequence field.
/// Correlate by request ID and action, then validate the returned network name.
pub fn join_result(data: &[u8], id: u64) -> Result<Option<JoinResult<'_>>> {
    if op(data)? != 37 {
        return Ok(None);
    }
    let r = records(data)?;
    let f = records(field(&r, 0x131a)?)?;
    if int64(field(&f, 0x02000340)?)? != id {
        return Ok(None);
    }
    ensure!(
        int32(field(&f, 0x0100030c)?)? == 2,
        "join response command mismatch"
    );
    if let Some(error) = optional(&f, 0x010001d2)? {
        return Ok(Some(JoinResult::Refused(int32(error)?)));
    }
    Ok(Some(JoinResult::Membership(field(&f, 0x1316)?)))
}
/// Request leaving a network with the ManageNetwork2 operation.
pub fn leave(network: &str, id: u64, seq: u32) -> Result<Vec<u8>> {
    let guid = hex::decode(network)?;
    ensure!(guid.len() == 16 && id > 0, "invalid leave request");
    Ok([
        u32v(CLIENT_OP, 52),
        tlv(
            0x1319,
            &[
                u32v(0x0100030c, 3),
                u64v(0x02000340, id),
                u32v(0x0100034a, seq),
                tlv(0x0d000309, &guid),
            ]
            .concat(),
        ),
    ]
    .concat())
}
/// A network command response echoes its verb; only 0x010001d2 is a failure.
pub fn leave_result(
    data: &[u8],
    id: u64,
    sequence: u32,
    network: &str,
) -> Result<Option<Option<u32>>> {
    if op(data)? != 37 {
        return Ok(None);
    }
    let r = records(data)?;
    let f = records(field(&r, 0x131a)?)?;
    if int64(field(&f, 0x02000340)?)? != id {
        return Ok(None);
    }
    ensure!(
        int32(field(&f, 0x0100030c)?)? == 3,
        "leave response command mismatch"
    );
    ensure!(
        int32(field(&f, 0x0100034a)?)? == sequence,
        "leave response sequence mismatch"
    );
    if let Some(error) = optional(&f, 0x010001d2)? {
        return Ok(Some(Some(int32(error)?)));
    }
    ensure!(
        hex::encode(field(&f, 0x0d000309)?) == network,
        "leave response network mismatch"
    );
    Ok(Some(None))
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PublicNetwork {
    pub name: String,
    pub reported_count: u32,
}
pub fn listing_id(data: &[u8]) -> Result<u64> {
    let r = records(data)?;
    let f = records(field(&r, 0x1334)?)?;
    int64(field(&f, 0x02000340)?)
}
pub fn listing(data: &[u8], id: u64) -> Result<(Vec<PublicNetwork>, u64)> {
    let r = records(data)?;
    let f = records(field(&r, 0x1334)?)?;
    ensure!(
        int64(field(&f, 0x02000340)?)? == id,
        "listing correlation mismatch"
    );
    let mut out = vec![];
    for r in f.iter().filter(|r| r.tag == 0x1333) {
        let n = records(r.value)?;
        out.push(PublicNetwork {
            name: display_text(field(&n, 0x03000306)?)?,
            reported_count: int32(field(&n, 0x01000341)?)?,
        });
    }
    Ok((out, int64(field(&f, 0x0200030f)?)?))
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Network {
    pub name: String,
    pub network_id: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Peer {
    pub rid: u64,
    pub name: String,
    pub vip: Ipv4Addr,
    pub server: Option<String>,
    pub state: u32,
    pub network_ids: BTreeSet<String>,
}
#[derive(Clone, Default, Serialize)]
pub struct Membership {
    pub own_rid: u64,
    pub roles: BTreeMap<String, BTreeMap<u64, u32>>,
    pub networks: BTreeMap<String, Network>,
    pub peers: BTreeMap<u64, Peer>,
}
fn network_id(b: &[u8]) -> Result<String> {
    ensure!(b.len() == 16, "invalid network ID");
    Ok(hex::encode(b))
}

/// Native NodeRemoved/NodeStatus events carry the subject RID followed by an
/// optional source RID using the same tag. Other message kinds remain strict
/// singletons. See docs/re-engineering/network-management/member-events.md.
fn event_member(fields: &[Record<'_>]) -> Result<u64> {
    let mut ids = fields.iter().filter(|field| field.tag == 0x020001e1);
    let member = int64(
        ids.next()
            .ok_or_else(|| anyhow::anyhow!("missing event member"))?
            .value,
    )?;
    if let Some(source) = ids.next() {
        int64(source.value)?;
    }
    ensure!(ids.next().is_none(), "too many member event identifiers");
    Ok(member)
}
impl Membership {
    pub fn remove_network(&mut self, id: &str) {
        self.networks.remove(id);
        self.roles.remove(id);
        self.peers.retain(|_, peer| {
            peer.network_ids.remove(id);
            !peer.network_ids.is_empty()
        });
    }
    pub fn role(&self, network: &str, rid: u64) -> Option<u32> {
        self.roles.get(network)?.get(&rid).copied()
    }
    pub fn remove_member(&mut self, network: &str, rid: u64) {
        if rid == self.own_rid && rid != 0 {
            self.remove_network(network);
            return;
        }
        if let Some(roles) = self.roles.get_mut(network) {
            roles.remove(&rid);
        }
        if let Some(peer) = self.peers.get_mut(&rid) {
            peer.network_ids.remove(network);
            if peer.network_ids.is_empty() {
                self.peers.remove(&rid);
            }
        }
    }
    fn read_role(&mut self, fields: &[Record<'_>], rid: u64) -> Result<()> {
        if let Some(value) = optional(fields, 0x0100030a)? {
            let id = network_id(field(fields, 0x0d000309)?)?;
            self.roles.entry(id).or_default().insert(rid, int32(value)?);
        }
        Ok(())
    }
    pub fn snapshot(&mut self, data: &[u8]) -> Result<()> {
        let r = records(data)?;
        let Some(root) = optional(&r, 0x1316)? else {
            return Ok(());
        };
        let entries = records(root)?;
        for r in &entries {
            if r.tag == 0x1315 {
                let f = records(r.value)?;
                let id = network_id(field(&f, 0x0d000309)?)?;
                if self.own_rid != 0 {
                    if let Some(role) = optional(&f, 0x0100030a)? {
                        self.roles
                            .entry(id.clone())
                            .or_default()
                            .insert(self.own_rid, int32(role)?);
                    }
                }
                self.networks.insert(
                    id.clone(),
                    Network {
                        name: display_text(field(&f, 0x03000306)?)?,
                        network_id: id,
                    },
                );
            } else if r.tag == 0x1317 {
                let f = records(r.value)?;
                let rid = int64(field(&f, 0x020001e1)?)?;
                let network_ids = self
                    .peers
                    .get(&rid)
                    .map(|p| p.network_ids.clone())
                    .unwrap_or_default();
                self.peers.insert(
                    rid,
                    Peer {
                        rid,
                        name: display_text(field(&f, 0x03000304)?)?,
                        vip: Ipv4Addr::from(int32(field(&f, 0x01000305)?)?),
                        server: optional(&f, 0x030001c9)?.map(text).transpose()?,
                        state: optional(&f, 0x010003a0)?
                            .map(int32)
                            .transpose()?
                            .unwrap_or(0),
                        network_ids,
                    },
                );
            }
        }
        for r in entries.iter().filter(|r| r.tag == 0x1318) {
            let f = records(r.value)?;
            let rid = int64(field(&f, 0x020001e1)?)?;
            self.read_role(&f, rid)?;
            if let Some(p) = self.peers.get_mut(&rid) {
                p.network_ids.insert(network_id(field(&f, 0x0d000309)?)?);
            }
        }
        Ok(())
    }
    pub fn changes(&mut self, data: &[u8]) -> Result<()> {
        let r = records(data)?;
        let Some(root) = optional(&r, 0x131f)? else {
            return Ok(());
        };
        let entries = records(root)?;
        for r in entries.iter().filter(|r| r.tag == 0x131e) {
            let f = records(r.value)?;
            let rid = int64(field(&f, 0x020001e1)?)?;
            self.read_role(&f, rid)?;
            let id = network_id(field(&f, 0x0d000309)?)?;
            if let Some(vip) = optional(&f, 0x01000305)? {
                let old = self.peers.remove(&rid);
                let mut ids = old
                    .as_ref()
                    .map(|p| p.network_ids.clone())
                    .unwrap_or_default();
                ids.insert(id);
                self.peers.insert(
                    rid,
                    Peer {
                        rid,
                        name: display_text(field(&f, 0x03000304)?)?,
                        vip: Ipv4Addr::from(int32(vip)?),
                        server: optional(&f, 0x030001c9)?.map(text).transpose()?,
                        state: old.map(|p| p.state).unwrap_or(0),
                        network_ids: ids,
                    },
                );
            }
        }
        for r in entries.iter().filter(|r| r.tag == 0x1323) {
            self.snapshot(&tlv(0x1316, &tlv(0x1315, r.value)))?;
        }
        for r in entries.iter().filter(|r| r.tag == 0x1318) {
            let f = records(r.value)?;
            self.read_role(&f, event_member(&f)?)?;
        }
        for r in entries.iter().filter(|r| r.tag == 0x131d) {
            let f = records(r.value)?;
            self.remove_member(&network_id(field(&f, 0x0d000309)?)?, event_member(&f)?);
        }
        for r in entries.iter().filter(|r| r.tag == 0x132e) {
            let f = records(r.value)?;
            self.remove_network(&network_id(field(&f, 0x0d000309)?)?);
        }
        for r in entries.iter().filter(|r| r.tag == 0x1366) {
            let f = records(r.value)?;
            if let Some(p) = self.peers.get_mut(&int64(field(&f, 0x020001e1)?)?) {
                p.state = int32(field(&f, 0x010003a0)?)?;
            }
        }
        Ok(())
    }
    pub fn eligible(&self, own: u64, names: &[String]) -> Result<Vec<Peer>> {
        for name in names {
            ensure!(
                self.networks.values().any(|n| &n.name == name),
                "requested network missing: {name}"
            );
        }
        let ids: BTreeSet<_> = self
            .networks
            .values()
            .filter(|n| names.is_empty() || names.contains(&n.name))
            .map(|n| n.network_id.clone())
            .collect();
        Ok(self
            .peers
            .values()
            .filter(|p| {
                p.rid != own
                    && p.server.is_some()
                    && [1, 5].contains(&p.state)
                    && p.network_ids
                        .intersection(&ids)
                        .any(|id| self.role(id, own) != Some(0) && self.role(id, p.rid) != Some(0))
            })
            .cloned()
            .collect())
    }
}
pub fn own_vip(data: &[u8]) -> Result<Option<Ipv4Addr>> {
    if op(data)? != 38 {
        return Ok(None);
    }
    let r = records(data)?;
    let f = records(field(&r, 0x1316)?)?;
    let Some(ip) = optional(&f, 0x01000305)? else {
        return Ok(None);
    };
    ensure!(
        field(&f, 0x09000305)?.len() == 16,
        "invalid companion address"
    );
    let ip = Ipv4Addr::from(int32(ip)?);
    ensure!(
        ip != Ipv4Addr::UNSPECIFIED && ip != Ipv4Addr::BROADCAST,
        "invalid own VIP"
    );
    Ok(Some(ip))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer(rid: u64, name_utf16: &[u8]) -> Vec<u8> {
        let mut name = name_utf16.to_vec();
        name.extend([0, 1]);
        tlv(
            0x1317,
            &[
                u64v(0x020001e1, rid),
                tlv(0x03000304, &name),
                u32v(0x01000305, 0x1a000002),
            ]
            .concat(),
        )
    }
    #[test]
    fn truncated_emoji_in_a_peer_name_does_not_reject_the_snapshot() {
        let good = peer(1, &[0, b'o', 0, b'k']);
        let half_emoji = peer(2, &[0, b'x', 0xd8, 0x3d]);
        let data = [
            u32v(SERVER_OP, 38),
            tlv(0x1316, &[good, half_emoji].concat()),
        ]
        .concat();
        let mut membership = Membership::default();
        membership.snapshot(&data).unwrap();
        assert_eq!(membership.peers[&1].name, "ok");
        assert_eq!(membership.peers[&2].name, "x\u{fffd}");
        assert!(text(&[0xd8, 0x3d, 0, 1]).is_err());
    }
}
