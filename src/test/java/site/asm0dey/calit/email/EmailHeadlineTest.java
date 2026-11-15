package site.asm0dey.calit.email;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.calit.email.EmailHeadline.BY_HOST;
import static site.asm0dey.calit.email.EmailHeadline.BY_INVITEE;
import static site.asm0dey.calit.email.EmailHeadline.PASSIVE;
import static site.asm0dey.calit.email.EmailHeadline.SELF;
import org.junit.jupiter.api.Test;

class EmailHeadlineTest {
    @Test
    void hostWhoActedReadsSelf() {
        assertThat(EmailHeadline.of(true, true, true)).isEqualTo(SELF);
    }

    @Test
    void hostWhoDidNotActReadsPassive() {
        assertThat(EmailHeadline.of(true, true, false)).isEqualTo(PASSIVE);
    }

    @Test
    void inviteeOfHostActionReadsByHost() {
        assertThat(EmailHeadline.of(true, false, true)).isEqualTo(BY_HOST);
        assertThat(EmailHeadline.of(true, false, false)).isEqualTo(BY_HOST);
    }

    @Test
    void hostOfInviteeActionReadsByInvitee() {
        assertThat(EmailHeadline.of(false, true, true)).isEqualTo(BY_INVITEE);
        assertThat(EmailHeadline.of(false, true, false)).isEqualTo(BY_INVITEE);
    }

    @Test
    void inviteeWhoActedReadsPassive() {
        assertThat(EmailHeadline.of(false, false, true)).isEqualTo(PASSIVE);
        assertThat(EmailHeadline.of(false, false, false)).isEqualTo(PASSIVE);
    }

    // EmailService passes hostActed = byOwner && groupId == null: a group cancel by a host is passive for every host.
    @Test
    void groupCancelIsPassiveForEveryHost() {
        assertThat(EmailHeadline.of(true, true, false)).isEqualTo(PASSIVE);
    }
}
