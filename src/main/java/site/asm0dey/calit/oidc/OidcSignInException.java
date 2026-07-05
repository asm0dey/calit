package site.asm0dey.calit.oidc;

/** An SSO login that cannot complete for a non-technical reason the user must be told about. */
public class OidcSignInException extends RuntimeException {

    public enum Reason {
        SIGNUP_DISABLED,
        AMBIGUOUS_EMAIL
    }

    public final Reason reason;

    public OidcSignInException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
