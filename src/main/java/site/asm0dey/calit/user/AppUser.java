package site.asm0dey.calit.user;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * DB-backed application user. Authentication is handled by {@link AppUserIdentityProvider}
 * (a custom IdentityProvider verifying argon2id hashes via PasswordHasher), so this is a
 * plain Panache entity — NOT a security-jpa @UserDefinition (that generated a competing
 * Elytron provider that raced ours and intermittently rejected valid logins).
 */
@Entity
@Table(name = "app_user")
public class AppUser extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(unique = true, nullable = false)
    public String username;

    // Nullable: users who only sign in with Google have no password (see V11). Form-login
    // users always have one; AppUserIdentityProvider guards against verifying a null hash.
    @Column(name = "password_hash")
    public String passwordHash;

    /** Stable Google id_token "sub" linking this account to a Google identity, or null. Unique. */
    @Column(name = "google_sub", unique = true)
    public String googleSub;

    @Column(nullable = false)
    public String roles;

    @Column(name = "is_admin", nullable = false)
    public boolean isAdmin = false;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "must_change_password", nullable = false)
    public boolean mustChangePassword = false;

    @Column(name = "settings_complete", nullable = false)
    public boolean settingsComplete = false;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** Roles string kept in sync with isAdmin: admins get "user,admin", others "user". */
    private static String rolesFor(boolean admin) {
        return admin ? "user,admin" : "user";
    }

    /**
     * Factory for a new user. Normalizes the username, syncs roles with isAdmin, and stamps
     * created_at. Lifecycle flags default to false (caller sets them as needed before persist).
     */
    public static AppUser create(String username, String passwordHash, boolean admin) {
        AppUser u = new AppUser();
        u.username = Usernames.normalize(username);
        u.passwordHash = passwordHash;
        u.isAdmin = admin;
        u.roles = rolesFor(admin);
        u.createdAt = Instant.now();
        return u;
    }

    /**
     * Factory for a Google-only user: no password, non-admin, not yet onboarded. The username
     * must already be uniquified by the caller (see Usernames.uniquify); it is normalized here.
     */
    public static AppUser createGoogleUser(String username, String googleSub) {
        AppUser u = new AppUser();
        u.username = Usernames.normalize(username);
        u.passwordHash = null;
        u.googleSub = googleSub;
        u.isAdmin = false;
        u.roles = rolesFor(false);
        u.mustChangePassword = false;
        u.settingsComplete = false;
        u.createdAt = Instant.now();
        return u;
    }

    public static AppUser findByUsername(String username) {
        return find("username", Usernames.normalize(username)).firstResult();
    }

    public static AppUser findByGoogleSub(String googleSub) {
        if (googleSub == null) {
            return null;
        }
        return find("googleSub", googleSub).firstResult();
    }

    public static boolean usernameTaken(String username) {
        return count("username", Usernames.normalize(username)) > 0;
    }

    /** Toggle site-admin, keeping the roles string in sync (the augmentor/identity reads roles). */
    public void setAdmin(boolean admin) {
        this.isAdmin = admin;
        this.roles = rolesFor(admin);
    }
}
