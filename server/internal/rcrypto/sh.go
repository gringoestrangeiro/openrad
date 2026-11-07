package rcrypto

import (
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"math/big"
	"unicode/utf16"

	"github.com/gringoestrangeiro/openrad/server/internal/wire"
)

// GroupPrimeHex is the compiled SH group; clients reject any other N/g.
const GroupPrimeHex = "e634471e75f2d76a0a3f5e252c5a6efb48edad645d31d9ecbc400416e14f5c6c" +
	"2ed378602d6eb8e9478b99e6474932393538b4be7177e0e05d3aaed17b5ae87a" +
	"a20f3dad37af019518f45b6dde76b2bfdfb140f379cc15383b304b7975b90796" +
	"7553cd6fdaf08d68832b603120430a501067d21beb2ce2275a20036a2dc80f1c" +
	"9d3ab9221afd5baceda5c2a31077c54fc6f274aaeba17ddd0b5c68f4f8e751a8" +
	"90a58648147a82e6f4f532ccdc939a12942799a713500e17dd4f6ff216cd54c7"

var (
	PrimeBytes, _ = hex.DecodeString(GroupPrimeHex)
	N             = new(big.Int).SetBytes(PrimeBytes)
	G             = big.NewInt(5)
	GBytes        = []byte{5}
	width         = len(PrimeBytes)
	multiplier    = new(big.Int).SetBytes(digest(PrimeBytes, pad(GBytes, width)))
	hashPrefix    = xor(digest(PrimeBytes), digest(GBytes))
)

func digest(parts ...[]byte) []byte {
	h := sha1.New()
	for _, p := range parts {
		h.Write(p)
	}
	return h.Sum(nil)
}

func pad(b []byte, n int) []byte {
	if len(b) >= n {
		return b
	}
	out := make([]byte, n)
	copy(out[n-len(b):], b)
	return out
}

func xor(a, b []byte) []byte {
	out := make([]byte, len(a))
	for i := range a {
		out[i] = a[i] ^ b[i]
	}
	return out
}

// IdentityBytes is the RID as LE u64 with each 16-bit word byte-swapped.
func IdentityBytes(rid uint64) []byte {
	var raw [8]byte
	binary.LittleEndian.PutUint64(raw[:], rid)
	for i := 0; i < 8; i += 2 {
		raw[i], raw[i+1] = raw[i+1], raw[i]
	}
	return raw[:]
}

// PasswordExponent is SHA1(salt || SHA1(identity || ':' || password)).
func PasswordExponent(salt, identity, password []byte) *big.Int {
	inner := digest(identity, []byte{':'}, password)
	return new(big.Int).SetBytes(digest(salt, inner))
}

// Verifier computes g^x mod N for an identity/password/salt.
func Verifier(rid uint64, password, salt []byte) []byte {
	x := PasswordExponent(salt, IdentityBytes(rid), password)
	return new(big.Int).Exp(G, x, N).Bytes()
}

// NetworkIdentity is CRC64-ECMA over the exact UTF-16LE name with complemented endpoints.
func NetworkIdentity(name string) uint64 {
	crc := ^uint64(0)
	for _, u := range utf16.Encode([]rune(name)) {
		for _, b := range []byte{byte(u), byte(u >> 8)} {
			crc ^= uint64(b) << 56
			for i := 0; i < 8; i++ {
				if crc&(1<<63) != 0 {
					crc = (crc << 1) ^ 0x42f0e1eba9ea3693
				} else {
					crc <<= 1
				}
			}
		}
	}
	return ^crc
}

// ParseNetworkVerifier splits the client's create blob: LE u32 32 || salt[32] || v.
func ParseNetworkVerifier(blob []byte) (salt, verifier []byte, err error) {
	if len(blob) < 4+32+1 || len(blob) > 4+32+width || binary.LittleEndian.Uint32(blob) != 32 {
		return nil, nil, errors.New("invalid network verifier")
	}
	v := new(big.Int).SetBytes(blob[36:])
	if v.Sign() <= 0 || v.Cmp(N) >= 0 {
		return nil, nil, errors.New("network verifier outside the group")
	}
	return append([]byte(nil), blob[4:36]...), v.Bytes(), nil
}

// SHServer is the verifier-based server side: states 1 -> 3 -> 5 -> 7.
type SHServer struct {
	rid      uint64
	identity []byte
	salt     []byte
	v        *big.Int
	b        *big.Int
	state    int
	key      []byte
	m1, m2   []byte
}

// NewSHServer prepares a handshake for rid using a stored salt/verifier.
// private may be nil for a random exponent.
func NewSHServer(rid uint64, salt, verifier []byte, private *big.Int) (*SHServer, error) {
	v := new(big.Int).SetBytes(verifier)
	if v.Sign() <= 0 || v.Cmp(N) >= 0 || len(salt) == 0 || len(salt) > 0x2800 {
		return nil, errors.New("invalid SH verifier or salt")
	}
	if private == nil {
		r := make([]byte, 32)
		if _, err := rand.Read(r); err != nil {
			return nil, err
		}
		private = new(big.Int).SetBytes(r)
		private.Add(private, big.NewInt(int64(N.BitLen())))
	}
	if private.Sign() <= 0 {
		return nil, errors.New("invalid private exponent")
	}
	return &SHServer{rid: rid, identity: IdentityBytes(rid), salt: salt, v: v, b: private, state: 1}, nil
}

// HelloRID extracts the RID from an SH state-1 message without consuming state.
func HelloRID(data []byte) (uint64, error) {
	vals, err := wire.ParseSH(data, 1, wire.SHIdentity)
	if err != nil {
		return 0, err
	}
	if len(vals[0]) != 8 {
		return 0, errors.New("SH identity requires eight bytes")
	}
	return binary.LittleEndian.Uint64(vals[0]), nil
}

// Hello answers state 1 with N, g and salt (f10).
func (s *SHServer) Hello(data []byte) ([]byte, error) {
	if s.state != 1 {
		return nil, errors.New("unexpected SH state")
	}
	s.state = 0
	rid, err := HelloRID(data)
	if err != nil {
		return nil, err
	}
	if rid != s.rid {
		return nil, errors.New("SH identity mismatch")
	}
	s.state = 3
	return wire.Cat(wire.SHMessage(2, wire.SHPrime, PrimeBytes),
		wire.SHRecord(wire.SHGenerator, GBytes), wire.SHRecord(wire.SHSalt, s.salt)), nil
}

// Public consumes the client's A and returns B (f12).
func (s *SHServer) Public(data []byte) ([]byte, error) {
	if s.state != 3 {
		return nil, errors.New("unexpected SH state")
	}
	s.state = 0
	vals, err := wire.ParseSH(data, 3, wire.SHPublic)
	if err != nil {
		return nil, err
	}
	a := vals[0]
	av := new(big.Int).SetBytes(a)
	if len(a) > width || av.Sign() <= 0 || av.Cmp(N) >= 0 {
		return nil, errors.New("invalid SH client public")
	}
	bv := new(big.Int).Mul(multiplier, s.v)
	bv.Add(bv, new(big.Int).Exp(G, s.b, N))
	bv.Mod(bv, N)
	b := bv.Bytes()
	u := new(big.Int).SetBytes(digest(pad(a, width), pad(b, width)))
	base := new(big.Int).Exp(s.v, u, N)
	base.Mul(base, av).Mod(base, N)
	shared := new(big.Int).Exp(base, s.b, N).Bytes()
	s.key = append(digest(shared, []byte{0, 0, 0, 0}), digest(shared, []byte{0, 0, 0, 1})...)
	s.m1 = digest(hashPrefix, digest(s.identity), s.salt, a, b, s.key)
	s.m2 = digest(a, s.m1, s.key)
	s.state = 5
	return wire.SHMessage(4, wire.SHPublic, b), nil
}

// Proof verifies M1 and returns M2 (f14) and the 40-byte session key.
func (s *SHServer) Proof(data []byte) ([]byte, []byte, error) {
	if s.state != 5 {
		return nil, nil, errors.New("unexpected SH state")
	}
	s.state = 7
	vals, err := wire.ParseSH(data, 5, wire.SHProof)
	if err != nil {
		return nil, nil, err
	}
	if subtle.ConstantTimeCompare(vals[0], s.m1) != 1 {
		return nil, nil, errors.New("SH client proof rejected")
	}
	return wire.SHMessage(6, wire.SHProof, s.m2), s.key, nil
}
