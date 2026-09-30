package site.asm0dey.calit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MeetingTypeExtensionsTest {
    @Test
    @TestTransaction
    void persistsWithPlan1bDefaults() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Defaults";
        t.slug = "mt-ext-defaults";
        t.durationMinutes = 30;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug(1L, "mt-ext-defaults");
        assertThat(loaded.minNoticeMinutes).isZero();
        assertThat(loaded.horizonDays).isEqualTo(60);
        assertThat(loaded.locationType).isEqualTo(MeetingType.LocationType.GOOGLE_MEET);
        assertThat(loaded.locationDetail).isNull();
        assertThat(loaded.requiresApproval).isFalse();
    }

    @Test
    @TestTransaction
    void roundTripsNonDefaultLocationAndApproval() {
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Phone Approval";
        t.slug = "mt-ext-phone";
        t.durationMinutes = 30;
        t.minNoticeMinutes = 120;
        t.horizonDays = 14;
        t.locationType = MeetingType.LocationType.PHONE;
        t.locationDetail = "+31 6 1234 5678";
        t.requiresApproval = true;
        t.persist();

        MeetingType loaded = MeetingType.findBySlug(1L, "mt-ext-phone");
        assertThat(loaded.minNoticeMinutes).isEqualTo(120);
        assertThat(loaded.horizonDays).isEqualTo(14);
        assertThat(loaded.locationType).isEqualTo(MeetingType.LocationType.PHONE);
        assertThat(loaded.locationDetail).isEqualTo("+31 6 1234 5678");
        assertThat(loaded.requiresApproval).isTrue();
    }
}
