// Command openrad-server is a self-hosted coordination server compatible with
// the OpenRad clients (and their Radmin VPN wire protocol).
package main

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"text/tabwriter"
	"time"

	"github.com/gringoestrangeiro/openrad/server/internal/rcrypto"
	"github.com/gringoestrangeiro/openrad/server/internal/server"
	"github.com/gringoestrangeiro/openrad/server/internal/store"
)

// version is set at release build time with -ldflags "-X main.version=X.Y.Z".
var version = "dev"

// defaultRSABits is the size of a newly generated server key.
const defaultRSABits = 2048

const usage = `openrad-server: OpenRad-compatible coordination server

Usage:
  openrad-server [--db FILE] serve [flags]       run the server (default command)
  openrad-server [--db FILE] modulus [-o FILE]   write the public RSA modulus clients need
  openrad-server --version
  openrad-server [--db FILE] network list
  openrad-server [--db FILE] network add-public NAME [--capacity N]
  openrad-server [--db FILE] network delete NAME
  openrad-server [--db FILE] network set NAME [--approval on|off] [--capacity N]
  openrad-server [--db FILE] network members NAME
  openrad-server [--db FILE] network set-role NAME RID pending|member|admin
  openrad-server [--db FILE] network remove-member NAME RID
  openrad-server [--db FILE] user list
  openrad-server [--db FILE] user delete RID

The default database is openrad-server/openrad-server.db under the user
state directory ($XDG_STATE_HOME or ~/.local/state on Linux, %LOCALAPPDATA%
on Windows); OPENRAD_SERVER_DB overrides it and --db overrides both.
Administrative commands edit the database directly; online clients see
membership changes made this way after they reconnect.

Clients must be pointed at this server's IPv4 address and modulus file:
  openrad server set --host SERVER_IP [--port PORT] --modulus modulus.bin
  openrad.py run --host SERVER_IP --modulus modulus.bin ...
`

// defaultDBPath picks the database location when --db is not given.
func defaultDBPath() string {
	if p := os.Getenv("OPENRAD_SERVER_DB"); p != "" {
		return p
	}
	var base string
	if runtime.GOOS == "windows" {
		base = os.Getenv("LOCALAPPDATA")
	} else if base = os.Getenv("XDG_STATE_HOME"); base == "" {
		if home, err := os.UserHomeDir(); err == nil {
			base = filepath.Join(home, ".local", "state")
		}
	}
	if base == "" {
		return "openrad-server.db"
	}
	return filepath.Join(base, "openrad-server", "openrad-server.db")
}

func main() {
	log.SetFlags(log.LstdFlags | log.Lmicroseconds)
	global := flag.NewFlagSet("openrad-server", flag.ExitOnError)
	global.Usage = func() { fmt.Fprint(os.Stderr, usage) }
	dbPath := global.String("db", defaultDBPath(), "SQLite database path")
	showVersion := global.Bool("version", false, "print the version and exit")
	global.Parse(os.Args[1:])
	if *showVersion {
		fmt.Println("openrad-server " + version)
		return
	}
	args := global.Args()
	cmd := "serve"
	if len(args) > 0 {
		cmd, args = args[0], args[1:]
	}
	var err error
	switch cmd {
	case "serve":
		err = serve(*dbPath, args)
	case "modulus":
		err = modulus(*dbPath, args)
	case "network":
		err = networkCmd(*dbPath, args)
	case "user":
		err = userCmd(*dbPath, args)
	case "version":
		fmt.Println("openrad-server " + version)
	case "help", "-h", "--help":
		fmt.Print(usage)
	default:
		err = fmt.Errorf("unknown command %q\n\n%s", cmd, usage)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

type listFlag []string

func (l *listFlag) String() string { return strings.Join(*l, ",") }
func (l *listFlag) Set(v string) error {
	for _, p := range strings.Split(v, ",") {
		if p = strings.TrimSpace(p); p != "" {
			*l = append(*l, p)
		}
	}
	return nil
}

// loadIdentity returns the persisted RSA key and server ID, creating them once.
func loadIdentity(st *store.Store, bits int) (*rsa.PrivateKey, uint32, error) {
	var key *rsa.PrivateKey
	der, err := st.Setting("rsa_key")
	switch {
	case err == nil:
		if key, err = rcrypto.ParseKey(der); err != nil {
			return nil, 0, fmt.Errorf("stored RSA key: %w", err)
		}
	case errors.Is(err, store.ErrNotFound):
		log.Printf("generating %d-bit RSA key", bits)
		if key, err = rcrypto.GenerateKey(bits); err != nil {
			return nil, 0, err
		}
		if err := st.SetSetting("rsa_key", rcrypto.MarshalKey(key)); err != nil {
			return nil, 0, err
		}
	default:
		return nil, 0, err
	}
	var id uint32
	raw, err := st.Setting("server_id")
	switch {
	case err == nil && len(raw) == 4:
		id = binary.BigEndian.Uint32(raw)
	case err == nil || errors.Is(err, store.ErrNotFound):
		for id == 0 {
			id = binary.BigEndian.Uint32(randomBytes(4))
		}
		var b [4]byte
		binary.BigEndian.PutUint32(b[:], id)
		if err := st.SetSetting("server_id", b[:]); err != nil {
			return nil, 0, err
		}
	default:
		return nil, 0, err
	}
	return key, id, nil
}

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}

// writeModulus writes the public modulus; it is not secret, so clients and
// other users may read it.
func writeModulus(key *rsa.PrivateKey, path string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, rcrypto.Modulus(key), 0o644)
}

func serve(dbPath string, args []string) error {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	listen := fs.String("listen", "0.0.0.0:17301", "control TCP address (Python and Android use port 17301)")
	relayListen := fs.String("relay-listen", "0.0.0.0:17302", "relay TCP address")
	relayPort := fs.Int("relay-port", 0, "relay port advertised to clients (default: the relay listener's port)")
	publicIP := fs.String("public-ip", "", "IPv4 address advertised to clients (default: the address each client connected to)")
	var ues listFlag
	fs.Var(&ues, "ues-host", "routable IPv4 address for UDP endpoint discovery (repeatable; two distinct hosts are needed)")
	autoPublic := fs.Bool("auto-public", false, "create a public network when a client joins an unknown public name")
	allowPublicCreate := fs.Bool("allow-public-create", false, "let clients create public networks")
	regLimit := fs.Int("registration-limit", 30, "new identities per source IP per hour (0 = unlimited)")
	modulusOut := fs.String("modulus-out", "", "write the RSA modulus here on startup (default: modulus.bin next to the database)")
	rsaBits := fs.Int("rsa-bits", defaultRSABits, "RSA key size, used only when the key is first generated")
	var publics listFlag
	fs.Var(&publics, "public", "ensure a public network with this name exists (repeatable)")
	verbose := fs.Bool("v", false, "verbose protocol logging")
	fs.Parse(args)

	log.Printf("openrad-server %s, database %s", version, dbPath)
	st, err := store.Open(dbPath)
	if err != nil {
		return err
	}
	defer st.Close()
	key, serverID, err := loadIdentity(st, *rsaBits)
	if err != nil {
		return err
	}
	out := *modulusOut
	if out == "" {
		out = filepath.Join(filepath.Dir(dbPath), "modulus.bin")
	}
	if err := writeModulus(key, out); err != nil {
		return fmt.Errorf("writing modulus: %w", err)
	}
	log.Printf("RSA modulus (%d bits) written to %s", key.N.BitLen(), out)
	for _, name := range publics {
		if _, err := st.NetworkByName(name); errors.Is(err, store.ErrNotFound) {
			if _, err := st.CreateNetwork(name, true, nil, nil, false, 0); err != nil {
				return fmt.Errorf("creating public network %q: %w", name, err)
			}
			log.Printf("created public network %q", name)
		}
	}
	cfg := server.Config{
		Listen: *listen, RelayListen: *relayListen, RelayPort: *relayPort,
		Store: st, Key: key, ServerID: serverID,
		AutoPublic: *autoPublic, AllowPublicCreate: *allowPublicCreate,
		RegistrationLimit: *regLimit, Verbose: *verbose,
	}
	if *publicIP != "" {
		if cfg.PublicIP = net.ParseIP(*publicIP).To4(); cfg.PublicIP == nil {
			return fmt.Errorf("invalid --public-ip %q", *publicIP)
		}
	}
	for _, h := range ues {
		ip := net.ParseIP(h).To4()
		if ip == nil {
			return fmt.Errorf("invalid --ues-host %q", h)
		}
		cfg.UESHosts = append(cfg.UESHosts, ip)
	}
	srv, err := server.New(cfg)
	if err != nil {
		return err
	}
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		log.Printf("shutting down")
		srv.Close()
	}()
	return srv.Serve()
}

func modulus(dbPath string, args []string) error {
	fs := flag.NewFlagSet("modulus", flag.ExitOnError)
	out := fs.String("o", "modulus.bin", "output file")
	rsaBits := fs.Int("rsa-bits", defaultRSABits, "RSA key size, used only when the key is first generated")
	fs.Parse(args)
	st, err := store.Open(dbPath)
	if err != nil {
		return err
	}
	defer st.Close()
	key, _, err := loadIdentity(st, *rsaBits)
	if err != nil {
		return err
	}
	if err := writeModulus(key, *out); err != nil {
		return err
	}
	fmt.Printf("wrote %d-byte modulus to %s\n", len(rcrypto.Modulus(key)), *out)
	return nil
}

func roleName(r uint32) string {
	switch r {
	case store.RolePending:
		return "pending"
	case store.RoleMember:
		return "member"
	case store.RoleAdmin:
		return "admin"
	}
	return strconv.Itoa(int(r))
}

func parseRole(s string) (uint32, error) {
	switch s {
	case "pending":
		return store.RolePending, nil
	case "member":
		return store.RoleMember, nil
	case "admin":
		return store.RoleAdmin, nil
	}
	return 0, fmt.Errorf("role must be pending, member or admin")
}

func networkCmd(dbPath string, args []string) error {
	if len(args) == 0 {
		return errors.New("network: missing subcommand\n\n" + usage)
	}
	st, err := store.Open(dbPath)
	if err != nil {
		return err
	}
	defer st.Close()
	sub, args := args[0], args[1:]
	byName := func(name string) (*store.Network, error) {
		n, err := st.NetworkByName(name)
		if errors.Is(err, store.ErrNotFound) {
			return nil, fmt.Errorf("no network named %q", name)
		}
		return n, err
	}
	switch sub {
	case "list":
		nets, err := st.Networks()
		if err != nil {
			return err
		}
		w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
		fmt.Fprintln(w, "NAME\tID\tTYPE\tMEMBERS\tAPPROVAL\tCAPACITY")
		for _, n := range nets {
			count, _ := st.CountMembers(n.ID)
			kind := "private"
			if n.Public {
				kind = "public"
			}
			fmt.Fprintf(w, "%s\t%s\t%s\t%d\t%v\t%d\n", n.Name, hex.EncodeToString(n.ID), kind, count, n.RequireApproval, n.Capacity)
		}
		return w.Flush()
	case "add-public":
		fs := flag.NewFlagSet("add-public", flag.ExitOnError)
		capacity := fs.Int("capacity", 0, "maximum members (0 = unlimited)")
		name, rest := firstArg(args)
		fs.Parse(rest)
		if name == "" {
			return errors.New("add-public: NAME required")
		}
		n, err := st.CreateNetwork(name, true, nil, nil, false, *capacity)
		if err != nil {
			return err
		}
		fmt.Printf("created public network %q (%s)\n", n.Name, hex.EncodeToString(n.ID))
	case "delete":
		if len(args) != 1 {
			return errors.New("delete: NAME required")
		}
		n, err := byName(args[0])
		if err != nil {
			return err
		}
		return st.DeleteNetwork(n.ID)
	case "set":
		name, rest := firstArg(args)
		n, err := byName(name)
		if err != nil {
			return err
		}
		fs := flag.NewFlagSet("set", flag.ExitOnError)
		approval := fs.String("approval", "", "on|off: new private-network members wait for admin approval")
		capacity := fs.Int("capacity", n.Capacity, "maximum members (0 = unlimited)")
		fs.Parse(rest)
		req := n.RequireApproval
		switch *approval {
		case "":
		case "on":
			req = true
		case "off":
			req = false
		default:
			return errors.New("--approval must be on or off")
		}
		return st.UpdateNetworkSettings(n.ID, req, *capacity)
	case "members":
		if len(args) != 1 {
			return errors.New("members: NAME required")
		}
		n, err := byName(args[0])
		if err != nil {
			return err
		}
		ms, err := st.Members(n.ID)
		if err != nil {
			return err
		}
		w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
		fmt.Fprintln(w, "RID\tNAME\tVIP\tROLE")
		for _, m := range ms {
			u, err := st.User(m.RID)
			if err != nil {
				continue
			}
			fmt.Fprintf(w, "%d\t%s\t%s\t%s\n", m.RID, u.Name, vipString(u.VIP), roleName(m.Role))
		}
		return w.Flush()
	case "set-role":
		if len(args) != 3 {
			return errors.New("set-role: NAME RID ROLE required")
		}
		n, err := byName(args[0])
		if err != nil {
			return err
		}
		rid, err := strconv.ParseUint(args[1], 10, 64)
		if err != nil {
			return err
		}
		role, err := parseRole(args[2])
		if err != nil {
			return err
		}
		if _, err := st.User(rid); err != nil {
			return fmt.Errorf("user %d: %w", rid, err)
		}
		return st.AddMember(n.ID, rid, role)
	case "remove-member":
		if len(args) != 2 {
			return errors.New("remove-member: NAME RID required")
		}
		n, err := byName(args[0])
		if err != nil {
			return err
		}
		rid, err := strconv.ParseUint(args[1], 10, 64)
		if err != nil {
			return err
		}
		return st.RemoveMember(n.ID, rid)
	default:
		return fmt.Errorf("network: unknown subcommand %q", sub)
	}
	return nil
}

func firstArg(args []string) (string, []string) {
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		return "", args
	}
	return args[0], args[1:]
}

func vipString(v uint32) string {
	return net.IPv4(byte(v>>24), byte(v>>16), byte(v>>8), byte(v)).String()
}

func userCmd(dbPath string, args []string) error {
	if len(args) == 0 {
		return errors.New("user: missing subcommand")
	}
	st, err := store.Open(dbPath)
	if err != nil {
		return err
	}
	defer st.Close()
	switch args[0] {
	case "list":
		us, err := st.Users()
		if err != nil {
			return err
		}
		w := tabwriter.NewWriter(os.Stdout, 0, 2, 2, ' ', 0)
		fmt.Fprintln(w, "RID\tNAME\tVIP\tCREATED\tLAST SEEN")
		for _, u := range us {
			seen := "never"
			if u.LastSeen.Unix() > 0 {
				seen = u.LastSeen.Format(time.DateTime)
			}
			fmt.Fprintf(w, "%d\t%s\t%s\t%s\t%s\n", u.RID, u.Name, vipString(u.VIP), u.Created.Format(time.DateTime), seen)
		}
		return w.Flush()
	case "delete":
		if len(args) != 2 {
			return errors.New("user delete: RID required")
		}
		rid, err := strconv.ParseUint(args[1], 10, 64)
		if err != nil {
			return err
		}
		return st.DeleteUser(rid)
	}
	return fmt.Errorf("user: unknown subcommand %q", args[0])
}
