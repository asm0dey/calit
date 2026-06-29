package site.asm0dey.calit.user;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Username normalization, validation, and reserved-word checks. Pure (no DB). */
public final class Usernames {

    private Usernames() {}

    private static final Pattern VALID = Pattern.compile("^[a-z0-9](-?[a-z0-9])*$");
    private static final int MIN_LEN = 2;
    private static final int MAX_LEN = 64;

    private static final Set<String> RESERVED = Set.of(
            "me",
            "login",
            "logout",
            "signup",
            "setup",
            "forgot-password",
            "reset-password",
            "booking",
            "api",
            "q",
            "health",
            "calit",
            "index",
            "privacy",
            "terms");

    /** Trim + lowercase. Null-safe: null stays null. */
    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase();
    }

    /** True when value matches the handle regex and length bounds. Operates on the raw value. */
    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        var len = value.length();
        return len >= MIN_LEN && len <= MAX_LEN && VALID.matcher(value).matches();
    }

    /** True when the normalized value is a reserved word. */
    public static boolean isReserved(String value) {
        return RESERVED.contains(normalize(value));
    }

    /**
     * Normalizes {@code raw}, then rejects invalid, reserved, or already-taken handles.
     * @param taken predicate answering "is this normalized username already in use?"
     * @return the normalized, accepted username
     * @throws IllegalArgumentException if invalid, reserved, or taken
     */
    public static String validateNew(String raw, Predicate<String> taken) {
        var norm = normalize(raw);
        if (!isValid(norm)) {
            throw new IllegalArgumentException(
                    "Username must be 2-64 chars, lowercase letters/digits, single hyphens between.");
        }
        if (isReserved(norm)) {
            throw new IllegalArgumentException("That username is reserved.");
        }
        if (taken.test(norm)) {
            throw new IllegalArgumentException("That username is already taken.");
        }
        return norm;
    }

    /**
     * Best-effort handle from an email's local-part: lowercase it, drop everything outside
     * [a-z0-9-], collapse repeated hyphens, trim leading/trailing hyphens. Returns "user" when
     * the result is not a valid handle (too short, empty, etc.). The result still needs
     * {@link #uniquify} before use — it may collide with an existing username.
     */
    public static String fromEmail(String email) {
        if (email == null) {
            return "user";
        }
        var at = email.indexOf('@');
        var local = normalize(at > 0 ? email.substring(0, at) : email);
        var cleaned = trimHyphens(keepHandleChars(local));
        return isValid(cleaned) && !isReserved(cleaned) ? cleaned : "user";
    }

    /** Keep only [a-z0-9-], dropping a leading hyphen and collapsing consecutive hyphens. */
    private static String keepHandleChars(String s) {
        var sb = new StringBuilder(s.length());
        for (var i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            var allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            var repeatedHyphen = c == '-' && (sb.isEmpty() || sb.charAt(sb.length() - 1) == '-');
            if (allowed && !repeatedHyphen) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Strip leading and trailing hyphens. */
    private static String trimHyphens(String s) {
        var start = 0;
        var end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * Return {@code base} if it is a usable, free handle; otherwise replace a reserved/invalid base
     * with "user" and append "-2", "-3", … until {@code taken} reports the candidate is free.
     * Suffixed candidates are truncated so they never exceed {@link #MAX_LEN} characters.
     */
    public static String uniquify(String base, Predicate<String> taken) {
        var root = (isValid(base) && !isReserved(base)) ? normalize(base) : "user";
        if (!taken.test(root)) {
            return root;
        }
        // Leave room for a "-NN" suffix within MAX_LEN so suffixed candidates stay valid handles.
        var stem = root.length() > MAX_LEN - 4 ? trimHyphens(root.substring(0, MAX_LEN - 4)) : root;
        for (var n = 2; ; n++) {
            var candidate = stem + "-" + n;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }
}
