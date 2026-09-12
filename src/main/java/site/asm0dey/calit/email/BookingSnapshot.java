package site.asm0dey.calit.email;

import java.time.ZoneId;
import java.util.List;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * Immutable bundle read once in one transaction, shared by {@link EmailService} and the outbound
 * notification path. {@code hostDeliveries} is empty for a single-host booking
 * ({@code booking.groupId == null}); for a group booking it holds one entry per accepted host
 * (their own {@link OwnerSettings} + their own row of the group).
 */
public record BookingSnapshot(
        Booking booking,
        MeetingType meetingType,
        OwnerSettings owner,
        ZoneId zone,
        List<EmailService.AnswerLine> answers,
        List<HostDelivery> hostDeliveries) {}
