package io.github.gringoestrangeiro.openradplugin.crypto;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateful authenticated CBC channel. Each direction chains independently across records and
 * rekeys.
 */
public final class Channel implements AutoCloseable {
    private byte[] key;
    private byte[] encIv = new byte[16];
    private byte[] decIv = new byte[16];
    private static final int MAX = 4 * 1024 * 1024;

    public Channel(byte[] key) {
        rekey(key);
    }

    public synchronized void rekey(byte[] newKey) {
        Crypto.require(newKey.length == 32, "channel key must be 32 bytes");
        if (key != null) Crypto.clear(key);
        key = newKey.clone();
    }

    public synchronized byte[] encrypt(byte[] plain) {
        Crypto.require(plain.length > 0 && plain.length <= MAX, "invalid plaintext length");
        int total = (plain.length + 24) / 16 * 16;
        byte[] data = Arrays.copyOf(plain, total);
        Arrays.fill(data, plain.length, total - 9, (byte) 0xcc);
        byte[] tag = Crypto.auth8(Arrays.copyOf(data, total - 9));
        System.arraycopy(tag, 0, data, total - 9, 8);
        data[total - 1] = (byte) (total - plain.length);
        try {
            byte[] out = crypt(Cipher.ENCRYPT_MODE, data, encIv);
            encIv = Arrays.copyOfRange(out, out.length - 16, out.length);
            return out;
        } finally {
            Crypto.clear(data);
        }
    }

    public synchronized byte[] decrypt(byte[] ciphertext) {
        Crypto.require(
                ciphertext.length > 0 && ciphertext.length % 16 == 0 && ciphertext.length <= MAX,
                "invalid ciphertext length");
        byte[] data = crypt(Cipher.DECRYPT_MODE, ciphertext, decIv);
        // Rust consumes the receive chain even when the authentication trailer fails.
        decIv = Arrays.copyOfRange(ciphertext, ciphertext.length - 16, ciphertext.length);
        try {
            int pad = data[data.length - 1] & 255;
            Crypto.require(pad >= 9 && pad < 25 && pad < data.length, "invalid channel padding");
            Crypto.require(
                    MessageDigest.isEqual(
                            Crypto.auth8(Arrays.copyOf(data, data.length - 9)),
                            Arrays.copyOfRange(data, data.length - 9, data.length - 1)),
                    "channel trailer rejected");
            return Arrays.copyOf(data, data.length - pad);
        } finally {
            Crypto.clear(data);
        }
    }

    private byte[] crypt(int mode, byte[] data, byte[] iv) {
        if (key == null) throw new IllegalStateException("channel closed");
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK AES unavailable", e);
        }
    }

    @Override
    public synchronized void close() {
        if (key != null) Crypto.clear(key);
        key = null;
        Crypto.clear(encIv);
        Crypto.clear(decIv);
    }
}
