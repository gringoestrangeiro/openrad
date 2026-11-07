package server

import (
	"time"

	"github.com/gringoestrangeiro/openrad/server/internal/store"
	"github.com/gringoestrangeiro/openrad/server/internal/wire"
)

// Conn is one brokered peer connection. Side 0 is the initiator's purpose-5
// coordinator session; side 1 is the acceptor's attachment session.
type Conn struct {
	cid      uint64
	password []byte
	a, b     *Session
	sides    [2]connSide
	created  time.Time
}

type connSide struct {
	sess       *Session
	requested  bool   // asked for TCP candidates (op 2)
	tcp        []byte // advertised 0x127d entries
	tcpSent    bool
	nonce      uint32 // UDP rendezvous nonce from op 28/3
	ticket     []byte
	ticketSent bool
}

func connectionOffer(cid, peer uint64, password []byte) []byte {
	return wire.ServerMsg(11, wire.U64(tagCID, cid), wire.TLV(0x1235, wire.Cat(
		wire.U64(tagRID, peer), wire.TLV(0x0a0001cd, password))))
}

// shareApprovedLocked: both members hold a non-pending role in one network.
func (s *Server) shareApprovedLocked(a, b uint64) bool {
	rows, err := s.cfg.Store.SharedMemberships(a)
	if err != nil {
		return false
	}
	mine := map[string]bool{}
	for _, m := range rows {
		if m.RID == a && m.Role != store.RolePending {
			mine[string(m.NetworkID)] = true
		}
	}
	for _, m := range rows {
		if m.RID == b && m.Role != store.RolePending && mine[string(m.NetworkID)] {
			return true
		}
	}
	return false
}

// coordinate handles purpose 5: the initiator asks to reach target.
func (s *Server) coordinate(ss *Session, target uint64) {
	s.mu.Lock()
	b := s.online[target]
	if b == nil || !b.ready || target == ss.rid || !s.shareApprovedLocked(ss.rid, target) {
		s.mu.Unlock()
		s.log.Printf("rid=%d -> %d: connection refused (peer offline or not shared)", ss.rid, target)
		ss.Send(wire.ServerMsg(44, wire.U64(tagRID, target)))
		ss.CloseAfter()
		<-ss.done
		return
	}
	cid := randomU64()
	for s.conns[cid] != nil {
		cid = randomU64()
	}
	c := &Conn{cid: cid, password: randomBytes(32), a: ss, b: b, created: time.Now()}
	c.sides[0].sess, c.sides[1].sess = ss, b
	s.conns[cid] = c
	ss.coord = c
	ss.Send(s.loginComplete())
	ss.Send(connectionOffer(cid, target, c.password))
	b.Send(connectionOffer(cid, ss.rid, c.password))
	s.mu.Unlock()
	s.log.Printf("rid=%d -> %d: brokering connection cid=%d", ss.rid, target, cid)

	defer func() {
		s.mu.Lock()
		if s.conns[cid] == c {
			delete(s.conns, cid)
		}
		s.mu.Unlock()
		s.debugf("cid=%d: coordinator closed", cid)
	}()
	for {
		data, err := ss.receive(coordIdle)
		if err != nil {
			return
		}
		op, recs, err := wire.Op(data, wire.ClientOp)
		if err != nil {
			continue
		}
		switch op {
		case 4:
		case 2, 3, 22, 28:
			if v, err := recs.U64(tagCID); err != nil || v != cid {
				s.debugf("cid=%d: initiator record for foreign CID", cid)
				continue
			}
			s.mu.Lock()
			s.connRecordLocked(c, 0, op, recs)
			s.mu.Unlock()
		default:
			s.debugf("cid=%d: ignoring initiator operation %d", cid, op)
		}
	}
}

// connRecord routes an acceptor's record from its attachment session.
func (s *Server) connRecord(ss *Session, op uint32, recs wire.Records) {
	cid, err := recs.U64(tagCID)
	if err != nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	c := s.conns[cid]
	if c == nil || c.b != ss {
		s.debugf("%s: record %d for unknown connection %d", ss, op, cid)
		return
	}
	s.connRecordLocked(c, 1, op, recs)
}

// dropConnsLocked forgets connections whose acceptor session ended.
func (s *Server) dropConnsLocked(ss *Session) {
	for cid, c := range s.conns {
		if c.b == ss {
			delete(s.conns, cid)
		}
	}
}

func (s *Server) connRecordLocked(c *Conn, x int, op uint32, recs wire.Records) {
	y := 1 - x
	X, Y := &c.sides[x], &c.sides[y]
	cidField := wire.U64(tagCID, c.cid)
	switch op {
	case 2: // TCP: a bare request, or an advertisement of listeners (0x1230)
		X.requested = true
		if body, ok, _ := recs.Optional(0x1230); ok {
			X.tcp = s.tcpCandidates(body, X.sess)
			X.tcpSent = false
		}
		for _, d := range [][2]int{{x, y}, {y, x}} {
			from, to := &c.sides[d[0]], &c.sides[d[1]]
			if to.requested && from.tcp != nil && !from.tcpSent {
				to.sess.Send(wire.ServerMsg(6, cidField, wire.TLV(0x1236, from.tcp)))
				from.tcpSent = true
				s.debugf("cid=%d: forwarded TCP candidates to side %d", c.cid, d[1])
			}
		}
	case 28: // local UDP candidates (and the acceptor's nonce)
		body, ok, _ := recs.Optional(0x127b)
		if !ok {
			return
		}
		if f, err := wire.Parse(body); err == nil {
			if n, ok, _ := f.OptU32(0x0100020a); ok && n != 0 {
				X.nonce = n
			}
		}
		Y.sess.Send(wire.ServerMsg(29, cidField, wire.TLV(0x127c, body)))
	case 3: // UES-discovered (mapped) UDP endpoint
		body, err := recs.Field(0x1231)
		if err != nil {
			return
		}
		f, err := wire.Parse(body)
		if err != nil {
			return
		}
		ip, err1 := f.Field(0x030001c4)
		port, err2 := f.Field(0x010001c5)
		if err1 != nil || err2 != nil {
			return
		}
		if n, ok, _ := f.OptU32(0x0100020a); ok && n != 0 {
			X.nonce = n
		}
		fields := wire.Cat(wire.TLV(0x030001c4, ip), wire.TLV(0x010001c5, port))
		// Both directions use the acceptor's nonce.
		if nonce := c.sides[1].nonce; nonce != 0 {
			fields = append(fields, wire.U32(0x0100020a, nonce)...)
		}
		Y.sess.Send(wire.ServerMsg(7, cidField, wire.TLV(0x1237, fields)))
	case 22: // relay request
		s.issueRelayLocked(c, x)
	}
}

// tcpCandidates keeps the advertised entries (at most 32) and appends the
// advertiser's observed public address on the first advertised port.
func (s *Server) tcpCandidates(body []byte, from *Session) []byte {
	recs, err := wire.Parse(body)
	if err != nil {
		return nil
	}
	var out []byte
	count, firstPort := 0, []byte(nil)
	seen := map[string]bool{}
	for _, v := range recs.All(0x127d) {
		if count >= 32 {
			break
		}
		f, err := wire.Parse(v)
		if err != nil {
			continue
		}
		if t, err := f.Text(0x030001c2); err == nil {
			seen[t] = true
		}
		if p, err := f.Field(0x010001c3); err == nil && firstPort == nil {
			firstPort = p
		}
		out = append(out, wire.TLV(0x127d, v)...)
		count++
	}
	ip := from.remote.To4()
	if count > 0 && count < 32 && firstPort != nil && ip != nil && ip.IsGlobalUnicast() && !ip.IsPrivate() && !seen[ip.String()] {
		out = append(out, wire.TLV(0x127d, wire.Cat(wire.Text(0x030001c2, ip.String()), wire.TLV(0x010001c3, firstPort)))...)
	}
	if out == nil {
		return []byte{}
	}
	return out
}

func relayOffer(cid uint64, host string, port int, ticket []byte) []byte {
	return wire.ServerMsg(23, wire.U64(tagCID, cid), wire.TLV(0x123f, wire.Cat(
		wire.Text(0x030001cb, host), wire.U32(0x010001cc, uint32(port)), wire.TLV(0x090001ca, ticket))))
}

// issueRelayLocked answers op 22. The acceptor is also sent its ticket when
// the initiator asks, because some clients never request one themselves.
func (s *Server) issueRelayLocked(c *Conn, x int) {
	if c.sides[0].ticket == nil {
		c.sides[0].ticket, c.sides[1].ticket = s.relay.issue(c.cid)
	}
	send := func(i int) {
		side := &c.sides[i]
		side.sess.Send(relayOffer(c.cid, side.sess.serverAddress(), s.cfg.RelayPort, side.ticket))
		side.ticketSent = true
	}
	send(x)
	if x == 0 && !c.sides[1].ticketSent {
		send(1)
	}
	s.debugf("cid=%d: relay tickets issued (requested by side %d)", c.cid, x)
}
