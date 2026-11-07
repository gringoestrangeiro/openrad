package rcrypto

import (
	"encoding/hex"
	"encoding/json"
	"math/big"
	"os"
	"testing"
)

// fixtures are the synthetic vectors owned by the Rust client (tests/fixtures
// at the repository root); the server reads them in place instead of keeping a
// mirror that could drift.
const fixtures = "../../../tests/fixtures/"

func load(t *testing.T, name string, v any) {
	t.Helper()
	b, err := os.ReadFile(fixtures + name)
	if err != nil {
		t.Fatalf("fixture %s unavailable: %v", name, err)
	}
	if err := json.Unmarshal(b, v); err != nil {
		t.Fatal(err)
	}
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestSHServerMatchesIncomingVector(t *testing.T) {
	var f struct {
		Private  int64    `json:"private"`
		Salt     string   `json:"salt"`
		Messages []string `json:"messages"`
		Key      string   `json:"key"`
	}
	load(t, "incoming-sh.json", &f)
	salt := unhex(t, f.Salt)
	v := Verifier(12345, []byte("controlled-test-password"), salt)
	s, err := NewSHServer(12345, salt, v, big.NewInt(f.Private))
	if err != nil {
		t.Fatal(err)
	}
	m := make([][]byte, len(f.Messages))
	for i, h := range f.Messages {
		m[i] = unhex(t, h)
	}
	got, err := s.Hello(m[0])
	if err != nil || hex.EncodeToString(got) != f.Messages[1] {
		t.Fatalf("hello mismatch: %v", err)
	}
	got, err = s.Public(m[2])
	if err != nil || hex.EncodeToString(got) != f.Messages[3] {
		t.Fatalf("public mismatch: %v", err)
	}
	proof, key, err := s.Proof(m[4])
	if err != nil || hex.EncodeToString(proof) != f.Messages[5] || hex.EncodeToString(key) != f.Key {
		t.Fatalf("proof mismatch: %v", err)
	}
	if _, _, err := s.Proof(m[4]); err == nil {
		t.Fatal("replayed proof accepted")
	}
}

func TestChannelVectors(t *testing.T) {
	var f struct {
		Channel []struct {
			PT string `json:"pt"`
			CT string `json:"ct"`
		} `json:"channel"`
		Rekey struct {
			PT string `json:"pt"`
			CT string `json:"ct"`
		} `json:"rekey"`
		SH struct {
			Key string `json:"key"`
		} `json:"sh"`
	}
	load(t, "reference.json", &f)
	key := make([]byte, 64)
	for i := range key {
		key[i] = byte(i)
	}
	enc, _ := NewCChannel(key[:32])
	dec, _ := NewCChannel(key[:32])
	check := func(name, ptHex, ctHex string) {
		ct, err := enc.Encrypt(unhex(t, ptHex))
		if err != nil || hex.EncodeToString(ct) != ctHex {
			t.Fatalf("%s encrypt mismatch", name)
		}
		pt, err := dec.Decrypt(ct)
		if err != nil || hex.EncodeToString(pt) != ptHex {
			t.Fatalf("%s decrypt mismatch: %v", name, err)
		}
	}
	for i, c := range f.Channel {
		check("channel vector "+string(rune('0'+i)), c.PT, c.CT)
	}
	// Rekeying keeps both CBC chains.
	enc.Rekey(key[32:])
	dec.Rekey(key[32:])
	check("rekey", f.Rekey.PT, f.Rekey.CT)
}

func TestNetworkIdentityAndVerifier(t *testing.T) {
	var f struct {
		Name     string `json:"name"`
		Password string `json:"password"`
		Identity uint64 `json:"identity"`
		Salt     string `json:"salt"`
		Verifier string `json:"verifier"`
	}
	load(t, "network-verifier.json", &f)
	if got := NetworkIdentity(f.Name); got != f.Identity {
		t.Fatalf("identity %d != %d", got, f.Identity)
	}
	salt, v, err := ParseNetworkVerifier(unhex(t, f.Verifier))
	if err != nil || hex.EncodeToString(salt) != f.Salt {
		t.Fatalf("verifier parse: %v", err)
	}
	want := Verifier(f.Identity, append([]byte(f.Password), 0), salt)
	if hex.EncodeToString(v) != hex.EncodeToString(want) {
		t.Fatal("verifier mismatch")
	}
}
