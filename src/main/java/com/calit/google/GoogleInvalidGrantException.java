package com.calit.google;

/**
 * The Google refresh token is permanently dead — Google returned HTTP 400 {@code invalid_grant}
 * (revoked consent, expired offline grant, password change, or 6-months-unused). Distinct from a
 * transient network/5xx error, which leaves the token possibly still valid. Extends
 * {@link IllegalStateException} so existing broad {@code catch (RuntimeException)} fail-soft paths
 * behave unchanged; only the connection probe branches on this subtype.
 */
public class GoogleInvalidGrantException extends IllegalStateException {
    public GoogleInvalidGrantException(String message, Throwable cause) {
        super(message, cause);
    }
}
