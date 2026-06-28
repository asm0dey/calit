package site.asm0dey.calit.booking;

/** Lifecycle of one guest on a booking. */
public enum GuestStatus {
    /** Active guest: receives every booking email + .ics. */
    INVITED,
    /** Guest declined via their own decline link. No further emails. */
    DECLINED,
    /** Guest was removed by the invitee during a reschedule. No further emails. */
    REMOVED
}
