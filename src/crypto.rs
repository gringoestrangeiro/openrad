//! Wire-compatible RSA, SH authentication and encrypted channels.
use aes::{
    cipher::{generic_array::GenericArray, BlockDecrypt, BlockEncrypt, KeyInit},
    Aes256,
};
use anyhow::{bail, ensure, Result};
use md4::{Digest, Md4};
use num_bigint::BigUint;
use num_traits::Zero;
use rand::{rngs::OsRng, RngCore};
use sha1::Sha1;
use subtle::ConstantTimeEq;
use zeroize::Zeroize;

pub const PRIME: &str = concat!(
    "e634471e75f2d76a0a3f5e252c5a6efb48edad645d31d9ecbc400416e14f5c6c",
    "2ed378602d6eb8e9478b99e6474932393538b4be7177e0e05d3aaed17b5ae87a",
    "a20f3dad37af019518f45b6dde76b2bfdfb140f379cc15383b304b7975b90796",
    "7553cd6fdaf08d68832b603120430a501067d21beb2ce2275a20036a2dc80f1c",
    "9d3ab9221afd5baceda5c2a31077c54fc6f274aaeba17ddd0b5c68f4f8e751a8",
    "90a58648147a82e6f4f532ccdc939a12942799a713500e17dd4f6ff216cd54c7"
);

pub fn random(n: usize) -> Vec<u8> {
    let mut v = vec![0; n];
    OsRng.fill_bytes(&mut v);
    v
}
pub fn hash(v: &[u8]) -> Vec<u8> {
    Sha1::digest(v).to_vec()
}
fn number(v: &[u8]) -> BigUint {
    BigUint::from_bytes_be(v)
}
fn serial(v: &BigUint) -> Vec<u8> {
    if v.is_zero() {
        vec![]
    } else {
        v.to_bytes_be()
    }
}
fn padded(v: &[u8], width: usize) -> Vec<u8> {
    let mut b = vec![0; width - v.len()];
    b.extend(v);
    b
}

pub fn auth8(region: &[u8]) -> [u8; 8] {
    let sum = region.chunks(8).fold(0u64, |a, b| {
        let mut word = [0; 8];
        word[..b.len()].copy_from_slice(b);
        a.wrapping_add(u64::from_le_bytes(word))
    });
    let digest = Md4::digest(sum.to_le_bytes());
    std::array::from_fn(|i| digest[i].wrapping_add(digest[i + 8]))
}

pub struct Channel {
    cipher: Aes256,
    enc_iv: [u8; 16],
    dec_iv: [u8; 16],
}
impl Channel {
    pub fn new(key: &[u8]) -> Result<Self> {
        ensure!(key.len() == 32, "channel key must be 32 bytes");
        Ok(Self {
            cipher: Aes256::new(GenericArray::from_slice(key)),
            enc_iv: [0; 16],
            dec_iv: [0; 16],
        })
    }
    pub fn rekey(&mut self, key: &[u8]) -> Result<()> {
        ensure!(key.len() == 32, "channel key must be 32 bytes");
        self.cipher = Aes256::new(GenericArray::from_slice(key));
        Ok(())
    }
    pub fn encrypt(&mut self, plain: &[u8]) -> Result<Vec<u8>> {
        ensure!(
            !plain.is_empty() && plain.len() <= 4 * 1024 * 1024,
            "invalid plaintext length"
        );
        let total = (plain.len() + 9).div_ceil(16) * 16;
        let mut out = Vec::with_capacity(total);
        out.extend_from_slice(plain);
        out.resize(total - 9, 0xcc);
        out.extend(auth8(&out));
        out.push((total - plain.len()) as u8);
        for block in out.as_chunks_mut::<16>().0 {
            for (b, v) in block.iter_mut().zip(self.enc_iv) {
                *b ^= v;
            }
            self.cipher
                .encrypt_block(GenericArray::from_mut_slice(block));
            self.enc_iv.copy_from_slice(block);
        }
        Ok(out)
    }
    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>> {
        ensure!(
            !ciphertext.is_empty()
                && ciphertext.len().is_multiple_of(16)
                && ciphertext.len() <= 4 * 1024 * 1024,
            "invalid ciphertext length"
        );
        let mut out = ciphertext.to_vec();
        for block in out.as_chunks_mut::<16>().0 {
            let next = *block;
            self.cipher
                .decrypt_block(GenericArray::from_mut_slice(block));
            for (b, v) in block.iter_mut().zip(self.dec_iv) {
                *b ^= v;
            }
            self.dec_iv = next;
        }
        let n = out.len();
        let pad = out[n - 1] as usize;
        ensure!((9..25).contains(&pad) && pad < n, "invalid channel padding");
        ensure!(
            bool::from(auth8(&out[..n - 9]).ct_eq(&out[n - 9..n - 1])),
            "channel trailer rejected"
        );
        out.truncate(n - pad);
        Ok(out)
    }
}

pub fn sh_record(kind: u32, data: &[u8]) -> Vec<u8> {
    let mut out = (kind | data.len() as u32).to_be_bytes().to_vec();
    out.extend(data);
    out
}
pub fn sh_message(index: u32, kind: u32, data: &[u8]) -> Vec<u8> {
    [
        sh_record(0x10000000, &index.to_be_bytes()),
        sh_record(kind, data),
    ]
    .concat()
}
pub fn sh_parse<'a>(data: &'a [u8], index: u32, kinds: &[u32]) -> Result<Vec<&'a [u8]>> {
    let mut offset = 0;
    let mut result = vec![];
    for kind in std::iter::once(&0x10000000).chain(kinds.iter()) {
        ensure!(
            data.len().saturating_sub(offset) >= 4,
            "truncated SH header"
        );
        let word = u32::from_be_bytes(data[offset..offset + 4].try_into()?);
        offset += 4;
        let len = (word & 0x07ffffff) as usize;
        ensure!(
            word & 0xf8000000 == *kind && (1..=0x2800).contains(&len),
            "invalid SH record"
        );
        ensure!(len <= data.len() - offset, "truncated SH body");
        result.push(&data[offset..offset + len]);
        offset += len;
    }
    ensure!(
        offset == data.len() && result[0] == index.to_be_bytes(),
        "SH state/trailing data mismatch"
    );
    Ok(result[1..].to_vec())
}
pub fn identity_bytes(rid: u64) -> Vec<u8> {
    rid.to_le_bytes()
        .chunks(2)
        .flat_map(|c| [c[1], c[0]])
        .collect()
}

pub struct ShClient {
    rid: u64,
    password: Vec<u8>,
    state: u8,
    salt: Vec<u8>,
    public: Vec<u8>,
    pub private: BigUint,
    key: Vec<u8>,
    expected: Vec<u8>,
}
impl Drop for ShClient {
    fn drop(&mut self) {
        self.password.zeroize();
        self.key.zeroize();
        self.expected.zeroize();
    }
}
impl ShClient {
    pub fn new(rid: u64, password: &[u8]) -> Result<Self> {
        Self::with_private(rid, password, number(&random(32)) + BigUint::from(1536u32))
    }
    pub fn with_private(rid: u64, password: &[u8], private: BigUint) -> Result<Self> {
        ensure!(
            password.len() > 5 && !private.is_zero(),
            "invalid SH credentials/exponent"
        );
        Ok(Self {
            rid,
            password: password.to_vec(),
            state: 0,
            salt: vec![],
            public: vec![],
            private,
            key: vec![],
            expected: vec![],
        })
    }
    pub fn start(&mut self) -> Result<Vec<u8>> {
        ensure!(self.state == 0, "SH already started");
        self.state = 2;
        Ok(sh_message(1, 0x20000000, &self.rid.to_le_bytes()))
    }
    pub fn parameters(&mut self, data: &[u8]) -> Result<Vec<u8>> {
        ensure!(self.state == 2, "SH unexpected parameters");
        let values = sh_parse(data, 2, &[0x30000000, 0x40000000, 0x50000000])?;
        ensure!(
            values[0] == hex::decode(PRIME)? && values[1] == [5],
            "unsupported SH group"
        );
        self.salt = values[2].to_vec();
        self.public = serial(&BigUint::from(5u32).modpow(&self.private, &number(values[0])));
        self.state = 4;
        Ok(sh_message(3, 0x60000000, &self.public))
    }
    pub fn challenge(&mut self, data: &[u8]) -> Result<Vec<u8>> {
        ensure!(self.state == 4, "SH unexpected challenge");
        let b = sh_parse(data, 4, &[0x60000000])?[0];
        let prime = hex::decode(PRIME)?;
        let n = number(&prime);
        let bv = number(b);
        ensure!(
            b.len() <= prime.len() && !bv.is_zero() && bv < n,
            "invalid SH peer public"
        );
        let identity = identity_bytes(self.rid);
        let x = number(&hash(
            &[
                self.salt.clone(),
                hash(&[identity.clone(), b":".to_vec(), self.password.clone()].concat()),
            ]
            .concat(),
        ));
        let k = number(&hash(&[prime.clone(), padded(&[5], prime.len())].concat()));
        ensure!(!k.is_zero(), "zero SH multiplier");
        let u = number(&hash(
            &[padded(&self.public, prime.len()), padded(b, prime.len())].concat(),
        ));
        let v = BigUint::from(5u32).modpow(&x, &n);
        let base = (bv + &n - ((&k * v) % &n)) % &n;
        let shared = serial(&base.modpow(&(&self.private + u * x), &n));
        self.key = (0u32..2)
            .flat_map(|i| hash(&[shared.clone(), i.to_be_bytes().to_vec()].concat()))
            .collect();
        let prefix: Vec<u8> = hash(&prime)
            .iter()
            .zip(hash(&[5]))
            .map(|(x, y)| x ^ y)
            .collect();
        let m1 = hash(
            &[
                prefix,
                hash(&identity),
                self.salt.clone(),
                self.public.clone(),
                b.to_vec(),
                self.key.clone(),
            ]
            .concat(),
        );
        self.expected = hash(&[self.public.clone(), m1.clone(), self.key.clone()].concat());
        self.state = 6;
        Ok(sh_message(5, 0x70000000, &m1))
    }
    pub fn confirm(&mut self, data: &[u8]) -> Result<Vec<u8>> {
        ensure!(self.state == 6, "SH unexpected confirmation");
        let proof = sh_parse(data, 6, &[0x70000000])?[0];
        ensure!(
            bool::from(proof.ct_eq(&self.expected)),
            "SH server proof rejected"
        );
        self.state = 7;
        Ok(self.key.clone())
    }
}

/// Incoming peer SH. The hello names our identity; the server-issued connection
/// password binds the remote peer. Never release a key before checking M1.
pub struct ShServer {
    rid: u64,
    password: Vec<u8>,
    pub salt: Vec<u8>,
    pub private: BigUint,
    state: u8,
    key: Vec<u8>,
    m1: Vec<u8>,
    m2: Vec<u8>,
}
impl ShServer {
    pub fn new(rid: u64, password: &[u8]) -> Result<Self> {
        Self::with_private(
            rid,
            password,
            random(16),
            number(&random(32)) + BigUint::from(1536u32),
        )
    }
    pub fn with_private(
        rid: u64,
        password: &[u8],
        salt: Vec<u8>,
        private: BigUint,
    ) -> Result<Self> {
        ensure!(
            rid != 0 && password.len() > 5 && salt.len() == 16 && !private.is_zero(),
            "invalid SH server parameters"
        );
        Ok(Self {
            rid,
            password: password.to_vec(),
            salt,
            private,
            state: 1,
            key: vec![],
            m1: vec![],
            m2: vec![],
        })
    }
    pub fn hello(&mut self, data: &[u8]) -> Result<Vec<u8>> {
        ensure!(self.state == 1, "SH unexpected hello");
        ensure!(
            sh_parse(data, 1, &[0x20000000])?[0] == self.rid.to_le_bytes(),
            "SH identity mismatch"
        );
        self.state = 3;
        Ok([
            sh_message(2, 0x30000000, &hex::decode(PRIME)?),
            sh_record(0x40000000, &[5]),
            sh_record(0x50000000, &self.salt),
        ]
        .concat())
    }
    pub fn public(&mut self, data: &[u8]) -> Result<Vec<u8>> {
        ensure!(self.state == 3, "SH unexpected client public");
        let a = sh_parse(data, 3, &[0x60000000])?[0];
        let prime = hex::decode(PRIME)?;
        let n = number(&prime);
        let av = number(a);
        ensure!(
            a.len() <= prime.len() && !av.is_zero() && av < n,
            "invalid SH client public"
        );
        let identity = identity_bytes(self.rid);
        let x = number(&hash(
            &[
                self.salt.clone(),
                hash(&[identity.clone(), b":".to_vec(), self.password.clone()].concat()),
            ]
            .concat(),
        ));
        let g = BigUint::from(5u32);
        let v = g.modpow(&x, &n);
        let k = number(&hash(&[prime.clone(), padded(&[5], prime.len())].concat()));
        let b = serial(&((k * &v + g.modpow(&self.private, &n)) % &n));
        let u = number(&hash(
            &[padded(a, prime.len()), padded(&b, prime.len())].concat(),
        ));
        ensure!(!u.is_zero(), "zero SH scrambling parameter");
        let shared = serial(&((av * v.modpow(&u, &n)) % &n).modpow(&self.private, &n));
        self.key = (0u32..2)
            .flat_map(|i| hash(&[shared.clone(), i.to_be_bytes().to_vec()].concat()))
            .collect();
        let prefix: Vec<u8> = hash(&prime)
            .iter()
            .zip(hash(&[5]))
            .map(|(x, y)| x ^ y)
            .collect();
        self.m1 = hash(
            &[
                prefix,
                hash(&identity),
                self.salt.clone(),
                a.to_vec(),
                b.clone(),
                self.key.clone(),
            ]
            .concat(),
        );
        self.m2 = hash(&[a.to_vec(), self.m1.clone(), self.key.clone()].concat());
        self.state = 5;
        Ok(sh_message(4, 0x60000000, &b))
    }
    pub fn proof(&mut self, data: &[u8]) -> Result<(Vec<u8>, Vec<u8>)> {
        ensure!(self.state == 5, "SH unexpected client proof");
        // A failed proof consumes this handshake too.
        self.state = 7;
        ensure!(
            bool::from(sh_parse(data, 5, &[0x70000000])?[0].ct_eq(&self.m1)),
            "SH client proof rejected"
        );
        Ok((sh_message(6, 0x70000000, &self.m2), self.key.clone()))
    }
}

pub fn rsa_session(modulus: &[u8], purpose: u32) -> Result<(Vec<u8>, Vec<u8>)> {
    let n = number(modulus);
    let e = BigUint::from(65537u32);
    ensure!(
        [3, 4, 5].contains(&purpose) && n > e && n.bit(0),
        "invalid RSA parameters"
    );
    let width = n.bits().div_ceil(8) as usize;
    ensure!((87..=1024).contains(&width), "unsupported RSA width");
    let secret = random(64);
    let mut message = vec![];
    for v in [3u32, 0x4b, purpose] {
        message.extend(v.to_le_bytes());
    }
    message.extend(&secret);
    let padding_len = width - message.len() - 3;
    ensure!(padding_len >= 8, "RSA message too long");
    let mut encoded = vec![0, 2];
    for _ in 0..128 {
        if encoded.len() == padding_len + 2 {
            break;
        }
        encoded.extend(
            random(padding_len + 2 - encoded.len())
                .into_iter()
                .filter(|x| *x != 0),
        );
    }
    if encoded.len() != padding_len + 2 {
        bail!("RSA randomness failure");
    }
    encoded.push(0);
    encoded.extend(message);
    let value = number(&encoded);
    ensure!(value < n, "RSA encoded message exceeds modulus");
    Ok((padded(&serial(&value.modpow(&e, &n)), width), secret))
}
