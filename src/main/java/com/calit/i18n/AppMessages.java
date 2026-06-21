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
    @Message("calit gives every user their own scheduling space — a personal booking page, availability, and Google Calendar — running entirely on")
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

    @Message("A public page for invitees, a personal landing for every user, and an owner console to run it all — each isolated to its owner, served from the same self-hosted instance.")
    String pub_landing_gallery_lede();

    @Message("The owner console")
    String pub_landing_cap_console_title();

    /** Gallery caption for the owner console card. Plain text — /me rendered separately. */
    @Message("Manage meeting types, availability, booking fields, Google sync, and — for admins — the whole team's users at")
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

    @Message("Everything you expect from a scheduling tool — plus true multi-tenancy and the peace of mind of owning your data.")
    String pub_landing_features_lede();

    @Message("Isolation")
    String pub_landing_feat_isolation_k();

    @Message("Per-user tenancy")
    String pub_landing_feat_isolation_h3();

    @Message("Every meeting type, booking, and setting carries an owner. One user can never see or touch another's data.")
    String pub_landing_feat_isolation_p();

    @Message("Calendar")
    String pub_landing_feat_calendar_k();

    @Message("Google sync & Meet")
    String pub_landing_feat_calendar_h3();

    @Message("Connect each user's own Google account. Bookings create events and auto-generate a Meet link — or run fully degraded.")
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

    @Message("Passwords hashed with argon2id, stateless encrypted cookies, instant lockout — no embedded admin password.")
    String pub_landing_feat_trust_p();

    @Message("Defense")
    String pub_landing_feat_defense_k();

    @Message("Abuse protection")
    String pub_landing_feat_defense_h3();

    @Message("Cloudflare Turnstile, a honeypot, and a per-email daily cap guard every public booking form out of the box.")
    String pub_landing_feat_defense_p();

    @Message("Ops")
    String pub_landing_feat_ops_k();

    @Message("One binary + Postgres")
    String pub_landing_feat_ops_h3();

    @Message("A single Quarkus app and a database. Self-host it anywhere, invite your team, done. Opt-in public sign-up too.")
    String pub_landing_feat_ops_p();

    @Message("Get started")
    String pub_landing_close_eyebrow();

    @Message("Spin up your own in minutes.")
    String pub_landing_close_h2();

    @Message("Point it at a Postgres database and open the site — the first visit creates your admin account. No license, no waitlist, no seat math.")
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

    @Message("Thanks, {inviteeName}. Your requested time is held while {meetingTypeName}'s owner reviews it. You'll get an email once it's approved or declined.")
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

    @Message("Booking cancelled: {meetingTypeName}")
    String email_cancelled_subject(String meetingTypeName);

    @Message("Reminder: {meetingTypeName}")
    String email_reminder_subject(String meetingTypeName);

    // ---- Email date/time formatting ----

    /** strftime-like pattern used to format booking date/time in email bodies. */
    @Message("EEEE, d MMMM yyyy 'at' HH:mm")
    String email_datetime_pattern();
}
