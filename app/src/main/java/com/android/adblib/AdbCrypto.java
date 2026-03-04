package com.android.adblib;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;

public class AdbCrypto {
    public static final int KEY_LENGTH_BITS = 2048;
    public static final int KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8;
    public static final int KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4;

    public static final byte[] SIGNATURE_PADDING;

    private KeyPair keyPair;
    private AdbBase64 base64;

    static {
        SIGNATURE_PADDING = new byte[KEY_LENGTH_BYTES - 20];
        SIGNATURE_PADDING[0] = 0x00;
        SIGNATURE_PADDING[1] = 0x01;
        Arrays.fill(SIGNATURE_PADDING, 2, SIGNATURE_PADDING.length - 15, (byte) 0xFF);
        byte[] digestInfoPrefix = new byte[]{
                0x00, 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
                0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        System.arraycopy(digestInfoPrefix, 0, SIGNATURE_PADDING,
                SIGNATURE_PADDING.length - digestInfoPrefix.length, digestInfoPrefix.length);
    }

    private static byte[] convertRsaPublicKeyToAdbFormat(RSAPublicKey pubkey) {
        BigInteger word = BigInteger.ZERO.setBit(32);
        BigInteger modulus = pubkey.getModulus();

        BigInteger r = BigInteger.ZERO.setBit(KEY_LENGTH_BITS);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), modulus);
        BigInteger rem = modulus.remainder(word);
        BigInteger n0Inv = rem.modInverse(word);

        int[] modulusWords = new int[KEY_LENGTH_WORDS];
        int[] rrWords = new int[KEY_LENGTH_WORDS];

        for (int i = 0; i < KEY_LENGTH_WORDS; i++) {
            BigInteger[] qAndR = rr.divideAndRemainder(word);
            rr = qAndR[0];
            rem = qAndR[1];
            rrWords[i] = rem.intValue();

            qAndR = modulus.divideAndRemainder(word);
            modulus = qAndR[0];
            rem = qAndR[1];
            modulusWords[i] = rem.intValue();
        }

        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(KEY_LENGTH_WORDS);
        buffer.putInt(n0Inv.negate().intValue());
        for (int modulusWord : modulusWords) {
            buffer.putInt(modulusWord);
        }
        for (int rrWord : rrWords) {
            buffer.putInt(rrWord);
        }
        buffer.putInt(pubkey.getPublicExponent().intValue());
        return buffer.array();
    }

    public static AdbCrypto loadAdbKeyPair(AdbBase64 base64, File privateKeyFile, File publicKeyFile)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        AdbCrypto crypto = new AdbCrypto();

        int privLength = (int) privateKeyFile.length();
        int pubLength = (int) publicKeyFile.length();
        byte[] privBytes = new byte[privLength];
        byte[] pubBytes = new byte[pubLength];

        try (FileInputStream privIn = new FileInputStream(privateKeyFile);
             FileInputStream pubIn = new FileInputStream(publicKeyFile)) {
            privIn.read(privBytes);
            pubIn.read(pubBytes);
        }

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec privSpec = new PKCS8EncodedKeySpec(privBytes);
        X509EncodedKeySpec pubSpec = new X509EncodedKeySpec(pubBytes);
        crypto.keyPair = new KeyPair(keyFactory.generatePublic(pubSpec), keyFactory.generatePrivate(privSpec));
        crypto.base64 = base64;
        return crypto;
    }

    public static AdbCrypto loadAdbKeyPair(AdbBase64 base64, KeyPair keyPair) {
        AdbCrypto crypto = new AdbCrypto();
        crypto.keyPair = keyPair;
        crypto.base64 = base64;
        return crypto;
    }

    public static AdbCrypto generateAdbKeyPair(AdbBase64 base64) throws NoSuchAlgorithmException {
        AdbCrypto crypto = new AdbCrypto();
        KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("RSA");
        keyGenerator.initialize(KEY_LENGTH_BITS);
        crypto.keyPair = keyGenerator.genKeyPair();
        crypto.base64 = base64;
        return crypto;
    }

    public byte[] signAdbTokenPayload(byte[] payload) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, keyPair.getPrivate());
        c.update(SIGNATURE_PADDING);
        return c.doFinal(payload);
    }

    public byte[] getAdbPublicKeyPayload() throws IOException {
        byte[] converted = convertRsaPublicKeyToAdbFormat((RSAPublicKey) keyPair.getPublic());
        StringBuilder builder = new StringBuilder(720);
        builder.append(base64.encodeToString(converted));
        builder.append(" unknown@unknown");
        builder.append('\0');
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    public void saveAdbKeyPair(File privateKeyFile, File publicKeyFile) throws IOException {
        try (FileOutputStream privOut = new FileOutputStream(privateKeyFile);
             FileOutputStream pubOut = new FileOutputStream(publicKeyFile)) {
            privOut.write(keyPair.getPrivate().getEncoded());
            pubOut.write(keyPair.getPublic().getEncoded());
        }
    }
}
