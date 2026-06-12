package com.calit.crypto;

import jakarta.inject.Inject;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Transparently encrypts a String column at rest via {@link TokenCipher} (SEC-SECRET-02).
 * Quarkus enables CDI injection into JPA converters, so {@link TokenCipher} is injected.
 * {@code autoApply=false} — applied explicitly with {@code @Convert} only on token columns.
 */
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Inject
    TokenCipher cipher;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher().decrypt(dbData);
    }

    private TokenCipher cipher() {
        if (cipher == null) {
            throw new IllegalStateException("TokenCipher was not injected into EncryptedStringConverter");
        }
        return cipher;
    }
}
