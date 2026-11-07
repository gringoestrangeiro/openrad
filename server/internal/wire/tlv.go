// Package wire implements the OpenRad/Radmin control-plane encodings:
// big-endian TLV records, UTF-16BE text with a U+0001 terminator, the SH
// authentication record format and 4-byte length-prefixed framing.
package wire

import (
	"encoding/binary"
	"errors"
	"fmt"
	"unicode/utf16"
)

// Operation tags.
const (
	ClientOp = 0x0100031f
	ServerOp = 0x01000320
)

const (
	MaxRecords   = 16384
	MaxPlaintext = 4 * 1024 * 1024
)

// Record is one TLV entry. Value aliases the parsed buffer.
type Record struct {
	Tag   uint32
	Value []byte
}

// Records is one parsed TLV level.
type Records []Record

// Parse parses one TLV level; nested values remain opaque.
func Parse(data []byte) (Records, error) {
	if len(data) > MaxPlaintext {
		return nil, errors.New("TLV payload exceeds limit")
	}
	var out Records
	off := 0
	for off < len(data) {
		if len(out) >= MaxRecords || len(data)-off < 8 {
			return nil, errors.New("invalid TLV header or record count")
		}
		size := int(binary.BigEndian.Uint32(data[off:]))
		tag := binary.BigEndian.Uint32(data[off+4:])
		off += 8
		if size > len(data)-off {
			return nil, errors.New("truncated TLV body")
		}
		out = append(out, Record{Tag: tag, Value: data[off : off+size]})
		off += size
	}
	return out, nil
}

// Optional returns the single value for tag, nil when absent, or an error on duplicates.
func (r Records) Optional(tag uint32) ([]byte, bool, error) {
	var found []byte
	ok := false
	for _, rec := range r {
		if rec.Tag == tag {
			if ok {
				return nil, false, fmt.Errorf("duplicate singleton tag %#010x", tag)
			}
			found, ok = rec.Value, true
		}
	}
	return found, ok, nil
}

// Field returns a required singleton value.
func (r Records) Field(tag uint32) ([]byte, error) {
	v, ok, err := r.Optional(tag)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, fmt.Errorf("missing tag %#010x", tag)
	}
	return v, nil
}

// All returns every value with the tag, in order.
func (r Records) All(tag uint32) [][]byte {
	var out [][]byte
	for _, rec := range r {
		if rec.Tag == tag {
			out = append(out, rec.Value)
		}
	}
	return out
}

func (r Records) U32(tag uint32) (uint32, error) {
	v, err := r.Field(tag)
	if err != nil {
		return 0, err
	}
	return AsU32(v)
}

func (r Records) U64(tag uint32) (uint64, error) {
	v, err := r.Field(tag)
	if err != nil {
		return 0, err
	}
	return AsU64(v)
}

func (r Records) OptU32(tag uint32) (uint32, bool, error) {
	v, ok, err := r.Optional(tag)
	if err != nil || !ok {
		return 0, ok, err
	}
	n, err := AsU32(v)
	return n, true, err
}

func (r Records) OptU64(tag uint32) (uint64, bool, error) {
	v, ok, err := r.Optional(tag)
	if err != nil || !ok {
		return 0, ok, err
	}
	n, err := AsU64(v)
	return n, true, err
}

func (r Records) Text(tag uint32) (string, error) {
	v, err := r.Field(tag)
	if err != nil {
		return "", err
	}
	return DecodeText(v)
}

func AsU32(v []byte) (uint32, error) {
	if len(v) != 4 {
		return 0, errors.New("u32 field requires four bytes")
	}
	return binary.BigEndian.Uint32(v), nil
}

func AsU64(v []byte) (uint64, error) {
	if len(v) != 8 {
		return 0, errors.New("u64 field requires eight bytes")
	}
	return binary.BigEndian.Uint64(v), nil
}

// Op returns the server or client operation code of a message.
func Op(data []byte, tag uint32) (uint32, Records, error) {
	recs, err := Parse(data)
	if err != nil {
		return 0, nil, err
	}
	op, err := recs.U32(tag)
	return op, recs, err
}

// ---- builders ----

func TLV(tag uint32, value []byte) []byte {
	out := make([]byte, 8+len(value))
	binary.BigEndian.PutUint32(out, uint32(len(value)))
	binary.BigEndian.PutUint32(out[4:], tag)
	copy(out[8:], value)
	return out
}

func U32(tag, v uint32) []byte {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	return TLV(tag, b[:])
}

func U64(tag uint32, v uint64) []byte {
	var b [8]byte
	binary.BigEndian.PutUint64(b[:], v)
	return TLV(tag, b[:])
}

// EncodeText encodes UTF-16BE followed by the 00 01 terminator.
func EncodeText(s string) []byte {
	units := utf16.Encode([]rune(s))
	out := make([]byte, 0, 2*len(units)+2)
	for _, u := range units {
		out = append(out, byte(u>>8), byte(u))
	}
	return append(out, 0, 1)
}

func Text(tag uint32, s string) []byte { return TLV(tag, EncodeText(s)) }

// ASCIIZ is the NUL-terminated byte text used by type-0x0e fields.
func ASCIIZ(tag uint32, s string) []byte { return TLV(tag, append([]byte(s), 0)) }

// DecodeText decodes UTF-16BE text with its final U+0001 terminator.
// Unpaired surrogates become U+FFFD; callers validate content.
func DecodeText(v []byte) (string, error) {
	if len(v) < 2 || len(v) > 8192 || len(v)%2 != 0 || v[len(v)-2] != 0 || v[len(v)-1] != 1 {
		return "", errors.New("invalid text length or terminator")
	}
	units := make([]uint16, 0, len(v)/2-1)
	for i := 0; i+1 < len(v)-2; i += 2 {
		units = append(units, uint16(v[i])<<8|uint16(v[i+1]))
	}
	return string(utf16.Decode(units)), nil
}

// Cat concatenates byte slices.
func Cat(parts ...[]byte) []byte {
	n := 0
	for _, p := range parts {
		n += len(p)
	}
	out := make([]byte, 0, n)
	for _, p := range parts {
		out = append(out, p...)
	}
	return out
}

// ServerMsg builds a server operation followed by fields.
func ServerMsg(op uint32, fields ...[]byte) []byte {
	return Cat(append([][]byte{U32(ServerOp, op)}, fields...)...)
}
