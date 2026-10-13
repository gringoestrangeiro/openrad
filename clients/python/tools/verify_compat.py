#!/usr/bin/env python3
"""Compare changed crypto/framing helpers with a saved original radproto.py."""
import argparse
import importlib.util
from pathlib import Path
import random
import sys
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import radproto as current


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('original', type=Path, help='original radproto.py')
    args = cli.parse_args()
    spec = importlib.util.spec_from_file_location('original_radproto', args.original)
    original = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = original
    spec.loader.exec_module(original)
    rng = random.Random(0x524144)
    lengths = list(range(1, 81)) + [127, 128, 129, 499, 500, 501, 1499, 1500, 1501, 65520]
    key = rng.randbytes(32)
    old_enc, old_dec = original.PureCC(key), original.PureCC(key)
    new_enc, new_dec = current.PureCC(key), current.PureCC(key)
    for index, length in enumerate(lengths):
        if index % 17 == 0:
            key = rng.randbytes(32)
            old_enc.set_key_enc(key)
            new_enc.set_key_enc(key)
            old_dec.set_key_dec(key)
            new_dec.set_key_dec(key)
        plain = rng.randbytes(length)
        expected, actual = old_enc.enc(plain), new_enc.enc(plain)
        assert expected == actual, ('ciphertext', length)
        assert old_enc.iv_e == new_enc.iv_e
        assert old_dec.dec(actual) == new_dec.dec(expected) == plain
        assert old_dec.iv_d == new_dec.iv_d
        assert original.auth8(plain) == current.auth8(plain)
        assert original.sum64_le(plain) == current.sum64_le(plain)
    # Failed authentication consumes the last CBC block in both versions.
    for length in (0, 1, 16, 32, 48, 64):
        damaged = rng.randbytes(length)
        before = bytes(old_dec.iv_d)
        for decoder in (old_dec, new_dec):
            try:
                decoder.dec(damaged)
            except ValueError:
                pass
            else:
                raise AssertionError('invalid ciphertext accepted')
        assert old_dec.iv_d == new_dec.iv_d
        assert new_dec.iv_d == (damaged[-16:] if damaged and length % 16 == 0 else before)
    # Native CBC and the actual stdlib fallback agree across padding/rekeys.
    old_enc, new_enc = original.PureCC(key), current.PureCC(key)
    native_dec, pure_dec = current.PureCC(key), current.PureCC(key)
    for length in list(range(1, 49)) + [500, 1500]:
        plain = rng.randbytes(length)
        expected = old_enc.enc(plain)
        with patch.object(current, '_AES', None), patch.object(current, '_MD4', None, create=True), patch.object(current, 'enc_block', current._python_enc_block), patch.object(current, 'dec_block', current._python_dec_block):
            actual = new_enc.enc(plain)
            decoded = pure_dec.dec(expected)
        assert expected == actual and decoded == plain
        assert native_dec.dec(actual) == plain
    print(f'PASS: {len(lengths)} chained native packets, 50 stdlib packets, rekeys, malformed ciphertext/IV state, auth8/sum64')


if __name__ == '__main__':
    main()
