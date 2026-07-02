package site.asm0dey.calit.i18n;

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
// S100: snake_case method names are intentional — Qute maps each @Message method name to its .properties key.
@SuppressWarnings("java:S100")
public interface AppMessages {

    @Message("Cancel")
    String common_cancel();

    @Message("Language")
    String common_language();

    // ---- Landing page (index.html at /) ----

    @Message("calit — scheduling you own")
    String pub_index_title();

    @Message("Product")
    String pub_landing_nav_product();

    @Message("Features")
    String pub_landing_nav_features();

    @Message("Signed in as")
    String pub_landing_signed_in_as();

    @Message("Settings")
    String pub_landing_nav_settings();

    @Message("Log out")
    String pub_landing_nav_logout();

    @Message("Your dashboard")
    String pub_landing_nav_dashboard();

    @Message("Sign in")
    String pub_landing_nav_signin();

    @Message("Open your instance")
    String pub_landing_nav_open_instance();

    @Message("Self-hosted · Open source · Multi-user")
    String pub_landing_eyebrow();

    /** Text before the italic emphasis in the hero h1. */
    @Message("The scheduling app you")
    String pub_landing_hero_h1_pre();

    /** The italic-emphasized fragment of the hero h1. */
    @Message("actually own")
    String pub_landing_hero_h1_em();

    /** Hero sub-heading, fragment before &lt;strong&gt;. */
    @Message(
            "calit gives every user their own scheduling space — a personal booking page, availability, and Google Calendar — running entirely on")
    String pub_landing_hero_sub_pre();

    /** The strong-emphasised word in the hero sub ("your" server). */
    @Message("your")
    String pub_landing_hero_sub_strong();

    /** Hero sub-heading, fragment after &lt;strong&gt;. */
    @Message("server. No SaaS, no per-seat pricing, no lock-in.")
    String pub_landing_hero_sub_post();

    @Message("Go to your dashboard")
    String pub_landing_cta_dashboard();

    @Message("See it in action")
    String pub_landing_cta_gallery();

    @Message("argon2id auth")
    String pub_landing_trust_argon();

    @Message("Google Meet links")
    String pub_landing_trust_meet();

    @Message("approval flows")
    String pub_landing_trust_approvals();

    @Message("one binary + Postgres")
    String pub_landing_trust_binary();

    @Message("A look inside")
    String pub_landing_gallery_eyebrow();

    @Message("One app. Three surfaces.")
    String pub_landing_gallery_h2();

    @Message(
            "A public page for invitees, a personal landing for every user, and an owner console to run it all — each isolated to its owner, served from the same self-hosted instance.")
    String pub_landing_gallery_lede();

    @Message("The owner console")
    String pub_landing_cap_console_title();

    /** Gallery caption for the owner console card. Plain text — /me rendered separately. */
    @Message(
            "Manage meeting types, availability, booking fields, Google sync, and — for admins — the whole team's users at")
    String pub_landing_cap_console_desc_pre();

    /** Gallery caption for the per-user landing card. Plain text — /their-name rendered separately. */
    @Message("Every user gets")
    String pub_landing_cap_landing_pre();

    @Message("their own active meeting types, nobody else's.")
    String pub_landing_cap_landing_post();

    @Message("Each user's landing")
    String pub_landing_cap_landing_title();

    @Message("Confirmed in a tap")
    String pub_landing_cap_confirmed_title();

    @Message("Invitees pick a slot in their own timezone; both sides get an email and a Meet link.")
    String pub_landing_cap_confirmed_desc();

    @Message("Why calit")
    String pub_landing_features_eyebrow();

    @Message("Built to be run, not rented.")
    String pub_landing_features_h2();

    @Message(
            "Everything you expect from a scheduling tool — plus true multi-tenancy and the peace of mind of owning your data.")
    String pub_landing_features_lede();

    @Message("Isolation")
    String pub_landing_feat_isolation_k();

    @Message("Per-user tenancy")
    String pub_landing_feat_isolation_h3();

    @Message(
            "Every meeting type, booking, and setting carries an owner. One user can never see or touch another's data.")
    String pub_landing_feat_isolation_p();

    @Message("Calendar")
    String pub_landing_feat_calendar_k();

    @Message("Google sync & Meet")
    String pub_landing_feat_calendar_h3();

    @Message(
            "Connect each user's own Google account. Bookings create events and auto-generate a Meet link — or run fully degraded.")
    String pub_landing_feat_calendar_p();

    @Message("Control")
    String pub_landing_feat_control_k();

    @Message("Approvals & limits")
    String pub_landing_feat_control_h3();

    @Message("Hold meetings as pending until approved, with per-type buffers, minimum notice, and a booking horizon.")
    String pub_landing_feat_control_p();

    @Message("Trust")
    String pub_landing_feat_trust_k();

    @Message("Real authentication")
    String pub_landing_feat_trust_h3();

    @Message(
            "Passwords hashed with argon2id, stateless encrypted cookies, instant lockout — no embedded admin password.")
    String pub_landing_feat_trust_p();

    @Message("Defense")
    String pub_landing_feat_defense_k();

    @Message("Abuse protection")
    String pub_landing_feat_defense_h3();

    @Message(
            "Cloudflare Turnstile, a honeypot, and a per-email daily cap guard every public booking form out of the box.")
    String pub_landing_feat_defense_p();

    @Message("Ops")
    String pub_landing_feat_ops_k();

    @Message("One binary + Postgres")
    String pub_landing_feat_ops_h3();

    @Message(
            "A single Quarkus app and a database. Self-host it anywhere, invite your team, done. Opt-in public sign-up too.")
    String pub_landing_feat_ops_p();

    @Message("Get started")
    String pub_landing_close_eyebrow();

    @Message("Spin up your own in minutes.")
    String pub_landing_close_h2();

    @Message(
            "Point it at a Postgres database and open the site — the first visit creates your admin account. No license, no waitlist, no seat math.")
    String pub_landing_close_p();

    @Message("Open your dashboard")
    String pub_landing_close_cta_dashboard();

    @Message("Explore features")
    String pub_landing_close_cta_features();

    @Message("Self-hosted scheduling · your data never leaves your server")
    String pub_landing_footer_meta();

    // ---- User landing page (landing.html at /{username}) ----

    @Message("Book a meeting")
    String pub_user_title();

    @Message("Pick a meeting type to see available times.")
    String pub_user_pick_type();

    @Message("No meeting types are currently available.")
    String pub_user_no_types();

    @Message("Choose a time")
    String pub_user_choose_time();

    // ---- Booking page (book.html) ----

    @Message("Book —")
    String pub_book_title_prefix();

    @Message("Guests (optional)")
    String pub_book_guests_label();

    @Message("Type an email and press Enter")
    String pub_book_guests_hint();

    // ---- Guest decline pages ----

    @Message("Decline invitation")
    String pub_guest_decline_confirm_title();

    @Message("Decline this invitation?")
    String pub_guest_decline_confirm_h1();

    @Message("Invitation for:")
    String pub_guest_decline_invitation_for();

    @Message("You'll be removed from this meeting and won't receive further updates.")
    String pub_guest_decline_confirm_desc();

    @Message("Decline")
    String pub_guest_decline_confirm_btn();

    @Message("Keep my spot")
    String pub_guest_decline_keep_btn();

    @Message("Invitation declined")
    String pub_guest_declined_title();

    @Message("You've declined")
    String pub_guest_declined_h1();

    @Message("You've been removed from this meeting. The organizer has been notified.")
    String pub_guest_declined_desc();

    @Message("Done")
    String pub_guest_declined_btn();

    @Message("← All meeting types")
    String pub_book_back();

    @Message("Select a Date & Time")
    String pub_book_select_datetime();

    @Message("No available times right now. Please check back later.")
    String pub_book_no_times();

    @Message("Your name")
    String pub_book_your_name();

    @Message("Your email")
    String pub_book_your_email();

    @Message("Location:")
    String pub_book_location_label();

    @Message("Google Meet — link sent after booking")
    String pub_book_meet_hint();

    @Message("This meeting requires owner approval — you'll send a request and be notified once it's approved.")
    String pub_book_approval_hint();

    @Message("Request")
    String pub_book_btn_request();

    @Message("Confirm booking")
    String pub_book_btn_confirm();

    // ---- Confirmation page (confirmation.html) ----

    @Message("Request sent")
    String pub_conf_title_pending();

    @Message("Booking confirmed")
    String pub_conf_title_confirmed();

    @Message("Request sent — pending owner approval")
    String pub_conf_h1_pending();

    @Message(
            "Thanks, {inviteeName}. Your requested time is held while {meetingTypeName}'s owner reviews it. You'll get an email once it's approved or declined.")
    String pub_conf_pending_desc(String inviteeName, String meetingTypeName);

    @Message("You're booked, {inviteeName}!")
    String pub_conf_h1_confirmed(String inviteeName);

    @Message("When:")
    String pub_conf_when_label();

    @Message("Google Meet:")
    String pub_conf_meet_label();

    @Message("Location:")
    String pub_conf_location_label();

    @Message("A request confirmation email is on its way to {inviteeEmail}.")
    String pub_conf_pending_email(String inviteeEmail);

    @Message("A confirmation email is on its way to {inviteeEmail}.")
    String pub_conf_confirmed_email(String inviteeEmail);

    @Message("Need to change or cancel this booking?")
    String pub_conf_manage_link();

    // ---- Cancelled page (cancelled.html) ----

    @Message("Booking cancelled")
    String pub_cancelled_title();

    @Message("Your booking is cancelled")
    String pub_cancelled_h1();

    @Message("The meeting has been cancelled and the calendar event removed.")
    String pub_cancelled_desc();

    @Message("Book a different time")
    String pub_cancelled_btn();

    // ---- Public — booking-summary labels + invitee cancel confirmation ----

    @Message("Meeting:")
    String pub_booking_meeting_label();

    @Message("When:")
    String pub_booking_when_label();

    @Message("Cancel booking")
    String pub_cancel_confirm_title();

    @Message("Cancel this booking?")
    String pub_cancel_confirm_h1();

    @Message("This frees the slot and notifies everyone. It can't be undone.")
    String pub_cancel_confirm_desc();

    @Message("Confirm cancellation")
    String pub_cancel_confirm_btn();

    @Message("Keep booking")
    String pub_cancel_keep_btn();

    // ---- Manage booking page (manage.html) ----

    @Message("Manage booking")
    String pub_manage_title();

    @Message("Manage your booking")
    String pub_manage_h1();

    @Message("Currently:")
    String pub_manage_currently_label();

    @Message("For:")
    String pub_manage_for_label();

    @Message("Reschedule")
    String pub_manage_h2_reschedule();

    @Message("No alternative times available.")
    String pub_manage_no_alternatives();

    @Message("Reschedule to selected time")
    String pub_manage_btn_reschedule();

    @Message("Edit name & description")
    String pub_edit_details_h2();

    @Message("Meeting name")
    String pub_edit_details_name_label();

    @Message("Description")
    String pub_edit_details_desc_label();

    @Message("Save changes")
    String pub_edit_details_btn();

    @Message("Cancel")
    String pub_manage_h2_cancel();

    @Message("Cancel this booking")
    String pub_manage_btn_cancel();

    // ---- Unavailable page (unavailable.html) ----

    @Message("Scheduling temporarily unavailable")
    String pub_unavailable_title();

    @Message("Scheduling temporarily unavailable")
    String pub_unavailable_h1();

    @Message("This calendar can't be reached right now, so new bookings are paused. Please check back soon.")
    String pub_unavailable_desc();

    // ---- Not-ready page (notReady.html) ----

    @Message("Not available yet")
    String pub_not_ready_title();

    @Message("This booking page isn't ready yet")
    String pub_not_ready_h1();

    @Message("The owner hasn't finished setting up calit. Please check back soon.")
    String pub_not_ready_desc();

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

    @Message("Booking updated: {meetingTypeName}")
    String email_updated_subject(String meetingTypeName);

    @Message("Booking cancelled: {meetingTypeName}")
    String email_cancelled_subject(String meetingTypeName);

    @Message("Reminder: {meetingTypeName}")
    String email_reminder_subject(String meetingTypeName);

    @Message("Reset your calit password")
    String email_password_reset_subject();

    @Message("Action needed: reconnect your Google Calendar")
    String email_google_disconnected_subject();

    // ---- Email date/time formatting ----

    /** strftime-like pattern used to format booking date/time in email bodies. */
    @Message("EEEE, d MMMM yyyy 'at' HH:mm")
    String email_datetime_pattern();

    // ---- Email body — shared labels ----

    /** Greeting line used in most booking emails. */
    @Message("Hi {name},")
    String email_body_greeting(String name);

    @Message("Meeting:")
    String email_body_meeting_label();

    @Message("When:")
    String email_body_when_label();

    @Message("Duration:")
    String email_body_duration_label();

    @Message("{minutes} minutes")
    String email_body_duration_minutes(int minutes);

    @Message("Google Meet:")
    String email_body_meet_label();

    @Message("Location:")
    String email_body_location_label();

    @Message("Your answers:")
    String email_body_your_answers_label();

    @Message("Manage your booking")
    String email_body_manage_link_text();

    @Message("Reschedule or cancel")
    String email_body_owner_manage_link_text();

    @Message("You're invited to a meeting")
    String email_guest_invite_title();

    @Message("{inviterName} has invited you to a meeting.")
    String email_guest_invite_body(String inviterName);

    @Message("Can't attend? Decline this invitation")
    String email_guest_decline_link_text();

    @Message("Meeting cancelled")
    String email_guest_cancel_title();

    @Message("This meeting has been cancelled. It has been removed from your calendar.")
    String email_guest_cancel_body();

    @Message("A guest declined: {meetingTypeName}")
    String email_guest_declined_subject(String meetingTypeName);

    @Message("A guest can't attend")
    String email_guest_declined_title();

    @Message("{guestEmail} declined your meeting invitation. You may want to reschedule.")
    String email_guest_declined_body(String guestEmail);

    @Message("Requested time:")
    String email_body_requested_time_label();

    // ---- Email body — confirmation ----

    @Message("Booking confirmed")
    String email_confirmation_title();

    @Message("Your booking is confirmed.")
    String email_confirmation_body();

    // ---- Email body — requested ----

    @Message("Booking request received")
    String email_requested_title();

    @Message("Your booking request has been received and is awaiting confirmation.")
    String email_requested_body();

    // ---- Email body — reminder ----

    @Message("Booking reminder")
    String email_reminder_title();

    @Message("This is a reminder of your upcoming meeting.")
    String email_reminder_body();

    // ---- Email body — cancellation ----

    @Message("Booking cancelled")
    String email_cancellation_title();

    @Message("Your booking has been cancelled.")
    String email_cancellation_body();

    @Message("Was scheduled for:")
    String email_cancellation_was_scheduled();

    // ---- Email body — reschedule ----

    @Message("Booking rescheduled")
    String email_reschedule_title();

    @Message("Your booking has been rescheduled.")
    String email_reschedule_body();

    @Message("Previous time:")
    String email_reschedule_previous_time();

    @Message("New time:")
    String email_reschedule_new_time();

    // ---- Email body — owner-copy variants (name the invitee) ----

    @Message("{name} requested a booking. Review it to approve or decline.")
    String email_requested_body_owner(String name);

    @Message("{name} booked a meeting with you.")
    String email_confirmation_body_owner(String name);

    @Message("You declined {name}'s booking request.")
    String email_declined_body_owner(String name);

    @Message("{name} rescheduled their booking.")
    String email_reschedule_body_owner(String name);

    /** Invitee copy when the host drove the reschedule; {name} is the owner's display name. */
    @Message("{name} rescheduled your booking.")
    String email_reschedule_body_by_owner(String name);

    @Message("Booking updated")
    String email_updated_title();

    @Message("The meeting details were updated.")
    String email_updated_body_self();

    @Message("{name} updated the meeting details.")
    String email_updated_body_by_owner(String name);

    @Message("{name} updated the meeting details.")
    String email_updated_body_by_invitee(String name);

    @Message("Description:")
    String email_updated_description_label();

    @Message("{name}'s booking was cancelled.")
    String email_cancellation_body_owner(String name);

    /** Invitee copy when the host drove the cancellation; {name} is the owner's display name. */
    @Message("{name} cancelled your booking.")
    String email_cancellation_body_by_owner(String name);

    @Message("Reminder: upcoming meeting with {name}.")
    String email_reminder_body_owner(String name);

    @Message("Cancel this booking")
    String email_body_cancel_link_text();

    @Message("Approve")
    String email_body_approve_link_text();

    @Message("Decline")
    String email_body_decline_link_text();

    // ---- Email body — declined ----

    @Message("Booking declined")
    String email_declined_title();

    @Message("Unfortunately your booking request was declined.")
    String email_declined_body();

    // ---- Email body — password reset ----

    @Message("Reset your calit password")
    String email_password_reset_title();

    @Message("Hi,")
    String email_password_reset_greeting();

    @Message("Someone (hopefully you) asked to reset the password for your calit account.")
    String email_password_reset_body();

    @Message("Reset your password")
    String email_password_reset_btn();

    @Message("Or paste this link into your browser:")
    String email_paste_link_hint();

    @Message(
            "This link expires in 30 minutes and can be used once. If you didn't request this, ignore this email — your password stays unchanged.")
    String email_password_reset_expiry();

    // ---- Email body — Google disconnected ----

    @Message("Reconnect your Google Calendar")
    String email_google_disconnected_title();

    @Message("Hi,")
    String email_google_disconnected_greeting();

    @Message("calit can no longer access your Google Calendar account {accountEmail}.")
    String email_google_disconnected_body(String accountEmail);

    @Message(
            "While it stays disconnected, your booking page is paused — new bookings are blocked so nobody can book over events calit can't see.")
    String email_google_disconnected_paused();

    @Message("Reconnect Google Calendar")
    String email_google_disconnected_btn();

    @Message(
            "This usually happens when access was revoked, your password changed, or the connection sat unused for a long time. Reconnecting takes a few seconds.")
    String email_google_disconnected_why();

    // ---- Auth / bootstrap pages ----

    // -- Login page --
    @Message("Sign in — calit")
    String auth_login_title();

    @Message("Sign in")
    String auth_login_h1();

    @Message("Owner access to calit admin.")
    String auth_login_subtitle();

    @Message("Invalid credentials — try again.")
    String auth_login_invalid_credentials();

    @Message("Username")
    String auth_login_username_label();

    @Message("Password")
    String auth_login_password_label();

    @Message("Remember me on this device")
    String auth_login_remember_me();

    @Message("Sign in")
    String auth_login_submit();

    @Message("Forgot password?")
    String auth_login_forgot_password();

    @Message("or")
    String auth_login_or_divider();

    @Message("Sign in with Google")
    String auth_login_google_btn();

    @Message("No account is linked to that Google account, and sign-ups are disabled.")
    String auth_login_notice_google_signup_disabled();

    @Message("That Google email matches more than one account; sign in with your password instead.")
    String auth_login_notice_google_ambiguous();

    @Message("Google sign-in could not be completed. Please try again.")
    String auth_login_notice_google_generic();

    // -- Signup page --
    @Message("Sign up — calit")
    String auth_signup_title();

    @Message("Sign up")
    String auth_signup_h1();

    @Message("Username")
    String auth_signup_username_label();

    @Message("Password")
    String auth_signup_password_label();

    @Message("Create account")
    String auth_signup_submit();

    @Message("Already have an account? Log in")
    String auth_signup_login_link();

    // -- Forgot password page --
    @Message("Forgot password — calit")
    String auth_forgot_title();

    @Message("Forgot password")
    String auth_forgot_h1();

    @Message("If that account exists, we've emailed a link to reset its password. The link expires in 30 minutes.")
    String auth_forgot_sent_notice();

    @Message("Back to sign in")
    String auth_forgot_back_to_login();

    @Message("Enter your username and we'll email a reset link to the account's address.")
    String auth_forgot_instruction();

    @Message("Username")
    String auth_forgot_username_label();

    @Message("Email reset link")
    String auth_forgot_submit();

    // -- Reset password page --
    @Message("Reset password — calit")
    String auth_reset_title();

    @Message("Reset password")
    String auth_reset_h1();

    @Message("Enter a new password.")
    String auth_reset_enter_new_password();

    @Message("New password")
    String auth_reset_new_password_label();

    @Message("Set new password")
    String auth_reset_submit();

    @Message("This reset link is invalid or has expired.")
    String auth_reset_invalid_link();

    @Message("Request a new link")
    String auth_reset_request_new_link();

    // -- Setup (first-run bootstrap) page --
    @Message("Set up calit")
    String auth_setup_title();

    @Message("Create the first user")
    String auth_setup_h1();

    @Message("This first account is the site administrator.")
    String auth_setup_subtitle();

    @Message("Username invalid, reserved, or taken — try another.")
    String auth_setup_error();

    @Message(
            "That username can't be used — it may be invalid, reserved, or already taken. Use 2–64 lowercase letters or digits, with single hyphens between.")
    String auth_signup_error();

    @Message("Username")
    String auth_setup_username_label();

    @Message("Password")
    String auth_setup_password_label();

    @Message("Create administrator")
    String auth_setup_submit();

    // -- Google bridge page --
    @Message("Signing you in…")
    String auth_bridge_title();

    @Message("Signing you in…")
    String auth_bridge_progress();

    @Message("Continue signing in")
    String auth_bridge_noscript_btn();
}
