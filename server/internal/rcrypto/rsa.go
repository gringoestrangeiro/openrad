package rcrypto

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/binary"
	"errors"
)

// GenerateKey creates the server's RSA key. Clients accept 87..1024-byte moduli
// with e = 65537.
func GenerateKey(bits int) (*rsa.PrivateKey, error) {
	return rsa.GenerateKey(rand.Reader, bits)
}

func MarshalKey(k *rsa.PrivateKey) []byte { return x509.MarshalPKCS1PrivateKey(k) }

func ParseKey(der []byte) (*rsa.PrivateKey, error) {
	k, err := x509.ParsePKCS1PrivateKey(der)
	if err != nil {
		return nil, err
	}
	if k.E != 65537 {
		return nil, errors.New("server RSA key must use e = 65537")
	}
	return k, nil
}

// Modulus returns the raw big-endian modulus, the format of the client's --modulus file.
func Modulus(k *rsa.PrivateKey) []byte { return k.N.Bytes() }

// RSession is the decrypted f6 bootstrap message.
type RSession struct {
	Purpose uint32
	Secret  []byte // 64 bytes; the first 32 key the initial CChannel
}

// OpenRSession decrypts f6: PKCS#1 v1.5 type 2 over LE (3, 0x4b, purpose) || secret[64].
func OpenRSession(k *rsa.PrivateKey, f6 []byte) (*RSession, error) {
	if len(f6) != k.Size() {
		return nil, errors.New("RSession ciphertext width mismatch")
	}
	msg, err := rsa.DecryptPKCS1v15(nil, k, f6)
	if err != nil {
		return nil, errors.New("RSession decryption failed")
	}
	if len(msg) != 12+64 {
		return nil, errors.New("RSession plaintext length mismatch")
	}
	if binary.LittleEndian.Uint32(msg[0:]) != 3 || binary.LittleEndian.Uint32(msg[4:]) != 0x4b {
		return nil, errors.New("RSession header mismatch")
	}
	purpose := binary.LittleEndian.Uint32(msg[8:])
	if purpose != 3 && purpose != 4 && purpose != 5 {
		return nil, errors.New("unsupported RSession purpose")
	}
	return &RSession{Purpose: purpose, Secret: append([]byte(nil), msg[12:]...)}, nil
}
