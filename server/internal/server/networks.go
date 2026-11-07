package server

import (
	"errors"
	"time"

	"github.com/gringoestrangeiro/openrad/server/internal/rcrypto"
	"github.com/gringoestrangeiro/openrad/server/internal/store"
	"github.com/gringoestrangeiro/openrad/server/internal/wire"
)

// Server error codes (tag 0x010001d2). Clients only display them, except
// 19, which the Rust client explains as "grant admin to another member
// before leaving, or delete the network".
const (
	errGeneric    = 1
	errNotFound   = 2
	errNotMember  = 3
	errPermission = 5
	errExists     = 6
	errInvalid    = 7
	errLastAdmin  = 19
	errFull       = 20
)

// Op-40 join statuses (tag 0x02000303); nonzero refuses the join.
const (
	joinUnknownNetwork  = 1
	joinPasswordNeeded  = 2
	joinNotPrivate      = 3
	joinMalformedHello  = 4
	joinPendingExpiry   = 60 * time.Second
	managementActionTag = 0x0100030c
	contextTag          = 0x0100034a
	errorTag            = 0x010001d2
	sequenceTag         = 0x010003be
	shBlobTag           = 0x0a00030e
)

type joinAuth struct {
	sh      *rcrypto.SHServer
	nid     []byte
	id      uint64
	step    int
	expires time.Time
}

func joinReply(id uint64, seq uint32, status uint64, blob []byte) []byte {
	fields := wire.Cat(wire.U64(0x02000303, status), wire.U64(tagRequestID, id), wire.U32(sequenceTag, seq))
	if blob != nil {
		fields = append(fields, wire.TLV(shBlobTag, blob)...)
	}
	return wire.ServerMsg(40, wire.TLV(0x131c, fields))
}

func joinError(id uint64, code uint32) []byte {
	return wire.ServerMsg(37, wire.TLV(0x131a, wire.Cat(
		wire.U32(managementActionTag, 2), wire.U64(tagRequestID, id), wire.U32(errorTag, code))))
}

// join handles op 39: a new public/private join, or a private-join continuation.
func (s *Server) join(ss *Session, recs wire.Records) {
	body, err := recs.Field(0x131c)
	if err != nil {
		return
	}
	f, err := wire.Parse(body)
	if err != nil {
		return
	}
	seq, _, _ := f.OptU32(sequenceTag)
	blob, hasBlob, _ := f.Optional(shBlobTag)
	nameV, hasName, _ := f.Optional(tagNetName)
	if !hasName {
		s.joinContinue(ss, seq, blob)
		return
	}
	id, err := f.U64(tagRequestID)
	if err != nil {
		return
	}
	name, err := wire.DecodeText(nameV)
	if err != nil || validateText(name, 255) != nil {
		ss.Send(joinReply(id, seq, joinUnknownNetwork, nil))
		return
	}
	flag, _, _ := f.OptU32(0x0b00033f)
	public := flag == 1

	s.mu.Lock()
	defer s.mu.Unlock()
	n, err := s.cfg.Store.NetworkByName(name)
	if errors.Is(err, store.ErrNotFound) {
		switch {
		case public && s.cfg.AutoPublic:
			if n, err = s.cfg.Store.CreateNetwork(name, true, nil, nil, false, 0); err != nil {
				s.log.Printf("%s: auto-create %q: %v", ss, name, err)
				ss.Send(joinError(id, errGeneric))
				return
			}
			s.log.Printf("auto-created public network %q", name)
		case public:
			ss.Send(joinError(id, errNotFound))
			return
		default:
			ss.Send(joinReply(id, seq, joinUnknownNetwork, nil))
			return
		}
	} else if err != nil {
		s.log.Printf("%s: join lookup: %v", ss, err)
		ss.Send(joinError(id, errGeneric))
		return
	}
	if public && !n.Public {
		ss.Send(joinReply(id, seq, joinPasswordNeeded, nil))
		return
	}
	if !public && n.Public {
		ss.Send(joinReply(id, seq, joinNotPrivate, nil))
		return
	}
	if public {
		s.completeJoinLocked(ss, n, id)
		return
	}
	if !hasBlob {
		ss.Send(joinReply(id, seq, joinMalformedHello, nil))
		return
	}
	sh, err := rcrypto.NewSHServer(rcrypto.NetworkIdentity(name), n.Salt, n.Verifier, nil)
	if err != nil {
		s.log.Printf("%s: network %q verifier: %v", ss, name, err)
		ss.Send(joinError(id, errGeneric))
		return
	}
	f10, err := sh.Hello(blob)
	if err != nil {
		ss.Send(joinReply(id, seq, joinMalformedHello, nil))
		return
	}
	now := time.Now()
	for k, j := range ss.joins {
		if now.After(j.expires) {
			delete(ss.joins, k)
		}
	}
	ss.joins[seq] = &joinAuth{sh: sh, nid: n.ID, id: id, step: 3, expires: now.Add(joinPendingExpiry)}
	ss.Send(joinReply(id, seq, 0, f10))
}

func (s *Server) joinContinue(ss *Session, seq uint32, blob []byte) {
	j := ss.joins[seq]
	if j == nil || time.Now().After(j.expires) {
		delete(ss.joins, seq)
		s.debugf("%s: join continuation for unknown sequence %d", ss, seq)
		return
	}
	fail := func(why error) {
		delete(ss.joins, seq)
		s.log.Printf("%s: private join failed: %v", ss, why)
		ss.Send(joinReply(j.id, seq, 0, wire.SHFailure()))
	}
	switch j.step {
	case 3:
		f12, err := j.sh.Public(blob)
		if err != nil {
			fail(err)
			return
		}
		j.step = 5
		ss.Send(joinReply(j.id, seq, 0, f12))
	case 5:
		f14, _, err := j.sh.Proof(blob)
		if err != nil {
			fail(err)
			return
		}
		delete(ss.joins, seq)
		ss.Send(joinReply(j.id, seq, 0, f14))
		s.mu.Lock()
		defer s.mu.Unlock()
		n, err := s.cfg.Store.NetworkByID(j.nid)
		if err != nil {
			ss.Send(joinError(j.id, errNotFound))
			return
		}
		s.completeJoinLocked(ss, n, j.id)
	}
}

// completeJoinLocked adds the membership and sends op 37, op 42 and member upserts.
func (s *Server) completeJoinLocked(ss *Session, n *store.Network, id uint64) {
	role, exists, err := s.cfg.Store.Role(n.ID, ss.rid)
	if err != nil {
		ss.Send(joinError(id, errGeneric))
		return
	}
	if !exists {
		count, err := s.cfg.Store.CountMembers(n.ID)
		if err != nil {
			ss.Send(joinError(id, errGeneric))
			return
		}
		if n.Capacity > 0 && count >= n.Capacity {
			ss.Send(joinError(id, errFull))
			return
		}
		role = store.RoleMember
		switch {
		case !n.Public && count == 0:
			role = store.RoleAdmin // an ownerless private network adopts its first member
		case !n.Public && n.RequireApproval:
			role = store.RolePending
		}
		if err := s.cfg.Store.AddMember(n.ID, ss.rid, role); err != nil {
			ss.Send(joinError(id, errGeneric))
			return
		}
	}
	members, err := s.cfg.Store.Members(n.ID)
	if err != nil {
		ss.Send(joinError(id, errGeneric))
		return
	}
	snapshot := wire.Cat(networkEntry(n, role), s.rosterLocked(members, ss))
	ss.Send(wire.ServerMsg(37, wire.TLV(0x131a, wire.Cat(
		wire.U32(managementActionTag, 2), wire.U64(tagRequestID, id), wire.TLV(0x1316, snapshot)))))
	if role != store.RolePending {
		ss.Send(approvalMsg(n.ID, members))
	}
	if !exists {
		s.notifyMembersLocked(n.ID, map[uint64]bool{ss.rid: true}, func(r *Session) []byte {
			return wire.TLV(0x131e, wire.Cat(wire.TLV(tagNetworkID, n.ID), wire.U32(tagRole, role), s.peerFields(ss.user, r)))
		})
	}
	s.log.Printf("rid=%d joined %q (public=%v role=%d new=%v)", ss.rid, n.Name, n.Public, role, !exists)
}

// approvalMsg is JoinApproved (op 42): the approved network's members and roles.
func approvalMsg(nid []byte, members []store.Member) []byte {
	var entries []byte
	for _, m := range members {
		if m.Role == store.RolePending {
			continue
		}
		entries = append(entries, wire.TLV(0x131e, wire.Cat(
			wire.TLV(tagNetworkID, nid), wire.U64(tagRID, m.RID), wire.U32(tagRole, m.Role)))...)
	}
	return wire.ServerMsg(42, wire.TLV(0x131f, entries))
}

// manage handles op 52: create (1), leave (3), kick (4), delete (7), grant (8), revoke (9).
func (s *Server) manage(ss *Session, recs wire.Records) {
	body, err := recs.Field(0x1319)
	if err != nil {
		return
	}
	f, err := wire.Parse(body)
	if err != nil {
		return
	}
	action, err1 := f.U32(managementActionTag)
	id, err2 := f.U64(tagRequestID)
	ctx, err3 := f.U32(contextTag)
	if err1 != nil || err2 != nil || err3 != nil {
		s.debugf("%s: malformed management request", ss)
		return
	}
	respond := func(extra ...[]byte) {
		head := [][]byte{wire.U32(managementActionTag, action), wire.U64(tagRequestID, id), wire.U32(contextTag, ctx)}
		ss.Send(wire.ServerMsg(37, wire.TLV(0x131a, wire.Cat(append(head, extra...)...))))
	}
	fail := func(code uint32, extra ...[]byte) {
		s.log.Printf("%s: network action %d refused with error %d", ss, action, code)
		respond(append(extra, wire.U32(errorTag, code))...)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	if action == 1 {
		s.createLocked(ss, f, respond, fail)
		return
	}
	nid, err := f.Field(tagNetworkID)
	if err != nil || len(nid) != 16 {
		fail(errInvalid)
		return
	}
	idField := wire.TLV(tagNetworkID, nid)
	n, err := s.cfg.Store.NetworkByID(nid)
	if err != nil {
		fail(errNotFound, idField)
		return
	}
	role, member, err := s.cfg.Store.Role(nid, ss.rid)
	if err != nil {
		fail(errGeneric, idField)
		return
	}
	if !member {
		fail(errNotMember, idField)
		return
	}
	members, err := s.cfg.Store.Members(nid)
	if err != nil {
		fail(errGeneric, idField)
		return
	}
	admins := 0
	for _, m := range members {
		if m.Role == store.RoleAdmin {
			admins++
		}
	}

	switch action {
	case 3: // leave
		if !n.Public && role == store.RoleAdmin && admins == 1 && len(members) > 1 {
			fail(errLastAdmin, idField)
			return
		}
		if err := s.cfg.Store.RemoveMember(nid, ss.rid); err != nil {
			fail(errGeneric, idField)
			return
		}
		if !n.Public && len(members) == 1 {
			s.cfg.Store.DeleteNetwork(nid)
			s.log.Printf("private network %q removed with its last member", n.Name)
		} else {
			s.notifyMembersLocked(nid, map[uint64]bool{ss.rid: true}, func(*Session) []byte {
				return wire.TLV(0x131d, wire.Cat(wire.U64(tagRID, ss.rid), idField))
			})
		}
		respond(idField)
		s.log.Printf("rid=%d left %q", ss.rid, n.Name)

	case 7: // delete
		if n.Public || role != store.RoleAdmin {
			fail(errPermission, idField)
			return
		}
		if err := s.cfg.Store.DeleteNetwork(nid); err != nil {
			fail(errGeneric, idField)
			return
		}
		msg := wire.ServerMsg(41, wire.TLV(0x131f, wire.TLV(0x132e, idField)))
		for _, m := range members {
			if m.RID != ss.rid && s.isOnlineLocked(m.RID) {
				s.online[m.RID].Send(msg)
			}
		}
		respond(idField)
		s.log.Printf("rid=%d deleted %q", ss.rid, n.Name)

	case 4, 8, 9: // kick, grant admin, revoke admin
		target, err := f.U64(tagRID)
		if err != nil {
			fail(errInvalid, idField)
			return
		}
		extra := wire.Cat(idField, wire.U64(tagRID, target))
		if n.Public || role != store.RoleAdmin {
			fail(errPermission, extra)
			return
		}
		trole, ok, err := s.cfg.Store.Role(nid, target)
		if err != nil || !ok {
			fail(errNotMember, extra)
			return
		}
		event := func(tag uint32, fields ...[]byte) func(*Session) []byte {
			body := wire.Cat(append([][]byte{wire.U64(tagRID, target), wire.U64(tagRID, ss.rid), idField}, fields...)...)
			return func(*Session) []byte { return wire.TLV(tag, body) }
		}
		skip := map[uint64]bool{ss.rid: true}
		switch action {
		case 4:
			if target == ss.rid {
				fail(errInvalid, extra)
				return
			}
			// Notify before removal so the removed member hears it too.
			s.notifyMembersLocked(nid, skip, event(0x131d))
			if err := s.cfg.Store.RemoveMember(nid, target); err != nil {
				fail(errGeneric, extra)
				return
			}
			s.log.Printf("rid=%d kicked %d from %q", ss.rid, target, n.Name)
		case 8, 9:
			newRole := uint32(store.RoleAdmin)
			if action == 9 {
				newRole = store.RoleMember
				if trole == store.RoleAdmin && admins == 1 {
					fail(errLastAdmin, extra)
					return
				}
			}
			if err := s.cfg.Store.SetRole(nid, target, newRole); err != nil {
				fail(errGeneric, extra)
				return
			}
			s.notifyMembersLocked(nid, skip, event(0x1318, wire.U32(tagRole, newRole)))
			s.log.Printf("rid=%d set role %d for %d in %q (was %d)", ss.rid, newRole, target, n.Name, trole)
		}
		respond(extra)

	default:
		fail(errInvalid, idField)
	}
}

func (s *Server) createLocked(ss *Session, f wire.Records, respond func(...[]byte), fail func(uint32, ...[]byte)) {
	dv, err := f.Field(0x1314)
	if err != nil {
		fail(errInvalid)
		return
	}
	d, err := wire.Parse(dv)
	if err != nil {
		fail(errInvalid)
		return
	}
	name, err := d.Text(tagNetName)
	if err != nil || validateText(name, 255) != nil {
		fail(errInvalid)
		return
	}
	if _, err := s.cfg.Store.NetworkByName(name); err == nil {
		fail(errExists)
		return
	} else if !errors.Is(err, store.ErrNotFound) {
		fail(errGeneric)
		return
	}
	kind, _, _ := d.OptU32(0x0b000308)
	var n *store.Network
	role := uint32(store.RoleAdmin)
	if kind == 0 {
		blob, err := d.Field(0x0a000307)
		if err != nil {
			fail(errInvalid)
			return
		}
		salt, verifier, err := rcrypto.ParseNetworkVerifier(blob)
		if err != nil {
			fail(errInvalid)
			return
		}
		n, err = s.cfg.Store.CreateNetwork(name, false, salt, verifier, false, 0)
		if err != nil {
			fail(errGeneric)
			return
		}
	} else {
		if !s.cfg.AllowPublicCreate {
			fail(errPermission)
			return
		}
		role = store.RoleMember
		if n, err = s.cfg.Store.CreateNetwork(name, true, nil, nil, false, 0); err != nil {
			fail(errGeneric)
			return
		}
	}
	if err := s.cfg.Store.AddMember(n.ID, ss.rid, role); err != nil {
		fail(errGeneric)
		return
	}
	respond(networkEntry(n, role))
	s.log.Printf("rid=%d created %q (public=%v)", ss.rid, name, n.Public)
}
