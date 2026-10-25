package io.github.gringoestrangeiro.openradplugin.crypto;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stateful authenticated CBC channel. Each direction chains independently across records and
 * rekeys.
 */
public final class Channel implements AutoCloseable {
    /** Consumes a decrypted plaintext prefix in place; it must not retain {@code plain}. */
    @FunctionalInterface
    public interface PlainDecoder<T> {
        T decode(byte[] plain, int length);
    }

    private static final byte[] EMPTY = new byte[0];
    private static final PlainDecoder<byte[]> COPY = Arrays::copyOf;
    private byte[] key;
    private SecretKeySpec keySpec;
    // One AES instance per direction, guarded by this channel's monitor. Each record is still
    // initialized with that direction's chained IV, exactly as a fresh instance would be.
    private Cipher encryptor, decryptor;
    private final byte[] encIv = new byte[16];
    private final byte[] decIv = new byte[16];
    private static final int MAX = 4 * 1024 * 1024;

    public Channel(byte[] key) {
        rekey(key);
    }

    public synchronized void rekey(byte[] newKey) {
        Crypto.require(newKey.length == 32, "channel key must be 32 bytes");
        if (key != null) Crypto.clear(key);
        key = newKey.clone();
        keySpec = new SecretKeySpec(key, "AES");
    }

    public synchronized byte[] encrypt(byte[] plain) {
        return encrypt(EMPTY, plain);
    }

    /** Encrypts the plaintext {@code head || body} as one record, without concatenating first. */
    public synchronized byte[] encrypt(byte[] head, byte[] body) {
        int length = head.length + body.length;
        Crypto.require(
                length > 0 && length <= MAX && body.length <= MAX, "invalid plaintext length");
        int total = (length + 24) / 16 * 16;
        byte[] data = new byte[total];
        System.arraycopy(head, 0, data, 0, head.length);
        System.arraycopy(body, 0, data, head.length, body.length);
        Arrays.fill(data, length, total - 9, (byte) 0xcc);
        Crypto.auth8(data, 0, total - 9, data, total - 9);
        data[total - 1] = (byte) (total - length);
        try {
            if (key != null && encryptor == null) encryptor = cipher();
            byte[] out = crypt(encryptor, Cipher.ENCRYPT_MODE, data, encIv);
            System.arraycopy(out, out.length - 16, encIv, 0, 16);
            return out;
        } finally {
            Crypto.clear(data);
        }
    }

    public synchronized byte[] decrypt(byte[] ciphertext) {
        return decrypt(ciphertext, COPY);
    }

    /**
     * Decrypts and authenticates one record, then hands the plaintext prefix to {@code decoder}
     * before the working buffer is cleared. Saves the plaintext copy for decoders that parse.
     */
    public synchronized <T> T decrypt(byte[] ciphertext, PlainDecoder<T> decoder) {
        Crypto.require(
                ciphertext.length > 0 && ciphertext.length % 16 == 0 && ciphertext.length <= MAX,
                "invalid ciphertext length");
        if (key != null && decryptor == null) decryptor = cipher();
        byte[] data = crypt(decryptor, Cipher.DECRYPT_MODE, ciphertext, decIv);
        // Rust consumes the receive chain even when the authentication trailer fails.
        System.arraycopy(ciphertext, ciphertext.length - 16, decIv, 0, 16);
        try {
            int pad = data[data.length - 1] & 255;
            Crypto.require(pad >= 9 && pad < 25 && pad < data.length, "invalid channel padding");
            Crypto.require(
                    Crypto.auth8Matches(data, 0, data.length - 9, data, data.length - 9),
                    "channel trailer rejected");
            return decoder.decode(data, data.length - pad);
        } finally {
            Crypto.clear(data);
        }
    }

    private static Cipher cipher() {
        try {
            return Cipher.getInstance("AES/CBC/NoPadding");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK AES unavailable", e);
        }
    }

    private byte[] crypt(Cipher cipher, int mode, byte[] data, byte[] iv) {
        if (key == null) throw new IllegalStateException("channel closed");
        try {
            cipher.init(mode, keySpec, new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK AES unavailable", e);
        }
    }

    @Override
    public synchronized void close() {
        if (key != null) Crypto.clear(key);
        key = null;
        keySpec = null;
        encryptor = null;
        decryptor = null;
        Crypto.clear(encIv);
        Crypto.clear(decIv);
    }
}
