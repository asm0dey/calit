package site.asm0dey.calit.booking.events;

/**
 * A pending co-host row was created; the candidate must accept (or decline) before they count as a host.
 */
public record HostConsentRequested(Long meetingTypeId, Long cohostOwnerId, String consentToken) {}
