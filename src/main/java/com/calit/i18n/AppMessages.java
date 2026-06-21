package com.calit.i18n;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * Type-safe UI string bundle. English is the default value here; German lives in
 * src/main/resources/messages/msg_de.properties keyed by method name. Missing German
 * key falls back to the English default automatically. Template namespace: {msg:key}.
 *
 * Keys follow <area>_<screen>_<what>: pub_*, adm_*, auth_*, email_*, common_*.
 */
@MessageBundle // default namespace "msg"
public interface AppMessages {

    @Message("Cancel")
    String common_cancel();

    @Message("Language")
    String adm_settings_language();

    // ---- Email subjects ----

    @Message("Booking request received: {meetingTypeName}")
    String email_requested_subject(String meetingTypeName);

    @Message("Booking confirmed: {meetingTypeName}")
    String email_confirmed_subject(String meetingTypeName);

    @Message("Booking approved: {meetingTypeName}")
    String email_approved_subject(String meetingTypeName);

    @Message("Booking declined: {meetingTypeName}")
    String email_declined_subject(String meetingTypeName);

    @Message("Booking rescheduled: {meetingTypeName}")
    String email_rescheduled_subject(String meetingTypeName);

    @Message("Booking cancelled: {meetingTypeName}")
    String email_cancelled_subject(String meetingTypeName);

    @Message("Reminder: {meetingTypeName}")
    String email_reminder_subject(String meetingTypeName);

    /** Shorthand for tests: confirmation subject with placeholder filled. */
    @Message("Booking confirmed")
    String email_confirmation_subject();

    // ---- Email date/time formatting ----

    /** strftime-like pattern used to format booking date/time in email bodies. */
    @Message("EEEE, d MMMM yyyy 'at' HH:mm")
    String email_datetime_pattern();
}
