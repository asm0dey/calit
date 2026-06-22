package site.asm0dey.calit.user;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.PasswordCredential;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.audit.AuditLog;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavioral test for the failed-login audit hook (SEC-SECRET-05). The custom
 * {@link AppUserIdentityProvider} is the authoritative auth-outcome seam, so we install a recording
 * {@link AuditLog} via QuarkusMock and assert a bad-credential authentication emits an audit event.
 * This proves the previously-silent failure path now produces a structured record.
 */
@QuarkusTest
class FailedLoginAuditTest {

    /** Records emitted audit events instead of logging, so tests can assert on them (non-brittle). */
    static class RecordingAuditLog extends AuditLog {
        final List<String[]> events = new ArrayList<>();

        @Override
        public void event(String actor, String action, String target, String sourceIp) {
            events.add(new String[]{actor, action, target, sourceIp});
        }
    }

    @Inject
    AppUserIdentityProvider provider;

    RecordingAuditLog recorder;

    @BeforeEach
    void installRecorder() {
        recorder = new RecordingAuditLog();
        QuarkusMock.installMockForType(recorder, AuditLog.class);
    }

    private static UsernamePasswordAuthenticationRequest req(String user, String pass) {
        return new UsernamePasswordAuthenticationRequest(user, new PasswordCredential(pass.toCharArray()));
    }

    @Test
    @TestTransaction
    void wrongPasswordEmitsLoginFailedAudit() {
        AppUser u = AppUser.create("audit-pw", new PasswordHasher().hash("correct-horse"), false);
        u.persistAndFlush();

        assertThrows(AuthenticationFailedException.class,
                () -> provider.authenticateBlocking(req("audit-pw", "wrong-password")));

        assertTrue(recorder.events.stream().anyMatch(e ->
                        "login-failed".equals(e[1]) && "audit-pw".equals(e[0])),
                "expected a login-failed audit event for the attempted username");
    }

    @Test
    @TestTransaction
    void unknownUserEmitsLoginFailedAudit() {
        assertThrows(AuthenticationFailedException.class,
                () -> provider.authenticateBlocking(req("no-such-user", "whatever")));

        assertEquals(1, recorder.events.stream()
                .filter(e -> "login-failed".equals(e[1])).count());
        assertTrue(recorder.events.stream().anyMatch(e ->
                "no-such-user".equals(e[0]) && "login-failed".equals(e[1])));
    }

    @Test
    @TestTransaction
    void successfulLoginEmitsLoginSuccessAudit() {
        AppUser u = AppUser.create("audit-ok", new PasswordHasher().hash("s3cret"), false);
        u.persistAndFlush();

        provider.authenticateBlocking(req("audit-ok", "s3cret"));

        assertTrue(recorder.events.stream().anyMatch(e ->
                "login-success".equals(e[1]) && "audit-ok".equals(e[0])));
    }
}
