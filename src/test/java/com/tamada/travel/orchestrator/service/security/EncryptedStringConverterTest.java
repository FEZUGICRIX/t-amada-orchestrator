package com.tamada.travel.orchestrator.service.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EncryptedStringConverterTest {

    private EncryptedStringConverter converter;

    @BeforeEach
    void setUp() {
        converter = new EncryptedStringConverter();
        converter.setEncryptionProperties(new EncryptionProperties(true, "test-secret-encryption-key-256bit-test!"));
    }

    @Test
    void shouldEncryptAndDecryptPlainTextCorrectly() {
        String sensitiveData = "4510 123456";
        String encrypted = converter.convertToDatabaseColumn(sensitiveData);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("enc:v1:"));
        assertNotEquals(sensitiveData, encrypted);

        String decrypted = converter.convertToEntityAttribute(encrypted);
        assertEquals(sensitiveData, decrypted);
    }

    @Test
    void shouldProduceDifferentCiphertextsForSameInputDueToRandomIv() {
        String sensitiveData = "+79991234567";
        String encrypted1 = converter.convertToDatabaseColumn(sensitiveData);
        String encrypted2 = converter.convertToDatabaseColumn(sensitiveData);

        assertNotNull(encrypted1);
        assertNotNull(encrypted2);
        assertNotEquals(encrypted1, encrypted2);

        assertEquals(sensitiveData, converter.convertToEntityAttribute(encrypted1));
        assertEquals(sensitiveData, converter.convertToEntityAttribute(encrypted2));
    }

    @Test
    void shouldReturnLegacyUnencryptedDataAsIsForBackwardCompatibility() {
        String plainTextData = "legacy-unencrypted-passport-777";
        String result = converter.convertToEntityAttribute(plainTextData);

        assertEquals(plainTextData, result);
    }

    @Test
    void shouldHandleNullGracefully() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }
}
