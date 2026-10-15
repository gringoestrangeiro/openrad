"""OpenRAD wire protocol and crypto, adapted from the MIT Python/Rust clients.

Contains the wire-compatible RSA/SH/CChannel, network management and Ethernet
code. No vendor runtime or external source checkout is required at runtime.
"""
from dataclasses import dataclass, field
from functools import lru_cache
from hashlib import sha1
from ipaddress import IPv4Address, IPv6Address
import hashlib
import hmac
import json
import secrets
import struct
import sys


# gen_sbox (openrad/work, MIT)


def xtime(a):
    return ((a << 1) ^ 0x11B) & 0xFF if a & 0x80 else (a << 1) & 0xFF


def mul(a, b):
    r = 0
    while b:
        if b & 1:
            r ^= a
        a, b = xtime(a), b >> 1
    return r


def inv(a):
    if a == 0:
        return 0
    for b in range(1, 256):
        if mul(a, b) == 1:
            return b


def tables():
    sbox = bytearray(256)
    for i in range(256):
        v = inv(i)
        out = 0
        for bit in range(8):
            b = (((v >> bit) & 1) ^ ((v >> ((bit + 4) % 8)) & 1)
                 ^ ((v >> ((bit + 5) % 8)) & 1) ^ ((v >> ((bit + 6) % 8)) & 1)
                 ^ ((v >> ((bit + 7) % 8)) & 1) ^ ((0x63 >> bit) & 1))
            out |= b << bit
        sbox[i] = out
    invbox = bytearray(256)
    for i, v in enumerate(sbox):
        invbox[v] = i
    assert sbox[0x53] == 0xED and sbox[0x00] == 0x63 and sbox[0x01] == 0x7C
    assert invbox[0xED] == 0x53
    return bytes(sbox), bytes(invbox)





# aes_pure (openrad/work, MIT)

SBOX, INV = tables()
RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36,
        0x6C, 0xD8, 0xAB, 0x4D, 0x9A, 0x2F, 0x5E, 0xBC, 0x63, 0xC6]


# The key-schedule cache retains key bytes until eviction. Rekeying entry
# points call expand.cache_clear() to drop them promptly. This only drops
# references: CPython bytes are immutable, so memory cannot be truly wiped.
# See the security section of AGENTS.md.
@lru_cache(maxsize=128)
def expand(key):
    if len(key) not in (16, 24, 32):
        raise ValueError("AES key must contain 16, 24 or 32 bytes")
    nk = len(key) // 4
    nr = {4: 10, 6: 12, 8: 14}[nk]
    w = [list(key[i:i + 4]) for i in range(0, len(key), 4)]
    for i in range(nk, 4 * (nr + 1)):
        t = w[i - 1][:]
        if i % nk == 0:
            t = t[1:] + t[:1]
            t = [SBOX[b] for b in t]
            t[0] ^= RCON[i // nk - 1]
        elif nk > 6 and i % nk == 4:
            t = [SBOX[b] for b in t]
        w.append([a ^ b for a, b in zip(w[i - nk], t)])
    return [b for word in w for b in word], nr


def _add(st, rk, r):
    return [s ^ rk[16 * r + i] for i, s in enumerate(st)]


def _sub(st):
    return [SBOX[b] for b in st]


def _isub(st):
    return [INV[b] for b in st]


def _shift(st):
    return [st[0], st[5], st[10], st[15], st[4], st[9], st[14], st[3],
            st[8], st[13], st[2], st[7], st[12], st[1], st[6], st[11]]


def _ishift(st):
    return [st[0], st[13], st[10], st[7], st[4], st[1], st[14], st[11],
            st[8], st[5], st[2], st[15], st[12], st[9], st[6], st[3]]


def _xt(b):
    return ((b << 1) ^ 0x1B) & 0xFF if b & 0x80 else (b << 1) & 0xFF


def _mix_col(a, b, c, d):
    x2 = [_xt(x) for x in (a, b, c, d)]
    x3 = [x ^ y for x, y in zip(x2, (a, b, c, d))]
    return (x2[0] ^ x3[1] ^ c ^ d,
            a ^ x2[1] ^ x3[2] ^ d,
            a ^ b ^ x2[2] ^ x3[3],
            x3[0] ^ b ^ c ^ x2[3])


def _mix(st):
    o = []
    for i in range(0, 16, 4):
        o += list(_mix_col(*st[i:i + 4]))
    return o


def _mul(a, b):
    r = 0
    while b:
        if b & 1:
            r ^= a
        a = _xt(a)
        b >>= 1
    return r & 0xFF


def _imix_col(a, b, c, d):
    return (_mul(a, 14) ^ _mul(b, 11) ^ _mul(c, 13) ^ _mul(d, 9),
            _mul(a, 9) ^ _mul(b, 14) ^ _mul(c, 11) ^ _mul(d, 13),
            _mul(a, 13) ^ _mul(b, 9) ^ _mul(c, 14) ^ _mul(d, 11),
            _mul(a, 11) ^ _mul(b, 13) ^ _mul(c, 9) ^ _mul(d, 14))


def _imix(st):
    o = []
    for i in range(0, 16, 4):
        o += list(_imix_col(*st[i:i + 4]))
    return o


def enc_block(key, pt):
    if len(pt) != 16:
        raise ValueError("AES block must contain 16 bytes")
    rk, nr = expand(key)
    st = _add(list(pt), rk, 0)
    for r in range(1, nr):
        st = _add(_mix(_shift(_sub(st))), rk, r)
    return bytes(_add(_shift(_sub(st)), rk, nr))


def dec_block(key, ct):
    if len(ct) != 16:
        raise ValueError("AES block must contain 16 bytes")
    rk, nr = expand(key)
    st = _add(list(ct), rk, nr)
    for r in range(nr - 1, 0, -1):
        st = _imix(_add(_ishift(_isub(st)), rk, r))
    return bytes(_add(_ishift(_isub(st)), rk, 0))


def cbc_dec(key, ct, iv):
    if len(iv) != 16 or len(ct) % 16:
        raise ValueError("CBC requires a 16-byte IV and complete blocks")
    out = b''
    prev = iv
    for i in range(0, len(ct), 16):
        p = dec_block(key, ct[i:i + 16])
        out += bytes(a ^ b for a, b in zip(p, prev))
        prev = ct[i:i + 16]
    return out



# cc_pure (openrad/work, MIT)


def _lrot(x, n):
    return ((x << n) | (x >> (32 - n))) & 0xFFFFFFFF


def md4(msg):
    ml = len(msg) * 8
    msg = bytearray(msg) + b"\x80"
    while len(msg) % 64 != 56:
        msg += b"\x00"
    msg += struct.pack("<Q", ml & 0xFFFFFFFFFFFFFFFF)
    A, B, C, D = 0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476

    def F(x, y, z):
        return (x & y) | ((~x) & z)

    def G(x, y, z):
        return (x & y) | (x & z) | (y & z)

    def H(x, y, z):
        return x ^ y ^ z

    for off in range(0, len(msg), 64):
        X = list(struct.unpack("<16I", bytes(msg[off:off + 64])))
        a, b, c, d = A, B, C, D
        for i in [0, 4, 8, 12]:
            a = _lrot((a + F(b, c, d) + X[i]) & 0xFFFFFFFF, 3)
            d = _lrot((d + F(a, b, c) + X[i + 1]) & 0xFFFFFFFF, 7)
            c = _lrot((c + F(d, a, b) + X[i + 2]) & 0xFFFFFFFF, 11)
            b = _lrot((b + F(c, d, a) + X[i + 3]) & 0xFFFFFFFF, 19)
        for i in [0, 1, 2, 3]:
            a = _lrot((a + G(b, c, d) + X[i] + 0x5A827999) & 0xFFFFFFFF, 3)
            d = _lrot((d + G(a, b, c) + X[i + 4] + 0x5A827999) & 0xFFFFFFFF, 5)
            c = _lrot((c + G(d, a, b) + X[i + 8] + 0x5A827999) & 0xFFFFFFFF, 9)
            b = _lrot((b + G(c, d, a) + X[i + 12] + 0x5A827999) & 0xFFFFFFFF, 13)
        for i in [0, 2, 1, 3]:
            a = _lrot((a + H(b, c, d) + X[i] + 0x6ED9EBA1) & 0xFFFFFFFF, 3)
            d = _lrot((d + H(a, b, c) + X[i + 8] + 0x6ED9EBA1) & 0xFFFFFFFF, 9)
            c = _lrot((c + H(d, a, b) + X[i + 4] + 0x6ED9EBA1) & 0xFFFFFFFF, 11)
            b = _lrot((b + H(c, d, a) + X[i + 12] + 0x6ED9EBA1) & 0xFFFFFFFF, 15)
        A = (A + a) & 0xFFFFFFFF
        B = (B + b) & 0xFFFFFFFF
        C = (C + c) & 0xFFFFFFFF
        D = (D + d) & 0xFFFFFFFF
    return struct.pack("<4I", A, B, C, D)


def sum64_le(data):
    # Native uint64 loads are little-endian on Linux x86/ARM. Mask once:
    # addition modulo 2**64 is associative, including the partial last word.
    if sys.byteorder == 'little' and isinstance(data, (bytes, bytearray, memoryview)):
        view = memoryview(data)
        if view.c_contiguous and view.ndim == 1 and view.itemsize == 1:
            end = len(view) & ~7
            total = sum(view[:end].cast('Q')) if end else 0
            return (total + int.from_bytes(view[end:], 'little')) & 0xFFFFFFFFFFFFFFFF
    s = 0
    for i in range(len(data) // 8):
        s = (s + int.from_bytes(data[i * 8:i * 8 + 8], "little")) & 0xFFFFFFFFFFFFFFFF
    t = data[len(data) // 8 * 8:]
    if t:
        s = (s + int.from_bytes(t, "little")) & 0xFFFFFFFFFFFFFFFF
    return s


def auth8(region):
    word = sum64_le(region).to_bytes(8, "little")
    d = _MD4.new(word).digest() if _MD4 is not None else md4(word)
    return bytes((d[i] + d[i + 8]) & 0xFF for i in range(8))


class PureCC:
    """Fresh CChannel: independent enc/dec IVs, zero-init, chain by last block."""

    def __init__(self, key):
        self.iv_e = bytes(16)
        self.iv_d = bytes(16)
        self.set_key(key)

    def set_key(self, key):
        self._check_key(key)
        self.key_e = self.key_d = bytes(key)
        self._cipher_e = self._cipher_d = None
        # Binary setters update schedules only; both CBC IVs survive rekeying.
        # Drop the previous key schedule, which otherwise retains key bytes.
        expand.cache_clear()

    @staticmethod
    def _check_key(key):
        if len(key) != 32:
            raise ValueError("CChannel requires a 32-byte key")

    def set_key_enc(self, key):
        self._check_key(key)
        self.key_e = bytes(key)
        self._cipher_e = None
        expand.cache_clear()

    def set_key_dec(self, key):
        self._check_key(key)
        self.key_d = bytes(key)
        self._cipher_d = None
        expand.cache_clear()

    def enc(self, pt):
        if not pt:
            raise ValueError("CChannel rejects empty plaintext")
        pt = bytes(pt)
        total = ((len(pt) + 9 + 15) // 16) * 16
        padcc = total - len(pt) - 9
        region = pt + b"\xcc" * padcc
        a = auth8(region)
        body = region + a + bytes([total - len(pt)])
        assert len(body) == total
        if _AES is not None:
            if (self._cipher_e is None or self._cipher_e_key != self.key_e
                    or self._cipher_e_iv != self.iv_e):
                self._cipher_e = _AES.new(self.key_e, _AES.MODE_CBC, self.iv_e)
                self._cipher_e_key = self.key_e
            out = self._cipher_e.encrypt(body)
            self.iv_e = out[-16:]
            self._cipher_e_iv = self.iv_e
            return out
        out = b""
        iv = self.iv_e
        for i in range(0, total, 16):
            blk = bytes(x ^ y for x, y in zip(body[i:i + 16], iv))
            ct = _python_enc_block(self.key_e, blk)
            out += ct
            iv = ct
        self.iv_e = iv
        return out

    def dec(self, ct):
        if not ct or len(ct) % 16:
            raise ValueError("CChannel requires nonempty complete ciphertext blocks")
        ct = bytes(ct)
        if _AES is not None:
            if (self._cipher_d is None or self._cipher_d_key != self.key_d
                    or self._cipher_d_iv != self.iv_d):
                self._cipher_d = _AES.new(self.key_d, _AES.MODE_CBC, self.iv_d)
                self._cipher_d_key = self.key_d
            body = self._cipher_d.decrypt(ct)
            self._cipher_d_iv = ct[-16:]
        else:
            body = bytearray()
            iv = self.iv_d
            for off in range(0, len(ct), 16):
                block = ct[off:off + 16]
                body.extend(x ^ y for x, y in zip(_python_dec_block(self.key_d, block), iv))
                iv = block
        # Decryption precedes verification in 0x10069d70; failure consumes IV.
        self.iv_d = ct[-16:]
        padding = body[-1]
        if not 9 <= padding < 25 or padding >= len(body):
            raise ValueError("invalid CChannel padding length")
        if not hmac.compare_digest(auth8(body[:-9]), bytes(body[-9:-1])):
            raise ValueError("invalid CChannel authentication trailer")
        # The vendor verifier does not check that filler bytes equal 0xcc.
        return bytes(body[:-padding])

# rsa_pure (openrad/work, MIT)


def encode_v15(message, width, randbytes=secrets.token_bytes):
    count = width - len(message) - 3
    if count < 8:
        raise ValueError("RSA message is too long")
    padding = bytearray()
    # Bound a broken injected RNG instead of hanging indefinitely.
    for _ in range(128):
        missing = count - len(padding)
        if not missing:
            break
        block = randbytes(missing)
        if len(block) != missing:
            raise ValueError("RSA RNG returned the wrong byte count")
        padding.extend(b for b in block if b)
    if len(padding) != count:
        raise ValueError("RSA RNG did not produce nonzero padding")
    return b"\0\2" + padding + b"\0" + message


@dataclass(frozen=True)
class RSession:
    f6: bytes
    secret: bytes = field(repr=False)


def create_rsession(modulus, exponent=65537, *, purpose=4, secret=None,
                    randbytes=secrets.token_bytes):
    if purpose not in (3, 4, 5):
        raise ValueError("unsupported RSession purpose")
    n = int.from_bytes(modulus, "big") if isinstance(modulus, bytes) else modulus
    if n <= 0 or not n & 1 or exponent < 3 or not exponent & 1 or exponent >= n:
        raise ValueError("invalid RSA public key")
    width = (n.bit_length() + 7) // 8
    secret = randbytes(64) if secret is None else bytes(secret)
    if len(secret) != 64:
        raise ValueError("RSession needs exactly 64 secret bytes")
    encoded = encode_v15(struct.pack("<III", 3, 0x4b, purpose) + secret, width, randbytes)
    value = int.from_bytes(encoded, "big")
    if value >= n:
        raise ValueError("RSA encoded message exceeds modulus")
    return RSession(pow(value, exponent, n).to_bytes(width, "big"), secret)

# sh_pure (openrad/work, MIT)


# 0x100699e0 compares f10 N/g byte-for-byte with these compiled values.
# Public parameters read at 0x100ac280 / 0x100ac279 (see crypto_evidence/group.json).
GROUP_PRIME = bytes.fromhex(
    "e634471e75f2d76a0a3f5e252c5a6efb48edad645d31d9ecbc400416e14f5c6c"
    "2ed378602d6eb8e9478b99e6474932393538b4be7177e0e05d3aaed17b5ae87a"
    "a20f3dad37af019518f45b6dde76b2bfdfb140f379cc15383b304b7975b90796"
    "7553cd6fdaf08d68832b603120430a501067d21beb2ce2275a20036a2dc80f1c"
    "9d3ab9221afd5baceda5c2a31077c54fc6f274aaeba17ddd0b5c68f4f8e751a8"
    "90a58648147a82e6f4f532ccdc939a12942799a713500e17dd4f6ff216cd54c7"
)
GROUP_GENERATOR = b"\x05"


def digest(data):
    return sha1(data).digest()


def bn_bytes(value):
    """BN_bn2bin wrapper 0x1005fad0: unsigned, minimal, big-endian."""
    if value < 0:
        raise ValueError("negative bignum")
    return value.to_bytes((value.bit_length() + 7) // 8, "big")


def record(kind, body):
    # 0x100626e0/0x100629b0 use FIVE kind bits and 27 length bits.
    if kind & 0x07ffffff or not 0 < len(body) <= 0x07ffffff:
        raise ValueError("invalid SH record")
    return struct.pack(">I", kind | len(body)) + body


def message(index, kind, body):
    return record(0x10000000, struct.pack(">I", index)) + record(kind, body)


def parse_message(data, index, kinds):
    """Strict record parsing: reject truncation, wrong kinds and trailing data."""
    values = []
    off = 0
    for expected in (0x10000000, *kinds):
        if off + 4 > len(data):
            raise ValueError("truncated SH header")
        word = struct.unpack_from(">I", data, off)[0]
        size = word & 0x07ffffff
        off += 4
        if word & 0xf8000000 != expected or not 0 < size <= 0x2800:
            raise ValueError("invalid SH kind or size")
        if off + size > len(data):
            raise ValueError("truncated SH body")
        values.append(bytes(data[off:off + size]))
        off += size
    if off != len(data) or values[0] != struct.pack(">I", index):
        raise ValueError("unexpected SH state or trailing data")
    return values[1:]


def identity_bytes(rid):
    """CreateClient swaps bytes within each LE16 word of the u64 RID."""
    raw = rid.to_bytes(8, "little")
    return b"".join(raw[i:i + 2][::-1] for i in range(0, 8, 2))


@dataclass(frozen=True)
class Parameters:
    prime: bytes
    generator: bytes
    salt: bytes

    @classmethod
    def from_f10(cls, f10):
        values = parse_message(f10, 2, (0x30000000, 0x40000000, 0x50000000))
        params = cls(*values)
        if params.prime != GROUP_PRIME or params.generator != GROUP_GENERATOR:
            raise ValueError("SH group does not match the verified client build")
        n, g = int.from_bytes(params.prime, "big"), int.from_bytes(params.generator, "big")
        if n <= 2 or not n & 1 or not 1 < g < n:
            raise ValueError("invalid SH group")
        return params


def password_exponent(params, identity, password):
    # 0x10060340: SHA1(salt || SHA1(identity || ':' || password)).
    return int.from_bytes(digest(params.salt + digest(identity + b":" + password)), "big")


@dataclass(frozen=True, repr=False)
class Confirmation:
    """Intermediate values for deterministic, offline differential checks."""
    public: bytes
    multiplier: bytes
    scrambling: bytes
    shared: bytes
    key: bytes
    body: bytes
    server_body: bytes
    hash_input: bytes

    @property
    def f13(self):
        return message(5, 0x70000000, self.body)


def derive_confirmation(params, identity, private, peer, x, extra=b""):
    """State 4 from explicit inputs; private is the final exponent, not RNG bytes.

    x is the result of password_exponent. Keeping it explicit also permits
    checking intermediate values against the binary without hidden state.
    """
    n, g = int.from_bytes(params.prime, "big"), int.from_bytes(params.generator, "big")
    width = len(bn_bytes(n))
    peer_value = int.from_bytes(peer, "big")
    if private <= 0 or x < 0 or len(peer) > width or not 0 < peer_value < n:
        raise ValueError("invalid SH exponent or peer public value")
    public = bn_bytes(pow(g, private, n))
    multiplier = digest(bn_bytes(n) + bn_bytes(g).rjust(width, b"\0"))
    k = int.from_bytes(multiplier, "big")
    if not k:
        raise ValueError("zero SH multiplier")
    scrambling = digest(public.rjust(width, b"\0") + peer.rjust(width, b"\0"))
    u = int.from_bytes(scrambling, "big")
    verifier = pow(g, x, n)
    shared = bn_bytes(pow((peer_value - k * verifier) % n, private + u * x, n))
    # 0x10061790: SHA1(serialized S || BE32 counter), counters 0 and 1.
    key = b"".join(digest(shared + struct.pack(">I", i)) for i in range(2))
    prefix = bytes(a ^ b for a, b in zip(digest(params.prime), digest(params.generator)))
    hash_input = prefix + digest(identity) + params.salt + public + peer + key + extra
    body = digest(hash_input)
    return Confirmation(public, multiplier, scrambling, shared, key, body,
                        digest(public + body + key), hash_input)


class SHClient:
    """Client states 0→2→4→6→7. Keys become public through API after f14 verifies."""

    def __init__(self, rid, password, *, private=None, randbytes=secrets.token_bytes):
        if len(password) <= 5:
            raise ValueError("SH password must contain more than five bytes")
        self.rid = rid
        self.identity = identity_bytes(rid)
        self.password = bytes(password)
        self.private = private
        self.randbytes = randbytes
        self.state = 0
        self.confirmation = None

    def start(self):
        if self.state != 0:
            raise ValueError("SH already started")
        self.state = 2
        return message(1, 0x20000000, self.rid.to_bytes(8, "little"))

    def receive_f10(self, f10):
        if self.state != 2:
            raise ValueError("SH is not awaiting f10")
        try:
            self.params = Parameters.from_f10(f10)
            n = int.from_bytes(self.params.prime, "big")
            g = int.from_bytes(self.params.generator, "big")
            if self.private is None:
                random = self.randbytes(32)
                if len(random) != 32:
                    raise ValueError("SH RNG must return 32 bytes")
                # 0x10060410/0x1005f830 ADD the modulus bit length, not mask bits.
                self.private = int.from_bytes(random, "big") + n.bit_length()
            if self.private <= 0:
                raise ValueError("invalid private exponent")
            self.x = password_exponent(self.params, self.identity, self.password)
        finally:
            # The password is only needed to derive x. Drop the reference on
            # every path, including failure; bytes are immutable, so CPython
            # cannot wipe the underlying memory (see AGENTS.md).
            self.password = None
        public = bn_bytes(pow(g, self.private, n))
        self.state = 4
        return message(3, 0x60000000, public)

    def receive_f12(self, f12):
        if self.state != 4:
            raise ValueError("SH is not awaiting f12")
        peer, = parse_message(f12, 4, (0x60000000,))
        self.confirmation = derive_confirmation(self.params, self.identity,
                                               self.private, peer, self.x)
        self.state = 6
        return self.confirmation.f13

    def receive_f14(self, f14):
        if self.state != 6:
            raise ValueError("SH is not awaiting f14")
        body, = parse_message(f14, 6, (0x70000000,))
        if not hmac.compare_digest(body, self.confirmation.server_body):
            raise ValueError("invalid SH server confirmation")
        self.state = 7
        return self.confirmation.key

# sh_server (openrad/work, MIT)


class SHServer:
    def __init__(self, rid, password, *, salt=None, private=None):
        if len(password) <= 5:
            raise ValueError("SH password too short")
        self.rid, self.password = rid, password
        self.params = Parameters(GROUP_PRIME, GROUP_GENERATOR,
                                 secrets.token_bytes(16) if salt is None else salt)
        self.private = int.from_bytes(secrets.token_bytes(32), 'big') + 1536 if private is None else private
        if self.private <= 0:
            raise ValueError("invalid private exponent")
        self.state = 1

    def hello(self, data):
        if self.state != 1:
            raise ValueError("unexpected SH state")
        rid, = parse_message(data, 1, (0x20000000,))
        if rid != self.rid.to_bytes(8, 'little'):
            raise ValueError("SH identity mismatch")
        self.state = 3
        return (message(2, 0x30000000, GROUP_PRIME) + record(0x40000000, GROUP_GENERATOR)
                + record(0x50000000, self.params.salt))

    def public(self, data):
        if self.state != 3:
            raise ValueError("unexpected SH state")
        a, = parse_message(data, 3, (0x60000000,))
        n, g = int.from_bytes(GROUP_PRIME, 'big'), 5
        av = int.from_bytes(a, 'big')
        if len(a) > len(GROUP_PRIME) or not 0 < av < n:
            raise ValueError("invalid SH client public")
        try:
            identity = identity_bytes(self.rid)
            x = password_exponent(self.params, identity, self.password)
        finally:
            # As in SHClient.receive_f10: the password is only needed to
            # derive x, so drop it on every path, including failure.
            self.password = None
        v = pow(g, x, n)
        k = int.from_bytes(digest(GROUP_PRIME + GROUP_GENERATOR.rjust(len(GROUP_PRIME), b'\0')), 'big')
        b = bn_bytes((k*v + pow(g, self.private, n)) % n)
        u = int.from_bytes(digest(a.rjust(len(GROUP_PRIME), b'\0') + b.rjust(len(GROUP_PRIME), b'\0')), 'big')
        shared = bn_bytes(pow(av * pow(v, u, n) % n, self.private, n))
        self._key = b''.join(digest(shared + struct.pack('>I', i)) for i in range(2))
        prefix = bytes(x ^ y for x, y in zip(digest(GROUP_PRIME), digest(GROUP_GENERATOR)))
        self.m1 = digest(prefix + digest(identity) + self.params.salt + a + b + self._key)
        self.m2 = digest(a + self.m1 + self._key)
        self.state = 5
        return message(4, 0x60000000, b)

    def proof(self, data):
        if self.state != 5:
            raise ValueError("unexpected SH state")
        proof, = parse_message(data, 5, (0x70000000,))
        if not hmac.compare_digest(proof, self.m1):
            raise ValueError("SH client proof rejected")
        self.state = 7
        return message(6, 0x70000000, self.m2), self._key

# session_crypto (openrad/work, MIT)


class SessionCrypto:
    def __init__(self, modulus, rid, password, *, purpose=4, randbytes=secrets.token_bytes,
                 private=None):
        self.rs = create_rsession(modulus, purpose=purpose, randbytes=randbytes)
        self.purpose = purpose
        self.cc = PureCC(self.rs.secret[:32])
        self.sh = SHClient(rid, password, private=private, randbytes=randbytes)
        self.started = False

    @property
    def f6(self):
        return self.rs.f6

    def accept_echo(self, echo):
        if self.started or echo != b"\x03\0\0\0":
            raise ValueError("unexpected RSession echo")
        self.started = True
        return self.cc.enc(self.sh.start())

    def receive_f10(self, ciphertext):
        return self.cc.enc(self.sh.receive_f10(self.cc.dec(ciphertext)))

    def receive_f12(self, ciphertext):
        return self.cc.enc(self.sh.receive_f12(self.cc.dec(ciphertext)))

    def receive_f14(self, ciphertext):
        key = self.sh.receive_f14(self.cc.dec(ciphertext))
        self.cc.set_key(key[:32])

# registration (openrad/work, MIT)


MAX_RECORDS = 16384
MAX_PLAINTEXT = 4 * 1024 * 1024
SERVER_OPERATION = 0x01000320
NETWORKS = 0x00001316
IPV4 = 0x01000305
ADDRESS = 0x09000305


class ServerRedirect(ValueError):
    """An authenticated server asked the same identity to use another server."""
    def __init__(self, host):
        super().__init__("authenticated server redirect")
        self.host = host


def check_redirect(records):
    if unique(records, SERVER_OPERATION).u32() == 12:
        fields = parse_records(unique(records, 0x1238).value)
        address = parse_records(unique(fields, 0x1234).value)
        host = decode_text(unique(address, 0x030001c9))
        IPv4Address(host)  # Only the observed literal-address form is supported.
        raise ServerRedirect(host)


@dataclass(frozen=True)
class Record:
    tag: int
    value: bytes

    def u32(self):
        if len(self.value) != 4:
            raise ValueError(f"tag {self.tag:#010x} requires four bytes")
        return struct.unpack(">I", self.value)[0]


def parse_records(data):
    """Parse one bounded level; nested/unknown values remain opaque bytes."""
    if len(data) > MAX_PLAINTEXT:
        raise ValueError("TLV payload exceeds limit")
    records, offset = [], 0
    while offset < len(data):
        if len(records) >= MAX_RECORDS or len(data) - offset < 8:
            raise ValueError("invalid TLV header or record count")
        size, tag = struct.unpack_from(">II", data, offset)
        offset += 8
        if size > len(data) - offset:
            raise ValueError("truncated TLV body")
        records.append(Record(tag, data[offset:offset + size]))
        offset += size
    return tuple(records)


def unique(records, tag, *, required=True):
    matches = [record for record in records if record.tag == tag]
    if len(matches) > 1:
        raise ValueError(f"duplicate singleton tag {tag:#010x}")
    if not matches:
        if required:
            raise ValueError(f"missing tag {tag:#010x}")
        return None
    return matches[0]


def tlv(tag, value):
    return struct.pack(">II", len(value), tag) + value


def u32(tag, value):
    return tlv(tag, struct.pack(">I", value))


def text16(tag, value):
    if not value or any(ord(char) < 32 for char in value):
        raise ValueError("name/version must be nonempty and contain no control characters")
    encoded = value.encode("utf-16-be")
    if len(encoded) > 512:
        raise ValueError("name/version exceeds client limit")
    return tlv(tag, encoded + b"\x00\x01")


def round_milliseconds(nanoseconds):
    """ROL 0x10001ff0: nearest integer millisecond, ties to even."""
    if nanoseconds < 0:
        raise ValueError("negative elapsed time")
    whole, remainder = divmod(nanoseconds, 1_000_000)
    return whole + int(remainder > 500_000 or remainder == 500_000 and whole % 2)


def build_login(node_name, latency_ms, *, purpose=4):
    """Construct f15 using the observed 2.1.4951.1 compatibility profile.

    These OS/profile values reproduce the owned install's saved metadata; this
    does not claim to detect the host OS. latency_ms is measured, never persisted
    or incremented between attempts. No captured packet/template is loaded.
    """
    if purpose not in (3, 4):
        raise ValueError("unsupported Login purpose")
    if not 0 <= latency_ms < 0xffffffff:
        raise ValueError("latency does not fit the Login field")
    identity = (u32(0x0100023b, 31) + u32(0x0100025a, 0)
                + text16(0x03000304, node_name))
    version = u32(0x010001ec, 8) + text16(0x030001ed, "2.1.4951.1")
    body = (u32(0x0100031f, 20) + u32(0x010003e0, 11)
            + tlv(0x0000122f, identity) + tlv(0x00001286, version))
    # ROL 0x10018010 / 0x10018090 omit both fields for provisioning.
    if purpose == 3:
        return body
    profile = b"".join(u32(tag, value) for tag, value in (
        (0x01000337, 10), (0x01000338, 0), (0x01000339, 19045),
        (0x0100033a, 0), (0x0b00033b, 0), (0x0b00039f, 1),
        (0x010003c8, 0), (0x010003c9, 34404), (0x0b00033c, 1),
        (0x010003dc, 1),
    ))
    body += tlv(0x00001331, profile)
    return body + (u32(0x01000343, latency_ms) if latency_ms else b"")


@dataclass(frozen=True)
class Assignment:
    ipv4: str
    address: bytes = field(repr=False)


def own_assignment(records):
    """Read only the direct Networks address pair, never recurse into peers."""
    if unique(records, SERVER_OPERATION).u32() != 38:
        return None
    children = parse_records(unique(records, NETWORKS).value)
    ipv4 = unique(children, IPV4, required=False)
    address = unique(children, ADDRESS, required=False)
    if ipv4 is None and address is None:
        return None
    if ipv4 is None or address is None or len(address.value) != 16:
        raise ValueError("incomplete or malformed own-address pair")
    value = ipv4.u32()
    if value in (0, 0xffffffff):
        raise ValueError("server returned an unspecified/broadcast VIP")
    return Assignment(str(IPv4Address(value)), address.value)


class Registration:
    """Minimal LoginComplete -> optional Message -> Networks receiver."""
    def __init__(self):
        self.login_complete = False
        self.assignment = None

    def receive(self, plaintext):
        if self.assignment is not None:
            raise ValueError("assignment already received")
        records = parse_records(plaintext)
        check_redirect(records)
        operation = unique(records, SERVER_OPERATION).u32()
        if not self.login_complete:
            if operation != 21:
                raise ValueError(f"expected LoginComplete, received operation {operation}")
            self.login_complete = True
        elif operation == 38:
            self.assignment = own_assignment(records)
        elif operation != 9:
            raise ValueError(f"unexpected registration operation {operation}")
        return operation, self.assignment

# provisioning (openrad/work, MIT)



BOOTSTRAP_PASSWORD = b"rpassword"
REGISTERED = 0x00001233
RID = 0x020001e1
NODE_NAME = 0x03000304
CREDENTIAL = 0x0a0001c8
SERVER_ADDRESS = 0x030001e2
LEGACY_CREDENTIAL = 0x0a0001c7


def bootstrap_session(modulus):
    """Choose one native bootstrap selector, with fresh RSA and SH randomness."""
    rid = secrets.randbelow(1000) + 1
    return rid, SessionCrypto(modulus, rid, BOOTSTRAP_PASSWORD, purpose=3)


def decode_text(record):
    # SVC 0x474790: even byte count, UTF-16BE, final U+0001.
    value = record.value
    if not 4 <= len(value) <= 8192 or len(value) % 2 or value[-2:] != b"\0\1":
        raise ValueError("invalid registration text length or terminator")
    try:
        decoded = value[:-2].decode("utf-16-be")
    except UnicodeError:
        raise ValueError("invalid registration text encoding") from None
    if "\0" in decoded:
        raise ValueError("embedded NUL in registration text")
    return decoded


@dataclass(frozen=True)
class Identity:
    rid: int
    ipv4: str
    node_name: str
    address: bytes = field(repr=False)
    credential: bytes = field(repr=False)
    server_address: str
    legacy_credential: bytes = field(default=b"", repr=False)

    def __post_init__(self):
        if type(self.rid) is not int or not 0 < self.rid < 1 << 64:
            raise ValueError("invalid assigned RID")
        if int(IPv4Address(self.ipv4)) in (0, 0xffffffff):
            raise ValueError("invalid assigned VIP")
        if len(self.address) != 16 or not any(self.address):
            raise ValueError("invalid assigned companion address")
        if not 6 <= len(self.credential) <= 4096 or len(self.legacy_credential) > 4096:
            raise ValueError("invalid assigned credential length")
        for value in (self.node_name, self.server_address):
            if not isinstance(value, str) or not value or len(value) > 4095 or "\0" in value:
                raise ValueError("invalid assigned name or server address")

    def save(self, capture):
        """Persist the reusable identity first, all through exclusive private files."""
        data = {"format": "openrad-identity-v1", "rid": self.rid,
                "vip": self.ipv4, "node_name": self.node_name,
                "address": self.address.hex(), "credential": self.credential.hex(),
                "server_address": self.server_address, "legacy_credential": self.legacy_credential.hex()}
        capture.save("identity.json", (json.dumps(data, indent=2) + "\n").encode())
        capture.save("credential.bin", self.credential)
        capture.save("password.hex", (self.credential.hex() + "\n").encode("ascii"))

    @classmethod
    def load(cls, path):
        """Read our private identity file for a later purpose-4 login."""
        if path.stat().st_size > 65536:
            raise ValueError("identity file exceeds limit")
        try:
            data = json.loads(path.read_text())
            if data.get("format", "openrad-identity-v1") != "openrad-identity-v1":
                raise ValueError()
            return cls(data["rid"], data["vip"], data["node_name"],
                       bytes.fromhex(data["address"]), bytes.fromhex(data["credential"]),
                       data["server_address"], bytes.fromhex(data.get("legacy_credential", "")))
        except (KeyError, TypeError, ValueError, AttributeError):
            # Never include private JSON/text in diagnostics.
            raise ValueError("invalid openrad identity file") from None


def registered_identity(records):
    """Parse only the direct 0x1233 container from an authenticated Registered."""
    if unique(records, SERVER_OPERATION).u32() != 8:
        raise ValueError("expected Registered")
    fields = parse_records(unique(records, REGISTERED).value)
    rid = unique(fields, RID).value
    if len(rid) != 8:
        raise ValueError("assigned RID requires eight bytes")
    legacy = unique(fields, LEGACY_CREDENTIAL, required=False)
    return Identity(
        struct.unpack(">Q", rid)[0],
        str(IPv4Address(unique(fields, IPV4).u32())),
        decode_text(unique(fields, NODE_NAME)),
        unique(fields, ADDRESS).value,
        unique(fields, CREDENTIAL).value,
        decode_text(unique(fields, SERVER_ADDRESS)),
        legacy.value if legacy else b"",
    )


class Provisioning:
    """Purpose-3 LoginComplete -> Registered, exactly once."""
    def __init__(self):
        self.login_complete = False
        self.assignment = None

    def receive(self, plaintext):
        if self.assignment is not None:
            raise ValueError("new identity already received")
        records = parse_records(plaintext)
        check_redirect(records)
        operation = unique(records, SERVER_OPERATION).u32()
        if not self.login_complete:
            if operation != 21:
                raise ValueError(f"expected LoginComplete, received operation {operation}")
            self.login_complete = True
        elif operation == 8:
            self.assignment = registered_identity(records)
        else:
            raise ValueError(f"unexpected provisioning operation {operation}")
        return operation, self.assignment

# network_protocol (openrad/work, MIT)


CLIENT_OPERATION = 0x0100031f
SERVER_OPERATION = 0x01000320


def u64(tag, value):
    if type(value) is not int or not 0 <= value < 1 << 64:
        raise ValueError("invalid unsigned 64-bit field")
    return tlv(tag, struct.pack(">Q", value))


def build_public_list(query="", *, request_id=1, cursor=0):
    # Native text serializer permits an empty search string.
    name = text16(0x03000306, query) if query else tlv(0x03000306, b"\0\1")
    return u32(CLIENT_OPERATION, 43) + tlv(0x1332,
        u64(0x02000340, request_id) + u64(0x0200030f, cursor) + name)


def build_public_join(name, *, request_id=2, sequence=1):
    if not 0 < sequence < 1 << 32:
        raise ValueError("invalid JOIN sequence")
    return u32(CLIENT_OPERATION, 39) + tlv(0x131c,
        text16(0x03000306, name) + u32(0x0b00033f, 1)
        + u64(0x02000340, request_id) + u32(0x010003be, sequence))


def build_connect_login(name, latency_ms, peer_rid):
    # ROL 0x1008f660 / 0x100180f0 / 0x10017f60: purpose 5 has no OS
    # container; repeated peer RID fields and the connection option follow RTT.
    result = build_login(name, 0, purpose=3)
    if latency_ms:
        result += u32(0x01000343, latency_ms)
    return result + u64(0x0200030b, peer_rid) + u32(0x01000377, 0)


def build_request_relay(connection_id):
    # ROL 0x100185f0 / 0x10016640.
    return u32(CLIENT_OPERATION, 22) + u64(0x020001c1, connection_id)


def read_u64(record):
    if len(record.value) != 8:
        raise ValueError("invalid 64-bit field width")
    return int.from_bytes(record.value, "big")


def public_listing(plaintext, request_id=1):
    fields = parse_records(unique(parse_records(plaintext), 0x1334).value)
    if read_u64(unique(fields, 0x02000340)) != request_id:
        raise ValueError("public listing request ID mismatch")
    networks = []
    for entry in fields:
        if entry.tag == 0x1333:
            values = parse_records(entry.value)
            networks.append({"name": decode_text(unique(values, 0x03000306)),
                             "reported_count": unique(values, 0x01000341).u32()})
    return {"networks": networks, "cursor": read_u64(unique(fields, 0x0200030f))}


def networks_from_snapshot(plaintext):
    root = unique(parse_records(plaintext), 0x1316, required=False)
    if root is None:
        return []
    result = []
    for entry in parse_records(root.value):
        if entry.tag == 0x1315:
            values = parse_records(entry.value)
            network_id = unique(values, 0x0d000309).value
            if len(network_id) != 16:
                raise ValueError("invalid network ID width")
            result.append({"name": decode_text(unique(values, 0x03000306)),
                           "network_id": network_id.hex()})
    return result


def public_join_result(plaintext, name, request_id=2):
    """Recognize the exercised op-37 JOIN response and its network snapshot."""
    records = parse_records(plaintext)
    if unique(records, SERVER_OPERATION).u32() != 37:
        return None
    fields = parse_records(unique(records, 0x131a).value)
    if read_u64(unique(fields, 0x02000340)) != request_id:
        return None
    snapshot = unique(fields, 0x1316, required=False)
    result_code = unique(fields, 0x0100030c).u32()
    if snapshot is None:
        raise ValueError(f"public JOIN returned code {result_code} without a network snapshot")
    networks = networks_from_snapshot(tlv(0x1316, snapshot.value))
    matches = [n for n in networks if n["name"] == name]
    if len(matches) != 1:
        raise ValueError("JOIN snapshot does not contain the requested network exactly once")
    return {**matches[0], "response_code": result_code}


def approved_network_ids(plaintext):
    records = parse_records(plaintext)
    if unique(records, SERVER_OPERATION).u32() != 42:
        return set()
    result = set()
    for entry in parse_records(unique(records, 0x131f).value):
        if entry.tag == 0x131e:
            identifier = unique(parse_records(entry.value), 0x0d000309).value
            if len(identifier) != 16:
                raise ValueError("invalid approval network ID")
            result.add(identifier.hex())
    return result


def record_tree(data, *, depth=0):
    """Bounded diagnostic tree; opaque blobs are represented by sizes only."""
    if depth > 8:
        raise ValueError("TLV nesting exceeds limit")
    result = []
    for rec in parse_records(data):
        item = {"tag": f"{rec.tag:08x}", "size": len(rec.value)}
        kind = rec.tag >> 24
        if kind == 0:
            item["children"] = record_tree(rec.value, depth=depth + 1)
        elif kind in (1, 2, 11) and len(rec.value) in (4, 8):
            item["value"] = int.from_bytes(rec.value, "big")
        elif kind == 3 and len(rec.value) % 2 == 0 and rec.value.endswith(b"\0\1"):
            item["text"] = rec.value[:-2].decode("utf-16-be", errors="replace")
        result.append(item)
    return result

# tunnel_protocol (openrad/work, MIT)



def build_syn(rid, mac):
    if len(mac) != 6 or mac[0] & 1:
        raise ValueError("expected a unicast Ethernet MAC")
    return (u32(0x01000302, 8) + tlv(0x09000304, mac)
            + u64(0x020001e1, rid))


def accept_synack(plaintext):
    fields = parse_records(plaintext)
    status = unique(fields, 0x02000303).value
    if status != bytes(8):
        raise ValueError(f"peer service refused handshake: {status.hex()}")
    version = unique(fields, 0x01000302).u32()
    mac = unique(fields, 0x09000304).value
    if version < 5 or len(mac) != 6 or mac[0] & 1:
        raise ValueError("unsupported peer service version or MAC")
    version = min(version, 8)
    ack = u64(0x02000303, 0) + u32(0x01000302, version)
    return ack, version, mac


def keepalive(sequence, *, reply=False):
    if not 0 < sequence < 1 << 32:
        raise ValueError("invalid keepalive sequence")
    return struct.pack(">HI", 7 if reply else 3, sequence)


def encode_frame(ethernet):
    if not 14 <= len(ethernet) <= 65535:
        raise ValueError("invalid Ethernet frame length")
    return b"\0\0" + struct.pack("<II", 0, len(ethernet)) + ethernet


def decode_packet(plaintext):
    if len(plaintext) < 2:
        raise ValueError("truncated tunnel packet")
    kind = int.from_bytes(plaintext[:2], "big")
    if kind == 0:
        # 0x427e00 iterates multiple driver frames in one service record.
        frames, offset = [], 2
        while offset < len(plaintext):
            if len(plaintext) - offset < 22:
                raise ValueError("truncated Ethernet envelope")
            flags, length = struct.unpack_from("<II", plaintext, offset)
            offset += 8
            if flags != 0 or not 14 <= length <= 65535 or offset + length > len(plaintext):
                raise ValueError("Ethernet envelope length mismatch")
            frames.append({"flags": flags, "ethernet": plaintext[offset:offset+length]})
            offset += length
        if not frames:
            raise ValueError("empty Ethernet envelope")
        return {"kind": kind, "frames": frames}
    if kind in (3, 7):
        if len(plaintext) != 6:
            raise ValueError("invalid keepalive length")
        return {"kind": kind, "sequence": int.from_bytes(plaintext[2:], "big")}
    return {"kind": kind, "bytes": len(plaintext)}

# Userspace Ethernet primitives (openrad/work, MIT)
def checksum(data):
    data = bytes(data)  # Padding must not mutate a caller's bytearray packet.
    if len(data) % 2:
        data += b"\0"
    # Cast without unpacking a tuple of Python integers for each 16-bit word.
    # memoryview uses host byte order, so swap the folded sum on little-endian hosts.
    total = sum(memoryview(data).cast("H"))
    while total >> 16:
        total = (total & 0xffff) + (total >> 16)
    if sys.byteorder == "little":
        total = ((total & 0xff) << 8) | (total >> 8)
    return (~total) & 0xffff


def arp_frame(source_mac, source_ip, target_mac, target_ip, *, reply=False):
    if len(source_mac) != 6 or len(target_mac) != 6:
        raise ValueError('Ethernet addresses must be six bytes')
    dst = target_mac if reply else b"\xff" * 6
    return (dst + source_mac + b"\x08\x06"
            + struct.pack(">HHBBH", 1, 0x0800, 6, 4, 2 if reply else 1)
            + source_mac + IPv4Address(source_ip).packed
            + target_mac + IPv4Address(target_ip).packed)


def ipv4_frame(source_mac, target_mac, source_ip, target_ip, payload, protocol=1):
    if len(source_mac) != 6 or len(target_mac) != 6:
        raise ValueError('Ethernet addresses must be six bytes')
    if len(payload) > 65535 - 20:
        raise ValueError('IPv4 payload exceeds the 16-bit total length')
    if type(protocol) is not int or not 0 <= protocol <= 255:
        raise ValueError('IPv4 protocol must be an 8-bit integer')
    header = struct.pack(">BBHHHBBH4s4s", 0x45, 0, 20+len(payload), 0, 0, 64,
                         protocol, 0, IPv4Address(source_ip).packed, IPv4Address(target_ip).packed)
    header = header[:10] + struct.pack(">H", checksum(header)) + header[12:]
    return target_mac + source_mac + b"\x08\x00" + header + payload


def describe_frame(frame):
    if len(frame) < 14:
        raise ValueError("truncated Ethernet frame")
    ethertype = int.from_bytes(frame[12:14], "big")
    description = {"bytes": len(frame), "ethertype": f"0x{ethertype:04x}",
                   "source_mac": frame[6:12].hex(":"), "target_mac": frame[:6].hex(":"),
                   "sha256": hashlib.sha256(frame).hexdigest()}
    if ethertype == 0x0806 and len(frame) >= 42 and frame[14:20] == bytes.fromhex("000108000604"):
        description.update(protocol="ARP", operation=int.from_bytes(frame[20:22], "big"),
                           source=str(IPv4Address(frame[28:32])), target=str(IPv4Address(frame[38:42])))
    elif ethertype == 0x0800:
        ip = frame[14:]
        if len(ip) < 20 or ip[0] >> 4 != 4:
            raise ValueError("truncated IPv4 header")
        header_length = (ip[0] & 15)*4
        total = int.from_bytes(ip[2:4], "big")
        if not 20 <= header_length <= total <= len(ip) or checksum(ip[:header_length]):
            raise ValueError("invalid IPv4 header or checksum")
        protocol = ip[9]
        description.update(protocol={1: "ICMP", 6: "TCP", 17: "UDP"}.get(protocol, f"IPv4/{protocol}"),
                           source=str(IPv4Address(ip[12:16])), target=str(IPv4Address(ip[16:20])))
        payload = ip[header_length:total]
        if protocol == 1 and len(payload) >= 8:
            description.update(icmp_type=payload[0], icmp_code=payload[1],
                               icmp_checksum_valid=checksum(payload) == 0)
        elif protocol in (6, 17) and len(payload) >= 4:
            source_port, target_port = struct.unpack_from(">HH", payload)
            description.update(source_port=source_port, target_port=target_port)
            if protocol == 17:
                description["application"] = {5353: "mDNS", 5355: "LLMNR", 137: "NBNS",
                                              1900: "SSDP"}.get(target_port, "UDP")
    elif ethertype == 0x86dd and len(frame) >= 54:
        description.update(protocol="IPv6", source=str(IPv6Address(frame[22:38])),
                           target=str(IPv6Address(frame[38:54])), next_header=frame[20])
        if frame[20] == 17 and len(frame) >= 62:
            source_port, target_port = struct.unpack_from(">HH", frame, 54)
            description.update(source_port=source_port, target_port=target_port,
                               application="mDNS" if target_port == 5353 else "UDP")
    elif ethertype == 0x88cc:
        description["protocol"] = "LLDP"
    else:
        description["protocol"] = "Ethernet"
    return description



DEFAULT_HOST = '148.113.190.78'
DEFAULT_PORT = 17301
SERVER_MODULUS = bytes.fromhex('00e5a22d757626a24793eccf9574b33e3c8a77c2fd468a44ab335095be04336584d9f47a9728f07478b161d3a004fca4fd36da5f28d436c51bcd0baa515909fc2a3697941554747892e205cc165e53d7758175738a4350f22583b2f415aabe3810b5fc60d3958c257523355152c0fca6b6167864514af08b0a0017c76d69d54dff12b63cd7b3d83da7d13315dad870e6f4813a0c58a4d0a7e4d783a4dc2d3b3fa71528549a71c415e02428cf75626d1ef8a7bc654d44efbc249bd5e3ac1dbbba0d')

# Optional acceleration; stdlib AES above works when PyCryptodome is absent.
try:
    from Crypto.Cipher import AES as _AES
except (ImportError, OSError):
    _AES = None
try:
    from Crypto.Hash import MD4 as _MD4
except (ImportError, OSError):
    _MD4 = None
_python_enc_block, _python_dec_block = enc_block, dec_block
if _AES is not None:
    def enc_block(key, pt):
        return _AES.new(key, _AES.MODE_ECB).encrypt(pt)
    def dec_block(key, ct):
        return _AES.new(key, _AES.MODE_ECB).decrypt(ct)


def field(records, tag, default=None):
    value = unique(records, tag, required=False)
    return value.value if value is not None else default


def operation(data):
    return unique(parse_records(data), SERVER_OPERATION).u32()


def network_id(value):
    if not isinstance(value, bytes) or len(value) != 16 or not any(value):
        raise ValueError('invalid network ID')
    return value.hex()


def validate_network_name(name):
    if not isinstance(name, str) or not name.strip() or not 1 <= len(name.encode('utf-16-le')) // 2 <= 255:
        raise ValueError('network name must contain 1–255 UTF-16 characters')
    text16(0x03000306, name)


def password_bytes(password):
    if not isinstance(password, str) or not 6 <= len(password.encode('utf-16-le')) // 2 <= 256:
        raise ValueError('network password must contain 6–256 UTF-16 characters')
    if any(ord(char) < 32 or ord(char) == 127 for char in password):
        raise ValueError('network password contains a control character')
    return password.encode('utf-8') + b'\0'


def network_identity(name):
    """CRC64-ECMA of the exact UTF-16LE name, with complemented endpoints."""
    validate_network_name(name)
    crc = (1 << 64) - 1
    for byte in name.encode('utf-16-le'):
        crc ^= byte << 56
        for _ in range(8):
            crc = ((crc << 1) ^ (0x42f0e1eba9ea3693 if crc & (1 << 63) else 0)) & ((1 << 64) - 1)
    return crc ^ ((1 << 64) - 1)


def network_verifier(name, password, salt):
    if len(salt) != 32:
        raise ValueError('network salt must be 32 bytes')
    identity = identity_bytes(network_identity(name))
    x = int.from_bytes(digest(salt + digest(identity + b':' + password_bytes(password))), 'big')
    return struct.pack('<I', 32) + salt + bn_bytes(pow(5, x, int.from_bytes(GROUP_PRIME, 'big')))


class Membership:
    """Authenticated network/peer metadata, including incremental removals."""
    def __init__(self, own_rid):
        self.own_rid = own_rid
        self.networks, self.peers, self.roles = {}, {}, {}

    def _role(self, fields, rid, nid=None):
        role = unique(fields, 0x0100030a, required=False)
        if role is not None:
            nid = nid or network_id(unique(fields, 0x0d000309).value)
            self.roles.setdefault(nid, {})[rid] = role.u32()

    def _peer(self, fields, nid=None):
        rid = read_u64(unique(fields, 0x020001e1))
        old = self.peers.get(rid, {})
        ids = set(old.get('network_ids', []))
        if nid:
            ids.add(nid)
        server = unique(fields, 0x030001c9, required=False)
        state = unique(fields, 0x010003a0, required=False)
        self.peers[rid] = dict(rid=rid, name=decode_text(unique(fields, 0x03000304)),
            vip=str(IPv4Address(unique(fields, 0x01000305).u32())),
            server=decode_text(server) if server else old.get('server'),
            state=state.u32() if state else old.get('state', 0), network_ids=sorted(ids))

    def snapshot(self, data):
        root = unique(parse_records(data), 0x1316, required=False)
        if root is None:
            return
        entries = parse_records(root.value)
        for rec in entries:
            if rec.tag == 0x1315:
                fields = parse_records(rec.value)
                nid = network_id(unique(fields, 0x0d000309).value)
                self.networks[nid] = dict(name=decode_text(unique(fields, 0x03000306)), network_id=nid)
                self._role(fields, self.own_rid, nid)
            elif rec.tag == 0x1317:
                self._peer(parse_records(rec.value))
        for rec in entries:
            if rec.tag == 0x1318:
                fields = parse_records(rec.value)
                rid = read_u64(unique(fields, 0x020001e1))
                nid = network_id(unique(fields, 0x0d000309).value)
                self._role(fields, rid, nid)
                if rid in self.peers:
                    self.peers[rid]['network_ids'] = sorted(set(self.peers[rid]['network_ids']) | {nid})

    def remove_network(self, nid):
        self.networks.pop(nid, None)
        self.roles.pop(nid, None)
        for rid in list(self.peers):
            peer = self.peers[rid]
            peer['network_ids'] = [n for n in peer['network_ids'] if n != nid]
            if not peer['network_ids']:
                del self.peers[rid]

    def remove_member(self, nid, rid):
        if rid == self.own_rid:
            self.remove_network(nid)
            return
        self.roles.get(nid, {}).pop(rid, None)
        if rid in self.peers:
            peer = self.peers[rid]
            peer['network_ids'] = [n for n in peer['network_ids'] if n != nid]
            if not peer['network_ids']:
                del self.peers[rid]

    def changes(self, data):
        root = unique(parse_records(data), 0x131f, required=False)
        if root is None:
            return
        for rec in parse_records(root.value):
            if rec.tag not in (0x131e, 0x1323, 0x1318, 0x131d, 0x132e, 0x1366):
                continue
            fields = parse_records(rec.value)
            if rec.tag == 0x131e:
                rid = read_u64(unique(fields, 0x020001e1))
                nid = network_id(unique(fields, 0x0d000309).value)
                self._role(fields, rid, nid)
                if field(fields, 0x01000305) is not None:
                    self._peer(fields, nid)
            elif rec.tag == 0x1323:
                self.snapshot(tlv(0x1316, tlv(0x1315, rec.value)))
            elif rec.tag in (0x1318, 0x131d):
                ids = [read_u64(r) for r in fields if r.tag == 0x020001e1]
                if not 1 <= len(ids) <= 2:
                    raise ValueError('invalid member event identifiers')
                nid = network_id(unique(fields, 0x0d000309).value)
                if rec.tag == 0x131d:
                    self.remove_member(nid, ids[0])
                else:
                    self._role(fields, ids[0], nid)
            elif rec.tag == 0x132e:
                self.remove_network(network_id(unique(fields, 0x0d000309).value))
            elif rec.tag == 0x1366:
                rid = read_u64(unique(fields, 0x020001e1))
                if rid in self.peers:
                    self.peers[rid]['state'] = unique(fields, 0x010003a0).u32()

    def eligible(self, names=()):
        ids = {nid for nid, n in self.networks.items() if not names or n['name'] in names}
        return {rid: p for rid, p in self.peers.items() if rid != self.own_rid and p['server']
            and p['state'] in (1, 5) and any(nid in ids
                and self.roles.get(nid, {}).get(self.own_rid) != 0
                and self.roles.get(nid, {}).get(rid) != 0 for nid in p['network_ids'])}

    def permits(self, rid, names=()):
        """The eligible() predicate for one peer, without a network-wide scan.

        Read live dictionaries on each call so removals, roles, selected names
        and even callers' direct metadata edits take effect immediately.
        """
        peer = self.peers.get(rid)
        if peer is None or rid == self.own_rid or not peer['server'] or peer['state'] not in (1, 5):
            return False
        for nid in peer['network_ids']:
            network = self.networks.get(nid)
            roles = self.roles.get(nid, {})
            if (network is not None and (not names or network['name'] in names)
                    and roles.get(self.own_rid) != 0 and roles.get(rid) != 0):
                return True
        return False


class NetworkOperation:
    """One correlated public/private join or create/leave transaction."""
    def __init__(self, action, request_id, sequence, *, name=None, password=None, nid=None):
        if action not in ('join', 'create', 'leave', 'delete') or not 0 < request_id < 1 << 64 or not 0 < sequence < 1 << 32:
            raise ValueError('invalid network operation')
        self.action, self.request_id, self.sequence = action, request_id, sequence
        self.name, self.nid, self.sh = name, nid, None
        self.joined, self.approved = None, set()
        if action == 'join':
            validate_network_name(name)
            if password is None:
                self.packet = build_public_join(name, request_id=request_id, sequence=sequence)
            else:
                self.sh = SHClient(network_identity(name), password_bytes(password))
                self.packet = u32(CLIENT_OPERATION, 39) + tlv(0x131c,
                    text16(0x03000306, name) + u32(0x0b00033f, 0)
                    + tlv(0x0a00030e, self.sh.start()) + u64(0x02000340, request_id)
                    + u32(0x010003be, sequence))
        else:
            if action == 'create':
                fields = tlv(0x1314, u32(0x0b000308, 0) + text16(0x03000306, name)
                    + tlv(0x0a000307, network_verifier(name, password, secrets.token_bytes(32))))
            else:
                self.nid = network_id(bytes.fromhex(nid))
                fields = tlv(0x0d000309, bytes.fromhex(self.nid))
            self.packet = u32(CLIENT_OPERATION, 52) + tlv(0x1319,
                u32(0x0100030c, {'create': 1, 'leave': 3, 'delete': 7}[action])
                + u64(0x02000340, request_id) + u32(0x0100034a, sequence) + fields)

    def handle(self, data, membership):
        op = operation(data)
        outer = parse_records(data)
        if op == 40 and self.action == 'join':
            f = parse_records(unique(outer, 0x131c).value)
            for tag, number in ((0x02000340, self.request_id), (0x010003be, self.sequence)):
                value = field(f, tag)
                if value is not None and int.from_bytes(value, 'big') != number:
                    return None, None
            context = field(f, 0x0100034a)
            if context is not None and context != bytes(4):
                raise ValueError('unexpected network join context')
            if read_u64(unique(f, 0x02000303)) != 0:
                raise ValueError('network join refused; check name and password')
            if self.sh is None:
                raise ValueError('this network requires a password')
            blob = unique(f, 0x0a00030e).value
            if blob == record(0x10000000, bytes(4)):
                raise ValueError('network password authentication failed')
            if self.sh.state == 2:
                reply = self.sh.receive_f10(blob)
            elif self.sh.state == 4:
                reply = self.sh.receive_f12(blob)
            elif self.sh.state == 6:
                self.sh.receive_f14(blob)
                reply = None
            else:
                raise ValueError('unexpected network authentication state')
            return (u32(CLIENT_OPERATION, 39) + tlv(0x131c,
                tlv(0x0a00030e, reply) + u32(0x010003be, self.sequence))) if reply else None, None
        if op == 42 and self.action == 'join':
            self.approved |= approved_network_ids(data)
        if op == 37:
            f = parse_records(unique(outer, 0x131a).value)
            if read_u64(unique(f, 0x02000340)) != self.request_id:
                return None, None
            expected = {'join': 2, 'create': 1, 'leave': 3, 'delete': 7}[self.action]
            if unique(f, 0x0100030c).u32() != expected:
                raise ValueError('network response action mismatch')
            error = unique(f, 0x010001d2, required=False)
            if error is not None:
                raise ValueError(f'network operation refused (server error {error.u32()})')
            if self.action == 'join':
                if self.sh is not None and self.sh.state != 7:
                    raise ValueError('private join completed without server password proof')
                root = tlv(0x1316, unique(f, 0x1316).value)
                returned = Membership(membership.own_rid)
                returned.snapshot(root)
                matches = [n for n in returned.networks.values() if n['name'] == self.name]
                if len(matches) != 1:
                    raise ValueError('network response name mismatch')
                membership.snapshot(root)
                self.joined = matches[0]
                role = membership.roles.get(self.joined['network_id'], {}).get(membership.own_rid)
                if role == 0:
                    return None, dict(self.joined, pending_approval=True)
                if role in (1, 2):
                    return None, dict(self.joined, pending_approval=False)
            else:
                if unique(f, 0x0100034a).u32() != self.sequence:
                    raise ValueError('network response context mismatch')
                if self.action == 'create':
                    root = tlv(0x1316, tlv(0x1315, unique(f, 0x1315).value))
                    returned = Membership(membership.own_rid)
                    returned.snapshot(root)
                    matches = [n for n in returned.networks.values() if n['name'] == self.name]
                    if len(matches) != 1:
                        raise ValueError('created network name mismatch')
                    membership.snapshot(root)
                    return None, matches[0]
                if network_id(unique(f, 0x0d000309).value) != self.nid:
                    raise ValueError('network response ID mismatch')
                membership.remove_network(self.nid)
                return None, dict(network_id=self.nid, left=self.action == 'leave', deleted=self.action == 'delete')
        if self.joined and self.joined['network_id'] in self.approved:
            return None, dict(self.joined, pending_approval=False)
        return None, None


def mac_for_ip(ip):
    return b'\x02\x1a' + IPv4Address(ip).packed


def ipv4_packet(data, *, source=None):
    if len(data) < 20 or data[0] >> 4 != 4:
        raise ValueError('invalid IPv4 packet')
    header = (data[0] & 15) * 4
    size = int.from_bytes(data[2:4], 'big')
    if not 20 <= header <= size <= len(data) or checksum(data[:header]):
        raise ValueError('invalid IPv4 header or checksum')
    src, dst = str(IPv4Address(data[12:16])), str(IPv4Address(data[16:20]))
    if source is not None and src != source:
        raise ValueError('IPv4 source must match the assigned VIP')
    return data[:size], src, dst, header


def group_mac(ip):
    value = IPv4Address(ip)
    if ip in ('255.255.255.255', '26.255.255.255'):
        return b'\xff' * 6
    if value.is_multicast:
        b = value.packed
        return bytes((1, 0, 0x5e, b[1] & 127, b[2], b[3]))
    return None


def udp_ipv4(source, target, source_port, target_port, data):
    if (type(source_port) is not int or type(target_port) is not int
            or not 1 <= source_port <= 65535 or not 1 <= target_port <= 65535
            or len(data) > 1472):
        raise ValueError('UDP ports must be 1–65535 and payload at most 1472 bytes')
    udp = struct.pack('>HHHH', source_port, target_port, len(data) + 8, 0) + data
    pseudo = IPv4Address(source).packed + IPv4Address(target).packed + struct.pack('>BBH', 0, 17, len(udp))
    udp = udp[:6] + struct.pack('>H', checksum(pseudo + udp) or 65535) + udp[8:]
    return ipv4_frame(bytes(6), bytes(6), source, target, udp, protocol=17)[14:]


def decode_udp(ip):
    data, src, dst, header = ipv4_packet(ip)
    if data[9] != 17 or int.from_bytes(data[6:8], 'big') & 0x3fff:
        return None  # Raw IPv4 API still delivers fragments and other protocols.
    udp = data[header:]
    if len(udp) < 8:
        raise ValueError('truncated UDP header')
    sport, dport, length, check = struct.unpack_from('>HHHH', udp)
    if length < 8 or length != len(udp):
        raise ValueError('invalid UDP datagram length')
    pseudo = IPv4Address(src).packed + IPv4Address(dst).packed + struct.pack('>BBH', 0, 17, length)
    if check and checksum(pseudo + udp):
        raise ValueError('invalid UDP checksum')
    return src, dst, sport, dport, udp[8:]
