package site.asm0dey.calit.caldav;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class HostGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://10.1.2.3/",
            "http://192.168.1.10/",
            "http://169.254.169.254/",
            "http://[::1]/",
            "http://[fd00::1]/",
            "http://100.64.0.1/",
            "http://0.0.0.0/",
            "http://[::ffff:10.0.0.1]/",
            "http://224.0.0.1/",
            "http://[::a00:1]/",
            "http://[64:ff9b::a00:1]/",
            "http://[2002:a00:1::]/",
            "http://0.1.2.3/",
            "http://240.0.0.1/",
            "http://255.255.255.255/",
            "http://[64:ff9b::7f00:1]/"
    })
    void refusesPrivateAddresses(String url) {
        assertThatThrownBy(() -> HostGuard.check(URI.create(url), false))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.PRIVATE_HOST));
    }

    @Test
    void allowsPrivateWhenConfigured() {
        assertThatCode(() -> HostGuard.check(URI.create("http://192.168.1.10/"), true)).doesNotThrowAnyException();
    }

    @Test
    void allowsPublicAddress() {
        assertThatCode(() -> HostGuard.check(URI.create("https://93.184.216.34/"), false)).doesNotThrowAnyException();
    }

    @Test
    void refusesNonHttpScheme() {
        assertThatThrownBy(() -> HostGuard.check(URI.create("file:///etc/passwd"), true))
            .isInstanceOfSatisfying(CaldavException.class, e -> assertThat(e.reason())
                .isEqualTo(CaldavException.Reason.NOT_CALDAV));
    }
}
