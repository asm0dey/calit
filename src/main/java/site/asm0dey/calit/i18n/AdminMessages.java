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

    @Message("Shared")
    String adm_nav_shared();

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

    @Message("Admin — Shared meeting types")
    String adm_shared_title();

    @Message("Admin — Co-hosting requests")
    String adm_shared_requests_title();

    @Message("Admin — Availability for {typeName}")
    String adm_shared_availability_title(String typeName);

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

    @Message("Manage")
    String adm_dashboard_btn_manage();

    // ---- Meeting types list ----

    @Message("Meeting types")
    String adm_meetingTypes_h1();

    @Message("Shared →")
    String adm_meetingTypes_shared_link();

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

    @Message(
            "Pick where the meeting happens. Google Meet generates a link after booking (requires Google connected); for the others, fill in the detail below.")
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

    @Message(
            "Weekly hours for this meeting type. Leave a day blank to use the global default; fill a day to override it for this type.")
    String adm_meetingTypes_working_hours_hint();

    @Message("to")
    String adm_meetingTypes_to();

    @Message("Date override")
    String adm_meetingTypes_section_date_override();

    @Message(
            "Optional. An override REPLACES this date's normal hours for this type. Set a date and leave the windows blank to mark it a day off; add windows to set the only bookable times.")
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

    // ---- Shared meeting types (multi-host) ----

    @Message("Shared meeting types")
    String adm_shared_h1();

    @Message("Creator")
    String adm_shared_role_creator();

    @Message("Co-host")
    String adm_shared_role_cohost();

    @Message("pending")
    String adm_shared_pending();

    @Message("reconnect Google")
    String adm_shared_reconnect();

    @Message("No shared meeting types yet.")
    String adm_shared_empty();

    @Message("Set availability")
    String adm_shared_card_setAvailability();

    @Message("Leave")
    String adm_shared_card_leave();

    @Message("Respond to invitation →")
    String adm_shared_card_respond();

    // ---- Co-host consent requests + shared availability editor (SharedMeetingsResource) ----

    @Message("Pending co-hosting invitations")
    String adm_shared_requests_h1();

    @Message("No pending co-hosting invitations.")
    String adm_shared_requests_empty();

    @Message("Invited by {creatorName}")
    String adm_shared_requests_from(String creatorName);

    @Message("Accept")
    String adm_shared_requests_accept();

    @Message("Decline")
    String adm_shared_requests_decline();

    @Message("← Back to requests")
    String adm_shared_availability_back();

    @Message("Buffers")
    String adm_shared_availability_section_buffers();

    @Message(
            "Your own buffer before/after this shared type overrides its default when set; leave blank to use the default.")
    String adm_shared_availability_buffers_hint();

    @Message("Buffer before (minutes)")
    String adm_shared_availability_buffer_before_label();

    @Message("Buffer after (minutes)")
    String adm_shared_availability_buffer_after_label();

    @Message("Save buffers")
    String adm_shared_availability_btn_save_buffers();

    @Message("Leave this meeting type")
    String adm_shared_availability_section_revoke();

    @Message(
            "You can stop co-hosting this meeting type at any time. Your own bookings and availability for it will be affected.")
    String adm_shared_availability_revoke_hint();

    @Message("Stop co-hosting")
    String adm_shared_availability_btn_revoke();

    @Message("Stop co-hosting?")
    String adm_shared_revokeConfirm_title();

    @Message("You have {count} upcoming booking(s) for this shared meeting type. What should happen to them?")
    String adm_shared_revokeConfirm_count(long count);

    @Message("Keep bookings, just stop co-hosting")
    String adm_shared_revokeConfirm_keep();

    @Message("Cancel these bookings and stop co-hosting")
    String adm_shared_revokeConfirm_cancel();

    @Message("Back without removing")
    String adm_shared_revokeConfirm_back();

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

    @Message(
            "Weekly hours for this meeting type. When any frame is set for a day, it replaces the global default hours for that day. Each day can hold several time frames; use the copy buttons to mirror one day, then Save.")
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

    @Message(
            "An override REPLACES this date's normal hours for this meeting type. Leave the windows blank to mark the date as a day off.")
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

    // ---- Meeting type detail: hosts (Task 17) ----

    @Message("Hosts")
    String adm_hosts_h2();

    @Message("Remove")
    String adm_hosts_remove();

    @Message("Add co-host")
    String adm_hosts_add();

    @Message("username")
    String adm_hosts_add_placeholder();

    @Message("pending")
    String adm_hosts_status_pending();

    @Message("accepted")
    String adm_hosts_status_accepted();

    @Message(
            "No eligible user with that username -- check spelling, or they may already be a host, disabled, or not yet fully set up.")
    String adm_hosts_error_not_eligible();

    @Message("The creator cannot be removed from their own meeting type.")
    String adm_hosts_error_creator_immutable();

    @Message("You already co-host a meeting type with the slug \"{slug}\" -- pick a different slug.")
    String adm_hosts_error_slug_owned_cohost(String slug);

    @Message("A meeting can have at most {max} hosts.")
    String adm_hosts_error_cap(int max);

    @Message("{username} already uses the slug \"{slug}\" -- pick a different slug or ask them to free it.")
    String adm_hosts_error_slug_owned(String username, String slug);

    @Message("{username} already co-hosts a type with slug \"{slug}\"")
    String adm_hosts_error_slug_cohosts(String username, String slug);

    @Message("A host already uses the slug \"{slug}\"")
    String adm_hosts_error_slug_across(String slug);

    // ---- Meeting type detail: host removal interstitial (Task 18) ----

    @Message("Remove co-host?")
    String adm_hosts_removeConfirm_title();

    @Message("{username} has {count} upcoming booking(s) on this meeting type. What should happen to them?")
    String adm_hosts_removeConfirm_count(String username, long count);

    @Message("Keep bookings, just remove co-host")
    String adm_hosts_removeConfirm_keep();

    @Message("Cancel those bookings and remove co-host")
    String adm_hosts_removeConfirm_cancel();

    @Message("Back without removing")
    String adm_hosts_removeConfirm_back();

    // ---- Availability ----

    @Message("Availability (work hours)")
    String adm_availability_h1();

    @Message(
            "Your default weekly schedule. Each day can hold several time frames. Use the copy buttons to mirror one day across the week, then Save.")
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

    @Message(
            "An override REPLACES that date's normal work hours. Adding windows sets the only bookable times; leaving the windows empty marks the whole date as a day off.")
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

    @Message(
            "Full name and email are always asked. These default extra fields apply to every meeting type that has no fields of its own. Set per-type fields from each meeting type's page.")
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

    // ---- Approve/decline from email — result page ----

    @Message("Booking request")
    String adm_approve_result_title();

    @Message("Booking approved")
    String adm_approve_approved_h1();

    @Message("The booking is confirmed and the invitee has been notified.")
    String adm_approve_approved_desc();

    @Message("Booking declined")
    String adm_approve_declined_h1();

    @Message("The request was declined and the invitee has been notified.")
    String adm_approve_declined_desc();

    @Message("Already handled")
    String adm_approve_gone_h1();

    @Message("This request was already approved, declined, or has expired.")
    String adm_approve_gone_desc();

    @Message("Back to pending requests")
    String adm_approve_back();

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

    @Message(
            "Connect Google accounts so calit can read your busy times and create events. Pick which calendars block availability, and one calendar to create booking events on.")
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

    @Message("Email")
    String users_label_email();

    @Message("Enter a valid email address.")
    String users_error_email_invalid();

    @Message("That user has already activated their account.")
    String users_error_not_pending();

    @Message("Awaiting activation")
    String users_status_pending();

    @Message("Resend invite")
    String users_btn_resend_invite();

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
