package wire

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// MaxWire is the client's maximum framed record size.
const MaxWire = 4 * 1024 * 1024

// ReadFrame reads one 4-byte big-endian length-prefixed record.
func ReadFrame(r io.Reader, max int) ([]byte, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := int(binary.BigEndian.Uint32(hdr[:]))
	if n <= 0 || n > max {
		return nil, fmt.Errorf("frame size %d outside 1..%d", n, max)
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return nil, err
	}
	return buf, nil
}

// EncodeFrame prefixes body with its length.
func EncodeFrame(body []byte) ([]byte, error) {
	if len(body) == 0 || len(body) > MaxWire {
		return nil, errors.New("invalid outgoing frame size")
	}
	out := make([]byte, 4+len(body))
	binary.BigEndian.PutUint32(out, uint32(len(body)))
	copy(out[4:], body)
	return out, nil
}

// WriteFrame writes one length-prefixed record.
func WriteFrame(w io.Writer, body []byte) error {
	b, err := EncodeFrame(body)
	if err != nil {
		return err
	}
	_, err = w.Write(b)
	return err
}

// ---- SH record format: 5 kind bits, 27 length bits ----

const (
	SHIndex     = 0x10000000
	SHIdentity  = 0x20000000
	SHPrime     = 0x30000000
	SHGenerator = 0x40000000
	SHSalt      = 0x50000000
	SHPublic    = 0x60000000
	SHProof     = 0x70000000
)

func SHRecord(kind uint32, body []byte) []byte {
	out := make([]byte, 4+len(body))
	binary.BigEndian.PutUint32(out, kind|uint32(len(body)))
	copy(out[4:], body)
	return out
}

func SHMessage(index, kind uint32, body []byte) []byte {
	var idx [4]byte
	binary.BigEndian.PutUint32(idx[:], index)
	return Cat(SHRecord(SHIndex, idx[:]), SHRecord(kind, body))
}

// SHFailure is the one-record failure blob ("state 0").
func SHFailure() []byte { return SHRecord(SHIndex, []byte{0, 0, 0, 0}) }

// ParseSH strictly parses an SH message: index record, the expected kinds, nothing more.
func ParseSH(data []byte, index uint32, kinds ...uint32) ([][]byte, error) {
	var values [][]byte
	off := 0
	for _, expected := range append([]uint32{SHIndex}, kinds...) {
		if off+4 > len(data) {
			return nil, errors.New("truncated SH header")
		}
		word := binary.BigEndian.Uint32(data[off:])
		size := int(word & 0x07ffffff)
		off += 4
		if word&0xf8000000 != expected || size <= 0 || size > 0x2800 {
			return nil, errors.New("invalid SH kind or size")
		}
		if off+size > len(data) {
			return nil, errors.New("truncated SH body")
		}
		values = append(values, data[off:off+size])
		off += size
	}
	if off != len(data) || len(values[0]) != 4 || binary.BigEndian.Uint32(values[0]) != index {
		return nil, errors.New("unexpected SH state or trailing data")
	}
	return values[1:], nil
}
