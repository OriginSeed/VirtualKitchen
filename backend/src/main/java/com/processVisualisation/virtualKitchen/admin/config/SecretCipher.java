package com.processVisualisation.virtualKitchen.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts admin-entered secrets (API keys, the SMTP password) before they are stored in Mongo: AES-256-GCM with a
 * key derived from {@code VK_CONFIG_ENC_KEY}. Without that variable secrets can't be stored, so secret editing is off.
 */
@Component
public class SecretCipher {

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public SecretCipher(@Value("${VK_CONFIG_ENC_KEY:}") String passphrase) {
        this.key = passphrase == null || passphrase.isBlank() ? null : new SecretKeySpec(sha256(passphrase), "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length)
                    .put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a secret", e);
        }
    }

    /** @throws IllegalArgumentException if the value wasn't encrypted with this key (e.g. the key changed) */
    public String decrypt(String stored) {
        requireConfigured();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Stored secret is not encrypted");
        }
        try {
            byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
            return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Stored secret can't be decrypted with the current VK_CONFIG_ENC_KEY", e);
        }
    }

    /** "••••1234": enough to tell keys apart, never enough to use one. Empty when unset. */
    public static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        return "••••" + (secret.length() > 8 ? secret.substring(secret.length() - 4) : "");
    }

    private void requireConfigured() {
        if (key == null) {
            throw new IllegalStateException("VK_CONFIG_ENC_KEY is not set");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
