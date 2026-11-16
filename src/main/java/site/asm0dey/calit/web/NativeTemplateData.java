package site.asm0dey.calit.web;

import io.quarkus.qute.TemplateData;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.email.MailHealth;
import site.asm0dey.calit.i18n.LocaleOption;

/**
 * Types whose template members Qute would otherwise reach by reflection, which a native image cannot do
 * (#342). Two causes:
 * <ul>
 *   <li>passed to a message bundle method somewhere: Qute then generates a resolver trimmed to that one
 *       member, in native builds too (quarkusio/quarkus#58441);</li>
 *   <li>reaching a template untyped (a template initializer, an un-declared loop variable): no resolver is
 *       generated at all.</li>
 * </ul>
 * {@code @TemplateData} generates a resolver with every public member. The test-only
 * {@code NoReflectionFallbackResolver} fails the suite when a type is missing here.
 * <p>
 * ponytail: one list instead of an annotation per class, so it goes away in one commit once #58441 is fixed
 * (the untyped ones stay).
 */
@TemplateData(target = Booking.class)
@TemplateData(target = MeetingType.class)
@TemplateData(target = OwnerSettings.class)
@TemplateData(target = MailHealth.Status.class)
@TemplateData(target = EmailService.SessionLine.class)
@TemplateData(target = LocaleOption.class)
@TemplateData(target = PublicResource.SetPicker.class)
@TemplateData(target = PublicResource.PickRow.class)
@TemplateData(target = PublicResource.SetSibling.class)
@TemplateData(target = SharedMeetingsResource.PendingRequestRow.class)
final class NativeTemplateData {
    private NativeTemplateData() {
    }
}
