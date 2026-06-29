package site.asm0dey.calit.google;

/** A sign-in that cannot complete for a non-technical reason the user must be told about. */
public class GoogleSignInException extends RuntimeException {

    public enum Reason {
        SIGNUP_DISABLED,
        AMBIGUOUS_EMAIL
    }

    public final Reason reason;

    public GoogleSignInException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }
}
