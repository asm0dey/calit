# CalDAV Mirror events carry no attendees; calit sends the invites

A Mirror event written to a CalDAV calendar has no `ORGANIZER` and no `ATTENDEE` properties; guests' email
addresses go in the description. calit's own `.ics` is the invitation, exactly as in degraded
mode (Google differs only because Google itself sends invites). Servers that implement RFC 6638
implicit scheduling, Nextcloud among them, therefore have nothing to schedule and send no second
invitation.

## Considered options

**`SCHEDULE-AGENT=CLIENT` on the attendees** — tells a server not to schedule. Rejected: not honoured
by every server, and a server that ignores it double-invites guests.

**Attendees, and no calit `.ics`** — let the server invite. Rejected: calit cannot rely on a CalDAV
server sending anything, or sending it from an address the Invitee recognises.

## Consequences

- The CalDAV event `UID` equals the invitee `.ics` `UID` (the booking's `manageToken`). A mailbox that
  auto-imports the invitation into the same calendar merges with the Mirror event instead of
  duplicating it, since CalDAV forbids two events with one `UID` in a collection.
- `SEQUENCE` is kept at or above `booking.icsSequence`, so a later calit `.ics` update is never
  older than the stored event.
- `ORGANIZER` and `ATTENDEE` are stripped on every update, including when the event was edited by
  hand in another client.
- The Creator gets no `.ics` attachment when the event sits on their CalDAV calendar; Invitee, guests
  and Co-hosts do (ADR-0007).
