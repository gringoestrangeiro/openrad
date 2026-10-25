//! Standalone synthetic interop harness; links the unmodified canonical OpenRad library.
use openrad::{crypto::{Channel, ShClient}, output::ReportDirectory, peer::{PeerChannel, PeerStream, TransportPath}, protocol::Peer, session::Framed, tunnel, udp::Enet};
use std::{collections::BTreeSet, error::Error, io::{self, BufRead, Write}, net::{Ipv4Addr, SocketAddr, TcpListener, UdpSocket}, time::Duration};
type Result<T> = std::result::Result<T, Box<dyn Error>>;
const PASSWORD: &[u8] = b"interop-password";
fn frame(sequence: u8) -> Vec<u8> {(0..1514).map(|i| (i as u8).wrapping_add(sequence)).collect()}
fn exchange_accept(stream: PeerStream, path: TransportPath) -> Result<()> {
    let peer=Peer {rid:11,name:"Synthetic Java".into(),vip:Ipv4Addr::new(26,0,0,1),server:Some("127.0.0.1".into()),state:1,network_ids:BTreeSet::new()};
    let mut channel=PeerChannel::accept_authenticated(stream,path,PASSWORD,22,Ipv4Addr::new(26,0,0,2),peer,&ReportDirectory::disabled())?;
    channel.stream.sustain();
    for i in 0..50u8 {assert_eq!(channel.receive()?,tunnel::encode(&frame(i))?);assert!(channel.send(&tunnel::encode(&frame(i+1))?)?);}
    assert_eq!(channel.receive()?,[42]);Ok(())
}
fn exchange_initiate(mut stream: PeerStream) -> Result<()> {
    let mut sh=ShClient::new(22,PASSWORD)?;stream.send(&sh.start()?)?;let parameters=stream.receive(65536)?;stream.send(&sh.parameters(&parameters)?)?;let challenge=stream.receive(65536)?;stream.send(&sh.challenge(&challenge)?)?;let confirm=stream.receive(65536)?;let key=sh.confirm(&confirm)?;let mut channel=Channel::new(&key[..32])?;
    stream.send(&channel.encrypt(&[0xef,0xbe,0xad,0xde])?)?;stream.send(&channel.encrypt(&[0;16])?)?;stream.send(&channel.encrypt(&tunnel::syn(11,&tunnel::mac(Ipv4Addr::new(26,0,0,1))))?)?;
    let response=channel.decrypt(&stream.receive(65536)?)?;let(ack,mac,version)=tunnel::synack(&response)?;assert_eq!(mac,tunnel::mac(Ipv4Addr::new(26,0,0,2)));assert_eq!(version,8);stream.send(&channel.encrypt(&ack)?)?;stream.sustain();
    for i in 0..50u8 {stream.send(&channel.encrypt(&tunnel::encode(&frame(i))?)?)?;assert_eq!(channel.decrypt(&stream.receive(65536)?)?,tunnel::encode(&frame(i+1))?);}
    stream.send(&channel.encrypt(&[42])?)?;Ok(())
}
fn announce(address: SocketAddr){println!("{address}");io::stdout().flush().unwrap();}
fn udp_socket()->Result<UdpSocket>{
    // Match canonical bind_dual_stack: ENET maps v4 destinations onto its v6 socket.
    let socket=socket2::Socket::new(socket2::Domain::IPV6,socket2::Type::DGRAM,Some(socket2::Protocol::UDP))?;
    socket.set_only_v6(false)?;socket.bind(&"[::]:0".parse::<SocketAddr>()?.into())?;Ok(socket.into())
}
fn announce_udp(socket:&UdpSocket){announce(SocketAddr::new(Ipv4Addr::LOCALHOST.into(),socket.local_addr().unwrap().port()));}
fn main() -> Result<()> {
    let args:Vec<_>=std::env::args().collect();let duration=Duration::from_secs(20);
    match args[1].as_str(){
        "tcp-server"=>{let listener=TcpListener::bind("127.0.0.1:0")?;announce(listener.local_addr()?);let(socket,_)=listener.accept()?;let mut framed=Framed::from_socket(socket,duration,None)?;framed.accept_rendezvous(11,99)?;exchange_accept(PeerStream::Tcp(framed),TransportPath::DirectTcp)?;}
        "udp-server"=>{let socket=udp_socket()?;announce_udp(&socket);let mut line=String::new();io::stdin().lock().read_line(&mut line)?;let endpoint:SocketAddr=line.trim().parse()?;let stream=Enet::accept(socket,&[endpoint],81,duration,None)?;exchange_accept(PeerStream::Udp(stream),TransportPath::DirectUdp)?;}
        "tcp-client"=>{let endpoint:SocketAddr=args[2].parse()?;let mut stream=Framed::connect(&endpoint.ip().to_string(),endpoint.port(),duration)?;stream.peer_rendezvous(11,99,7)?;exchange_initiate(PeerStream::Tcp(stream))?;}
        "udp-client"=>{let endpoint:SocketAddr=args[2].parse()?;let socket=udp_socket()?;announce_udp(&socket);let stream=Enet::connect(socket,&[endpoint],81,duration,None)?;exchange_initiate(PeerStream::Udp(stream))?;}
        _=>panic!("unknown mode"),
    }
    println!("PASS");Ok(())
}
