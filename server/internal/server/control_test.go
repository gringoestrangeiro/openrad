package server

import (
	"errors"
	"io"
	"log"
	"net"
	"path/filepath"
	"testing"
	"time"

	"github.com/gringoestrangeiro/openrad/server/internal/rcrypto"
	"github.com/gringoestrangeiro/openrad/server/internal/store"
	"github.com/gringoestrangeiro/openrad/server/internal/wire"
)

func testServer(t *testing.T) *Server {
	t.Helper()
	db, err := store.Open(filepath.Join(t.TempDir(), "state.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return &Server{
		cfg:    Config{Store: db, ServerID: 1},
		log:    log.New(io.Discard, "", 0),
		online: map[uint64]*Session{},
		conns:  map[uint64]*Conn{},
	}
}

func testUser(t *testing.T, s *Server, name string) *store.User {
	t.Helper()
	u, err := s.cfg.Store.CreateUser(name, []byte{1}, func(uint64) []byte { return []byte{2} })
	if err != nil {
		t.Fatal(err)
	}
	return u
}

type testControl struct {
	t      *testing.T
	conn   net.Conn
	cipher *rcrypto.CChannel
	exited <-chan struct{}
}

func openTestControl(t *testing.T, s *Server, u *store.User, purpose uint32, serve func(*Session)) *testControl {
	t.Helper()
	// Start at the authenticated control boundary with real framed records.
	key := make([]byte, 32)
	serverCipher, err := rcrypto.NewCChannel(key)
	if err != nil {
		t.Fatal(err)
	}
	clientCipher, err := rcrypto.NewCChannel(key)
	if err != nil {
		t.Fatal(err)
	}
	client, server := net.Pipe()
	ss := &Session{
		srv: s, conn: server, cc: serverCipher, purpose: purpose,
		rid: u.RID, user: u, name: u.Name,
		out: make(chan []byte, sendQueue), done: make(chan struct{}),
	}
	exited := make(chan struct{})
	go ss.writer()
	go func() {
		defer close(exited)
		defer ss.Close()
		serve(ss)
	}()
	c := &testControl{t: t, conn: client, cipher: clientCipher, exited: exited}
	t.Cleanup(c.close)
	return c
}

func testAttach(t *testing.T, s *Server, u *store.User) *testControl {
	t.Helper()
	c := openTestControl(t, s, u, 4, s.attach)
	c.expect(21)
	c.expect(38)
	return c
}

func (c *testControl) close() {
	c.conn.Close()
	<-c.exited
}

func (c *testControl) send(op uint32) {
	c.t.Helper()
	ct, err := c.cipher.Encrypt(wire.U32(wire.ClientOp, op))
	if err != nil {
		c.t.Fatal(err)
	}
	if err := c.conn.SetWriteDeadline(time.Now().Add(2 * time.Second)); err != nil {
		c.t.Fatal(err)
	}
	if err := wire.WriteFrame(c.conn, ct); err != nil {
		c.t.Fatal(err)
	}
}

func (c *testControl) receive() ([]byte, error) {
	c.t.Helper()
	if err := c.conn.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil && !errors.Is(err, io.ErrClosedPipe) {
		return nil, err
	}
	ct, err := wire.ReadFrame(c.conn, wire.MaxWire)
	if err != nil {
		return nil, err
	}
	return c.cipher.Decrypt(ct)
}

func (c *testControl) expect(expected uint32) wire.Records {
	c.t.Helper()
	record, err := c.receive()
	if err != nil {
		c.t.Fatalf("waiting for server operation %d: %v", expected, err)
	}
	op, recs, err := wire.Op(record, wire.ServerOp)
	if err != nil || op != expected {
		c.t.Fatalf("expected server operation %d, got %d (%v)", expected, op, err)
	}
	return recs
}

func (c *testControl) expectEOF() {
	c.t.Helper()
	_, err := c.receive()
	if !errors.Is(err, io.EOF) {
		c.t.Fatalf("expected server to close connection, got %v", err)
	}
}

func TestAttachmentRespondsToHeartbeat(t *testing.T) {
	s := testServer(t)
	c := testAttach(t, s, testUser(t, s, "Synthetic Minecraft client"))
	for range 2 {
		c.send(4)
		c.expect(17)
	}
}

func TestReplacingAttachmentDisconnectsOnlyPreviousSession(t *testing.T) {
	s := testServer(t)
	u := testUser(t, s, "Synthetic Minecraft client")
	previous := testAttach(t, s, u)
	replacement := testAttach(t, s, u)
	reason, err := previous.expect(16).U32(0x010001d2)
	if err != nil || reason != 1 {
		t.Fatalf("expected replacement reason 1, got %d (%v)", reason, err)
	}
	previous.expectEOF()
	replacement.send(4)
	replacement.expect(17)
}

func TestPeerRefusalLeavesAttachmentAvailable(t *testing.T) {
	s := testServer(t)
	u := testUser(t, s, "Synthetic Fabric")
	target := testUser(t, s, "Synthetic Paper")
	attachment := testAttach(t, s, u)
	testAttach(t, s, target)
	// Both RIDs are online but share no authorized network.
	coord := openTestControl(t, s, u, 5, func(ss *Session) { s.coordinate(ss, target.RID) })
	rid, err := coord.expect(44).U64(tagRID)
	if err != nil || rid != target.RID {
		t.Fatalf("expected refused RID %d, got %d (%v)", target.RID, rid, err)
	}
	coord.expectEOF()
	attachment.send(4)
	attachment.expect(17)
}

func TestAcceptorDisconnectClosesPeerCoordinator(t *testing.T) {
	s := testServer(t)
	initiator := testUser(t, s, "Synthetic Fabric")
	acceptor := testUser(t, s, "Synthetic Paper")
	network, err := s.cfg.Store.CreateNetwork("Synthetic Minecraft", true, nil, nil, false, 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, u := range []*store.User{initiator, acceptor} {
		if err := s.cfg.Store.AddMember(network.ID, u.RID, store.RoleMember); err != nil {
			t.Fatal(err)
		}
	}
	attachment := testAttach(t, s, acceptor)
	coord := openTestControl(t, s, initiator, 5, func(ss *Session) { s.coordinate(ss, acceptor.RID) })
	coord.expect(21)
	coord.expect(11)
	attachment.expect(11)
	attachment.close()
	coord.expectEOF()
}
