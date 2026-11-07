package com.processVisualisation.virtualKitchen.admin.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretCipherTest {

    @Test
    void aSecretRoundTripsAndIsNeverStoredInClear() {
        SecretCipher cipher = new SecretCipher("test-passphrase");

        String stored = cipher.encrypt("sentinel-key-12345678");

        assertFalse(stored.contains("sentinel"));
        assertNotEquals(stored, cipher.encrypt("sentinel-key-12345678"), "a fresh IV per encryption");
        assertEquals("sentinel-key-12345678", cipher.decrypt(stored));
    }

    @Test
    void anotherKeyCantDecrypt() {
        String stored = new SecretCipher("one").encrypt("secret-value");

        assertThrows(IllegalArgumentException.class, () -> new SecretCipher("two").decrypt(stored));
    }

    @Test
    void withoutAPassphraseNothingCanBeStored() {
        SecretCipher cipher = new SecretCipher("");

        assertFalse(cipher.isConfigured());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("x"));
    }

    @Test
    void masksShowOnlyTheLastFourOfLongSecrets() {
        assertEquals("••••5678", SecretCipher.mask("abcdefgh12345678"));
        assertEquals("••••", SecretCipher.mask("short"));
        assertEquals("", SecretCipher.mask(null));
    }
}
