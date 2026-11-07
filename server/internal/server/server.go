// Package server implements the OpenRad/Radmin-compatible coordination
// service: registration, attachment sessions, network management, peer
// connection brokering, the TCP relay and the UDP endpoint service.
package server

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/binary"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	"github.com/gringoestrangeiro/openrad/server/internal/rcrypto"
	"github.com/gringoestrangeiro/openrad/server/internal/store"
	"github.com/gringoestrangeiro/openrad/server/internal/wire"
)

// Config controls one server instance.
type Config struct {
	Listen      string // control TCP address (Python, Android and Minecraft clients only dial port 17301)
	RelayListen string // relay TCP address
	RelayPort   int    // advertised relay port (defaults to RelayListen's port)
	PublicIP    net.IP // advertised IPv4; nil uses the address each client connected to
	UESHosts    []net.IP

	Store    *store.Store
	Key      *rsa.PrivateKey
	ServerID uint32

	AutoPublic        bool // a public join of an unknown name creates the network
	AllowPublicCreate bool // create requests with a nonzero type create public networks
	RegistrationLimit int  // registrations per source IP per hour; 0 = unlimited
	SearchPageSize    int

	Logger  *log.Logger
	Verbose bool
}

// Server is the shared coordination state. mu guards every map below and
// serializes all membership mutations with their notifications.
type Server struct {
	cfg Config
	log *log.Logger

	mu     sync.Mutex
	online map[uint64]*Session // ready attachment sessions by RID
	conns  map[uint64]*Conn    // brokered peer connections by CID

	relay   *Relay
	limiter *rateLimiter

	listeners []net.Listener
	closing   chan struct{}
	wg        sync.WaitGroup
}

func New(cfg Config) (*Server, error) {
	if cfg.Store == nil || cfg.Key == nil {
		return nil, errors.New("server requires a store and an RSA key")
	}
	if cfg.ServerID == 0 {
		return nil, errors.New("server ID must be nonzero")
	}
	if cfg.SearchPageSize <= 0 {
		cfg.SearchPageSize = 100
	}
	if cfg.Logger == nil {
		cfg.Logger = log.Default()
	}
	if cfg.PublicIP != nil && cfg.PublicIP.To4() == nil {
		return nil, errors.New("public IP must be IPv4")
	}
	for _, ip := range cfg.UESHosts {
		ip4 := ip.To4()
		if ip4 == nil || ip4.IsLoopback() || ip4.IsUnspecified() || ip4.IsMulticast() || ip4.Equal(net.IPv4bcast) {
			return nil, fmt.Errorf("UES host %s must be a routable IPv4 address (clients reject loopback)", ip)
		}
	}
	s := &Server{
		cfg:     cfg,
		log:     cfg.Logger,
		online:  map[uint64]*Session{},
		conns:   map[uint64]*Conn{},
		limiter: newRateLimiter(cfg.RegistrationLimit, time.Hour),
		closing: make(chan struct{}),
	}
	s.relay = newRelay(s)
	return s, nil
}

func (s *Server) debugf(format string, args ...any) {
	if s.cfg.Verbose {
		s.log.Printf(format, args...)
	}
}

// Serve starts every listener and blocks until Close or a fatal listener error.
func (s *Server) Serve() error {
	ctl, err := net.Listen("tcp4", s.cfg.Listen)
	if err != nil {
		return fmt.Errorf("control listener: %w", err)
	}
	rl, err := net.Listen("tcp4", s.cfg.RelayListen)
	if err != nil {
		ctl.Close()
		return fmt.Errorf("relay listener: %w", err)
	}
	if s.cfg.RelayPort == 0 {
		s.cfg.RelayPort = rl.Addr().(*net.TCPAddr).Port
	}
	s.listeners = append(s.listeners, ctl, rl)
	if err := s.startUES(); err != nil {
		s.Close()
		return err
	}
	s.log.Printf("control listening on %s, relay on %s (advertised port %d)", ctl.Addr(), rl.Addr(), s.cfg.RelayPort)
	errc := make(chan error, 2)
	go func() { errc <- s.acceptLoop(ctl, s.handleControl) }()
	go func() { errc <- s.acceptLoop(rl, s.relay.handle) }()
	err = <-errc
	select {
	case <-s.closing:
		return nil
	default:
		s.Close()
		return err
	}
}

func (s *Server) acceptLoop(l net.Listener, handle func(net.Conn)) error {
	for {
		c, err := l.Accept()
		if err != nil {
			select {
			case <-s.closing:
				return nil
			default:
			}
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				time.Sleep(50 * time.Millisecond)
				continue
			}
			return err
		}
		if tc, ok := c.(*net.TCPConn); ok {
			tc.SetNoDelay(true)
			tc.SetKeepAlive(true)
			tc.SetKeepAlivePeriod(30 * time.Second)
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			handle(c)
		}()
	}
}

// Close stops listeners and disconnects every session.
func (s *Server) Close() {
	select {
	case <-s.closing:
		return
	default:
		close(s.closing)
	}
	for _, l := range s.listeners {
		l.Close()
	}
	s.mu.Lock()
	for _, sess := range s.online {
		sess.Close()
	}
	for _, c := range s.conns {
		c.a.Close()
	}
	s.mu.Unlock()
	s.relay.closeAll()
}

// ---- sessions ----

const (
	sendQueue        = 4096
	handshakeTimeout = 30 * time.Second
	attachIdle       = 120 * time.Second
	coordIdle        = 30 * time.Minute
)

// Session is one authenticated control connection (purpose 3, 4 or 5).
type Session struct {
	srv     *Server
	conn    net.Conn
	cc      *rcrypto.CChannel
	purpose uint32
	rid     uint64
	user    *store.User
	name    string // node name from this login
	local   net.IP // server address this client dialled
	remote  net.IP

	out       chan []byte
	done      chan struct{}
	closeOnce sync.Once

	// guarded by srv.mu
	ready bool
	coord *Conn // purpose 5: the brokered connection this session initiated

	// reader goroutine only
	joins map[uint32]*joinAuth
}

func (ss *Session) String() string {
	return fmt.Sprintf("rid=%d purpose=%d from=%s", ss.rid, ss.purpose, ss.remote)
}

// Send queues a plaintext record. A full queue disconnects the slow client.
func (ss *Session) Send(msg []byte) {
	select {
	case <-ss.done:
		return
	default:
	}
	select {
	case ss.out <- msg:
	default:
		ss.srv.log.Printf("%s: send queue full, disconnecting", ss)
		ss.Close()
	}
}

// CloseAfter flushes queued records, then closes.
func (ss *Session) CloseAfter() {
	select {
	case ss.out <- nil:
	default:
		ss.Close()
	}
}

func (ss *Session) Close() {
	ss.closeOnce.Do(func() {
		close(ss.done)
		ss.conn.Close()
	})
}

func (ss *Session) writer() {
	defer ss.Close()
	for {
		select {
		case <-ss.done:
			return
		case msg := <-ss.out:
			if msg == nil {
				// Give the client a moment to read the final record.
				time.Sleep(200 * time.Millisecond)
				return
			}
			ct, err := ss.cc.Encrypt(msg)
			if err != nil {
				ss.srv.log.Printf("%s: encrypt: %v", ss, err)
				return
			}
			ss.conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
			if err := wire.WriteFrame(ss.conn, ct); err != nil {
				return
			}
		}
	}
}

func (ss *Session) receive(idle time.Duration) ([]byte, error) {
	ss.conn.SetReadDeadline(time.Now().Add(idle))
	ct, err := wire.ReadFrame(ss.conn, wire.MaxWire)
	if err != nil {
		return nil, err
	}
	return ss.cc.Decrypt(ct)
}

// serverAddress is the IPv4 literal this client should use to reach us: the
// configured public IP, otherwise the address the client connected to (the
// handshake refuses connections without a usable local IPv4 address).
func (ss *Session) serverAddress() string {
	if ip := ss.srv.cfg.PublicIP; ip != nil {
		return ip.To4().String()
	}
	return ss.local.To4().String()
}

var bootstrapPassword = []byte("rpassword")

// handshake runs the RSession + SH exchange and returns the decrypted Login.
func (s *Server) handshake(c net.Conn) (*Session, []byte, error) {
	c.SetDeadline(time.Now().Add(handshakeTimeout))
	hello, err := wire.ReadFrame(c, 4)
	if err != nil {
		return nil, nil, fmt.Errorf("reading RSession marker: %w", err)
	}
	if string(hello) != "\x03\x00\x00\x00" {
		return nil, nil, errors.New("unexpected RSession marker")
	}
	f6, err := wire.ReadFrame(c, 4096)
	if err != nil {
		return nil, nil, fmt.Errorf("reading RSession: %w", err)
	}
	rs, err := rcrypto.OpenRSession(s.cfg.Key, f6)
	if err != nil {
		return nil, nil, err
	}
	if err := wire.WriteFrame(c, []byte{3, 0, 0, 0}); err != nil {
		return nil, nil, err
	}
	cc, err := rcrypto.NewCChannel(rs.Secret[:32])
	if err != nil {
		return nil, nil, err
	}
	recv := func() ([]byte, error) {
		ct, err := wire.ReadFrame(c, 65536)
		if err != nil {
			return nil, err
		}
		return cc.Decrypt(ct)
	}
	send := func(pt []byte) error {
		ct, err := cc.Encrypt(pt)
		if err != nil {
			return err
		}
		return wire.WriteFrame(c, ct)
	}
	m1, err := recv()
	if err != nil {
		return nil, nil, fmt.Errorf("reading SH hello: %w", err)
	}
	rid, err := rcrypto.HelloRID(m1)
	if err != nil {
		return nil, nil, err
	}
	ss := &Session{srv: s, conn: c, cc: cc, purpose: rs.Purpose, rid: rid,
		out: make(chan []byte, sendQueue), done: make(chan struct{}), joins: map[uint32]*joinAuth{}}
	if a, ok := c.LocalAddr().(*net.TCPAddr); ok {
		ss.local = a.IP
	}
	if a, ok := c.RemoteAddr().(*net.TCPAddr); ok {
		ss.remote = a.IP
	}
	if s.cfg.PublicIP == nil {
		if ip4 := ss.local.To4(); ip4 == nil || ip4.IsUnspecified() {
			return nil, nil, errors.New("no IPv4 local address to advertise; set a public IP")
		}
	}
	var salt, verifier []byte
	if rs.Purpose == 3 {
		if rid < 1 || rid > 1000 {
			return nil, nil, fmt.Errorf("bootstrap selector %d outside 1..1000", rid)
		}
		salt = randomBytes(16)
		verifier = rcrypto.Verifier(rid, bootstrapPassword, salt)
	} else {
		u, err := s.cfg.Store.User(rid)
		if errors.Is(err, store.ErrNotFound) {
			return nil, nil, fmt.Errorf("unknown identity %d", rid)
		}
		if err != nil {
			return nil, nil, err
		}
		ss.user = u
		salt, verifier = u.Salt, u.Verifier
	}
	sh, err := rcrypto.NewSHServer(rid, salt, verifier, nil)
	if err != nil {
		return nil, nil, err
	}
	f10, err := sh.Hello(m1)
	if err != nil {
		return nil, nil, err
	}
	if err := send(f10); err != nil {
		return nil, nil, err
	}
	m3, err := recv()
	if err != nil {
		return nil, nil, fmt.Errorf("reading SH public: %w", err)
	}
	f12, err := sh.Public(m3)
	if err != nil {
		return nil, nil, err
	}
	if err := send(f12); err != nil {
		return nil, nil, err
	}
	m5, err := recv()
	if err != nil {
		return nil, nil, fmt.Errorf("reading SH proof: %w", err)
	}
	f14, key, err := sh.Proof(m5)
	if err != nil {
		return nil, nil, fmt.Errorf("rid %d: %w (wrong credential)", rid, err)
	}
	if err := send(f14); err != nil {
		return nil, nil, err
	}
	if err := cc.Rekey(key[:32]); err != nil {
		return nil, nil, err
	}
	ct, err := wire.ReadFrame(c, 65536)
	if err != nil {
		return nil, nil, fmt.Errorf("reading login: %w", err)
	}
	login, err := cc.Decrypt(ct)
	if err != nil {
		return nil, nil, err
	}
	c.SetDeadline(time.Time{})
	return ss, login, nil
}

// LoginInfo is the decoded op-20 Login.
type LoginInfo struct {
	Name    string
	Latency uint32
	PeerRID uint64 // purpose 5 only
}

func parseLogin(data []byte, purpose uint32) (*LoginInfo, error) {
	op, recs, err := wire.Op(data, wire.ClientOp)
	if err != nil {
		return nil, err
	}
	if op != 20 {
		return nil, fmt.Errorf("expected Login (20), received %d", op)
	}
	idv, err := recs.Field(0x122f)
	if err != nil {
		return nil, err
	}
	idRecs, err := wire.Parse(idv)
	if err != nil {
		return nil, err
	}
	name, err := idRecs.Text(0x03000304)
	if err != nil {
		return nil, err
	}
	if err := validateText(name, 255); err != nil {
		return nil, fmt.Errorf("node name: %w", err)
	}
	info := &LoginInfo{Name: name}
	if v, ok, _ := recs.OptU32(0x01000343); ok {
		info.Latency = v
	}
	if purpose == 5 {
		if info.PeerRID, err = recs.U64(0x0200030b); err != nil {
			return nil, err
		}
	}
	return info, nil
}

func validateText(s string, maxUnits int) error {
	if len(s) == 0 {
		return errors.New("empty text")
	}
	units := 0
	blank := true
	for _, r := range s {
		if r < 32 || r == 127 || (r >= 0x80 && r < 0xa0) {
			return errors.New("control character in text")
		}
		if r != ' ' && r != '\t' {
			blank = false
		}
		units++
		if r > 0xffff {
			units++
		}
	}
	if blank || units > maxUnits {
		return errors.New("text is blank or too long")
	}
	return nil
}

func (s *Server) handleControl(c net.Conn) {
	ss, login, err := s.handshake(c)
	if err != nil {
		s.debugf("handshake from %s failed: %v", c.RemoteAddr(), err)
		c.Close()
		return
	}
	info, err := parseLogin(login, ss.purpose)
	if err != nil {
		s.log.Printf("%s: invalid login: %v", ss, err)
		c.Close()
		return
	}
	ss.name = info.Name
	go ss.writer()
	defer ss.Close()
	switch ss.purpose {
	case 3:
		s.register(ss)
	case 4:
		s.attach(ss)
	case 5:
		s.coordinate(ss, info.PeerRID)
	}
}

// LoginComplete (op 21) carries the server ID and, when configured, UES hosts.
func (s *Server) loginComplete() []byte {
	fields := [][]byte{wire.U32(0x0100025b, s.cfg.ServerID)}
	if len(s.cfg.UESHosts) >= 2 {
		var hosts []byte
		for _, ip := range s.cfg.UESHosts {
			hosts = append(hosts, wire.ASCIIZ(0x0e00036c, ip.To4().String())...)
		}
		fields = append(fields, wire.TLV(0x1340, hosts))
	}
	return wire.ServerMsg(21, fields...)
}

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}

func randomU64() uint64 {
	for {
		if v := binary.BigEndian.Uint64(randomBytes(8)); v != 0 {
			return v
		}
	}
}

// ---- registration rate limiting ----

type rateLimiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	hits   map[string][]time.Time
	swept  time.Time
}

func newRateLimiter(limit int, window time.Duration) *rateLimiter {
	return &rateLimiter{limit: limit, window: window, hits: map[string][]time.Time{}}
}

func (r *rateLimiter) allow(key string) bool {
	if r.limit <= 0 {
		return true
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	// Forget sources whose newest hit left the window so one-off addresses
	// do not accumulate forever.
	if now.Sub(r.swept) >= r.window {
		for k, ts := range r.hits {
			if len(ts) == 0 || now.Sub(ts[len(ts)-1]) >= r.window {
				delete(r.hits, k)
			}
		}
		r.swept = now
	}
	kept := r.hits[key][:0]
	for _, t := range r.hits[key] {
		if now.Sub(t) < r.window {
			kept = append(kept, t)
		}
	}
	if len(kept) >= r.limit {
		r.hits[key] = kept
		return false
	}
	r.hits[key] = append(kept, now)
	return true
}
