#![cfg(any(unix, windows))]

use anyhow::{Context, Result};
use openrad::{
    daemon::{CustomServer, DataDir},
    network::NetworkRequest,
    output::ReportDirectory,
    session::Session,
};
use std::{net::Ipv4Addr, path::PathBuf, time::Duration};

#[test]
#[ignore = "requires a fresh loopback coordinator, run scripts/test-self-hosted.py"]
fn registration_catalog_join_and_reattach_use_the_selected_server() -> Result<()> {
    let root = PathBuf::from(
        std::env::var_os("OPENRAD_TEST_COORDINATOR_DIR")
            .context("missing disposable coordinator directory")?,
    );
    let port = std::env::var("OPENRAD_TEST_COORDINATOR_PORT")?.parse()?;
    let endpoint = CustomServer::new(Ipv4Addr::LOCALHOST, port)?;
    let selection = (endpoint, Some(std::fs::read(root.join("modulus.bin"))?));
    let reports = ReportDirectory::disabled();
    let session_timeout = Duration::from_secs(5);
    let mut users = Vec::new();
    for name in ["Integration A", "Integration B"] {
        let dir = DataDir::open(Some(root.join(name)))?;
        dir.set_server(Some(selection.clone()))?;
        let dir = DataDir::open(Some(dir.path.clone()))?;
        let server = dir.use_server()?;
        assert_eq!(server.port, port);
        assert_eq!(server.host, "127.0.0.1");

        let identity = Session::provision_controlled_at_port(
            &server.modulus,
            name,
            &server.host,
            server.port,
            &reports,
            &mut || Ok(()),
            None,
            &mut |_| {},
        )?;
        assert_eq!(identity.server_address, "127.0.0.1");
        let (mut session, mut membership, vip) = Session::attach_at_port(
            &identity,
            &server.modulus,
            port,
            &reports,
            session_timeout,
            None,
        )?;
        let (networks, _) = session.list_public("OpenRad loopback test")?;
        assert!(networks
            .iter()
            .any(|network| network.name == "OpenRad loopback test"));
        let joined = session.network_operation(
            NetworkRequest::public_join("OpenRad loopback test".into()),
            2,
            2,
            &mut membership,
        )?;
        assert!(!joined.error, "{}", joined.message);
        users.push((identity, vip, session));
    }
    let (identity, vip, connection) = users.pop().unwrap();
    drop(connection);
    let (_, membership, vip_after) = Session::attach_at_port(
        &identity,
        selection.1.as_ref().unwrap(),
        port,
        &reports,
        session_timeout,
        None,
    )?;
    assert_eq!(vip, vip_after);
    assert_eq!(membership.own_rid, identity.rid);
    assert!(membership.peers.contains_key(&users[0].0.rid));
    Ok(())
}
