package site.asm0dey.calit.domain;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import org.junit.jupiter.api.Test;

class MeetingTypeDefaultsTest {
    @Test
    void freshTypeHasDocumentedDefaults() {
        var t = new MeetingType();
        assertSoftly(s -> {
            s.assertThat(t.horizonDays).isEqualTo(60);
            s.assertThat(t.locationType).isEqualTo(MeetingType.LocationType.GOOGLE_MEET);
            s.assertThat(t.minNoticeMinutes).isZero();
            s.assertThat(t.bufferBeforeMinutes).isZero();
            s.assertThat(t.bufferAfterMinutes).isZero();
            s.assertThat(t.active).isTrue();
            s.assertThat(t.secret).isFalse();
            s.assertThat(t.requiresApproval).isFalse();
        });
    }
}
