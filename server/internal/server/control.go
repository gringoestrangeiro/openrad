package server

import (
	"bytes"
	"time"

	"github.com/gringoestrangeiro/openrad/server/internal/rcrypto"
	"github.com/gringoestrangeiro/openrad/server/internal/store"
	"github.com/gringoestrangeiro/openrad/server/internal/wire"
)

// Tags shared by snapshots and change notifications.
const (
	tagRID       = 0x020001e1
	tagNodeName  = 0x03000304
	tagVIP       = 0x01000305
	tagAddress   = 0x09000305
	tagServer    = 0x030001c9
	tagState     = 0x010003a0
	tagNetworkID = 0x0d000309
	tagNetName   = 0x03000306
	tagRole      = 0x0100030a
	tagRequestID = 0x02000340
	tagCID       = 0x020001c1
)

// register handles purpose 3: LoginComplete, then Registered (op 8).
func (s *Server) register(ss *Session) {
	if !s.limiter.allow(ss.remote.String()) {
		s.log.Printf("registration from %s refused: rate limit", ss.remote)
		ss.Send(wire.ServerMsg(16, wire.U32(0x010001d2, 2)))
		ss.CloseAfter()
		<-ss.done
		return
	}
	ss.Send(s.loginComplete())
	credential := randomBytes(32)
	salt := randomBytes(16)
	u, err := s.cfg.Store.CreateUser(ss.name, salt, func(rid uint64) []byte {
		return rcrypto.Verifier(rid, credential, salt)
	})
	if err != nil {
		s.log.Printf("registration from %s failed: %v", ss.remote, err)
		return
	}
	ss.Send(wire.ServerMsg(8, wire.TLV(0x1233, wire.Cat(
		wire.U64(tagRID, u.RID),
		wire.U32(tagVIP, u.VIP),
		wire.Text(tagNodeName, u.Name),
		wire.TLV(tagAddress, u.Address),
		wire.TLV(0x0a0001c8, credential),
		wire.Text(0x030001e2, ss.serverAddress()),
	))))
	s.log.Printf("registered rid=%d vip=%s name=%q from %s", u.RID, ipString(u.VIP), u.Name, ss.remote)
	// The client closes after storing its identity; wait briefly for that.
	ss.conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	for {
		if _, err := wire.ReadFrame(ss.conn, wire.MaxWire); err != nil {
			return
		}
	}
}

func ipString(v uint32) string {
	return string(appendIP(nil, v))
}

func appendIP(b []byte, v uint32) []byte {
	for i := 3; i >= 0; i-- {
		n := byte(v >> (8 * i))
		if n >= 100 {
			b = append(b, '0'+n/100)
		}
		if n >= 10 {
			b = append(b, '0'+n/10%10)
		}
		b = append(b, '0'+n%10)
		if i > 0 {
			b = append(b, '.')
		}
	}
	return b
}

// attach handles purpose 4: the long-lived attachment session.
func (s *Server) attach(ss *Session) {
	u := ss.user
	renamed := ss.name != u.Name
	if renamed {
		if err := s.cfg.Store.RenameUser(u.RID, ss.name); err != nil {
			s.log.Printf("%s: rename: %v", ss, err)
		}
		u.Name = ss.name
	}
	s.cfg.Store.TouchUser(u.RID)
	ss.Send(s.loginComplete())

	s.mu.Lock()
	if old := s.online[ss.rid]; old != nil {
		delete(s.online, ss.rid)
		old.ready = false
		old.Send(wire.ServerMsg(16, wire.U32(0x010001d2, 1)))
		old.CloseAfter()
		s.dropConnsLocked(old)
		s.log.Printf("rid=%d: replaced previous session from %s", ss.rid, old.remote)
	}
	snap, err := s.snapshotLocked(ss)
	if err != nil {
		s.mu.Unlock()
		s.log.Printf("%s: snapshot: %v", ss, err)
		return
	}
	ss.Send(snap)
	ss.ready = true
	s.online[ss.rid] = ss
	s.announceLocked(ss)
	s.mu.Unlock()
	s.log.Printf("online rid=%d vip=%s name=%q from %s", u.RID, ipString(u.VIP), u.Name, ss.remote)

	defer func() {
		s.mu.Lock()
		if s.online[ss.rid] == ss {
			delete(s.online, ss.rid)
			ss.ready = false
			s.announceOfflineLocked(ss.rid)
			s.log.Printf("offline rid=%d", ss.rid)
		}
		s.dropConnsLocked(ss)
		s.mu.Unlock()
		s.cfg.Store.TouchUser(ss.rid)
	}()

	for {
		data, err := ss.receive(attachIdle)
		if err != nil {
			s.debugf("%s: closed: %v", ss, err)
			return
		}
		op, recs, err := wire.Op(data, wire.ClientOp)
		if err != nil {
			s.debugf("%s: undecodable record: %v", ss, err)
			continue
		}
		switch op {
		case 4: // heartbeat
		case 43:
			s.search(ss, recs)
		case 39:
			s.join(ss, recs)
		case 52:
			s.manage(ss, recs)
		case 2, 3, 22, 28:
			s.connRecord(ss, op, recs)
		default:
			s.debugf("%s: ignoring client operation %d", ss, op)
		}
	}
}

// isOnlineLocked reports whether rid has a ready attachment session.
func (s *Server) isOnlineLocked(rid uint64) bool {
	o := s.online[rid]
	return o != nil && o.ready
}

// peerFields describes a member as seen by viewer.
func (s *Server) peerFields(u *store.User, viewer *Session) []byte {
	var state uint32
	if s.isOnlineLocked(u.RID) {
		state = 1
	}
	return wire.Cat(
		wire.U64(tagRID, u.RID),
		wire.Text(tagNodeName, u.Name),
		wire.U32(tagVIP, u.VIP),
		wire.Text(tagServer, viewer.serverAddress()),
		wire.U32(tagState, state),
	)
}

// userLocked prefers the live session's user record.
func (s *Server) userLocked(rid uint64, cache map[uint64]*store.User) (*store.User, error) {
	if u, ok := cache[rid]; ok {
		return u, nil
	}
	var u *store.User
	if o := s.online[rid]; o != nil && o.user != nil {
		u = o.user
	} else {
		var err error
		if u, err = s.cfg.Store.User(rid); err != nil {
			return nil, err
		}
	}
	if cache != nil {
		cache[rid] = u
	}
	return u, nil
}

func networkEntry(n *store.Network, role uint32) []byte {
	return wire.TLV(0x1315, wire.Cat(wire.TLV(tagNetworkID, n.ID), wire.Text(tagNetName, n.Name), wire.U32(tagRole, role)))
}

func memberEntry(m store.Member) []byte {
	return wire.TLV(0x1318, wire.Cat(wire.U64(tagRID, m.RID), wire.TLV(tagNetworkID, m.NetworkID), wire.U32(tagRole, m.Role)))
}

// snapshotLocked builds op 38: own address pair, networks, peers and roles.
func (s *Server) snapshotLocked(viewer *Session) ([]byte, error) {
	u := viewer.user
	nets, roles, err := s.cfg.Store.Memberships(u.RID)
	if err != nil {
		return nil, err
	}
	shared, err := s.cfg.Store.SharedMemberships(u.RID)
	if err != nil {
		return nil, err
	}
	body := wire.Cat(wire.U32(tagVIP, u.VIP), wire.TLV(tagAddress, u.Address))
	for i, n := range nets {
		body = append(body, networkEntry(n, roles[i])...)
	}
	body = append(body, s.rosterLocked(shared, viewer)...)
	return wire.ServerMsg(38, wire.TLV(0x1316, body)), nil
}

// rosterLocked renders 0x1317 peers then 0x1318 roles for the given rows,
// excluding the viewer itself.
func (s *Server) rosterLocked(rows []store.Member, viewer *Session) []byte {
	var peers, roles []byte
	cache := map[uint64]*store.User{}
	for _, m := range rows {
		if m.RID == viewer.rid {
			continue
		}
		if _, seen := cache[m.RID]; !seen {
			pu, err := s.userLocked(m.RID, cache)
			if err != nil {
				s.log.Printf("roster: member %d: %v", m.RID, err)
				continue
			}
			peers = append(peers, wire.TLV(0x1317, s.peerFields(pu, viewer))...)
		}
		roles = append(roles, memberEntry(m)...)
	}
	return append(peers, roles...)
}

// announceLocked sends member upserts for ss to every online peer sharing a network.
func (s *Server) announceLocked(ss *Session) {
	shared, err := s.cfg.Store.SharedMemberships(ss.rid)
	if err != nil {
		s.log.Printf("%s: announce: %v", ss, err)
		return
	}
	own := map[string]uint32{}
	for _, m := range shared {
		if m.RID == ss.rid {
			own[string(m.NetworkID)] = m.Role
		}
	}
	recipients := map[uint64][][]byte{}
	var order []uint64
	for _, m := range shared {
		if m.RID == ss.rid || !s.isOnlineLocked(m.RID) {
			continue
		}
		if _, ok := recipients[m.RID]; !ok {
			order = append(order, m.RID)
		}
		recipients[m.RID] = append(recipients[m.RID], m.NetworkID)
	}
	for _, rid := range order {
		r := s.online[rid]
		var entries []byte
		for _, nid := range recipients[rid] {
			entries = append(entries, wire.TLV(0x131e, wire.Cat(
				wire.TLV(tagNetworkID, nid), wire.U32(tagRole, own[string(nid)]), s.peerFields(ss.user, r)))...)
		}
		r.Send(wire.ServerMsg(41, wire.TLV(0x131f, entries)))
	}
}

// announceOfflineLocked sends a presence change (state 0) for rid.
func (s *Server) announceOfflineLocked(rid uint64) {
	shared, err := s.cfg.Store.SharedMemberships(rid)
	if err != nil {
		s.log.Printf("rid=%d: offline announce: %v", rid, err)
		return
	}
	msg := wire.ServerMsg(41, wire.TLV(0x131f, wire.TLV(0x1366, wire.Cat(wire.U64(tagRID, rid), wire.U32(tagState, 0)))))
	sent := map[uint64]bool{rid: true}
	for _, m := range shared {
		if sent[m.RID] || !s.isOnlineLocked(m.RID) {
			continue
		}
		sent[m.RID] = true
		s.online[m.RID].Send(msg)
	}
}

// notifyMembersLocked sends op 41 built per recipient to online members of nid.
func (s *Server) notifyMembersLocked(nid []byte, skip map[uint64]bool, build func(r *Session) []byte) {
	members, err := s.cfg.Store.Members(nid)
	if err != nil {
		s.log.Printf("notify: %v", err)
		return
	}
	for _, m := range members {
		if skip[m.RID] || !s.isOnlineLocked(m.RID) {
			continue
		}
		r := s.online[m.RID]
		r.Send(wire.ServerMsg(41, wire.TLV(0x131f, build(r))))
	}
}

// search answers op 43 with op 45.
func (s *Server) search(ss *Session, recs wire.Records) {
	body, err := recs.Field(0x1332)
	if err != nil {
		return
	}
	f, err := wire.Parse(body)
	if err != nil {
		return
	}
	id, err := f.U64(tagRequestID)
	if err != nil {
		return
	}
	cursor, _, _ := f.OptU64(0x0200030f)
	query := ""
	if v, ok, _ := f.Optional(tagNetName); ok {
		query, _ = wire.DecodeText(v)
	}
	if cursor > 1<<31 {
		cursor = 1 << 31
	}
	list, more, err := s.cfg.Store.SearchPublic(query, int(cursor), s.cfg.SearchPageSize)
	if err != nil {
		s.log.Printf("%s: search: %v", ss, err)
		list, more = nil, false
	}
	var next uint64
	if more {
		next = cursor + uint64(len(list))
	}
	out := wire.Cat(wire.U64(tagRequestID, id), wire.U64(0x0200030f, next))
	for _, n := range list {
		out = append(out, wire.TLV(0x1333, wire.Cat(wire.Text(tagNetName, n.Name), wire.U32(0x01000341, uint32(n.Members))))...)
	}
	ss.Send(wire.ServerMsg(45, wire.TLV(0x1334, out)))
	s.debugf("%s: search %q cursor=%d -> %d results", ss, query, cursor, len(list))
}

func sameID(a, b []byte) bool { return bytes.Equal(a, b) }
