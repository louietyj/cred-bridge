package me.louietyj.credbridge;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Port of request-text's {@code seal()} (claude-skills/request-text/web/form.html): ECDH P-256
 * with an ephemeral key, HKDF-SHA256 (salt and info as below), AES-256-GCM with the request id
 * as AAD. Byte-compatible with the page, so request-text's server decrypts it unchanged.
 */
public final class Seal {

    private static final byte[] INFO = "request-text v1".getBytes(StandardCharsets.UTF_8);
    private static final int COORD = 32;

    /**
     * {@code epk}, {@code iv} and {@code ct} as unpadded base64url, the POST body's fields.
     * Not a record: AGP 9.2's D8 references a RecordTag class it then leaves out of the APK.
     */
    public static final class Sealed {
        private final String epk, iv, ct;

        Sealed(String epk, String iv, String ct) {
            this.epk = epk;
            this.iv = iv;
            this.ct = ct;
        }

        public String epk() { return epk; }
        public String iv() { return iv; }
        public String ct() { return ct; }
    }

    private Seal() {}

    public static Sealed seal(String serverKeyB64u, String id, String plaintext)
            throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair eph = gen.generateKeyPair();
        ECParameterSpec params = ((ECPublicKey) eph.getPublic()).getParams();

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(eph.getPrivate());
        ka.doPhase(decodePoint(unb64u(serverKeyB64u), params), true);
        byte[] shared = ka.generateSecret();

        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] key = hkdfSha256(shared, idBytes, INFO, 32);

        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(idBytes);
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        return new Sealed(b64u(encodePoint((ECPublicKey) eph.getPublic())), b64u(iv), b64u(ct));
    }

    // RFC 5869 extract-then-expand.
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        for (int done = 0, i = 1; done < length; i++) {
            mac.update(block);
            mac.update(info);
            mac.update((byte) i);
            block = mac.doFinal();
            int n = Math.min(block.length, length - done);
            System.arraycopy(block, 0, out, done, n);
            done += n;
        }
        return out;
    }

    // WebCrypto's "raw" format: 0x04 || X || Y.
    private static PublicKey decodePoint(byte[] raw, ECParameterSpec params)
            throws GeneralSecurityException {
        if (raw.length != 1 + 2 * COORD || raw[0] != 4) {
            throw new GeneralSecurityException("server key is not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + COORD));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 1 + COORD, raw.length));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
    }

    private static byte[] encodePoint(ECPublicKey key) {
        byte[] out = new byte[1 + 2 * COORD];
        out[0] = 4;
        writeCoord(key.getW().getAffineX(), out, 1);
        writeCoord(key.getW().getAffineY(), out, 1 + COORD);
        return out;
    }

    // BigInteger.toByteArray() is minimal two's complement: strip a sign byte or left-pad.
    private static void writeCoord(BigInteger v, byte[] out, int offset) {
        byte[] b = v.toByteArray();
        int n = Math.min(b.length, COORD);
        System.arraycopy(b, b.length - n, out, offset + COORD - n, n);
    }

    private static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] unb64u(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
