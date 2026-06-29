package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EmailRoleCopyTest {

    @Inject
    @Location("email/requested.html")
    Template requested;

    @Inject
    @Location("email/confirmation.html")
    Template confirmation;

    private static TemplateInstance base(Template t, String role) {
        return t.instance()
                .setLocale(Locale.ENGLISH)
                .data("lang", "en")
                .data("recipientRole", role)
                .data("recipientRoleDisplay", role)
                .data("greetingName", "invitee".equals(role) ? "Sam Invitee" : "Olivia Owner")
                .data("inviteeName", "Sam Invitee")
                .data("meetingTypeName", "Intro call")
                .data("startTime", "Wed, 1 Jul 2026, 09:00")
                .data("oldStartTime", "Tue, 30 Jun 2026, 09:00")
                .data("durationMinutes", 30)
                .data("location", null)
                .data("isMeetLink", false)
                .data("manageUrl", "https://calit.example/booking/tok/manage")
                .data("cancelUrl", "https://calit.example/booking/tok/cancel")
                .data(
                        "approveUrl",
                        "invitee".equals(role) ? null : "https://calit.example/me/bookings/42/approve?t=abc")
                .data(
                        "declineUrl",
                        "invitee".equals(role) ? null : "https://calit.example/me/bookings/42/decline?t=abc")
                .data("answers", List.of());
    }

    @Test
    void requestedOwnerCopyGreetsOwnerNamesInviteeAndLinksApproveDecline() {
        String body = base(requested, "owner").render();
        assertTrue(body.contains("Hi Olivia Owner,"), "owner greeted by name");
        assertTrue(body.contains("Sam Invitee requested"), "owner body names the invitee");
        assertTrue(body.contains("/me/bookings/42/approve?t=abc"), "owner gets the approve link");
        assertTrue(body.contains("/me/bookings/42/decline?t=abc"), "owner gets the decline link");
        assertFalse(body.contains("/booking/tok/cancel"), "owner copy has no invitee cancel link");
    }

    @Test
    void requestedInviteeCopyGreetsInviteeAndLinksManageAndCancel() {
        String body = base(requested, "invitee").render();
        assertTrue(body.contains("Hi Sam Invitee,"), "invitee greeted by name");
        assertTrue(body.contains("/booking/tok/manage"), "invitee gets the manage link");
        assertTrue(body.contains("/booking/tok/cancel"), "invitee gets the cancel link");
        assertFalse(body.contains("/approve"), "invitee copy has no approve link");
    }

    @Test
    void confirmationOwnerCopyNamesInviteeNoManageLink() {
        String body = base(confirmation, "owner").render();
        assertTrue(body.contains("Sam Invitee booked"), "owner body names the invitee");
        assertFalse(body.contains("/manage"), "owner copy has no invitee manage link");
    }
}
