package com.calit.google;

import com.calit.domain.OwnerSettings;
import com.calit.user.AppUser;
import com.calit.user.Usernames;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Maps a verified {@link GoogleIdentity} to the {@link AppUser} that should be logged in:
 *   1. known google_sub        -> that user;
 *   2. unknown sub but the id_token's VERIFIED email matches exactly one existing account
 *      -> link the sub to that account (covers a password user adopting Google);
 *   3. otherwise provision a new passwordless, not-yet-onboarded account, but only when
 *      SIGNUP_ENABLED=true; else reject with SIGNUP_DISABLED.
 * Ambiguous email (more than one same-email account) is rejected rather than guessed.
 *
 * @implNote Auto-link trusts Google's verified email against the account's OwnerSettings
 *           email, which the app itself does NOT verify (it is free-text from the settings
 *           wizard). A user who entered someone else's address could thus be linked by that
 *           address's real Google owner. Acceptable pre-public; before going public, add email
 *           verification to the settings wizard or gate auto-link behind a confirmation step.
 */
@ApplicationScoped
public class GoogleSignInService {

    @ConfigProperty(name = "calit.signup.enabled", defaultValue = "false")
    boolean signupEnabled;

    @Transactional
    public AppUser resolveOrProvision(GoogleIdentity identity) {
        AppUser bySub = AppUser.findByGoogleSub(identity.sub());
        if (bySub != null) {
            return bySub;
        }

        if (identity.emailVerified() && identity.email() != null) {
            List<Long> owners = OwnerSettings.findOwnerIdsByEmail(identity.email());
            if (owners.size() == 1) {
                AppUser linked = AppUser.findById(owners.get(0));
                linked.googleSub = identity.sub(); // managed entity -> dirty-checked in this tx
                return linked;
            }
            if (owners.size() > 1) {
                throw new GoogleSignInException(GoogleSignInException.Reason.AMBIGUOUS_EMAIL);
            }
        }

        if (!signupEnabled) {
            throw new GoogleSignInException(GoogleSignInException.Reason.SIGNUP_DISABLED);
        }
        return provision(identity);
    }

    private AppUser provision(GoogleIdentity identity) {
        String username = Usernames.uniquify(Usernames.fromEmail(identity.email()), AppUser::usernameTaken);
        AppUser u = AppUser.createGoogleUser(username, identity.sub());
        u.persist();

        // Pre-create the settings row so the first-login wizard (/me/setup) can pre-fill the email.
        // ownerName/timezone are NOT NULL; seed placeholders the user finishes in the wizard.
        OwnerSettings s = new OwnerSettings();
        s.ownerId = u.id;
        s.ownerName = "";
        // ownerEmail "" only when Google returns no email; satisfies NOT NULL until the wizard sets a real one.
        s.ownerEmail = identity.email() == null ? "" : identity.email();
        s.timezone = "UTC";
        s.persist();
        return u;
    }
}
