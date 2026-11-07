package rcrypto

import (
	"encoding/binary"
	"math/bits"
)

// md4 implements RFC 1320. It is only used for the CChannel auth trailer.
func md4(msg []byte) [16]byte {
	ml := uint64(len(msg)) * 8
	buf := make([]byte, 0, len(msg)+72)
	buf = append(buf, msg...)
	buf = append(buf, 0x80)
	for len(buf)%64 != 56 {
		buf = append(buf, 0)
	}
	var l [8]byte
	binary.LittleEndian.PutUint64(l[:], ml)
	buf = append(buf, l[:]...)

	a0, b0, c0, d0 := uint32(0x67452301), uint32(0xefcdab89), uint32(0x98badcfe), uint32(0x10325476)
	f := func(x, y, z uint32) uint32 { return (x & y) | (^x & z) }
	g := func(x, y, z uint32) uint32 { return (x & y) | (x & z) | (y & z) }
	h := func(x, y, z uint32) uint32 { return x ^ y ^ z }
	var X [16]uint32
	for off := 0; off < len(buf); off += 64 {
		for i := range X {
			X[i] = binary.LittleEndian.Uint32(buf[off+4*i:])
		}
		a, b, c, d := a0, b0, c0, d0
		for _, i := range []int{0, 4, 8, 12} {
			a = bits.RotateLeft32(a+f(b, c, d)+X[i], 3)
			d = bits.RotateLeft32(d+f(a, b, c)+X[i+1], 7)
			c = bits.RotateLeft32(c+f(d, a, b)+X[i+2], 11)
			b = bits.RotateLeft32(b+f(c, d, a)+X[i+3], 19)
		}
		for _, i := range []int{0, 1, 2, 3} {
			a = bits.RotateLeft32(a+g(b, c, d)+X[i]+0x5A827999, 3)
			d = bits.RotateLeft32(d+g(a, b, c)+X[i+4]+0x5A827999, 5)
			c = bits.RotateLeft32(c+g(d, a, b)+X[i+8]+0x5A827999, 9)
			b = bits.RotateLeft32(b+g(c, d, a)+X[i+12]+0x5A827999, 13)
		}
		for _, i := range []int{0, 2, 1, 3} {
			a = bits.RotateLeft32(a+h(b, c, d)+X[i]+0x6ED9EBA1, 3)
			d = bits.RotateLeft32(d+h(a, b, c)+X[i+8]+0x6ED9EBA1, 9)
			c = bits.RotateLeft32(c+h(d, a, b)+X[i+4]+0x6ED9EBA1, 11)
			b = bits.RotateLeft32(b+h(c, d, a)+X[i+12]+0x6ED9EBA1, 15)
		}
		a0 += a
		b0 += b
		c0 += c
		d0 += d
	}
	var out [16]byte
	binary.LittleEndian.PutUint32(out[0:], a0)
	binary.LittleEndian.PutUint32(out[4:], b0)
	binary.LittleEndian.PutUint32(out[8:], c0)
	binary.LittleEndian.PutUint32(out[12:], d0)
	return out
}
