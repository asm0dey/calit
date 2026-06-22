package site.asm0dey.calit.i18n;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * Admin UI string bundle. English is the default value here; German lives in
 * src/main/resources/messages/adm_de.properties keyed by method name. Missing German
 * key falls back to the English default automatically. Template namespace: {adm:key}.
 *
 * Split from AppMessages to avoid JVM method-size limits on the generated bundle resolver.
 * Keys: adm_*, google_*, users_*, mesetup_*.
 */
@MessageBundle("adm")
// S100: snake_case method names are intentional — Qute maps each @Message method name to its .properties key.
@SuppressWarnings("java:S100")
public interface AdminMessages {

    // ---- Common / shared ----

    @Message("blocked")
    String adm_common_blocked();

    // ---- Admin nav (adminBase.html) ----

    @Message("Dashboard")
    String adm_nav_dashboard();

    @Message("Pending")
    String adm_nav_pending();

    @Message("Meeting types")
    String adm_nav_meeting_types();

    @Message("Availability")
    String adm_nav_availability();

    @Message("Date overrides")
    String adm_nav_date_overrides();

    @Message("Booking fields")
    String adm_nav_booking_fields();

    @Message("Settings")
    String adm_nav_settings();

    @Message("Google")
    String adm_nav_google();

    @Message("Users")
    String adm_nav_users();

    @Message("Log out")
    String adm_nav_logout();

    @Message("+ Create")
    String adm_nav_create();

    // ---- Admin page titles ----

    @Message("Admin — Dashboard")
    String adm_dashboard_title();

    @Message("Admin — Meeting types")
    String adm_meetingTypes_title();

    @Message("Admin — ")
    String adm_meetingTypeDetail_title_prefix();

    @Message("Admin — Availability")
    String adm_availability_title();

    @Message("Admin — Date overrides")
    String adm_dateOverrides_title();

    @Message("Admin — Booking fields")
    String adm_bookingFields_title();

    @Message("Admin — Settings")
    String adm_settings_title();

    @Message("Admin — Pending approvals")
    String adm_pending_title();

    @Message("Admin — Google")
    String adm_google_title();

    @Message("Admin — Users")
    String adm_users_title();

    @Message("Welcome — set up your account")
    String mesetup_title();

    // ---- Dashboard ----

    @Message("Dashboard")
    String adm_dashboard_h1();

    @Message("Upcoming bookings")
    String adm_dashboard_upcoming_stat();

    @Message("Pending approvals")
    String adm_dashboard_pending_stat();

    @Message("Upcoming bookings")
    String adm_dashboard_h2();

    @Message("No upcoming bookings.")
    String adm_dashboard_no_upcoming();

    // ---- Meeting types list ----

    @Message("Meeting types")
    String adm_meetingTypes_h1();

    @Message("secret")
    String adm_meetingTypes_badge_secret();

    @Message("inactive")
    String adm_meetingTypes_badge_inactive();

    @Message("approval")
    String adm_meetingTypes_badge_approval();

    @Message("min notice")
    String adm_meetingTypes_min_notice();

    @Message("horizon")
    String adm_meetingTypes_horizon();

    @Message("days")
    String adm_meetingTypes_days();

    @Message("slot interval")
    String adm_meetingTypes_slot_interval();

    @Message("min")
    String adm_meetingTypes_min();

    @Message("buffer")
    String adm_meetingTypes_buffer();

    @Message("Copy booking link")
    String adm_meetingTypes_copy_link_aria();

    @Message("Edit")
    String adm_meetingTypes_btn_edit();

    @Message("Deactivate")
    String adm_meetingTypes_btn_deactivate();

    @Message("Activate")
    String adm_meetingTypes_btn_activate();

    @Message("Delete")
    String adm_meetingTypes_btn_delete();

    @Message("Create meeting type")
    String adm_meetingTypes_create_h2();

    @Message("Basics")
    String adm_meetingTypes_section_basics();

    @Message("Name")
    String adm_meetingTypes_label_name();

    @Message("Slug")
    String adm_meetingTypes_label_slug();

    @Message("(blank = auto from name)")
    String adm_meetingTypes_slug_hint();

    @Message("Secret (hidden from public landing)")
    String adm_meetingTypes_label_secret();

    @Message("Requires owner approval (hold as pending)")
    String adm_meetingTypes_label_approval();

    @Message("Duration")
    String adm_meetingTypes_section_duration();

    @Message("Duration (minutes)")
    String adm_meetingTypes_label_duration();

    @Message("Slot interval (minutes, blank = back-to-back)")
    String adm_meetingTypes_label_slot_interval();

    @Message("Buffer before (minutes)")
    String adm_meetingTypes_label_buffer_before();

    @Message("Buffer after (minutes)")
    String adm_meetingTypes_label_buffer_after();

    @Message("Location")
    String adm_meetingTypes_section_location();

    @Message("Pick where the meeting happens. Google Meet generates a link after booking (requires Google connected); for the others, fill in the detail below.")
    String adm_meetingTypes_location_hint();

    @Message("Location detail (phone / address / custom; ignored for Google Meet)")
    String adm_meetingTypes_label_location_detail();

    @Message("Scheduling limits")
    String adm_meetingTypes_section_limits();

    @Message("Min scheduling notice (minutes)")
    String adm_meetingTypes_label_min_notice();

    @Message("Booking horizon (days)")
    String adm_meetingTypes_label_horizon();

    @Message("Working hours")
    String adm_meetingTypes_section_working_hours();

    @Message("Weekly hours for this meeting type. Leave a day blank to use the global default; fill a day to override it for this type.")
    String adm_meetingTypes_working_hours_hint();

    @Message("to")
    String adm_meetingTypes_to();

    @Message("Date override")
    String adm_meetingTypes_section_date_override();

    @Message("Optional. An override REPLACES this date's normal hours for this type. Set a date and leave the windows blank to mark it a day off; add windows to set the only bookable times.")
    String adm_meetingTypes_date_override_hint();

    @Message("Date")
    String adm_meetingTypes_label_date();

    @Message("Bookable windows (leave all blank = day off)")
    String adm_meetingTypes_windows_legend();

    @Message("Window 1")
    String adm_meetingTypes_window_1();

    @Message("Window 2")
    String adm_meetingTypes_window_2();

    @Message("Window 3")
    String adm_meetingTypes_window_3();

    @Message("Create")
    String adm_meetingTypes_btn_create();

    @Message("Link copied")
    String adm_meetingTypes_toast_copied();

    // ---- Meeting type detail ----

    @Message("← All meeting types")
    String adm_detail_back();

    @Message("Basics")
    String adm_detail_section_basics();

    @Message("Name")
    String adm_detail_label_name();

    @Message("Slug")
    String adm_detail_label_slug();

    @Message("(blank = auto from name)")
    String adm_detail_slug_hint();

    @Message("Duration (minutes)")
    String adm_detail_label_duration();

    @Message("Buffer before (minutes)")
    String adm_detail_label_buffer_before();

    @Message("Buffer after (minutes)")
    String adm_detail_label_buffer_after();

    @Message("Slot interval (minutes, blank = back-to-back)")
    String adm_detail_label_slot_interval();

    @Message("Min scheduling notice (minutes)")
    String adm_detail_label_min_notice();

    @Message("Booking horizon (days)")
    String adm_detail_label_horizon();

    @Message("Location")
    String adm_detail_label_location();

    @Message("Location detail (phone / address / custom; ignored for Google Meet)")
    String adm_detail_label_location_detail();

    @Message("Secret (hidden from public landing)")
    String adm_detail_label_secret();

    @Message("Requires owner approval")
    String adm_detail_label_approval();

    @Message("Save changes")
    String adm_detail_btn_save();

    @Message("Booking fields")
    String adm_detail_section_fields();

    @Message("These are asked only for this meeting type, in addition to the always-present name and email.")
    String adm_detail_fields_hint();

    @Message("required")
    String adm_detail_badge_required();

    @Message("Label")
    String adm_detail_label_field_label();

    @Message("Field key")
    String adm_detail_label_field_key();

    @Message("Type")
    String adm_detail_label_field_type();

    @Message("Required")
    String adm_detail_label_field_required();

    @Message("Position")
    String adm_detail_label_field_position();

    @Message("Add field")
    String adm_detail_btn_add_field();

    @Message("Working hours")
    String adm_detail_section_working_hours();

    @Message("Weekly hours for this meeting type. When any frame is set for a day, it replaces the global default hours for that day. Each day can hold several time frames; use the copy buttons to mirror one day, then Save.")
    String adm_detail_working_hours_hint();

    @Message("to")
    String adm_detail_to();

    @Message("+ Frame")
    String adm_detail_frame_add();

    @Message("Copy to all days")
    String adm_detail_copy_all();

    @Message("Copy to weekdays")
    String adm_detail_copy_weekdays();

    @Message("Save working hours")
    String adm_detail_btn_save_hours();

    @Message("Date overrides")
    String adm_detail_section_overrides();

    @Message("An override REPLACES this date's normal hours for this meeting type. Leave the windows blank to mark the date as a day off.")
    String adm_detail_overrides_hint();

    @Message("day off")
    String adm_detail_badge_day_off();

    @Message("Date")
    String adm_detail_label_date();

    @Message("Bookable windows (leave all blank = day off)")
    String adm_detail_windows_legend();

    @Message("Window 1")
    String adm_detail_window_1();

    @Message("Window 2")
    String adm_detail_window_2();

    @Message("Window 3")
    String adm_detail_window_3();

    @Message("Save override")
    String adm_detail_btn_save_override();

    @Message("Delete")
    String adm_detail_btn_delete();

    @Message("Remove frame")
    String adm_detail_remove_frame_aria();

    // ---- Availability ----

    @Message("Availability (work hours)")
    String adm_availability_h1();

    @Message("Your default weekly schedule. Each day can hold several time frames. Use the copy buttons to mirror one day across the week, then Save.")
    String adm_availability_hint();

    @Message("+ Frame")
    String adm_availability_frame_add();

    @Message("Copy to all days")
    String adm_availability_copy_all();

    @Message("Copy to weekdays")
    String adm_availability_copy_weekdays();

    @Message("to")
    String adm_availability_to();

    @Message("Remove frame")
    String adm_availability_remove_frame_aria();

    @Message("Save schedule")
    String adm_availability_btn_save();

    // ---- Date overrides ----

    @Message("Date-specific overrides")
    String adm_dateOverrides_h1();

    @Message("An override REPLACES that date's normal work hours. Adding windows sets the only bookable times; leaving the windows empty marks the whole date as a day off.")
    String adm_dateOverrides_hint();

    @Message("day off")
    String adm_dateOverrides_badge_day_off();

    @Message("global")
    String adm_dateOverrides_global();

    @Message("Delete")
    String adm_dateOverrides_btn_delete();

    @Message("Add an override")
    String adm_dateOverrides_add_h2();

    @Message("Date")
    String adm_dateOverrides_label_date();

    @Message("Applies to")
    String adm_dateOverrides_label_applies_to();

    @Message("All (global)")
    String adm_dateOverrides_option_all_global();

    @Message("Bookable windows (leave all blank = day off)")
    String adm_dateOverrides_windows_legend();

    @Message("Window 1")
    String adm_dateOverrides_window_1();

    @Message("Window 2")
    String adm_dateOverrides_window_2();

    @Message("Window 3")
    String adm_dateOverrides_window_3();

    @Message("Save override")
    String adm_dateOverrides_btn_save();

    @Message("to")
    String adm_dateOverrides_to();

    // ---- Booking fields ----

    @Message("Default booking fields")
    String adm_bookingFields_h1();

    @Message("Full name and email are always asked. These default extra fields apply to every meeting type that has no fields of its own. Set per-type fields from each meeting type's page.")
    String adm_bookingFields_hint();

    @Message("required")
    String adm_bookingFields_badge_required();

    @Message("position")
    String adm_bookingFields_position_prefix();

    @Message("Delete")
    String adm_bookingFields_btn_delete();

    @Message("Add a field")
    String adm_bookingFields_add_h2();

    @Message("Label")
    String adm_bookingFields_label_label();

    @Message("Field key")
    String adm_bookingFields_label_key();

    @Message("Type")
    String adm_bookingFields_label_type();

    @Message("Required")
    String adm_bookingFields_label_required();

    @Message("Position")
    String adm_bookingFields_label_position();

    @Message("Add field")
    String adm_bookingFields_btn_add();

    // ---- Pending approvals ----

    @Message("Pending approvals")
    String adm_pending_h1();

    @Message("No requests are awaiting approval.")
    String adm_pending_empty();

    @Message("Approve")
    String adm_pending_btn_approve();

    @Message("Decline")
    String adm_pending_btn_decline();

    // ---- Settings ----

    @Message("Owner settings")
    String adm_settings_h1();

    @Message("Language")
    String adm_settings_language();

    @Message("Name")
    String adm_settings_label_name();

    @Message("Email")
    String adm_settings_label_email();

    @Message("Timezone")
    String adm_settings_label_timezone();

    @Message("Send me (the owner) email notifications for bookings")
    String adm_settings_label_notifications();

    @Message("Save")
    String adm_settings_btn_save();

    @Message("Reminder lead:")
    String adm_settings_reminder_lead_prefix();

    @Message("minutes before the meeting")
    String adm_settings_reminder_lead_suffix();

    @Message("(set via the REMINDER_LEAD_MINUTES environment variable)")
    String adm_settings_reminder_lead_env();

    // ---- Google Calendar ----

    @Message("Google Calendar")
    String google_h1();

    @Message("Connect Google accounts so calit can read your busy times and create events. Pick which calendars block availability, and one calendar to create booking events on.")
    String google_hint();

    @Message("Connect a Google account")
    String google_btn_connect();

    @Message("Couldn't reach Google for one or more accounts. Reconnect the flagged account, then reload.")
    String google_load_error();

    @Message("No Google accounts connected yet.")
    String google_no_accounts();

    @Message("needs reconnect")
    String google_badge_needs_reconnect();

    @Message("couldn't load — try reload")
    String google_badge_load_failed();

    @Message("Disconnect")
    String google_btn_disconnect();

    @Message("Calendar")
    String google_table_calendar();

    @Message("Read busy")
    String google_table_read_busy();

    @Message("Write events here")
    String google_table_write_events();

    @Message("Reconnect to edit. Your saved selection is shown and kept when you save other accounts.")
    String google_reconnect_hint();

    @Message("Save calendar selection")
    String google_btn_save_selection();

    @Message("Disconnect this Google account? Its calendar selections are removed.")
    String google_disconnect_confirm();

    @Message("Pick a new write target on another account first")
    String google_disabled_title();

    // ---- Users ----

    @Message("Users")
    String users_h1();

    @Message("Create user")
    String users_create_h2();

    @Message("Username")
    String users_label_username();

    @Message("Temporary password")
    String users_label_temp_password();

    @Message("Create user")
    String users_btn_create();

    @Message("Username")
    String users_th_username();

    @Message("Admin")
    String users_th_admin();

    @Message("Status")
    String users_th_status();

    @Message("Actions")
    String users_th_actions();

    @Message("Yes")
    String users_yes();

    @Message("No")
    String users_no();

    @Message("Active")
    String users_active();

    @Message("Locked")
    String users_locked();

    @Message("Revoke admin")
    String users_btn_revoke_admin();

    @Message("Grant admin")
    String users_btn_grant_admin();

    @Message("Lock")
    String users_btn_lock();

    @Message("Unlock")
    String users_btn_unlock();

    // ---- Me setup wizard ----

    @Message("Finish setting up")
    String mesetup_h1();

    @Message("A couple of details before you start.")
    String mesetup_subtitle();

    @Message("Choose a password")
    String mesetup_h2_password();

    @Message("New password")
    String mesetup_label_new_password();

    @Message("Your details")
    String mesetup_h2_details();

    @Message("Name")
    String mesetup_label_name();

    @Message("Email")
    String mesetup_label_email();

    @Message("Timezone")
    String mesetup_label_timezone();

    @Message("Finish")
    String mesetup_btn_finish();

    @Message("Please choose a new password.")
    String mesetup_choose_new_password();
}
