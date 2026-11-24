package com.tamada.travel.orchestrator.service.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * JPA AttributeConverter для прозрачного шифрования персональных данных (ПДн: паспорт, телефон, email)
 * алгоритмом AES-256 GCM (Galois/Counter Mode).
 * Поддерживает обратную совместимость: незашифрованные строки возвращаются без изменений.
 */
@Converter
@Component
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);
    private static final String PREFIX = "enc:v1:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static volatile byte[] masterKeyBytes = deriveKey("tamada-secret-encryption-key-256bit-ok!");

    @Autowired(required = false)
    public void setEncryptionProperties(EncryptionProperties properties) {
        if (properties != null && properties.secretKey() != null && !properties.secretKey().isBlank()) {
            masterKeyBytes = deriveKey(properties.secretKey());
        }
    }

    public static void setStaticSecretKey(String secretKey) {
        if (secretKey != null && !secretKey.isBlank()) {
            masterKeyBytes = deriveKey(secretKey);
        }
    }

    private static byte[] deriveKey(String rawSecret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(rawSecret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive encryption key", e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        if (attribute.startsWith(PREFIX)) {
            return attribute;
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKey secretKey = new SecretKeySpec(masterKeyBytes, "AES");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] plaintextBytes = attribute.getBytes(StandardCharsets.UTF_8);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return PREFIX + Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Failed to encrypt sensitive attribute: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to encrypt sensitive attribute: " + e.getMessage(), e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!dbData.startsWith(PREFIX)) {
            return dbData;
        }

        try {
            String base64Payload = dbData.substring(PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(base64Payload);

            if (combined.length < GCM_IV_LENGTH_BYTES) {
                return dbData;
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(combined);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            byteBuffer.get(iv);

            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKey secretKey = new SecretKeySpec(masterKeyBytes, "AES");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] decrypted = cipher.doFinal(ciphertext);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt attribute, returning raw data: {}", e.getMessage());
            return dbData;
        }
    }
}
