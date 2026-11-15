package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EmailRoleCopyTest {
    @Inject
    @Location("email/requested.html")
    Template requested;
    @Inject
    @Location("email/confirmation.html")
    Template confirmation;
    @Inject
    @Location("email/cancellation.html")
    Template cancellation;

    private static TemplateInstance base(Template t, String role) {
        return t
            .instance()
            .setLocale(Locale.ENGLISH)
            .data("lang", "en")
            .data("recipientRole", role)
            .data("recipientRoleDisplay", role)
            .data("greetingName", "invitee".equals(role) ? "Sam Invitee" : "Olivia Owner")
            .data("inviteeName", "Sam Invitee")
            .data("inviteeEmail", "sam@example.com")
            .data("ownerName", "Olivia Owner")
            .data("byOwner", false)
            .data("hostSelfCancel", false)
            .data("headline", EmailHeadline.PASSIVE)
            .data("meetingTypeName", "Intro call")
            .data("startTime", "Wed, 1 Jul 2026, 09:00")
            .data("oldStartTime", "Tue, 30 Jun 2026, 09:00")
            .data("durationMinutes", 30)
            .data("location", null)
            .data("isMeetLink", false)
            .data("manageUrl", "https://calit.example/booking/tok/manage")
            .data("ownerManageUrl", "https://calit.example/me/bookings/42/manage")
            .data("cancelUrl", "https://calit.example/booking/tok/cancel")
            .data("approveUrl", "invitee".equals(role) ? null : "https://calit.example/me/bookings/42/approve?t=abc")
            .data("declineUrl", "invitee".equals(role) ? null : "https://calit.example/me/bookings/42/decline?t=abc")
            .data("answers", List.of());
    }

    @Test
    void requestedOwnerCopyGreetsOwnerNamesInviteeAndLinksApproveDecline() {
        String body = base(requested, "owner").render();
        assertThat(body)
            .as("owner greeted by name")
            .contains("Hi Olivia Owner,")
            .as("owner body names the invitee")
            .contains("Sam Invitee requested")
            .as("owner gets the approve link")
            .contains("/me/bookings/42/approve?t=abc")
            .as("owner gets the decline link")
            .contains("/me/bookings/42/decline?t=abc")
            .as("owner copy has no invitee cancel link")
            .doesNotContain("/booking/tok/cancel");
    }

    @Test
    void requestedInviteeCopyGreetsInviteeAndLinksManageAndCancel() {
        String body = base(requested, "invitee").render();
        assertThat(body)
            .as("invitee greeted by name")
            .contains("Hi Sam Invitee,")
            .as("invitee gets the manage link")
            .contains("/booking/tok/manage")
            .as("invitee gets the cancel link")
            .contains("/booking/tok/cancel")
            .as("invitee copy has no approve link")
            .doesNotContain("/approve");
    }

    @Test
    void confirmationInviteeCopyDoesNotEchoTheirOwnAddress() {
        String body = base(confirmation, "invitee").render();
        assertThat(body).as("invitee copy must not gain an Invitee: line").doesNotContain("sam@example.com");
    }

    @Test
    void hostCancelOwnerCopySaysTheHostCancelledAndNamesTheInvitee() {
        String body = base(cancellation, "owner").data("headline", EmailHeadline.of(true, true, true)).render();
        assertThat(body)
            .as("host who cancelled reads an active line naming the invitee")
            .contains("You cancelled your meeting with Sam Invitee.")
            .as("host copy must not reuse the invitee's passive string")
            .doesNotContain("Your booking has been cancelled.");
    }
}
