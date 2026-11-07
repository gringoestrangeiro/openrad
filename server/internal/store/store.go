// Package store persists identities, networks and memberships in SQLite.
package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/binary"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	_ "modernc.org/sqlite"
)

// Roles as carried in tag 0x0100030a.
const (
	RolePending = 0
	RoleMember  = 1
	RoleAdmin   = 2
)

var ErrNotFound = errors.New("not found")

type Store struct{ db *sql.DB }

type User struct {
	RID      uint64
	Name     string
	VIP      uint32
	Address  []byte // 16-byte companion (IPv6) address
	Salt     []byte
	Verifier []byte
	Created  time.Time
	LastSeen time.Time
}

type Network struct {
	ID              []byte // 16 bytes
	Name            string
	Public          bool
	Salt            []byte // private networks: 32-byte salt from the creator's verifier
	Verifier        []byte
	RequireApproval bool
	Capacity        int // 0 = unlimited
	Created         time.Time
}

type Member struct {
	NetworkID []byte
	RID       uint64
	Role      uint32
}

const schema = `
CREATE TABLE IF NOT EXISTS settings (
	key   TEXT PRIMARY KEY,
	value BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS users (
	rid       INTEGER PRIMARY KEY,
	name      TEXT    NOT NULL,
	vip       INTEGER NOT NULL UNIQUE,
	address   BLOB    NOT NULL,
	salt      BLOB    NOT NULL,
	verifier  BLOB    NOT NULL,
	created   INTEGER NOT NULL,
	last_seen INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS networks (
	nid              BLOB    PRIMARY KEY,
	name             TEXT    NOT NULL UNIQUE,
	public           INTEGER NOT NULL,
	salt             BLOB,
	verifier         BLOB,
	require_approval INTEGER NOT NULL DEFAULT 0,
	capacity         INTEGER NOT NULL DEFAULT 0,
	created          INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS members (
	nid    BLOB    NOT NULL REFERENCES networks(nid) ON DELETE CASCADE,
	rid    INTEGER NOT NULL REFERENCES users(rid) ON DELETE CASCADE,
	role   INTEGER NOT NULL,
	joined INTEGER NOT NULL,
	PRIMARY KEY (nid, rid)
);
CREATE INDEX IF NOT EXISTS members_rid ON members(rid);
`

// Open opens (creating if needed) the database at path. The database holds the
// server's RSA private key and credential verifiers, so a new file and its
// directory are created owner-only, and on Unix an existing file readable by
// others is tightened to 0600.
func Open(path string) (*Store, error) {
	if path == "" {
		return nil, errors.New("database path is empty")
	}
	// The driver treats everything after the first '?' as connection options.
	if strings.ContainsRune(path, '?') {
		return nil, fmt.Errorf("database path %q must not contain '?'", path)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("creating database directory: %w", err)
	}
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, fmt.Errorf("opening database: %w", err)
	}
	info, err := f.Stat()
	f.Close()
	if err != nil {
		return nil, err
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		if err := os.Chmod(path, 0o600); err != nil {
			return nil, fmt.Errorf("restricting database permissions: %w", err)
		}
	}
	dsn := path + "?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("initializing schema: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error { return s.db.Close() }

// ---- settings ----

func (s *Store) Setting(key string) ([]byte, error) {
	var v []byte
	err := s.db.QueryRow(`SELECT value FROM settings WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return v, err
}

func (s *Store) SetSetting(key string, value []byte) error {
	_, err := s.db.Exec(`INSERT INTO settings(key, value) VALUES(?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}

// ---- users ----

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}

// CompanionAddress derives the fdfd::/16 IPv6 companion address of a VIP.
func CompanionAddress(vip uint32) []byte {
	a := make([]byte, 16)
	a[0], a[1] = 0xfd, 0xfd
	binary.BigEndian.PutUint32(a[12:], vip)
	return a
}

// CreateUser allocates a RID (> 1000, below 2^53) and a unique 26.0.0.0/8 VIP.
// verifierFor derives the stored verifier from the RID.
func (s *Store) CreateUser(name string, salt []byte, verifierFor func(rid uint64) []byte) (*User, error) {
	now := time.Now()
	for attempt := 0; attempt < 64; attempt++ {
		r := randomBytes(12)
		rid := binary.BigEndian.Uint64(r)&((1<<53)-1) | 1<<40
		host := binary.BigEndian.Uint32(r[8:]) & 0x00ffffff
		if host&0xff == 0 || host&0xff == 0xff {
			continue
		}
		vip := 26<<24 | host
		u := &User{RID: rid, Name: name, VIP: vip, Address: CompanionAddress(vip),
			Salt: salt, Verifier: verifierFor(rid), Created: now}
		_, err := s.db.Exec(`INSERT INTO users(rid, name, vip, address, salt, verifier, created)
			VALUES(?, ?, ?, ?, ?, ?, ?)`, int64(u.RID), u.Name, int64(u.VIP), u.Address, u.Salt, u.Verifier, now.Unix())
		if err == nil {
			return u, nil
		}
		if !strings.Contains(err.Error(), "UNIQUE") {
			return nil, err
		}
	}
	return nil, errors.New("could not allocate a unique RID/VIP")
}

func scanUser(row interface{ Scan(...any) error }) (*User, error) {
	var u User
	var rid, vip, created, seen int64
	err := row.Scan(&rid, &u.Name, &vip, &u.Address, &u.Salt, &u.Verifier, &created, &seen)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	u.RID, u.VIP = uint64(rid), uint32(vip)
	u.Created, u.LastSeen = time.Unix(created, 0), time.Unix(seen, 0)
	return &u, nil
}

const userCols = `rid, name, vip, address, salt, verifier, created, last_seen`

func (s *Store) User(rid uint64) (*User, error) {
	return scanUser(s.db.QueryRow(`SELECT `+userCols+` FROM users WHERE rid = ?`, int64(rid)))
}

func (s *Store) Users() ([]*User, error) {
	rows, err := s.db.Query(`SELECT ` + userCols + ` FROM users ORDER BY created`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*User
	for rows.Next() {
		u, err := scanUser(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (s *Store) RenameUser(rid uint64, name string) error {
	_, err := s.db.Exec(`UPDATE users SET name = ? WHERE rid = ?`, name, int64(rid))
	return err
}

func (s *Store) TouchUser(rid uint64) error {
	_, err := s.db.Exec(`UPDATE users SET last_seen = ? WHERE rid = ?`, time.Now().Unix(), int64(rid))
	return err
}

func (s *Store) DeleteUser(rid uint64) error {
	_, err := s.db.Exec(`DELETE FROM users WHERE rid = ?`, int64(rid))
	return err
}

// ---- networks ----

const netCols = `nid, name, public, salt, verifier, require_approval, capacity, created`

func scanNetwork(row interface{ Scan(...any) error }) (*Network, error) {
	var n Network
	var pub, appr, capacity, created int64
	err := row.Scan(&n.ID, &n.Name, &pub, &n.Salt, &n.Verifier, &appr, &capacity, &created)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	n.Public, n.RequireApproval, n.Capacity = pub != 0, appr != 0, int(capacity)
	n.Created = time.Unix(created, 0)
	return &n, nil
}

func (s *Store) NetworkByName(name string) (*Network, error) {
	return scanNetwork(s.db.QueryRow(`SELECT `+netCols+` FROM networks WHERE name = ?`, name))
}

func (s *Store) NetworkByID(id []byte) (*Network, error) {
	return scanNetwork(s.db.QueryRow(`SELECT `+netCols+` FROM networks WHERE nid = ?`, id))
}

func b2i(b bool) int {
	if b {
		return 1
	}
	return 0
}

// CreateNetwork inserts a network with a fresh 16-byte ID.
func (s *Store) CreateNetwork(name string, public bool, salt, verifier []byte, requireApproval bool, capacity int) (*Network, error) {
	n := &Network{ID: randomBytes(16), Name: name, Public: public, Salt: salt, Verifier: verifier,
		RequireApproval: requireApproval, Capacity: capacity, Created: time.Now()}
	_, err := s.db.Exec(`INSERT INTO networks(`+netCols+`) VALUES(?, ?, ?, ?, ?, ?, ?, ?)`,
		n.ID, n.Name, b2i(public), salt, verifier, b2i(requireApproval), capacity, n.Created.Unix())
	if err != nil {
		return nil, err
	}
	return n, nil
}

func (s *Store) DeleteNetwork(id []byte) error {
	_, err := s.db.Exec(`DELETE FROM networks WHERE nid = ?`, id)
	return err
}

func (s *Store) UpdateNetworkSettings(id []byte, requireApproval bool, capacity int) error {
	_, err := s.db.Exec(`UPDATE networks SET require_approval = ?, capacity = ? WHERE nid = ?`,
		b2i(requireApproval), capacity, id)
	return err
}

type PublicListing struct {
	Name    string
	Members int
}

// SearchPublic lists public networks whose name contains query (case-insensitive).
func (s *Store) SearchPublic(query string, offset, limit int) ([]PublicListing, bool, error) {
	rows, err := s.db.Query(`SELECT n.name, (SELECT COUNT(*) FROM members m WHERE m.nid = n.nid)
		FROM networks n WHERE n.public = 1 AND instr(lower(n.name), lower(?)) > 0
		ORDER BY n.name LIMIT ? OFFSET ?`, query, limit+1, offset)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	var out []PublicListing
	for rows.Next() {
		var p PublicListing
		if err := rows.Scan(&p.Name, &p.Members); err != nil {
			return nil, false, err
		}
		out = append(out, p)
	}
	more := len(out) > limit
	if more {
		out = out[:limit]
	}
	return out, more, rows.Err()
}

func (s *Store) Networks() ([]*Network, error) {
	rows, err := s.db.Query(`SELECT ` + netCols + ` FROM networks ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Network
	for rows.Next() {
		n, err := scanNetwork(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// ---- members ----

func (s *Store) Role(nid []byte, rid uint64) (uint32, bool, error) {
	var role int64
	err := s.db.QueryRow(`SELECT role FROM members WHERE nid = ? AND rid = ?`, nid, int64(rid)).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, false, nil
	}
	return uint32(role), err == nil, err
}

func (s *Store) AddMember(nid []byte, rid uint64, role uint32) error {
	_, err := s.db.Exec(`INSERT INTO members(nid, rid, role, joined) VALUES(?, ?, ?, ?)
		ON CONFLICT(nid, rid) DO UPDATE SET role = excluded.role`, nid, int64(rid), role, time.Now().Unix())
	return err
}

func (s *Store) SetRole(nid []byte, rid uint64, role uint32) error {
	_, err := s.db.Exec(`UPDATE members SET role = ? WHERE nid = ? AND rid = ?`, role, nid, int64(rid))
	return err
}

func (s *Store) RemoveMember(nid []byte, rid uint64) error {
	_, err := s.db.Exec(`DELETE FROM members WHERE nid = ? AND rid = ?`, nid, int64(rid))
	return err
}

func (s *Store) Members(nid []byte) ([]Member, error) {
	rows, err := s.db.Query(`SELECT rid, role FROM members WHERE nid = ? ORDER BY joined, rid`, nid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Member
	for rows.Next() {
		var rid, role int64
		if err := rows.Scan(&rid, &role); err != nil {
			return nil, err
		}
		out = append(out, Member{NetworkID: nid, RID: uint64(rid), Role: uint32(role)})
	}
	return out, rows.Err()
}

func (s *Store) CountMembers(nid []byte) (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM members WHERE nid = ?`, nid).Scan(&n)
	return n, err
}

// Memberships lists the networks of rid with its role in each.
func (s *Store) Memberships(rid uint64) ([]*Network, []uint32, error) {
	rows, err := s.db.Query(`SELECT n.nid, n.name, n.public, n.salt, n.verifier, n.require_approval,
		n.capacity, n.created, m.role FROM members m JOIN networks n ON n.nid = m.nid
		WHERE m.rid = ? ORDER BY m.joined`, int64(rid))
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	var nets []*Network
	var roles []uint32
	for rows.Next() {
		var n Network
		var pub, appr, capacity, created, role int64
		if err := rows.Scan(&n.ID, &n.Name, &pub, &n.Salt, &n.Verifier, &appr, &capacity, &created, &role); err != nil {
			return nil, nil, err
		}
		n.Public, n.RequireApproval, n.Capacity = pub != 0, appr != 0, int(capacity)
		n.Created = time.Unix(created, 0)
		nets = append(nets, &n)
		roles = append(roles, uint32(role))
	}
	return nets, roles, rows.Err()
}

// SharedMemberships returns every (network, member) row of networks that rid belongs to.
func (s *Store) SharedMemberships(rid uint64) ([]Member, error) {
	rows, err := s.db.Query(`SELECT m.nid, m.rid, m.role FROM members m
		WHERE m.nid IN (SELECT nid FROM members WHERE rid = ?) ORDER BY m.joined, m.rid`, int64(rid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Member
	for rows.Next() {
		var m Member
		var r, role int64
		if err := rows.Scan(&m.NetworkID, &r, &role); err != nil {
			return nil, err
		}
		m.RID, m.Role = uint64(r), uint32(role)
		out = append(out, m)
	}
	return out, rows.Err()
}
