// Package rcrypto contains the wire-compatible cryptography: the CChannel
// record cipher, the RSA session bootstrap, the SH (SRP-like) server and the
// network identity/verifier helpers.
package rcrypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/subtle"
	"encoding/binary"
	"errors"
)

// Auth8 is the 8-byte CChannel trailer: MD4 of the wrapping LE-u64 sum.
func Auth8(region []byte) [8]byte {
	var sum uint64
	n := len(region) &^ 7
	for i := 0; i < n; i += 8 {
		sum += binary.LittleEndian.Uint64(region[i:])
	}
	if n < len(region) {
		var tail [8]byte
		copy(tail[:], region[n:])
		sum += binary.LittleEndian.Uint64(tail[:])
	}
	var w [8]byte
	binary.LittleEndian.PutUint64(w[:], sum)
	d := md4(w[:])
	var out [8]byte
	for i := range out {
		out[i] = d[i] + d[i+8]
	}
	return out
}

// CChannel is the authenticated AES-256-CBC record channel. Both directions
// start with zero IVs and chain across records; rekeying keeps both IVs.
type CChannel struct {
	enc, dec     cipher.Block
	encIV, decIV [16]byte
}

func NewCChannel(key []byte) (*CChannel, error) {
	c := &CChannel{}
	if err := c.Rekey(key); err != nil {
		return nil, err
	}
	return c, nil
}

// Rekey replaces both key schedules and preserves the CBC chain state.
func (c *CChannel) Rekey(key []byte) error {
	if len(key) != 32 {
		return errors.New("CChannel requires a 32-byte key")
	}
	b, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	c.enc, c.dec = b, b
	return nil
}

// Encrypt pads with 0xcc, appends Auth8 and the padding length, then CBC-encrypts.
func (c *CChannel) Encrypt(pt []byte) ([]byte, error) {
	if len(pt) == 0 || len(pt) > 4*1024*1024 {
		return nil, errors.New("invalid CChannel plaintext length")
	}
	total := (len(pt) + 9 + 15) / 16 * 16
	body := make([]byte, total)
	copy(body, pt)
	for i := len(pt); i < total-9; i++ {
		body[i] = 0xcc
	}
	a := Auth8(body[:total-9])
	copy(body[total-9:], a[:])
	body[total-1] = byte(total - len(pt))
	iv := c.encIV
	for off := 0; off < total; off += 16 {
		blk := body[off : off+16]
		for i := range blk {
			blk[i] ^= iv[i]
		}
		c.enc.Encrypt(blk, blk)
		copy(iv[:], blk)
	}
	c.encIV = iv
	return body, nil
}

// Decrypt verifies and strips a record. A failed record still consumes the IV.
func (c *CChannel) Decrypt(ct []byte) ([]byte, error) {
	if len(ct) == 0 || len(ct)%16 != 0 {
		return nil, errors.New("CChannel requires nonempty complete blocks")
	}
	body := make([]byte, len(ct))
	iv := c.decIV
	for off := 0; off < len(ct); off += 16 {
		c.dec.Decrypt(body[off:off+16], ct[off:off+16])
		for i := 0; i < 16; i++ {
			body[off+i] ^= iv[i]
		}
		copy(iv[:], ct[off:off+16])
	}
	c.decIV = iv
	pad := int(body[len(body)-1])
	if pad < 9 || pad >= 25 || pad >= len(body) {
		return nil, errors.New("invalid CChannel padding length")
	}
	a := Auth8(body[:len(body)-9])
	if subtle.ConstantTimeCompare(a[:], body[len(body)-9:len(body)-1]) != 1 {
		return nil, errors.New("invalid CChannel authentication trailer")
	}
	return body[:len(body)-pad], nil
}
